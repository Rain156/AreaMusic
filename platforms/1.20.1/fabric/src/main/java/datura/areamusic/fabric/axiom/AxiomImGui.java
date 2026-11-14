package datura.areamusic.fabric.axiom;

import datura.areamusic.ui.PanelUi;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;

/**
 * Gives the tool a panel adapter for the Dear ImGui that the installed Axiom draws with.
 *
 * <p>Axiom 5 and older ship ImGui in the {@code imgui} package. Axiom 6 ships a newer build under
 * a package of its own ({@code imgui.moulberry92}), with the same calls. The adapter is compiled
 * against the first; for any other package a copy of it is made here with the package name
 * swapped, so there is one adapter in the source and it follows Axiom wherever ImGui goes.
 */
final class AxiomImGui {
    private static final String ADAPTER = "datura/areamusic/ui/imgui/ImGuiPanelUi";
    private static final String COPY = "datura/areamusic/fabric/axiom/RelocatedImGuiPanelUi";
    private static final String COMPILED_AGAINST = "imgui";
    private static final String[] KNOWN_PACKAGES = {"imgui.moulberry92", COMPILED_AGAINST};

    private AxiomImGui() {
    }

    static PanelUi createPanelUi() throws ReflectiveOperationException, IOException {
        ClassLoader loader = AxiomImGui.class.getClassLoader();
        String imgui = imguiPackage(loader);
        Class<?> adapter = imgui.equals(COMPILED_AGAINST)
                ? Class.forName(ADAPTER.replace('/', '.'), true, loader)
                : MethodHandles.lookup().defineClass(relocate(adapterBytes(loader), imgui));
        return (PanelUi) adapter.getConstructor().newInstance();
    }

    /** The package of the ImGui that Axiom itself uses, so the panel lands in Axiom's window. */
    static String imguiPackage(ClassLoader loader) {
        try {
            // Not initialised: only the signature is read, Axiom's editor is left alone.
            Class<?> editor = Class.forName("com.moulberry.axiom.editor.EditorUI", false, loader);
            return editor.getMethod("getIO").getReturnType().getPackageName();
        } catch (ReflectiveOperationException | LinkageError exception) {
            for (String candidate : KNOWN_PACKAGES) {
                if (loader.getResource(candidate.replace('.', '/') + "/ImGui.class") != null) {
                    return candidate;
                }
            }
            throw new IllegalStateException("Could not find the Dear ImGui that Axiom uses", exception);
        }
    }

    /** The adapter's class file with every reference to {@code imgui} moved to another package. */
    @SuppressWarnings("deprecation") // The constructor every ASM 9 has.
    static byte[] relocate(byte[] adapter, String imguiPackage) {
        String target = imguiPackage.replace('.', '/') + '/';
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(adapter).accept(new ClassRemapper(writer, new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName.equals(ADAPTER)) {
                    return COPY;
                }
                return internalName.startsWith(COMPILED_AGAINST + '/')
                        ? target + internalName.substring(COMPILED_AGAINST.length() + 1)
                        : internalName;
            }
        }), 0);
        return writer.toByteArray();
    }

    private static byte[] adapterBytes(ClassLoader loader) throws IOException {
        try (InputStream in = loader.getResourceAsStream(ADAPTER + ".class")) {
            if (in == null) {
                throw new IOException("The panel adapter is missing from the mod: " + ADAPTER);
            }
            return in.readAllBytes();
        }
    }
}
