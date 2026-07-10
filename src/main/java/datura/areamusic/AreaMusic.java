package datura.areamusic;

import datura.areamusic.config.AreaMusicClientConfig;
import datura.areamusic.network.AreaMusicNetwork;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(AreaMusic.MOD_ID)
public final class AreaMusic {
    public static final String MOD_ID = "areamusic";

    public AreaMusic() {
        ModLoadingContext.get().registerConfig(
                ModConfig.Type.CLIENT,
                AreaMusicClientConfig.INSTANCE.spec(),
                AreaMusicClientConfig.FILE_NAME
        );
        AreaMusicNetwork.register();
    }
}
