package datura.areamusic.server;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerAreaTrackerTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.tryParse("minecraft:overworld");
    private static final AreaDefinition AREA = area(1.0f);

    @Test
    void sendsInitialStateFromFirstTrackAndSkipsIdenticalChecks() {
        PlayerAreaTracker tracker = new PlayerAreaTracker();

        Optional<PlaybackState> initial = tracker.update(OVERWORLD, new BlockPos(1, 65, 1), 1L, List.of(AREA));

        PlaybackState state = initial.orElseThrow();
        assertEquals("track.ogg", state.musicId());
        assertEquals(1.0f, state.volume());
        assertTrue(tracker.update(OVERWORLD, new BlockPos(1, 65, 1), 1L, List.of(AREA)).isEmpty());
    }

    @Test
    void movingInsideTheSameEffectiveAreaDoesNotSendAgain() {
        PlayerAreaTracker tracker = new PlayerAreaTracker();
        tracker.update(OVERWORLD, new BlockPos(1, 65, 1), 1L, List.of(AREA));

        assertTrue(tracker.update(OVERWORLD, new BlockPos(2, 65, 2), 1L, List.of(AREA)).isEmpty());
    }

    @Test
    void leavingAllAreasSendsStoppedState() {
        PlayerAreaTracker tracker = new PlayerAreaTracker();
        tracker.update(OVERWORLD, new BlockPos(1, 65, 1), 1L, List.of(AREA));

        PlaybackState stopped = tracker.update(OVERWORLD, new BlockPos(50, 65, 50), 1L, List.of(AREA)).orElseThrow();

        assertEquals(PlaybackState.stopped(), stopped);
    }

    @Test
    void revisionChangeReevaluatesPlaybackParameters() {
        PlayerAreaTracker tracker = new PlayerAreaTracker();
        BlockPos position = new BlockPos(1, 65, 1);
        tracker.update(OVERWORLD, position, 1L, List.of(AREA));

        PlaybackState changed = tracker.update(OVERWORLD, position, 2L, List.of(area(0.5f))).orElseThrow();

        assertEquals(0.5f, changed.volume());
    }

    private static AreaDefinition area(float volume) {
        return AreaDefinition.create(
                "area", OVERWORLD, new BlockPos(0, 60, 0), new BlockPos(10, 80, 10),
                List.of(
                        new AreaTrackDefinition("track.ogg", 0, volume, true, 2000, 2000),
                        new AreaTrackDefinition("second.ogg", 4, 0.25f, false, 300, 700)
                ),
                false,
                0
        );
    }
}
