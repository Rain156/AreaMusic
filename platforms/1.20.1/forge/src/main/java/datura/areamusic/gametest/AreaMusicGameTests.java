package datura.areamusic.gametest;

import datura.areamusic.AreaMusic;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.music.MusicDirectory;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder(AreaMusic.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AreaMusicGameTests {
    private AreaMusicGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void multitrackServerSmokeTest(GameTestHelper helper) {
        List<AreaTrackDefinition> tracks = List.of(
                new AreaTrackDefinition("ambient.ogg", 0, 1.0f, true, 0, 0),
                new AreaTrackDefinition("voice.mp3", 5, 0.8f, false, 0, 1000)
        );
        AreaDefinition area = AreaDefinition.create(
                "smoke",
                "minecraft:overworld",
                new AreaPosition(0, 0, 0),
                new AreaPosition(0, 0, 0),
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
}
