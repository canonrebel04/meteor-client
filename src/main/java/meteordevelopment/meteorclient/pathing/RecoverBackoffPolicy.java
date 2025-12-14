/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

/**
 * Small, deterministic policy for recovery cooldown/backoff.
 *
 * The goal is to prevent repeated "recover" spam loops while still allowing quick retries.
 */
final class RecoverBackoffPolicy {
    private static final int RESET_AFTER_TICKS = 20 * 20; // 20s

    private static final int BASE_COOLDOWN_TICKS = 40;
    private static final int MAX_BACKOFF_LEVEL = 4;
    private static final int MAX_COOLDOWN_TICKS = 20 * 30; // 30s

    private RecoverBackoffPolicy() {
    }

    static int ticksSinceLastAttempt(int currentTick, int lastAttemptTick) {
        if (lastAttemptTick <= 0) return Integer.MAX_VALUE;
        return Math.max(0, currentTick - lastAttemptTick);
    }

    static int nextBackoffLevel(int currentBackoffLevel, int ticksSinceLastAttempt) {
        if (ticksSinceLastAttempt >= RESET_AFTER_TICKS) return 0;
        return Math.min(MAX_BACKOFF_LEVEL, currentBackoffLevel + 1);
    }

    static int cooldownTicksForBackoffLevel(int backoffLevel) {
        int level = Math.max(0, Math.min(MAX_BACKOFF_LEVEL, backoffLevel));

        // BASE * 2^level
        long cooldown = (long) BASE_COOLDOWN_TICKS << level;
        if (cooldown > MAX_COOLDOWN_TICKS) return MAX_COOLDOWN_TICKS;
        return (int) cooldown;
    }
}
