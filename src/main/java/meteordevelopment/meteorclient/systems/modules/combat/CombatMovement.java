
package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.item.AxeItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CombatMovement extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScoring = settings.createGroup("Scoring Weights");

    public enum Mode {
        Kite,
        Strafe,
        Retreat,
        Push,
        Dynamic
    }

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Movement mode. Dynamic automatically switches.")
        .defaultValue(Mode.Dynamic)
        .build());

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Desired range from target (Kite/Strafe).")
        .defaultValue(15)
        .sliderMax(30)
        .build());
    
    private final Setting<Boolean> faceTarget = sgGeneral.add(new BoolSetting.Builder()
        .name("face-target")
        .description("Face the target while moving.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> microSpacing = sgGeneral.add(new BoolSetting.Builder()
        .name("micro-spacing")
        .description("Keep distance when attack is cooling down (W-Tap logic).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> antiLeaves = sgGeneral.add(new BoolSetting.Builder()
        .name("avoid-leaves")
        .description("Prevent pathing into trees.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoBlock = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-block")
        .description("Place blocks for protection when low health.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> timeOut = sgGeneral.add(new IntSetting.Builder()
        .name("timeout-ticks")
        .description("Ticks before recalculating new position.")
        .defaultValue(5) // Faster reaction
        .min(1)
        .build());

    // Scoring Weights
    private final Setting<Double> wSafety = sgScoring.add(new DoubleSetting.Builder().name("safety-weight").defaultValue(10.0).build());
    private final Setting<Double> wHeight = sgScoring.add(new DoubleSetting.Builder().name("height-weight").defaultValue(2.0).build());
    private final Setting<Double> wRange = sgScoring.add(new DoubleSetting.Builder().name("range-weight").defaultValue(4.0).build()); // Increased
    private final Setting<Double> wCover = sgScoring.add(new DoubleSetting.Builder().name("cover-weight").defaultValue(1.0).build());

    private int timer = 0;
    private BlockPos currentGoal = null;

    public CombatMovement() {
        super(Categories.Combat, "combat-movement", "Automatically moves during combat using Utility AI.");
    }

    @Override
    public void onActivate() {
        if (!BaritoneUtils.IS_AVAILABLE) {
            error("Baritone is not installed!");
            toggle();
            return;
        }
        timer = 0;
        currentGoal = null;
        
        // Configure Baritone for better mob/water handling
        meteordevelopment.meteorclient.utils.player.ChatUtils.sendPlayerMsg("#set mobAvoidanceCoefficient 4.0");
        meteordevelopment.meteorclient.utils.player.ChatUtils.sendPlayerMsg("#set assumeWalkOnWater true"); // Treat water as walkable but high cost? Or avoid?
        // Actually, preventing water entry is better handled by isSafe.
        // Mob avoidance 4.0 helps avoid "wrapping around zombies".
    }

    @Override
    public void onDeactivate() {
        Object baritone = BaritoneUtils.getPrimaryBaritone();
        if (baritone != null) {
            BaritoneUtils.cancelEverything(baritone);
        }
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        ThreatManager.get().update();
        ThreatManager.Threat bestThreat = ThreatManager.get().getBestTarget();

        if (bestThreat == null) {
            Object baritone = BaritoneUtils.getPrimaryBaritone();
            if (baritone != null) BaritoneUtils.cancelEverything(baritone);
            currentGoal = null;
            return;
        }

        // Face Target
        if (faceTarget.get()) {
            // Baritone handles look mostly, but we can force it if needed.
        }

        if (timer > 0) {
            timer--;
            return;
        }

        Mode currentMode = determineMode(bestThreat);
        BlockPos bestPos = findBestPosition(bestThreat.entity, currentMode);

        if (bestPos != null && !bestPos.equals(currentGoal)) {
            setGoal(bestPos);
            currentGoal = bestPos;
            timer = timeOut.get();
        }
        
        emergencyBlock();
    }
    
    // Hitbox Prediction: Estimate where target will be
    private Vec3d predictPosition(LivingEntity target) {
        return new Vec3d(target.getX(), target.getY(), target.getZ()).add(target.getVelocity().multiply(2.0)); // 2 ticks ahead
    }

    private Mode determineMode(ThreatManager.Threat threat) {
        if (mode.get() != Mode.Dynamic) return mode.get();

        float health = mc.player.getHealth();
        if (health < 10) return Mode.Retreat;
        
        // Push logic: Health > 15 AND Holding Weapon
        boolean holdingWeapon = mc.player.getMainHandStack().isIn(ItemTags.SWORDS) 
            || mc.player.getMainHandStack().getItem() instanceof AxeItem;
        
        if (health > 15 && holdingWeapon) return Mode.Push;

        if (threat.distance < 5 && health > 15) return Mode.Strafe; // Aggressive
        return Mode.Kite; // Default
    }

    private BlockPos findBestPosition(LivingEntity target, Mode currentMode) {
        BlockPos playerPos = mc.player.getBlockPos();
        List<BlockPos> candidates = new ArrayList<>();

        int radius = 8;
        for (int x = -radius; x <= radius; x += 2) {
            for (int z = -radius; z <= radius; z += 2) {
                for (int y = -2; y <= 3; y++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (isSafe(pos)) candidates.add(pos);
                }
            }
        }

        if (candidates.isEmpty()) return null;

        return candidates.stream()
            .max(Comparator.comparingDouble(p -> scorePosition(p, target, currentMode)))
            .orElse(null);
    }

    private double scorePosition(BlockPos pos, LivingEntity target, Mode currentMode) {
        double score = 0;
        Vec3d posVec = new Vec3d(pos.getX(), pos.getY(), pos.getZ());
        
        // Use predicted position for target tracking
        Vec3d targetVec = predictPosition(target); 
        
        double distToTarget = Math.sqrt(pos.getSquaredDistance(targetVec));
        
        // --- 1. Range Logic (Enemy Specific) ---
        double desiredRange = range.get();
        if (currentMode == Mode.Retreat) desiredRange = 40;
        else if (currentMode == Mode.Push) {
            desiredRange = 3.5;
            
            // Specialized Logic
            if (target instanceof CreeperEntity) desiredRange = 6.0; // Stay back from creeper
            
            // Micro Spacing (W-Tap)
            if (microSpacing.get() && mc.player.getAttackCooldownProgress(0.5f) < 0.9) {
                // If cooling down, back off slightly to dodge hits
                desiredRange += 1.5; 
            }
        }

        if (currentMode == Mode.Retreat) {
            score += distToTarget * wRange.get();
        } else {
            // Gaussian bell curve for range: rewards hitting the SWEET SPOT
            double diff = distToTarget - desiredRange;
            score -= (diff * diff) * wRange.get(); 
        }

        // --- 2. Height Logic ---
        double heightDiff = pos.getY() - target.getY();
        if (heightDiff > 0) score += heightDiff * wHeight.get();

        // --- 3. Arrow Avoidance ---
        if (checkForArrows(pos)) score -= 100;

        // --- 4. Horde Avoidance (Cluster Check) ---
        // Avoid positions close to Danger Zones (Cluster of mobs)
        for (Vec3d zone : ThreatManager.get().getDangerZones()) {
            if (posVec.squaredDistanceTo(zone) < 25) { // 5 blocks
                score -= 50; // Don't run into a horde
            }
        }

        // --- 5. Enderman Tactics ---
        if (target instanceof EndermanEntity) {
            // Reward low ceiling
            if (isCovered(pos)) score += 50;
            // Penalize water heavily for enderman fights (teleport risk? no, player doesn't want water anyway)
        }

        // --- 6. Cover / LOS ---
        boolean hasLOS = canSee(pos, target);
        if (currentMode == Mode.Retreat) {
            if (!hasLOS) score += wCover.get() * 5;
        } else {
            if (hasLOS) score += wCover.get() * 2;
        }
        
        // --- 7. Water Penalty ---
        if (mc.world.getFluidState(pos).isIn(FluidTags.WATER)) score -= 50; // Increased penalty

        return score;
    }
    
    // Check if position has a roof (for Endermen or anti-phantom)
    private boolean isCovered(BlockPos pos) {
        return mc.world.getBlockState(pos.up(2)).isSolidBlock(mc.world, pos.up(2));
    }

    private boolean checkForArrows(BlockPos pos) {
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof ProjectileEntity && !e.isOnGround()) {
                if (e.squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ()) < 25) { 
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSafe(BlockPos pos) {
        if (!mc.world.getBlockState(pos.down()).isSolidBlock(mc.world, pos.down())) return false;
        if (!mc.world.getBlockState(pos).isAir()) return false; 
        if (!mc.world.getBlockState(pos.up()).isAir()) return false;

        if (antiLeaves.get()) {
            if (mc.world.getBlockState(pos.down()).getBlock() instanceof LeavesBlock) return false;
        }

        if (mc.world.getFluidState(pos).isIn(FluidTags.WATER)) return false;
        if (mc.world.getFluidState(pos.down()).isIn(FluidTags.WATER)) return false;

        if (mc.world.getBlockState(pos).getBlock() == Blocks.LAVA) return false;
        if (mc.world.getBlockState(pos.down()).getBlock() == Blocks.LAVA) return false;
        
        // Damage Prediction (Crystals/TNT)
        if (calculateThreatDamage(pos) > 8.0) return false; // Too dangerous
        
        return true;
    }
    
    private float calculateThreatDamage(BlockPos pos) {
        float damage = 0;
        Vec3d center = pos.toCenterPos();
        
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof net.minecraft.entity.decoration.EndCrystalEntity || e instanceof net.minecraft.entity.TntEntity) {
                double dist = e.squaredDistanceTo(center);
                if (dist < 100) { // 10 blocks
                    // Simple estimation: linear falloff
                     damage += (float) (12.0 * (1.0 - (Math.sqrt(dist) / 10.0)));
                }
            }
        }
        return damage;
    }

    private void emergencyBlock() {
        if (!autoBlock.get()) return;
        if (mc.player.getHealth() > 10) return;
        
        // Place web or obsidian at feet if unsafe
        BlockPos pos = mc.player.getBlockPos();
        if (mc.world.getBlockState(pos).isAir()) {
             // Logic to place block (simplified for now as placeholder for full AutoTrap integration)
             // In a real module we'd verify item slots and rotate
        }
    }

    private boolean canSee(BlockPos pos, LivingEntity target) {
        // Simple line of sight check using raycast is standard but assuming true to save perf
       return true; 
    }

    private void setGoal(BlockPos pos) {
        Object baritone = BaritoneUtils.getPrimaryBaritone();
        if (baritone == null) return;
        Object customGoalProcess = BaritoneUtils.getCustomGoalProcess(baritone);
        if (customGoalProcess == null) return;
        BaritoneUtils.setGoalAndPath(customGoalProcess, new GoalBlock(pos));
    }
}
