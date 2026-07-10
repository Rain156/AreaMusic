package datura.areamusic;

import datura.areamusic.network.AreaMusicNetwork;
import net.minecraftforge.fml.common.Mod;

@Mod(AreaMusic.MOD_ID)
public final class AreaMusic {
    public static final String MOD_ID = "areamusic";

    public AreaMusic() {
        AreaMusicNetwork.register();
    }
}
