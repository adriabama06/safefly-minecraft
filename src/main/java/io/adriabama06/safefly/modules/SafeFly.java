package io.adriabama06.safefly.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IElytraProcess;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockPosSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
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

    public enum CoordinateMode {
        XYZ,
        XZ
    }

    private final Setting<CoordinateMode> coordinateMode = sgGeneral.add(new EnumSetting.Builder<CoordinateMode>()
        .name("coordinate-mode")
        .description("Whether to use exact XYZ coordinates (X, Y, Z) or only XZ (horizontal). In XZ mode the Y is ignored.")
        .defaultValue(CoordinateMode.XYZ)
        .build()
    );

    private final Setting<BlockPos> targetPos = sgGeneral.add(new BlockPosSetting.Builder()
        .name("target-coordinates")
        .description("The coordinates to fly to while using the Elytra. In XZ mode the Y is ignored.")
        .defaultValue(new BlockPos(0, 120, 0))
        .build()
    );

    private final Setting<Integer> walkRadius = sgGeneral.add(new IntSetting.Builder()
        .name("walk-radius")
        .description("Max distance (blocks) from target after landing to finish on foot. In XZ mode this is horizontal distance. If further away (out of rockets / unreachable), skip walking.")
        .defaultValue(64)
        .min(1)
        .sliderRange(1, 256)
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
    private static final double CENTER_TOLERANCE = 0.2;

    public SafeFly(Category category) {
        super(category, "safe-fly", "Flies to a set of coordinates with Baritone and boxes yourself in Netherrack upon arrival or when you run out of fireworks.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) return;

        BlockPos target = targetPos.get();
        BlockPos baritoneTarget = getBaritoneTarget(target);

        if (isXZ()) {
            info("Starting elytra flight to X: %d, Z: %d (XZ mode - Y ignored)", target.getX(), target.getZ());
        } else {
            info("Starting elytra flight to X: %d, Y: %d, Z: %d", target.getX(), target.getY(), target.getZ());
        }

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
            if (isXZ()) {
                baritone.getCommandManager().execute(
                    String.format("goto %d %d", baritoneTarget.getX(), baritoneTarget.getZ())
                );
            } else {
                baritone.getCommandManager().execute(
                    String.format("goto %d %d %d", baritoneTarget.getX(), baritoneTarget.getY(), baritoneTarget.getZ())
                );
            }
            baritone.getCommandManager().execute("elytra");
        } else {
            elytra.pathTo(baritoneTarget);
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
    }

    /**
     * @return {@code true} if the player is on the ground and not gliding.
     */
    private boolean isPlayerSettled() {
        return mc.player.onGround() && !mc.player.isFallFlying();
    }

    private boolean isXZ() {
        return coordinateMode.get() == CoordinateMode.XZ;
    }

    /**
     * Convert a BlockPos to the effective Baritone target.
     * In XYZ mode returns the pos unchanged.
     * In XZ mode keeps X/Z but replaces Y with the player's current Y
     * so Baritone only cares about the horizontal column.
     */
    private BlockPos getBaritoneTarget(BlockPos base) {
        if (isXZ() && mc.player != null) {
            return new BlockPos(base.getX(), mc.player.blockPosition().getY(), base.getZ());
        }
        return base;
    }

    /**
     * Distance to target: 3D Euclidean in XYZ, horizontal only in XZ.
     */
    private double distanceToTarget(BlockPos playerPos, BlockPos target) {
        if (isXZ()) {
            int dx = playerPos.getX() - target.getX();
            int dz = playerPos.getZ() - target.getZ();
            return Math.sqrt((double) dx * dx + (double) dz * dz);
        } else {
            return Math.sqrt(playerPos.distSqr(target));
        }
    }

    /**
     * Whether the player is within radius of the target.
     * Uses 3D closerThan in XYZ, horizontal distance in XZ.
     */
    private boolean isWithinDistance(BlockPos playerPos, BlockPos target, double radius) {
        if (isXZ()) {
            int dx = playerPos.getX() - target.getX();
            int dz = playerPos.getZ() - target.getZ();
            return (dx * dx + dz * dz) < radius * radius;
        } else {
            return playerPos.closerThan(target, radius);
        }
    }

    /**
     * Set Baritone walking goal correctly for the current coordinate mode.
     * In XYZ uses GoalBlock (exact X Y Z), in XZ uses GoalXZ (only X Z, Y ignored).
     */
    private void setWalkingGoal(BlockPos pos) {
        if (isXZ()) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(pos.getX(), pos.getZ()));
        } else {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
        }
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
        double dist = distanceToTarget(playerPos, target);
        int radius = walkRadius.get();

        if (isXZ()) {
            info("Landed. Distance to target (horizontal): %.1f blocks.", dist);
        } else {
            info("Landed. Distance to target: %.1f blocks.", dist);
        }

        if (dist <= radius) {
            // If we landed basically on the exact target block, skip walking
            // and go straight to centering on that block's center. Otherwise
            // start a normal on-foot goto first.
            baritone.getPathingBehavior().cancelEverything();
            if (isWithinDistance(playerPos, target, ALREADY_THERE_RADIUS)) {
                info("Landed right on target. Centering on block...");
                beginCentering();
            } else {
                if (isXZ()) {
                    info("Within walk radius (%d horizontal). Walking to exact XZ...", radius);
                } else {
                    info("Within walk radius (%d). Walking to exact spot...", radius);
                }
                setWalkingGoal(target);
                transitionTo(State.WALKING);
            }
        } else {
            // Too far — out of rockets or unreachable by flight.
            // Per user request, do NOT attempt to walk all the way there.
            // Still center on the current block so the cube builds cleanly.
            if (isXZ()) {
                warning("Landed too far from target (%.1f blocks horizontal > %d). Skipping walk, building box at current location.", dist, radius);
            } else {
                warning("Landed too far from target (%.1f blocks > %d). Skipping walk, building box at current location.", dist, radius);
            }
            baritone.getPathingBehavior().cancelEverything();
            beginCentering();
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
        if (isPlayerSettled() && isWithinDistance(playerPos, target, ALREADY_THERE_RADIUS)) {
            settledTicks++;
            if (settledTicks >= SETTLED_TICKS_REQUIRED) {
                info("Reached target block. Centering...");
                baritone.getPathingBehavior().cancelEverything();
                beginCentering();
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
            if (isWithinDistance(playerPos, target, 3)) {
                info("Baritone didn't need to walk (already at target). Centering...");
            } else {
                warning("Baritone failed to start walking. Centering/boxing at current location.");
            }
            baritone.getPathingBehavior().cancelEverything();
            beginCentering();
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
        BlockPos centerOn = isWithinDistance(playerPos, target, 3) ? target : playerPos;
        if (centerOn == target) {
            info("Reached target block. Centering...");
        } else {
            warning("Could not reach the exact spot, centering/boxing where I stand.");
        }
        baritone.getPathingBehavior().cancelEverything();
        beginCentering();
    }

    /**
     * Kick off the CENTERING state.
     */
    private void beginCentering() {
        baritone.getPathingBehavior().cancelEverything();
        transitionTo(State.CENTERING);
    }

    /**
     * CENTERING: look at the center of the current block and walk forward sneaking
     * until within CENTER_TOLERANCE.
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

        BlockPos playerBlock = mc.player.blockPosition();
        double targetX = playerBlock.getX() + 0.5;
        double targetZ = playerBlock.getZ() + 0.5;
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        double dx = targetX - px;
        double dz = targetZ - pz;
        double distH = Math.sqrt(dx * dx + dz * dz);

        var input = baritone.getInputOverrideHandler();

        if (distH <= CENTER_TOLERANCE) {
            input.clearAllKeys();
            var vel = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(0, vel.y, 0);
            info("Centered on block. Building Netherrack box...");
            transitionTo(State.BOXING);
            return;
        }

        // Look toward the center of the current block
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYRot(targetYaw);
        mc.player.setXRot(0);

        // Walk forward while sneaking
        input.setInputForceState(Input.MOVE_FORWARD, true);
        input.setInputForceState(Input.SNEAK, true);
        input.setInputForceState(Input.MOVE_BACK, false);
        input.setInputForceState(Input.MOVE_LEFT, false);
        input.setInputForceState(Input.MOVE_RIGHT, false);
        input.setInputForceState(Input.JUMP, false);
        input.setInputForceState(Input.SPRINT, false);
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
            pPos.below(), pPos.below().north(),
            pPos.north(), pPos.south(), pPos.east(), pPos.west(),
            pPos.above().north(), pPos.above().south(), pPos.above().east(), pPos.above().west(),
            pPos.above(2).north(), pPos.above(2)
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
