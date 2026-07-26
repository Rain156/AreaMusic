package datura.areamusic.network;

import datura.areamusic.playback.PlaybackState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AreaMusicNetworkTest {
    @Test
    void typedPayloadsUseStableDistinctIds() {
        assertEquals("areamusic:playback", AreaMusicNetwork.PlaybackPayload.TYPE.id().toString());
        assertEquals("areamusic:reload", AreaMusicNetwork.ReloadPayload.TYPE.id().toString());
        assertFalse(AreaMusicNetwork.PlaybackPayload.TYPE.equals(AreaMusicNetwork.ReloadPayload.TYPE));
    }

    @Test
    void replacementHandlerReceivesClientboundMessagesAndStaleClearCannotRemoveIt() {
        List<String> calls = new ArrayList<>();
        ClientPacketHandler oldHandler = new ClientPacketHandler() {
            @Override
            public void onPlayback(long revision, PlaybackState state) {
            }

            @Override
            public void onReload(long revision) {
            }
        };
        ClientPacketHandler replacement = new ClientPacketHandler() {
            @Override
            public void onPlayback(long revision, PlaybackState state) {
                calls.add("playback:" + revision);
            }

            @Override
            public void onReload(long revision) {
                calls.add("reload:" + revision);
            }
        };

        AreaMusicNetwork.setClientHandler(oldHandler);
        AreaMusicNetwork.setClientHandler(replacement);
        try {
            assertFalse(AreaMusicNetwork.clearClientHandler(oldHandler));
            AreaMusicNetwork.handleClientReload(new ClientboundReloadMusic(7L));
            AreaMusicNetwork.handleClientPlayback(
                    new ClientboundPlaybackState(8L, PlaybackState.stopped())
            );
        } finally {
            AreaMusicNetwork.clearClientHandler(replacement);
        }

        assertEquals(List.of("reload:7", "playback:8"), calls);
    }

    @Test
    void dispatchFailsClearlyUntilTheClientLifecycleInstallsAHandler() {
        assertThrows(
                IllegalStateException.class,
                () -> AreaMusicNetwork.handleClientReload(new ClientboundReloadMusic(1L))
        );
    }
}
