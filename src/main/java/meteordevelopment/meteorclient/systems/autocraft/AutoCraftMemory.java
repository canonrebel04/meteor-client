/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.autocraft;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class AutoCraftMemory extends System<AutoCraftMemory> {
    private final List<ChestSnapshot> chests = new ArrayList<>();
    private boolean useOnlySelectedContainers;

    public AutoCraftMemory() {
        super("autocraft");
    }

    public static AutoCraftMemory get() {
        return Systems.get(AutoCraftMemory.class);
    }

    /** Clears only remembered container snapshots (does not reset settings). */
    public void clearChests() {
        chests.clear();
        save();
    }

    /** Full reset of AutoCraft memory (snapshots + settings). */
    public void clear() {
        chests.clear();
        useOnlySelectedContainers = false;
        save();
    }

    public boolean useOnlySelectedContainers() {
        return useOnlySelectedContainers;
    }

    public void setUseOnlySelectedContainers(boolean enabled) {
        if (useOnlySelectedContainers == enabled) return;
        useOnlySelectedContainers = enabled;
        save();
    }

    public int chestCount() {
        return chests.size();
    }

    public int enabledChestCount() {
        int out = 0;
        for (ChestSnapshot s : chests) {
            if (s != null && s.enabled) out++;
        }
        return out;
    }

    public List<ChestSnapshot> snapshots() {
        return Collections.unmodifiableList(chests);
    }

    public BlockPos findBestContainerFor(Object2IntOpenHashMap<Item> needed, BlockPos playerPos) {
        if (needed == null || needed.isEmpty()) return null;
        if (mc.world == null || playerPos == null) return null;

        String dim = mc.world.getRegistryKey().getValue().toString();

        BlockPos bestPos = null;
        int bestKinds = 0;
        int bestScore = 0;
        double bestDistSq = Double.POSITIVE_INFINITY;

        for (ChestSnapshot s : chests) {
            if (s == null) continue;
            if (!isSnapshotAllowedBySelection(s)) continue;
            if (!dim.equals(s.dimensionId)) continue;

            int kinds = 0;
            int score = 0;
            for (var e : needed.object2IntEntrySet()) {
                Item item = e.getKey();
                int need = e.getIntValue();
                if (item == null || need <= 0) continue;

                Identifier id = Registries.ITEM.getId(item);
                if (id == null) continue;

                int have = s.countOf(id.toString());
                if (have <= 0) continue;
                kinds++;
                score += Math.min(have, need);
            }

            if (score <= 0) continue;

            BlockPos pos = s.toBlockPos();
            double distSq = pos.getSquaredDistance(playerPos);

            if (kinds > bestKinds
                || (kinds == bestKinds && score > bestScore)
                || (kinds == bestKinds && score == bestScore && distSq < bestDistSq)) {
                bestKinds = kinds;
                bestScore = score;
                bestDistSq = distSq;
                bestPos = pos;
            }
        }

        return bestPos;
    }

    public int countInChests(Item item) {
        if (item == null) return 0;
        Identifier id = Registries.ITEM.getId(item);
        if (id == null) return 0;

        int total = 0;
        for (ChestSnapshot s : chests) {
            if (s == null) continue;
            if (!isSnapshotAllowedBySelection(s)) continue;
            total += s.countOf(id.toString());
        }
        return total;
    }

    public Object2IntOpenHashMap<Item> aggregateAllItems() {
        Object2IntOpenHashMap<Item> out = new Object2IntOpenHashMap<>();
        for (ChestSnapshot s : chests) {
            if (s == null) continue;
            if (!isSnapshotAllowedBySelection(s)) continue;
            s.addTo(out);
        }
        return out;
    }

    public void setSnapshotEnabled(ChestSnapshot snapshot, boolean enabled) {
        if (snapshot == null) return;

        for (ChestSnapshot s : chests) {
            if (s == null) continue;
            if (s.sameKey(snapshot)) {
                if (s.enabled == enabled) return;
                s.enabled = enabled;
                save();
                return;
            }
        }
    }

    public void setAllSnapshotsEnabled(boolean enabled) {
        boolean changed = false;
        for (ChestSnapshot s : chests) {
            if (s == null) continue;
            if (s.enabled != enabled) {
                s.enabled = enabled;
                changed = true;
            }
        }
        if (changed) save();
    }

    /**
     * Records the first (container) portion of a screen handler's slots as a chest snapshot.
     * This is best-effort: it relies on the caller to only invoke it for storage-like containers.
     */
    public void recordContainer(BlockPos pos, String dimensionId, List<ItemStack> containerStacks) {
        if (pos == null || containerStacks == null) return;
        if (dimensionId == null) dimensionId = "unknown";

        ChestSnapshot snapshot = new ChestSnapshot(dimensionId, pos.getX(), pos.getY(), pos.getZ());
        for (ItemStack s : containerStacks) {
            if (s == null || s.isEmpty()) continue;
            Identifier id = Registries.ITEM.getId(s.getItem());
            if (id == null) continue;
            snapshot.add(id.toString(), s.getCount());
        }

        upsert(snapshot);
        save();
    }

    private void upsert(ChestSnapshot snapshot) {
        for (int i = 0; i < chests.size(); i++) {
            ChestSnapshot s = chests.get(i);
            if (s.sameKey(snapshot)) {
                // Preserve selection state across rescans.
                snapshot.enabled = s.enabled;
                chests.set(i, snapshot);
                return;
            }
        }
        chests.add(snapshot);
    }

    private boolean isSnapshotAllowedBySelection(ChestSnapshot snapshot) {
        if (snapshot == null) return false;
        if (!useOnlySelectedContainers) return true;
        return snapshot.enabled;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();

        tag.putBoolean("useOnlySelected", useOnlySelectedContainers);

        NbtList list = new NbtList();
        for (ChestSnapshot s : chests) {
            NbtCompound c = new NbtCompound();
            c.putString("dim", s.dimensionId);
            c.putInt("x", s.x);
            c.putInt("y", s.y);
            c.putInt("z", s.z);
            c.putBoolean("enabled", s.enabled);

            NbtList items = new NbtList();
            for (ItemCount ic : s.items) {
                NbtCompound it = new NbtCompound();
                it.putString("id", ic.id);
                it.putInt("count", ic.count);
                items.add(it);
            }
            c.put("items", items);

            list.add(c);
        }
        tag.put("chests", list);

        return tag;
    }

    @Override
    public AutoCraftMemory fromTag(NbtCompound tag) {
        chests.clear();

        useOnlySelectedContainers = tag.getBoolean("useOnlySelected", false);

        NbtList list = tag.getListOrEmpty("chests");
        for (NbtElement el : list) {
            if (!(el instanceof NbtCompound c)) continue;

            String dim = c.getString("dim", "unknown");
            int x = c.getInt("x", 0);
            int y = c.getInt("y", 0);
            int z = c.getInt("z", 0);

            ChestSnapshot s = new ChestSnapshot(dim, x, y, z);
            s.enabled = c.getBoolean("enabled", true);

            NbtList items = c.getListOrEmpty("items");
            for (NbtElement el2 : items) {
                if (!(el2 instanceof NbtCompound it)) continue;
                String id = it.getString("id", "");
                int count = it.getInt("count", 0);
                if (id == null || id.isBlank() || count <= 0) continue;
                s.add(id, count);
            }

            chests.add(s);
        }

        return this;
    }

    public record ItemCount(String id, int count) {
    }

    public static final class ChestSnapshot {
        final String dimensionId;
        final int x;
        final int y;
        final int z;
        boolean enabled = true;
        final List<ItemCount> items = new ArrayList<>();

        ChestSnapshot(String dimensionId, int x, int y, int z) {
            this.dimensionId = dimensionId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public String getDimensionId() {
            return dimensionId;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getZ() {
            return z;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }

        boolean sameKey(ChestSnapshot other) {
            return other != null
                && x == other.x
                && y == other.y
                && z == other.z
                && dimensionId.equals(other.dimensionId);
        }

        void add(String id, int count) {
            if (id == null || id.isBlank() || count <= 0) return;

            for (int i = 0; i < items.size(); i++) {
                ItemCount ic = items.get(i);
                if (ic.id.equals(id)) {
                    items.set(i, new ItemCount(id, ic.count + count));
                    return;
                }
            }

            items.add(new ItemCount(id, count));
        }

        int countOf(String id) {
            if (id == null || id.isBlank()) return 0;
            int total = 0;
            for (ItemCount ic : items) {
                if (id.equals(ic.id)) total += ic.count;
            }
            return total;
        }

        void addTo(Object2IntOpenHashMap<Item> map) {
            for (ItemCount ic : items) {
                try {
                    Item item = Registries.ITEM.get(Identifier.of(ic.id));
                    if (item != null) map.addTo(item, ic.count);
                } catch (Throwable ignored) {
                    // Ignore malformed ids.
                }
            }
        }
    }
}
