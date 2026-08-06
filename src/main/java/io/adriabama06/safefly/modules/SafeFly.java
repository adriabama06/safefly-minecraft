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

    private final Setting<Integer> minFireworks = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-fireworks")
        .description("The minimum number of fireworks before aborting and landing.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private enum State {
        FLYING,
        LANDING,
        BOXING,
        IDLE
    }

    private State currentState = State.IDLE;
    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

    public SafeFly(Category category) {
        super(category, "safe-fly", "Flies to a set of coordinates with Baritone and boxes yourself in Netherrack upon arrival or when you run out of fireworks.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) return;

        currentState = State.FLYING;
        BlockPos target = targetPos.get();

        info("Starting flight to X: %d, Y: %d, Z: %d", target.getX(), target.getY(), target.getZ());
        baritone.getCommandManager().execute(
            String.format("goto %d %d %d", target.getX(), target.getY(), target.getZ())
        );
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
            case LANDING -> handleLandingState();
            case BOXING -> handleBoxingState();
            case IDLE -> {}
        }
    }

    private void handleFlyingState() {
        FindItemResult fireworks = InvUtils.find(Items.FIREWORK_ROCKET);
        boolean hasEnoughFireworks = fireworks.found() && fireworks.count() >= minFireworks.get();

        BlockPos target = targetPos.get();
        boolean arrived = mc.player.blockPosition().closerThan(target, 15);

        if (!hasEnoughFireworks || arrived) {
            if (!hasEnoughFireworks) {
                warning("Not enough fireworks. Aborting flight and landing...");
            } else {
                info("Destination reached. Starting landing maneuver...");
            }

            baritone.getPathingBehavior().cancelEverything();
            baritone.getCommandManager().execute("land");
            currentState = State.LANDING;
        }
    }

    private void handleLandingState() {
        if (mc.player.onGround() && !mc.player.isFallFlying()) {
            info("Safe landing detected. Building Netherrack box...");
            currentState = State.BOXING;
        }
    }

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
