package datura.areamusic.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeClientSubscriberTest {
    @Test
    void clientSetupSubscriberHandlesAModLifecycleEventOnTheClientDistribution() throws Exception {
        EventBusSubscriber subscriber = NeoForgeClientAreaMusic.ModEvents.class
                .getAnnotation(EventBusSubscriber.class);

        assertNotNull(subscriber);
        assertArrayEquals(new Dist[]{Dist.CLIENT}, subscriber.value());

        Method setup = NeoForgeClientAreaMusic.ModEvents.class
                .getDeclaredMethod("onClientSetup", FMLClientSetupEvent.class);
        assertNotNull(setup.getAnnotation(SubscribeEvent.class));
        assertTrue(Modifier.isStatic(setup.getModifiers()));
        assertTrue(IModBusEvent.class.isAssignableFrom(FMLClientSetupEvent.class));
    }

    @Test
    void modLifecycleSubscriberLetsNeoForgeInferTheEventBus() throws IOException {
        Path source = findClientSource();
        String sourceText = Files.readString(source);
        int annotationStart = sourceText.indexOf("@EventBusSubscriber(");
        int modEventsStart = sourceText.indexOf("public static final class ModEvents");

        assertTrue(annotationStart >= 0 && modEventsStart > annotationStart);
        assertFalse(sourceText.substring(annotationStart, modEventsStart).contains("bus ="));

        Set<String> annotationElements = compiledSubscriberAnnotationElements();
        assertTrue(annotationElements.contains("modid"));
        assertTrue(annotationElements.contains("value"));
        assertFalse(annotationElements.contains("bus"));
    }

    private static Path findClientSource() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path moduleRelative = current.resolve(Path.of("src", "main", "java", "datura", "areamusic",
                    "client", "NeoForgeClientAreaMusic.java"));
            if (Files.isRegularFile(moduleRelative)) {
                return moduleRelative;
            }
            Path rootRelative = current.resolve(Path.of("platforms", "1.21.1", "neoforge", "src", "main",
                    "java", "datura", "areamusic", "client", "NeoForgeClientAreaMusic.java"));
            if (Files.isRegularFile(rootRelative)) {
                return rootRelative;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate NeoForgeClientAreaMusic.java");
    }

    private static Set<String> compiledSubscriberAnnotationElements() throws IOException {
        String resourceName = "/" + NeoForgeClientAreaMusic.ModEvents.class.getName().replace('.', '/') + ".class";
        try (InputStream classBytes = NeoForgeClientAreaMusic.ModEvents.class.getResourceAsStream(resourceName)) {
            assertNotNull(classBytes);
            Set<String> elements = new HashSet<>();
            new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (!Type.getDescriptor(EventBusSubscriber.class).equals(descriptor)) {
                        return null;
                    }
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            elements.add(name);
                        }

                        @Override
                        public void visitEnum(String name, String descriptor, String value) {
                            elements.add(name);
                        }

                        @Override
                        public AnnotationVisitor visitArray(String name) {
                            elements.add(name);
                            return super.visitArray(name);
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return elements;
        }
    }
}
