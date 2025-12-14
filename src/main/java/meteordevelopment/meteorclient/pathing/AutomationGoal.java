/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import java.util.List;

/**
 * A small, explainable automation goal that compiles into a finite list of queued tasks.
 *
 * Goals are intentionally offline-first: they do not call external services and should be fast to compile.
 */
public interface AutomationGoal {
    String name();

    /**
     * Human-readable explanation suitable for UI.
     */
    String explain();

    /**
     * Compile the goal into a finite list of tasks.
     */
    List<BaritoneTaskAgent.Task> compile();
}
