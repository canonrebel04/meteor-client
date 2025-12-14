/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;

/**
 * Simple background agent that runs queued Baritone tasks sequentially.
 *
 * This runs entirely from the client tick (no extra threads) so it is safe to
 * call Baritone and Minecraft APIs.
 */
public class BaritoneTaskAgent {
    public sealed interface Task permits GoToTask, SmartGoToTask, MineTask, StopTask, RecoverTask, SafeModeTask {
        String name();
        default String detail() {
            return null;
        }
        void start(BaritonePathManager pathManager);

        default boolean isInstant() {
            return false;
        }
    }

    public record GoToTask(BlockPos pos, boolean ignoreY) implements Task {
        @Override
        public String name() {
            return "Goto";
        }

        @Override
        public String detail() {
            return pos.getX() + " " + pos.getY() + " " + pos.getZ() + (ignoreY ? " (ignoreY)" : "");
        }

        @Override
        public void start(BaritonePathManager pathManager) {
            pathManager.moveTo(pos, ignoreY);
        }
    }

    public record SmartGoToTask(BlockPos pos, boolean ignoreYHint) implements Task {
        @Override
        public String name() {
            return "Smart Goto";
        }

        @Override
        public String detail() {
            return pos.getX() + " " + pos.getY() + " " + pos.getZ() + (ignoreYHint ? " (ignoreY)" : "");
        }

        @Override
        public void start(BaritonePathManager pathManager) {
            pathManager.smartMoveTo(pos, ignoreYHint);
        }
    }

    public record MineTask(Block[] blocks) implements Task {
        @Override
        public String name() {
            return "Mine";
        }

        @Override
        public String detail() {
            int count = blocks != null ? blocks.length : 0;
            return count == 1 ? "1 target" : (count + " targets");
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

        @Override
        public boolean isInstant() {
            return true;
        }
    }

    public record RecoverTask() implements Task {
        @Override
        public String name() {
            return "Recover";
        }

        @Override
        public void start(BaritonePathManager pathManager) {
            pathManager.recover();
        }

        @Override
        public boolean isInstant() {
            return true;
        }
    }

    public record SafeModeTask(boolean enabled) implements Task {
        @Override
        public String name() {
            return enabled ? "Safe Mode: ON" : "Safe Mode: OFF";
        }

        @Override
        public void start(BaritonePathManager pathManager) {
            pathManager.setSafeMode(enabled);
        }

        @Override
        public boolean isInstant() {
            return true;
        }
    }

    private final BaritoneTaskScheduler scheduler;
    private final AutomationMacroRecorder macroRecorder;

    public BaritoneTaskAgent(BaritonePathManager pathManager) {
        this(pathManager, null);
    }

    public BaritoneTaskAgent(BaritonePathManager pathManager, AutomationMacroRecorder macroRecorder) {
        this.scheduler = new BaritoneTaskScheduler(pathManager, new AutomationBlackboard());
        this.macroRecorder = macroRecorder;
    }

    public AutomationBlackboard getBlackboard() {
        return scheduler.blackboard();
    }

    public void enqueue(Task task) {
        if (task == null) return;

        if (macroRecorder != null) {
            macroRecorder.record(task);
        }
        scheduler.enqueue(task);
    }

    public void enqueueGoal(AutomationGoal goal) {
        if (goal == null) return;

        var tasks = goal.compile();
        if (tasks == null || tasks.isEmpty()) return;

        for (Task task : tasks) {
            if (task != null) enqueue(task);
        }
    }

    public void enqueueGoTo(BlockPos pos, boolean ignoreY) {
        enqueue(new GoToTask(pos, ignoreY));
    }

    public void enqueueSmartGoTo(BlockPos pos, boolean ignoreYHint) {
        enqueue(new SmartGoToTask(pos, ignoreYHint));
    }

    public void enqueueMine(Block[] blocks) {
        enqueue(new MineTask(blocks != null ? blocks.clone() : null));
    }

    public void enqueueStop() {
        enqueue(new StopTask());
    }

    public void enqueueRecover() {
        enqueue(new RecoverTask());
    }

    public void enqueueSafeMode(boolean enabled) {
        enqueue(new SafeModeTask(enabled));
    }

    public void clear() {
        scheduler.clear();
    }

    public void next() {
        scheduler.next();
    }

    public int size() {
        return scheduler.size();
    }

    public Task current() {
        return scheduler.current();
    }

    public void tick() {
        scheduler.tick();
    }
}
