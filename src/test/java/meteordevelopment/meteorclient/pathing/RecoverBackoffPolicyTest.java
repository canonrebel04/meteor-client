package meteordevelopment.meteorclient.pathing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RecoverBackoffPolicyTest {
    @Test
    void firstAttemptHasNoPreviousTick() {
        assertEquals(Integer.MAX_VALUE, RecoverBackoffPolicy.ticksSinceLastAttempt(100, 0));
        assertEquals(Integer.MAX_VALUE, RecoverBackoffPolicy.ticksSinceLastAttempt(100, -5));
    }

    @Test
    void ticksSinceLastAttemptIsNonNegative() {
        assertEquals(0, RecoverBackoffPolicy.ticksSinceLastAttempt(10, 999));
    }

    @Test
    void backoffResetsAfterWindow() {
        int level = 3;
        int next = RecoverBackoffPolicy.nextBackoffLevel(level, 20 * 20);
        assertEquals(0, next);
    }

    @Test
    void backoffIncrementsWithinWindow() {
        assertEquals(1, RecoverBackoffPolicy.nextBackoffLevel(0, 1));
        assertEquals(2, RecoverBackoffPolicy.nextBackoffLevel(1, 10));
    }

    @Test
    void cooldownIsBaseThenDoubles() {
        assertEquals(40, RecoverBackoffPolicy.cooldownTicksForBackoffLevel(0));
        assertEquals(80, RecoverBackoffPolicy.cooldownTicksForBackoffLevel(1));
        assertEquals(160, RecoverBackoffPolicy.cooldownTicksForBackoffLevel(2));
    }

    @Test
    void cooldownClampsToMax() {
        int c = RecoverBackoffPolicy.cooldownTicksForBackoffLevel(999);
        assertTrue(c <= 20 * 30);
        assertEquals(20 * 30, c);
    }
}
