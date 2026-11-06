package adris.altoclef.util.helpers;

import com.mojang.blaze3d.platform.InputConstants;

public class InputHelper {

    // codes are InputConstants.KEY_* now (sdl scancodes), not glfw ones, and it reads the keyboard state so no window handle
    public static boolean isKeyPressed(int code) {
        return InputConstants.isKeyDown(code);
    }
}
