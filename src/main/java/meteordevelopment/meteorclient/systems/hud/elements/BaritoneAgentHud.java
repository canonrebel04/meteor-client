/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.pathing.BaritonePathManager;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/**
 * Minimal objective/status HUD for the Baritone automation runtime.
 */
public class BaritoneAgentHud extends HudElement {
    public static final HudElementInfo<BaritoneAgentHud> INFO = new HudElementInfo<>(Hud.GROUP, "baritone-agent", "Displays Baritone automation status (objective, current task and queue).", BaritoneAgentHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScale = settings.createGroup("Scale");
    private final SettingGroup sgBackground = settings.createGroup("Background");

    private final Setting<Integer> updateDelay = sgGeneral.add(new IntSetting.Builder()
        .name("update-delay")
        .description("Update delay in ticks.")
        .defaultValue(4)
        .min(0)
        .build()
    );

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Text shadow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showObjective = sgGeneral.add(new BoolSetting.Builder()
        .name("show-objective")
        .description("Displays the current objective (Baritone goal).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> customScale = sgScale.add(new BoolSetting.Builder()
        .name("custom-scale")
        .description("Applies a custom scale to this hud element.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgScale.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Custom scale.")
        .visible(customScale::get)
        .defaultValue(1)
        .min(0.5)
        .sliderRange(0.5, 3)
        .build()
    );

    private final Setting<Boolean> background = sgBackground.add(new BoolSetting.Builder()
        .name("background")
        .description("Displays background.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgBackground.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Color used for the background.")
        .visible(background::get)
        .defaultValue(new SettingColor(25, 25, 25, 50))
        .build()
    );

    private int timer;

    private String line1;
    private String line2;
    private String line3;

    public BaritoneAgentHud() {
        super(INFO);
    }

    @Override
    public void tick(HudRenderer renderer) {
        if (timer <= 0) {
            updateLines();
            timer = updateDelay.get();
        } else timer--;

        // Keep size stable while editing even if the text is empty.
        if (line1 == null || line2 == null || (showObjective.get() && line3 == null)) {
            updateLines();
        }

        double scale = getScale();
        double w1 = renderer.textWidth(line1, shadow.get(), scale);
        double w2 = renderer.textWidth(line2, shadow.get(), scale);
        double width = Math.max(w1, w2);

        int lines = showObjective.get() ? 3 : 2;
        if (showObjective.get()) {
            double w3 = renderer.textWidth(line3, shadow.get(), scale);
            width = Math.max(width, w3);
        }

        double height = renderer.textHeight(shadow.get(), scale) * lines;
        setSize(width, height);
    }

    @Override
    public void render(HudRenderer renderer) {
        if (line1 == null || line2 == null || (showObjective.get() && line3 == null)) updateLines();

        double scale = getScale();
        double lineHeight = renderer.textHeight(shadow.get(), scale);

        if (background.get()) {
            renderer.quad(this.x, this.y, getWidth(), getHeight(), backgroundColor.get());
        }

        renderer.text(line1, this.x, this.y, Hud.get().textColors.get().getFirst(), shadow.get(), scale);
        renderer.text(line2, this.x, this.y + lineHeight, Hud.get().textColors.get().getFirst(), shadow.get(), scale);
        if (showObjective.get()) {
            renderer.text(line3, this.x, this.y + lineHeight * 2, Hud.get().textColors.get().getFirst(), shadow.get(), scale);
        }
    }

    private void updateLines() {
        if (isInEditor()) {
            line1 = "Baritone: Idle";
            line2 = "Objective: —";
            line3 = "Task: — | Q:0 | Next: Smart Goto, Mine";
            return;
        }

        if (!(PathManagers.get() instanceof BaritonePathManager baritone)) {
            line1 = "Baritone: —";
            line2 = "Objective: —";
            line3 = "Task: — | Q:0";
            return;
        }

        var bb = baritone.getAutomationBlackboard();

        String status = bb.isPaused() ? "Paused" : (bb.isInventoryFull() ? "Inventory Full" : (bb.isLowHealth() ? "Low Health" : (bb.isStuck() ? "Stuck" : (bb.isPathing() ? "Pathing" : "Idle"))));
        line1 = "Baritone: " + status;

        String goal = bb.getGoalSummary();
        if (goal == null) {
            line2 = "Objective: —";
        } else {
            line2 = "Objective: " + truncate(goal, 80);
        }

        String currentName = bb.getCurrentTaskName();
        String currentDetail = bb.getCurrentTaskDetail();
        String taskText;
        if (currentName == null) {
            taskText = "—";
        } else if (currentDetail != null && !currentDetail.isBlank()) {
            taskText = currentName + ": " + currentDetail;
        } else {
            taskText = currentName;
        }

        String preview = bb.getQueuedPreview();
        line3 = "Task: " + taskText + " | Q:" + bb.getQueuedTaskCount() + (preview != null ? " | " + preview : "");

        if (!showObjective.get()) {
            // In 2-line mode, move the task line up.
            line2 = line3;
            line3 = null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (max <= 0) return "";
        if (s.length() <= max) return s;
        if (max <= 1) return s.substring(0, 1);
        return s.substring(0, max - 1) + "…";
    }

    private double getScale() {
        return customScale.get() ? scale.get() : Hud.get().getTextScale();
    }
}
