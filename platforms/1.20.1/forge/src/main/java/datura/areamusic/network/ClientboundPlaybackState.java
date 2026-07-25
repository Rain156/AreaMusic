package datura.areamusic.network;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.playback.ParallelPlayback;
import datura.areamusic.playback.PlaybackDefinition;
import datura.areamusic.playback.PlaybackMode;
import datura.areamusic.playback.PlaybackState;
import datura.areamusic.playback.PlaylistLoopPlayback;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public record ClientboundPlaybackState(long revision, PlaybackState state) {
    private static final int MAX_AREA_ID_LENGTH = 64;

    public ClientboundPlaybackState {
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
        Objects.requireNonNull(state, "state");
    }

    public static void encode(ClientboundPlaybackState message, FriendlyByteBuf buffer) {
        buffer.writeVarLong(message.revision);
        PlaybackState state = message.state;
        buffer.writeBoolean(state.playing());
        if (!state.playing()) {
            return;
        }
        buffer.writeUtf(state.areaId(), MAX_AREA_ID_LENGTH);
        buffer.writeBoolean(state.resumeOnReenter());
        PlaybackDefinition definition = state.definition().orElseThrow(
                () -> new IllegalStateException("Playing state has no playback definition")
        );
        buffer.writeVarInt(definition.mode().networkId());
        if (definition instanceof ParallelPlayback parallel) {
            buffer.writeVarInt(parallel.tracks().size());
            for (AreaTrackDefinition track : parallel.tracks()) {
                buffer.writeUtf(track.musicId(), PlaybackDefinition.MAX_MUSIC_ID_LENGTH);
                buffer.writeVarInt(track.delaySeconds());
                buffer.writeFloat(track.volume());
                buffer.writeBoolean(track.loop());
                buffer.writeVarInt(track.fadeInMs());
                buffer.writeVarInt(track.fadeOutMs());
            }
        } else if (definition instanceof PlaylistLoopPlayback playlistLoop) {
            buffer.writeVarInt(playlistLoop.playlist().size());
            for (String musicId : playlistLoop.playlist()) {
                buffer.writeUtf(musicId, PlaybackDefinition.MAX_MUSIC_ID_LENGTH);
            }
            buffer.writeFloat(playlistLoop.volume());
            buffer.writeVarInt(playlistLoop.fadeInMs());
            buffer.writeVarInt(playlistLoop.fadeOutMs());
        } else {
            throw new AssertionError("Unknown playback definition: " + definition.getClass());
        }
    }

    public static ClientboundPlaybackState decode(FriendlyByteBuf buffer) {
        long revision = buffer.readVarLong();
        if (!buffer.readBoolean()) {
            return new ClientboundPlaybackState(revision, PlaybackState.stopped());
        }
        String areaId = buffer.readUtf(MAX_AREA_ID_LENGTH);
        boolean resumeOnReenter = buffer.readBoolean();
        PlaybackMode mode = PlaybackMode.fromNetworkId(buffer.readVarInt());
        PlaybackDefinition definition = mode == PlaybackMode.PARALLEL
                ? decodeParallel(buffer)
                : decodePlaylistLoop(buffer);
        PlaybackState state = PlaybackState.playing(areaId, definition, resumeOnReenter);
        return new ClientboundPlaybackState(revision, state);
    }

    private static ParallelPlayback decodeParallel(FriendlyByteBuf buffer) {
        int trackCount = buffer.readVarInt();
        if (trackCount < 1 || trackCount > AreaDefinition.MAX_TRACKS) {
            throw new IllegalArgumentException("Invalid network track count: " + trackCount);
        }
        List<AreaTrackDefinition> tracks = new ArrayList<>(trackCount);
        for (int index = 0; index < trackCount; index++) {
            tracks.add(new AreaTrackDefinition(
                    buffer.readUtf(PlaybackDefinition.MAX_MUSIC_ID_LENGTH),
                    buffer.readVarInt(),
                    buffer.readFloat(),
                    buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readVarInt()
            ));
        }
        return new ParallelPlayback(tracks);
    }

    private static PlaylistLoopPlayback decodePlaylistLoop(FriendlyByteBuf buffer) {
        int playlistCount = buffer.readVarInt();
        if (playlistCount < 1 || playlistCount > PlaylistLoopPlayback.MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid network playlist count: " + playlistCount);
        }
        List<String> playlist = new ArrayList<>(playlistCount);
        for (int index = 0; index < playlistCount; index++) {
            playlist.add(buffer.readUtf(PlaybackDefinition.MAX_MUSIC_ID_LENGTH));
        }
        float volume = buffer.readFloat();
        int fadeInMs = buffer.readVarInt();
        int fadeOutMs = buffer.readVarInt();
        return new PlaylistLoopPlayback(playlist, volume, fadeInMs, fadeOutMs);
    }

    public static void handle(ClientboundPlaybackState message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> AreaMusicNetwork.handleClientPlayback(message));
        context.setPacketHandled(true);
    }
}
