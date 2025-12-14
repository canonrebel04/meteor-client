/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

public class CoordinateHud extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Corner> corner = sgGeneral.add(new EnumSetting.Builder<Corner>()
        .name("corner")
        .description("Where the HUD is anchored.")
        .defaultValue(Corner.TopLeft)
        .build()
    );

    private final Setting<Integer> xOffset = sgGeneral.add(new IntSetting.Builder()
        .name("x")
        .description("X offset from the chosen corner.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Integer> yOffset = sgGeneral.add(new IntSetting.Builder()
        .name("y")
        .description("Y offset from the chosen corner.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Text scale.")
        .defaultValue(1.0)
        .min(0.5)
        .sliderMin(0.5)
        .sliderMax(2.0)
        .build()
    );

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Renders text with a shadow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> textColor = sgGeneral.add(new ColorSetting.Builder()
        .name("text-color")
        .description("Text color.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<Boolean> showWaypoints = sgGeneral.add(new BoolSetting.Builder()
        .name("show-waypoints")
        .description("Shows a small list of nearest waypoints.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxWaypoints = sgGeneral.add(new IntSetting.Builder()
        .name("max-waypoints")
        .description("Maximum number of waypoint entries to show.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 25)
        .visible(showWaypoints::get)
        .build()
    );

    private final String[] lines = new String[64];
    private int lineCount;

    private final Waypoint[] bestWaypoints = new Waypoint[25];
    private final double[] bestDistSq = new double[25];

    public CoordinateHud() {
        super(Categories.Render, "coordinate-hud", "Renders a minimal coordinate + waypoint HUD.");
    }

    @Override
    public void onActivate() {
        lineCount = 0;
    }

    @Override
    public void onDeactivate() {
        lineCount = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) {
            lineCount = 0;
            return;
        }

        int n = 0;

        int bx = mc.player.getBlockX();
        int by = mc.player.getBlockY();
        int bz = mc.player.getBlockZ();

        lines[n++] = "XYZ: " + bx + " " + by + " " + bz;

        String dim = mc.world.getRegistryKey().getValue().toString();
        lines[n++] = "Dim: " + dim;

        if (showWaypoints.get()) {
            int max = MathHelper.clamp(maxWaypoints.get(), 0, bestWaypoints.length);
            if (max > 0) {
                int found = selectNearestWaypoints(max, bx, by, bz);
                if (found > 0) {
                    lines[n++] = "Waypoints:";
                    for (int i = 0; i < found && n < lines.length; i++) {
                        Waypoint wp = bestWaypoints[i];
                        if (wp == null) continue;
                        BlockPos pos = wp.getPos();
                        int dx = pos.getX() - bx;
                        int dy = pos.getY() - by;
                        int dz = pos.getZ() - bz;
                        int dist = (int) Math.round(Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz));
                        lines[n++] = "- " + wp.name.get() + " (" + dist + ")";
                    }
                }
            }
        }

        lineCount = n;
    }

    private int selectNearestWaypoints(int max, int bx, int by, int bz) {
        for (int i = 0; i < max; i++) {
            bestWaypoints[i] = null;
            bestDistSq[i] = Double.POSITIVE_INFINITY;
        }

        int found = 0;

        for (Waypoint waypoint : Waypoints.get()) {
            if (waypoint == null) continue;
            if (!waypoint.visible.get()) continue;
            if (!Waypoints.checkDimension(waypoint)) continue;

            BlockPos pos = waypoint.getPos();
            double dx = (pos.getX() + 0.5) - (bx + 0.5);
            double dy = (pos.getY() + 0.5) - (by + 0.5);
            double dz = (pos.getZ() + 0.5) - (bz + 0.5);
            double distSq = dx * dx + dy * dy + dz * dz;

            if (found < max) {
                bestWaypoints[found] = waypoint;
                bestDistSq[found] = distSq;
                found++;
            } else {
                int worst = 0;
                double worstDist = bestDistSq[0];
                for (int i = 1; i < max; i++) {
                    if (bestDistSq[i] > worstDist) {
                        worstDist = bestDistSq[i];
                        worst = i;
                    }
                }

                if (distSq < worstDist) {
                    bestWaypoints[worst] = waypoint;
                    bestDistSq[worst] = distSq;
                }
            }
        }

        // Sort the selected waypoints by distance (small N).
        for (int i = 1; i < found; i++) {
            Waypoint w = bestWaypoints[i];
            double d = bestDistSq[i];
            int j = i - 1;
            while (j >= 0 && bestDistSq[j] > d) {
                bestWaypoints[j + 1] = bestWaypoints[j];
                bestDistSq[j + 1] = bestDistSq[j];
                j--;
            }
            bestWaypoints[j + 1] = w;
            bestDistSq[j + 1] = d;
        }

        return found;
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (lineCount == 0) return;

        TextRenderer tr = TextRenderer.get();
        boolean sh = shadow.get();

        tr.begin(scale.get());

        double lineHeight = tr.getHeight(sh);
        double paddingX = xOffset.get();
        double paddingY = yOffset.get();

        double maxWidth = 0;
        for (int i = 0; i < lineCount; i++) {
            String s = lines[i];
            if (s == null) continue;
            double w = tr.getWidth(s, sh);
            if (w > maxWidth) maxWidth = w;
        }

        double totalHeight = lineHeight * lineCount;

        double x;
        double y;

        switch (corner.get()) {
            case TopLeft -> {
                x = paddingX;
                y = paddingY;
            }
            case TopRight -> {
                x = event.screenWidth - paddingX - maxWidth;
                y = paddingY;
            }
            case BottomLeft -> {
                x = paddingX;
                y = event.screenHeight - paddingY - totalHeight;
            }
            case BottomRight -> {
                x = event.screenWidth - paddingX - maxWidth;
                y = event.screenHeight - paddingY - totalHeight;
            }
            default -> {
                x = paddingX;
                y = paddingY;
            }
        }

        SettingColor c = textColor.get();
        for (int i = 0; i < lineCount; i++) {
            String s = lines[i];
            if (s == null) continue;
            tr.render(s, x, y, c, sh);
            y += lineHeight;
        }

        tr.end();
    }

    public enum Corner {
        TopLeft,
        TopRight,
        BottomLeft,
        BottomRight
    }
}
