/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Simple background agent that runs queued Baritone tasks sequentially.
 *
 * This runs entirely from the client tick (no extra threads) so it is safe to
 * call Baritone and Minecraft APIs.
 */
public class BaritoneTaskAgent {
    public sealed interface Task permits GoToTask, MineTask, StopTask {
        String name();
        void start(BaritonePathManager pathManager);
    }

    public record GoToTask(BlockPos pos, boolean ignoreY) implements Task {
        @Override
        public String name() {
            return "Goto";
        }

        @Override
        public void start(BaritonePathManager pathManager) {
            pathManager.moveTo(pos, ignoreY);
        }
    }

    public record MineTask(Block[] blocks) implements Task {
        @Override
        public String name() {
            return "Mine";
        }

        @Override
        public void start(BaritonePathManager pathManager) {
            pathManager.mine(blocks);
        }

        @Override
        public String toString() {
            return "Mine" + Arrays.toString(blocks);
        }
    }

    public record StopTask() implements Task {
        @Override
        public String name() {
            return "Stop";
        }

        @Override
        public void start(BaritonePathManager pathManager) {
            pathManager.stop();
        }
    }

    private final BaritonePathManager pathManager;
    private final ArrayDeque<Task> queue = new ArrayDeque<>();

    private Task current;
    private boolean sawPathing;
    private int idleTicksAfterPathing;

    public BaritoneTaskAgent(BaritonePathManager pathManager) {
        this.pathManager = pathManager;
    }

    public void enqueue(Task task) {
        queue.add(task);
    }

    public void enqueueGoTo(BlockPos pos, boolean ignoreY) {
        enqueue(new GoToTask(pos, ignoreY));
    }

    public void enqueueMine(Block[] blocks) {
        enqueue(new MineTask(blocks));
    }

    public void clear() {
        queue.clear();
        stopAndForgetCurrent();
    }

    public void next() {
        stopAndForgetCurrent();
    }

    public int size() {
        return queue.size();
    }

    public Task current() {
        return current;
    }

    public void tick() {
        if (pathManager.isPaused()) return;

        if (current == null) {
            if (!queue.isEmpty() && !pathManager.isPathing()) {
                current = queue.poll();
                sawPathing = false;
                idleTicksAfterPathing = 0;
                current.start(pathManager);
            }
            return;
        }

        boolean pathing = pathManager.isPathing();
        if (pathing) {
            sawPathing = true;
            idleTicksAfterPathing = 0;
            return;
        }

        if (sawPathing) {
            idleTicksAfterPathing++;
            if (idleTicksAfterPathing >= 5) {
                current = null;
                sawPathing = false;
                idleTicksAfterPathing = 0;
            }
        }
    }

    private void stopAndForgetCurrent() {
        if (current != null) {
            pathManager.stop();
        }

        current = null;
        sawPathing = false;
        idleTicksAfterPathing = 0;
    }
}
