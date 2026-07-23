package datura.areamusic.client.audio;

import datura.areamusic.area.AreaTrackDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AreaPlaybackTimeline {
    private static final int MAX_TRACKS = 16;

    private final List<TrackState> tracks;

    private AreaPlaybackTimeline(List<TrackState> tracks) {
        this.tracks = tracks;
    }

    public static AreaPlaybackTimeline fresh(List<AreaTrackDefinition> definitions, int sampleRate) {
        List<AreaTrackDefinition> checkedDefinitions = validateDefinitions(definitions, sampleRate);
        List<TrackState> tracks = new ArrayList<>(checkedDefinitions.size());
        for (AreaTrackDefinition definition : checkedDefinitions) {
            tracks.add(new TrackState(definition.delayFrames(sampleRate), 0, false, false, false));
        }
        return new AreaPlaybackTimeline(tracks);
    }

    public static AreaPlaybackTimeline restore(
            Snapshot snapshot,
            List<AreaTrackDefinition> definitions,
            int sampleRate
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<AreaTrackDefinition> checkedDefinitions = validateDefinitions(definitions, sampleRate);
        List<TrackSnapshot> snapshots = snapshot.tracks();
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("snapshot must contain at least one track");
        }
        if (snapshots.size() != checkedDefinitions.size()) {
            throw new IllegalArgumentException(
                    "snapshot track count " + snapshots.size()
                            + " does not match definition count " + checkedDefinitions.size()
            );
        }

        List<TrackState> tracks = new ArrayList<>(snapshots.size());
        for (int index = 0; index < snapshots.size(); index++) {
            TrackSnapshot track = snapshots.get(index);
            validateSnapshotTrack(
                    index,
                    track,
                    checkedDefinitions.get(index).delayFrames(sampleRate)
            );
            tracks.add(new TrackState(
                    track.remainingDelayFrames(),
                    track.positionInLoopFrames(),
                    track.started(),
                    track.completed(),
                    track.failed()
            ));
        }
        return new AreaPlaybackTimeline(tracks);
    }

    public List<Integer> dueTrackIndices() {
        List<Integer> due = new ArrayList<>();
        for (int index = 0; index < tracks.size(); index++) {
            TrackState track = tracks.get(index);
            if (isPending(track) && track.remainingDelayFrames == 0) {
                due.add(index);
            }
        }
        return List.copyOf(due);
    }

    public long framesUntilNextStart() {
        long nearest = Long.MAX_VALUE;
        for (TrackState track : tracks) {
            if (isPending(track)) {
                nearest = Math.min(nearest, track.remainingDelayFrames);
            }
        }
        return nearest;
    }

    public void advancePending(long frames) {
        if (frames < 0) {
            throw new IllegalArgumentException("frames must not be negative");
        }
        for (TrackState track : tracks) {
            if (!isPending(track)) {
                continue;
            }
            if (frames >= track.remainingDelayFrames) {
                track.remainingDelayFrames = 0;
            } else {
                track.remainingDelayFrames -= frames;
            }
        }
    }

    public void markStarted(int trackIndex) {
        TrackState track = trackAt(trackIndex);
        if (track.completed || track.failed) {
            throw new IllegalStateException("track " + trackIndex + " is already terminated");
        }
        if (track.started) {
            throw new IllegalStateException("track " + trackIndex + " has already started");
        }
        if (track.remainingDelayFrames != 0) {
            throw new IllegalStateException("track " + trackIndex + " is not due yet");
        }
        track.started = true;
    }

    public void recordFramesRead(int trackIndex, long frames) {
        if (frames < 0) {
            throw new IllegalArgumentException("frames must not be negative");
        }
        TrackState track = requireActive(trackIndex);
        long updatedPosition = Math.addExact(track.positionInLoopFrames, frames);
        track.positionInLoopFrames = updatedPosition;
    }

    public void markLoopRestarted(int trackIndex) {
        TrackState track = requireActive(trackIndex);
        track.positionInLoopFrames = 0;
    }

    public void markCompleted(int trackIndex) {
        TrackState track = requireActive(trackIndex);
        track.completed = true;
    }

    public void markFailed(int trackIndex) {
        TrackState track = trackAt(trackIndex);
        if (track.completed || track.failed) {
            throw new IllegalStateException("track " + trackIndex + " is already terminated");
        }
        track.failed = true;
    }

    public long remainingDelayFrames(int trackIndex) {
        return trackAt(trackIndex).remainingDelayFrames;
    }

    public long positionInLoopFrames(int trackIndex) {
        return trackAt(trackIndex).positionInLoopFrames;
    }

    public boolean started(int trackIndex) {
        return trackAt(trackIndex).started;
    }

    public boolean completed(int trackIndex) {
        return trackAt(trackIndex).completed;
    }

    public boolean failed(int trackIndex) {
        return trackAt(trackIndex).failed;
    }

    public Snapshot snapshot() {
        List<TrackSnapshot> snapshots = new ArrayList<>(tracks.size());
        for (TrackState track : tracks) {
            snapshots.add(new TrackSnapshot(
                    track.remainingDelayFrames,
                    track.positionInLoopFrames,
                    track.started,
                    track.completed,
                    track.failed
            ));
        }
        return new Snapshot(snapshots);
    }

    public record Snapshot(List<TrackSnapshot> tracks) {
        public Snapshot {
            tracks = List.copyOf(tracks);
        }
    }

    public record TrackSnapshot(
            long remainingDelayFrames,
            long positionInLoopFrames,
            boolean started,
            boolean completed,
            boolean failed
    ) {
    }

    private static List<AreaTrackDefinition> validateDefinitions(
            List<AreaTrackDefinition> definitions,
            int sampleRate
    ) {
        Objects.requireNonNull(definitions, "definitions");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        List<AreaTrackDefinition> copy = List.copyOf(definitions);
        if (copy.isEmpty() || copy.size() > MAX_TRACKS) {
            throw new IllegalArgumentException(
                    "definitions must contain between 1 and " + MAX_TRACKS + " tracks"
            );
        }
        return copy;
    }

    private static void validateSnapshotTrack(int index, TrackSnapshot track, long originalDelayFrames) {
        if (track.remainingDelayFrames() < 0) {
            throw invalidSnapshot(index, "remaining delay must not be negative");
        }
        if (track.remainingDelayFrames() > originalDelayFrames) {
            throw invalidSnapshot(index, "remaining delay exceeds the original delay");
        }
        if (track.positionInLoopFrames() < 0) {
            throw invalidSnapshot(index, "loop position must not be negative");
        }
        if (track.started() && track.remainingDelayFrames() != 0) {
            throw invalidSnapshot(index, "a started track must have no remaining delay");
        }
        if (!track.started() && track.positionInLoopFrames() != 0) {
            throw invalidSnapshot(index, "an unstarted track cannot have a loop position");
        }
        if (track.completed() && !track.started()) {
            throw invalidSnapshot(index, "a completed track must have started");
        }
        if (track.completed() && track.failed()) {
            throw invalidSnapshot(index, "a track cannot be both completed and failed");
        }
    }

    private static IllegalArgumentException invalidSnapshot(int index, String reason) {
        return new IllegalArgumentException("invalid snapshot for track " + index + ": " + reason);
    }

    private static boolean isPending(TrackState track) {
        return !track.started && !track.completed && !track.failed;
    }

    private TrackState requireActive(int trackIndex) {
        TrackState track = trackAt(trackIndex);
        if (!track.started) {
            throw new IllegalStateException("track " + trackIndex + " has not started");
        }
        if (track.completed || track.failed) {
            throw new IllegalStateException("track " + trackIndex + " is already terminated");
        }
        return track;
    }

    private TrackState trackAt(int trackIndex) {
        Objects.checkIndex(trackIndex, tracks.size());
        return tracks.get(trackIndex);
    }

    private static final class TrackState {
        private long remainingDelayFrames;
        private long positionInLoopFrames;
        private boolean started;
        private boolean completed;
        private boolean failed;

        private TrackState(
                long remainingDelayFrames,
                long positionInLoopFrames,
                boolean started,
                boolean completed,
                boolean failed
        ) {
            this.remainingDelayFrames = remainingDelayFrames;
            this.positionInLoopFrames = positionInLoopFrames;
            this.started = started;
            this.completed = completed;
            this.failed = failed;
        }
    }
}
