package datura.areamusic.gametest;

import datura.areamusic.AreaMusic;
import datura.areamusic.server.AreaMusicServer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
