package datura.areamusic.network;

import datura.areamusic.playback.PlaybackState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaMusicNetworkTest {
    @Test
    void protocolVersionIsThreeForPlaybackModeWireLayout() throws Exception {
        Field protocolVersion = AreaMusicNetwork.class.getDeclaredField("PROTOCOL_VERSION");
        protocolVersion.setAccessible(true);

        assertEquals("3", protocolVersion.get(null));
    }

    @Test
    void forgeTransportTargetsTheCommonClientPacketHandlerContract() throws Exception {
        Class<?> handlerType = Class.forName("datura.areamusic.network.ClientPacketHandler");
        Class<?> serviceType = Class.forName("datura.areamusic.client.ClientAreaMusic");
        assertTrue(handlerType.isInterface());
        assertTrue(handlerType.isAssignableFrom(serviceType));
        Method setHandler = AreaMusicNetwork.class.getMethod("setClientHandler", handlerType);
        List<String> calls = new ArrayList<>();
        ClientPacketHandler handler = (ClientPacketHandler) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{handlerType},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(this, arguments);
                    }
                    calls.add(method.getName() + ":" + arguments[0]);
                    return null;
                }
        );

        try {
            setHandler.invoke(null, handler);
            AreaMusicNetwork.handleClientReload(new ClientboundReloadMusic(7L));
            AreaMusicNetwork.handleClientPlayback(
                    new ClientboundPlaybackState(8L, PlaybackState.stopped())
            );
        } finally {
            AreaMusicNetwork.clearClientHandler(handler);
        }

        assertEquals(List.of("onReload:7", "onPlayback:8"), calls);
    }

    @Test
    void staleClearCannotRemoveReplacementClientHandler() {
        List<String> calls = new ArrayList<>();
        ClientPacketHandler oldHandler = recordingHandler(new ArrayList<>());
        ClientPacketHandler replacementHandler = recordingHandler(calls);

        try {
            AreaMusicNetwork.setClientHandler(oldHandler);
            AreaMusicNetwork.setClientHandler(replacementHandler);

            AreaMusicNetwork.clearClientHandler(oldHandler);
            AreaMusicNetwork.handleClientReload(new ClientboundReloadMusic(9L));
        } finally {
            AreaMusicNetwork.clearClientHandler(replacementHandler);
        }

        assertEquals(List.of("onReload:9"), calls);
    }

    private ClientPacketHandler recordingHandler(List<String> calls) {
        return new ClientPacketHandler() {
            @Override
            public void onPlayback(long revision, PlaybackState state) {
                calls.add("onPlayback:" + revision);
            }

            @Override
            public void onReload(long revision) {
                calls.add("onReload:" + revision);
            }
        };
    }
}
