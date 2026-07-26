package datura.areamusic.fabric.network;

import datura.areamusic.fabric.AreaMusicFabric;
import datura.areamusic.fabric.AreaMusicFabric.RegistrationGuard;
import datura.areamusic.network.ClientboundPlaybackState;
import datura.areamusic.network.ClientboundReloadMusic;
import datura.areamusic.playback.PlaybackState;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

public final class FabricAreaMusicNetwork {
    public static final ResourceLocation PLAYBACK_CHANNEL = new ResourceLocation(
            AreaMusicFabric.MOD_ID,
            "playback"
    );
    public static final ResourceLocation RELOAD_CHANNEL = new ResourceLocation(
            AreaMusicFabric.MOD_ID,
            "reload"
    );

    private static final RegistrationGuard REGISTRATION = new RegistrationGuard(
            "AreaMusic Fabric networking has not been registered"
    );

    private FabricAreaMusicNetwork() {
    }

    public static void register() {
        REGISTRATION.register();
    }

    public static void sendReload(ServerPlayer player, long revision) {
        requireRegistered();
        ServerPlayer checkedPlayer = Objects.requireNonNull(player, "player");
        FriendlyByteBuf buffer = PacketByteBufs.create();
        ClientboundReloadMusic.encode(new ClientboundReloadMusic(revision), buffer);
        ServerPlayNetworking.send(checkedPlayer, RELOAD_CHANNEL, buffer);
    }

    public static void sendPlayback(ServerPlayer player, long revision, PlaybackState state) {
        requireRegistered();
        ServerPlayer checkedPlayer = Objects.requireNonNull(player, "player");
        FriendlyByteBuf buffer = PacketByteBufs.create();
        ClientboundPlaybackState.encode(
                new ClientboundPlaybackState(revision, Objects.requireNonNull(state, "state")),
                buffer
        );
        ServerPlayNetworking.send(checkedPlayer, PLAYBACK_CHANNEL, buffer);
    }

    static void requireRegistered() {
        REGISTRATION.requireRegistered();
    }
}
