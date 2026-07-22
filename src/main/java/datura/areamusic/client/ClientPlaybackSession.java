package datura.areamusic.client;

import datura.areamusic.client.audio.ClientAudioMixer;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;

import java.util.Objects;

public final class ClientPlaybackSession implements AutoCloseable {
    private final MixerFactory mixerFactory;
    private MusicLibrary musicLibrary;
    private PlaybackUpdate latestUpdate;
    private ClientAudioMixer mixer;
    private long highestSeenRevision = -1L;
    private boolean reloadPending;

    public ClientPlaybackSession(MusicLibrary initialLibrary, MixerFactory mixerFactory) {
        musicLibrary = Objects.requireNonNull(initialLibrary, "initialLibrary");
        this.mixerFactory = Objects.requireNonNull(mixerFactory, "mixerFactory");
    }

    public void connect() {
        if (mixer != null) {
            return;
        }
        mixer = Objects.requireNonNull(mixerFactory.create(musicLibrary), "mixer");
        mixer.start();
        applyLatestUpdate();
    }

    public boolean beginReload(long revision) {
        validateRevision(revision);
        if (revision < highestSeenRevision) {
            return false;
        }
        highestSeenRevision = revision;
        reloadPending = true;
        return true;
    }

    public void apply(long revision, PlaybackState state) {
        validateRevision(revision);
        if (revision < highestSeenRevision) {
            return;
        }
        PlaybackState checkedState = Objects.requireNonNull(state, "state");
        latestUpdate = new PlaybackUpdate(revision, checkedState);
        highestSeenRevision = revision;
        if (mixer != null && !reloadPending) {
            mixer.apply(revision, checkedState);
        }
    }

    public void finishReload(MusicLibrary library) {
        musicLibrary = Objects.requireNonNull(library, "library");
        reloadPending = false;
        if (mixer != null) {
            mixer.updateMusicLibrary(library);
            applyLatestUpdate();
        }
    }

    public void failReload() {
        reloadPending = false;
        applyLatestUpdate();
    }

    public void setMasterGain(float gain) {
        if (mixer != null) {
            mixer.setMasterGain(gain);
        }
    }

    public void setPaused(boolean paused) {
        if (mixer != null) {
            mixer.setPaused(paused);
        }
    }

    public void disconnect() {
        latestUpdate = null;
        highestSeenRevision = -1L;
        reloadPending = false;
        if (mixer != null) {
            mixer.close();
            mixer = null;
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    @FunctionalInterface
    public interface MixerFactory {
        ClientAudioMixer create(MusicLibrary musicLibrary);
    }

    private void applyLatestUpdate() {
        PlaybackUpdate update = latestUpdate;
        if (mixer != null
                && update != null
                && (!reloadPending || update.revision() < highestSeenRevision)) {
            mixer.apply(update.revision(), update.state());
        }
    }

    private static void validateRevision(long revision) {
        if (revision < 0L) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
    }

    private record PlaybackUpdate(long revision, PlaybackState state) {
    }
}
