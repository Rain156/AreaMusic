package datura.areamusic.client;

import datura.areamusic.AreaMusic;
import datura.areamusic.config.AreaMusicClientConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = AreaMusic.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class ForgeAreaMusicSoundOptions {
    private ForgeAreaMusicSoundOptions() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        AreaMusicClientConfig config = AreaMusicClientConfig.INSTANCE;
        AreaMusicSoundOptions.onScreenInit(
                event.getScreen(),
                event.getListenersList(),
                config::volume,
                config::setVolume
        );
    }
}
