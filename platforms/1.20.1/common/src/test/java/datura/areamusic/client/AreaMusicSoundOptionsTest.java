package datura.areamusic.client;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AreaMusicSoundOptionsTest {
    @Test
    void ignoresNonSoundScreensWithoutReadingOrUpdatingTheLoaderConfig() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        Screen otherScreen = new Screen(Component.literal("other")) {
        };

        AreaMusicSoundOptions.onScreenInit(
                otherScreen,
                List.of(),
                () -> {
                    reads.incrementAndGet();
                    return 0.5;
                },
                ignored -> writes.incrementAndGet()
        );

        assertEquals(0, reads.get());
        assertEquals(0, writes.get());
    }

    @Test
    void selectsOnlyOneMatchingSoundOptionsList() {
        assertEquals("sound", AreaMusicSoundOptions.findUnique(
                List.of("other", "sound"),
                value -> value.equals("sound")
        ));
        assertNull(AreaMusicSoundOptions.findUnique(
                List.of("sound-1", "sound-2"),
                value -> value.startsWith("sound")
        ));
        assertNull(AreaMusicSoundOptions.findUnique(
                List.of("other"),
                value -> value.equals("sound")
        ));
    }

    @Test
    void createsTheSliderFromTheSavedIndependentVolume() {
        AtomicReference<Double> update = new AtomicReference<>();

        OptionInstance<Double> option = AreaMusicSoundOptions.createVolumeOption(
                () -> 0.42,
                update::set
        );

        assertEquals(0.42, option.get(), 0.0001);
    }

    @Test
    void formatsNonZeroVolumeLikeVanillaSoundSliders() {
        Component caption = Component.literal("AreaMusic");

        TranslatableContents contents = (TranslatableContents)
                AreaMusicSoundOptions.volumeLabel(caption, 0.42).getContents();

        assertEquals("options.percent_value", contents.getKey());
        assertSame(caption, contents.getArgs()[0]);
        assertEquals(42, contents.getArgs()[1]);
    }

    @Test
    void formatsZeroVolumeAsOff() {
        Component caption = Component.literal("AreaMusic");

        TranslatableContents contents = (TranslatableContents)
                AreaMusicSoundOptions.volumeLabel(caption, 0.0).getContents();

        assertEquals("options.generic_value", contents.getKey());
        assertSame(caption, contents.getArgs()[0]);
        assertSame(CommonComponents.OPTION_OFF, contents.getArgs()[1]);
    }
}
