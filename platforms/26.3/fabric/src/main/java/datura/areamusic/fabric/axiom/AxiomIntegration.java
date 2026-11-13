package datura.areamusic.fabric.axiom;

import com.mojang.logging.LogUtils;
import datura.areamusic.client.ClientAreaMusic;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/**
 * Adds an AreaMusic tool to Axiom when Axiom is installed. Nothing in this class refers to Axiom
 * or ImGui types directly, so it is safe to load without them; the tool class is only touched
 * after the mod is known to be present, and any linkage problem with a different Axiom version is
 * logged instead of crashing the game.
 */
public final class AxiomIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TOOL_CLASS = "datura.areamusic.fabric.axiom.AreaMusicAxiomTool";

    private AxiomIntegration() {
    }

    public static void registerIfPresent(ClientAreaMusic client) {
        if (!FabricLoader.getInstance().isModLoaded("axiom")) {
            return;
        }
        try {
            Class.forName(TOOL_CLASS)
                    .getMethod("register", ClientAreaMusic.class)
                    .invoke(null, client);
            LOGGER.info("Added the AreaMusic tool to Axiom");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            LOGGER.warn(
                    "Axiom is installed but the AreaMusic tool could not be added to it. "
                            + "The built-in editor still works.",
                    exception
            );
        }
    }
}
