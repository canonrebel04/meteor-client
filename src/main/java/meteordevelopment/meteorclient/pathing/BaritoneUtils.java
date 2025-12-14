/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import baritone.api.BaritoneAPI;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.List;

public class BaritoneUtils {
    public static boolean IS_AVAILABLE = false;

    private static int containerProtectionRefs;
    private static List<Block> previousDisallowBreaking;
    private static Boolean previousAllowBreak;

    private static int miningProtectionRefs;
    private static List<Block> miningPreviousDisallowBreaking;
    private static Boolean miningPreviousAllowBreak;

    private BaritoneUtils() {
    }

    public static String getPrefix() {
        if (IS_AVAILABLE) {
            return BaritoneAPI.getSettings().prefix.value;
        }

        return "";
    }

    /**
     * Prevent Baritone from breaking containers/utility blocks while running non-destructive workflows
     * like storage scanning and auto-crafting.
     */
    public static void acquireContainerProtection() {
        if (!IS_AVAILABLE) return;

        containerProtectionRefs++;
        if (containerProtectionRefs != 1) return;

        try {
            var settings = BaritoneAPI.getSettings();
            previousDisallowBreaking = new ArrayList<>(settings.blocksToDisallowBreaking.value);

            // Strong safety: do not break anything during these workflows.
            previousAllowBreak = settings.allowBreak.value;
            settings.allowBreak.value = false;

            // Also explicitly disallow breaking of any block-entity block (usually player-made utility blocks).
            for (Block b : Registries.BLOCK) {
                if (b instanceof BlockEntityProvider) addIfMissing(settings.blocksToDisallowBreaking.value, b);
            }

            // Enforce strict non-destruction for storage/automation blocks.
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.CHEST);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.TRAPPED_CHEST);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.BARREL);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.HOPPER);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.CRAFTING_TABLE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.FURNACE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.BLAST_FURNACE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.SMOKER);

            // Redstone components (avoid destroying player contraptions).
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.REDSTONE_WIRE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.REDSTONE_TORCH);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.REDSTONE_WALL_TORCH);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.REPEATER);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.COMPARATOR);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.OBSERVER);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.PISTON);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.STICKY_PISTON);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.DISPENSER);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.DROPPER);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.LEVER);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.STONE_BUTTON);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.OAK_BUTTON);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.STONE_PRESSURE_PLATE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.TRIPWIRE_HOOK);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.DAYLIGHT_DETECTOR);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.TARGET);
        } catch (Throwable ignored) {
            // Best-effort.
        }
    }

    public static void releaseContainerProtection() {
        if (!IS_AVAILABLE) return;
        if (containerProtectionRefs <= 0) return;

        containerProtectionRefs--;
        if (containerProtectionRefs != 0) return;

        try {
            if (previousDisallowBreaking != null) {
                BaritoneAPI.getSettings().blocksToDisallowBreaking.value = new ArrayList<>(previousDisallowBreaking);
            }

            if (previousAllowBreak != null) {
                BaritoneAPI.getSettings().allowBreak.value = previousAllowBreak;
            }
        } catch (Throwable ignored) {
        } finally {
            previousDisallowBreaking = null;
            previousAllowBreak = null;
        }
    }

    /**
     * Mining-oriented protection: keep Baritone's breaking enabled, but still disallow breaking containers,
     * redstone, and block-entity blocks. This helps avoid griefing while still allowing basic mining.
     *
     * Note: If container protection is active, this will not override it.
     */
    public static void acquireMiningProtection() {
        if (!IS_AVAILABLE) return;

        // Never override the stricter no-break workflow.
        if (containerProtectionRefs > 0) return;

        miningProtectionRefs++;
        if (miningProtectionRefs != 1) return;

        try {
            var settings = BaritoneAPI.getSettings();
            miningPreviousDisallowBreaking = new ArrayList<>(settings.blocksToDisallowBreaking.value);

            miningPreviousAllowBreak = settings.allowBreak.value;
            settings.allowBreak.value = true;

            for (Block b : Registries.BLOCK) {
                if (b instanceof BlockEntityProvider) addIfMissing(settings.blocksToDisallowBreaking.value, b);
            }

            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.CHEST);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.TRAPPED_CHEST);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.BARREL);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.HOPPER);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.CRAFTING_TABLE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.FURNACE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.BLAST_FURNACE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.SMOKER);

            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.REDSTONE_WIRE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.REDSTONE_TORCH);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.REDSTONE_WALL_TORCH);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.REPEATER);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.COMPARATOR);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.OBSERVER);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.PISTON);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.STICKY_PISTON);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.DISPENSER);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.DROPPER);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.LEVER);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.STONE_BUTTON);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.OAK_BUTTON);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.STONE_PRESSURE_PLATE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.TRIPWIRE_HOOK);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.DAYLIGHT_DETECTOR);
            addIfMissing(settings.blocksToDisallowBreaking.value, Blocks.TARGET);
        } catch (Throwable ignored) {
        }
    }

    public static void releaseMiningProtection() {
        if (!IS_AVAILABLE) return;
        if (miningProtectionRefs <= 0) return;

        miningProtectionRefs--;
        if (miningProtectionRefs != 0) return;

        try {
            if (miningPreviousDisallowBreaking != null) {
                BaritoneAPI.getSettings().blocksToDisallowBreaking.value = new ArrayList<>(miningPreviousDisallowBreaking);
            }

            if (miningPreviousAllowBreak != null) {
                BaritoneAPI.getSettings().allowBreak.value = miningPreviousAllowBreak;
            }
        } catch (Throwable ignored) {
        } finally {
            miningPreviousDisallowBreaking = null;
            miningPreviousAllowBreak = null;
        }
    }

    private static void addIfMissing(List<Block> list, Block block) {
        if (list == null || block == null) return;
        if (!list.contains(block)) list.add(block);
    }
}
