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
import meteordevelopment.meteorclient.systems.autocraft.AutoCraftMemory;
import meteordevelopment.meteorclient.systems.autocraft.AutoCraftPlanner;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.Random;

public class AutoCraft extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> searchRadius = sgGeneral.add(new IntSetting.Builder()
        .name("search-radius")
        .description("Radius (in blocks) to look for a crafting table.")
        .defaultValue(24)
        .min(4)
        .sliderRange(4, 128)
        .build()
    );

    private final Setting<Integer> yRange = sgGeneral.add(new IntSetting.Builder()
        .name("y-range")
        .description("Vertical range relative to player to sample for crafting tables.")
        .defaultValue(4)
        .min(0)
        .sliderRange(0, 32)
        .build()
    );

    private final Setting<Integer> samplesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("samples-per-tick")
        .description("How many random positions to sample per tick when searching.")
        .defaultValue(240)
        .min(1)
        .sliderRange(1, 4096)
        .build()
    );

    private final Setting<Integer> interactCooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("interact-cooldown")
        .description("Minimum ticks between crafting table interaction / crafting attempts.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Boolean> closeWhenDone = sgGeneral.add(new BoolSetting.Builder()
        .name("close-when-done")
        .description("Close the crafting screen and disable when the target count is reached.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> stopPathingOnDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-on-disable")
        .description("Stop the current Path Manager task when this module is disabled.")
        .defaultValue(true)
        .build()
    );

    private final Random rng = new Random();
    private final BlockPos.Mutable samplePos = new BlockPos.Mutable();

    private long targetTablePos = Long.MIN_VALUE;
    private long placedTablePos = Long.MIN_VALUE;
    private long targetStoragePos = Long.MIN_VALUE;
    private long lastOpenedStoragePos = Long.MIN_VALUE;
    private boolean pendingStorageRefreshCapture;

    private int tick;
    private int lastActionTick;
    private int consecutiveCraftFailures;

    private boolean pausedForInventoryFull;
    private String statusLine;
    private String lastStatusNotified;

    private Item targetItem;
    private int targetCount;

    public AutoCraft() {
        super(Categories.World, "auto-craft", "Walks to a crafting table and crafts a target item using the recipe-click API. Only uses items already in your inventory.");
    }

    @Override
    public String getInfoString() {
        return statusLine;
    }

    public void setTarget(Item item, int count) {
        this.targetItem = item;
        this.targetCount = Math.max(1, count);
    }

    @Override
    public void onActivate() {
        BaritoneUtils.acquireContainerProtection();
        tick = 0;
        lastActionTick = 0;
        consecutiveCraftFailures = 0;
        targetTablePos = Long.MIN_VALUE;
        placedTablePos = Long.MIN_VALUE;
        targetStoragePos = Long.MIN_VALUE;
        lastOpenedStoragePos = Long.MIN_VALUE;
        pendingStorageRefreshCapture = false;
        pausedForInventoryFull = false;
        statusLine = null;
        lastStatusNotified = null;

        setStatus("Starting");

        // If a target wasn't set (e.g., toggled via Modules UI), do nothing useful.
        if (targetItem == null) {
            error("No target set. Use the AutoCraft tab to pick an item.");
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        if (stopPathingOnDisable.get()) PathManagers.get().stop();
        BaritoneUtils.releaseContainerProtection();
        targetTablePos = Long.MIN_VALUE;
        placedTablePos = Long.MIN_VALUE;
        targetStoragePos = Long.MIN_VALUE;
        lastOpenedStoragePos = Long.MIN_VALUE;
        pendingStorageRefreshCapture = false;
        consecutiveCraftFailures = 0;
        pausedForInventoryFull = false;
        setStatus(null);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate()) return;
        if (mc.player == null || mc.world == null) return;
        if (targetItem == null || targetCount <= 0) {
            toggle();
            return;
        }

        tick++;

        if (pausedForInventoryFull) {
            if (canAcceptAnyNewItems()) {
                pausedForInventoryFull = false;
                setStatus("Resumed");
            } else {
                setStatus("Paused (inv full)");
                return;
            }
        }

        // If a storage screen is open, withdraw needed items.
        if (isStorageScreenOpen()) {
            setStatus("Withdrawing");
            int cooldown = Math.max(0, interactCooldownTicks.get());
            if (cooldown > 0 && tick - lastActionTick < cooldown) return;

            boolean acted = tryWithdrawFromOpenContainer();
            lastActionTick = tick;

            if (pausedForInventoryFull) {
                mc.player.closeHandledScreen();
                return;
            }

            if (pendingStorageRefreshCapture) {
                // Allow one tick for the screen-tick mixin to re-capture post-withdrawal contents.
                pendingStorageRefreshCapture = false;
                mc.player.closeHandledScreen();
                return;
            }

            if (!acted) {
                mc.player.closeHandledScreen();
            }
            return;
        }

        // If a crafting screen is open, attempt crafting.
        if (isCraftingTableOpen()) {
            setStatus("Crafting");
            if (countInInventory(targetItem) >= targetCount) {
                if (closeWhenDone.get()) mc.player.closeHandledScreen();
                setStatus("Done");
                toggle();
                return;
            }

            int cooldown = Math.max(0, interactCooldownTicks.get());
            if (cooldown > 0 && tick - lastActionTick < cooldown) return;

            Item next = selectNextCraftItem();

            if (next != null && !canAcceptItem(next)) {
                pauseForInventoryFull("Need space for output", next);
                mc.player.closeHandledScreen();
                return;
            }

            boolean ok = tryCraftOnce(next);
            lastActionTick = tick;

            if (!ok) {
                consecutiveCraftFailures++;
                if (consecutiveCraftFailures >= 6) {
                    // Likely missing ingredients; try fetching from storage if we have memory.
                    setStatus("Missing items (fetching)");
                    mc.player.closeHandledScreen();
                    consecutiveCraftFailures = 0;
                }
            } else {
                consecutiveCraftFailures = 0;
            }

            return;
        }

        // If we need base items and have them in memory, go fetch them first.
        if (shouldFetchFromStorage()) {
            setStatus("Fetching from storage");
            if (targetStoragePos == Long.MIN_VALUE || !isValidStorage(BlockPos.fromLong(targetStoragePos))) {
                findStorageTargetFromMemory();
            }

            if (targetStoragePos != Long.MIN_VALUE) {
                BlockPos pos = BlockPos.fromLong(targetStoragePos);

                double distSq = mc.player.squaredDistanceTo(Vec3d.ofCenter(pos));
                double interactRange = mc.player.getBlockInteractionRange();

                if (distSq <= interactRange * interactRange) {
                    int cooldown = Math.max(0, interactCooldownTicks.get());
                    if (cooldown > 0 && tick - lastActionTick < cooldown) return;

                    AutoCraftContext.markNextContainer(pos);
                    BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
                    BlockUtils.interact(hit, Hand.MAIN_HAND, true);
                    lastActionTick = tick;
                    lastOpenedStoragePos = pos.asLong();
                    // Force re-pick after open.
                    targetStoragePos = Long.MIN_VALUE;
                    return;
                }

                if (!PathManagers.get().isPathing()) {
                    PathManagers.get().moveTo(pos, false);
                }

                return;
            }
        }

        // Otherwise, find and open a crafting table.
        setStatus("Searching table");
        if (targetTablePos == Long.MIN_VALUE || !isValidCraftingTable(BlockPos.fromLong(targetTablePos))) {
            findNearbyCraftingTable();
        }

        // If no table is found, craft and place one near the player.
        if (targetTablePos == Long.MIN_VALUE) {
            if (placedTablePos != Long.MIN_VALUE && isValidCraftingTable(BlockPos.fromLong(placedTablePos))) {
                targetTablePos = placedTablePos;
            } else {
                BlockPos placed = tryEnsureAndPlaceCraftingTable();
                if (placed != null) {
                    placedTablePos = placed.asLong();
                    targetTablePos = placedTablePos;
                } else {
                    setStatus("No table (need planks)");
                    return;
                }
            }
        }

        BlockPos pos = BlockPos.fromLong(targetTablePos);

        double distSq = mc.player.squaredDistanceTo(Vec3d.ofCenter(pos));
        double interactRange = mc.player.getBlockInteractionRange();

        if (distSq <= interactRange * interactRange) {
            int cooldown = Math.max(0, interactCooldownTicks.get());
            if (cooldown > 0 && tick - lastActionTick < cooldown) return;

            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            BlockUtils.interact(hit, Hand.MAIN_HAND, true);
            lastActionTick = tick;
            // Force a new target scan next time.
            targetTablePos = Long.MIN_VALUE;
            return;
        }

        if (!PathManagers.get().isPathing()) {
            PathManagers.get().moveTo(pos, false);
        }
    }

    private void setStatus(String status) {
        if (status == null || status.isBlank()) {
            statusLine = null;
        } else {
            statusLine = status;
        }

        if (!isActive()) return;
        if (statusLine == null) return;
        if (statusLine.equals(lastStatusNotified)) return;

        lastStatusNotified = statusLine;
        info(statusLine);
    }

    private void pauseForInventoryFull(String context, Item item) {
        pausedForInventoryFull = true;
        String name = item != null ? item.getName().getString() : "?";
        setStatus("Paused (inv full): " + context + " [" + name + "]");
    }

    private boolean canAcceptAnyNewItems() {
        if (mc.player == null) return false;

        // Any empty slot in the player's inventory is sufficient to resume.
        int total = mc.player.getInventory().size();
        for (int i = 0; i < total; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s == null || s.isEmpty()) return true;
        }

        return false;
    }

    private boolean canAcceptItem(Item item) {
        if (mc.player == null || item == null) return false;

        int total = mc.player.getInventory().size();
        for (int i = 0; i < total; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s == null || s.isEmpty()) return true;
            if (s.getItem() != item) continue;
            if (s.getCount() < s.getMaxCount()) return true;
        }

        return false;
    }

    private boolean isCraftingTableOpen() {
        if (mc.player == null) return false;
        if (mc.currentScreen == null) return false;

        // Prefer handler-based detection.
        var handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        String name = handler.getClass().getSimpleName();
        return name.contains("Crafting") && name.contains("ScreenHandler");
    }

    private Item selectNextCraftItem() {
        // Prefer crafting intermediates we need but don't have yet.
        AutoCraftPlanner.Plan plan = AutoCraftPlanner.computePlan(targetItem, targetCount);
        for (var line : plan.intermediateCrafts()) {
            if (line.item() == null) continue;
            if (line.item() == targetItem) continue;

            int have = countInInventory(line.item());
            if (have >= line.needed()) continue;

            if (AutoCraftPlanner.findCraftingRecipeEntryFor(line.item()) != null) return line.item();
        }

        return targetItem;
    }

    private boolean tryCraftOnce(Item what) {
        if (mc.interactionManager == null || mc.player == null || mc.world == null) return false;
        if (what == null) return false;

        // Guard against spamming slot clicks if the handler behaves unexpectedly.
        final int[] clicksBudget = new int[] { 18 };

        Object entry = AutoCraftPlanner.findCraftingRecipeEntryFor(what);
        if (entry == null) return false;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;
        int syncId = handler.syncId;

        // Try to use clickRecipe(...) to populate the grid.
        boolean clicked = tryInvokeClickRecipe(syncId, entry);
        if (!clicked) {
            Object recipe = AutoCraftPlanner.unwrapRecipeFromEntry(entry);
            if (recipe != null) clicked = tryInvokeClickRecipe(syncId, recipe);
        }

        if (!clicked) return false;

        // Shift-click the output slot to craft/move result into inventory.
        // If the output didn't appear (stale grid / desync), clear the grid and retry once.
        try {
            int outSlot = findCraftOutputSlotIndex(handler, what);
            if (outSlot < 0) {
                clearCraftingGrid(handler, clicksBudget);
                // Retry clickRecipe once after cleanup.
                clicked = tryInvokeClickRecipe(syncId, entry);
                if (!clicked) {
                    Object recipe = AutoCraftPlanner.unwrapRecipeFromEntry(entry);
                    if (recipe != null) clicked = tryInvokeClickRecipe(syncId, recipe);
                }
                if (!clicked) return false;

                outSlot = findCraftOutputSlotIndex(handler, what);
                if (outSlot < 0) return false;
            }

            return clickWithBudget(handler, outSlot, 0, SlotActionType.QUICK_MOVE, clicksBudget);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int findCraftOutputSlotIndex(ScreenHandler handler, Item expected) {
        if (handler == null || expected == null) return -1;

        // For vanilla crafting handlers, output is usually in the first few slots.
        // Only accept slots that actually contain the expected crafted item.
        int limit = Math.min(handler.slots.size(), 12);
        for (int i = 0; i < limit; i++) {
            ItemStack s = handler.slots.get(i).getStack();
            if (s == null || s.isEmpty()) continue;
            if (s.getItem() == expected) return i;
        }

        return -1;
    }

    private void clearCraftingGrid(ScreenHandler handler, int[] clicksBudget) {
        if (handler == null || mc.player == null) return;

        // If we're holding something on the cursor, stow it first.
        ItemStack cursor = handler.getCursorStack();
        if (cursor != null && !cursor.isEmpty()) {
            int deposit = findFirstPlayerInventorySlot(handler, 10);
            if (deposit != -1) {
                clickWithBudget(handler, deposit, 0, SlotActionType.PICKUP, clicksBudget);
            }
        }

        // Best-effort clear: for vanilla crafting handlers, input grid is usually slots 1..9.
        int limit = Math.min(handler.slots.size(), 10);
        for (int i = 1; i < limit; i++) {
            ItemStack s = handler.slots.get(i).getStack();
            if (s == null || s.isEmpty()) continue;
            clickWithBudget(handler, i, 0, SlotActionType.QUICK_MOVE, clicksBudget);
        }
    }

    private boolean clickWithBudget(ScreenHandler handler, int slot, int button, SlotActionType type, int[] clicksBudget) {
        if (clicksBudget != null && clicksBudget.length > 0) {
            if (clicksBudget[0] <= 0) return false;
            clicksBudget[0]--;
        }

        return click(handler, slot, button, type);
    }

    private int findFirstPlayerInventorySlot(ScreenHandler handler, int assumedContainerSlots) {
        if (handler == null) return -1;
        int slotCount = handler.slots.size();
        int start = MathHelper.clamp(assumedContainerSlots, 0, slotCount);

        for (int i = start; i < slotCount; i++) {
            ItemStack s = handler.slots.get(i).getStack();
            if (s == null || s.isEmpty()) return i;
        }

        return -1;
    }

    private boolean isStorageScreenOpen() {
        if (mc.player == null) return false;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        // Same heuristic as capture mixin.
        int slotCount = handler.slots.size();
        int containerSlots = slotCount - 36;
        return containerSlots >= 9 && containerSlots <= 54 && containerSlots % 9 == 0;
    }

    private boolean tryWithdrawFromOpenContainer() {
        if (mc.player == null || mc.interactionManager == null) return false;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        AutoCraftPlanner.Plan plan = AutoCraftPlanner.computePlan(targetItem, targetCount);
        if (plan.missingLeaves().isEmpty()) return false;

        // Build needed-from-chests map: how many we still need in inventory to proceed.
        it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<Item> need = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();
        for (var line : plan.missingLeaves()) {
            Item item = line.item();
            if (item == null) continue;
            int stillNeedInInv = Math.max(0, line.needed() - line.inInventory());
            if (stillNeedInInv > 0) need.addTo(item, stillNeedInInv);
        }

        if (need.isEmpty()) return false;

        int slotCount = handler.slots.size();
        int containerSlots = slotCount - 36;
        containerSlots = MathHelper.clamp(containerSlots, 0, slotCount);

        boolean movedAny = false;
        int clicksBudget = 28;

        // If we somehow have an item on the cursor (lag/desync), stow it first to avoid corrupting transfers.
        if (!stowCursorStack(handler, containerSlots)) return false;

        // Exact-count withdrawals: move only what we need into the player inventory portion of the handler.
        for (var e : need.object2IntEntrySet()) {
            if (clicksBudget <= 0) break;

            Item item = e.getKey();
            int remaining = e.getIntValue();
            if (item == null || remaining <= 0) continue;

            while (remaining > 0 && clicksBudget > 0) {
                int containerSlot = findContainerSlotWith(handler, containerSlots, item);
                if (containerSlot == -1) break;

                int depositSlot = findPlayerDepositSlot(handler, containerSlots, item);
                if (depositSlot == -1) {
                    pauseForInventoryFull("Need space to withdraw", item);
                    return movedAny;
                }

                // Pick up the full stack from the container slot.
                if (!click(handler, containerSlot, 0, SlotActionType.PICKUP)) break;
                clicksBudget--;

                ItemStack cursor = handler.getCursorStack();
                if (cursor == null || cursor.isEmpty() || cursor.getItem() != item) {
                    // Put it back if something odd happened.
                    click(handler, containerSlot, 0, SlotActionType.PICKUP);
                    clicksBudget--;
                    break;
                }

                int cursorCount = cursor.getCount();
                int toMove = Math.min(cursorCount, remaining);

                if (toMove >= cursorCount) {
                    // Move the whole cursor stack in one click.
                    if (!click(handler, depositSlot, 0, SlotActionType.PICKUP)) {
                        clicksBudget--;
                        // Put back.
                        click(handler, containerSlot, 0, SlotActionType.PICKUP);
                        clicksBudget--;
                        break;
                    }
                    clicksBudget--;
                    movedAny = true;
                    remaining -= cursorCount;
                } else {
                    // Move exactly N items via right-click (1 per click).
                    int moved = 0;
                    for (int k = 0; k < toMove && clicksBudget > 0; k++) {
                        if (!click(handler, depositSlot, 1, SlotActionType.PICKUP)) break;
                        clicksBudget--;
                        moved++;
                    }

                    movedAny |= moved > 0;
                    remaining -= moved;

                    // Put the remainder back.
                    if (handler.getCursorStack() != null && !handler.getCursorStack().isEmpty()) {
                        click(handler, containerSlot, 0, SlotActionType.PICKUP);
                        clicksBudget--;
                    }
                }

                if (clicksBudget <= 0) break;
            }
        }

        if (movedAny && targetStoragePos != Long.MIN_VALUE) {
            // Ask the capture mixin to refresh this container's contents after our moves.
            AutoCraftContext.markNextContainer(BlockPos.fromLong(lastOpenedStoragePos));
            pendingStorageRefreshCapture = true;
        }

        return movedAny;
    }

    private boolean stowCursorStack(ScreenHandler handler, int containerSlots) {
        if (handler == null) return false;
        ItemStack cursor = handler.getCursorStack();
        if (cursor == null || cursor.isEmpty()) return true;

        Item item = cursor.getItem();

        // Prefer putting it into the player's inventory section.
        int playerSlot = findDepositSlotInRange(handler, MathHelper.clamp(containerSlots, 0, handler.slots.size()), handler.slots.size(), item);
        if (playerSlot != -1) return click(handler, playerSlot, 0, SlotActionType.PICKUP);

        // Fallback: put it back into the container section to avoid being stuck holding it.
        int containerSlot = findDepositSlotInRange(handler, 0, Math.min(containerSlots, handler.slots.size()), item);
        if (containerSlot != -1) return click(handler, containerSlot, 0, SlotActionType.PICKUP);

        return false;
    }

    private int findDepositSlotInRange(ScreenHandler handler, int start, int end, Item item) {
        if (handler == null || item == null) return -1;

        int s = MathHelper.clamp(start, 0, handler.slots.size());
        int e = MathHelper.clamp(end, 0, handler.slots.size());
        if (e <= s) return -1;

        // Prefer stacking onto an existing partial stack.
        for (int i = s; i < e; i++) {
            ItemStack st = handler.slots.get(i).getStack();
            if (st == null || st.isEmpty()) continue;
            if (st.getItem() != item) continue;
            if (st.getCount() < st.getMaxCount()) return i;
        }

        // Otherwise use the first empty slot.
        for (int i = s; i < e; i++) {
            ItemStack st = handler.slots.get(i).getStack();
            if (st == null || st.isEmpty()) return i;
        }

        return -1;
    }

    private boolean click(ScreenHandler handler, int slot, int button, SlotActionType type) {
        try {
            mc.interactionManager.clickSlot(handler.syncId, slot, button, type, mc.player);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int findContainerSlotWith(ScreenHandler handler, int containerSlots, Item item) {
        if (handler == null || item == null) return -1;
        int max = Math.min(containerSlots, handler.slots.size());
        for (int i = 0; i < max; i++) {
            ItemStack s = handler.slots.get(i).getStack();
            if (s == null || s.isEmpty()) continue;
            if (s.getItem() == item) return i;
        }
        return -1;
    }

    private int findPlayerDepositSlot(ScreenHandler handler, int containerSlots, Item item) {
        if (handler == null || item == null) return -1;

        int slotCount = handler.slots.size();
        int start = MathHelper.clamp(containerSlots, 0, slotCount);

        // Prefer stacking onto an existing partial stack.
        for (int i = start; i < slotCount; i++) {
            ItemStack s = handler.slots.get(i).getStack();
            if (s == null || s.isEmpty()) continue;
            if (s.getItem() != item) continue;
            if (s.getCount() < s.getMaxCount()) return i;
        }

        // Otherwise use the first empty slot.
        for (int i = start; i < slotCount; i++) {
            ItemStack s = handler.slots.get(i).getStack();
            if (s == null || s.isEmpty()) return i;
        }

        return -1;
    }

    private boolean shouldFetchFromStorage() {
        AutoCraftMemory mem = AutoCraftMemory.get();
        if (mem == null || mem.chestCount() == 0) return false;

        AutoCraftPlanner.Plan plan = AutoCraftPlanner.computePlan(targetItem, targetCount);
        if (plan.missingLeaves().isEmpty()) return false;

        for (var line : plan.missingLeaves()) {
            // If anything is missing in inventory but exists in chests, we should fetch.
            int stillNeedInInv = Math.max(0, line.needed() - line.inInventory());
            if (stillNeedInInv > 0 && line.inChests() > 0) return true;
        }

        return false;
    }

    private void findStorageTargetFromMemory() {
        AutoCraftMemory mem = AutoCraftMemory.get();
        if (mem == null || mc.player == null) return;

        AutoCraftPlanner.Plan plan = AutoCraftPlanner.computePlan(targetItem, targetCount);
        it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<Item> needed = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();
        for (var line : plan.missingLeaves()) {
            int stillNeedInInv = Math.max(0, line.needed() - line.inInventory());
            if (stillNeedInInv <= 0) continue;
            if (line.inChests() <= 0) continue;
            needed.addTo(line.item(), stillNeedInInv);
        }

        if (needed.isEmpty()) return;

        BlockPos pos = mem.findBestContainerFor(needed, mc.player.getBlockPos());
        if (pos == null) return;
        if (!isValidStorage(pos)) return;
        targetStoragePos = pos.asLong();
    }

    private boolean isValidStorage(BlockPos pos) {
        Block b = mc.world.getBlockState(pos).getBlock();
        return b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL;
    }

    private boolean tryInvokeClickRecipe(int syncId, Object recipeOrEntry) {
        if (mc.interactionManager == null || mc.player == null) return false;
        boolean craftAll = false;

        try {
            for (Method m : mc.interactionManager.getClass().getMethods()) {
                if (!m.getName().equals("clickRecipe")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 3) continue;
                if (p[0] != int.class) continue;
                if (p[2] != boolean.class) continue;
                if (!p[1].isInstance(recipeOrEntry)) continue;

                m.invoke(mc.interactionManager, syncId, recipeOrEntry, craftAll);
                return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private BlockPos tryEnsureAndPlaceCraftingTable() {
        if (mc.player == null || mc.world == null) return null;

        // If we already have one in inventory, just place it.
        if (!hasInInventory(Items.CRAFTING_TABLE)) {
            // Craft one using the player's 2x2 grid (works even without an existing crafting table).
            boolean crafted = tryCraftInPlayerGrid(Items.CRAFTING_TABLE);
            if (!crafted) return null;
        }

        FindItemResult table = InvUtils.findInHotbar(Items.CRAFTING_TABLE);
        if (!table.found()) {
            // Move it into hotbar if it's somewhere else.
            FindItemResult any = InvUtils.find(Items.CRAFTING_TABLE);
            if (!any.found()) return null;
            InvUtils.move().from(any.slot()).toHotbar(0);
            table = InvUtils.findInHotbar(Items.CRAFTING_TABLE);
            if (!table.found()) return null;
        }

        BlockPos base = mc.player.getBlockPos();
        BlockPos[] candidates = new BlockPos[] {
            base.north(), base.south(), base.east(), base.west(),
            base.north().down(), base.south().down(), base.east().down(), base.west().down(),
            base.down().north(), base.down().south(), base.down().east(), base.down().west()
        };

        for (BlockPos p : candidates) {
            if (p == null) continue;
            if (!BlockUtils.canPlace(p)) continue;
            if (BlockUtils.place(p, table, 100)) return p;
        }

        return null;
    }

    private boolean hasInInventory(Item item) {
        if (mc.player == null || item == null) return false;
        int total = mc.player.getInventory().size();
        for (int i = 0; i < total; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s == null || s.isEmpty()) continue;
            if (s.getItem() == item) return true;
        }
        return false;
    }

    private boolean tryCraftInPlayerGrid(Item what) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (what == null) return false;

        // Ensure we are on the player's own screen handler.
        ScreenHandler handler = mc.player.playerScreenHandler;
        if (handler == null) return false;

        Object entry = AutoCraftPlanner.findCraftingRecipeEntryFor(what);
        if (entry == null) return false;

        int syncId = handler.syncId;

        boolean clicked = tryInvokeClickRecipe(syncId, entry);
        if (!clicked) {
            Object recipe = AutoCraftPlanner.unwrapRecipeFromEntry(entry);
            if (recipe != null) clicked = tryInvokeClickRecipe(syncId, recipe);
        }
        if (!clicked) return false;

        // Output slot in player 2x2 crafting is also typically slot 0.
        int out = findCraftOutputSlotIndex(handler, what);
        if (out < 0) return false;

        return click(handler, out, 0, SlotActionType.QUICK_MOVE);
    }

    private void findNearbyCraftingTable() {
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

            if (block != Blocks.CRAFTING_TABLE) continue;
            if (!state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.LAVA)) continue;

            double distSq = mc.player.squaredDistanceTo(Vec3d.ofCenter(samplePos));
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = samplePos.asLong();
            }
        }

        if (best != Long.MIN_VALUE) targetTablePos = best;
    }

    private boolean isValidCraftingTable(BlockPos pos) {
        return mc.world.getBlockState(pos).getBlock() == Blocks.CRAFTING_TABLE;
    }

    private int countInInventory(Item item) {
        if (mc.player == null || item == null) return 0;

        int total = mc.player.getInventory().size();
        int out = 0;
        for (int i = 0; i < total; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s == null || s.isEmpty()) continue;
            if (s.getItem() == item) out += s.getCount();
        }

        return out;
    }
}
