package datura.areamusic.client;

import com.mojang.logging.LogUtils;
import datura.areamusic.AreaMusic;
import datura.areamusic.config.AreaMusicClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

@EventBusSubscriber(modid = AreaMusic.MOD_ID, value = Dist.CLIENT)
public final class AreaMusicSoundOptions {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String VOLUME_TRANSLATION_KEY = "options.areamusic.volume";

    private AreaMusicSoundOptions() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof SoundOptionsScreen)) {
            return;
        }

        Options options = Minecraft.getInstance().options;
        OptionInstance<Double> masterVolume = options.getSoundSourceOptionInstance(SoundSource.MASTER);
        OptionInstance<Double> voiceVolume = options.getSoundSourceOptionInstance(SoundSource.VOICE);
        List<OptionsList> optionLists = event.getListenersList().stream()
                .filter(OptionsList.class::isInstance)
                .map(OptionsList.class::cast)
                .toList();
        OptionsList list = findUnique(
                optionLists,
                candidate -> candidate.findOption(masterVolume) != null
                        && candidate.findOption(voiceVolume) != null
        );
        if (list == null) {
            LOGGER.warn("Could not identify one unique sound options list; skipping the AreaMusic volume slider");
            return;
        }

        AreaMusicClientConfig config = AreaMusicClientConfig.INSTANCE;
        OptionInstance<Double> areaMusicVolume = createVolumeOption(
                config::volume,
                config::setVolume
        );
        insertVolumeOption(list, options, areaMusicVolume);
    }

    static <T> T findUnique(List<T> candidates, Predicate<? super T> predicate) {
        T match = null;
        for (T candidate : candidates) {
            if (!predicate.test(candidate)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = candidate;
        }
        return match;
    }

    static OptionInstance<Double> createVolumeOption(
            DoubleSupplier initialVolume,
            DoubleConsumer updateVolume
    ) {
        double initial = initialVolume.getAsDouble();
        if (!Double.isFinite(initial)) {
            initial = 1.0;
        }
        initial = Math.max(0.0, Math.min(1.0, initial));
        return new OptionInstance<>(
                VOLUME_TRANSLATION_KEY,
                OptionInstance.noTooltip(),
                AreaMusicSoundOptions::volumeLabel,
                OptionInstance.UnitDouble.INSTANCE,
                initial,
                updateVolume::accept
        );
    }

    static Component volumeLabel(Component caption, Double volume) {
        if (volume == 0.0) {
            return Options.genericValueLabel(caption, CommonComponents.OPTION_OFF);
        }
        return Component.translatable("options.percent_value", caption, (int) (volume * 100.0));
    }

    private static void insertVolumeOption(
            OptionsList list,
            Options options,
            OptionInstance<Double> areaMusicVolume
    ) {
        OptionInstance<Double> voiceVolume = options.getSoundSourceOptionInstance(SoundSource.VOICE);
        AbstractWidget voiceWidget = list.findOption(voiceVolume);
        if (voiceWidget != null) {
            List<?> entries = list.children();
            for (int index = 0; index < entries.size(); index++) {
                Object entry = entries.get(index);
                if (entry instanceof ContainerEventHandler container
                        && container.children().size() == 1
                        && container.children().contains(voiceWidget)) {
                    list.addSmall(voiceVolume, areaMusicVolume);
                    moveLastEntryTo(list.children(), index);
                    return;
                }
            }
        }

        list.addSmall(areaMusicVolume, null);
    }

    @SuppressWarnings("unchecked")
    private static void moveLastEntryTo(List<?> entries, int targetIndex) {
        List<Object> mutableEntries = (List<Object>) entries;
        Object replacement = mutableEntries.remove(mutableEntries.size() - 1);
        mutableEntries.set(targetIndex, replacement);
    }
}
