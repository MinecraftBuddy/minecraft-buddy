package adris.altoclef.ui.gui.kit;

// one render frame. widgets read time from here and never from the clock, so a preview can ask for the exact frame
// 80 ms into an animation and get the same pixels every run
public record Frame(long nowNanos, float dtSeconds, float mouseX, float mouseY) {

    public long nowMillis() {
        return nowNanos / 1_000_000L;
    }
}
