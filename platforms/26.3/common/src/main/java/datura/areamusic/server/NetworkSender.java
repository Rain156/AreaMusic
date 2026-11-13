package datura.areamusic.server;

import datura.areamusic.playback.PlaybackState;
import net.minecraft.server.level.ServerPlayer;

public interface NetworkSender {
    void sendReload(ServerPlayer player, long revision);

    void sendPlayback(ServerPlayer player, long revision, PlaybackState state);

    /** Sends the encoded bytes of one clientbound {@code SyncMessage}. */
    void sendSync(ServerPlayer player, byte[] data);
}
