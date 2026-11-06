package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.KeyNames;
import com.mojang.blaze3d.platform.InputConstants;

// "Right Shift" for 344, straight from the game's own key table so it follows the language. the table is keyed by sdl
// scancode, so the glfw number the setting holds goes through McKeys first
final class McKeyNames implements KeyNames {

    @Override
    public String name(int glfwKey) {
        if (glfwKey < 0) {
            return "None";
        }
        try {
            int scancode = McKeys.toGame(glfwKey);
            if (scancode > 0) {
                String s = InputConstants.Type.KEYBOARD.getOrCreate(scancode).getDisplayName().getString();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
            // falls through to the number
        }
        return "Key " + glfwKey;
    }
}
