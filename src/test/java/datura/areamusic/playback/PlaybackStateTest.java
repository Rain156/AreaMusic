package datura.areamusic.playback;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.network.ClientboundPlaybackState;
import datura.areamusic.network.ClientboundReloadMusic;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackStateTest {
    @Test
    void playingStateRoundTripsThroughTheNetworkCodec() {
        List<AreaTrackDefinition> tracks = List.of(
                new AreaTrackDefinition("village/day.mp3", 0, 0.75f, true, 1000, 2500),
                new AreaTrackDefinition("village/bell.ogg", 5, 0.5f, false, 0, 500)
        );
        PlaybackState state = PlaybackState.playing("square", tracks, true);
        ClientboundPlaybackState message = new ClientboundPlaybackState(7L, state);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundPlaybackState.encode(message, buffer);
        ClientboundPlaybackState decoded = ClientboundPlaybackState.decode(buffer);

        assertEquals(message, decoded);
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void parallelPayloadWritesModeBeforeTrackCount() {
        PlaybackState state = PlaybackState.playing("square", List.of(
                new AreaTrackDefinition("track.ogg", 0, 1.0f, true, 2000, 2000)
        ), false);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundPlaybackState.encode(new ClientboundPlaybackState(7L, state), buffer);

        assertEquals(7L, buffer.readVarLong());
        assertTrue(buffer.readBoolean());
        assertEquals("square", buffer.readUtf(64));
        assertFalse(buffer.readBoolean());
        assertEquals(PlaybackMode.PARALLEL.networkId(), buffer.readVarInt());
        assertEquals(1, buffer.readVarInt());
    }

    @Test
    void playlistStateRoundTripsThroughTheNetworkCodecInOrder() {
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "playlist", List.of("first.ogg", "second.ogg", "first.ogg"),
                0.75f, 250, 900, true
        );
        ClientboundPlaybackState message = new ClientboundPlaybackState(8L, state);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundPlaybackState.encode(message, buffer);
        ClientboundPlaybackState decoded = ClientboundPlaybackState.decode(buffer);

        assertEquals(message, decoded);
        assertEquals(List.of("first.ogg", "second.ogg", "first.ogg"), decoded.state().musicIds());
        assertEquals(PlaybackMode.PLAYLIST_LOOP, decoded.state().mode().orElseThrow());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void playlistPayloadWritesOrderedDefinitionAfterMode() {
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "playlist", List.of("first.ogg", "second.ogg"),
                0.75f, 250, 900, true
        );
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundPlaybackState.encode(new ClientboundPlaybackState(8L, state), buffer);

        assertEquals(8L, buffer.readVarLong());
        assertTrue(buffer.readBoolean());
        assertEquals("playlist", buffer.readUtf(64));
        assertTrue(buffer.readBoolean());
        assertEquals(PlaybackMode.PLAYLIST_LOOP.networkId(), buffer.readVarInt());
        assertEquals(2, buffer.readVarInt());
        assertEquals("first.ogg", buffer.readUtf(1024));
        assertEquals("second.ogg", buffer.readUtf(1024));
        assertEquals(0.75f, buffer.readFloat());
        assertEquals(250, buffer.readVarInt());
        assertEquals(900, buffer.readVarInt());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void stoppedStateRoundTripsWithoutPlaceholderTrackData() {
        PlaybackState stopped = PlaybackState.stopped();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundPlaybackState.encode(new ClientboundPlaybackState(8L, stopped), buffer);
        ClientboundPlaybackState decoded = ClientboundPlaybackState.decode(buffer);

        assertFalse(decoded.state().playing());
        assertTrue(decoded.state().tracks().isEmpty());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void rejectsInvalidNetworkTrackCountsBeforeReadingTracks() {
        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> ClientboundPlaybackState.decode(playingPayload(PlaybackMode.PARALLEL, 0))
        );
        IllegalArgumentException excessive = assertThrows(
                IllegalArgumentException.class,
                () -> ClientboundPlaybackState.decode(playingPayload(
                        PlaybackMode.PARALLEL, AreaDefinition.MAX_TRACKS + 1
                ))
        );

        assertEquals("Invalid network track count: 0", empty.getMessage());
        assertEquals("Invalid network track count: 17", excessive.getMessage());
    }

    @Test
    void rejectsOverlongNetworkMusicId() {
        FriendlyByteBuf buffer = playingPayload(PlaybackMode.PARALLEL, 1);
        writeNetworkTrack(buffer, "x".repeat(1025), 0, 1.0f, 0, 0);

        assertThrows(DecoderException.class, () -> ClientboundPlaybackState.decode(buffer));
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
    void rejectsUnknownNetworkPlaybackModeBeforeReadingDefinition() {
        FriendlyByteBuf buffer = playingPayload(2, 1);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ClientboundPlaybackState.decode(buffer)
        );

        assertEquals("Unknown playback mode network ID: 2", error.getMessage());
    }

    @Test
    void rejectsZeroNetworkPlaylistCountBeforeReadingEntries() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ClientboundPlaybackState.decode(playingPayload(PlaybackMode.PLAYLIST_LOOP, 0))
        );

        assertEquals("Invalid network playlist count: 0", error.getMessage());
    }

    @Test
    void rejects257NetworkPlaylistCountBeforeReadingEntries() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ClientboundPlaybackState.decode(playingPayload(PlaybackMode.PLAYLIST_LOOP, 257))
        );

        assertEquals("Invalid network playlist count: 257", error.getMessage());
    }

    @Test
    void rejectsOverlongNetworkPlaylistMusicId() {
        FriendlyByteBuf buffer = playingPayload(PlaybackMode.PLAYLIST_LOOP, 1);
        buffer.writeUtf("x".repeat(PlaybackDefinition.MAX_MUSIC_ID_LENGTH + 1));
        writePlaylistSettings(buffer, 1.0f, 2000, 2000);

        assertThrows(DecoderException.class, () -> ClientboundPlaybackState.decode(buffer));
    }

    @Test
    void playlistMusicIdAt1024CharacterLimitRoundTrips() {
        String musicId = "x".repeat(PlaybackDefinition.MAX_MUSIC_ID_LENGTH);
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "playlist", List.of(musicId), 1.0f, 2000, 2000, false
        );
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundPlaybackState.encode(new ClientboundPlaybackState(10L, state), buffer);

        assertEquals(state, ClientboundPlaybackState.decode(buffer).state());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void overlongPlaylistMusicIdCannotEnterNetworkState() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PlaybackState.playingPlaylistLoop(
                        "playlist", List.of("x".repeat(PlaybackDefinition.MAX_MUSIC_ID_LENGTH + 1)),
                        1.0f, 2000, 2000, false
                ));

        assertTrue(error.getMessage().contains("playlist[0]"));
    }

    @Test
    void rejectsInvalidNetworkPlaylistVolume() {
        for (float volume : List.of(Float.NaN, -0.1f, 1.1f)) {
            FriendlyByteBuf buffer = playlistPayload(volume, 2000, 2000);

            assertThrows(IllegalArgumentException.class,
                    () -> ClientboundPlaybackState.decode(buffer));
        }
    }

    @Test
    void rejectsInvalidNetworkPlaylistFadeIn() {
        for (int fadeInMs : List.of(-1, AreaTrackDefinition.MAX_FADE_MS + 1)) {
            FriendlyByteBuf buffer = playlistPayload(1.0f, fadeInMs, 2000);

            assertThrows(IllegalArgumentException.class,
                    () -> ClientboundPlaybackState.decode(buffer));
        }
    }

    @Test
    void rejectsInvalidNetworkPlaylistFadeOut() {
        for (int fadeOutMs : List.of(-1, AreaTrackDefinition.MAX_FADE_MS + 1)) {
            FriendlyByteBuf buffer = playlistPayload(1.0f, 2000, fadeOutMs);

            assertThrows(IllegalArgumentException.class,
                    () -> ClientboundPlaybackState.decode(buffer));
        }
    }

    @Test
    void accepts256NetworkPlaylistEntries() {
        List<String> playlist = IntStream.range(0, PlaylistLoopPlayback.MAX_ENTRIES)
                .mapToObj(index -> "track-" + index + ".ogg")
                .toList();
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "playlist", playlist, 1.0f, 2000, 2000, false
        );
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundPlaybackState.encode(new ClientboundPlaybackState(9L, state), buffer);

        assertEquals(state, ClientboundPlaybackState.decode(buffer).state());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void reloadNoticeRoundTripsItsRevision() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ClientboundReloadMusic.encode(new ClientboundReloadMusic(42L), buffer);

        assertEquals(new ClientboundReloadMusic(42L), ClientboundReloadMusic.decode(buffer));
        assertEquals(0, buffer.readableBytes());
    }

    private static FriendlyByteBuf playingPayload(PlaybackMode mode, int definitionCount) {
        return playingPayload(mode.networkId(), definitionCount);
    }

    private static FriendlyByteBuf playingPayload(int modeId, int definitionCount) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarLong(1L);
        buffer.writeBoolean(true);
        buffer.writeUtf("area");
        buffer.writeBoolean(false);
        buffer.writeVarInt(modeId);
        buffer.writeVarInt(definitionCount);
        return buffer;
    }

    private static void assertInvalidNetworkTrack(int delaySeconds, float volume, int fadeInMs, int fadeOutMs) {
        FriendlyByteBuf buffer = playingPayload(PlaybackMode.PARALLEL, 1);
        writeNetworkTrack(buffer, "track.ogg", delaySeconds, volume, fadeInMs, fadeOutMs);

        assertThrows(IllegalArgumentException.class, () -> ClientboundPlaybackState.decode(buffer));
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

    private static FriendlyByteBuf playlistPayload(float volume, int fadeInMs, int fadeOutMs) {
        FriendlyByteBuf buffer = playingPayload(PlaybackMode.PLAYLIST_LOOP, 1);
        buffer.writeUtf("track.ogg");
        writePlaylistSettings(buffer, volume, fadeInMs, fadeOutMs);
        return buffer;
    }

    private static void writePlaylistSettings(
            FriendlyByteBuf buffer,
            float volume,
            int fadeInMs,
            int fadeOutMs
    ) {
        buffer.writeFloat(volume);
        buffer.writeVarInt(fadeInMs);
        buffer.writeVarInt(fadeOutMs);
    }
}
