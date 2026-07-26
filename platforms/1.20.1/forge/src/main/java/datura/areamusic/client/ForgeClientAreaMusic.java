package datura.areamusic.client;

import datura.areamusic.AreaMusic;
import datura.areamusic.config.AreaMusicClientConfig;
import datura.areamusic.network.AreaMusicNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class ForgeClientAreaMusic {
    private static ClientAreaMusic instance;

    private ForgeClientAreaMusic() {
    }

    private static synchronized void initialize() {
        if (instance != null) {
            return;
        }
        Path gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        ClientAreaMusic created = new ClientAreaMusic(
                gameDirectory,
                AreaMusicClientConfig.INSTANCE::volume
        );
        instance = created;
        AreaMusicNetwork.setClientHandler(created);
    }

    @Mod.EventBusSubscriber(
            modid = AreaMusic.MOD_ID,
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD
    )
    public static final class ModEvents {
        private ModEvents() {
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(ForgeClientAreaMusic::initialize);
        }
    }

    @Mod.EventBusSubscriber(
            modid = AreaMusic.MOD_ID,
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.FORGE
    )
    public static final class ForgeEvents {
        private ForgeEvents() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            ClientAreaMusic current = instance;
            if (event.phase == TickEvent.Phase.END && current != null) {
                current.tick();
            }
        }

        @SubscribeEvent
        public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
            ClientAreaMusic current = instance;
            if (current != null) {
                current.connect();
            }
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientAreaMusic current = instance;
            if (current != null) {
                current.disconnect();
            }
        }

        @SubscribeEvent
        public static void onGameShuttingDown(GameShuttingDownEvent event) {
            ClientAreaMusic current;
            synchronized (ForgeClientAreaMusic.class) {
                current = instance;
                instance = null;
            }
            if (current != null) {
                current.shutdown();
                AreaMusicNetwork.clearClientHandler(current);
            }
        }
    }
}
