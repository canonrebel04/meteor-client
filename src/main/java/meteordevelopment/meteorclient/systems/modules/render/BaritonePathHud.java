/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.Optional;

public class BaritonePathHud extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> updateIntervalTicks = sgGeneral.add(new IntSetting.Builder()
            .name("update-interval")
            .description("How often (in ticks) to sample Baritone's current path.")
            .defaultValue(1)
            .min(1)
            .sliderRange(1, 20)
            .build());

    private final Setting<Integer> maxPoints = sgGeneral.add(new IntSetting.Builder()
            .name("max-points")
            .description("Maximum number of path points to render.")
            .defaultValue(512)
            .min(2)
            .sliderRange(16, 4096)
            .build());

    private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
            .name("max-distance")
            .description("Do not render points farther than this many blocks from you.")
            .defaultValue(256)
            .min(16)
            .sliderMin(64)
            .sliderMax(4096)
            .build());

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
            .name("line-color")
            .description("Path line color.")
            .defaultValue(new SettingColor(64, 255, 255, 255))
            .build());

    // HUD Settings
    private final SettingGroup sgHud = settings.createGroup("HUD");

    private final Setting<Boolean> showInfo = sgHud.add(new BoolSetting.Builder()
            .name("show-info")
            .description("Shows ETA and distance information.")
            .defaultValue(true)
            .build());

    private final Setting<Double> scale = sgHud.add(new DoubleSetting.Builder()
            .name("scale")
            .description("HUD text scale.")
            .defaultValue(1.0)
            .min(0.5)
            .sliderMin(0.5)
            .sliderMax(2.0)
            .visible(showInfo::get)
            .build());
    
    // Position settings could be added, but for now hardcode to top-left or use simple offsets like CoordinateHud
    private final Setting<Integer> xOffset = sgHud.add(new IntSetting.Builder()
            .name("x")
            .description("X offset.")
            .defaultValue(5)
            .min(0)
            .sliderRange(0, 200)
            .visible(showInfo::get)
            .build());

    private final Setting<Integer> yOffset = sgHud.add(new IntSetting.Builder()
            .name("y")
            .description("Y offset.")
            .defaultValue(50)
            .min(0)
            .sliderRange(0, 200)
            .visible(showInfo::get)
            .build());

    private int[] xs = new int[0];
    private int[] ys = new int[0];
    private int[] zs = new int[0];
    private int points;

    private String[] infoLines = new String[3];
    private int infoLineCount = 0;
    private SettingColor[] infoColors = new SettingColor[3];

    private int tickCounter;

    public BaritonePathHud() {
        super(Categories.Render, "baritone-path-hud", "Renders Baritone's planned route as a 3D overlay.");
    }

    @Override
    public void onDeactivate() {
        points = 0;
        tickCounter = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Use BaritoneUtils to get the path
        Object baritone = BaritoneUtils.getPrimaryBaritone();
        if (baritone == null) {
            points = 0;
            return;
        }

        Object behavior = BaritoneUtils.getPathingBehavior(baritone);
        if (behavior == null) {
            points = 0;
            return;
        }

        Object path = BaritoneUtils.getCurrentPath(behavior); // IPath
        if (path == null) {
            points = 0;
            return;
        }

        java.util.List<baritone.api.utils.BetterBlockPos> nodes = BaritoneUtils.getPathNodes(path);
        if (nodes == null || nodes.isEmpty()) {
            points = 0;
            return;
        }

        // Limit points
        int max = maxPoints.get();
        points = Math.min(nodes.size(), max);

        ensureCapacity(points);
        
        // HUD Update
        if (!showInfo.get()) {
            infoLineCount = 0;
        } else {
             updateHudInfo(behavior, nodes);
        }

        int i = 0;
        for (baritone.api.utils.BetterBlockPos pos : nodes) {
            if (i >= points)
                break;
            xs[i] = pos.getX();
            ys[i] = pos.getY();
            zs[i] = pos.getZ();
            i++;
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (points < 2)
            return;

        event.renderer.lines.ensureLineCapacity();

        int iLast = -1;
        SettingColor c = lineColor.get();

        for (int i = 0; i < points; i++) {
            float x = (float) (xs[i] + 0.5);
            float y = (float) (ys[i] + 0.5);
            float z = (float) (zs[i] + 0.5);

            int idx = event.renderer.lines.vec3(x, y, z).color(c).next();
            if (iLast != -1)
                event.renderer.lines.line(iLast, idx);
            iLast = idx;
        }
    }

    private void ensureCapacity(int capacity) {
        if (xs.length < capacity) {
            xs = new int[capacity];
            ys = new int[capacity];
            zs = new int[capacity];
        }
    }
    
    private void updateHudInfo(Object behavior, List<baritone.api.utils.BetterBlockPos> nodes) {
        Optional<Double> ticksOpt = BaritoneUtils.estimatedTicksToGoal(behavior);
        double ticks = ticksOpt.orElse(-1.0);
        
        int n = 0;
        
        // Line 1: Status/Points
        infoLines[n] = String.format("Path Leg: %d nodes", nodes.size());
        infoColors[n] = new SettingColor(255, 255, 255);
        n++;

        // Line 2: ETA
        if (ticks >= 0) {
             int seconds = (int) (ticks / 20);
             int m = seconds / 60;
             int s = seconds % 60;
             infoLines[n] = String.format("ETA: %02d:%02d", m, s);
             
             // Risk Color
             if (seconds < 30) infoColors[n] = new SettingColor(100, 255, 100); // Green
             else if (seconds < 120) infoColors[n] = new SettingColor(255, 255, 100); // Yellow
             else infoColors[n] = new SettingColor(255, 100, 100); // Red
             
             n++;
        } else {
             infoLines[n] = "ETA: Calculating...";
             infoColors[n] = new SettingColor(200, 200, 200);
             n++;
        }
        
        infoLineCount = n;
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!showInfo.get() || infoLineCount == 0) return;

        TextRenderer tr = TextRenderer.get();
        tr.begin(scale.get());

        double lineHeight = tr.getHeight(true);
        double x = xOffset.get();
        double y = yOffset.get();

        for (int i = 0; i < infoLineCount; i++) {
            tr.render(infoLines[i], x, y, infoColors[i], true);
            y += lineHeight;
        }

        tr.end();
    }
}
