package imgui.moulberry92.type;

/** A stand-in for the value holder of the same name in Axiom 6's Dear ImGui. */
public final class ImBoolean {
    private boolean value;

    public ImBoolean() {
    }

    public boolean get() {
        return value;
    }

    public void set(boolean value) {
        this.value = value;
    }
}
