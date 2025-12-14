package meteordevelopment.meteorclient.pathing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BaritonePathManagerSmartGotoTest {
    @Test
    void ignoreYHintForcesIgnoreY() {
        var d = BaritonePathManager.SmartGotoDecision.decide(true, 100, 200, false, false);
        assertTrue(d.ignoreY);
    }

    @Test
    void smallYDeltaImpliesIgnoreY() {
        var d = BaritonePathManager.SmartGotoDecision.decide(false, 64, 67, false, false);
        assertTrue(d.ignoreY);
    }

    @Test
    void largeYDeltaDoesNotIgnoreY() {
        var d = BaritonePathManager.SmartGotoDecision.decide(false, 64, 80, false, false);
        assertFalse(d.ignoreY);
    }

    @Test
    void safeModeEnablesApproachRadius() {
        var d = BaritonePathManager.SmartGotoDecision.decide(false, 64, 80, true, false);
        assertEquals(2, d.approachRadius);
    }

    @Test
    void hazardousTargetEnablesApproachRadius() {
        var d = BaritonePathManager.SmartGotoDecision.decide(false, 64, 80, false, true);
        assertEquals(2, d.approachRadius);
    }

    @Test
    void normalConditionsHaveNoApproachRadius() {
        var d = BaritonePathManager.SmartGotoDecision.decide(false, 64, 80, false, false);
        assertEquals(0, d.approachRadius);
    }
}
