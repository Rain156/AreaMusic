package datura.areamusic.server;

import datura.areamusic.AreaMusic;
import datura.areamusic.network.AreaMusicNetwork;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = AreaMusic.MOD_ID)
public final class NeoForgeAreaMusicServer {
    private NeoForgeAreaMusicServer() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        AreaMusicServer.registerCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        AreaMusicServer.onServerStarted(
                event.getServer(),
                FMLPaths.GAMEDIR.get().toAbsolutePath().normalize(),
                FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize(),
                NeoForgeNetworkSender.INSTANCE
        );
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        AreaMusicServer.onServerStopping(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AreaMusicServer.onPlayerEndTick(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AreaMusicServer.onPlayerLogin(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AreaMusicServer.onPlayerLogout(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AreaMusicServer.onPlayerRespawn(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AreaMusicServer.onPlayerChangedDimension(player);
        }
    }

    private enum NeoForgeNetworkSender implements NetworkSender {
        INSTANCE;

        @Override
        public void sendReload(ServerPlayer player, long revision) {
            AreaMusicNetwork.sendReload(player, revision);
        }

        @Override
        public void sendPlayback(ServerPlayer player, long revision, PlaybackState state) {
            AreaMusicNetwork.sendPlayback(player, revision, state);
        }
    }
}
