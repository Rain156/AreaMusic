package datura.areamusic.fabric.client;

import datura.areamusic.client.AreaMusicSoundOptions;
import datura.areamusic.client.ClientAreaMusic;
import datura.areamusic.fabric.config.FabricClientConfig;
import datura.areamusic.fabric.network.FabricClientNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.nio.file.Path;

public final class FabricClientAreaMusic implements ClientModInitializer {
    private static ClientAreaMusic instance;
    private static FabricClientConfig config;

    @Override
    public void onInitializeClient() {
        initialize();
        ClientTickEvents.END_CLIENT_TICK.register(FabricClientAreaMusic::onClientEndTick);
        ClientPlayConnectionEvents.JOIN.register(FabricClientAreaMusic::onJoin);
        ClientPlayConnectionEvents.DISCONNECT.register(FabricClientAreaMusic::onDisconnect);
        ClientLifecycleEvents.CLIENT_STOPPING.register(FabricClientAreaMusic::onClientStopping);
        ScreenEvents.AFTER_INIT.register(FabricClientAreaMusic::onScreenInit);
    }

    private static synchronized void initialize() {
        if (instance != null) {
            return;
        }

        FabricLoader loader = FabricLoader.getInstance();
        Path gameDirectory = loader.getGameDir().toAbsolutePath().normalize();
        Path configDirectory = loader.getConfigDir().toAbsolutePath().normalize();
        FabricClientConfig loadedConfig = FabricClientConfig.load(configDirectory);
        ClientAreaMusic created = new ClientAreaMusic(gameDirectory, loadedConfig::volume);
        FabricClientNetworking.setClientHandler(created);
        try {
            FabricClientNetworking.registerClientReceivers();
        } catch (RuntimeException | Error failure) {
            FabricClientNetworking.clearClientHandler(created);
            created.shutdown();
            throw failure;
        }
        config = loadedConfig;
        instance = created;
    }

    private static void onClientEndTick(Minecraft client) {
        ClientAreaMusic current = instance;
        if (current != null) {
            current.tick();
        }
    }

    private static void onJoin(
            ClientPacketListener networkHandler,
            PacketSender responseSender,
            Minecraft client
    ) {
        ClientAreaMusic current = instance;
        if (current != null) {
            client.execute(current::connect);
        }
    }

    private static void onDisconnect(
            ClientPacketListener networkHandler,
            Minecraft client
    ) {
        ClientAreaMusic current = instance;
        if (current != null) {
            client.execute(current::disconnect);
        }
    }

    private static void onClientStopping(Minecraft client) {
        ClientAreaMusic current;
        synchronized (FabricClientAreaMusic.class) {
            current = instance;
            instance = null;
            config = null;
        }
        if (current != null) {
            current.shutdown();
            FabricClientNetworking.clearClientHandler(current);
        }
    }

    private static void onScreenInit(
            Minecraft client,
            Screen screen,
            int scaledWidth,
            int scaledHeight
    ) {
        FabricClientConfig currentConfig = config;
        if (currentConfig != null) {
            AreaMusicSoundOptions.onScreenInit(
                    screen,
                    screen.children(),
                    currentConfig::volume,
                    currentConfig::setVolume
            );
        }
    }
}
