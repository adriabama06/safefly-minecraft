package io.adriabama06.safefly.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IElytraProcess;
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

    // Consecutive ticks the player has been "settled" (on ground, not flying)
    // while the current Baritone process is not active. Used to debounce the
    // "Baritone is done" signal.
    private int settledTicks = 0;

    // Ticks elapsed in the current state (used for the WALKING safety timeout).
    private int ticksInState = 0;

    // For the WALKING state we still need to observe normal ground pathing
    // start, since isPathing() briefly returns false when a new goto is sent
    // while it computes the first path.
    private boolean seenGroundPathingInWalkingState = false;

    // Ticks needed settled on the ground + baritone process inactive before
    // we consider the phase truly finished. 20 ticks = ~1 second, enough to
    // absorb Baritone landing finalization / path recalculations but short
    // enough to feel responsive.
    private static final int SETTLED_TICKS_REQUIRED = 20;

    // How close (in blocks) the player must be to the target to consider
    // themselves "already there" without needing Baritone to walk.
    private static final int ALREADY_THERE_RADIUS = 2;

    // Safety timeout for the WALKING state. If Baritone hasn't started walking
    // within this many ticks (e.g. landed exactly on the target, or path
    // impossible), give up and build the box where we stand.
    private static final int WALKING_START_TIMEOUT = 60; // ~3s

    public SafeFly(Category category) {
        super(category, "safe-fly", "Flies to a set of coordinates with Baritone and boxes yourself in Netherrack upon arrival or when you run out of fireworks.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) return;

        BlockPos target = targetPos.get();
        info("Starting elytra flight to X: %d, Y: %d, Z: %d", target.getX(), target.getY(), target.getZ());

        transitionTo(State.FLYING);

        // Use Baritone's dedicated Elytra process via the public API.
        // This is exactly what the #elytra chat command does internally, and
        // it lets us reliably check isActive() / currentDestination() to know
        // when the flight (including landing) is really over.
        //
        // CRITICAL: we do NOT cancel Baritone afterwards. Baritone must handle
        // the entire flight + landing by itself, choosing its own safe landing
        // spot near the destination.
        IElytraProcess elytra = baritone.getElytraProcess();
        if (!elytra.isLoaded()) {
            warning("Baritone elytra native library not loaded — is the elytra mode available? Trying command fallback...");
            baritone.getCommandManager().execute(
                String.format("goto %d %d %d", target.getX(), target.getY(), target.getZ())
            );
            baritone.getCommandManager().execute("elytra");
        } else {
            elytra.pathTo(target);
        }
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

    private void transitionTo(State next) {
        currentState = next;
        settledTicks = 0;
        ticksInState = 0;
        seenGroundPathingInWalkingState = false;
    }

    /**
     * @return {@code true} if the player is on the ground and not gliding.
     */
    private boolean isPlayerSettled() {
        return mc.player.onGround() && !mc.player.isFallFlying();
    }

    // ------------------------------------------------------------------
    // FLYING
    // ------------------------------------------------------------------

    /**
     * FLYING: let Baritone's ElytraProcess fly to the destination and land
     * completely on its own. We do NOT cancel it. We observe the elytra
     * process directly: when {@code isActive()} returns {@code false} (no
     * destination, flight done / landed) AND the player has been settled on
     * the ground for a short debounce period, we decide whether to walk the
     * last few blocks or build the box directly.
     */
    private void handleFlyingState() {
        // Low-fireworks warning (non-intrusive). Baritone handles landing
        // automatically when it can't boost anymore.
        FindItemResult fireworks = InvUtils.find(Items.FIREWORK_ROCKET);
        boolean hasEnoughFireworks = fireworks.found() && fireworks.count() >= minFireworks.get();
        if (!hasEnoughFireworks && minFireworks.get() > 0 && noFireworkWarnCooldown <= 0) {
            warning("Low on fireworks (%d left). Baritone will land shortly.", fireworks.count());
            noFireworkWarnCooldown = 100;
        }
        if (noFireworkWarnCooldown > 0) noFireworkWarnCooldown--;

        IElytraProcess elytra = baritone.getElytraProcess();
        boolean elytraActive = elytra.isActive() || elytra.currentDestination() != null;

        if (elytraActive || !isPlayerSettled()) {
            // Still flying, or still landing/settling. Reset debounce.
            settledTicks = 0;
            return;
        }

        settledTicks++;
        if (settledTicks < SETTLED_TICKS_REQUIRED) return;

        // Elytra process is inactive AND the player has been on the ground
        // (not fall-flying) for ~1 second → Baritone has finished landing.
        BlockPos target = targetPos.get();
        BlockPos playerPos = mc.player.blockPosition();
        double dist = Math.sqrt(playerPos.distSqr(target));
        int radius = walkRadius.get();

        info("Landed. Distance to target: %.1f blocks.", dist);

        if (dist <= radius) {
            // If we landed basically on the exact target, skip walking entirely.
            // Otherwise start a normal on-foot goto.
            baritone.getPathingBehavior().cancelEverything();
            if (playerPos.closerThan(target, ALREADY_THERE_RADIUS)) {
                info("Landed right on target. Building Netherrack box...");
                transitionTo(State.BOXING);
            } else {
                info("Within walk radius (%d). Walking to exact spot...", radius);
                baritone.getCommandManager().execute(
                    String.format("goto %d %d %d", target.getX(), target.getY(), target.getZ())
                );
                transitionTo(State.WALKING);
            }
        } else {
            // Too far — out of rockets or unreachable by flight.
            // Per user request, do NOT attempt to walk all the way there.
            warning("Landed too far from target (%.1f blocks > %d). Skipping walk, building box at current location.", dist, radius);
            baritone.getPathingBehavior().cancelEverything();
            transitionTo(State.BOXING);
        }
    }

    // ------------------------------------------------------------------
    // WALKING
    // ------------------------------------------------------------------

    /**
     * WALKING: Baritone is walking us to the exact block on foot.
     * We don't interfere — just wait for ground pathing to finish.
     * Edge cases handled:
     *   - If we entered WALKING already within a block or two of the target
     *     (Baritone didn't start pathing because there's nowhere to go),
     *     finish immediately.
     *   - If Baritone never starts pathing within a few seconds (target
     *     unreachable / same spot), time out and box where we stand.
     *   - Because {@code isPathing()} returns false briefly right after
     *     sending a new {@code goto} (while it computes the first path), we
     *     require that we've seen isPathing()==true at least once before we
     *     trust the "not pathing" signal as "actually done walking".
     */
    private void handleWalkingState() {
        ticksInState++;

        BlockPos target = targetPos.get();
        BlockPos playerPos = mc.player.blockPosition();

        // Fast path: already on (or right on top of) the target → we're done,
        // no need to wait for Baritone to ever start pathing.
        if (isPlayerSettled() && playerPos.closerThan(target, ALREADY_THERE_RADIUS)) {
            settledTicks++;
            if (settledTicks >= SETTLED_TICKS_REQUIRED) {
                info("Reached exact target. Building Netherrack box...");
                transitionTo(State.BOXING);
            }
            return;
        }

        boolean pathing = baritone.getPathingBehavior().isPathing();
        if (pathing) {
            seenGroundPathingInWalkingState = true;
        }

        // Safety timeout: if we've been waiting a few seconds and Baritone
        // never started walking, bail out (probably same-spot or unreachable).
        if (!seenGroundPathingInWalkingState && ticksInState > WALKING_START_TIMEOUT) {
            if (playerPos.closerThan(target, 3)) {
                info("Baritone didn't need to walk (already at target). Building Netherrack box...");
            } else {
                warning("Baritone failed to start walking. Building box at current location.");
            }
            baritone.getPathingBehavior().cancelEverything();
            transitionTo(State.BOXING);
            return;
        }

        boolean doneWalking =
               seenGroundPathingInWalkingState
            && !pathing
            && isPlayerSettled();

        if (!doneWalking) {
            settledTicks = 0;
            return;
        }

        settledTicks++;
        if (settledTicks < SETTLED_TICKS_REQUIRED) return;

        if (playerPos.closerThan(target, 3)) {
            info("Reached exact target. Building Netherrack box...");
        } else {
            warning("Could not reach the exact spot, building box where I stand.");
        }
        baritone.getPathingBehavior().cancelEverything();
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
