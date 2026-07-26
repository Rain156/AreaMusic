package datura.areamusic.server;

import datura.areamusic.AreaMusic;
import datura.areamusic.network.AreaMusicNetwork;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod.EventBusSubscriber(modid = AreaMusic.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeAreaMusicServer {
    private ForgeAreaMusicServer() {
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
                ForgeNetworkSender.INSTANCE
        );
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        AreaMusicServer.onServerStopping(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player) {
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

    private enum ForgeNetworkSender implements NetworkSender {
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
