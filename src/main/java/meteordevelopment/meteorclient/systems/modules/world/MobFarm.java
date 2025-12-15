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
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Hand;

import java.util.Set;

public class MobFarm extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to farm.")
        .defaultValue(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.SPIDER)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("The range to search for entities.")
        .defaultValue(50.0)
        .min(0)
        .sliderMax(100)
        .build()
    );
    
    private final Setting<Boolean> collectLoot = sgGeneral.add(new BoolSetting.Builder()
        .name("collect-loot")
        .description("Automatically path to dropped items when no mobs are found.")
        .defaultValue(true)
        .build()
    );
    
    // Internal state
    private Entity target;
    private int timer = 0;

    public MobFarm() {
        super(Categories.World, "mob-farm", "Automatically pathfinds to and kills mobs.");
    }

    @Override
    public void onDeactivate() {
        if (BaritoneUtils.IS_AVAILABLE) {
            BaritoneUtils.cancelEverything(BaritoneUtils.getPrimaryBaritone());
        }
        target = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!BaritoneUtils.IS_AVAILABLE) return;
        
        // 1. Find Target (Mob)
        target = TargetUtils.get(entity -> {
            if (!entities.get().contains(entity.getType())) return false;
            if (!(entity instanceof LivingEntity living) || living.isDead()) return false;
            // Check range
            if (mc.player.distanceTo(entity) > range.get()) return false;
            return true;
        }, SortPriority.LowestDistance);

        // 2. If no mob, check for loot
        if (target == null && collectLoot.get()) {
            target = TargetUtils.get(entity -> {
                if (!(entity instanceof net.minecraft.entity.ItemEntity)) return false;
                if (mc.player.distanceTo(entity) > range.get()) return false;
                return true;
            }, SortPriority.LowestDistance);
        }

        if (target == null) {
            // No targets, stop moving
            if (BaritoneUtils.isPathing(BaritoneUtils.getPathingBehavior(BaritoneUtils.getPrimaryBaritone()))) {
                 BaritoneUtils.cancelEverything(BaritoneUtils.getPrimaryBaritone());
            }
            return;
        }

        // 3. Pathfind
        // Update goal every 10 ticks or if not pathing
        if (timer++ >= 10 || !BaritoneUtils.isPathing(BaritoneUtils.getPathingBehavior(BaritoneUtils.getPrimaryBaritone()))) {
            timer = 0;
            Object baritone = BaritoneUtils.getPrimaryBaritone();
            Object customGoalProcess = BaritoneUtils.getCustomGoalProcess(baritone);
            BaritoneUtils.setGoalAndPath(customGoalProcess, new GoalBlock(target.getBlockPos()));
        }

        // 4. Attack (if it's a living entity)
        if (target instanceof LivingEntity) {
            // If within reach (approx 4.5 blocks), look and strike
            if (mc.player.distanceTo(target) <= 4.5f) {
                // Face target
                Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target));
                
                // Attack with cooldown check
                if (mc.player.getAttackCooldownProgress(0.5f) >= 1) {
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
            }
        }
    }
}
