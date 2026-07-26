package datura.areamusic.client;

import datura.areamusic.AreaMusic;
import datura.areamusic.config.AreaMusicClientConfig;
import datura.areamusic.network.AreaMusicNetwork;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.nio.file.Path;

public final class NeoForgeClientAreaMusic {
    private static ClientAreaMusic instance;

    private NeoForgeClientAreaMusic() {
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

    @EventBusSubscriber(
            modid = AreaMusic.MOD_ID,
            value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD
    )
    public static final class ModEvents {
        private ModEvents() {
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(NeoForgeClientAreaMusic::initialize);
        }
    }

    @EventBusSubscriber(modid = AreaMusic.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        private GameEvents() {
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            ClientAreaMusic current = instance;
            if (current != null) {
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
            synchronized (NeoForgeClientAreaMusic.class) {
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
