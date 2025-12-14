/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import net.minecraft.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal goal-oriented automation v1: enable Safe Mode (optional) then Mine.
 */
public record SafeMineGoal(Block[] blocks, boolean enableSafeMode) implements AutomationGoal {
    @Override
    public String name() {
        return "Safe Mine";
    }

    @Override
    public String explain() {
        int count = blocks != null ? blocks.length : 0;
        return (enableSafeMode ? "Enable Safe Mode, then " : "") + "Mine (" + count + " targets)";
    }

    @Override
    public List<BaritoneTaskAgent.Task> compile() {
        List<BaritoneTaskAgent.Task> tasks = new ArrayList<>(enableSafeMode ? 2 : 1);
        if (enableSafeMode) tasks.add(new BaritoneTaskAgent.SafeModeTask(true));
        tasks.add(new BaritoneTaskAgent.MineTask(blocks != null ? blocks : new Block[0]));
        return tasks;
    }
}
