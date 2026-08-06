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

    // Counters / guards ----------------------------------------------------
    private int noFireworkWarnCooldown = 0;

    // Per-state tracking used to robustly detect when Baritone is *actually*
    // done with the current job, rather than just briefly between path segments
    // or computing the initial route.
    private int ticksInState = 0;
    private int ticksSinceLastSeenPathing = 0;
    private boolean seenBaritoneActiveInThisState = false;

    // How many ticks to wait after Baritone stops reporting isPathing() before
    // we consider the current phase (flying / walking) truly finished. This
    // absorbs:
    //   - The initial gap between sending a `goto`/`elytra` command and the
    //     first path actually being computed (isPathing() is false during it).
    //   - Brief re-calculation pauses mid-flight/mid-walk.
    // 40 ticks ≈ 2 seconds.
    private static final int DONE_TICKS_THRESHOLD = 40;

    // Safety timeout per state. If Baritone has failed to make progress for
    // this many ticks in a given state, force a transition rather than hang
    // forever (e.g. unreachable target, path compute failure).
    private static final int STATE_TIMEOUT_TICKS = 20 * 60; // 60 seconds

    public SafeFly(Category category) {
        super(category, "safe-fly", "Flies to a set of coordinates with Baritone and boxes yourself in Netherrack upon arrival or when you run out of fireworks.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) return;

        BlockPos target = targetPos.get();
        info("Starting elytra flight to X: %d, Y: %d, Z: %d", target.getX(), target.getY(), target.getZ());

        transitionTo(State.FLYING);

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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Call when switching states. Resets all per-state bookkeeping.
     */
    private void transitionTo(State next) {
        currentState = next;
        ticksInState = 0;
        ticksSinceLastSeenPathing = 0;
        seenBaritoneActiveInThisState = false;
    }

    /**
     * Update per-state bookkeeping each tick. Returns {@code true} once
     * Baritone appears to be done with the current phase (landed / arrived).
     *
     * We do NOT trust "not pathing" on its own: Baritone returns false from
     * isPathing() both when calculating the very first path (right after a
     * command is sent) and during short re-calculations mid-route.
     *
     * To avoid declaring "done" while Baritone is still about to walk/fly:
     *   1. We must have observed isPathing() == true at least once in this
     *      state (so we know Baritone actually started working).
     *   2. isPathing() must have been false for DONE_TICKS_THRESHOLD ticks
     *      straight (absorbs computation gaps).
     *   3. The player must be on the ground and NOT fall-flying (so we don't
     *      trigger this while still mid-air).
     */
    private boolean tickAndCheckBaritoneDone(boolean requireOnGround) {
        ticksInState++;

        boolean baritonePathing = baritone.getPathingBehavior().isPathing();

        if (baritonePathing) {
            seenBaritoneActiveInThisState = true;
            ticksSinceLastSeenPathing = 0;
        } else {
            ticksSinceLastSeenPathing++;
        }

        // Safety timeout: if we've waited too long without seeing Baritone
        // actively pathing, treat it as done so we don't hang forever on
        // unreachable targets.
        if (ticksInState > STATE_TIMEOUT_TICKS) {
            warning("Baritone seems stuck (state timed out after %d s). Moving on.", STATE_TIMEOUT_TICKS / 20);
            return true;
        }

        // Must have seen Baritone actually start working first.
        if (!seenBaritoneActiveInThisState) return false;

        // Must have been idle (not pathing) for long enough.
        if (ticksSinceLastSeenPathing < DONE_TICKS_THRESHOLD) return false;

        // Ground check (most phases require being on ground to be "done").
        if (requireOnGround && (!mc.player.onGround() || mc.player.isFallFlying())) {
            return false;
        }

        return true;
    }

    /**
     * FLYING: we let Baritone do its thing (elytra flight + landing).
     * We do NOT cancel Baritone here. We only observe:
     *   - Warn once if out of fireworks (Baritone will still land safely).
     *   - When Baritone is fully done and the player is on the ground, decide
     *     whether to walk the rest of the way or go straight to boxing.
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

        if (!tickAndCheckBaritoneDone(true)) return;

        BlockPos target = targetPos.get();
        BlockPos playerPos = mc.player.blockPosition();
        double dist = Math.sqrt(playerPos.distSqr(target));
        int radius = walkRadius.get();

        info("Landed. Distance to target: %.1f blocks.", dist);

        if (dist <= radius) {
            info("Within walk radius (%d). Walking to exact spot...", radius);
            // Cancel the elytra process and start a fresh on-foot goto to the
            // exact coordinates.
            baritone.getPathingBehavior().cancelEverything();
            baritone.getCommandManager().execute(
                String.format("goto %d %d %d", target.getX(), target.getY(), target.getZ())
            );
            transitionTo(State.WALKING);
        } else {
            // Too far — almost certainly out of fireworks or couldn't reach the
            // target. Per user request, do NOT attempt to walk all the way.
            warning("Landed too far from target (%.1f blocks > %d). Skipping walk, building box at current location.", dist, radius);
            baritone.getPathingBehavior().cancelEverything();
            transitionTo(State.BOXING);
        }
    }

    /**
     * WALKING: Baritone is walking us to the exact block on foot.
     * Again, we just wait for Baritone to finish and don't interfere.
     */
    private void handleWalkingState() {
        if (!tickAndCheckBaritoneDone(true)) return;

        BlockPos target = targetPos.get();
        BlockPos playerPos = mc.player.blockPosition();

        if (playerPos.closerThan(target, 3)) {
            info("Reached exact target. Building Netherrack box...");
        } else {
            warning("Could not reach the exact spot, building box where I stand.");
        }
        transitionTo(State.BOXING);
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
