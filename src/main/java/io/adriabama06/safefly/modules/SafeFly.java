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

// Baritone movement-input enum (used to tap forward/back/left/right).
import baritone.api.utils.input.Input;

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
        CENTERING, // Snap to center of the target block so the cube builds cleanly
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

    // Ticks elapsed in the current state (used for the WALKING/CENTERING safety timeouts).
    private int ticksInState = 0;

    // For the WALKING state we still need to observe normal ground pathing
    // start, since isPathing() briefly returns false when a new goto is sent
    // while it computes the first path.
    private boolean seenGroundPathingInWalkingState = false;

    // For CENTERING: once Baritone has landed us on the right block column,
    // we take over for the final sub-block snap to the center.
    private boolean baritoneCenteringFinished = false;

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

    // Max time to spend trying to center. If we can't get the player onto
    // the center (e.g. stuck, blocked), just box them where they are.
    private static final int CENTERING_TIMEOUT = 200; // ~10s

    // How close to the exact center (in blocks) we need to be before starting
    // to build the cube. Block-center is at x+0.5, z+0.5.
    private static final double CENTER_TOLERANCE = 0.1;

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
            case CENTERING -> handleCenteringState();
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
        baritoneCenteringFinished = false;
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
            // If we landed basically on the exact target block, skip walking
            // and go straight to centering on that block's center. Otherwise
            // start a normal on-foot goto first.
            baritone.getPathingBehavior().cancelEverything();
            if (playerPos.closerThan(target, ALREADY_THERE_RADIUS)) {
                info("Landed right on target. Centering on block...");
                beginCentering(playerPos);
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
            // Still center on the current block so the cube builds cleanly.
            warning("Landed too far from target (%.1f blocks > %d). Skipping walk, building box at current location.", dist, radius);
            baritone.getPathingBehavior().cancelEverything();
            beginCentering(playerPos);
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
     *     unreachable / same spot), time out and center/box where we stand.
     *   - Because {@code isPathing()} returns false briefly right after
     *     sending a new {@code goto} (while it computes the first path), we
     *     require that we've seen isPathing()==true at least once before we
     *     trust the "not pathing" signal as "actually done walking".
     * When we reach the destination block, we transition to CENTERING (not
     * BOXING) so the final sub-block snap to the center happens before the
     * cube is placed.
     */
    private void handleWalkingState() {
        ticksInState++;

        BlockPos target = targetPos.get();
        BlockPos playerPos = mc.player.blockPosition();

        // Fast path: already on (or right on top of) the target → skip to
        // centering, no need to wait for Baritone to ever start pathing.
        if (isPlayerSettled() && playerPos.closerThan(target, ALREADY_THERE_RADIUS)) {
            settledTicks++;
            if (settledTicks >= SETTLED_TICKS_REQUIRED) {
                info("Reached target block. Centering...");
                baritone.getPathingBehavior().cancelEverything();
                beginCentering(target);
            }
            return;
        }

        boolean pathing = baritone.getPathingBehavior().isPathing();
        if (pathing) {
            seenGroundPathingInWalkingState = true;
        }

        // Safety timeout: if we've been waiting a few seconds and Baritone
        // never started walking, bail out (probably same-spot or unreachable)
        // and center/box where we stand.
        if (!seenGroundPathingInWalkingState && ticksInState > WALKING_START_TIMEOUT) {
            if (playerPos.closerThan(target, 3)) {
                info("Baritone didn't need to walk (already at target). Centering...");
            } else {
                warning("Baritone failed to start walking. Centering/boxing at current location.");
                target = playerPos;
            }
            baritone.getPathingBehavior().cancelEverything();
            beginCentering(target);
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

        // Determine the block we actually ended up on. If Baritone got us
        // close to the target, use the target; otherwise use where we stand.
        BlockPos centerOn = playerPos.closerThan(target, 3) ? target : playerPos;
        if (centerOn == target) {
            info("Reached target block. Centering...");
        } else {
            warning("Could not reach the exact spot, centering/boxing where I stand.");
        }
        baritone.getPathingBehavior().cancelEverything();
        beginCentering(centerOn);
    }

    /**
     * Kick off the CENTERING state for the given block position. We first
     * ask Baritone for one last fine-grained goto to that block (so it can
     * climb/step if needed), then once Baritone has us there we manually
     * walk the sub-block distance to the exact (x+0.5, z+0.5) center.
     */
    private void beginCentering(BlockPos blockToCenterOn) {
        // The cube is built around the player's feet position, so we want to
        // end up on top of blockToCenterOn (i.e. feet Y = blockToCenterOn.y + 1
        // when standing on a solid block). Baritone's goto to the solid block
        // itself will place us standing on top of it.
        baritone.getPathingBehavior().cancelEverything();
        baritone.getCommandManager().execute(
            String.format("goto %d %d %d", blockToCenterOn.getX(), blockToCenterOn.getY(), blockToCenterOn.getZ())
        );
        transitionTo(State.CENTERING);
    }

    /**
     * CENTERING: snap the player to the exact center (x+0.5, z+0.5) of the
     * block we've landed on, so the Netherrack cube builds symmetrically
     * (otherwise if the player is near a block edge, some cube positions
     * can't be placed because the player's hitbox is in the way).
     *
     * Phase 1: let Baritone finish walking us onto the correct block (it
     * may need to step up a half-block, etc.). If we're already on the
     * right block, this phase is skipped.
     *
     * Phase 2: manually rotate the player to face the block center and
     * hold MOVE_FORWARD via Baritone's InputOverrideHandler until we're
     * within CENTER_TOLERANCE of the center. On ground, Minecraft's walk
     * speed is ~0.1 b/t and friction will stop us quickly once we release,
     * so overshoot is minimal over the <0.5 blocks of travel involved.
     */
    private void handleCenteringState() {
        ticksInState++;

        // Safety timeout — don't spend forever trying to center.
        if (ticksInState > CENTERING_TIMEOUT) {
            warning("Centering timed out, building box where I stand.");
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getPathingBehavior().cancelEverything();
            transitionTo(State.BOXING);
            return;
        }

        var input = baritone.getInputOverrideHandler();

        // Phase 1: let Baritone get us onto the destination block.
        boolean pathing = baritone.getPathingBehavior().isPathing();
        if (pathing) {
            baritoneCenteringFinished = true;
        }

        // If we're already settled (on ground, same block we started from)
        // and Baritone isn't moving us, skip Baritone's phase.
        if (!baritoneCenteringFinished && !pathing && isPlayerSettled()) {
            baritoneCenteringFinished = true;
            settledTicks = 0;
        }
        if (baritoneCenteringFinished && pathing) {
            settledTicks = 0;
        }

        boolean stillWaitingForBaritone =
            !baritoneCenteringFinished && ticksInState < WALKING_START_TIMEOUT && pathing;
        if (stillWaitingForBaritone) {
            settledTicks = 0;
            return;
        }

        // Wait for Baritone to fully settle (not pathing, on ground) for a
        // few ticks before we take over.
        if (baritoneCenteringFinished && (pathing || !isPlayerSettled())) {
            settledTicks = 0;
            return;
        }
        if (baritoneCenteringFinished) {
            settledTicks++;
            if (settledTicks < 5) return;
        }

        // Cancel any remaining Baritone movement so we have full control.
        if (pathing) {
            baritone.getPathingBehavior().cancelEverything();
        }

        // Phase 2: manual nudge to the block center.
        BlockPos playerBlock = mc.player.blockPosition();
        double targetX = playerBlock.getX() + 0.5;
        double targetZ = playerBlock.getZ() + 0.5;
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        double dx = targetX - px;
        double dz = targetZ - pz;
        double distH = Math.sqrt(dx * dx + dz * dz);

        if (distH <= CENTER_TOLERANCE || !isPlayerSettled()) {
            // Done (or got knocked off the ground). Release keys and box.
            input.clearAllKeys();
            // Kill any remaining horizontal velocity so we don't drift.
            var vel = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(0, vel.y, 0);
            info("Centered on block. Building Netherrack box...");
            transitionTo(State.BOXING);
            return;
        }

        // Rotate the player to face the center of the block. Then holding
        // MOVE_FORWARD will walk exactly toward it.
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Minecraft yaw is in [-180, 180], wrap to that range.
        float currentYaw = mc.player.getYRot();
        float wrappedTarget = wrapYaw(targetYaw);
        float yawDiff = wrapYaw(wrappedTarget - currentYaw);

        // Interpolate yaw toward the target so the rotation happens over a
        // couple of ticks rather than snapping (smoother).
        float newYaw = currentYaw + yawDiff * 0.5f;
        mc.player.setYRot(newYaw);
        mc.player.setXRot(0); // look straight ahead, don't look down/up

        // Stop forward input early enough to let friction halt us on target
        // (avoids overshoot). Minecraft ground friction is ~0.6 per tick,
        // so releasing at ~0.05 from target leaves ~1 tick of slide.
        boolean holdForward = distH > Math.max(CENTER_TOLERANCE + 0.03, 0.05);
        input.setInputForceState(Input.MOVE_FORWARD, holdForward);
        input.setInputForceState(Input.MOVE_BACK,    false);
        input.setInputForceState(Input.MOVE_LEFT,    false);
        input.setInputForceState(Input.MOVE_RIGHT,   false);
        input.setInputForceState(Input.JUMP,         false);
        input.setInputForceState(Input.SNEAK,        false);
        input.setInputForceState(Input.SPRINT,       false);
    }

    /**
     * Wrap a yaw angle (degrees) into [-180, 180].
     */
    private static float wrapYaw(float yaw) {
        yaw = yaw % 360;
        if (yaw > 180) yaw -= 360;
        if (yaw < -180) yaw += 360;
        return yaw;
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
