/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal goal-oriented automation v1: a simple, finite goal that decomposes into queued tasks.
 *
 * This goal is explainable and interruptible (the queue can be cleared or advanced at any time).
 */
public record SafeSmartGotoGoal(BlockPos target, boolean ignoreYHint, boolean enableSafeMode) implements AutomationGoal {
    @Override
    public String name() {
        return "Safe Smart Goto";
    }

    @Override
    public String explain() {
        return (enableSafeMode ? "Enable Safe Mode, then " : "") + "Smart Goto to " + target.getX() + " " + target.getY() + " " + target.getZ();
    }

    @Override
    public List<BaritoneTaskAgent.Task> compile() {
        List<BaritoneTaskAgent.Task> tasks = new ArrayList<>(enableSafeMode ? 2 : 1);
        if (enableSafeMode) tasks.add(new BaritoneTaskAgent.SafeModeTask(true));
        tasks.add(new BaritoneTaskAgent.SmartGoToTask(target, ignoreYHint));
        return tasks;
    }
}
