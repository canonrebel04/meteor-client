/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import meteordevelopment.meteorclient.utils.player.ChatUtils;

import java.util.ArrayList;
import java.util.List;

public class AutomationMacroRecorder {
    private final List<BaritoneTaskAgent.Task> steps = new ArrayList<>();

    private boolean recording;
    private boolean playing;

    public boolean isRecording() {
        return recording;
    }

    public int size() {
        return steps.size();
    }

    public List<BaritoneTaskAgent.Task> snapshot() {
        return List.copyOf(steps);
    }

    public void startNew() {
        steps.clear();
        recording = true;
        ChatUtils.infoPrefix("Automation", "Macro recording started.");
    }

    public void stop() {
        if (!recording) return;
        recording = false;
        ChatUtils.infoPrefix("Automation", "Macro recording stopped (%d step%s).", steps.size(), steps.size() == 1 ? "" : "s");
    }

    public void clear() {
        steps.clear();
        recording = false;
        ChatUtils.infoPrefix("Automation", "Macro cleared.");
    }

    public void record(BaritoneTaskAgent.Task task) {
        if (!recording) return;
        if (playing) return;
        if (task == null) return;

        steps.add(copyTask(task));
    }

    public void play(BaritoneTaskAgent agent) {
        if (agent == null) return;
        if (steps.isEmpty()) {
            ChatUtils.warningPrefix("Automation", "Macro is empty.");
            return;
        }

        ChatUtils.infoPrefix("Automation", "Macro playback enqueued (%d step%s).", steps.size(), steps.size() == 1 ? "" : "s");

        playing = true;
        try {
            for (BaritoneTaskAgent.Task step : steps) {
                if (step == null) continue;
                agent.enqueue(copyTask(step));
            }
        } finally {
            playing = false;
        }
    }

    private static BaritoneTaskAgent.Task copyTask(BaritoneTaskAgent.Task task) {
        if (task instanceof BaritoneTaskAgent.MineTask mineTask) {
            var blocks = mineTask.blocks();
            return new BaritoneTaskAgent.MineTask(blocks != null ? blocks.clone() : null);
        }

        // Other tasks are records / immutable.
        return task;
    }
}
