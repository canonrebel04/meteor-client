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
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class BaritonePathManager implements IPathManager {
    private final BaritoneSettings settings;

    private GoalDirection directionGoal;
    private boolean pathingPaused;

    private boolean safeMode;
    private SafeModeSnapshot safeModeSnapshot;

    private final RecoveryController recoveryController;
    private int recoverCooldownTicks;

    private final StuckTracker stuckTracker;
    private final BaritoneTaskAgent agent;

    public BaritonePathManager() {
        // Subscribe to event bus
        MeteorClient.EVENT_BUS.subscribe(this);

        // Create settings
        settings = new BaritoneSettings();

        recoveryController = new RecoveryController();
        stuckTracker = new StuckTracker();
        agent = new BaritoneTaskAgent(this);

        // Baritone pathing control
        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingControlManager().registerProcess(new BaritoneProcess());
    }

    public boolean isPaused() {
        return pathingPaused;
    }

    public boolean isSafeMode() {
        return safeMode;
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
            }
        }
    }

    public BaritoneTaskAgent getAgent() {
        return agent;
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
    }

    public void smartMoveTo(BlockPos pos) {
        smartMoveTo(pos, false);
    }

    public void smartMoveTo(BlockPos pos, boolean ignoreYHint) {
        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

        BlockPos self = mc.player != null ? mc.player.getBlockPos() : null;
        boolean ignoreY = ignoreYHint || (self != null && Math.abs(self.getY() - pos.getY()) <= 3);

        // If the target looks risky (fluid / void), stop near it instead of forcing exact arrival.
        boolean hazardousTarget = isHazardousTarget(pos);
        int approachRadius = (safeMode || hazardousTarget) ? 2 : 0;

        if (ignoreY) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(pos.getX(), pos.getZ()));
            return;
        }

        if (approachRadius > 0) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, approachRadius));
            return;
        }

        baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(pos));
    }

    public void recover() {
        if (recoverCooldownTicks > 0) return;

        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        Goal goal = baritone.getCustomGoalProcess().getGoal();
        if (goal == null) return;

        recoverCooldownTicks = 40;
        recoveryController.start(goal);
    }

    @Override
    public void moveTo(BlockPos pos, boolean ignoreY) {
        if (ignoreY) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ(pos.getX(), pos.getZ()));
            return;
        }

        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(pos));
    }

    @Override
    public void moveInDirection(float yaw) {
        directionGoal = new GoalDirection(yaw);
        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(directionGoal);
    }

    @Override
    public void mine(Block... blocks) {
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
        agent.tick();

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

    private static final class SafeModeSnapshot {
        private final Boolean allowParkour;
        private final Boolean allowParkourAscend;
        private final Boolean allowParkourPlace;
        private final Boolean allowSprint;
        private final Boolean allowWaterBucketFall;

        private SafeModeSnapshot(Boolean allowParkour, Boolean allowParkourAscend, Boolean allowParkourPlace, Boolean allowSprint, Boolean allowWaterBucketFall) {
            this.allowParkour = allowParkour;
            this.allowParkourAscend = allowParkourAscend;
            this.allowParkourPlace = allowParkourPlace;
            this.allowSprint = allowSprint;
            this.allowWaterBucketFall = allowWaterBucketFall;
        }

        static SafeModeSnapshot capture() {
            return new SafeModeSnapshot(
                getBaritoneSettingBoolean("allowParkour"),
                getBaritoneSettingBoolean("allowParkourAscend"),
                getBaritoneSettingBoolean("allowParkourPlace"),
                getBaritoneSettingBoolean("allowSprint"),
                getBaritoneSettingBoolean("allowWaterBucketFall")
            );
        }

        void restore() {
            if (allowParkour != null) setBaritoneSettingBoolean("allowParkour", allowParkour);
            if (allowParkourAscend != null) setBaritoneSettingBoolean("allowParkourAscend", allowParkourAscend);
            if (allowParkourPlace != null) setBaritoneSettingBoolean("allowParkourPlace", allowParkourPlace);
            if (allowSprint != null) setBaritoneSettingBoolean("allowSprint", allowSprint);
            if (allowWaterBucketFall != null) setBaritoneSettingBoolean("allowWaterBucketFall", allowWaterBucketFall);
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
