/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.autocraft.AutoCraftContext;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public class StorageScanner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> searchRadius = sgGeneral.add(new IntSetting.Builder()
            .name("search-radius")
            .description("Radius (in blocks) to look for storage blocks.")
            .defaultValue(24)
            .min(4)
            .sliderRange(4, 128)
            .build());

    private final Setting<Integer> yRange = sgGeneral.add(new IntSetting.Builder()
            .name("y-range")
            .description("Vertical range relative to player to sample for storage blocks.")
            .defaultValue(4)
            .min(0)
            .sliderRange(0, 32)
            .build());

    private final Setting<Integer> samplesPerTick = sgGeneral.add(new IntSetting.Builder()
            .name("samples-per-tick")
            .description("How many random positions to sample per tick when searching.")
            .defaultValue(260)
            .min(1)
            .sliderRange(1, 4096)
            .build());

    private final Setting<Integer> interactCooldownTicks = sgGeneral.add(new IntSetting.Builder()
            .name("interact-cooldown")
            .description("Minimum ticks between interaction attempts.")
            .defaultValue(12)
            .min(0)
            .sliderRange(0, 200)
            .build());

    private final Setting<Integer> maxContainersPerRun = sgGeneral.add(new IntSetting.Builder()
            .name("max-containers")
            .description("Stop after opening this many containers.")
            .defaultValue(12)
            .min(1)
            .sliderRange(1, 100)
            .build());

    private final Setting<Boolean> includeBarrels = sgGeneral.add(new BoolSetting.Builder()
            .name("include-barrels")
            .description("Treat barrels as storage to scan.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> stopOnDisable = sgGeneral.add(new BoolSetting.Builder()
            .name("stop-on-disable")
            .description("Stop pathing when this module is disabled.")
            .defaultValue(true)
            .build());

    private final Random rng = new Random();
    private final BlockPos.Mutable samplePos = new BlockPos.Mutable();

    private long targetPos = Long.MIN_VALUE;
    private int tick;
    private int lastInteractTick;
    private int opened;

    public StorageScanner() {
        super(Categories.World, "storage-scanner",
                "Opens nearby storage blocks and records their inventory into AutoCraft memory.");
    }

    @Override
    public void onActivate() {
        BaritoneUtils.setSetting("allowInventory",
                (Boolean) BaritoneUtils.getSettingDefaultValue(BaritoneUtils.getSettings().allowInventory));
        tick = 0;
        lastInteractTick = 0;
        opened = 0;
        targetPos = Long.MIN_VALUE;
    }

    @Override
    public void onDeactivate() {
        if (stopOnDisable.get())
            PathManagers.get().stop();
        BaritoneUtils.releaseContainerProtection();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate())
            return;

        tick++;

        if (opened >= maxContainersPerRun.get()) {
            info("Scanned %d containers.".formatted(opened));

            // If the UI queued an AutoCraft request, start it after we stop scanning.
            startPendingAutoCraftIfAny();

            toggle();
            return;
        }

        if (targetPos == Long.MIN_VALUE || !isValidStorage(BlockPos.fromLong(targetPos))) {
            if (!findNearbyStorage())
                return;
        }

        BlockPos pos = BlockPos.fromLong(targetPos);

        double distSq = mc.player.squaredDistanceTo(Vec3d.ofCenter(pos));
        double interactRange = mc.player.getBlockInteractionRange();

        if (distSq <= interactRange * interactRange) {
            int cooldown = Math.max(0, interactCooldownTicks.get());
            if (cooldown > 0 && tick - lastInteractTick < cooldown)
                return;

            AutoCraftContext.markNextContainer(pos);
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            BlockUtils.interact(hit, Hand.MAIN_HAND, true);

            lastInteractTick = tick;
            opened++;
            // Force a new target next.
            targetPos = Long.MIN_VALUE;
            return;
        }

        if (!PathManagers.get().isPathing()) {
            BaritoneUtils.setSetting("allowInventory", true);
        }
    }

    private boolean findNearbyStorage() {
        int r = Math.max(1, searchRadius.get());
        int yR = Math.max(0, yRange.get());
        int samples = Math.max(1, samplesPerTick.get());

        int px = mc.player.getBlockX();
        int py = mc.player.getBlockY();
        int pz = mc.player.getBlockZ();

        int worldBottom = mc.world.getBottomY();
        int worldTop = worldBottom + mc.world.getHeight() - 1;

        long best = Long.MIN_VALUE;
        double bestDistSq = Double.POSITIVE_INFINITY;

        for (int i = 0; i < samples; i++) {
            int ox = rng.nextInt(r * 2 + 1) - r;
            int oz = rng.nextInt(r * 2 + 1) - r;
            int oy = rng.nextInt(yR * 2 + 1) - yR;

            int x = px + ox;
            int y = MathHelper.clamp(py + oy, worldBottom, worldTop);
            int z = pz + oz;

            samplePos.set(x, y, z);

            BlockState state = mc.world.getBlockState(samplePos);
            if (!isAllowedStorage(state.getBlock()))
                continue;
            if (!state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.LAVA))
                continue;

            double distSq = mc.player.squaredDistanceTo(Vec3d.ofCenter(samplePos));
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = samplePos.asLong();
            }
        }

        if (best != Long.MIN_VALUE) {
            targetPos = best;
            return true;
        }

        return false;
    }

    private boolean isAllowedStorage(Block block) {
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST)
            return true;
        if (block == Blocks.BARREL)
            return includeBarrels.get();
        return false;
    }

    private boolean isValidStorage(BlockPos pos) {
        Block block = mc.world.getBlockState(pos).getBlock();
        return isAllowedStorage(block);
    }

    private void startPendingAutoCraftIfAny() {
        try {
            var pending = AutoCraftContext.consumePendingAutoCraft();
            if (pending == null)
                return;

            Item target = pending.resolveItem();
            if (target == null)
                return;

            var mod = Modules.get().get(AutoCraft.class);
            if (mod == null)
                return;

            mod.setTarget(target, pending.count());
            if (!mod.isActive()) {
                mod.toggle();
                mod.sendToggledMsg();
            }
        } catch (Throwable ignored) {
        }
    }
}
