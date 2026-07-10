package datura.areamusic.client;

import datura.areamusic.client.audio.ClientAudioMixer;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;

import java.util.Objects;

public final class ClientPlaybackSession implements AutoCloseable {
    private final MixerFactory mixerFactory;
    private MusicLibrary musicLibrary;
    private PlaybackState desiredState = PlaybackState.stopped();
    private ClientAudioMixer mixer;
    private long latestRevision = -1L;
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
        mixer.apply(desiredState);
    }

    public boolean beginReload(long revision) {
        validateRevision(revision);
        if (revision < latestRevision) {
            return false;
        }
        latestRevision = revision;
        reloadPending = true;
        return true;
    }

    public void apply(long revision, PlaybackState state) {
        validateRevision(revision);
        if (revision < latestRevision) {
            return;
        }
        latestRevision = revision;
        desiredState = Objects.requireNonNull(state, "state");
        if (mixer != null && !reloadPending) {
            mixer.apply(state);
        }
    }

    public void finishReload(MusicLibrary library) {
        musicLibrary = Objects.requireNonNull(library, "library");
        reloadPending = false;
        if (mixer != null) {
            mixer.updateMusicLibrary(library);
            mixer.apply(desiredState);
        }
    }

    public void failReload() {
        reloadPending = false;
        if (mixer != null) {
            mixer.apply(desiredState);
        }
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
        desiredState = PlaybackState.stopped();
        latestRevision = -1L;
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

    private static void validateRevision(long revision) {
        if (revision < 0L) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
    }
}
