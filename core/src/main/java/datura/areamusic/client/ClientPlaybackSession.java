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
    private PlaybackState preview;

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

    /**
     * Plays {@code state} in place of whatever the server asks for, until cleared with
     * {@code null}. What the server says in the meantime is remembered and takes over again
     * afterwards.
     */
    public void setPreview(PlaybackState state) {
        if (Objects.equals(preview, state)) {
            return;
        }
        preview = state;
        if (mixer == null || pendingReloadRevision >= 0L) {
            return;
        }
        PlaybackUpdate update = compatibleUpdate;
        mixer.apply(
                update == null ? Math.max(0L, highestSeenRevision) : update.revision(),
                audible(update == null ? PlaybackState.stopped() : update.state()));
    }

    public boolean previewing() {
        return preview != null;
    }

    private PlaybackState audible(PlaybackState requested) {
        return preview != null ? preview : requested;
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
                mixer.apply(revision, audible(checkedState));
            }
        }
    }

    /** Applies a playback state together with a newer library, as one step. */
    public void apply(long revision, PlaybackState state, MusicLibrary library) {
        validateRevision(revision);
        if (revision < highestSeenRevision) {
            return;
        }
        musicLibrary = Objects.requireNonNull(library, "library");
        PlaybackUpdate update = new PlaybackUpdate(revision, Objects.requireNonNull(state, "state"));
        latestUpdate = update;
        highestSeenRevision = revision;
        if (pendingReloadRevision < 0L) {
            compatibleUpdate = update;
            if (mixer != null) {
                mixer.updateMusicLibraryAndApply(library, revision, audible(state));
            }
        }
    }

    /**
     * Switches to a library whose files changed outside a server reload, such as when a download
     * finishes, and restarts the current state so tracks that were missing begin to play.
     */
    public void updateLibrary(MusicLibrary library) {
        musicLibrary = Objects.requireNonNull(library, "library");
        if (mixer == null || pendingReloadRevision >= 0L) {
            return;
        }
        applyWithLibrary(library);
    }

    private void applyWithLibrary(MusicLibrary library) {
        PlaybackUpdate update = compatibleUpdate;
        if (update != null) {
            mixer.updateMusicLibraryAndApply(library, update.revision(), audible(update.state()));
        } else if (preview != null) {
            mixer.updateMusicLibraryAndApply(library, Math.max(0L, highestSeenRevision), preview);
        } else {
            mixer.updateMusicLibrary(library);
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
            applyWithLibrary(library);
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
        preview = null;
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
            mixer.apply(update.revision(), audible(update.state()));
        } else if (mixer != null && preview != null) {
            mixer.apply(Math.max(0L, highestSeenRevision), preview);
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
