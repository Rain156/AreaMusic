package datura.areamusic.fabric;

import datura.areamusic.fabric.network.FabricAreaMusicNetwork;
import datura.areamusic.fabric.server.FabricAreaMusicServer;
import net.fabricmc.api.ModInitializer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AreaMusicFabric implements ModInitializer {
    public static final String MOD_ID = "areamusic";

    @Override
    public void onInitialize() {
        FabricAreaMusicNetwork.register();
        FabricAreaMusicServer.register();
    }

    public static final class RegistrationGuard {
        private final AtomicBoolean registered = new AtomicBoolean();
        private final String missingRegistrationMessage;

        public RegistrationGuard(String missingRegistrationMessage) {
            this.missingRegistrationMessage = Objects.requireNonNull(
                    missingRegistrationMessage,
                    "missingRegistrationMessage"
            );
        }

        public boolean isRegistered() {
            return registered.get();
        }

        public void register() {
            registered.set(true);
        }

        public void requireRegistered() {
            if (!isRegistered()) {
                throw new IllegalStateException(missingRegistrationMessage);
            }
        }
    }
}
