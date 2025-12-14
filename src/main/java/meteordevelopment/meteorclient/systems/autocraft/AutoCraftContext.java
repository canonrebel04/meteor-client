/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.autocraft;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Lightweight context bridge between interaction code (which knows which block was clicked)
 * and screen handlers (which can expose inventory contents once opened).
 */
public final class AutoCraftContext {
    private static BlockPos pendingContainerPos;
    private static String pendingDimension;
    private static boolean pendingCapture;

    private static String pendingAutoCraftItemId;
    private static int pendingAutoCraftCount;

    private AutoCraftContext() {
    }

    public static void markNextContainer(BlockPos pos) {
        if (pos == null) return;
        pendingContainerPos = pos.toImmutable();
        pendingDimension = mc.world != null ? mc.world.getRegistryKey().getValue().toString() : "unknown";
        pendingCapture = true;
    }

    public static boolean hasPendingCapture() {
        return pendingCapture;
    }

    public static BlockPos getPendingContainerPos() {
        return pendingContainerPos;
    }

    public static String getPendingDimension() {
        return pendingDimension;
    }

    public static void clearPendingCapture() {
        pendingCapture = false;
        pendingContainerPos = null;
        pendingDimension = null;
    }

    public static void setPendingAutoCraft(Item item, int count) {
        if (item == null) return;
        Identifier id = Registries.ITEM.getId(item);
        if (id == null) return;
        pendingAutoCraftItemId = id.toString();
        pendingAutoCraftCount = Math.max(1, count);
    }

    public static boolean hasPendingAutoCraft() {
        return pendingAutoCraftItemId != null && !pendingAutoCraftItemId.isBlank() && pendingAutoCraftCount > 0;
    }

    public static PendingAutoCraft consumePendingAutoCraft() {
        if (!hasPendingAutoCraft()) return null;
        PendingAutoCraft out = new PendingAutoCraft(pendingAutoCraftItemId, pendingAutoCraftCount);
        pendingAutoCraftItemId = null;
        pendingAutoCraftCount = 0;
        return out;
    }

    public record PendingAutoCraft(String itemId, int count) {
        public Item resolveItem() {
            try {
                return Registries.ITEM.get(Identifier.of(itemId));
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
