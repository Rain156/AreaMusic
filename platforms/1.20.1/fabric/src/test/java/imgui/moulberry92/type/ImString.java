package imgui.moulberry92.type;

/** A stand-in for the value holder of the same name in Axiom 6's Dear ImGui. */
public final class ImString {
    private String value;

    public ImString(int capacity) {
    }

    public String get() {
        return value;
    }

    public void set(String value) {
        this.value = value;
    }
}
