/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import baritone.api.pathing.goals.GoalBlock;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.storage.StorageManager;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public class AutoSorter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("The range to search for containers.")
        .defaultValue(20.0)
        .min(0)
        .sliderMax(50)
        .build()
    );
    
    // TODO: Blacklist setting (ItemListSetting)

    private enum State {
        IDLE,
        PATHING,
        INTERACTING,
        DEPOSITING,
        CLOSING
    }

    private State state = State.IDLE;
    private BlockPos targetPos;
    private Item targetItem;
    private int timer = 0;

    public AutoSorter() {
        super(Categories.World, "auto-sorter", "Automatically deposits items into matching containers.");
    }

    @Override
    public void onDeactivate() {
        if (BaritoneUtils.IS_AVAILABLE) {
            BaritoneUtils.cancelEverything(BaritoneUtils.getPrimaryBaritone());
        }
        state = State.IDLE;
        targetPos = null;
        targetItem = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!BaritoneUtils.IS_AVAILABLE) return;
        
        switch (state) {
            case IDLE -> handleIdle();
            case PATHING -> handlePathing();
            case INTERACTING -> handleInteracting();
            case DEPOSITING -> handleDepositing();
            case CLOSING -> handleClosing();
        }
    }

    private void handleIdle() {
        // Scan inventory for items that are NOT in hotbar (usually we keep hotbar items)
        // Or just scan everything except blacklist. 
        // For simplicity, let's look at main inventory 9-35.
        
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            
            // Check if this item exists in any known container
            List<BlockPos> locations = StorageManager.get().findAll(stack.getItem());
            
            // Filter by range and sort by distance
            BlockPos bestPos = null;
            double bestDist = Double.MAX_VALUE;
            
            for (BlockPos pos : locations) {
                double dist = mc.player.squaredDistanceTo(pos.toCenterPos());
                if (dist > range.get() * range.get()) continue;
                
                if (dist < bestDist) {
                    bestDist = dist;
                    bestPos = pos;
                }
            }
            
            if (bestPos != null) {
                // Found a target!
                targetPos = bestPos;
                targetItem = stack.getItem();
                state = State.PATHING;
                return;
            }
        }
    }

    private void handlePathing() {
        if (targetPos == null) {
            state = State.IDLE;
            return;
        }

        // Start pathing if not already
        if (!BaritoneUtils.isPathing(BaritoneUtils.getPathingBehavior(BaritoneUtils.getPrimaryBaritone()))) {
             // If we are close enough, interact
             if (mc.player.squaredDistanceTo(targetPos.toCenterPos()) < 25) { // 5 blocks
                 state = State.INTERACTING;
                 BaritoneUtils.cancelEverything(BaritoneUtils.getPrimaryBaritone());
                 return;
             }
             
             // Otherwise path
             Object baritone = BaritoneUtils.getPrimaryBaritone();
             Object customGoalProcess = BaritoneUtils.getCustomGoalProcess(baritone);
             BaritoneUtils.setGoalAndPath(customGoalProcess, new GoalBlock(targetPos));
        } else {
            // Check if we arrived
             if (mc.player.squaredDistanceTo(targetPos.toCenterPos()) < 25) {
                 state = State.INTERACTING;
                 BaritoneUtils.cancelEverything(BaritoneUtils.getPrimaryBaritone());
             }
        }
    }

    private void handleInteracting() {
        // Look at block
        // Rotations.rotate(...); // Optional
        
        // Timer to prevent spam
        if (timer++ < 5) return;
        timer = 0;
        
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new net.minecraft.util.hit.BlockHitResult(targetPos.toCenterPos(), net.minecraft.util.math.Direction.UP, targetPos, false));
        state = State.DEPOSITING;
    }

    private void handleDepositing() {
        if (timer++ < 5) return; // Wait for GUI to open
        
        if (mc.currentScreen instanceof HandledScreen) {
            // Find our item and shift click it
            int slot = -1;
            for (int i = 9; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (!stack.isEmpty() && stack.getItem() == targetItem) {
                    slot = i;
                    break;
                }
            }
            
            if (slot != -1) {
                // We convert player inventory index to ScreenHandler slot index?
                // InvUtils handles slot actions usually.
                // But for container screens, slots are different.
                // Usually 0-X is container, then Player Inventory.
                
                // Let's use InvUtils to find the slot ID for us?
                // Or just assume behavior:
                // InvUtils.shiftClick().slotMain(slot - 9).run(); // Main start is 0 relative to player inv?
                
                // Let's rely on finding it in the container screen
                // Actually, let's just use InvUtils.find(targetItem) and see.
                
                // Simplest approach: Scan ALL slots in the open container, find the ones in the BOTTOM section (Player), matching our item.
                // ScreenHandler `slots`.
                
                // Actually, `InvUtils.quickMove().fromId(id)` is what we want.
                
                // Reset timer
                timer = 0;
                
                // Perform ONE move per tick/cycle
                 // HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
                 // InvUtils.find(targetItem) ...
                 
                 // Manual scan of open screen slots
                 HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
                 // container size
                 int containerSize = screen.getScreenHandler().slots.size() - 36; // rough guess
                 
                 for (int i = containerSize; i < screen.getScreenHandler().slots.size(); i++) {
                     if (screen.getScreenHandler().slots.get(i).getStack().getItem() == targetItem) {
                         InvUtils.shiftClick().slotId(i);
                         return; // Wait for next tick
                     }
                 }
                 
                 // If we are here, no more items to move
                 state = State.CLOSING;
                 
            } else {
                state = State.CLOSING;     
            }
        } else {
             // GUI closed unexpectedly
             state = State.CLOSING;
        }
    }
    
    private void handleClosing() {
        if (mc.player.currentScreenHandler != null) {
            mc.player.closeHandledScreen();
        }
        state = State.IDLE;
        timer = 0;
        targetPos = null;
        targetItem = null;
    }
}
