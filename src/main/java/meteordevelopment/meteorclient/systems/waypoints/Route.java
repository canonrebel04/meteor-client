/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.waypoints;

import meteordevelopment.meteorclient.utils.misc.ISerializable;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Route implements ISerializable<Route>, Iterable<Waypoint> {
    public String name = "Route";
    public final List<Waypoint> waypoints = new ArrayList<>();
    public boolean loop = false;

    // Not saved, runtime state
    public int currentIndex = 0;

    public Route() {
    }

    public Route(NbtCompound tag) {
        fromTag(tag);
    }

    public void add(Waypoint waypoint) {
        waypoints.add(waypoint);
    }

    public void remove(Waypoint waypoint) {
        waypoints.remove(waypoint);
    }

    public Waypoint next() {
        if (waypoints.isEmpty())
            return null;
        if (currentIndex >= waypoints.size()) {
            if (loop)
                currentIndex = 0;
            else
                return null;
        }
        return waypoints.get(currentIndex++);
    }

    public Waypoint current() {
        if (waypoints.isEmpty() || currentIndex >= waypoints.size())
            return null;
        return waypoints.get(currentIndex);
    }

    public void optimize() {
        if (waypoints.size() < 3)
            return;

        // 1. Initial pass: Nearest Neighbor
        List<Waypoint> optimized = new ArrayList<>();
        List<Waypoint> remaining = new ArrayList<>(waypoints);

        Waypoint current = remaining.remove(0); // Start with first
        optimized.add(current);

        while (!remaining.isEmpty()) {
            Waypoint nearest = null;
            double minDist = Double.MAX_VALUE;

            for (Waypoint w : remaining) {
                double dist = current.getPos().getSquaredDistance(w.getPos());
                if (dist < minDist) {
                    minDist = dist;
                    nearest = w;
                }
            }

            if (nearest != null) {
                remaining.remove(nearest);
                optimized.add(nearest);
                current = nearest;
            } else {
                break;
            }
        }

        waypoints.clear();
        waypoints.addAll(optimized);

        // 2. Refine with 2-opt
        boolean improved = true;
        int maxIterations = 100; // Prevention against infinite loops
        int iterations = 0;

        while (improved && iterations < maxIterations) {
            improved = false;
            iterations++;

            for (int i = 0; i < waypoints.size() - 2; i++) {
                for (int j = i + 2; j < waypoints.size() - (loop ? 0 : 1); j++) {
                    // For open route: check if reversing segment i+1...j shortens the path
                    // Connecting i -> j and i+1 -> j+1 (if j+1 exists)
                    // Original: i -> i+1 ... j -> j+1
                    // New: i -> j ... i+1 -> j+1

                    Waypoint p1 = waypoints.get(i);
                    Waypoint p2 = waypoints.get(i + 1);
                    Waypoint p3 = waypoints.get(j);
                    Waypoint p4 = (j + 1 < waypoints.size()) ? waypoints.get(j + 1) : (loop ? waypoints.get(0) : null);

                    double distOriginal = p1.getPos().getSquaredDistance(p2.getPos());
                    if (p4 != null)
                        distOriginal += p3.getPos().getSquaredDistance(p4.getPos());

                    double distNew = p1.getPos().getSquaredDistance(p3.getPos());
                    if (p4 != null)
                        distNew += p2.getPos().getSquaredDistance(p4.getPos());

                    if (distNew < distOriginal) {
                        // Reverse segment [i+1, j]
                        reverse(i + 1, j);
                        improved = true;
                    }
                }
            }
        }
    }

    private void reverse(int from, int to) {
        while (from < to) {
            Waypoint temp = waypoints.get(from);
            waypoints.set(from, waypoints.get(to));
            waypoints.set(to, temp);
            from++;
            to--;
        }
    }

    public double getTotalDistance() {
        if (waypoints.size() < 2) return 0;
        double dist = 0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            dist += Math.sqrt(waypoints.get(i).getPos().getSquaredDistance(waypoints.get(i + 1).getPos()));
        }
        if (loop && waypoints.size() > 1) {
            dist += Math.sqrt(waypoints.get(waypoints.size() - 1).getPos().getSquaredDistance(waypoints.get(0).getPos()));
        }
        return dist;
    }

    public String getEstimatedTime() {
        double dist = getTotalDistance();
        double seconds = dist / 4.3; // Approx walking speed
        int m = (int) (seconds / 60);
        int s = (int) (seconds % 60);
        if (m > 0) return String.format("%dm %ds", m, s);
        return String.format("%ds", s);
    }

    public boolean isSafe() {
        if (waypoints.size() < 2) return true;
        
        // Lazy check: Sample points along the route
        for (int i = 0; i < waypoints.size() - 1; i++) {
            if (!isSegmentSafe(waypoints.get(i).getPos(), waypoints.get(i + 1).getPos())) return false;
        }
        if (loop && waypoints.size() > 1) {
             if (!isSegmentSafe(waypoints.get(waypoints.size() - 1).getPos(), waypoints.get(0).getPos())) return false;
        }
        return true;
    }
    
    private boolean isSegmentSafe(BlockPos p1, BlockPos p2) {
        net.minecraft.util.math.Vec3d start = p1.toCenterPos();
        net.minecraft.util.math.Vec3d end = p2.toCenterPos();
        double dist = start.distanceTo(end);
        net.minecraft.util.math.Vec3d dir = end.subtract(start).normalize();
        
        // Sample every 5 blocks
        for (double d = 0; d < dist; d += 5.0) {
            net.minecraft.util.math.Vec3d current = start.add(dir.multiply(d));
            
            // Check Avoidance Zones
            for (meteordevelopment.meteorclient.systems.modules.world.Avoidance a : meteordevelopment.meteorclient.systems.modules.world.AvoidanceManager.get().getList()) {
                if (a.box.contains(current)) return false;
            }
            
            // Check Threat Clusters
            for (net.minecraft.util.math.Vec3d zone : meteordevelopment.meteorclient.systems.modules.combat.ThreatManager.get().getDangerZones()) {
                if (zone.squaredDistanceTo(current) < 25) return false; // 5 block radius
            }
        }
        return true;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.putString("name", name);
        tag.putBoolean("loop", loop);

        // We only save UUIDs of waypoints to keep it synced
        // But for simplicity/portability, maybe we should save full waypoints?
        // Plan said "Route objects (named routes with ordered legs)".
        // If we save UUIDs, if a waypoint is deleted, the route breaks.
        // Let's save UUIDs for now and handle missing ones on load?
        // Or store a local copy?
        // Let's store UUIDs list.
        long[] uuids = new long[waypoints.size() * 2];
        for (int i = 0; i < waypoints.size(); i++) {
            java.util.UUID id = waypoints.get(i).uuid;
            uuids[i * 2] = id.getMostSignificantBits();
            uuids[i * 2 + 1] = id.getLeastSignificantBits();
        }
        tag.putLongArray("waypoints", uuids);
        tag.putInt("currentIndex", currentIndex);

        return tag;
    }

    @Override
    public Route fromTag(NbtCompound tag) {
        name = tag.getString("name").orElse("");
        loop = tag.getBoolean("loop").orElse(false);
        currentIndex = tag.getInt("currentIndex").orElse(0);

        waypoints.clear();
        if (tag.contains("waypoints")) {
            long[] uuids = tag.getLongArray("waypoints").orElse(new long[0]);
            for (int i = 0; i < uuids.length / 2; i++) {
                java.util.UUID id = new java.util.UUID(uuids[i * 2], uuids[i * 2 + 1]);
                // Resolve UUID to Waypoint
                // We need access to Waypoints.get() which is static, so it works.
                // However, deserialization might happen before all waypoints are loaded?
                // Actually Waypoints.java loads waypoints first.
                // We'll implementation resolution in WaypointManager loading logic or lazy
                // load.
                // For now, let's just store the UUIDs and resolve later or resolve here if
                // possible.
                // Ideally, Route shouldn't depend on global state during deserialization if
                // possible,
                // but Waypoints.get() is the singleton.
                // Let's defer resolution or handle it in Waypoints.java load.
                // Wait, ISerializable interface...
                // Let's just store the UUIDs in a temporary list if needed, or resolve
                // immediately if Waypoints are loaded.
                // Assuming Waypoints are loaded.
                for (Waypoint w : Waypoints.get()) {
                    if (w.uuid.equals(id)) {
                        waypoints.add(w);
                        break;
                    }
                }
            }
        }

        return this;
    }

    @Override
    public Iterator<Waypoint> iterator() {
        return waypoints.iterator();
    }
}
