package meteordevelopment.meteorclient.pathing;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AutomationGoalTest {
    @Test
    void safeSmartGotoCompilesDeterministically() {
        AutomationGoal goal = new SafeSmartGotoGoal(new BlockPos(1, 64, -2), false, true);

        List<BaritoneTaskAgent.Task> tasks = goal.compile();
        assertEquals(2, tasks.size());
        assertEquals("Safe Mode: ON", tasks.get(0).name());
        assertEquals("Smart Goto", tasks.get(1).name());

        assertNotNull(goal.explain());
        assertFalse(goal.explain().isBlank());
    }

    @Test
    void safeSmartGotoOmitsSafeModeWhenDisabled() {
        AutomationGoal goal = new SafeSmartGotoGoal(new BlockPos(0, 0, 0), true, false);

        List<BaritoneTaskAgent.Task> tasks = goal.compile();
        assertEquals(1, tasks.size());
        assertEquals("Smart Goto", tasks.get(0).name());
    }

    @Test
    void recoverThenSafeSmartGotoCompilesInOrder() {
        AutomationGoal goal = new RecoverThenSafeSmartGotoGoal(new BlockPos(10, 65, 10), false, true);

        List<BaritoneTaskAgent.Task> tasks = goal.compile();
        assertEquals(3, tasks.size());
        assertEquals("Recover", tasks.get(0).name());
        assertEquals("Safe Mode: ON", tasks.get(1).name());
        assertEquals("Smart Goto", tasks.get(2).name());
    }

    @Test
    void safeMineCompilesDeterministically() {
        AutomationGoal goal = new SafeMineGoal(new net.minecraft.block.Block[0], true);

        List<BaritoneTaskAgent.Task> tasks = goal.compile();
        assertEquals(2, tasks.size());
        assertEquals("Safe Mode: ON", tasks.get(0).name());
        assertEquals("Mine", tasks.get(1).name());
    }

    @Test
    void recoverThenSafeMineCompilesInOrder() {
        AutomationGoal goal = new RecoverThenSafeMineGoal(new net.minecraft.block.Block[0], true);

        List<BaritoneTaskAgent.Task> tasks = goal.compile();
        assertEquals(3, tasks.size());
        assertEquals("Recover", tasks.get(0).name());
        assertEquals("Safe Mode: ON", tasks.get(1).name());
        assertEquals("Mine", tasks.get(2).name());
    }
}
