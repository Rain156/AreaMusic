package datura.areamusic.playback;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.network.ClientboundPlaybackState;
import datura.areamusic.network.ClientboundReloadMusic;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackStateTest {
    private static final AreaTrackDefinition TRACK =
            new AreaTrackDefinition("track.ogg", 0, 1.0f, true, 2000, 2000);

    @Test
    void playingStateRoundTripsThroughTheNetworkCodec() {
        List<AreaTrackDefinition> tracks = List.of(
                new AreaTrackDefinition("village/day.mp3", 0, 0.75f, true, 1000, 2500),
                new AreaTrackDefinition("village/bell.ogg", 5, 0.5f, false, 0, 500)
        );
        PlaybackState state = PlaybackState.playing("square", tracks, true);
        ClientboundPlaybackState message = new ClientboundPlaybackState(7L, state);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundPlaybackState.STREAM_CODEC.encode(buffer, message);
        ClientboundPlaybackState decoded = ClientboundPlaybackState.STREAM_CODEC.decode(buffer);

        assertEquals(message, decoded);
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void stoppedStateRoundTripsWithoutPlaceholderTrackData() {
        PlaybackState stopped = PlaybackState.stopped();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundPlaybackState.STREAM_CODEC.encode(buffer, new ClientboundPlaybackState(8L, stopped));
        ClientboundPlaybackState decoded = ClientboundPlaybackState.STREAM_CODEC.decode(buffer);

        assertFalse(decoded.state().playing());
        assertTrue(decoded.state().tracks().isEmpty());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void rejectsPlayingStateWithoutTracks() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackState.playing("area", List.of(), false));
    }

    @Test
    void rejectsPlayingStateAboveTrackLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackState.playing(
                        "area", Collections.nCopies(AreaDefinition.MAX_TRACKS + 1, TRACK), false
                ));
    }

    @Test
    void rejectsBlankPlayingAreaId() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackState.playing("", List.of(TRACK), false));
    }

    @Test
    void defensivelyCopiesPlayingTracks() {
        List<AreaTrackDefinition> source = new ArrayList<>(List.of(TRACK));

        PlaybackState state = PlaybackState.playing("area", source, false);
        source.clear();

        assertEquals(List.of(TRACK), state.tracks());
        assertThrows(UnsupportedOperationException.class, () -> state.tracks().clear());
    }

    @Test
    void normalizesStoppedStateFields() {
        PlaybackState stopped = new PlaybackState(false, "ignored", List.of(TRACK), true);

        assertEquals(PlaybackState.stopped(), stopped);
    }

    @Test
    void rejectsInvalidNetworkTrackCountsBeforeReadingTracks() {
        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> ClientboundPlaybackState.STREAM_CODEC.decode(playingPayload(0))
        );
        IllegalArgumentException excessive = assertThrows(
                IllegalArgumentException.class,
                () -> ClientboundPlaybackState.STREAM_CODEC.decode(
                        playingPayload(AreaDefinition.MAX_TRACKS + 1)
                )
        );

        assertEquals("Invalid network track count: 0", empty.getMessage());
        assertEquals("Invalid network track count: 17", excessive.getMessage());
    }

    @Test
    void rejectsOverlongNetworkMusicId() {
        FriendlyByteBuf buffer = playingPayload(1);
        writeNetworkTrack(buffer, "x".repeat(1025), 0, 1.0f, 0, 0);

        assertThrows(DecoderException.class, () -> ClientboundPlaybackState.STREAM_CODEC.decode(buffer));
    }

    @Test
    void rejectsInvalidNetworkTrackValues() {
        assertInvalidNetworkTrack(-1, 1.0f, 0, 0);
        assertInvalidNetworkTrack(0, Float.NaN, 0, 0);
        assertInvalidNetworkTrack(0, -0.1f, 0, 0);
        assertInvalidNetworkTrack(0, 1.1f, 0, 0);
        assertInvalidNetworkTrack(0, 1.0f, -1, 0);
        assertInvalidNetworkTrack(0, 1.0f, 0, AreaTrackDefinition.MAX_FADE_MS + 1);
    }

    @Test
    void reloadNoticeRoundTripsItsRevision() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundReloadMusic.STREAM_CODEC.encode(buffer, new ClientboundReloadMusic(42L));

        assertEquals(
                new ClientboundReloadMusic(42L),
                ClientboundReloadMusic.STREAM_CODEC.decode(buffer)
        );
        assertEquals(0, buffer.readableBytes());
    }

    private static FriendlyByteBuf playingPayload(int trackCount) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarLong(1L);
        buffer.writeBoolean(true);
        buffer.writeUtf("area");
        buffer.writeBoolean(false);
        buffer.writeVarInt(trackCount);
        return buffer;
    }

    private static void assertInvalidNetworkTrack(
            int delaySeconds,
            float volume,
            int fadeInMs,
            int fadeOutMs
    ) {
        FriendlyByteBuf buffer = playingPayload(1);
        writeNetworkTrack(buffer, "track.ogg", delaySeconds, volume, fadeInMs, fadeOutMs);

        assertThrows(
                IllegalArgumentException.class,
                () -> ClientboundPlaybackState.STREAM_CODEC.decode(buffer)
        );
    }

    private static void writeNetworkTrack(
            FriendlyByteBuf buffer,
            String musicId,
            int delaySeconds,
            float volume,
            int fadeInMs,
            int fadeOutMs
    ) {
        buffer.writeUtf(musicId);
        buffer.writeVarInt(delaySeconds);
        buffer.writeFloat(volume);
        buffer.writeBoolean(true);
        buffer.writeVarInt(fadeInMs);
        buffer.writeVarInt(fadeOutMs);
    }
}
