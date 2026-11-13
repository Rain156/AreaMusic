package datura.areamusic;

import datura.areamusic.config.AreaMusicClientConfig;
import datura.areamusic.network.AreaMusicNetwork;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(AreaMusic.MOD_ID)
public final class AreaMusic {
    public static final String MOD_ID = "areamusic";

    public AreaMusic(FMLJavaModLoadingContext context) {
        context.registerConfig(
                ModConfig.Type.CLIENT,
                AreaMusicClientConfig.INSTANCE.spec(),
                AreaMusicClientConfig.FILE_NAME
        );
        AreaMusicNetwork.register();
    }
}
