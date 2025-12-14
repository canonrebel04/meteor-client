/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

public class CaveFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> samplesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("samples-per-tick")
        .description("How many candidate positions to sample per tick.")
        .defaultValue(128)
        .min(1)
        .sliderRange(1, 2048)
        .build()
    );

    private final Setting<Integer> sampleRadius = sgGeneral.add(new IntSetting.Builder()
        .name("sample-radius")
        .description("Sampling radius (in blocks) around the player.")
        .defaultValue(64)
        .min(8)
        .sliderRange(8, 512)
        .build()
    );

    private final Setting<Integer> minYOffset = sgGeneral.add(new IntSetting.Builder()
        .name("min-y-offset")
        .description("Minimum Y offset (relative to player) for sampling.")
        .defaultValue(-64)
        .sliderRange(-256, 0)
        .build()
    );

    private final Setting<Integer> maxYOffset = sgGeneral.add(new IntSetting.Builder()
        .name("max-y-offset")
        .description("Maximum Y offset (relative to player) for sampling.")
        .defaultValue(16)
        .sliderRange(0, 256)
        .build()
    );

    private final Setting<Integer> requiredSolidNeighbors = sgGeneral.add(new IntSetting.Builder()
        .name("required-solid-neighbors")
        .description("Minimum number of solid neighbor blocks (out of 6) for a sample to count as a cave candidate.")
        .defaultValue(3)
        .min(0)
        .sliderRange(0, 6)
        .build()
    );

    private final Setting<Boolean> requireNotSkyVisible = sgGeneral.add(new BoolSetting.Builder()
        .name("require-not-sky-visible")
        .description("Only count air blocks that are not directly exposed to the sky.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxMarkers = sgGeneral.add(new IntSetting.Builder()
        .name("max-markers")
        .description("Maximum number of cave markers to keep.")
        .defaultValue(1024)
        .min(16)
        .sliderRange(16, 8192)
        .build()
    );

    private final Setting<Double> markerSeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("marker-seconds")
        .description("How long a marker stays visible.")
        .defaultValue(20)
        .min(0.25)
        .sliderMin(1)
        .sliderMax(120)
        .build()
    );

    private final Setting<Integer> renderRadius = sgGeneral.add(new IntSetting.Builder()
        .name("render-radius")
        .description("Only render markers within this radius (in blocks) of the player.")
        .defaultValue(128)
        .min(8)
        .sliderRange(8, 1024)
        .build()
    );

    private final Setting<Integer> maxRendered = sgGeneral.add(new IntSetting.Builder()
        .name("max-rendered")
        .description("Maximum number of markers rendered per frame.")
        .defaultValue(512)
        .min(1)
        .sliderRange(16, 8192)
        .build()
    );

    private final Setting<Double> markerSize = sgGeneral.add(new DoubleSetting.Builder()
        .name("marker-size")
        .description("Size of the marker box.")
        .defaultValue(0.4)
        .min(0.05)
        .sliderMin(0.1)
        .sliderMax(2)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How markers are rendered.")
        .defaultValue(ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Marker fill color.")
        .defaultValue(new SettingColor(0, 255, 255, 25))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Marker outline color.")
        .defaultValue(new SettingColor(0, 255, 255, 200))
        .build()
    );

    private final Random rng = new Random();

    private long[] markerPos = new long[0];
    private int[] markerExpiryTick = new int[0];
    private int markerCount;

    private int tick;

    private final BlockPos.Mutable samplePos = new BlockPos.Mutable();

    public CaveFinder() {
        super(Categories.Render, "cave-finder", "Finds nearby cave openings using bounded random sampling.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        reset();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        reset();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) {
            reset();
            return;
        }

        tick++;

        pruneExpired();

        int cap = maxMarkers.get();
        ensureCapacity(cap);

        int samples = samplesPerTick.get();
        int r = sampleRadius.get();

        int minDy = minYOffset.get();
        int maxDy = maxYOffset.get();
        if (minDy > maxDy) {
            int tmp = minDy;
            minDy = maxDy;
            maxDy = tmp;
        }

        int playerX = MathHelper.floor(mc.player.getX());
        int playerY = MathHelper.floor(mc.player.getY());
        int playerZ = MathHelper.floor(mc.player.getZ());

        int worldBottom = mc.world.getBottomY();
        int worldTop = worldBottom + mc.world.getHeight() - 1;

        int requiredSolid = MathHelper.clamp(requiredSolidNeighbors.get(), 0, 6);
        boolean requireSky = requireNotSkyVisible.get();

        int ttlTicks = (int) Math.round(markerSeconds.get() * 20.0);
        if (ttlTicks < 1) ttlTicks = 1;

        for (int i = 0; i < samples && markerCount < cap; i++) {
            int ox = rng.nextInt(r * 2 + 1) - r;
            int oz = rng.nextInt(r * 2 + 1) - r;
            int oy = rng.nextInt((maxDy - minDy) + 1) + minDy;

            int x = playerX + ox;
            int y = MathHelper.clamp(playerY + oy, worldBottom, worldTop);
            int z = playerZ + oz;

            samplePos.set(x, y, z);

            if (!mc.world.isAir(samplePos)) continue;
            if (requireSky && mc.world.isSkyVisible(samplePos)) continue;

            int solidNeighbors = 0;
            if (!mc.world.getBlockState(samplePos.north()).isAir()) solidNeighbors++;
            if (!mc.world.getBlockState(samplePos.south()).isAir()) solidNeighbors++;
            if (!mc.world.getBlockState(samplePos.east()).isAir()) solidNeighbors++;
            if (!mc.world.getBlockState(samplePos.west()).isAir()) solidNeighbors++;
            if (!mc.world.getBlockState(samplePos.up()).isAir()) solidNeighbors++;
            if (!mc.world.getBlockState(samplePos.down()).isAir()) solidNeighbors++;

            if (solidNeighbors < requiredSolid) continue;

            long packed = samplePos.asLong();
            if (containsMarker(packed)) continue;

            markerPos[markerCount] = packed;
            markerExpiryTick[markerCount] = tick + ttlTicks;
            markerCount++;
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        if (markerCount == 0) return;

        int max = maxRendered.get();
        if (max < 1) return;

        int px = MathHelper.floor(mc.player.getX());
        int py = MathHelper.floor(mc.player.getY());
        int pz = MathHelper.floor(mc.player.getZ());

        int rr = renderRadius.get();
        int rrSq = rr * rr;

        double s = markerSize.get();
        double half = s / 2.0;

        int rendered = 0;

        for (int i = 0; i < markerCount && rendered < max; i++) {
            long packed = markerPos[i];
            int x = BlockPos.unpackLongX(packed);
            int y = BlockPos.unpackLongY(packed);
            int z = BlockPos.unpackLongZ(packed);

            int dx = x - px;
            int dy = y - py;
            int dz = z - pz;

            if (dx * dx + dy * dy + dz * dz > rrSq) continue;

            event.renderer.box(
                (x + 0.5) - half, (y + 0.5) - half, (z + 0.5) - half,
                (x + 0.5) + half, (y + 0.5) + half, (z + 0.5) + half,
                sideColor.get(), lineColor.get(), shapeMode.get(), 0
            );
            rendered++;
        }
    }

    private void pruneExpired() {
        if (markerCount == 0) return;

        int write = 0;
        for (int i = 0; i < markerCount; i++) {
            if (markerExpiryTick[i] <= tick) continue;

            if (write != i) {
                markerPos[write] = markerPos[i];
                markerExpiryTick[write] = markerExpiryTick[i];
            }
            write++;
        }

        markerCount = write;
    }

    private boolean containsMarker(long packed) {
        for (int i = 0; i < markerCount; i++) {
            if (markerPos[i] == packed) return true;
        }
        return false;
    }

    private void ensureCapacity(int cap) {
        if (markerPos.length >= cap) return;

        markerPos = new long[cap];
        markerExpiryTick = new int[cap];
        markerCount = 0;
    }

    private void reset() {
        tick = 0;
        markerCount = 0;
    }
}
