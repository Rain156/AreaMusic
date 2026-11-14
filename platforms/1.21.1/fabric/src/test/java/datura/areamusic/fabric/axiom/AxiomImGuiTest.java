package datura.areamusic.fabric.axiom;

import datura.areamusic.ui.PanelUi;
import imgui.moulberry92.ImGui;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Axiom 6 moved Dear ImGui to a package of its own. These tests stand in for it with the small
 * fake under {@code imgui.moulberry92} in the test sources; the real {@code imgui} package is not
 * on the test class path at all, just as it is missing from a game with Axiom 6.
 */
class AxiomImGuiTest {
    private static final String ADAPTER = "datura/areamusic/ui/imgui/ImGuiPanelUi.class";

    @Test
    void theCopyOfTheAdapterRefersOnlyToTheRelocatedImGui() throws Exception {
        byte[] copy = AxiomImGui.relocate(adapterBytes(), "imgui.moulberry92");

        ClassReader reader = new ClassReader(copy);
        assertEquals("datura/areamusic/fabric/axiom/RelocatedImGuiPanelUi", reader.getClassName());
        assertArrayEquals(new String[] {"datura/areamusic/ui/PanelUi"}, reader.getInterfaces());
        String constants = new String(copy, StandardCharsets.ISO_8859_1);
        assertTrue(constants.contains("imgui/moulberry92/ImGui"));
        assertTrue(constants.contains("imgui/moulberry92/type/ImString"));
        assertFalse(Pattern.compile("imgui/(?!moulberry92/)").matcher(constants).find(),
                "a reference to the original imgui package, or to the adapter itself, is left");
    }

    @Test
    void theAdapterIsUntouchedWhenAxiomUsesThePackageItWasCompiledAgainst() throws Exception {
        byte[] original = adapterBytes();
        String constants = new String(AxiomImGui.relocate(original, "imgui"), StandardCharsets.ISO_8859_1);

        assertTrue(constants.contains("imgui/ImGui"));
        assertTrue(constants.contains("imgui/type/ImString"));
    }

    @Test
    void findsTheImGuiOnTheClassPathWhenAxiomsEditorCannotBeAsked() {
        assertEquals("imgui.moulberry92", AxiomImGui.imguiPackage(AxiomImGuiTest.class.getClassLoader()));
    }

    @Test
    void thePanelDrawsThroughTheRelocatedImGui() throws Exception {
        ImGui.CALLS.clear();

        PanelUi ui = AxiomImGui.createPanelUi();
        ui.label("hello");
        boolean clicked = ui.button("ok", "OK");

        assertEquals("datura.areamusic.fabric.axiom.RelocatedImGuiPanelUi", ui.getClass().getName());
        assertEquals(List.of("textWrapped hello", "button OK##ok"), ImGui.CALLS);
        assertTrue(clicked);
    }

    private static byte[] adapterBytes() throws Exception {
        try (InputStream in = AxiomImGuiTest.class.getClassLoader().getResourceAsStream(ADAPTER)) {
            assertNotNull(in, "the panel adapter is not on the test class path");
            return in.readAllBytes();
        }
    }
}
