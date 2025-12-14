/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.input.Input;
import baritone.api.utils.SettingsUtil;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class BaritonePathManager implements IPathManager {
    private final BaritoneSettings settings;

    private int tickCounter;

    private GoalDirection directionGoal;
    private boolean pathingPaused;

    private boolean safeMode;
    private SafeModeSnapshot safeModeSnapshot;

    private Goal lastGoal;

    private final RecoveryController recoveryController;
    private int recoverCooldownTicks;
    private int recoverBackoffLevel;
    private int recoverLastAttemptTick;

    private final StuckTracker stuckTracker;
    private final AutomationMacroRecorder macroRecorder;
    private final BaritoneTaskAgent agent;

    private boolean miningProtectionActive;

    public BaritonePathManager() {
        // Subscribe to event bus
        MeteorClient.EVENT_BUS.subscribe(this);

        // Create settings
        settings = new BaritoneSettings();

        recoveryController = new RecoveryController();
        stuckTracker = new StuckTracker();
        macroRecorder = new AutomationMacroRecorder();
        agent = new BaritoneTaskAgent(this, macroRecorder);

        // Baritone pathing control
        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingControlManager().registerProcess(new BaritoneProcess());
    }

    public boolean isPaused() {
        return pathingPaused;
    }

    public boolean isSafeMode() {
        return safeMode;
    }

    public boolean isStuck() {
        return stuckTracker.isStuck();
    }

    public boolean isLowHealth() {
        if (mc.player == null) return false;

        float effectiveHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double threshold = Config.get().automationLowHealthHpThreshold.get();
        // Conservative default: treat 3 hearts (6hp) as "low".
        if (threshold <= 0) threshold = 6.0;
        return effectiveHealth <= (float) threshold;
    }

    public String getHazardSummary() {
        if (mc.world == null || mc.player == null) return null;

        BlockPos pos = mc.player.getBlockPos();

        boolean lava = false;
        boolean water = false;
        boolean fallRisk = false;

        BlockState at = mc.world.getBlockState(pos);
        if (!at.getFluidState().isEmpty()) {
            if (at.getFluidState().isIn(FluidTags.LAVA)) lava = true;
            if (at.getFluidState().isIn(FluidTags.WATER)) water = true;
        }

        BlockState below = mc.world.getBlockState(pos.down());
        if (!below.getFluidState().isEmpty()) {
            if (below.getFluidState().isIn(FluidTags.LAVA)) lava = true;
            if (below.getFluidState().isIn(FluidTags.WATER)) water = true;
        }

        // Conservative fall risk: only consider edges while on ground.
        if (mc.player.isOnGround() && below.isAir()) fallRisk = true;

        if (!lava && !water && !fallRisk) return null;
        if (lava && water && fallRisk) return "Lava, Water, Fall";
        if (lava && water) return "Lava, Water";
        if (lava && fallRisk) return "Lava, Fall";
        if (water && fallRisk) return "Water, Fall";
        if (lava) return "Lava";
        if (water) return "Water";
        return "Fall";
    }

    public String getGoalSummary() {
        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        Goal goal = baritone.getCustomGoalProcess().getGoal();
        if (goal == null) goal = lastGoal;
        return goal != null ? goal.toString() : null;
    }

    public InventorySummary getInventorySummary() {
        if (mc.player == null) return new InventorySummary(0, 0);

        int used = 0;
        // Use only main inventory + hotbar. Armor/offhand slots can be empty while the
        // inventory is effectively full for generic items.
        int total = Math.min(36, mc.player.getInventory().size());

        for (int i = 0; i < total; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty()) used++;
        }

        return new InventorySummary(used, total);
    }

    public record InventorySummary(int usedSlots, int totalSlots) {
    }

    public void setSafeMode(boolean enabled) {
        if (safeMode == enabled) return;

        safeMode = enabled;

        if (enabled) {
            safeModeSnapshot = SafeModeSnapshot.capture();

            // "Assume" settings exposed in Meteor UI wrappers.
            settings.getWalkOnLava().set(false);
            settings.getWalkOnWater().set(false);
            settings.getStep().set(false);

            // This wrapper is inverted in practice (it raises maxFallHeightNoWater);
            // safe mode should be conservative.
            settings.getNoFall().set(false);

            // Additional conservative movement settings (best-effort; ignore if missing).
            setBaritoneSettingBoolean("allowParkour", false);
            setBaritoneSettingBoolean("allowParkourAscend", false);
            setBaritoneSettingBoolean("allowParkourPlace", false);
            setBaritoneSettingBoolean("allowSprint", false);
            setBaritoneSettingBoolean("allowWaterBucketFall", false);

            // Reduce destructive/path-risky behavior.
            setBaritoneSettingBoolean("allowBreak", false);
            setBaritoneSettingBoolean("allowPlace", false);
            setBaritoneSettingBoolean("allowInventory", false);
            setBaritoneSettingBoolean("allowDiagonalDescend", false);
            setBaritoneSettingBoolean("allowDiagonalAscend", false);
            setBaritoneSettingBoolean("allowDownward", false);
            setBaritoneSettingBoolean("strictLiquidCheck", true);
            setBaritoneSettingBoolean("cutoffAtLoadBoundary", true);

            // Falling: keep conservative even if player has a bucket.
            setBaritoneSettingInt("maxFallHeightNoWater", 3);
            setBaritoneSettingInt("maxFallHeightBucket", 6);

            // Avoidance: enable and enforce a small radius if available.
            setBaritoneSettingBoolean("avoidance", true);
            Integer mobRadius = getBaritoneSettingInt("mobAvoidanceRadius");
            Integer spawnerRadius = getBaritoneSettingInt("mobSpawnerAvoidanceRadius");
            if (mobRadius != null) setBaritoneSettingInt("mobAvoidanceRadius", Math.max(mobRadius, 16));
            if (spawnerRadius != null) setBaritoneSettingInt("mobSpawnerAvoidanceRadius", Math.max(spawnerRadius, 24));

            ChatUtils.infoPrefix("Baritone", "Safe Mode: ON (avoidance=%s, mobRadius=%s, spawnerRadius=%s)",
                String.valueOf(getBaritoneSettingBoolean("avoidance")),
                formatSettingInt(getBaritoneSettingInt("mobAvoidanceRadius")),
                formatSettingInt(getBaritoneSettingInt("mobSpawnerAvoidanceRadius"))
            );
        } else {
            // Restore explicit values first (if we captured them).
            if (safeModeSnapshot != null) {
                safeModeSnapshot.restore();
                safeModeSnapshot = null;
            } else {
                // Fallback: reset the settings we know about.
                settings.getWalkOnLava().reset();
                settings.getWalkOnWater().reset();
                settings.getStep().reset();
                settings.getNoFall().reset();

                setBaritoneSettingBoolean("allowParkour", BaritoneAPI.getSettings().allowParkour.defaultValue);
                setBaritoneSettingBoolean("allowParkourAscend", BaritoneAPI.getSettings().allowParkourAscend.defaultValue);
                setBaritoneSettingBoolean("allowParkourPlace", BaritoneAPI.getSettings().allowParkourPlace.defaultValue);
                setBaritoneSettingBoolean("allowSprint", BaritoneAPI.getSettings().allowSprint.defaultValue);
                setBaritoneSettingBoolean("allowWaterBucketFall", BaritoneAPI.getSettings().allowWaterBucketFall.defaultValue);

                setBaritoneSettingBoolean("allowBreak", BaritoneAPI.getSettings().allowBreak.defaultValue);
                setBaritoneSettingBoolean("allowPlace", BaritoneAPI.getSettings().allowPlace.defaultValue);
                setBaritoneSettingBoolean("allowInventory", BaritoneAPI.getSettings().allowInventory.defaultValue);
                setBaritoneSettingBoolean("allowDiagonalDescend", BaritoneAPI.getSettings().allowDiagonalDescend.defaultValue);
                setBaritoneSettingBoolean("allowDiagonalAscend", BaritoneAPI.getSettings().allowDiagonalAscend.defaultValue);
                setBaritoneSettingBoolean("allowDownward", BaritoneAPI.getSettings().allowDownward.defaultValue);
                setBaritoneSettingBoolean("strictLiquidCheck", BaritoneAPI.getSettings().strictLiquidCheck.defaultValue);
                setBaritoneSettingBoolean("cutoffAtLoadBoundary", BaritoneAPI.getSettings().cutoffAtLoadBoundary.defaultValue);

                setBaritoneSettingInt("maxFallHeightNoWater", BaritoneAPI.getSettings().maxFallHeightNoWater.defaultValue);
                setBaritoneSettingInt("maxFallHeightBucket", BaritoneAPI.getSettings().maxFallHeightBucket.defaultValue);

                setBaritoneSettingInt("mobAvoidanceRadius", BaritoneAPI.getSettings().mobAvoidanceRadius.defaultValue);
                setBaritoneSettingInt("mobSpawnerAvoidanceRadius", BaritoneAPI.getSettings().mobSpawnerAvoidanceRadius.defaultValue);
            }

            ChatUtils.infoPrefix("Baritone", "Safe Mode: OFF (avoidance=%s, mobRadius=%s, spawnerRadius=%s)",
                String.valueOf(getBaritoneSettingBoolean("avoidance")),
                formatSettingInt(getBaritoneSettingInt("mobAvoidanceRadius")),
                formatSettingInt(getBaritoneSettingInt("mobSpawnerAvoidanceRadius"))
            );
        }
    }

    public BaritoneTaskAgent getAgent() {
        return agent;
    }

    public AutomationMacroRecorder getMacroRecorder() {
        return macroRecorder;
    }

    public AutomationBlackboard getAutomationBlackboard() {
        return agent.getBlackboard();
    }

    @Override
    public String getName() {
        return "Baritone";
    }

    @Override
    public boolean isPathing() {
        return BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing();
    }

    @Override
    public void pause() {
        pathingPaused = true;
    }

    @Override
    public void resume() {
        pathingPaused = false;
    }

    @Override
    public void stop() {
        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();

        if (miningProtectionActive) {
            BaritoneUtils.releaseMiningProtection();
            miningProtectionActive = false;
        }
    }

    public void smartMoveTo(BlockPos pos) {
        smartMoveTo(pos, false);
    }

    public void smartMoveTo(BlockPos pos, boolean ignoreYHint) {
        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

        BlockPos self = mc.player != null ? mc.player.getBlockPos() : null;

        SmartGotoDecision decision = SmartGotoDecision.decide(
            ignoreYHint,
            self != null ? self.getY() : null,
            pos.getY(),
            safeMode,
            isHazardousTarget(pos)
        );

        if (decision.ignoreY) {
            Goal goal = new GoalXZ(pos.getX(), pos.getZ());
            lastGoal = goal;
            baritone.getCustomGoalProcess().setGoalAndPath(goal);
            return;
        }

        if (decision.approachRadius > 0) {
            Goal goal = new GoalNear(pos, decision.approachRadius);
            lastGoal = goal;
            baritone.getCustomGoalProcess().setGoalAndPath(goal);
            return;
        }

        Goal goal = new GoalGetToBlock(pos);
        lastGoal = goal;
        baritone.getCustomGoalProcess().setGoalAndPath(goal);
    }

    public void recover() {
        if (recoverCooldownTicks > 0) {
            ChatUtils.warningPrefix("Baritone", "Recover: cooldown (%d ticks)", recoverCooldownTicks);
            return;
        }

        if (mc.player == null) {
            ChatUtils.warningPrefix("Baritone", "Recover: player not available.");
            return;
        }

        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        Goal goal = baritone.getCustomGoalProcess().getGoal();
        if (goal == null) goal = lastGoal;
        if (goal == null) {
            ChatUtils.warningPrefix("Baritone", "Recover: no active/previous goal to recover.");
            return;
        }

        int ticksSinceLastAttempt = RecoverBackoffPolicy.ticksSinceLastAttempt(tickCounter, recoverLastAttemptTick);
        recoverBackoffLevel = RecoverBackoffPolicy.nextBackoffLevel(recoverBackoffLevel, ticksSinceLastAttempt);
        recoverLastAttemptTick = tickCounter;

        recoverCooldownTicks = RecoverBackoffPolicy.cooldownTicksForBackoffLevel(recoverBackoffLevel);
        recoveryController.start(goal);

        if (stuckTracker.isStuck()) {
            ChatUtils.infoPrefix("Baritone", "Recover: started (stuck=true, backoff=%d, cooldown=%dt).", recoverBackoffLevel, recoverCooldownTicks);
        } else {
            ChatUtils.infoPrefix("Baritone", "Recover: started (backoff=%d, cooldown=%dt).", recoverBackoffLevel, recoverCooldownTicks);
        }
    }

    @Override
    public void moveTo(BlockPos pos, boolean ignoreY) {
        if (ignoreY) {
            Goal goal = new GoalXZ(pos.getX(), pos.getZ());
            lastGoal = goal;
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(goal);
            return;
        }

        Goal goal = new GoalGetToBlock(pos);
        lastGoal = goal;
        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(goal);
    }

    @Override
    public void moveInDirection(float yaw) {
        directionGoal = new GoalDirection(yaw);
        lastGoal = directionGoal;
        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(directionGoal);
    }

    @Override
    public void mine(Block... blocks) {
        // Mining should still avoid breaking containers/redstone if possible.
        BaritoneUtils.acquireMiningProtection();
        miningProtectionActive = true;
        BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().mine(blocks);
    }

    @Override
    public void follow(Predicate<Entity> entity) {
        BaritoneAPI.getProvider().getPrimaryBaritone().getFollowProcess().follow(entity);
    }

    @Override
    public float getTargetYaw() {
        return BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().playerRotations().getYaw();
    }

    @Override
    public float getTargetPitch() {
        return BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().playerRotations().getPitch();
    }

    @Override
    public ISettings getSettings() {
        return settings;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        tickCounter++;
        agent.tick();

        // Release mining protection after mining/pathing ends.
        if (miningProtectionActive && !isPathing()) {
            BaritoneUtils.releaseMiningProtection();
            miningProtectionActive = false;
        }

        if (recoverCooldownTicks > 0) recoverCooldownTicks--;
        stuckTracker.tick();
        recoveryController.tick();

        if (directionGoal == null) return;

        if (directionGoal != BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().getGoal()) {
            directionGoal = null;
            return;
        }

        directionGoal.tick();
    }

    @SuppressWarnings("unused")
    private static class GoalDirection implements Goal {
        private static final double SQRT_2 = Math.sqrt(2);

        private final float yaw;
        private int x;
        private int z;

        private int timer;

        public GoalDirection(float yaw) {
            this.yaw = yaw;
            tick();
        }

        public static double calculate(double xDiff, double zDiff) {
            double x = Math.abs(xDiff);
            double z = Math.abs(zDiff);
            double straight;
            double diagonal;
            if (x < z) {
                straight = z - x;
                diagonal = x;
            } else {
                straight = x - z;
                diagonal = z;
            }

            diagonal *= SQRT_2;
            return (diagonal + straight) * BaritoneAPI.getSettings().costHeuristic.value;
        }

        public void tick() {
            if (timer <= 0) {
                timer = 20;

                Vec3d pos = mc.player.getEntityPos();
                float theta = (float) Math.toRadians(yaw);

                x = (int) Math.floor(pos.x - (double) MathHelper.sin(theta) * 100);
                z = (int) Math.floor(pos.z + (double) MathHelper.cos(theta) * 100);
            }

            timer--;
        }

        public boolean isInGoal(int x, int y, int z) {
            return x == this.x && z == this.z;
        }

        public double heuristic(int x, int y, int z) {
            int xDiff = x - this.x;
            int zDiff = z - this.z;
            return calculate(xDiff, zDiff);
        }

        public String toString() {
            return String.format("GoalXZ{x=%s,z=%s}", SettingsUtil.maybeCensor(this.x), SettingsUtil.maybeCensor(this.z));
        }

        public int getX() {
            return this.x;
        }

        public int getZ() {
            return this.z;
        }
    }

    private class BaritoneProcess implements IBaritoneProcess {
        @Override
        public boolean isActive() {
            return pathingPaused;
        }

        @Override
        public PathingCommand onTick(boolean b, boolean b1) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        @Override
        public boolean isTemporary() {
            return true;
        }

        @Override
        public void onLostControl() {
        }

        @Override
        public double priority() {
            return 0d;
        }

        @Override
        public String displayName0() {
            return "Meteor Client";
        }
    }

    private boolean isHazardousTarget(BlockPos pos) {
        if (mc.world == null) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (!state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.LAVA)) return true;

        // If the target is air and the block below is air, treat it as a "void/fall" risk.
        if (state.isAir()) {
            BlockState below = mc.world.getBlockState(pos.down());
            return below.isAir();
        }

        return false;
    }

    static final class SmartGotoDecision {
        final boolean ignoreY;
        final int approachRadius;

        private SmartGotoDecision(boolean ignoreY, int approachRadius) {
            this.ignoreY = ignoreY;
            this.approachRadius = approachRadius;
        }

        static SmartGotoDecision decide(boolean ignoreYHint, Integer selfY, int targetY, boolean safeMode, boolean hazardousTarget) {
            boolean ignoreY = ignoreYHint || (selfY != null && Math.abs(selfY - targetY) <= 3);
            int approachRadius = (safeMode || hazardousTarget) ? 2 : 0;
            return new SmartGotoDecision(ignoreY, approachRadius);
        }
    }

    private static void setBaritoneSettingBoolean(String name, boolean value) {
        try {
            var settings = BaritoneAPI.getSettings();
            var field = settings.getClass().getDeclaredField(name);
            Object obj = field.get(settings);
            if (obj instanceof baritone.api.Settings.Setting<?> setting && setting.value instanceof Boolean) {
                @SuppressWarnings("unchecked")
                baritone.api.Settings.Setting<Boolean> booleanSetting = (baritone.api.Settings.Setting<Boolean>) setting;
                booleanSetting.value = value;
            }
        } catch (Throwable ignored) {
            // Best effort only; Baritone settings differ across versions.
        }
    }

    private static void setBaritoneSettingInt(String name, int value) {
        try {
            var settings = BaritoneAPI.getSettings();
            var field = settings.getClass().getDeclaredField(name);
            Object obj = field.get(settings);
            if (obj instanceof baritone.api.Settings.Setting<?> setting && setting.value instanceof Integer) {
                @SuppressWarnings("unchecked")
                baritone.api.Settings.Setting<Integer> intSetting = (baritone.api.Settings.Setting<Integer>) setting;
                intSetting.value = value;
            }
        } catch (Throwable ignored) {
        }
    }

    private static String formatSettingInt(Integer value) {
        return value != null ? value.toString() : "n/a";
    }

    private static final class SafeModeSnapshot {
        private final Boolean allowParkour;
        private final Boolean allowParkourAscend;
        private final Boolean allowParkourPlace;
        private final Boolean allowSprint;
        private final Boolean allowWaterBucketFall;
        private final Boolean avoidance;
        private final Integer mobAvoidanceRadius;
        private final Integer mobSpawnerAvoidanceRadius;

        private final Boolean allowBreak;
        private final Boolean allowPlace;
        private final Boolean allowInventory;
        private final Boolean allowDiagonalDescend;
        private final Boolean allowDiagonalAscend;
        private final Boolean allowDownward;
        private final Boolean strictLiquidCheck;
        private final Boolean cutoffAtLoadBoundary;
        private final Integer maxFallHeightNoWater;
        private final Integer maxFallHeightBucket;

        private SafeModeSnapshot(
            Boolean allowParkour,
            Boolean allowParkourAscend,
            Boolean allowParkourPlace,
            Boolean allowSprint,
            Boolean allowWaterBucketFall,
            Boolean avoidance,
            Integer mobAvoidanceRadius,
            Integer mobSpawnerAvoidanceRadius,
            Boolean allowBreak,
            Boolean allowPlace,
            Boolean allowInventory,
            Boolean allowDiagonalDescend,
            Boolean allowDiagonalAscend,
            Boolean allowDownward,
            Boolean strictLiquidCheck,
            Boolean cutoffAtLoadBoundary,
            Integer maxFallHeightNoWater,
            Integer maxFallHeightBucket
        ) {
            this.allowParkour = allowParkour;
            this.allowParkourAscend = allowParkourAscend;
            this.allowParkourPlace = allowParkourPlace;
            this.allowSprint = allowSprint;
            this.allowWaterBucketFall = allowWaterBucketFall;
            this.avoidance = avoidance;
            this.mobAvoidanceRadius = mobAvoidanceRadius;
            this.mobSpawnerAvoidanceRadius = mobSpawnerAvoidanceRadius;

            this.allowBreak = allowBreak;
            this.allowPlace = allowPlace;
            this.allowInventory = allowInventory;
            this.allowDiagonalDescend = allowDiagonalDescend;
            this.allowDiagonalAscend = allowDiagonalAscend;
            this.allowDownward = allowDownward;
            this.strictLiquidCheck = strictLiquidCheck;
            this.cutoffAtLoadBoundary = cutoffAtLoadBoundary;
            this.maxFallHeightNoWater = maxFallHeightNoWater;
            this.maxFallHeightBucket = maxFallHeightBucket;
        }

        static SafeModeSnapshot capture() {
            return new SafeModeSnapshot(
                getBaritoneSettingBoolean("allowParkour"),
                getBaritoneSettingBoolean("allowParkourAscend"),
                getBaritoneSettingBoolean("allowParkourPlace"),
                getBaritoneSettingBoolean("allowSprint"),
                getBaritoneSettingBoolean("allowWaterBucketFall"),
                getBaritoneSettingBoolean("avoidance"),
                getBaritoneSettingInt("mobAvoidanceRadius"),
                getBaritoneSettingInt("mobSpawnerAvoidanceRadius"),

                getBaritoneSettingBoolean("allowBreak"),
                getBaritoneSettingBoolean("allowPlace"),
                getBaritoneSettingBoolean("allowInventory"),
                getBaritoneSettingBoolean("allowDiagonalDescend"),
                getBaritoneSettingBoolean("allowDiagonalAscend"),
                getBaritoneSettingBoolean("allowDownward"),
                getBaritoneSettingBoolean("strictLiquidCheck"),
                getBaritoneSettingBoolean("cutoffAtLoadBoundary"),
                getBaritoneSettingInt("maxFallHeightNoWater"),
                getBaritoneSettingInt("maxFallHeightBucket")
            );
        }

        void restore() {
            if (allowParkour != null) setBaritoneSettingBoolean("allowParkour", allowParkour);
            if (allowParkourAscend != null) setBaritoneSettingBoolean("allowParkourAscend", allowParkourAscend);
            if (allowParkourPlace != null) setBaritoneSettingBoolean("allowParkourPlace", allowParkourPlace);
            if (allowSprint != null) setBaritoneSettingBoolean("allowSprint", allowSprint);
            if (allowWaterBucketFall != null) setBaritoneSettingBoolean("allowWaterBucketFall", allowWaterBucketFall);
            if (avoidance != null) setBaritoneSettingBoolean("avoidance", avoidance);
            if (mobAvoidanceRadius != null) setBaritoneSettingInt("mobAvoidanceRadius", mobAvoidanceRadius);
            if (mobSpawnerAvoidanceRadius != null) setBaritoneSettingInt("mobSpawnerAvoidanceRadius", mobSpawnerAvoidanceRadius);

            if (allowBreak != null) setBaritoneSettingBoolean("allowBreak", allowBreak);
            if (allowPlace != null) setBaritoneSettingBoolean("allowPlace", allowPlace);
            if (allowInventory != null) setBaritoneSettingBoolean("allowInventory", allowInventory);
            if (allowDiagonalDescend != null) setBaritoneSettingBoolean("allowDiagonalDescend", allowDiagonalDescend);
            if (allowDiagonalAscend != null) setBaritoneSettingBoolean("allowDiagonalAscend", allowDiagonalAscend);
            if (allowDownward != null) setBaritoneSettingBoolean("allowDownward", allowDownward);
            if (strictLiquidCheck != null) setBaritoneSettingBoolean("strictLiquidCheck", strictLiquidCheck);
            if (cutoffAtLoadBoundary != null) setBaritoneSettingBoolean("cutoffAtLoadBoundary", cutoffAtLoadBoundary);
            if (maxFallHeightNoWater != null) setBaritoneSettingInt("maxFallHeightNoWater", maxFallHeightNoWater);
            if (maxFallHeightBucket != null) setBaritoneSettingInt("maxFallHeightBucket", maxFallHeightBucket);
        }

        private static Boolean getBaritoneSettingBoolean(String name) {
            try {
                var settings = BaritoneAPI.getSettings();
                var field = settings.getClass().getDeclaredField(name);
                Object obj = field.get(settings);
                if (obj instanceof baritone.api.Settings.Setting setting && setting.value instanceof Boolean b) {
                    return b;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private static Integer getBaritoneSettingInt(String name) {
            try {
                var settings = BaritoneAPI.getSettings();
                var field = settings.getClass().getDeclaredField(name);
                Object obj = field.get(settings);
                if (obj instanceof baritone.api.Settings.Setting setting && setting.value instanceof Integer i) {
                    return i;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    private static Boolean getBaritoneSettingBoolean(String name) {
        return SafeModeSnapshot.getBaritoneSettingBoolean(name);
    }

    private static Integer getBaritoneSettingInt(String name) {
        return SafeModeSnapshot.getBaritoneSettingInt(name);
    }

    private final class RecoveryController {
        private Goal goal;
        private int phase;
        private int phaseTicks;
        private int yawFlip;

        void start(Goal goal) {
            this.goal = goal;
            this.phase = 0;
            this.phaseTicks = 0;
        }

        void tick() {
            if (goal == null) return;
            if (mc.player == null) {
                cancel();
                return;
            }

            var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

            // If something else replaced the goal, abort recovery.
            if (goal != baritone.getCustomGoalProcess().getGoal() && baritone.getCustomGoalProcess().getGoal() != null) {
                cancel();
                return;
            }

            // Phase 0: cancel + clear inputs
            if (phase == 0) {
                baritone.getPathingBehavior().cancelEverything();
                baritone.getInputOverrideHandler().clearAllKeys();
                phase = 1;
                phaseTicks = 0;

                // Rotate a bit to break repeated collision patterns.
                float yaw = mc.player.getYaw() + ((yawFlip++ % 2 == 0) ? 90f : -90f);
                mc.player.setYaw(yaw);
                return;
            }

            // Phase 1: backstep a few ticks
            if (phase == 1) {
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                phaseTicks++;
                if (phaseTicks >= 6) {
                    phase = 2;
                    phaseTicks = 0;
                }
                return;
            }

            // Phase 2: jump while backing up
            if (phase == 2) {
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                phaseTicks++;
                if (phaseTicks >= 2) {
                    phase = 3;
                    phaseTicks = 0;
                }
                return;
            }

            // Phase 3: clear inputs, re-path
            if (phase == 3) {
                baritone.getInputOverrideHandler().clearAllKeys();
                phaseTicks++;
                if (phaseTicks >= 2) {
                    baritone.getCustomGoalProcess().setGoalAndPath(goal);
                    cancel();
                }
            }
        }

        private void cancel() {
            this.goal = null;
            this.phase = 0;
            this.phaseTicks = 0;
        }
    }

    private final class StuckTracker {
        private Vec3d lastPos;
        private int stuckTicks;

        void tick() {
            if (mc.player == null) {
                lastPos = null;
                stuckTicks = 0;
                return;
            }

            boolean pathing = isPathing();
            if (!pathing) {
                lastPos = null;
                stuckTicks = 0;
                return;
            }

            Vec3d now = mc.player.getEntityPos();
            if (lastPos == null) {
                lastPos = now;
                stuckTicks = 0;
                return;
            }

            double movedSq = now.squaredDistanceTo(lastPos);
            // If we haven't moved meaningfully for a while while pathing, count as stuck.
            if (movedSq < 0.05 * 0.05) stuckTicks++;
            else stuckTicks = 0;

            lastPos = now;
        }

        @SuppressWarnings("unused")
        boolean isStuck() {
            return stuckTicks >= 60;
        }
    }
}
