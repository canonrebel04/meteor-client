/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import net.minecraft.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal goal-oriented automation v1: Recover once, optionally enable Safe Mode, then Mine.
 */
public record RecoverThenSafeMineGoal(Block[] blocks, boolean enableSafeMode) implements AutomationGoal {
    @Override
    public String name() {
        return "Recover + Safe Mine";
    }

    @Override
    public String explain() {
        int count = blocks != null ? blocks.length : 0;
        return "Recover, then " + (enableSafeMode ? "enable Safe Mode, then " : "") + "Mine (" + count + " targets)";
    }

    @Override
    public List<BaritoneTaskAgent.Task> compile() {
        List<BaritoneTaskAgent.Task> tasks = new ArrayList<>(enableSafeMode ? 3 : 2);
        tasks.add(new BaritoneTaskAgent.RecoverTask());
        if (enableSafeMode) tasks.add(new BaritoneTaskAgent.SafeModeTask(true));
        tasks.add(new BaritoneTaskAgent.MineTask(blocks != null ? blocks : new Block[0]));
        return tasks;
    }
}
