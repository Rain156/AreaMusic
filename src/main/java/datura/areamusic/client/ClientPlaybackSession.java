package datura.areamusic.client;

import datura.areamusic.client.audio.ClientAudioMixer;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;

import java.util.Objects;

public final class ClientPlaybackSession implements AutoCloseable {
    private final MixerFactory mixerFactory;
    private MusicLibrary musicLibrary;
    private PlaybackUpdate latestUpdate;
    private PlaybackUpdate compatibleUpdate;
    private ClientAudioMixer mixer;
    private long highestSeenRevision = -1L;
    private long pendingReloadRevision = -1L;

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
        applyCompatibleUpdate();
    }

    public boolean beginReload(long revision) {
        validateRevision(revision);
        if (revision < highestSeenRevision) {
            return false;
        }
        highestSeenRevision = revision;
        pendingReloadRevision = revision;
        return true;
    }

    public void apply(long revision, PlaybackState state) {
        validateRevision(revision);
        if (revision < highestSeenRevision) {
            return;
        }
        PlaybackState checkedState = Objects.requireNonNull(state, "state");
        PlaybackUpdate update = new PlaybackUpdate(revision, checkedState);
        latestUpdate = update;
        highestSeenRevision = revision;
        if (pendingReloadRevision < 0L) {
            compatibleUpdate = update;
            if (mixer != null) {
                mixer.apply(revision, checkedState);
            }
        }
    }

    public void finishReload(MusicLibrary library) {
        musicLibrary = Objects.requireNonNull(library, "library");
        long completedRevision = pendingReloadRevision;
        pendingReloadRevision = -1L;
        if (completedRevision >= 0L) {
            compatibleUpdate = latestUpdate != null
                    && latestUpdate.revision() >= completedRevision
                    ? latestUpdate
                    : null;
        }
        if (mixer != null) {
            PlaybackUpdate update = compatibleUpdate;
            if (update == null) {
                mixer.updateMusicLibrary(library);
            } else {
                mixer.updateMusicLibraryAndApply(
                        library, update.revision(), update.state()
                );
            }
        }
    }

    public void failReload() {
        long failedRevision = pendingReloadRevision;
        pendingReloadRevision = -1L;
        if (failedRevision >= 0L
                && latestUpdate != null
                && latestUpdate.revision() >= failedRevision) {
            compatibleUpdate = latestUpdate;
        }
        applyCompatibleUpdate();
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
        compatibleUpdate = null;
        highestSeenRevision = -1L;
        pendingReloadRevision = -1L;
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

    private void applyCompatibleUpdate() {
        PlaybackUpdate update = compatibleUpdate;
        if (mixer != null && update != null) {
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
