package datura.areamusic.fabric.server;

import com.mojang.brigadier.CommandDispatcher;
import datura.areamusic.fabric.AreaMusicFabric.RegistrationGuard;
import datura.areamusic.fabric.network.FabricAreaMusicNetwork;
import datura.areamusic.playback.PlaybackState;
import datura.areamusic.server.AreaMusicServer;
import datura.areamusic.server.NetworkSender;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.nio.file.Path;

public final class FabricAreaMusicServer {
    private static final RegistrationGuard REGISTRATION = new RegistrationGuard(
            "AreaMusic Fabric server callbacks have not been registered"
    );

    private FabricAreaMusicServer() {
    }

    public static synchronized void register() {
        if (REGISTRATION.isRegistered()) {
            return;
        }

        CommandRegistrationCallback.EVENT.register(FabricAreaMusicServer::onRegisterCommands);
        ServerLifecycleEvents.SERVER_STARTED.register(FabricAreaMusicServer::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(FabricAreaMusicServer::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(FabricAreaMusicServer::onServerEndTick);
        ServerPlayConnectionEvents.JOIN.register(FabricAreaMusicServer::onPlayerLogin);
        ServerPlayConnectionEvents.DISCONNECT.register(FabricAreaMusicServer::onPlayerLogout);
        ServerPlayerEvents.AFTER_RESPAWN.register(FabricAreaMusicServer::onPlayerRespawn);
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
                FabricAreaMusicServer::onPlayerChangedDimension
        );
        REGISTRATION.register();
    }

    private static void onRegisterCommands(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext registryAccess,
            Commands.CommandSelection environment
    ) {
        AreaMusicServer.registerCommands(dispatcher);
    }

    private static void onServerStarted(MinecraftServer server) {
        FabricLoader loader = FabricLoader.getInstance();
        Path gameDirectory = loader.getGameDir().toAbsolutePath().normalize();
        Path configDirectory = loader.getConfigDir().toAbsolutePath().normalize();
        AreaMusicServer.onServerStarted(
                server,
                gameDirectory,
                configDirectory,
                FabricNetworkSender.INSTANCE
        );
    }

    private static void onServerStopping(MinecraftServer server) {
        AreaMusicServer.onServerStopping(server);
    }

    private static void onServerEndTick(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(AreaMusicServer::onPlayerEndTick);
    }

    private static void onPlayerLogin(
            ServerGamePacketListenerImpl handler,
            PacketSender responseSender,
            MinecraftServer server
    ) {
        AreaMusicServer.onPlayerLogin(handler.player);
    }

    private static void onPlayerLogout(
            ServerGamePacketListenerImpl handler,
            MinecraftServer server
    ) {
        AreaMusicServer.onPlayerLogout(handler.player);
    }

    private static void onPlayerRespawn(
            ServerPlayer oldPlayer,
            ServerPlayer newPlayer,
            boolean alive
    ) {
        AreaMusicServer.onPlayerRespawn(newPlayer);
    }

    private static void onPlayerChangedDimension(
            ServerPlayer player,
            ServerLevel origin,
            ServerLevel destination
    ) {
        AreaMusicServer.onPlayerChangedDimension(player);
    }

    private enum FabricNetworkSender implements NetworkSender {
        INSTANCE;

        @Override
        public void sendReload(ServerPlayer player, long revision) {
            FabricAreaMusicNetwork.sendReload(player, revision);
        }

        @Override
        public void sendPlayback(ServerPlayer player, long revision, PlaybackState state) {
            FabricAreaMusicNetwork.sendPlayback(player, revision, state);
        }
    }
}
