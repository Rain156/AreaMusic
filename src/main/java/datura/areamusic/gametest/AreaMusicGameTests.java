package datura.areamusic.gametest;

import datura.areamusic.AreaMusic;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.music.MusicDirectory;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
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
                ResourceLocation.tryParse("minecraft:overworld"),
                BlockPos.ZERO,
                BlockPos.ZERO,
                tracks,
                true,
                0
        );
        PlaybackState state = PlaybackState.fromArea(area);
        if (!state.tracks().equals(tracks)
                || !state.resumeOnReenter()
                || !"areamusic".equals(MusicDirectory.DIRECTORY_NAME)) {
            helper.fail("AreaMusic multitrack server model did not round-trip");
            return;
        }
        helper.succeed();
    }
}
