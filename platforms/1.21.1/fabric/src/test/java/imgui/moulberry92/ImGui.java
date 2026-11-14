package imgui.moulberry92;

import java.util.ArrayList;
import java.util.List;

/** A stand-in for the Dear ImGui that Axiom 6 ships, with just the calls the tests make. */
public final class ImGui {
    public static final List<String> CALLS = new ArrayList<>();

    private ImGui() {
    }

    public static void textWrapped(String text) {
        CALLS.add("textWrapped " + text);
    }

    public static boolean button(String label) {
        CALLS.add("button " + label);
        return true;
    }
}
