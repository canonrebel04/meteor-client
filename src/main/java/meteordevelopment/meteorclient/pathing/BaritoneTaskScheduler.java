/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import meteordevelopment.meteorclient.systems.config.Config;

import java.util.ArrayDeque;

/**
 * Tick-driven scheduler for {@link BaritoneTaskAgent.Task}.
 *
 * This intentionally mirrors the existing queue semantics from {@link BaritoneTaskAgent}
 * while publishing state into an {@link AutomationBlackboard} for UI/automation decisions.
 */
final class BaritoneTaskScheduler {
    private final BaritonePathManager pathManager;
    private final AutomationBlackboard blackboard;

    private final ArrayDeque<BaritoneTaskAgent.Task> queue = new ArrayDeque<>();

    private BaritoneTaskAgent.Task current;
    private int currentStartedTick;
    private boolean sawPathing;
    private int idleTicksAfterPathing;

    private boolean lastStuck;

    BaritoneTaskScheduler(BaritonePathManager pathManager, AutomationBlackboard blackboard) {
        this.pathManager = pathManager;
        this.blackboard = blackboard;

        publishState();
    }

    AutomationBlackboard blackboard() {
        return blackboard;
    }

    void enqueue(BaritoneTaskAgent.Task task) {
        queue.add(task);
        blackboard.setQueuedTaskCount(queue.size());
        updateQueuePreview();
    }

    void clear() {
        queue.clear();
        blackboard.setQueuedTaskCount(0);
        updateQueuePreview();
        stopAndForgetCurrent();
    }

    void next() {
        stopAndForgetCurrent();
    }

    int size() {
        return queue.size();
    }

    BaritoneTaskAgent.Task current() {
        return current;
    }

    void tick() {
        blackboard.advanceTick();
        publishState();

        if (pathManager.isPaused()) {
            blackboard.setBlockReason("Paused", blackboard.getTick());
            return;
        }

        // Recovery policy hook: optionally block automation while health is low.
        // If we were in the middle of a non-instant task, cancel and re-queue it.
        if (Config.get().automationPauseOnLowHealth.get() && blackboard.isLowHealth()) {
            blackboard.setBlockReason("Low Health", blackboard.getTick());
            if (current != null && !current.isInstant()) {
                pathManager.stop();
                queue.addFirst(current);
                blackboard.setQueuedTaskCount(queue.size());
                updateQueuePreview();

                current = null;
                sawPathing = false;
                idleTicksAfterPathing = 0;
                blackboard.onTaskEnded(blackboard.getTick());
            }
            return;
        }

        // Not blocked by health.
        blackboard.setBlockReason(null, blackboard.getTick());

        // Recovery policy hook: optionally block automation while inventory is full.
        // If we were in the middle of a non-instant task, cancel and re-queue it.
        if (Config.get().automationPauseOnInventoryFull.get() && blackboard.isInventoryFull()) {
            blackboard.setBlockReason("Inventory Full", blackboard.getTick());
            if (current != null && !current.isInstant()) {
                pathManager.stop();
                queue.addFirst(current);
                blackboard.setQueuedTaskCount(queue.size());
                updateQueuePreview();

                current = null;
                sawPathing = false;
                idleTicksAfterPathing = 0;
                blackboard.onTaskEnded(blackboard.getTick());
            }
            return;
        }

        // Not blocked.
        blackboard.setBlockReason(null, blackboard.getTick());

        // Minimal recovery policy hook: if we newly become stuck while a task is active,
        // trigger a single recover attempt. The Recover routine itself handles cooldown/backoff.
        boolean stuck = pathManager.isStuck();
        if (Config.get().automationAutoRecoverWhenStuck.get() && current != null && !current.isInstant() && stuck && !lastStuck) {
            pathManager.recover();
        }
        lastStuck = stuck;

        if (current == null) {
            if (!queue.isEmpty()) {
                BaritoneTaskAgent.Task next = queue.peek();
                if (next == null) return;
                if (pathManager.isPathing() && !next.isInstant()) return;

                current = queue.poll();
                blackboard.setQueuedTaskCount(queue.size());
                updateQueuePreview();

                sawPathing = false;
                idleTicksAfterPathing = 0;
                currentStartedTick = blackboard.getTick();

                blackboard.onTaskStarted(current.name(), current.detail(), blackboard.getTick());
                current.start(pathManager);

                if (current.isInstant()) {
                    current = null;
                    blackboard.onTaskEnded(blackboard.getTick());
                }
            }
            return;
        }

        boolean pathing = pathManager.isPathing();
        if (pathing) {
            sawPathing = true;
            idleTicksAfterPathing = 0;
            return;
        }

        // If a task never successfully starts pathing, treat it as unreachable.
        // This is intentionally conservative and avoids Baritone-internals coupling.
        int unreachableTimeout = Config.get().automationUnreachableStartTimeoutTicks.get();
        if (unreachableTimeout <= 0) unreachableTimeout = 60;
        if (!sawPathing && (blackboard.getTick() - currentStartedTick) >= unreachableTimeout) {
            pathManager.stop();
            blackboard.onFailure("No path found", blackboard.getTick());

            current = null;
            sawPathing = false;
            idleTicksAfterPathing = 0;
            blackboard.onTaskEnded(blackboard.getTick());
            return;
        }

        if (sawPathing) {
            idleTicksAfterPathing++;
            int idleEndTicks = Config.get().automationTaskEndIdleTicks.get();
            if (idleEndTicks <= 0) idleEndTicks = 5;
            if (idleTicksAfterPathing >= idleEndTicks) {
                current = null;
                sawPathing = false;
                idleTicksAfterPathing = 0;
                blackboard.onTaskEnded(blackboard.getTick());
            }
        }
    }

    private void publishState() {
        blackboard.setPaused(pathManager.isPaused());
        blackboard.setSafeMode(pathManager.isSafeMode());
        blackboard.setPathing(pathManager.isPathing());
        blackboard.setStuck(pathManager.isStuck());
        blackboard.setLowHealth(pathManager.isLowHealth());
        blackboard.setHazardSummary(pathManager.getHazardSummary());
        blackboard.setGoalSummary(pathManager.getGoalSummary());

        BaritonePathManager.InventorySummary inv = pathManager.getInventorySummary();
        blackboard.setInventorySlots(inv.usedSlots(), inv.totalSlots());
        blackboard.setInventoryFull(inv.totalSlots() > 0 && inv.usedSlots() >= inv.totalSlots());

        blackboard.setQueuedTaskCount(queue.size());
        // The preview is updated when the queue changes.

        // If something clears current outside the normal completion path, make sure
        // the public state doesn't get stuck.
        if (current == null && blackboard.getCurrentTaskName() != null) {
            blackboard.onTaskEnded(blackboard.getTick());
        }
    }

    private void updateQueuePreview() {
        if (queue.isEmpty()) {
            blackboard.setQueuedPreview(null);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Next: ");

        int limit = Config.get().automationQueuePreviewLimit.get();
        if (limit <= 0) {
            blackboard.setQueuedPreview("Next: —");
            return;
        }

        int i = 0;
        for (BaritoneTaskAgent.Task task : queue) {
            if (i >= limit) break;
            if (i > 0) sb.append(", ");
            sb.append(task.name());
            i++;
        }

        blackboard.setQueuedPreview(sb.toString());
    }

    private void stopAndForgetCurrent() {
        if (current != null) {
            pathManager.stop();
        }

        current = null;
        currentStartedTick = 0;
        sawPathing = false;
        idleTicksAfterPathing = 0;
        blackboard.onTaskEnded(blackboard.getTick());
    }
}
