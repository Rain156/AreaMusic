package datura.areamusic.client;

import datura.areamusic.AreaMusic;
import datura.areamusic.config.AreaMusicClientConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = AreaMusic.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeAreaMusicSoundOptions {
    private NeoForgeAreaMusicSoundOptions() {
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
