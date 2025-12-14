/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class NewChunks extends Module {
    private static final int SAVE_VERSION = 1;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> maxSeen = sgGeneral.add(new IntSetting.Builder()
        .name("max-seen-chunks")
        .description("Maximum number of seen chunks kept per dimension.")
        .defaultValue(50_000)
        .min(1_000)
        .sliderMin(1_000)
        .sliderMax(500_000)
        .build()
    );

    private final Setting<Integer> highlightCount = sgGeneral.add(new IntSetting.Builder()
        .name("highlight-count")
        .description("Maximum number of newly discovered chunks to keep highlighted.")
        .defaultValue(512)
        .min(16)
        .sliderMin(64)
        .sliderMax(4096)
        .build()
    );

    private final Setting<Double> highlightSeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("highlight-seconds")
        .description("How long newly discovered chunks stay highlighted.")
        .defaultValue(30)
        .min(1)
        .sliderMin(1)
        .sliderMax(300)
        .build()
    );

    private final Setting<Integer> renderRadius = sgGeneral.add(new IntSetting.Builder()
        .name("render-radius-chunks")
        .description("Only render highlighted chunks within this radius (in chunks) of the player.")
        .defaultValue(12)
        .min(1)
        .sliderMin(1)
        .sliderMax(64)
        .build()
    );

    private final Setting<Integer> maxRendered = sgGeneral.add(new IntSetting.Builder()
        .name("max-rendered")
        .description("Maximum number of chunk highlights rendered per frame.")
        .defaultValue(256)
        .min(1)
        .sliderMin(32)
        .sliderMax(2048)
        .build()
    );

    private final Setting<Double> boxHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("box-height")
        .description("Height of the rendered highlight box.")
        .defaultValue(1.0)
        .min(0.05)
        .sliderMin(0.05)
        .sliderMax(10)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the highlights are rendered.")
        .defaultValue(ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Highlight fill color.")
        .defaultValue(new SettingColor(0, 255, 255, 25))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Highlight outline color.")
        .defaultValue(new SettingColor(0, 255, 255, 200))
        .build()
    );

    private final Object2ObjectOpenHashMap<String, Tracker> trackers = new Object2ObjectOpenHashMap<>();

    private int tick;

    public NewChunks() {
        super(Categories.Render, "new-chunks", "Highlights newly discovered chunks.");
    }

    @Override
    public void onActivate() {
        resetAll();
        loadAsync();
    }

    @Override
    public void onDeactivate() {
        saveAsync();
        resetAll();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        // New world/server session.
        resetAll();
        loadAsync();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        saveAsync();
        resetAll();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        tick++;

        int ttlTicks = (int) Math.round(highlightSeconds.get() * 20.0);
        if (ttlTicks < 1) ttlTicks = 1;

        for (Tracker tracker : trackers.values()) tracker.pruneRecent(tick, ttlTicks, highlightCount.get());
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.world == null) return;

        String dim = mc.world.getRegistryKey().getValue().toString();
        Tracker tracker = trackers.get(dim);
        if (tracker == null) {
            tracker = new Tracker();
            trackers.put(dim, tracker);
        }

        ChunkPos pos = event.chunk().getPos();
        long key = pos.toLong();

        if (tracker.seen.contains(key)) return;

        tracker.addSeen(key, maxSeen.get());
        tracker.addRecent(key, tick, highlightCount.get());
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        Tracker tracker = trackers.get(mc.world.getRegistryKey().getValue().toString());
        if (tracker == null || tracker.recentSize == 0) return;

        int playerBlockX = MathHelper.floor(mc.player.getX());
        int playerBlockZ = MathHelper.floor(mc.player.getZ());

        int playerChunkX = playerBlockX >> 4;
        int playerChunkZ = playerBlockZ >> 4;

        int radius = renderRadius.get();
        int radiusSq = radius * radius;

        double y1 = mc.player.getY();
        double y2 = y1 + boxHeight.get();

        int rendered = 0;
        int max = maxRendered.get();

        long[] recentKeys = tracker.recentKeys;
        int size = tracker.recentSize;

        for (int i = 0; i < size && rendered < max; i++) {
            long key = recentKeys[i];

            int chunkX = (int) key;
            int chunkZ = (int) (key >>> 32);

            int dx = chunkX - playerChunkX;
            int dz = chunkZ - playerChunkZ;

            if (dx * dx + dz * dz > radiusSq) continue;

            int x1 = chunkX << 4;
            int z1 = chunkZ << 4;

            event.renderer.box(x1, y1, z1, x1 + 16, y2, z1 + 16, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            rendered++;
        }
    }

    private void resetAll() {
        trackers.clear();
        tick = 0;
    }

    private static File getSaveFile() {
        // Keep data isolated per world/server (same convention as StashFinder).
        return new File(new File(new File(MeteorClient.FOLDER, "newchunks"), Utils.getFileWorldName()), "seen-v1.dat");
    }

    private void loadAsync() {
        // Avoid doing IO on the client thread.
        MeteorExecutor.execute(() -> {
            Object2ObjectOpenHashMap<String, long[]> loaded;
            try {
                loaded = loadFromDisk(getSaveFile());
            } catch (Exception e) {
                MeteorClient.LOG.error("NewChunks: failed to load seen chunks", e);
                return;
            }

            if (loaded.isEmpty()) return;

            MinecraftClient client = mc;
            if (client == null) return;

            client.execute(() -> applyLoaded(loaded));
        });
    }

    private void applyLoaded(Object2ObjectOpenHashMap<String, long[]> loaded) {
        int max = maxSeen.get();

        for (var entry : loaded.object2ObjectEntrySet()) {
            Tracker tracker = new Tracker();
            long[] keys = entry.getValue();
            int n = Math.min(keys.length, max);

            for (int i = 0; i < n; i++) tracker.addSeen(keys[i], max);

            trackers.put(entry.getKey(), tracker);
        }
    }

    private void saveAsync() {
        Object2ObjectOpenHashMap<String, long[]> snapshot = snapshotSeen();
        if (snapshot.isEmpty()) return;

        MeteorExecutor.execute(() -> {
            try {
                saveToDisk(getSaveFile(), snapshot);
            } catch (Exception e) {
                MeteorClient.LOG.error("NewChunks: failed to save seen chunks", e);
            }
        });
    }

    private Object2ObjectOpenHashMap<String, long[]> snapshotSeen() {
        Object2ObjectOpenHashMap<String, long[]> out = new Object2ObjectOpenHashMap<>();
        int max = maxSeen.get();

        for (var entry : trackers.object2ObjectEntrySet()) {
            String dimKey = entry.getKey();
            Tracker tracker = entry.getValue();
            if (tracker == null || tracker.seen.isEmpty()) continue;

            long[] seen = tracker.seen.toLongArray();
            if (seen.length > max) {
                long[] trimmed = new long[max];
                System.arraycopy(seen, 0, trimmed, 0, max);
                seen = trimmed;
            }

            out.put(dimKey, seen);
        }

        return out;
    }

    private static Object2ObjectOpenHashMap<String, long[]> loadFromDisk(File file) throws IOException {
        Object2ObjectOpenHashMap<String, long[]> out = new Object2ObjectOpenHashMap<>();

        if (!file.exists()) return out;

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int version = in.readInt();
            if (version != SAVE_VERSION) return out;

            int dims = in.readInt();
            if (dims < 0 || dims > 128) return out;

            for (int i = 0; i < dims; i++) {
                String dimId = in.readUTF();
                int count = in.readInt();
                if (count < 0) count = 0;
                if (count > 2_000_000) count = 2_000_000;

                long[] keys = new long[count];
                for (int j = 0; j < count; j++) keys[j] = in.readLong();

                out.put(dimId, keys);
            }
        }

        return out;
    }

    private static void saveToDisk(File file, Object2ObjectOpenHashMap<String, long[]> snapshot) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(SAVE_VERSION);
            out.writeInt(snapshot.size());

            for (var entry : snapshot.object2ObjectEntrySet()) {
                out.writeUTF(entry.getKey());

                long[] keys = entry.getValue();
                out.writeInt(keys.length);
                for (long key : keys) out.writeLong(key);
            }
        }
    }

    private static final class Tracker {
        private final LongOpenHashSet seen = new LongOpenHashSet();
        private final LongArrayFIFOQueue seenOrder = new LongArrayFIFOQueue();

        private long[] recentKeys = new long[0];
        private int[] recentTicks = new int[0];
        private int recentSize;

        private void addSeen(long key, int maxSeen) {
            seen.add(key);
            seenOrder.enqueue(key);

            while (seen.size() > maxSeen && !seenOrder.isEmpty()) {
                long evicted = seenOrder.dequeueLong();
                seen.remove(evicted);
            }
        }

        private void addRecent(long key, int tick, int maxRecent) {
            ensureRecentCapacity(maxRecent);

            if (recentSize == recentKeys.length) {
                // If we somehow get here, drop the oldest entry.
                shiftLeft(1);
            }

            recentKeys[recentSize] = key;
            recentTicks[recentSize] = tick;
            recentSize++;
        }

        private void pruneRecent(int tick, int ttlTicks, int maxRecent) {
            ensureRecentCapacity(maxRecent);

            int write = 0;

            for (int read = 0; read < recentSize; read++) {
                int age = tick - recentTicks[read];
                if (age > ttlTicks) continue;

                if (write != read) {
                    recentKeys[write] = recentKeys[read];
                    recentTicks[write] = recentTicks[read];
                }

                write++;
            }

            recentSize = write;

            if (recentSize > maxRecent) {
                int drop = recentSize - maxRecent;
                shiftLeft(drop);
            }
        }

        private void ensureRecentCapacity(int maxRecent) {
            if (maxRecent < 1) maxRecent = 1;
            if (recentKeys.length == maxRecent) return;

            long[] newKeys = new long[maxRecent];
            int[] newTicks = new int[maxRecent];

            int copy = Math.min(recentSize, maxRecent);
            System.arraycopy(recentKeys, 0, newKeys, 0, copy);
            System.arraycopy(recentTicks, 0, newTicks, 0, copy);

            recentKeys = newKeys;
            recentTicks = newTicks;
            recentSize = copy;
        }

        private void shiftLeft(int count) {
            if (count <= 0) return;
            if (count >= recentSize) {
                recentSize = 0;
                return;
            }

            int remaining = recentSize - count;
            System.arraycopy(recentKeys, count, recentKeys, 0, remaining);
            System.arraycopy(recentTicks, count, recentTicks, 0, remaining);
            recentSize = remaining;
        }
    }
}
