package datura.areamusic.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeAreaMusicSoundOptionsTest {
    private static final String ADAPTER_CLASS =
            "datura/areamusic/client/ForgeAreaMusicSoundOptions.class";
    private static final String SUBSCRIBER_DESCRIPTOR =
            "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";
    private static final String DIST_DESCRIPTOR =
            "Lnet/minecraftforge/api/distmarker/Dist;";
    private static final String BUS_DESCRIPTOR =
            "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus;";
    private static final String SUBSCRIBE_EVENT_DESCRIPTOR =
            "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    private static final String HANDLER_DESCRIPTOR =
            "(Lnet/minecraftforge/client/event/ScreenEvent$Init$Post;)V";

    @Test
    void forgeAdapterBytecodeDeclaresTheClientForgeSubscriberContract() throws IOException {
        SubscriberMetadata metadata = readMetadata();

        assertTrue(metadata.subscriberAnnotationPresent, "EventBusSubscriber annotation is missing");
        assertEquals("areamusic", metadata.modId);
        assertEquals(List.of("CLIENT"), metadata.distValues);
        assertEquals("FORGE", metadata.busValue);
        assertEquals(1, metadata.handlers.size());

        HandlerMetadata handler = metadata.handlers.get(0);
        assertEquals(HANDLER_DESCRIPTOR, handler.descriptor);
        assertTrue((handler.access & Opcodes.ACC_PUBLIC) != 0, "handler must be public");
        assertTrue((handler.access & Opcodes.ACC_STATIC) != 0, "handler must be static");
        assertTrue(handler.subscribeEventAnnotationPresent, "SubscribeEvent annotation is missing");
    }

    private static SubscriberMetadata readMetadata() throws IOException {
        ClassLoader loader = ForgeAreaMusicSoundOptionsTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(ADAPTER_CLASS)) {
            assertNotNull(input, "compiled adapter class is missing");
            SubscriberMetadata metadata = new SubscriberMetadata();
            new ClassReader(input).accept(
                    new SubscriberClassVisitor(metadata),
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
            );
            return metadata;
        }
    }

    private static final class SubscriberClassVisitor extends ClassVisitor {
        private final SubscriberMetadata metadata;

        private SubscriberClassVisitor(SubscriberMetadata metadata) {
            super(Opcodes.ASM9);
            this.metadata = metadata;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!SUBSCRIBER_DESCRIPTOR.equals(descriptor)) {
                return null;
            }
            metadata.subscriberAnnotationPresent = true;
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    if ("modid".equals(name)) {
                        metadata.modId = (String) value;
                    }
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    if (!"value".equals(name)) {
                        return null;
                    }
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitEnum(String ignored, String enumDescriptor, String value) {
                            if (DIST_DESCRIPTOR.equals(enumDescriptor)) {
                                metadata.distValues.add(value);
                            }
                        }
                    };
                }

                @Override
                public void visitEnum(String name, String enumDescriptor, String value) {
                    if ("bus".equals(name) && BUS_DESCRIPTOR.equals(enumDescriptor)) {
                        metadata.busValue = value;
                    }
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions
        ) {
            if (!"onScreenInit".equals(name)) {
                return null;
            }
            HandlerMetadata handler = new HandlerMetadata(access, descriptor);
            metadata.handlers.add(handler);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    if (SUBSCRIBE_EVENT_DESCRIPTOR.equals(annotationDescriptor)) {
                        handler.subscribeEventAnnotationPresent = true;
                    }
                    return null;
                }
            };
        }
    }

    private static final class SubscriberMetadata {
        private boolean subscriberAnnotationPresent;
        private String modId;
        private final List<String> distValues = new ArrayList<>();
        private String busValue;
        private final List<HandlerMetadata> handlers = new ArrayList<>();
    }

    private static final class HandlerMetadata {
        private final int access;
        private final String descriptor;
        private boolean subscribeEventAnnotationPresent;

        private HandlerMetadata(int access, String descriptor) {
            this.access = access;
            this.descriptor = descriptor;
        }
    }
}
