package datura.areamusic.playback;

import datura.areamusic.network.ClientboundPlaybackState;
import datura.areamusic.network.ClientboundReloadMusic;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaybackStateTest {
    @Test
    void playingStateRoundTripsThroughTheNetworkCodec() {
        PlaybackState state = PlaybackState.playing("square", "village/day.mp3", 0.75f, true, 1000, 2500);
        ClientboundPlaybackState message = new ClientboundPlaybackState(7L, state);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundPlaybackState.encode(message, buffer);
        ClientboundPlaybackState decoded = ClientboundPlaybackState.decode(buffer);

        assertEquals(message, decoded);
    }

    @Test
    void stoppedStateRoundTripsWithoutPlaceholderTrackData() {
        PlaybackState stopped = PlaybackState.stopped();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundPlaybackState.encode(new ClientboundPlaybackState(8L, stopped), buffer);
        ClientboundPlaybackState decoded = ClientboundPlaybackState.decode(buffer);

        assertFalse(decoded.state().playing());
        assertEquals("", decoded.state().musicId());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void rejectsInvalidPlayingValues() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackState.playing("", "track.ogg", 1.0f, true, 2000, 2000));
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackState.playing("area", "", 1.0f, true, 2000, 2000));
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackState.playing("area", "track.ogg", Float.NaN, true, 2000, 2000));
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackState.playing("area", "track.ogg", 1.0f, true, -1, 2000));
    }

    @Test
    void reloadNoticeRoundTripsItsRevision() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundReloadMusic.encode(new ClientboundReloadMusic(42L), buffer);

        assertEquals(new ClientboundReloadMusic(42L), ClientboundReloadMusic.decode(buffer));
        assertEquals(0, buffer.readableBytes());
    }
}
