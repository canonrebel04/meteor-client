package meteordevelopment.meteorclient.systems.modules.misc.swarm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SwarmProtocolTest {
    @Test
    void encodePrefixesAndDecodes() {
        String msg = SwarmProtocol.goTo(1, 2, 3, true);
        assertNotNull(msg);
        assertTrue(msg.startsWith(SwarmProtocol.PREFIX));

        SwarmProtocol.Action a = SwarmProtocol.decode(msg);
        assertNotNull(a);
        assertEquals(SwarmProtocol.VERSION, a.v());
        assertEquals("goto", a.type());
        assertEquals(1, a.x());
        assertEquals(2, a.y());
        assertEquals(3, a.z());
        assertEquals(true, a.ignoreY());
    }

    @Test
    void decodeRejectsNullOrWrongPrefix() {
        assertNull(SwarmProtocol.decode(null));
        assertNull(SwarmProtocol.decode("swarm stop"));
        assertNull(SwarmProtocol.decode("swarm2"));
        assertNull(SwarmProtocol.decode("swarm2" + " {\"v\":1}"));
    }

    @Test
    void decodeRejectsEmptyJson() {
        assertNull(SwarmProtocol.decode(SwarmProtocol.PREFIX));
        assertNull(SwarmProtocol.decode(SwarmProtocol.PREFIX + "   "));
    }

    @Test
    void decodeRejectsInvalidJson() {
        assertNull(SwarmProtocol.decode(SwarmProtocol.PREFIX + "not-json"));
        assertNull(SwarmProtocol.decode(SwarmProtocol.PREFIX + "{"));
    }

    @Test
    void mineEncodesBlockIdsArray() {
        String msg = SwarmProtocol.mine("minecraft:diamond_ore", "minecraft:iron_ore");
        SwarmProtocol.Action a = SwarmProtocol.decode(msg);
        assertNotNull(a);
        assertEquals("mine", a.type());
        assertNotNull(a.blocks());
        assertEquals(2, a.blocks().length);
        assertEquals("minecraft:diamond_ore", a.blocks()[0]);
        assertEquals("minecraft:iron_ore", a.blocks()[1]);
    }
}
