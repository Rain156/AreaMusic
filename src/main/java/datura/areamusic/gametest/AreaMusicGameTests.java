package datura.areamusic.gametest;

import datura.areamusic.AreaMusic;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.music.MusicDirectory;
import datura.areamusic.playback.PlaybackState;
import datura.areamusic.server.AreaMusicServer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder(AreaMusic.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AreaMusicGameTests {
    private static final int EXPECTED_TRACK_COUNT = 7;
    private static final int EXPECTED_AREA_COUNT = 2;

    private AreaMusicGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 4000)
    public static void serverSmokeTest(GameTestHelper helper) {
        helper.onEachTick(() -> {
            AreaMusicServer.ReloadStatus status = AreaMusicServer.reloadStatus();
            switch (evaluateReload(status)) {
                case WAIT -> {
                }
                case SUCCEED -> helper.succeed();
                case FAIL -> helper.fail(failureMessage(status));
            }
        });
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void multitrackModelSmokeTest(GameTestHelper helper) {
        List<AreaTrackDefinition> tracks = List.of(
                new AreaTrackDefinition("ambient.ogg", 0, 1.0f, true, 0, 0),
                new AreaTrackDefinition("voice.mp3", 5, 0.8f, false, 0, 1000)
        );
        AreaDefinition area = AreaDefinition.create(
                "smoke",
                ResourceLocation.tryParse("minecraft:overworld"),
                BlockPos.ZERO,
                BlockPos.ZERO,
                tracks,
                true,
                0
        );
        PlaybackState state = PlaybackState.fromArea(area);
        if (!state.playing()) {
            helper.fail("AreaMusic playback state was not playing");
            return;
        }
        if (!"smoke".equals(state.areaId())) {
            helper.fail("AreaMusic playback state did not retain the area ID");
            return;
        }
        if (!state.tracks().equals(tracks)) {
            helper.fail("AreaMusic playback state did not retain the tracks");
            return;
        }
        if (!state.resumeOnReenter()) {
            helper.fail("AreaMusic playback state did not retain resume-on-reenter");
            return;
        }
        if (!"areamusic".equals(MusicDirectory.DIRECTORY_NAME)) {
            helper.fail("AreaMusic music directory name was not canonical lowercase");
            return;
        }
        helper.succeed();
    }

    static ReloadDecision evaluateReload(AreaMusicServer.ReloadStatus status) {
        return switch (status.phase()) {
            case NOT_READY, LOADING -> ReloadDecision.WAIT;
            case FAILED -> ReloadDecision.FAIL;
            case SUCCEEDED -> status.trackCount() == EXPECTED_TRACK_COUNT
                    && status.areaCount() == EXPECTED_AREA_COUNT
                    ? ReloadDecision.SUCCEED
                    : ReloadDecision.FAIL;
        };
    }

    private static String failureMessage(AreaMusicServer.ReloadStatus status) {
        if (status.phase() == AreaMusicServer.ReloadPhase.FAILED) {
            return "AreaMusic reload failed: " + status.failureMessage();
        }
        return "Expected " + EXPECTED_TRACK_COUNT + " AreaMusic tracks and " + EXPECTED_AREA_COUNT
                + " areas, but loaded " + status.trackCount() + " tracks and " + status.areaCount() + " areas";
    }

    enum ReloadDecision {
        WAIT,
        SUCCEED,
        FAIL
    }
}
