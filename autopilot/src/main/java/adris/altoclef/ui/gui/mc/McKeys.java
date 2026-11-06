package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Keys;
import net.minecraft.client.input.InputWithModifiers;

// the one place the game's numbers become the kit's. 26.3 reads the keyboard through sdl: a key is its usb scancode (a is 4,
// escape is 41, right shift 229), a mouse button counts from 1, and the held modifiers are sdl's kmod bits. the kit, the
// guiKey setting and the keybind logic all still speak glfw (right shift 344, escape 256, left button 0), and keeping the
// setting in glfw numbers means a settings.txt carries over between the 1.21.4 and 26.3 builds untouched
public final class McKeys {

    private McKeys() {
    }

    // a key sdl knows and glfw never numbered still has to round trip through the rebind, so it rides above glfw's range
    private static final int EXTENDED = 0x10000;

    // glfw code per sdl scancode, 0 where glfw has no number
    private static final int[] TO_GLFW = new int[232];
    // and back, only the glfw range up to keypad equals
    private static final int[] FROM_GLFW = new int[349];

    private static void pair(int scancode, int glfw) {
        TO_GLFW[scancode] = glfw;
        FROM_GLFW[glfw] = scancode;
    }

    static {
        for (int i = 0; i < 26; i++) {
            pair(4 + i, 65 + i);
        }
        // 1..9 then 0, which sdl puts last
        for (int i = 0; i < 9; i++) {
            pair(30 + i, 49 + i);
        }
        pair(39, 48);
        pair(40, 257);
        pair(41, 256);
        pair(42, 259);
        pair(43, 258);
        pair(44, 32);
        pair(45, 45);
        pair(46, 61);
        pair(47, 91);
        pair(48, 93);
        pair(49, 92);
        pair(50, 161);
        pair(51, 59);
        pair(52, 39);
        pair(53, 96);
        pair(54, 44);
        pair(55, 46);
        pair(56, 47);
        pair(57, 280);
        for (int i = 0; i < 12; i++) {
            pair(58 + i, 290 + i);
        }
        pair(70, 283);
        pair(71, 281);
        pair(72, 284);
        pair(73, 260);
        pair(74, 268);
        pair(75, 266);
        pair(76, 261);
        pair(77, 269);
        pair(78, 267);
        pair(79, 262);
        pair(80, 263);
        pair(81, 264);
        pair(82, 265);
        pair(83, 282);
        pair(84, 331);
        pair(85, 332);
        pair(86, 333);
        pair(87, 334);
        pair(88, 335);
        // keypad 1..9 then 0
        for (int i = 0; i < 9; i++) {
            pair(89 + i, 321 + i);
        }
        pair(98, 320);
        pair(99, 330);
        // the iso "<>" key next to left shift is world 2 in glfw's windows table
        pair(100, 162);
        pair(101, 348);
        pair(103, 336);
        for (int i = 0; i < 12; i++) {
            pair(104 + i, 302 + i);
        }
        pair(224, 341);
        pair(225, 340);
        pair(226, 342);
        pair(227, 343);
        pair(228, 345);
        pair(229, 344);
        pair(230, 346);
        pair(231, 347);
    }

    // sdl scancode in, glfw key code out (or the extended number when glfw never had one)
    public static int toKit(int scancode) {
        if (scancode <= 0) {
            return Keys.NONE;
        }
        if (scancode < TO_GLFW.length && TO_GLFW[scancode] != 0) {
            return TO_GLFW[scancode];
        }
        return EXTENDED + scancode;
    }

    // glfw key code in, sdl scancode out. 0 is the game's own "unknown key"
    public static int toGame(int kitKey) {
        if (kitKey >= EXTENDED) {
            return kitKey - EXTENDED;
        }
        if (kitKey > 0 && kitKey < FROM_GLFW.length) {
            return FROM_GLFW[kitKey];
        }
        return 0;
    }

    // left, middle, right are 1, 2, 3 in the game and 0, 1, 2 in the kit
    public static int buttonToKit(int button) {
        return Math.max(0, button - 1);
    }

    // the events carry the modifiers that were held for that very input, so no asking the keyboard
    public static int mods(InputWithModifiers e) {
        int m = 0;
        if (e.hasShiftDown()) {
            m |= Keys.MOD_SHIFT;
        }
        // the shortcut modifier: ctrl, or cmd on a mac, same as 1.21.4's Screen.hasControlDown
        if (e.hasControlDownWithQuirk()) {
            m |= Keys.MOD_CTRL;
        }
        if (e.hasAltDown()) {
            m |= Keys.MOD_ALT;
        }
        return m;
    }
}
