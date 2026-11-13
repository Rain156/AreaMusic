package datura.areamusic.network;

import datura.areamusic.playback.PlaybackState;

/** Receives decoded AreaMusic packets on the Minecraft client thread. */
public interface ClientPacketHandler {
    void onPlayback(long revision, PlaybackState state);

    void onReload(long revision);

    /** Receives the encoded bytes of one clientbound {@code SyncMessage}. */
    default void onSync(byte[] data) {
    }
}
