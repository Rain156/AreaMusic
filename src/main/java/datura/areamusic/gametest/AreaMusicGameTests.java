package datura.areamusic.gametest;

import datura.areamusic.AreaMusic;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AreaMusic.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AreaMusicGameTests {
    private AreaMusicGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 100)
    public static void serverSmokeTest(GameTestHelper helper) {
        helper.runAfterDelay(40L, helper::succeed);
    }
}
