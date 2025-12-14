/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3i;

import java.util.List;
import java.util.Random;
import java.lang.reflect.Method;

public class OreScanner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAutomation = settings.createGroup("Automation");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgWaypoints = settings.createGroup("Waypoints");

    // General

    private final Setting<List<Block>> targetBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("ores")
        .description("Which blocks count as ores for scanning.")
        .defaultValue(
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE,
            Blocks.ANCIENT_DEBRIS
        )
        .build()
    );

    private final Setting<Integer> samplesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("samples-per-tick")
        .description("How many positions to sample per tick.")
        .defaultValue(160)
        .min(1)
        .sliderRange(1, 4096)
        .build()
    );

    private final Setting<Integer> sampleRadius = sgGeneral.add(new IntSetting.Builder()
        .name("sample-radius")
        .description("Sampling radius (in blocks) around you.")
        .defaultValue(48)
        .min(8)
        .sliderRange(8, 256)
        .build()
    );

    private final Setting<Integer> minYOffset = sgGeneral.add(new IntSetting.Builder()
        .name("min-y-offset")
        .description("Minimum Y offset (relative to player) to sample.")
        .defaultValue(-64)
        .sliderRange(-256, 0)
        .build()
    );

    private final Setting<Integer> maxYOffset = sgGeneral.add(new IntSetting.Builder()
        .name("max-y-offset")
        .description("Maximum Y offset (relative to player) to sample.")
        .defaultValue(16)
        .sliderRange(0, 256)
        .build()
    );

    private final Setting<Double> keepSeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("keep-seconds")
        .description("How long to keep discovered ore points in memory.")
        .defaultValue(120)
        .min(1)
        .sliderMin(5)
        .sliderMax(1800)
        .build()
    );

    private final Setting<Integer> maxPoints = sgGeneral.add(new IntSetting.Builder()
        .name("max-points")
        .description("Maximum number of ore points to keep.")
        .defaultValue(1024)
        .min(64)
        .sliderRange(64, 20000)
        .build()
    );

    private final Setting<Integer> clusterRadius = sgGeneral.add(new IntSetting.Builder()
        .name("cluster-radius")
        .description("Maximum distance (in blocks) between points to be considered the same cluster.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 12)
        .build()
    );

    private final Setting<Integer> clusterUpdateInterval = sgGeneral.add(new IntSetting.Builder()
        .name("cluster-update-interval")
        .description("How often (in ticks) to recompute clusters.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 200)
        .build()
    );

    private final Setting<Integer> hazardCheckRadius = sgGeneral.add(new IntSetting.Builder()
        .name("hazard-check-radius")
        .description("Radius used to check for lava near the cluster center.")
        .defaultValue(4)
        .min(0)
        .sliderRange(0, 16)
        .build()
    );

    // Automation (Baritone)

    public enum MineMode {
        BestDetected,
        DetectedTypes,
        TargetList
    }

    private final Setting<Boolean> mineWithBaritone = sgAutomation.add(new BoolSetting.Builder()
        .name("mine-with-baritone")
        .description("Use the active Path Manager (Baritone) to mine detected ore clusters.")
        .defaultValue(false)
        .build()
    );

    private final Setting<MineMode> mineMode = sgAutomation.add(new EnumSetting.Builder<MineMode>()
        .name("mine-mode")
        .description("What set of blocks to give to Baritone when mining is triggered.")
        .defaultValue(MineMode.BestDetected)
        .visible(mineWithBaritone::get)
        .build()
    );

    private final Setting<Boolean> mineOnlyWhenDetected = sgAutomation.add(new BoolSetting.Builder()
        .name("mine-only-when-detected")
        .description("Only start mining after at least one matching cluster is detected.")
        .defaultValue(true)
        .visible(mineWithBaritone::get)
        .build()
    );

    private final Setting<Integer> minClusterSizeToMine = sgAutomation.add(new IntSetting.Builder()
        .name("min-cluster-size-to-mine")
        .description("Minimum cluster size required before mining is triggered.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 64)
        .visible(mineWithBaritone::get)
        .build()
    );

    private final Setting<Integer> maxMineTypes = sgAutomation.add(new IntSetting.Builder()
        .name("max-types")
        .description("Maximum number of distinct block types to mine at once.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 64)
        .visible(mineWithBaritone::get)
        .build()
    );

    private final Setting<Boolean> avoidLavaRiskClusters = sgAutomation.add(new BoolSetting.Builder()
        .name("avoid-lava-risk")
        .description("Avoid triggering mining if the best detected clusters are flagged as lava-risk.")
        .defaultValue(true)
        .visible(mineWithBaritone::get)
        .build()
    );

    private final Setting<Integer> mineCooldownTicks = sgAutomation.add(new IntSetting.Builder()
        .name("mine-cooldown")
        .description("Minimum ticks between mining trigger attempts.")
        .defaultValue(60)
        .min(0)
        .sliderRange(0, 600)
        .visible(mineWithBaritone::get)
        .build()
    );

    private final Setting<Boolean> stopPathingOnDisable = sgAutomation.add(new BoolSetting.Builder()
        .name("stop-on-disable")
        .description("Stop the current Path Manager task when this module is disabled.")
        .defaultValue(true)
        .visible(mineWithBaritone::get)
        .build()
    );

    private final Setting<Boolean> stopOnInventoryFull = sgAutomation.add(new BoolSetting.Builder()
        .name("stop-on-inventory-full")
        .description("Stop mining when your main inventory + hotbar are full.")
        .defaultValue(true)
        .visible(mineWithBaritone::get)
        .build()
    );

    private final Setting<Boolean> stopOnLowToolDurability = sgAutomation.add(new BoolSetting.Builder()
        .name("stop-on-low-tool-durability")
        .description("Stop mining when the held tool is low durability (prevents breaking tools).")
        .defaultValue(true)
        .visible(mineWithBaritone::get)
        .build()
    );

    private final Setting<Integer> minToolDurabilityRemaining = sgAutomation.add(new IntSetting.Builder()
        .name("min-tool-durability-remaining")
        .description("Minimum remaining durability for the held tool before stopping.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 64)
        .visible(() -> mineWithBaritone.get() && stopOnLowToolDurability.get())
        .build()
    );

    private final Setting<Boolean> returnHomeOnStop = sgAutomation.add(new BoolSetting.Builder()
        .name("return-home-on-stop")
        .description("After stopping due to a stop condition, go to a waypoint by name.")
        .defaultValue(false)
        .visible(mineWithBaritone::get)
        .build()
    );

    private final Setting<String> returnHomeWaypointName = sgAutomation.add(new StringSetting.Builder()
        .name("home-waypoint-name")
        .description("Waypoint name used for return-home.")
        .defaultValue("Home")
        .visible(() -> mineWithBaritone.get() && returnHomeOnStop.get())
        .build()
    );

    // Automation (Vein mine)

    private final Setting<Boolean> veinMine = sgAutomation.add(new BoolSetting.Builder()
        .name("vein-mine")
        .description("Instead of mining by block type, mine the best detected cluster as a connected component (Baritone builder).")
        .defaultValue(false)
        .visible(mineWithBaritone::get)
        .build()
    );

    private final Setting<Integer> veinRadius = sgAutomation.add(new IntSetting.Builder()
        .name("vein-radius")
        .description("Maximum radius around the seed ore block when collecting connected blocks.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 32)
        .visible(() -> mineWithBaritone.get() && veinMine.get())
        .build()
    );

    private final Setting<Integer> veinMaxBlocks = sgAutomation.add(new IntSetting.Builder()
        .name("vein-max-blocks")
        .description("Hard cap on how many blocks to mine in a single vein-mine run.")
        .defaultValue(48)
        .min(1)
        .sliderRange(1, 256)
        .visible(() -> mineWithBaritone.get() && veinMine.get())
        .build()
    );

    private final Setting<Boolean> veinAvoidAdjacentLava = sgAutomation.add(new BoolSetting.Builder()
        .name("vein-avoid-adjacent-lava")
        .description("Skip mining ore blocks that have lava directly adjacent (face-neighbor).")
        .defaultValue(true)
        .visible(() -> mineWithBaritone.get() && veinMine.get())
        .build()
    );

    // Render

    private final Setting<Boolean> renderClusters = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Render detected clusters.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxRendered = sgRender.add(new IntSetting.Builder()
        .name("max-rendered")
        .description("Maximum number of clusters rendered per frame.")
        .defaultValue(64)
        .min(1)
        .sliderRange(1, 512)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How clusters are rendered.")
        .defaultValue(ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Cluster fill color.")
        .defaultValue(new SettingColor(255, 255, 0, 10))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Cluster outline color.")
        .defaultValue(new SettingColor(255, 255, 0, 200))
        .build()
    );

    // Waypoints

    private final Setting<Boolean> createWaypoints = sgWaypoints.add(new BoolSetting.Builder()
        .name("create-waypoints")
        .description("Create waypoints for clusters that meet minimum size.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> minClusterSizeForWaypoint = sgWaypoints.add(new IntSetting.Builder()
        .name("min-cluster-size")
        .description("Minimum ore points required to create a cluster waypoint.")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 64)
        .visible(createWaypoints::get)
        .build()
    );

    private final Setting<Integer> waypointIntervalTicks = sgWaypoints.add(new IntSetting.Builder()
        .name("waypoint-interval")
        .description("Minimum ticks between auto-created waypoints.")
        .defaultValue(40)
        .min(0)
        .sliderRange(0, 400)
        .visible(createWaypoints::get)
        .build()
    );

    private final Setting<Integer> maxWaypointsPerSession = sgWaypoints.add(new IntSetting.Builder()
        .name("max-waypoints-per-session")
        .description("Hard cap of auto-created ore waypoints per session.")
        .defaultValue(20)
        .min(0)
        .sliderRange(0, 500)
        .visible(createWaypoints::get)
        .build()
    );

    private final Setting<String> waypointPrefix = sgWaypoints.add(new StringSetting.Builder()
        .name("waypoint-prefix")
        .description("Prefix for created ore waypoints.")
        .defaultValue("Ore")
        .visible(createWaypoints::get)
        .build()
    );

    // State

    private final Random rng = new Random();
    private final BlockPos.Mutable samplePos = new BlockPos.Mutable();

    // posLong -> lastSeenTick
    private final Long2IntOpenHashMap seen = new Long2IntOpenHashMap();

    private long[] points = new long[0];
    private Block[] pointBlock = new Block[0];
    private int pointCount;

    // clusters
    private int[] clusterMinX = new int[0];
    private int[] clusterMinY = new int[0];
    private int[] clusterMinZ = new int[0];
    private int[] clusterMaxX = new int[0];
    private int[] clusterMaxY = new int[0];
    private int[] clusterMaxZ = new int[0];
    private int[] clusterSize = new int[0];
    private Block[] clusterBlock = new Block[0];
    private boolean[] clusterLavaRisk = new boolean[0];
    private int clusterCount;

    private int tick;
    private int lastClusterTick;

    private int lastWaypointTick;
    private int waypointsThisSession;

    private int lastMineTick;

    private int lastStopTick;
    private String lastStopReason;

    // Vein mining state (Baritone builder: clearArea 1x1x1 per block)
    private boolean veinActive;
    private long[] veinTargets = new long[0];
    private int veinTargetCount;
    private int veinTargetIndex;

    // Cached reflection for Baritone (keeps runtime optional)
    private transient boolean baritoneResolveAttempted;
    private transient boolean baritoneResolved;
    private transient Object baritone;
    private transient Object builderProcess;
    private transient Object commandManager;
    private transient Method builderIsActive;
    private transient Method builderClearArea;
    private transient Method commandExecute;
    private transient Class<?> betterBlockPosClass;
    private transient Method betterBlockPosFrom;

    public OreScanner() {
        super(Categories.World, "ore-scanner", "Bounded ore detection + simple clustering foundation for mining automation.");
        seen.defaultReturnValue(Integer.MIN_VALUE);
    }

    @Override
    public void onDeactivate() {
        if (stopPathingOnDisable.get() && mineWithBaritone.get()) {
            PathManagers.get().stop();
        }

        if (veinActive) {
            cancelBaritoneBuilder();
        }

        reset();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) {
            reset();
            return;
        }

        tick++;

        // Stop conditions (bounded): inventory full / low tool durability.
        if (mineWithBaritone.get() && shouldStopMining(mc.player)) {
            stopMiningAndMaybeReturnHome();
            // If we stopped, don't do more work this tick.
            return;
        }

        // If a vein-mine run is active, drive it forward (one clear task at a time).
        if (veinActive) {
            driveVeinMining();
        }

        ensurePointCapacity(maxPoints.get());

        // Prune expired points based on TTL.
        int ttlTicks = (int) Math.round(keepSeconds.get() * 20.0);
        if (ttlTicks < 1) ttlTicks = 1;

        prunePoints(ttlTicks);

        // Sample new points.
        int r = sampleRadius.get();
        int samples = samplesPerTick.get();

        int minDy = minYOffset.get();
        int maxDy = maxYOffset.get();
        if (minDy > maxDy) {
            int tmp = minDy;
            minDy = maxDy;
            maxDy = tmp;
        }

        int px = mc.player.getBlockX();
        int py = mc.player.getBlockY();
        int pz = mc.player.getBlockZ();

        int worldBottom = mc.world.getBottomY();
        int worldTop = worldBottom + mc.world.getHeight() - 1;

        List<Block> targets = targetBlocks.get();
        if (targets == null || targets.isEmpty()) return;

        for (int i = 0; i < samples && pointCount < points.length; i++) {
            int ox = rng.nextInt(r * 2 + 1) - r;
            int oz = rng.nextInt(r * 2 + 1) - r;
            int oy = rng.nextInt((maxDy - minDy) + 1) + minDy;

            int x = px + ox;
            int y = MathHelper.clamp(py + oy, worldBottom, worldTop);
            int z = pz + oz;

            samplePos.set(x, y, z);
            BlockState state = mc.world.getBlockState(samplePos);
            Block b = state.getBlock();
            if (!targets.contains(b)) continue;

            long packed = samplePos.asLong();
            int lastSeen = seen.get(packed);
            if (lastSeen != Integer.MIN_VALUE) {
                seen.put(packed, tick);
                continue;
            }

            points[pointCount] = packed;
            pointBlock[pointCount] = b;
            pointCount++;
            seen.put(packed, tick);
        }

        // Recompute clusters at a throttled rate.
        int interval = clusterUpdateInterval.get();
        if (interval < 1) interval = 1;
        if (tick - lastClusterTick >= interval) {
            recomputeClusters();
            lastClusterTick = tick;

            if (createWaypoints.get()) maybeCreateWaypoints();

            if (mineWithBaritone.get()) {
                // Prefer vein mining when enabled; otherwise fall back to type-based mining.
                if (!maybeTriggerVeinMining()) {
                    maybeTriggerMining();
                }
            }
        }
    }

    private boolean shouldStopMining(ClientPlayerEntity player) {
        if (player == null) return false;

        // Throttle: once per second.
        if (tick - lastStopTick < 20) return false;

        if (stopOnInventoryFull.get() && isMainInventoryFull(player)) {
            lastStopReason = "Inventory Full";
            return true;
        }

        if (stopOnLowToolDurability.get() && isHeldToolLowDurability(player)) {
            lastStopReason = "Low Tool";
            return true;
        }

        return false;
    }

    private boolean isMainInventoryFull(ClientPlayerEntity player) {
        int total = Math.min(36, player.getInventory().size());
        for (int i = 0; i < total; i++) {
            if (player.getInventory().getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private boolean isHeldToolLowDurability(ClientPlayerEntity player) {
        var stack = player.getMainHandStack();
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isDamageable()) return false;

        int remaining = stack.getMaxDamage() - stack.getDamage();
        int minRemaining = Math.max(1, minToolDurabilityRemaining.get());
        return remaining <= minRemaining;
    }

    private void stopMiningAndMaybeReturnHome() {
        // Stop current pathing/mining and any vein builder activity.
        if (PathManagers.get().isPathing()) PathManagers.get().stop();

        if (veinActive) {
            cancelBaritoneBuilder();
            veinActive = false;
            veinTargetCount = 0;
            veinTargetIndex = 0;
        }

        lastStopTick = tick;

        if (lastStopReason != null) {
            info("Stopped: %s", lastStopReason);
        } else {
            info("Stopped");
        }

        if (!returnHomeOnStop.get()) return;

        Waypoint wp = null;
        String name = returnHomeWaypointName.get();
        if (name != null && !name.isBlank()) wp = Waypoints.get().get(name.trim());
        if (wp == null) return;

        BlockPos pos = wp.getPos();
        if (pos == null) return;

        PathManagers.get().moveTo(pos, false);
    }

    private void driveVeinMining() {
        if (!veinActive) return;

        if (veinTargetIndex >= veinTargetCount) {
            veinActive = false;
            return;
        }

        if (!ensureBaritoneResolved()) {
            veinActive = false;
            return;
        }

        try {
            if ((boolean) builderIsActive.invoke(builderProcess)) return;

            long packed = veinTargets[veinTargetIndex++];
            int x = BlockPos.unpackLongX(packed);
            int y = BlockPos.unpackLongY(packed);
            int z = BlockPos.unpackLongZ(packed);

            BlockPos pos = new BlockPos(x, y, z);
            Object bbp = betterBlockPosFrom.invoke(null, pos);
            builderClearArea.invoke(builderProcess, bbp, bbp);
        } catch (Throwable t) {
            veinActive = false;
        }
    }

    private void cancelBaritoneBuilder() {
        if (!ensureBaritoneResolved()) return;

        try {
            if ((boolean) builderIsActive.invoke(builderProcess)) {
                commandExecute.invoke(commandManager, "stop");
            }
        } catch (Throwable ignored) {
            // Best-effort cancel.
        }
    }

    private boolean ensureBaritoneResolved() {
        if (baritoneResolved) return true;
        if (baritoneResolveAttempted) return false;
        baritoneResolveAttempted = true;

        try {
            Class<?> baritoneApi = Class.forName("baritone.api.BaritoneAPI");
            Object provider = baritoneApi.getMethod("getProvider").invoke(null);
            baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);

            builderProcess = baritone.getClass().getMethod("getBuilderProcess").invoke(baritone);
            builderIsActive = builderProcess.getClass().getMethod("isActive");

            betterBlockPosClass = Class.forName("baritone.api.utils.BetterBlockPos");
            betterBlockPosFrom = betterBlockPosClass.getMethod("from", BlockPos.class);
            builderClearArea = builderProcess.getClass().getMethod("clearArea", betterBlockPosClass, betterBlockPosClass);

            commandManager = baritone.getClass().getMethod("getCommandManager").invoke(baritone);
            commandExecute = commandManager.getClass().getMethod("execute", String.class);

            baritoneResolved = true;
            return true;
        } catch (Throwable ignored) {
            baritoneResolved = false;
            return false;
        }
    }

    private boolean maybeTriggerVeinMining() {
        if (!veinMine.get()) return false;

        // Avoid triggering a new run if we're already executing one.
        if (veinActive) return true;

        int cooldown = Math.max(0, mineCooldownTicks.get());
        if (cooldown > 0 && tick - lastMineTick < cooldown) return false;

        if (mineOnlyWhenDetected.get() && clusterCount == 0) return false;
        if (PathManagers.get().isPathing()) return false;

        int minSize = Math.max(1, minClusterSizeToMine.get());
        boolean avoidLava = avoidLavaRiskClusters.get();

        int clusterIndex = findBestClusterIndex(minSize, avoidLava);
        if (clusterIndex == -1 && avoidLava) {
            // Fallback: allow lava-risk clusters if nothing else exists.
            clusterIndex = findBestClusterIndex(minSize, false);
        }

        if (clusterIndex == -1) return false;

        Block target = clusterBlock[clusterIndex];
        if (target == null) return false;

        BlockPos seed = findSeedInClusterAabb(clusterIndex, target);
        if (seed == null) return false;

        int radius = Math.max(1, veinRadius.get());
        int maxBlocks = Math.max(1, veinMaxBlocks.get());
        boolean avoidAdjLava = veinAvoidAdjacentLava.get();

        int count = buildVeinTargets(target, seed, radius, maxBlocks, avoidAdjLava);
        if (count <= 0) return false;

        veinActive = true;
        veinTargetCount = count;
        veinTargetIndex = 0;

        // Kick immediately.
        driveVeinMining();

        lastMineTick = tick;
        return true;
    }

    private int findBestClusterIndex(int minSize, boolean avoidLava) {
        for (int c = 0; c < clusterCount; c++) {
            if (clusterSize[c] < minSize) continue;
            if (clusterBlock[c] == null) continue;
            if (avoidLava && clusterLavaRisk[c]) continue;
            return c;
        }
        return -1;
    }

    private BlockPos findSeedInClusterAabb(int clusterIndex, Block target) {
        if (mc.world == null) return null;

        int minX = clusterMinX[clusterIndex];
        int minY = clusterMinY[clusterIndex];
        int minZ = clusterMinZ[clusterIndex];
        int maxX = clusterMaxX[clusterIndex];
        int maxY = clusterMaxY[clusterIndex];
        int maxZ = clusterMaxZ[clusterIndex];

        // Bounded search inside AABB to find a real ore block to seed BFS.
        int checks = 0;
        int maxChecks = 4096;

        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int y = minY; y <= maxY && checks < maxChecks; y++) {
            for (int x = minX; x <= maxX && checks < maxChecks; x++) {
                for (int z = minZ; z <= maxZ && checks < maxChecks; z++) {
                    m.set(x, y, z);
                    checks++;

                    if (mc.world.getBlockState(m).getBlock() == target) {
                        return m.toImmutable();
                    }
                }
            }
        }

        return null;
    }

    private int buildVeinTargets(Block target, BlockPos seed, int radius, int maxBlocks, boolean avoidAdjLava) {
        if (mc.world == null) return 0;

        int cap = Math.max(1, maxBlocks);
        if (veinTargets.length < cap) veinTargets = new long[cap];

        LongOpenHashSet visited = new LongOpenHashSet(Math.min(1024, cap * 8));

        int seedX = seed.getX();
        int seedY = seed.getY();
        int seedZ = seed.getZ();
        int rSq = radius * radius;

        // BFS frontier (bounded).
        int frontierCap = Math.min(4096, cap * 16);
        long[] queue = new long[frontierCap];
        int qHead = 0;
        int qTail = 0;

        BlockPos.Mutable m = new BlockPos.Mutable();

        long seedPacked = seed.asLong();
        queue[qTail++] = seedPacked;
        visited.add(seedPacked);

        int out = 0;

        Vec3i[] neigh = {
            new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0),
            new Vec3i(0, 1, 0), new Vec3i(0, -1, 0),
            new Vec3i(0, 0, 1), new Vec3i(0, 0, -1)
        };

        while (qHead < qTail && out < cap) {
            long packed = queue[qHead++];

            int x = BlockPos.unpackLongX(packed);
            int y = BlockPos.unpackLongY(packed);
            int z = BlockPos.unpackLongZ(packed);

            int dx = x - seedX;
            int dy = y - seedY;
            int dz = z - seedZ;
            if (dx * dx + dy * dy + dz * dz > rSq) continue;

            m.set(x, y, z);
            if (mc.world.getBlockState(m).getBlock() != target) continue;

            if (avoidAdjLava && hasAdjacentLava(m)) {
                continue;
            }

            veinTargets[out++] = packed;

            for (Vec3i o : neigh) {
                int nx = x + o.getX();
                int ny = y + o.getY();
                int nz = z + o.getZ();

                m.set(nx, ny, nz);
                long np = m.asLong();
                if (!visited.add(np)) continue;

                // Keep frontier bounded.
                if (qTail < frontierCap) {
                    queue[qTail++] = np;
                }
            }
        }

        return out;
    }

    private boolean hasAdjacentLava(BlockPos pos) {
        if (mc.world == null) return false;

        BlockPos.Mutable m = new BlockPos.Mutable();
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        m.set(x + 1, y, z);
        if (isLava(m)) return true;
        m.set(x - 1, y, z);
        if (isLava(m)) return true;
        m.set(x, y + 1, z);
        if (isLava(m)) return true;
        m.set(x, y - 1, z);
        if (isLava(m)) return true;
        m.set(x, y, z + 1);
        if (isLava(m)) return true;
        m.set(x, y, z - 1);
        return isLava(m);
    }

    private boolean isLava(BlockPos pos) {
        BlockState s = mc.world.getBlockState(pos);
        return !s.getFluidState().isEmpty() && s.getFluidState().isIn(FluidTags.LAVA);
    }

    private void maybeTriggerMining() {
        int cooldown = Math.max(0, mineCooldownTicks.get());
        if (cooldown > 0 && tick - lastMineTick < cooldown) return;

        if (mineOnlyWhenDetected.get() && clusterCount == 0) return;
        if (PathManagers.get().isPathing()) return;

        Block[] targets = buildMineTargets();
        if (targets == null || targets.length == 0) return;

        PathManagers.get().mine(targets);
        lastMineTick = tick;
    }

    private Block[] buildMineTargets() {
        MineMode mode = mineMode.get();
        if (mode == null) mode = MineMode.DetectedTypes;

        int maxTypes = Math.max(1, maxMineTypes.get());
        int minSize = Math.max(1, minClusterSizeToMine.get());
        boolean avoidLava = avoidLavaRiskClusters.get();

        return switch (mode) {
            case BestDetected -> pickBestDetectedTarget(minSize, avoidLava);
            case DetectedTypes -> pickDetectedTypesTargets(maxTypes, minSize, avoidLava);
            case TargetList -> pickTargetListTargets(maxTypes, minSize, avoidLava);
        };
    }

    private Block[] pickBestDetectedTarget(int minSize, boolean avoidLava) {
        Block[] out = pickDetectedTypesTargets(1, minSize, avoidLava);
        return out;
    }

    private Block[] pickDetectedTypesTargets(int maxTypes, int minSize, boolean avoidLava) {
        // First pass: non-lava clusters (if enabled)
        Block[] out = pickDetectedTypesTargetsInternal(maxTypes, minSize, avoidLava);
        if (out.length > 0) return out;

        // Fallback: allow lava-risk clusters if nothing else exists.
        if (!avoidLava) return out;
        return pickDetectedTypesTargetsInternal(maxTypes, minSize, false);
    }

    private Block[] pickDetectedTypesTargetsInternal(int maxTypes, int minSize, boolean avoidLava) {
        if (clusterCount == 0) return new Block[0];

        int cap = Math.max(1, maxTypes);
        Block[] buf = new Block[cap];
        int count = 0;

        for (int c = 0; c < clusterCount && count < cap; c++) {
            if (clusterSize[c] < minSize) continue;
            Block b = clusterBlock[c];
            if (b == null) continue;
            if (avoidLava && clusterLavaRisk[c]) continue;

            boolean exists = false;
            for (int i = 0; i < count; i++) {
                if (buf[i] == b) {
                    exists = true;
                    break;
                }
            }
            if (exists) continue;

            buf[count++] = b;
        }

        if (count == 0) return new Block[0];

        Block[] out = new Block[count];
        System.arraycopy(buf, 0, out, 0, count);
        return out;
    }

    private Block[] pickTargetListTargets(int maxTypes, int minSize, boolean avoidLava) {
        List<Block> targets = targetBlocks.get();
        if (targets == null || targets.isEmpty()) return new Block[0];

        // If we're using detection gating, we can also respect the lava-risk filter by requiring
        // that at least one eligible cluster exists before starting.
        if (mineOnlyWhenDetected.get()) {
            boolean foundEligibleCluster = false;
            for (int c = 0; c < clusterCount; c++) {
                if (clusterSize[c] < minSize) continue;
                if (avoidLava && clusterLavaRisk[c]) continue;
                foundEligibleCluster = true;
                break;
            }
            if (!foundEligibleCluster) {
                // Allow lava-only clusters as fallback (same semantics as detected-types).
                if (!avoidLava) return new Block[0];
                for (int c = 0; c < clusterCount; c++) {
                    if (clusterSize[c] < minSize) continue;
                    foundEligibleCluster = true;
                    break;
                }
                if (!foundEligibleCluster) return new Block[0];
            }
        }

        int cap = Math.max(1, maxTypes);
        int count = Math.min(cap, targets.size());

        Block[] out = new Block[count];
        for (int i = 0; i < count; i++) {
            out[i] = targets.get(i);
        }
        return out;
    }

    private void prunePoints(int ttlTicks) {
        if (pointCount == 0) return;

        int write = 0;
        for (int i = 0; i < pointCount; i++) {
            long packed = points[i];
            int lastSeen = seen.get(packed);

            if (lastSeen == Integer.MIN_VALUE || tick - lastSeen > ttlTicks) {
                if (lastSeen != Integer.MIN_VALUE) seen.remove(packed);
                continue;
            }

            if (write != i) {
                points[write] = packed;
                pointBlock[write] = pointBlock[i];
            }
            write++;
        }

        pointCount = write;
    }

    private void recomputeClusters() {
        // Bound cluster count to keep it cheap.
        int maxC = Math.min(256, Math.max(16, (int) Math.sqrt(Math.max(1, pointCount)) * 16));
        ensureClusterCapacity(maxC);
        clusterCount = 0;

        int r = Math.max(1, clusterRadius.get());
        int rSq = r * r;

        // Simple greedy clustering: assign each point to the first nearby matching-cluster.
        for (int i = 0; i < pointCount; i++) {
            long packed = points[i];
            Block b = pointBlock[i];
            if (b == null) continue;

            int x = BlockPos.unpackLongX(packed);
            int y = BlockPos.unpackLongY(packed);
            int z = BlockPos.unpackLongZ(packed);

            int assigned = -1;
            for (int c = 0; c < clusterCount; c++) {
                if (clusterBlock[c] != b) continue;

                // Distance to cluster AABB (cheap).
                int cx = clamp(x, clusterMinX[c], clusterMaxX[c]);
                int cy = clamp(y, clusterMinY[c], clusterMaxY[c]);
                int cz = clamp(z, clusterMinZ[c], clusterMaxZ[c]);

                int dx = x - cx;
                int dy = y - cy;
                int dz = z - cz;

                if (dx * dx + dy * dy + dz * dz <= rSq) {
                    assigned = c;
                    break;
                }
            }

            if (assigned == -1) {
                if (clusterCount >= maxC) continue;

                int c = clusterCount++;
                clusterBlock[c] = b;
                clusterMinX[c] = clusterMaxX[c] = x;
                clusterMinY[c] = clusterMaxY[c] = y;
                clusterMinZ[c] = clusterMaxZ[c] = z;
                clusterSize[c] = 1;
                clusterLavaRisk[c] = false;
            } else {
                clusterSize[assigned]++;
                if (x < clusterMinX[assigned]) clusterMinX[assigned] = x;
                if (y < clusterMinY[assigned]) clusterMinY[assigned] = y;
                if (z < clusterMinZ[assigned]) clusterMinZ[assigned] = z;
                if (x > clusterMaxX[assigned]) clusterMaxX[assigned] = x;
                if (y > clusterMaxY[assigned]) clusterMaxY[assigned] = y;
                if (z > clusterMaxZ[assigned]) clusterMaxZ[assigned] = z;
            }
        }

        // Hazard check: lava near cluster center (bounded).
        int hz = hazardCheckRadius.get();
        if (hz > 0 && mc.world != null) {
            BlockPos.Mutable m = new BlockPos.Mutable();

            for (int c = 0; c < clusterCount; c++) {
                int cx = (clusterMinX[c] + clusterMaxX[c]) / 2;
                int cy = (clusterMinY[c] + clusterMaxY[c]) / 2;
                int cz = (clusterMinZ[c] + clusterMaxZ[c]) / 2;

                boolean lava = false;

                for (int dx = -hz; dx <= hz && !lava; dx++) {
                    for (int dy = -hz; dy <= hz && !lava; dy++) {
                        for (int dz = -hz; dz <= hz && !lava; dz++) {
                            if (dx * dx + dy * dy + dz * dz > hz * hz) continue;

                            m.set(cx + dx, cy + dy, cz + dz);
                            BlockState s = mc.world.getBlockState(m);
                            if (!s.getFluidState().isEmpty() && s.getFluidState().isIn(FluidTags.LAVA)) {
                                lava = true;
                            }
                        }
                    }
                }

                clusterLavaRisk[c] = lava;
            }
        }

        // Sort clusters by size descending (small N).
        for (int i = 1; i < clusterCount; i++) {
            int size = clusterSize[i];
            Block b = clusterBlock[i];
            boolean lava = clusterLavaRisk[i];
            int minX = clusterMinX[i], minY = clusterMinY[i], minZ = clusterMinZ[i];
            int maxX = clusterMaxX[i], maxY = clusterMaxY[i], maxZ = clusterMaxZ[i];

            int j = i - 1;
            while (j >= 0 && clusterSize[j] < size) {
                clusterSize[j + 1] = clusterSize[j];
                clusterBlock[j + 1] = clusterBlock[j];
                clusterLavaRisk[j + 1] = clusterLavaRisk[j];
                clusterMinX[j + 1] = clusterMinX[j];
                clusterMinY[j + 1] = clusterMinY[j];
                clusterMinZ[j + 1] = clusterMinZ[j];
                clusterMaxX[j + 1] = clusterMaxX[j];
                clusterMaxY[j + 1] = clusterMaxY[j];
                clusterMaxZ[j + 1] = clusterMaxZ[j];
                j--;
            }

            int k = j + 1;
            clusterSize[k] = size;
            clusterBlock[k] = b;
            clusterLavaRisk[k] = lava;
            clusterMinX[k] = minX;
            clusterMinY[k] = minY;
            clusterMinZ[k] = minZ;
            clusterMaxX[k] = maxX;
            clusterMaxY[k] = maxY;
            clusterMaxZ[k] = maxZ;
        }
    }

    private void maybeCreateWaypoints() {
        int max = maxWaypointsPerSession.get();
        if (max >= 0 && waypointsThisSession >= max) return;

        int interval = Math.max(0, waypointIntervalTicks.get());
        if (interval > 0 && tick - lastWaypointTick < interval) return;

        int minSize = Math.max(1, minClusterSizeForWaypoint.get());

        // Take the best cluster above threshold.
        for (int c = 0; c < clusterCount; c++) {
            if (clusterSize[c] < minSize) continue;

            Block b = clusterBlock[c];
            if (b == null) continue;

            int cx = (clusterMinX[c] + clusterMaxX[c]) / 2;
            int cy = (clusterMinY[c] + clusterMaxY[c]) / 2;
            int cz = (clusterMinZ[c] + clusterMaxZ[c]) / 2;

            String prefix = waypointPrefix.get();
            if (prefix == null || prefix.isBlank()) prefix = "Ore";

            String name = prefix + ": " + b.getName().getString() + " x" + clusterSize[c];
            if (clusterLavaRisk[c]) name += " (lava)";

            Waypoint wp = new Waypoint.Builder()
                .name(name)
                .icon("diamond")
                .pos(new BlockPos(cx, cy, cz))
                .dimension(PlayerUtils.getDimension())
                .build();

            Waypoints.get().add(wp);

            lastWaypointTick = tick;
            waypointsThisSession++;
            return;
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderClusters.get()) return;
        if (clusterCount == 0) return;

        int max = maxRendered.get();
        if (max < 1) return;

        int rendered = 0;

        for (int c = 0; c < clusterCount && rendered < max; c++) {
            int minX = clusterMinX[c];
            int minY = clusterMinY[c];
            int minZ = clusterMinZ[c];
            int maxX = clusterMaxX[c] + 1;
            int maxY = clusterMaxY[c] + 1;
            int maxZ = clusterMaxZ[c] + 1;

            event.renderer.box(minX, minY, minZ, maxX, maxY, maxZ, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            rendered++;
        }
    }

    @Override
    public String getInfoString() {
        if (clusterCount == 0) return null;
        Block b = clusterBlock[0];
        if (b == null) return null;
        return b.getName().getString() + " x" + clusterSize[0];
    }

    private void ensurePointCapacity(int cap) {
        if (cap < 1) cap = 1;
        if (points.length >= cap) return;

        points = new long[cap];
        pointBlock = new Block[cap];
        pointCount = 0;
        seen.clear();
    }

    private void ensureClusterCapacity(int cap) {
        if (cap < 1) cap = 1;
        if (clusterMinX.length >= cap) return;

        clusterMinX = new int[cap];
        clusterMinY = new int[cap];
        clusterMinZ = new int[cap];
        clusterMaxX = new int[cap];
        clusterMaxY = new int[cap];
        clusterMaxZ = new int[cap];
        clusterSize = new int[cap];
        clusterBlock = new Block[cap];
        clusterLavaRisk = new boolean[cap];
        clusterCount = 0;
    }

    private void reset() {
        tick = 0;
        lastClusterTick = 0;
        lastWaypointTick = 0;
        waypointsThisSession = 0;
        lastMineTick = 0;

        pointCount = 0;
        clusterCount = 0;
        seen.clear();
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
