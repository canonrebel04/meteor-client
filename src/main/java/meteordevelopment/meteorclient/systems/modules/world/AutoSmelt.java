/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.AbstractFurnaceScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipePropertySet;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public class AutoSmelt extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> enableAutoSmelter = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-auto-smelter")
        .description("Automatically enables the Auto Smelter module while Auto Smelt is active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> stopPathingOnDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-on-disable")
        .description("Stop the current Path Manager task when this module is disabled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> searchRadius = sgGeneral.add(new IntSetting.Builder()
        .name("search-radius")
        .description("Radius (in blocks) to look for furnaces.")
        .defaultValue(24)
        .min(4)
        .sliderRange(4, 128)
        .build()
    );

    private final Setting<Integer> yRange = sgGeneral.add(new IntSetting.Builder()
        .name("y-range")
        .description("Vertical range (in blocks) relative to player to look for furnaces.")
        .defaultValue(4)
        .min(0)
        .sliderRange(0, 32)
        .build()
    );

    private final Setting<Integer> samplesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("samples-per-tick")
        .description("How many random positions to sample per tick when searching for a furnace.")
        .defaultValue(220)
        .min(1)
        .sliderRange(1, 4096)
        .build()
    );

    private final Setting<Integer> interactCooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("interact-cooldown")
        .description("Minimum ticks between furnace interaction attempts.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Boolean> useFurnace = sgGeneral.add(new BoolSetting.Builder()
        .name("use-furnace")
        .description("Allow using normal furnaces.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useBlastFurnace = sgGeneral.add(new BoolSetting.Builder()
        .name("use-blast-furnace")
        .description("Allow using blast furnaces.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> closeWhenDone = sgGeneral.add(new BoolSetting.Builder()
        .name("close-when-done")
        .description("Close the furnace screen and disable when there is nothing left to smelt.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> stopWhenNoFurnace = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-when-no-furnace")
        .description("Disable the module if no furnace is found nearby.")
        .defaultValue(false)
        .build()
    );

    private final Random rng = new Random();
    private final BlockPos.Mutable samplePos = new BlockPos.Mutable();

    private int tick;
    private int lastInteractTick;

    private long targetFurnace = Long.MIN_VALUE;
    private boolean managingAutoSmelter;

    public AutoSmelt() {
        super(Categories.World, "auto-smelt", "Sorts smeltables into nearby furnaces and collects output (uses Auto Smelter while the UI is open). Requires a Path Manager.");
    }

    @Override
    public void onActivate() {
        BaritoneUtils.acquireContainerProtection();
        tick = 0;
        lastInteractTick = 0;
        targetFurnace = Long.MIN_VALUE;

        if (enableAutoSmelter.get()) {
            if (!Modules.get().isActive(AutoSmelter.class)) {
                Modules.get().get(AutoSmelter.class).toggle();
                managingAutoSmelter = true;
            } else {
                managingAutoSmelter = false;
            }
        } else {
            managingAutoSmelter = false;
        }
    }

    @Override
    public void onDeactivate() {
        if (stopPathingOnDisable.get()) PathManagers.get().stop();
        BaritoneUtils.releaseContainerProtection();
        targetFurnace = Long.MIN_VALUE;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate()) return;
        tick++;

        // If a furnace screen is open, AutoSmelter runs via mixin; we only handle stopping/closing.
        if (mc.currentScreen instanceof AbstractFurnaceScreen<?> ) {
            if (managingAutoSmelter && !Modules.get().isActive(AutoSmelter.class)) {
                // AutoSmelter disabled itself (out of fuel/items/full). Stop this baseline too.
                if (closeWhenDone.get()) mc.player.closeHandledScreen();
                toggle();
                return;
            }

            if (closeWhenDone.get() && !hasAnySmeltableInInventory()) {
                mc.player.closeHandledScreen();
                toggle();
            }

            return;
        }

        if (closeWhenDone.get() && !hasAnySmeltableInInventory()) {
            toggle();
            return;
        }

        if (targetFurnace == Long.MIN_VALUE || !isValidFurnace(BlockPos.fromLong(targetFurnace))) {
            boolean found = findNearbyFurnace();
            if (!found && stopWhenNoFurnace.get()) {
                error("No furnace found within search radius.");
                toggle();
                return;
            }
        }

        if (targetFurnace == Long.MIN_VALUE) return;

        BlockPos furnacePos = BlockPos.fromLong(targetFurnace);

        double distSq = mc.player.squaredDistanceTo(Vec3d.ofCenter(furnacePos));
        double interactRange = mc.player.getBlockInteractionRange();

        // Interact if close enough.
        if (distSq <= interactRange * interactRange) {
            int cooldown = Math.max(0, interactCooldownTicks.get());
            if (cooldown > 0 && tick - lastInteractTick < cooldown) return;

            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(furnacePos), Direction.UP, furnacePos, false);
            BlockUtils.interact(hit, Hand.MAIN_HAND, true);
            lastInteractTick = tick;
            return;
        }

        // Otherwise walk to it.
        if (!PathManagers.get().isPathing()) {
            PathManagers.get().moveTo(furnacePos, false);
        }
    }

    private boolean hasAnySmeltableInInventory() {
        if (mc.world == null || mc.player == null) return false;

        // Prefer "furnace input" property set which covers common smeltables.
        var set = mc.world.getRecipeManager().getPropertySet(RecipePropertySet.FURNACE_INPUT);

        int total = Math.min(36, mc.player.getInventory().size());
        for (int i = 0; i < total; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            if (set.canUse(stack)) return true;
        }

        return false;
    }

    private boolean findNearbyFurnace() {
        if (mc.world == null || mc.player == null) return false;

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
            Block block = state.getBlock();

            if (!isAllowedFurnace(block)) continue;
            if (!state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.LAVA)) continue;

            double distSq = mc.player.squaredDistanceTo(Vec3d.ofCenter(samplePos));
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = samplePos.asLong();
            }
        }

        if (best != Long.MIN_VALUE) {
            targetFurnace = best;
            return true;
        }

        return false;
    }

    private boolean isAllowedFurnace(Block block) {
        if (block == Blocks.FURNACE) return useFurnace.get();
        if (block == Blocks.BLAST_FURNACE) return useBlastFurnace.get();
        return false;
    }

    private boolean isValidFurnace(BlockPos pos) {
        if (mc.world == null) return false;
        Block block = mc.world.getBlockState(pos).getBlock();
        return isAllowedFurnace(block);
    }
}
