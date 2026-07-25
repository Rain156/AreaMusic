package datura.areamusic.network;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AreaMusicNetworkTest {
    @Test
    void protocolVersionIsThreeForPlaybackModeWireLayout() throws Exception {
        Field protocolVersion = AreaMusicNetwork.class.getDeclaredField("PROTOCOL_VERSION");
        protocolVersion.setAccessible(true);

        assertEquals("3", protocolVersion.get(null));
    }
}
