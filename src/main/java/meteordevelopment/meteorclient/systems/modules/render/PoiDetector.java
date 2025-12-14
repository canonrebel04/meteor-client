/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

public class PoiDetector extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> detectPortals = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-portals")
        .description("Detects Nether/End portal blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> detectLights = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-underground-lights")
        .description("Detects underground light sources (torches/lanterns/etc.).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> detectChests = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-chest-clusters")
        .description("Detects chests/barrels (higher confidence if clustered).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> samplesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("samples-per-tick")
        .description("How many block positions to sample per tick.")
        .defaultValue(96)
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
        .defaultValue(32)
        .sliderRange(0, 256)
        .build()
    );

    private final Setting<Boolean> requireNotSkyVisible = sgGeneral.add(new BoolSetting.Builder()
        .name("require-not-sky-visible")
        .description("Only consider POIs not directly visible from the sky (helps focus on underground structures).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minAddIntervalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("min-add-interval")
        .description("Minimum ticks between auto-added POIs.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Integer> maxAddsPerSession = sgGeneral.add(new IntSetting.Builder()
        .name("max-adds-per-session")
        .description("Hard cap of auto-created POI waypoints per world/session.")
        .defaultValue(50)
        .min(0)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<String> namePrefix = sgGeneral.add(new StringSetting.Builder()
        .name("name-prefix")
        .description("Prefix for auto-created POI waypoints.")
        .defaultValue("POI")
        .build()
    );

    private final Random rng = new Random();
    private final BlockPos.Mutable samplePos = new BlockPos.Mutable();

    // Per-dimension dedupe: posLong -> lastSeenTick.
    private final Object2ObjectOpenHashMap<String, Long2IntOpenHashMap> seen = new Object2ObjectOpenHashMap<>();

    private int tick;
    private int lastAddTick;
    private int addsThisSession;

    public PoiDetector() {
        super(Categories.Render, "poi-detector", "Detects simple POIs and auto-creates waypoints for review.");
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

        int maxAdds = maxAddsPerSession.get();
        if (maxAdds >= 0 && addsThisSession >= maxAdds) return;

        int r = sampleRadius.get();
        int samples = samplesPerTick.get();

        int minDy = minYOffset.get();
        int maxDy = maxYOffset.get();
        if (minDy > maxDy) {
            int tmp = minDy;
            minDy = maxDy;
            maxDy = tmp;
        }

        int px = MathHelper.floor(mc.player.getX());
        int py = MathHelper.floor(mc.player.getY());
        int pz = MathHelper.floor(mc.player.getZ());

        int worldBottom = mc.world.getBottomY();
        int worldTop = worldBottom + mc.world.getHeight() - 1;

        boolean requireSky = requireNotSkyVisible.get();

        String dimKey = mc.world.getRegistryKey().getValue().toString();
        Long2IntOpenHashMap dimSeen = seen.get(dimKey);
        if (dimSeen == null) {
            dimSeen = new Long2IntOpenHashMap();
            dimSeen.defaultReturnValue(Integer.MIN_VALUE);
            seen.put(dimKey, dimSeen);
        }

        int addCooldown = Math.max(0, minAddIntervalTicks.get());

        for (int i = 0; i < samples; i++) {
            int ox = rng.nextInt(r * 2 + 1) - r;
            int oz = rng.nextInt(r * 2 + 1) - r;
            int oy = rng.nextInt((maxDy - minDy) + 1) + minDy;

            int x = px + ox;
            int y = MathHelper.clamp(py + oy, worldBottom, worldTop);
            int z = pz + oz;

            samplePos.set(x, y, z);

            if (requireSky && mc.world.isSkyVisible(samplePos)) continue;

            BlockState state = mc.world.getBlockState(samplePos);
            Block block = state.getBlock();

            PoiType type = classify(block);
            if (type == null) continue;

            // Per-position dedupe.
            long posLong = samplePos.asLong();
            int lastSeen = dimSeen.get(posLong);
            if (lastSeen != Integer.MIN_VALUE) {
                dimSeen.put(posLong, tick);
                continue;
            }

            double confidence = confidence(type, samplePos, state);
            if (confidence <= 0) continue;

            // Global add rate limit (prevents spam on noisy scenes).
            if (addCooldown > 0 && tick - lastAddTick < addCooldown) {
                dimSeen.put(posLong, tick);
                continue;
            }

            Dimension dim = PlayerUtils.getDimension();

            String label = formatName(type, confidence);
            Waypoint waypoint = new Waypoint.Builder()
                .name(label)
                .icon(iconFor(type))
                .pos(new BlockPos(x, y, z))
                .dimension(dim)
                .build();

            Waypoints.get().add(waypoint);

            dimSeen.put(posLong, tick);
            lastAddTick = tick;
            addsThisSession++;

            if (maxAdds >= 0 && addsThisSession >= maxAdds) return;
        }
    }

    private PoiType classify(Block block) {
        if (block == null) return null;

        if (detectPortals.get()) {
            if (block == Blocks.NETHER_PORTAL) return PoiType.NetherPortal;
            if (block == Blocks.END_PORTAL || block == Blocks.END_PORTAL_FRAME) return PoiType.EndPortal;
        }

        if (detectChests.get()) {
            if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL) return PoiType.Chest;
        }

        if (detectLights.get()) {
            if (isLight(block)) return PoiType.Light;
        }

        return null;
    }

    private boolean isLight(Block block) {
        return block == Blocks.TORCH
            || block == Blocks.WALL_TORCH
            || block == Blocks.SOUL_TORCH
            || block == Blocks.SOUL_WALL_TORCH
            || block == Blocks.LANTERN
            || block == Blocks.SOUL_LANTERN
            || block == Blocks.GLOWSTONE
            || block == Blocks.SEA_LANTERN
            || block == Blocks.SHROOMLIGHT
            || block == Blocks.REDSTONE_TORCH
            || block == Blocks.REDSTONE_WALL_TORCH;
    }

    private double confidence(PoiType type, BlockPos pos, BlockState state) {
        if (mc.world == null) return 0;

        return switch (type) {
            case NetherPortal -> 1.0;
            case EndPortal -> 1.0;
            case Light -> {
                // Prefer underground and non-sky-visible (already filtered optionally).
                double base = 0.60;
                int y = pos.getY();
                if (y <= 40) base += 0.10;
                if (y <= 0) base += 0.05;
                yield Math.min(0.85, base);
            }
            case Chest -> {
                double base = 0.65;
                // Small bounded neighbor scan to detect a cluster.
                boolean clustered = false;
                BlockPos.Mutable m = new BlockPos.Mutable();
                int x = pos.getX();
                int y = pos.getY();
                int z = pos.getZ();

                for (int dx = -2; dx <= 2 && !clustered; dx++) {
                    for (int dz = -2; dz <= 2 && !clustered; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        if (Math.abs(dx) + Math.abs(dz) > 3) continue;

                        m.set(x + dx, y, z + dz);
                        Block b = mc.world.getBlockState(m).getBlock();
                        if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL) {
                            clustered = true;
                        }
                    }
                }

                yield clustered ? 0.85 : base;
            }
        };
    }

    private String formatName(PoiType type, double confidence) {
        String prefix = namePrefix.get();
        if (prefix == null || prefix.isBlank()) prefix = "POI";

        int pct = (int) Math.round(confidence * 100.0);
        return prefix + ": " + type.label + " (" + pct + "%)";
    }

    private String iconFor(PoiType type) {
        return switch (type) {
            case NetherPortal -> "diamond";
            case EndPortal -> "star";
            case Chest -> "square";
            case Light -> "circle";
        };
    }

    private void reset() {
        tick = 0;
        lastAddTick = 0;
        addsThisSession = 0;
        seen.clear();
    }

    private enum PoiType {
        NetherPortal("Nether Portal"),
        EndPortal("End Portal"),
        Light("Underground Light"),
        Chest("Chest/Barrel");

        private final String label;

        PoiType(String label) {
            this.label = label;
        }
    }
}
