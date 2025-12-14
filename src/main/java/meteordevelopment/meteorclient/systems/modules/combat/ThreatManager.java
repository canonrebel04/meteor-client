package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.friends.Friends;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ThreatManager extends System<ThreatManager> {
    private final List<Threat> threats = new ArrayList<>();
    private final int scanRange = 20;

    public ThreatManager() {
        super("threat-manager");
    }

    public static ThreatManager get() {
        return Systems.get(ThreatManager.class);
    }

    public List<Vec3d> dangerZones = new ArrayList<>();

    public void update() {
        threats.clear();
        dangerZones.clear();
        if (mc.player == null || mc.world == null) return;

        List<LivingEntity> potentialThreats = new ArrayList<>();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (entity == mc.player) continue;
            if (living instanceof PlayerEntity player && Friends.get().isFriend(player)) continue;

            double distance = mc.player.distanceTo(entity);
            if (distance > scanRange) continue;

            int danger = calculateDanger(living);
            if (danger > 0) {
                threats.add(new Threat(living, danger, distance));
                potentialThreats.add(living);
            }
        }

        threats.sort(Comparator.comparingInt(t -> -t.danger));
        analyzeClusters(potentialThreats);
    }
    
    private void analyzeClusters(List<LivingEntity> enemies) {
        // Simple O(N^2) clustering: If 3+ enemies are within 5 blocks of a point, mark it as a Danger Zone.
        // Optimization: Just mark every enemy position. If multiple overlap, the penalties stack naturally in CombatMovement?
        // Better: Find centroids.
        // For "Insane" quality, we simply expose the list of all enemies to the module for density checking,
        // OR we pre-calculate a "Center of Mass" of the horde.
        
        if (enemies.size() < 3) return;
        
        // Calculate average position of enemies (Horde Center)
        double sumX = 0, sumZ = 0;
        for (LivingEntity e : enemies) {
            sumX += e.getX();
            sumZ += e.getZ();
        }
        Vec3d center = new Vec3d(sumX / enemies.size(), enemies.getFirst().getY(), sumZ / enemies.size());
        dangerZones.add(center);
    }

    private int calculateDanger(LivingEntity entity) {
        int score = 0;

        if (entity instanceof PlayerEntity player) {
            score += 10;
            
            // Held Item Analysis
            if (player.getMainHandStack().isIn(ItemTags.SWORDS)) score += 10;
            else if (player.getMainHandStack().getItem() instanceof AxeItem) score += 15; // Axes disable shields
            else if (player.getMainHandStack().getItem() == Items.END_CRYSTAL) score += 20; // High danger
            else if (player.getMainHandStack().getItem() == Items.RESPAWN_ANCHOR) score += 20;

            // Armor Analysis (simplified)
            int armorValue = 0;
            net.minecraft.entity.EquipmentSlot[] slots = {
                net.minecraft.entity.EquipmentSlot.HEAD, 
                net.minecraft.entity.EquipmentSlot.CHEST, 
                net.minecraft.entity.EquipmentSlot.LEGS, 
                net.minecraft.entity.EquipmentSlot.FEET
            };
            
            for (net.minecraft.entity.EquipmentSlot slot : slots) {
                net.minecraft.item.ItemStack stack = player.getEquippedStack(slot);
                if (stack.getItem() == Items.NETHERITE_HELMET || stack.getItem() == Items.NETHERITE_CHESTPLATE || 
                    stack.getItem() == Items.NETHERITE_LEGGINGS || stack.getItem() == Items.NETHERITE_BOOTS) {
                    armorValue += 5;
                } else if (stack.getItem() == Items.DIAMOND_HELMET || stack.getItem() == Items.DIAMOND_CHESTPLATE || 
                           stack.getItem() == Items.DIAMOND_LEGGINGS || stack.getItem() == Items.DIAMOND_BOOTS) {
                    armorValue += 3;
                }
            }
            if (armorValue > 10) score += 5; // Good armor
            
            // Potion Effects
            if (player.hasStatusEffect(StatusEffects.STRENGTH)) score += 10;
            if (player.hasStatusEffect(StatusEffects.WEAKNESS)) score -= 5;
            if (player.hasStatusEffect(StatusEffects.SPEED)) score += 5;

        } else if (entity instanceof Monster) {
            score += 5;
            if (entity instanceof CreeperEntity) score += 15;
        }

        // Proximity Danger (Exponential)
        double dist = mc.player.distanceTo(entity);
        if (dist < 5) score += 15;
        else if (dist < 10) score += 5;

        return score;
    }

    public List<Threat> getThreats() {
        return threats;
    }
    
    public List<Vec3d> getDangerZones() {
        return dangerZones;
    }

    public Threat getBestTarget() {
        return threats.isEmpty() ? null : threats.getFirst();
    }

    public static class Threat {
        public final LivingEntity entity;
        public final int danger;
        public final double distance;

        public Threat(LivingEntity entity, int danger, double distance) {
            this.entity = entity;
            this.danger = danger;
            this.distance = distance;
        }
    }
}
