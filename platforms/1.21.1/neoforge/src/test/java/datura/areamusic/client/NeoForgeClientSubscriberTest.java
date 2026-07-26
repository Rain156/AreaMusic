package datura.areamusic.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NeoForgeClientSubscriberTest {
    @Test
    void clientSetupSubscriberUsesTheModLifecycleBusOnTheClientDistribution() throws Exception {
        EventBusSubscriber subscriber = NeoForgeClientAreaMusic.ModEvents.class
                .getAnnotation(EventBusSubscriber.class);

        assertNotNull(subscriber);
        assertEquals(EventBusSubscriber.Bus.MOD, subscriber.bus());
        assertArrayEquals(new Dist[]{Dist.CLIENT}, subscriber.value());

        Method setup = NeoForgeClientAreaMusic.ModEvents.class
                .getDeclaredMethod("onClientSetup", FMLClientSetupEvent.class);
        assertNotNull(setup.getAnnotation(SubscribeEvent.class));
    }
}
