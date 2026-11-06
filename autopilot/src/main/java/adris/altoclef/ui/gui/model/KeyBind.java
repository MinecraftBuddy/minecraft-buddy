package adris.altoclef.ui.gui.model;

// the open key and the little state machine around changing it. pure logic: the capture control and the shell both
// hold one of these and ask it the questions, so the rules live in exactly one place
public final class KeyBind {

    public enum Outcome { BOUND, CLEARED, CANCELLED, IGNORED }

    private static final int ESCAPE = 256;
    private static final int BACKSPACE = 259;
    private static final int DELETE = 261;
    private static final int F2 = 291;
    private static final int F11 = 300;

    public int key;
    public boolean enabled = true;
    public boolean capturing;

    public KeyBind(int key) {
        this.key = key;
    }

    public void startCapture() {
        capturing = true;
    }

    public void cancelCapture() {
        capturing = false;
    }

    // feed it the next key while capturing. a plain shift is a perfectly good bind (it is the default), so modifier
    // keys are not special here
    public Outcome press(int pressed) {
        if (!capturing || pressed < 0) {
            return Outcome.IGNORED;
        }
        // the game acts on these before any screen sees them (fullscreen, screenshot), so they could open the
        // panel but never close it. stay in capture and wait for a key that works both ways
        if (pressed == F2 || pressed == F11) {
            return Outcome.IGNORED;
        }
        capturing = false;
        if (pressed == ESCAPE) {
            return Outcome.CANCELLED;
        }
        if (pressed == BACKSPACE || pressed == DELETE) {
            key = -1;
            return Outcome.CLEARED;
        }
        key = pressed;
        return Outcome.BOUND;
    }

    // the hotkey only opens from the world. with a screen up it would eat typing
    public boolean opens(int pressed, boolean screenOpen) {
        return enabled && key >= 0 && pressed == key && !screenOpen && !capturing;
    }

    // the same key closes it again, except mid capture where the key being pressed is the new bind, not a close
    public boolean closes(int pressed) {
        return enabled && key >= 0 && pressed == key && !capturing;
    }
}
