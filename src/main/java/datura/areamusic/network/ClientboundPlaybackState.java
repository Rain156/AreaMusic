package datura.areamusic.network;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public record ClientboundPlaybackState(long revision, PlaybackState state) {
    private static final int MAX_AREA_ID_LENGTH = 64;
    private static final int MAX_MUSIC_ID_LENGTH = 1024;

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
        buffer.writeVarInt(state.tracks().size());
        for (AreaTrackDefinition track : state.tracks()) {
            buffer.writeUtf(track.musicId(), MAX_MUSIC_ID_LENGTH);
            buffer.writeVarInt(track.delaySeconds());
            buffer.writeFloat(track.volume());
            buffer.writeBoolean(track.loop());
            buffer.writeVarInt(track.fadeInMs());
            buffer.writeVarInt(track.fadeOutMs());
        }
    }

    public static ClientboundPlaybackState decode(FriendlyByteBuf buffer) {
        long revision = buffer.readVarLong();
        if (!buffer.readBoolean()) {
            return new ClientboundPlaybackState(revision, PlaybackState.stopped());
        }
        String areaId = buffer.readUtf(MAX_AREA_ID_LENGTH);
        boolean resumeOnReenter = buffer.readBoolean();
        int trackCount = buffer.readVarInt();
        if (trackCount < 1 || trackCount > AreaDefinition.MAX_TRACKS) {
            throw new IllegalArgumentException("Invalid network track count: " + trackCount);
        }
        List<AreaTrackDefinition> tracks = new ArrayList<>(trackCount);
        for (int index = 0; index < trackCount; index++) {
            tracks.add(new AreaTrackDefinition(
                    buffer.readUtf(MAX_MUSIC_ID_LENGTH),
                    buffer.readVarInt(),
                    buffer.readFloat(),
                    buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readVarInt()
            ));
        }
        PlaybackState state = PlaybackState.playing(areaId, tracks, resumeOnReenter);
        return new ClientboundPlaybackState(revision, state);
    }

    public static void handle(ClientboundPlaybackState message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> AreaMusicNetwork.handleClientPlayback(message));
        context.setPacketHandled(true);
    }
}
