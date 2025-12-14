/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import baritone.api.pathing.goals.GoalBlock;

public class AutoReturn extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> healthThreshold = sgGeneral.add(new IntSetting.Builder()
            .name("health-threshold")
            .description("The health threshold to return home.")
            .defaultValue(10)
            .min(1)
            .sliderMax(20)
            .build());

    private final Setting<Double> durabilityThreshold = sgGeneral.add(new DoubleSetting.Builder()
            .name("durability-threshold")
            .description("The durability threshold percentage to return home.")
            .defaultValue(10)
            .min(1)
            .sliderMax(100)
            .build());

    private final Setting<Boolean> fullInventory = sgGeneral.add(new BoolSetting.Builder()
            .name("full-inventory")
            .description("Return home when inventory is full.")
            .defaultValue(false)
            .build());

    private boolean returning = false;

    public AutoReturn() {
        super(Categories.Player, "auto-return", "Automatically paths to home when unsafe.");
    }

    @Override
    public void onDeactivate() {
        returning = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null)
            return;

        // If already returning, check if we arrived or cancelled? For now, we mainly
        // trigger.
        // Actually, we shouldn't spam setGoal if it's already running.
        // But BaritoneUtils doesn't easily tell us if we are pathing to *specifically*
        // home unless we store state.
        // For simplicity, we trigger if unsafe conditions match.

        boolean unsafe = false;
        String reason = "";

        // Health Check
        if (mc.player.getHealth() + mc.player.getAbsorptionAmount() <= healthThreshold.get()) {
            unsafe = true;
            reason = "Low Health";
        }

        // Durability Check
        if (!unsafe) {
            for (net.minecraft.entity.EquipmentSlot slot : net.minecraft.entity.EquipmentSlot.values()) {
                if (slot.getType() != net.minecraft.entity.EquipmentSlot.Type.HUMANOID_ARMOR)
                    continue;
                ItemStack stack = mc.player.getEquippedStack(slot);
                if (stack.isEmpty())
                    continue;
                if (!stack.isDamageable())
                    continue; // Should be checks for isDamageable
                double percentage = (double) (stack.getMaxDamage() - stack.getDamage()) / stack.getMaxDamage() * 100.0;
                if (percentage <= durabilityThreshold.get()) {
                    unsafe = true;
                    reason = "Low Durability";
                    break;
                }
            }
        }

        // Full Inventory Check
        if (!unsafe && fullInventory.get()) {
            if (!InvUtils.findEmpty().found()) {
                unsafe = true;
                reason = "Full Inventory";
            }
        }

        if (unsafe) {
            if (!returning) {
                // Find Home
                Waypoint home = Waypoints.get().get("Home");
                if (home == null)
                    home = Waypoints.get().get("home");

                if (home != null) {
                    BlockPos pos = home.pos.get();
                    Object baritone = BaritoneUtils.getPrimaryBaritone();
                    if (baritone != null) {
                        Object customGoalProcess = BaritoneUtils.getCustomGoalProcess(baritone);
                        if (customGoalProcess != null) {
                            BaritoneUtils.setGoalAndPath(customGoalProcess, new GoalBlock(pos));
                            info("Triggered AutoReturn: " + reason);
                            returning = true;
                        }
                    }
                } else {
                    // warn once?
                }
            }
        } else {
            // Conditions met, reset "returning" flag so we can trigger again if we heal up
            // and go back out?
            // Actually, if we are returning, we stay "returning" until... we arrive?
            // If user cancels baritone, we might want to re-trigger if still unsafe.
            // But if we heal up, we stop being "unsafe".
            returning = false;
        }
    }
}
