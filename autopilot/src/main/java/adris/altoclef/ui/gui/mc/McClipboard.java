package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Clipboard;
import adris.altoclef.ui.gui.kit.Safe;
import net.minecraft.client.Minecraft;

// the system clipboard by way of the game's keyboard handler (it knows how to ask glfw without crashing)
final class McClipboard implements Clipboard {

    @Override
    public String get() {
        try {
            String s = Minecraft.getInstance().keyboardHandler.getClipboard();
            return s == null ? "" : s;
        } catch (Throwable t) {
            Safe.report("clipboard get", t);
            return "";
        }
    }

    @Override
    public void set(String text) {
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(text == null ? "" : text);
        } catch (Throwable t) {
            Safe.report("clipboard set", t);
        }
    }
}
