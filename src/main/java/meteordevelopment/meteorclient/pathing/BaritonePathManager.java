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
import baritone.api.utils.BetterBlockPos;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class BaritonePathManager implements IPathManager {
    private final BaritoneSettings settings;

    private int tickCounter;

    private GoalDirectionHelper directionGoalHelper;
    private Object directionGoalProxy;

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
        Object baritone = BaritoneUtils.getPrimaryBaritone();
        Object pcm = BaritoneUtils.getPathingControlManager(baritone);

        // Create Proxy for BaritoneProcess
        try {
            Class<?> processClass = Class.forName("baritone.api.process.IBaritoneProcess");
            Object processProxy = Proxy.newProxyInstance(
                    processClass.getClassLoader(),
                    new Class[] { processClass },
                    new BaritoneProcessInvocationHandler(new BaritoneProcessHelper()));
            BaritoneUtils.registerProcess(pcm, processProxy);
        } catch (Exception e) {
            e.printStackTrace();
        }
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
        if (mc.player == null)
            return false;

        float effectiveHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double threshold = Config.get().automationLowHealthHpThreshold.get();
        // Conservative default: treat 3 hearts (6hp) as "low".
        if (threshold <= 0)
            threshold = 6.0;
        return effectiveHealth <= (float) threshold;
    }

    public String getHazardSummary() {
        if (mc.world == null || mc.player == null)
            return null;

        BlockPos pos = mc.player.getBlockPos();

        boolean lava = false;
        boolean water = false;
        boolean fallRisk = false;

        BlockState at = mc.world.getBlockState(pos);
        if (!at.getFluidState().isEmpty()) {
            if (at.getFluidState().isIn(FluidTags.LAVA))
                lava = true;
            if (at.getFluidState().isIn(FluidTags.WATER))
                water = true;
        }

        BlockState below = mc.world.getBlockState(pos.down());
        if (!below.getFluidState().isEmpty()) {
            if (below.getFluidState().isIn(FluidTags.LAVA))
                lava = true;
            if (below.getFluidState().isIn(FluidTags.WATER))
                water = true;
        }

        // Conservative fall risk: only consider edges while on ground.
        if (mc.player.isOnGround() && below.isAir())
            fallRisk = true;

        if (!lava && !water && !fallRisk)
            return null;
        if (lava && water && fallRisk)
            return "Lava, Water, Fall";
        if (lava && water)
            return "Lava, Water";
        if (lava && fallRisk)
            return "Lava, Fall";
        if (water && fallRisk)
            return "Water, Fall";
        if (lava)
            return "Lava";
        if (water)
            return "Water";
        return "Fall";
    }

    public String getGoalSummary() {
        var baritone = BaritoneUtils.getPrimaryBaritone();
        var cgp = BaritoneUtils.getCustomGoalProcess(baritone);
        Goal goal = BaritoneUtils.getGoal(cgp);
        if (goal == null)
            goal = lastGoal;
        return goal != null ? goal.toString() : null;
    }

    public InventorySummary getInventorySummary() {
        if (mc.player == null)
            return new InventorySummary(0, 0);

        int used = 0;
        // Use only main inventory + hotbar. Armor/offhand slots can be empty while the
        // inventory is effectively full for generic items.
        int total = Math.min(36, mc.player.getInventory().size());

        for (int i = 0; i < total; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty())
                used++;
        }

        return new InventorySummary(used, total);
    }

    public record InventorySummary(int usedSlots, int totalSlots) {
    }

    public void setSafeMode(boolean enabled) {
        if (safeMode == enabled)
            return;

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
            if (mobRadius != null)
                setBaritoneSettingInt("mobAvoidanceRadius", Math.max(mobRadius, 16));
            if (spawnerRadius != null)
                setBaritoneSettingInt("mobSpawnerAvoidanceRadius", Math.max(spawnerRadius, 24));

            ChatUtils.infoPrefix("Baritone", "Safe Mode: ON (avoidance=%s, mobRadius=%s, spawnerRadius=%s)",
                    String.valueOf(getBaritoneSettingBoolean("avoidance")),
                    formatSettingInt(getBaritoneSettingInt("mobAvoidanceRadius")),
                    formatSettingInt(getBaritoneSettingInt("mobSpawnerAvoidanceRadius")));
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

                setBaritoneSettingBoolean("allowParkour",
                        (Boolean) BaritoneUtils.getSettingDefaultValue(BaritoneUtils.getSettings().allowParkour));
                setBaritoneSettingBoolean("allowParkourAscend",
                        (Boolean) BaritoneUtils.getSettingDefaultValue(BaritoneUtils.getSettings().allowParkourAscend));
                setBaritoneSettingBoolean("allowParkourPlace",
                        (Boolean) BaritoneUtils.getSettingDefaultValue(BaritoneUtils.getSettings().allowParkourPlace));
                setBaritoneSettingBoolean("allowSprint",
                        (Boolean) BaritoneUtils.getSettingDefaultValue(BaritoneUtils.getSettings().allowSprint));
                setBaritoneSettingBoolean("allowWaterBucketFall", (Boolean) BaritoneUtils
                        .getSettingDefaultValue(BaritoneUtils.getSettings().allowWaterBucketFall));

                setBaritoneSettingBoolean("allowBreak",
                        (Boolean) BaritoneUtils.getSettingDefaultValue(BaritoneUtils.getSettings().allowBreak));
                setBaritoneSettingBoolean("allowPlace",
                        (Boolean) BaritoneUtils.getSettingDefaultValue(BaritoneUtils.getSettings().allowPlace));
                setBaritoneSettingBoolean("allowInventory",
                        (Boolean) BaritoneUtils.getSettingDefaultValue(BaritoneUtils.getSettings().allowInventory));
                setBaritoneSettingBoolean("allowDiagonalDescend", (Boolean) BaritoneUtils
                        .getSettingDefaultValue(BaritoneUtils.getSettings().allowDiagonalDescend));
                setBaritoneSettingBoolean("allowDiagonalAscend", (Boolean) BaritoneUtils
                        .getSettingDefaultValue(BaritoneUtils.getSettings().allowDiagonalAscend));
                setBaritoneSettingBoolean("allowDownward",
                        (Boolean) BaritoneUtils.getSettingDefaultValue(BaritoneUtils.getSettings().allowDownward));
                setBaritoneSettingBoolean("strictLiquidCheck",
                        (Boolean) BaritoneUtils.getSettingDefaultValue(BaritoneUtils.getSettings().strictLiquidCheck));
                setBaritoneSettingBoolean("cutoffAtLoadBoundary", (Boolean) BaritoneUtils
                        .getSettingDefaultValue(BaritoneUtils.getSettings().cutoffAtLoadBoundary));

                setBaritoneSettingInt("maxFallHeightNoWater", (Integer) BaritoneUtils
                        .getSettingDefaultValue(BaritoneUtils.getSettings().maxFallHeightNoWater));
                setBaritoneSettingInt("maxFallHeightBucket", (Integer) BaritoneUtils
                        .getSettingDefaultValue(BaritoneUtils.getSettings().maxFallHeightBucket));

                setBaritoneSettingInt("mobAvoidanceRadius",
                        (Integer) BaritoneUtils.getSettingDefaultValue(BaritoneUtils.getSettings().mobAvoidanceRadius));
                setBaritoneSettingInt("mobSpawnerAvoidanceRadius", (Integer) BaritoneUtils
                        .getSettingDefaultValue(BaritoneUtils.getSettings().mobSpawnerAvoidanceRadius));
            }

            ChatUtils.infoPrefix("Baritone", "Safe Mode: OFF (avoidance=%s, mobRadius=%s, spawnerRadius=%s)",
                    String.valueOf(getBaritoneSettingBoolean("avoidance")),
                    formatSettingInt(getBaritoneSettingInt("mobAvoidanceRadius")),
                    formatSettingInt(getBaritoneSettingInt("mobSpawnerAvoidanceRadius")));
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
        return BaritoneUtils.isPathing(BaritoneUtils.getPathingBehavior(BaritoneUtils.getPrimaryBaritone()));
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
        BaritoneUtils.cancelEverything(BaritoneUtils.getPathingBehavior(BaritoneUtils.getPrimaryBaritone()));

        if (miningProtectionActive) {
            BaritoneUtils.releaseMiningProtection();
            miningProtectionActive = false;
        }
    }

    public void smartMoveTo(BlockPos pos) {
        smartMoveTo(pos, false);
    }

    public void smartMoveTo(BlockPos pos, boolean ignoreYHint) {
        var baritone = BaritoneUtils.getPrimaryBaritone();
        var cgp = BaritoneUtils.getCustomGoalProcess(baritone);

        BlockPos self = mc.player != null ? mc.player.getBlockPos() : null;

        SmartGotoDecision decision = SmartGotoDecision.decide(
                ignoreYHint,
                self != null ? self.getY() : null,
                pos.getY(),
                safeMode,
                isHazardousTarget(pos));

        if (decision.ignoreY) {
            Goal goal = new GoalXZ(pos.getX(), pos.getZ());
            lastGoal = goal;
            BaritoneUtils.setGoalAndPath(cgp, goal);
            return;
        }

        if (decision.approachRadius > 0) {
            Goal goal = new GoalNear(pos, decision.approachRadius);
            lastGoal = goal;
            BaritoneUtils.setGoalAndPath(cgp, goal);
            return;
        }

        Goal goal = new GoalGetToBlock(pos);
        lastGoal = goal;
        BaritoneUtils.setGoalAndPath(cgp, goal);
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

        var baritone = BaritoneUtils.getPrimaryBaritone();
        var cgp = BaritoneUtils.getCustomGoalProcess(baritone);
        Goal goal = BaritoneUtils.getGoal(cgp);

        if (goal == null)
            goal = lastGoal;
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
            ChatUtils.infoPrefix("Baritone", "Recover: started (stuck=true, backoff=%d, cooldown=%dt).",
                    recoverBackoffLevel, recoverCooldownTicks);
        } else {
            ChatUtils.infoPrefix("Baritone", "Recover: started (backoff=%d, cooldown=%dt).", recoverBackoffLevel,
                    recoverCooldownTicks);
        }
    }

    @Override
    public void moveTo(BlockPos pos, boolean ignoreY) {
        var baritone = BaritoneUtils.getPrimaryBaritone();
        var cgp = BaritoneUtils.getCustomGoalProcess(baritone);

        if (ignoreY) {
            Goal goal = new GoalXZ(pos.getX(), pos.getZ());
            lastGoal = goal;
            BaritoneUtils.setGoalAndPath(cgp, goal);
            return;
        }

        Goal goal = new GoalGetToBlock(pos);
        lastGoal = goal;
        BaritoneUtils.setGoalAndPath(cgp, goal);
    }

    @Override
    public void moveInDirection(float yaw) {
        var baritone = BaritoneUtils.getPrimaryBaritone();
        var cgp = BaritoneUtils.getCustomGoalProcess(baritone);

        directionGoalHelper = new GoalDirectionHelper(yaw);

        try {
            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            directionGoalProxy = Proxy.newProxyInstance(
                    goalClass.getClassLoader(),
                    new Class[] { goalClass },
                    new GoalInvocationHandler(directionGoalHelper));

            lastGoal = (Goal) directionGoalProxy;
            BaritoneUtils.setGoalAndPath(cgp, (Goal) directionGoalProxy);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void mine(Block... blocks) {
        // Mining should still avoid breaking containers/redstone if possible.
        BaritoneUtils.acquireMiningProtection();
        miningProtectionActive = true;

        var baritone = BaritoneUtils.getPrimaryBaritone();
        var mp = BaritoneUtils.getMineProcess(baritone);
        BaritoneUtils.mine(mp, blocks);
    }

    @Override
    public void follow(Predicate<Entity> entity) {
        // TODO: Fix wrapper for FollowProcess
        // BaritoneAPI.getProvider().getPrimaryBaritone().getFollowProcess().follow(entity);
    }

    @Override
    public float getTargetYaw() {
        return 0; // TODO: Fix wrapper
        // return
        // BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().playerRotations().getYaw();
    }

    @Override
    public float getTargetPitch() {
        return 0; // TODO: Fix wrapper
        // return
        // BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().playerRotations().getPitch();
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

        if (recoverCooldownTicks > 0)
            recoverCooldownTicks--;
        stuckTracker.tick();
        recoveryController.tick();

        if (directionGoalHelper == null || directionGoalProxy == null)
            return;

        var baritone = BaritoneUtils.getPrimaryBaritone();
        var cgp = BaritoneUtils.getCustomGoalProcess(baritone);

        // Check if our proxy is still the goal
        // directionGoalProxy IS the goal instance.
        // BaritoneUtils.getGoal(cgp) returns strict equality?
        // Note: Baritone might wrap it? No, setGoal stores it.
        Object currentGoal = BaritoneUtils.getGoal(cgp);
        if (directionGoalProxy != currentGoal && (currentGoal == null || !currentGoal.equals(directionGoalProxy))) {
            directionGoalHelper = null;
            directionGoalProxy = null;
            return;
        }

        directionGoalHelper.tick();
    }

    private boolean isHazardousTarget(BlockPos pos) {
        if (mc.world == null)
            return false;

        BlockState state = mc.world.getBlockState(pos);
        if (!state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.LAVA))
            return true;

        // If the target is air and the block below is air, treat it as a "void/fall"
        // risk.
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

        static SmartGotoDecision decide(boolean ignoreYHint, Integer selfY, int targetY, boolean safeMode,
                boolean hazardousTarget) {
            boolean ignoreY = ignoreYHint || (selfY != null && Math.abs(selfY - targetY) <= 3);
            int approachRadius = (safeMode || hazardousTarget) ? 2 : 0;
            return new SmartGotoDecision(ignoreY, approachRadius);
        }
    }

    private static void setBaritoneSettingBoolean(String name, boolean value) {
        try {
            var settings = BaritoneUtils.getSettings();
            var field = settings.getClass().getDeclaredField(name);
            Object obj = field.get(settings);
            if (obj instanceof baritone.api.Settings.Setting<?> setting) {
                BaritoneUtils.setSettingValue(setting, value);
            }
        } catch (Throwable ignored) {
            // Best effort only; Baritone settings differ across versions.
        }
    }

    private static void setBaritoneSettingInt(String name, int value) {
        try {
            var settings = BaritoneUtils.getSettings();
            var field = settings.getClass().getDeclaredField(name);
            Object obj = field.get(settings);
            if (obj instanceof baritone.api.Settings.Setting<?> setting) {
                BaritoneUtils.setSettingValue(setting, value);
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
                Integer maxFallHeightBucket) {
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
                    getBaritoneSettingInt("maxFallHeightBucket"));
        }

        void restore() {
            if (allowParkour != null)
                setBaritoneSettingBoolean("allowParkour", allowParkour);
            if (allowParkourAscend != null)
                setBaritoneSettingBoolean("allowParkourAscend", allowParkourAscend);
            if (allowParkourPlace != null)
                setBaritoneSettingBoolean("allowParkourPlace", allowParkourPlace);
            if (allowSprint != null)
                setBaritoneSettingBoolean("allowSprint", allowSprint);
            if (allowWaterBucketFall != null)
                setBaritoneSettingBoolean("allowWaterBucketFall", allowWaterBucketFall);
            if (avoidance != null)
                setBaritoneSettingBoolean("avoidance", avoidance);
            if (mobAvoidanceRadius != null)
                setBaritoneSettingInt("mobAvoidanceRadius", mobAvoidanceRadius);
            if (mobSpawnerAvoidanceRadius != null)
                setBaritoneSettingInt("mobSpawnerAvoidanceRadius", mobSpawnerAvoidanceRadius);

            if (allowBreak != null)
                setBaritoneSettingBoolean("allowBreak", allowBreak);
            if (allowPlace != null)
                setBaritoneSettingBoolean("allowPlace", allowPlace);
            if (allowInventory != null)
                setBaritoneSettingBoolean("allowInventory", allowInventory);
            if (allowDiagonalDescend != null)
                setBaritoneSettingBoolean("allowDiagonalDescend", allowDiagonalDescend);
            if (allowDiagonalAscend != null)
                setBaritoneSettingBoolean("allowDiagonalAscend", allowDiagonalAscend);
            if (allowDownward != null)
                setBaritoneSettingBoolean("allowDownward", allowDownward);
            if (strictLiquidCheck != null)
                setBaritoneSettingBoolean("strictLiquidCheck", strictLiquidCheck);
            if (cutoffAtLoadBoundary != null)
                setBaritoneSettingBoolean("cutoffAtLoadBoundary", cutoffAtLoadBoundary);
            if (maxFallHeightNoWater != null)
                setBaritoneSettingInt("maxFallHeightNoWater", maxFallHeightNoWater);
            if (maxFallHeightBucket != null)
                setBaritoneSettingInt("maxFallHeightBucket", maxFallHeightBucket);
        }

        private static Boolean getBaritoneSettingBoolean(String name) {
            try {
                var settings = BaritoneUtils.getSettings();
                var field = settings.getClass().getDeclaredField(name);
                Object obj = field.get(settings);
                if (obj instanceof baritone.api.Settings.Setting setting) {
                    Object val = BaritoneUtils.getSettingValue(setting);
                    if (val instanceof Boolean b) {
                        return b;
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private static Integer getBaritoneSettingInt(String name) {
            try {
                var settings = BaritoneUtils.getSettings();
                var field = settings.getClass().getDeclaredField(name);
                Object obj = field.get(settings);
                if (obj instanceof baritone.api.Settings.Setting setting) {
                    Object val = BaritoneUtils.getSettingValue(setting);
                    if (val instanceof Integer i) {
                        return i;
                    }
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
            if (goal == null)
                return;
            if (mc.player == null) {
                cancel();
                return;
            }

            var baritone = BaritoneUtils.getPrimaryBaritone();
            var cgp = BaritoneUtils.getCustomGoalProcess(baritone);
            // If something else replaced the goal, abort recovery.
            if (goal != BaritoneUtils.getGoal(cgp) && BaritoneUtils.getGoal(cgp) != null) {
                cancel();
                return;
            }

            var behavior = BaritoneUtils.getPathingBehavior(baritone);
            var ioh = BaritoneUtils.getInputOverrideHandler(baritone);

            // Phase 0: cancel + clear inputs
            if (phase == 0) {
                BaritoneUtils.cancelEverything(behavior);
                BaritoneUtils.clearAllKeys(ioh);
                phase = 1;
                phaseTicks = 0;

                // Rotate a bit to break repeated collision patterns.
                float yaw = mc.player.getYaw() + ((yawFlip++ % 2 == 0) ? 90f : -90f);
                mc.player.setYaw(yaw);
                return;
            }

            // Phase 1: backstep a few ticks
            if (phase == 1) {
                BaritoneUtils.clearAllKeys(ioh);
                BaritoneUtils.setInputForceState(ioh, BaritoneUtils.getInput("MOVE_BACK"), true);
                phaseTicks++;
                if (phaseTicks >= 6) {
                    phase = 2;
                    phaseTicks = 0;
                }
                return;
            }

            // Phase 2: jump while backing up
            if (phase == 2) {
                // reset keys
                if (ioh != null && !PathManagers.get().isPathing()) {
                    BaritoneUtils.setInputForceState(ioh, BaritoneUtils.getInput("MOVE_BACK"), true);
                    BaritoneUtils.setInputForceState(ioh, BaritoneUtils.getInput("JUMP"), true);
                }
                phaseTicks++;
                if (phaseTicks >= 8) {
                    phase = 3;
                    phaseTicks = 0;
                }
                return;
            }

            // Phase 3: finish up
            BaritoneUtils.clearAllKeys(ioh);
            BaritoneUtils.setGoalAndPath(cgp, goal);
            cancel();
        }

        void cancel() {
            goal = null;
            phase = 0;
            phaseTicks = 0;
        }
    }

    // PROXY HELPERS

    // Goal Helper
    private static class GoalDirectionHelper {
        private static final double SQRT_2 = Math.sqrt(2);
        private final float yaw;
        private int x;
        private int z;
        private int timer;

        public GoalDirectionHelper(float yaw) {
            this.yaw = yaw;
            tick();
        }

        // Delegated methods
        public boolean isInGoal(int x, int y, int z) {
            return x == this.x && z == this.z;
        }

        public double heuristic(int x, int y, int z) {
            int xDiff = x - this.x;
            int zDiff = z - this.z;
            return calculate(xDiff, zDiff);
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
            return (diagonal + straight) * 12;
        }

        @Override
        public String toString() {
            return String.format("GoalXZ{x=%s,z=%s}", this.x, this.z);
        }
    }

    // BaritoneProcess Helper
    private class BaritoneProcessHelper {
        public boolean isActive() {
            return pathingPaused;
        }

        public Object onTick(boolean b1, boolean b2) {
            BaritoneUtils.clearAllKeys(BaritoneUtils.getInputOverrideHandler(BaritoneUtils.getPrimaryBaritone()));
            return BaritoneUtils.createPathingCommandPause();
        }

        public boolean isTemporary() {
            return true;
        }

        public void onLostControl() {
        }

        public double priority() {
            return 0d;
        }

        public String displayName0() {
            return "Meteor Client";
        }
    }

    // Invocation Handlers
    private static class GoalInvocationHandler implements InvocationHandler {
        private final GoalDirectionHelper helper;

        public GoalInvocationHandler(GoalDirectionHelper helper) {
            this.helper = helper;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Class<?> returnType = method.getReturnType();

            // map methods
            if (name.equals("a")) {
                // boolean a(x,y,z) is isInGoal
                // double a(x,y,z) is heuristic
                if (returnType == boolean.class && args.length == 3) {
                    return helper.isInGoal((int) args[0], (int) args[1], (int) args[2]);
                }
                if (returnType == double.class && args.length == 3) {
                    return helper.heuristic((int) args[0], (int) args[1], (int) args[2]);
                }
                // boolean a(BetterBlockPos)?
                // double a()?
            }
            if (name.equals("toString")) {
                return helper.toString();
            }

            return null; // default
        }
    }

    private static class BaritoneProcessInvocationHandler implements InvocationHandler {
        private final BaritoneProcessHelper helper;

        public BaritoneProcessInvocationHandler(BaritoneProcessHelper helper) {
            this.helper = helper;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Class<?> returnType = method.getReturnType();

            if (name.equals("a")) {
                // boolean a() -> isActive
                // void a() -> onLostControl
                // PathingCommand a(bool, bool) -> onTick
                // double a() -> priority
                // String a() -> displayName (default)

                if (returnType == boolean.class && args == null)
                    return helper.isActive();
                if (returnType == void.class && args == null) {
                    helper.onLostControl();
                    return null;
                }
                if (returnType.getName().endsWith("PathingCommand") && args.length == 2) {
                    return helper.onTick((boolean) args[0], (boolean) args[1]);
                }
                if (returnType == double.class && args == null)
                    return helper.priority();
                if (returnType == String.class && args == null)
                    return helper.displayName0(); // Should be displayName() but mapped to a or b?
            }
            if (name.equals("b")) {
                // boolean b() -> isTemporary
                // String b() -> displayName0
                if (returnType == boolean.class && args == null)
                    return helper.isTemporary();
                if (returnType == String.class && args == null)
                    return helper.displayName0();
            }

            // default implementations for default methods if needed
            return null;
        }
    }
}
