/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.MathHelper;

public class Navigator extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("How the destination is chosen.")
        .defaultValue(Mode.Waypoint)
        .build()
    );

    // Waypoint mode
    private final Setting<String> waypointName = sgGeneral.add(new StringSetting.Builder()
        .name("waypoint-name")
        .description("Waypoint name to navigate to (case-insensitive, first match).")
        .defaultValue("")
        .visible(() -> mode.get() == Mode.Waypoint)
        .build()
    );

    // Coords mode
    private final Setting<Integer> x = sgGeneral.add(new IntSetting.Builder()
        .name("x")
        .description("Destination X.")
        .defaultValue(0)
        .visible(() -> mode.get() == Mode.Coordinates)
        .build()
    );

    private final Setting<Integer> y = sgGeneral.add(new IntSetting.Builder()
        .name("y")
        .description("Destination Y.")
        .defaultValue(64)
        .visible(() -> mode.get() == Mode.Coordinates)
        .build()
    );

    private final Setting<Integer> z = sgGeneral.add(new IntSetting.Builder()
        .name("z")
        .description("Destination Z.")
        .defaultValue(0)
        .visible(() -> mode.get() == Mode.Coordinates)
        .build()
    );

    private final Setting<Boolean> ignoreY = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-y")
        .description("When using coordinates, keep the marker at your current Y.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Coordinates)
        .build()
    );

    private final Setting<Boolean> renderTracer = sgGeneral.add(new BoolSetting.Builder()
        .name("render-tracer")
        .description("Renders a tracer to the destination.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderMarker = sgGeneral.add(new BoolSetting.Builder()
        .name("render-marker")
        .description("Renders a small marker box at the destination.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-distance")
        .description("Do not render if the destination is farther than this many blocks.")
        .defaultValue(10_000)
        .min(16)
        .sliderMin(64)
        .sliderMax(50_000)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Tracer color.")
        .defaultValue(new SettingColor(64, 255, 255, 255))
        .visible(renderTracer::get)
        .build()
    );

    private final Setting<SettingColor> markerLineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("marker-line-color")
        .description("Marker outline color.")
        .defaultValue(new SettingColor(64, 255, 255, 255))
        .visible(renderMarker::get)
        .build()
    );

    private boolean hasTarget;
    private int targetX;
    private int targetY;
    private int targetZ;

    public Navigator() {
        super(Categories.Render, "navigator", "Renders a direction indicator to a destination.");
    }

    public void setWaypointTarget(String name) {
        waypointName.set(name);
        mode.set(Mode.Waypoint);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) {
            hasTarget = false;
            return;
        }

        switch (mode.get()) {
            case Waypoint -> {
                String name = waypointName.get();
                if (name == null || name.isBlank()) {
                    hasTarget = false;
                    return;
                }

                Waypoint match = null;
                for (Waypoint waypoint : Waypoints.get()) {
                    if (!waypoint.visible.get()) continue;
                    if (!Waypoints.checkDimension(waypoint)) continue;

                    String wpName = waypoint.name.get();
                    if (wpName != null && wpName.equalsIgnoreCase(name)) {
                        match = waypoint;
                        break;
                    }
                }

                if (match == null) {
                    hasTarget = false;
                    return;
                }

                var pos = match.getPos();
                targetX = pos.getX();
                targetY = pos.getY();
                targetZ = pos.getZ();
                hasTarget = true;
            }

            case Coordinates -> {
                targetX = x.get();
                targetZ = z.get();
                targetY = ignoreY.get() ? mc.player.getBlockY() : y.get();
                hasTarget = true;
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!hasTarget || mc.player == null) return;

        double dx = (targetX + 0.5) - mc.player.getX();
        double dy = (targetY + 0.5) - mc.player.getEyeY();
        double dz = (targetZ + 0.5) - mc.player.getZ();

        double distSq = dx * dx + dy * dy + dz * dz;
        double max = maxDistance.get();
        if (distSq > max * max) return;

        if (renderTracer.get()) {
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, targetX + 0.5, targetY + 0.5, targetZ + 0.5, tracerColor.get());
        }

        if (renderMarker.get()) {
            // Small, cheap outline box.
            double h = MathHelper.clamp(Math.sqrt(distSq) / 200.0, 0.5, 2.0);
            event.renderer.box(
                targetX + 0.5 - 0.5, targetY, targetZ + 0.5 - 0.5,
                targetX + 0.5 + 0.5, targetY + h, targetZ + 0.5 + 0.5,
                markerLineColor.get(), markerLineColor.get(), ShapeMode.Lines, 0
            );
        }
    }

    public enum Mode {
        Waypoint,
        Coordinates
    }
}
