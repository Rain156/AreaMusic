package datura.areamusic;

import datura.areamusic.config.AreaMusicClientConfig;
import datura.areamusic.network.AreaMusicNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(AreaMusic.MOD_ID)
public final class AreaMusic {
    public static final String MOD_ID = "areamusic";

    public AreaMusic(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(
                ModConfig.Type.CLIENT,
                AreaMusicClientConfig.INSTANCE.spec(),
                AreaMusicClientConfig.FILE_NAME
        );
        modEventBus.addListener(AreaMusicNetwork::register);
    }
}
