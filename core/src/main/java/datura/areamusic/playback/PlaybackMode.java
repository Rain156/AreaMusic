package datura.areamusic.playback;

import java.util.Arrays;
import java.util.Objects;

public enum PlaybackMode {
    PARALLEL("parallel", 0),
    PLAYLIST_LOOP("playlist_loop", 1);

    private final String jsonId;
    private final int networkId;

    PlaybackMode(String jsonId, int networkId) {
        this.jsonId = jsonId;
        this.networkId = networkId;
    }

    public String jsonId() {
        return jsonId;
    }

    public int networkId() {
        return networkId;
    }

    public static PlaybackMode fromJsonId(String jsonId) {
        Objects.requireNonNull(jsonId, "jsonId");
        return Arrays.stream(values())
                .filter(mode -> mode.jsonId.equals(jsonId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown playback mode JSON ID: " + jsonId));
    }

    public static PlaybackMode fromNetworkId(int networkId) {
        return Arrays.stream(values())
                .filter(mode -> mode.networkId == networkId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown playback mode network ID: " + networkId));
    }
}
