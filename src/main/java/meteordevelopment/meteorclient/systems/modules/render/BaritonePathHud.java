/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import baritone.api.BaritoneAPI;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

public class BaritonePathHud extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> updateIntervalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("update-interval")
        .description("How often (in ticks) to sample Baritone's current path.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> maxPoints = sgGeneral.add(new IntSetting.Builder()
        .name("max-points")
        .description("Maximum number of path points to render.")
        .defaultValue(512)
        .min(2)
        .sliderRange(16, 4096)
        .build()
    );

    private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-distance")
        .description("Do not render points farther than this many blocks from you.")
        .defaultValue(256)
        .min(16)
        .sliderMin(64)
        .sliderMax(4096)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Path line color.")
        .defaultValue(new SettingColor(64, 255, 255, 255))
        .build()
    );

    private int[] xs = new int[0];
    private int[] ys = new int[0];
    private int[] zs = new int[0];
    private int points;

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
        if (!BaritoneUtils.IS_AVAILABLE || mc.world == null || mc.player == null) {
            points = 0;
            return;
        }

        int interval = updateIntervalTicks.get();
        if (interval > 1 && (tickCounter++ % interval) != 0) return;

        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) {
            points = 0;
            return;
        }

        var optPath = baritone.getPathingBehavior().getPath();
        if (optPath == null || optPath.isEmpty()) {
            points = 0;
            return;
        }

        var path = optPath.get();
        var positions = path.positions();
        if (positions == null || positions.isEmpty()) {
            points = 0;
            return;
        }

        int cap = maxPoints.get();
        ensureCapacity(cap);

        double maxDist = maxDistance.get();
        double maxDistSq = maxDist * maxDist;

        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();

        int copied = 0;
        for (int i = 0; i < positions.size() && copied < cap; i++) {
            var pos = positions.get(i);
            if (pos == null) continue;

            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();

            double dx = (x + 0.5) - px;
            double dy = (y + 0.5) - py;
            double dz = (z + 0.5) - pz;

            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > maxDistSq) {
                if (copied > 0) break;
                continue;
            }

            xs[copied] = x;
            ys[copied] = y;
            zs[copied] = z;
            copied++;
        }

        points = copied;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (points < 2) return;

        event.renderer.lines.ensureLineCapacity();

        int iLast = -1;
        SettingColor c = lineColor.get();

        for (int i = 0; i < points; i++) {
            float x = (float) (xs[i] + 0.5);
            float y = (float) (ys[i] + 0.5);
            float z = (float) (zs[i] + 0.5);

            int idx = event.renderer.lines.vec3(x, y, z).color(c).next();
            if (iLast != -1) event.renderer.lines.line(iLast, idx);
            iLast = idx;
        }
    }

    private void ensureCapacity(int cap) {
        if (xs.length >= cap) return;

        xs = new int[cap];
        ys = new int[cap];
        zs = new int[cap];
    }
}
