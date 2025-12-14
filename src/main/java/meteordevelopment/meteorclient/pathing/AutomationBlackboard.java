/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

/**
 * Shared, tick-updated state for automation.
 *
 * This is intentionally lightweight and updated from the client tick thread.
 */
public final class AutomationBlackboard {
    private int tick;

    private boolean paused;
    private boolean pathing;
    private boolean stuck;
    private boolean safeMode;

    private boolean lowHealth;

    private String hazardSummary;

    private String goalSummary;

    private int inventoryUsedSlots;
    private int inventoryTotalSlots;
    private boolean inventoryFull;

    private String currentTaskName;
    private String currentTaskDetail;
    private int currentTaskAgeTicks;

    private int queuedTaskCount;

    private String queuedPreview;

    private String lastTaskName;
    private String lastTaskDetail;
    private int lastTaskStartedTick;
    private int lastTaskEndedTick;

    private String lastFailureReason;
    private int lastFailureTick;

    private String blockReason;
    private int blockSinceTick;

    AutomationBlackboard() {
    }

    void advanceTick() {
        tick++;

        if (currentTaskName != null) {
            currentTaskAgeTicks++;
        } else {
            currentTaskAgeTicks = 0;
        }
    }

    void setPaused(boolean paused) {
        this.paused = paused;
    }

    void setPathing(boolean pathing) {
        this.pathing = pathing;
    }

    void setStuck(boolean stuck) {
        this.stuck = stuck;
    }

    void setSafeMode(boolean safeMode) {
        this.safeMode = safeMode;
    }

    void setLowHealth(boolean lowHealth) {
        this.lowHealth = lowHealth;
    }

    void setHazardSummary(String hazardSummary) {
        this.hazardSummary = (hazardSummary == null || hazardSummary.isBlank()) ? null : hazardSummary;
    }

    void setGoalSummary(String goalSummary) {
        this.goalSummary = (goalSummary == null || goalSummary.isBlank()) ? null : goalSummary;
    }

    void setInventorySlots(int usedSlots, int totalSlots) {
        this.inventoryUsedSlots = Math.max(0, usedSlots);
        this.inventoryTotalSlots = Math.max(0, totalSlots);
    }

    void setInventoryFull(boolean inventoryFull) {
        this.inventoryFull = inventoryFull;
    }

    void setQueuedTaskCount(int queuedTaskCount) {
        this.queuedTaskCount = queuedTaskCount;
    }

    void setQueuedPreview(String queuedPreview) {
        this.queuedPreview = (queuedPreview == null || queuedPreview.isBlank()) ? null : queuedPreview;
    }

    void onTaskStarted(String taskName, String taskDetail, int tick) {
        this.currentTaskName = taskName;
        this.currentTaskDetail = taskDetail;
        this.currentTaskAgeTicks = 0;

        this.lastTaskName = taskName;
        this.lastTaskDetail = taskDetail;
        this.lastTaskStartedTick = tick;
    }

    void onTaskEnded(int tick) {
        this.currentTaskName = null;
        this.currentTaskDetail = null;
        this.currentTaskAgeTicks = 0;
        this.lastTaskEndedTick = tick;
    }

    void onFailure(String reason, int tick) {
        this.lastFailureReason = reason;
        this.lastFailureTick = tick;
    }

    void setBlockReason(String reason, int tick) {
        if (reason == null || reason.isBlank()) {
            blockReason = null;
            blockSinceTick = 0;
            return;
        }

        if (reason.equals(blockReason)) return;
        blockReason = reason;
        blockSinceTick = tick;
    }

    public int getTick() {
        return tick;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isPathing() {
        return pathing;
    }

    public boolean isStuck() {
        return stuck;
    }

    public boolean isSafeMode() {
        return safeMode;
    }

    public boolean isLowHealth() {
        return lowHealth;
    }

    public String getHazardSummary() {
        return hazardSummary;
    }

    public String getGoalSummary() {
        return goalSummary;
    }

    public int getInventoryUsedSlots() {
        return inventoryUsedSlots;
    }

    public int getInventoryTotalSlots() {
        return inventoryTotalSlots;
    }

    public boolean isInventoryFull() {
        return inventoryFull;
    }

    public String getCurrentTaskName() {
        return currentTaskName;
    }

    public String getCurrentTaskDetail() {
        return currentTaskDetail;
    }

    public int getCurrentTaskAgeTicks() {
        return currentTaskAgeTicks;
    }

    public int getQueuedTaskCount() {
        return queuedTaskCount;
    }

    public String getQueuedPreview() {
        return queuedPreview;
    }

    public String getLastTaskName() {
        return lastTaskName;
    }

    public String getLastTaskDetail() {
        return lastTaskDetail;
    }

    public int getLastTaskStartedTick() {
        return lastTaskStartedTick;
    }

    public int getLastTaskEndedTick() {
        return lastTaskEndedTick;
    }

    public String getLastFailureReason() {
        return lastFailureReason;
    }

    public int getLastFailureTick() {
        return lastFailureTick;
    }

    public String getBlockReason() {
        return blockReason;
    }

    public int getBlockSinceTick() {
        return blockSinceTick;
    }
}
