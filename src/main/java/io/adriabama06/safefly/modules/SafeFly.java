package io.adriabama06.safefly.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockPosSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

public class SafeFly extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<BlockPos> targetPos = sgGeneral.add(new BlockPosSetting.Builder()
        .name("target-coordinates")
        .description("The coordinates to fly to while using the Elytra.")
        .defaultValue(new BlockPos(0, 120, 0))
        .build()
    );

    private final Setting<Integer> walkRadius = sgGeneral.add(new IntSetting.Builder()
        .name("walk-radius")
        .description("Max distance (blocks) from target after landing to finish on foot. If further away (out of rockets / unreachable), skip walking.")
        .defaultValue(64)
        .min(1)
        .sliderRange(1, 256)
        .build()
    );

    private final Setting<Integer> minFireworks = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-fireworks")
        .description("Minimum fireworks required. Below this, a warning is shown (Baritone still lands on its own).")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    private enum State {
        FLYING,    // Baritone elytra-flying + landing completely on its own
        WALKING,   // Landed within radius, Baritone walking to exact spot
        BOXING,    // Building the Netherrack box (UNTOUCHED)
        IDLE
    }

    private State currentState = State.IDLE;
    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

    // Counters / guards
    private int noFireworkWarnCooldown = 0;
    private int landedTicks = 0; // ticks that the player has been on ground + not fall flying + baritone not pathing

    public SafeFly(Category category) {
        super(category, "safe-fly", "Flies to a set of coordinates with Baritone and boxes yourself in Netherrack upon arrival or when you run out of fireworks.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) return;

        BlockPos target = targetPos.get();
        info("Starting elytra flight to X: %d, Y: %d, Z: %d", target.getX(), target.getY(), target.getZ());

        currentState = State.FLYING;
        landedTicks = 0;
        noFireworkWarnCooldown = 0;

        // Set the goal first, then start Baritone's elytra process.
        // Using Baritone commands (same as typing #goto / #elytra in chat)
        // ensures we don't depend on internal setting names that vary by version.
        // CRITICAL: we do NOT cancel Baritone afterwards — Baritone must handle
        // the entire flight + landing by itself.
        baritone.getCommandManager().execute(
            String.format("goto %d %d %d", target.getX(), target.getY(), target.getZ())
        );
        baritone.getCommandManager().execute("elytra");
    }

    @Override
    public void onDeactivate() {
        if (baritone.getPathingBehavior().isPathing()) {
            baritone.getPathingBehavior().cancelEverything();
        }
        currentState = State.IDLE;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        switch (currentState) {
            case FLYING -> handleFlyingState();
            case WALKING -> handleWalkingState();
            case BOXING -> handleBoxingState();
            case IDLE -> {}
        }
    }

    /**
     * FLYING: we let Baritone do its thing (elytra flight + landing).
     * We do NOT cancel Baritone here. We only observe:
     *   - Warn once if out of fireworks (Baritone will still land safely).
     *   - When the player has been on the ground (not fall-flying) and Baritone
     *     is no longer pathing for a few consecutive ticks, we consider flight
     *     + landing fully complete.
     * Then we decide: walk to exact spot if within radius, else skip to boxing.
     */
    private void handleFlyingState() {
        // Low-fireworks warning (non-intrusive, just a reminder).
        FindItemResult fireworks = InvUtils.find(Items.FIREWORK_ROCKET);
        boolean hasEnoughFireworks = fireworks.found() && fireworks.count() >= minFireworks.get();
        if (!hasEnoughFireworks && minFireworks.get() > 0 && noFireworkWarnCooldown <= 0) {
            warning("Low on fireworks (%d left). Baritone will land shortly.", fireworks.count());
            noFireworkWarnCooldown = 100;
        }
        if (noFireworkWarnCooldown > 0) noFireworkWarnCooldown--;

        // We consider the elytra flight + landing to be finished when:
        //  1. The player is on the ground and NOT fall-flying.
        //  2. Baritone is no longer trying to path (flight/landing process
        //     has fully released control).
        // We require N consecutive stable ticks to avoid false positives at
        // takeoff, during fireworks boosts, or from brief lag spikes.
        boolean onGround = mc.player.onGround() && !mc.player.isFallFlying();
        boolean baritoneDone = !baritone.getPathingBehavior().isPathing();

        if (onGround && baritoneDone) {
            landedTicks++;
        } else {
            landedTicks = 0;
        }

        // Require a few stable ticks to avoid false positives at takeoff
        // or during brief landings/lag spikes.
        if (landedTicks < 10) return;

        BlockPos target = targetPos.get();
        BlockPos playerPos = mc.player.blockPosition();
        double dist = Math.sqrt(playerPos.distSqr(target));
        int radius = walkRadius.get();

        info("Landed. Distance to target: %.1f blocks.", dist);

        if (dist <= radius) {
            info("Within walk radius (%d). Walking to exact spot...", radius);
            // Stop the elytra process (it's finished anyway) and start a normal
            // on-foot goto to the exact coordinates.
            baritone.getPathingBehavior().cancelEverything();
            baritone.getCommandManager().execute(
                String.format("goto %d %d %d", target.getX(), target.getY(), target.getZ())
            );
            landedTicks = 0;
            currentState = State.WALKING;
        } else {
            // Too far — almost certainly out of fireworks or couldn't reach the
            // target. Per user request, do NOT attempt to walk all the way.
            warning("Landed too far from target (%.1f blocks > %d). Skipping walk, building box at current location.", dist, radius);
            baritone.getPathingBehavior().cancelEverything();
            currentState = State.BOXING;
        }
    }

    /**
     * WALKING: Baritone is walking us to the exact block on foot.
     * Again, we just wait for Baritone to finish and don't interfere.
     */
    private void handleWalkingState() {
        boolean onGround = mc.player.onGround();
        boolean baritoneDone = !baritone.getPathingBehavior().isPathing();

        if (onGround && baritoneDone) {
            landedTicks++;
        } else {
            landedTicks = 0;
        }

        if (landedTicks < 10) return;

        BlockPos target = targetPos.get();
        BlockPos playerPos = mc.player.blockPosition();

        if (playerPos.closerThan(target, 3)) {
            info("Reached exact target. Building Netherrack box...");
        } else {
            warning("Could not reach the exact spot, building box where I stand.");
        }
        currentState = State.BOXING;
    }

    // ==================== CUBE BUILDING — DO NOT TOUCH ====================
    private void handleBoxingState() {
        FindItemResult netherrack = InvUtils.findInHotbar(Items.NETHERRACK);

        if (!netherrack.found()) {
            error("Could not find Netherrack in the Hotbar. Cannot box yourself in.");
            toggle();
            return;
        }

        BlockPos pPos = mc.player.blockPosition();

        BlockPos[] boxPositions = new BlockPos[] {
            pPos.below(),
            pPos.north(), pPos.south(), pPos.east(), pPos.west(),
            pPos.above().north(), pPos.above().south(), pPos.above().east(), pPos.above().west(),
            pPos.above(2)
        };

        boolean finishedBuilding = true;

        for (BlockPos pos : boxPositions) {
            if (mc.level.getBlockState(pos).isAir()) {
                BlockUtils.place(pos, netherrack, true, 50);
                finishedBuilding = false;
                break;
            }
        }

        if (finishedBuilding) {
            info("Player fully boxed in Netherrack. Disabling SafeFly.");
            toggle();
        }
    }
}
