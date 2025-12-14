/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.Deque;

public class BranchMiner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> mainLength = sgGeneral.add(new IntSetting.Builder()
            .name("main-length")
            .description("Length of the main tunnel.")
            .defaultValue(120)
            .min(1)
            .sliderRange(1, 512)
            .build());

    private final Setting<Integer> branchLength = sgGeneral.add(new IntSetting.Builder()
            .name("branch-length")
            .description("Length of each branch tunnel.")
            .defaultValue(24)
            .min(0)
            .sliderRange(0, 256)
            .build());

    private final Setting<Integer> branchSpacing = sgGeneral.add(new IntSetting.Builder()
            .name("branch-spacing")
            .description("How often to place branches along the main tunnel.")
            .defaultValue(12)
            .min(1)
            .sliderRange(1, 64)
            .build());

    private final Setting<Integer> tunnelHeight = sgGeneral.add(new IntSetting.Builder()
            .name("tunnel-height")
            .description("Height of tunnels.")
            .defaultValue(2)
            .min(2)
            .sliderRange(2, 6)
            .build());

    private final Setting<Integer> tunnelWidth = sgGeneral.add(new IntSetting.Builder()
            .name("tunnel-width")
            .description("Width of tunnels.")
            .defaultValue(1)
            .min(1)
            .sliderRange(1, 5)
            .build());

    private enum BranchSide {
        Alternate,
        Left,
        Right,
        Both
    }

    private final Setting<BranchSide> branchSide = sgGeneral.add(new EnumSetting.Builder<BranchSide>()
            .name("branch-side")
            .description("Which side(s) to mine branches on.")
            .defaultValue(BranchSide.Alternate)
            .build());

    private final Setting<Boolean> keepActive = sgGeneral.add(new BoolSetting.Builder()
            .name("keep-active")
            .description("Keep the module active after finishing the pattern.")
            .defaultValue(false)
            .build());

    private final Object baritone = BaritoneUtils.getPrimaryBaritone();

    private final Deque<ClearTask> tasks = new ArrayDeque<>();
    private Direction mainDir;
    private boolean nextAlternateLeft;

    public BranchMiner() {
        super(Categories.World, "branch-miner", "Digs a simple branch-mine pattern using Baritone builder.");
    }

    @Override
    public void onActivate() {
        if (!Utils.canUpdate())
            return;

        Direction facing = mc.player.getHorizontalFacing();
        if (facing == Direction.UP || facing == Direction.DOWN)
            facing = Direction.NORTH;
        mainDir = facing;

        tasks.clear();
        nextAlternateLeft = true;

        buildTasks(mc.player.getBlockPos());

        if (tasks.isEmpty()) {
            warning("Nothing to do.");
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        tasks.clear();
        Object builder = BaritoneUtils.getBuilderProcess(baritone);
        if (BaritoneUtils.isBuilderActive(builder)) {
            // Stop via chat or pathing behavior? Builder usage usually recommends stop
            // command or cancel.
            // But existing code used execute "stop".
            // We can use cancelEverything on pathing behavior? Builder might persist.
            // baritone.getCommandManager().execute("stop") needs CommandManager wrapper.
            // Simple fallback: chat.
            ChatUtils.sendPlayerMsg("#stop");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate())
            return;

        Object builder = BaritoneUtils.getBuilderProcess(baritone);
        if (!BaritoneUtils.isBuilderActive(builder)) {
            ClearTask task = tasks.pollFirst();
            if (task == null) {
                if (!keepActive.get())
                    toggle();
                return;
            }

            BaritoneUtils.clearArea(builder, task.corner1, task.corner2);
        }
    }

    private void buildTasks(BlockPos baseFeet) {
        int remaining = mainLength.get();
        int offset = 0;

        while (remaining > 0) {
            int segment = Math.min(branchSpacing.get(), remaining);

            // Main segment
            BlockPos segStart = baseFeet.offset(mainDir, offset);
            tasks.addLast(makeTunnel(segStart, mainDir, tunnelHeight.get(), tunnelWidth.get(), segment));

            offset += segment;
            remaining -= segment;

            if (remaining <= 0)
                break;
            if (branchLength.get() <= 0)
                continue;

            BlockPos branchStart = baseFeet.offset(mainDir, offset);
            switch (branchSide.get()) {
                case Left -> tasks.addLast(makeTunnel(branchStart, rotateLeft(mainDir), tunnelHeight.get(),
                        tunnelWidth.get(), branchLength.get()));
                case Right -> tasks.addLast(makeTunnel(branchStart, rotateRight(mainDir), tunnelHeight.get(),
                        tunnelWidth.get(), branchLength.get()));
                case Both -> {
                    tasks.addLast(makeTunnel(branchStart, rotateLeft(mainDir), tunnelHeight.get(), tunnelWidth.get(),
                            branchLength.get()));
                    tasks.addLast(makeTunnel(branchStart, rotateRight(mainDir), tunnelHeight.get(), tunnelWidth.get(),
                            branchLength.get()));
                }
                case Alternate -> {
                    Direction dir = nextAlternateLeft ? rotateLeft(mainDir) : rotateRight(mainDir);
                    tasks.addLast(
                            makeTunnel(branchStart, dir, tunnelHeight.get(), tunnelWidth.get(), branchLength.get()));
                    nextAlternateLeft = !nextAlternateLeft;
                }
            }
        }
    }

    private static Direction rotateLeft(Direction dir) {
        return switch (dir) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            case WEST -> Direction.SOUTH;
            default -> dir;
        };
    }

    private static Direction rotateRight(Direction dir) {
        return switch (dir) {
            case NORTH -> Direction.EAST;
            case SOUTH -> Direction.WEST;
            case EAST -> Direction.SOUTH;
            case WEST -> Direction.NORTH;
            default -> dir;
        };
    }

    private static ClearTask makeTunnel(BlockPos feet, Direction dir, int height, int width, int depth) {
        int heightOffset = height - 1;
        int widthOffset = width - 1;
        int addition = (widthOffset % 2 == 0) ? 0 : 1;

        BlockPos corner1;
        BlockPos corner2;

        switch (dir) {
            case EAST -> {
                corner1 = new BlockPos(feet.getX(), feet.getY(), feet.getZ() - widthOffset / 2);
                corner2 = new BlockPos(feet.getX() + depth, feet.getY() + heightOffset,
                        feet.getZ() + widthOffset / 2 + addition);
            }
            case WEST -> {
                corner1 = new BlockPos(feet.getX(), feet.getY(), feet.getZ() + widthOffset / 2 + addition);
                corner2 = new BlockPos(feet.getX() - depth, feet.getY() + heightOffset, feet.getZ() - widthOffset / 2);
            }
            case NORTH -> {
                corner1 = new BlockPos(feet.getX() - widthOffset / 2, feet.getY(), feet.getZ());
                corner2 = new BlockPos(feet.getX() + widthOffset / 2 + addition, feet.getY() + heightOffset,
                        feet.getZ() - depth);
            }
            case SOUTH -> {
                corner1 = new BlockPos(feet.getX() + widthOffset / 2 + addition, feet.getY(), feet.getZ());
                corner2 = new BlockPos(feet.getX() - widthOffset / 2, feet.getY() + heightOffset, feet.getZ() + depth);
            }
            default -> {
                // Should never happen for horizontal-facing patterns.
                corner1 = feet;
                corner2 = feet;
            }
        }

        return new ClearTask(corner1, corner2);
    }

    private record ClearTask(BlockPos corner1, BlockPos corner2) {
    }
}
