package adris.altoclef.ui.gui.kit;

// glfw key codes and modifier bits, copied so the kit never imports lwjgl. -1 is "no key" for the open bind
public final class Keys {

    private Keys() {
    }

    public static final int NONE = -1;
    public static final int SPACE = 32;
    public static final int ONE = 49;
    public static final int F2 = 291;
    public static final int F11 = 300;
    public static final int A = 65;
    public static final int C = 67;
    public static final int V = 86;
    public static final int X = 88;
    public static final int Z = 90;
    public static final int ESCAPE = 256;
    public static final int ENTER = 257;
    public static final int TAB = 258;
    public static final int BACKSPACE = 259;
    public static final int INSERT = 260;
    public static final int DELETE = 261;
    public static final int RIGHT = 262;
    public static final int LEFT = 263;
    public static final int DOWN = 264;
    public static final int UP = 265;
    public static final int PAGE_UP = 266;
    public static final int PAGE_DOWN = 267;
    public static final int HOME = 268;
    public static final int END = 269;
    public static final int KP_ENTER = 335;
    public static final int LEFT_SHIFT = 340;
    public static final int LEFT_CONTROL = 341;
    public static final int LEFT_ALT = 342;
    public static final int LEFT_SUPER = 343;
    public static final int RIGHT_SHIFT = 344;
    public static final int RIGHT_CONTROL = 345;
    public static final int RIGHT_ALT = 346;
    public static final int RIGHT_SUPER = 347;

    public static final int MOD_SHIFT = 1;
    public static final int MOD_CTRL = 2;
    public static final int MOD_ALT = 4;
    public static final int MOD_SUPER = 8;

    public static boolean shift(int mods) {
        return (mods & MOD_SHIFT) != 0;
    }

    // ctrl on windows and linux, cmd on mac: both count as "the shortcut key"
    public static boolean ctrl(int mods) {
        return (mods & (MOD_CTRL | MOD_SUPER)) != 0;
    }

    public static boolean alt(int mods) {
        return (mods & MOD_ALT) != 0;
    }

    public static boolean isModifier(int key) {
        return key >= LEFT_SHIFT && key <= RIGHT_SUPER;
    }

    public static boolean isEnter(int key) {
        return key == ENTER || key == KP_ENTER;
    }
}
