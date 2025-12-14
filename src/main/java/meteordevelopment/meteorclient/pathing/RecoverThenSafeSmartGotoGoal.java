/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * A small goal that attempts a single recover first, then performs a Safe Smart Goto.
 *
 * This is useful when you suspect the player is currently wedged or Baritone state is stale.
 */
public record RecoverThenSafeSmartGotoGoal(BlockPos target, boolean ignoreYHint, boolean enableSafeMode) implements AutomationGoal {
    @Override
    public String name() {
        return "Recover + Safe Smart Goto";
    }

    @Override
    public String explain() {
        return "Recover, then " + (enableSafeMode ? "enable Safe Mode, then " : "") + "Smart Goto to "
            + target.getX() + " " + target.getY() + " " + target.getZ();
    }

    @Override
    public List<BaritoneTaskAgent.Task> compile() {
        List<BaritoneTaskAgent.Task> tasks = new ArrayList<>(enableSafeMode ? 3 : 2);
        tasks.add(new BaritoneTaskAgent.RecoverTask());
        if (enableSafeMode) tasks.add(new BaritoneTaskAgent.SafeModeTask(true));
        tasks.add(new BaritoneTaskAgent.SmartGoToTask(target, ignoreYHint));
        return tasks;
    }
}
