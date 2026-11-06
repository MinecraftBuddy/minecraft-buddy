package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.Theme;
import baritone.Baritone;
import baritone.api.Settings;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// the canvas and theme a small kit thing on a vanilla screen needs (the title screen's update card, the release notes):
// the atlas canvas when it comes up, the plain one when it does not, and the same swap GuiScreen does when a page
// refuses to upload mid frame. MoveScreen has this inline, this is that pulled out so the next one is five lines
final class KitSurface {

    private final McDraw draw = new McDraw();
    private McCanvas atlasCanvas;
    private FallbackCanvas plainCanvas;
    private Canvas canvas;
    final Theme theme;

    KitSurface(float scale) {
        McAtlases at = McAtlases.get();
        if (at != null && at.ok) {
            atlasCanvas = new McCanvas(at, draw);
            canvas = atlasCanvas;
        } else {
            plainCanvas = new FallbackCanvas(draw);
            canvas = plainCanvas;
        }
        theme = new Theme(scale, canvas);
        theme.setDark(dark());
        canvas.setTheme(theme);
        if (atlasCanvas != null) {
            at.prewarm(theme);
        }
    }

    Canvas canvas() {
        return canvas;
    }

    void begin(GuiGraphicsExtractor g, int w, int h) {
        if (atlasCanvas != null) {
            atlasCanvas.begin(g, w, h);
        } else {
            plainCanvas.begin(g, w, h);
        }
    }

    void end() {
        if (atlasCanvas != null) {
            atlasCanvas.end();
        } else {
            plainCanvas.end();
        }
    }

    // call once the frame is over: a canvas that broke goes plain so the words come back next frame
    void afterFrame() {
        if (atlasCanvas != null && atlasCanvas.broken) {
            atlasCanvas = null;
            plainCanvas = new FallbackCanvas(draw);
            canvas = plainCanvas;
            canvas.setTheme(theme);
        }
    }

    // the click gui and #set can flip these while the title screen is up, so read them every frame like GuiScreen does
    void syncPreferences(long now) {
        Settings s = settings();
        if (s == null) {
            return;
        }
        Anim.enabled = s.guiAnimations.value;
        if (theme.dark() != s.guiDarkMode.value) {
            theme.setDark(s.guiDarkMode.value, now);
        }
    }

    private static boolean dark() {
        Settings s = settings();
        return s == null || s.guiDarkMode.value;
    }

    private static Settings settings() {
        try {
            return Baritone.settings();
        } catch (Throwable t) {
            Safe.report("kit surface settings", t);
            return null;
        }
    }

    // the kit's design px are at 1080p with the gui at 2x. this follows the gui scale instead so the card sits next to
    // vanilla's buttons at about three quarters of their height whatever the window is: 2 -> 1.0, 3 -> 1.5, 4 and up
    // -> 2.0. the top stays at 2.0 so the card still clears the logo at 1440p's auto scale of 6. quarter steps keep
    // the glyph atlases cached, same reason as Theme.scaleFor
    static float scaleFor(int guiScale) {
        float raw = Math.max(1, guiScale) * 0.5f;
        float stepped = Math.round(raw * 4f) / 4f;
        return Math.max(0.75f, Math.min(2.0f, stepped));
    }

    // the cursor in framebuffer pixels, from where the renderer reads it, same as GuiScreen
    static float pointerX() {
        Minecraft mc = Minecraft.getInstance();
        Window win = mc.getWindow();
        return (float) (mc.mouseHandler.xpos() * win.getWidth() / Math.max(1, win.getScreenWidth()));
    }

    static float pointerY() {
        Minecraft mc = Minecraft.getInstance();
        Window win = mc.getWindow();
        return (float) (mc.mouseHandler.ypos() * win.getHeight() / Math.max(1, win.getScreenHeight()));
    }
}
