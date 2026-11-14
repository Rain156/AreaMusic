package imgui.moulberry92.type;

/** A stand-in for the value holder of the same name in Axiom 6's Dear ImGui. */
public final class ImInt {
    private int value;

    public ImInt(int initial) {
    }

    public int get() {
        return value;
    }

    public void set(int value) {
        this.value = value;
    }
}
