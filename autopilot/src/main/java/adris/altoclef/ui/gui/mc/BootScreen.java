package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.BrandArt;
import adris.altoclef.ui.gui.kit.Easing;
import adris.altoclef.ui.gui.kit.PixelImage;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.Theme;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;

// our look for vanilla's loading overlay: black, the soprano logo, a thin bar. vanilla keeps the whole lifecycle (the
// reload, the progress smoothing, its two second fade out and taking itself off screen), MixinLoadingOverlay only
// swaps the red for black, drops the mojang logo and the bar, and calls draw() at the end of every frame. images and
// round rects only: no fonts exist yet on first boot. if anything throws we go vanilla for the rest of the run
public final class BootScreen {

    private static final int MARK = 112;
    private static final int MARK_LIFT = 36;
    private static final int BAR_W = 240;
    private static final int BAR_H = 6;
    private static final int BAR_GAP = 64;
    private static final int TRACK = 0x2EFFFFFF;
    private static final int FILL = 0xFFFFFFFF;
    private static final float MARK_GROW = 0.08f;

    private static boolean broken;
    // set by the logo redirect when it actually skipped mojang's logo this frame, eaten by draw()
    private static boolean logoSkipped;
    private static McDraw draw;
    private static McCanvas canvas;
    private static int markPx;
    // the mark at its own size, and baked a bit bigger for the grow on the way out (drawn smaller than that as it grows)
    private static PixelImage mark;
    private static PixelImage markBig;

    private BootScreen() {
    }

    // the redirects ask this every frame: true = black instead of red, no mojang logo, no vanilla bar
    public static boolean drawing() {
        return !broken && SopranoMenus.enabled();
    }

    public static void skippedLogo() {
        logoSkipped = true;
    }

    // the bar hook runs after the logo blits in the same frame, so it can ask too
    public static boolean ownsFrame() {
        return drawing() && logoSkipped;
    }

    // the overlay's tail. the clock numbers are vanilla's own fields, currentProgress already moved this frame
    public static void draw(GuiGraphicsExtractor g, boolean fadeIn, long fadeInStart, long fadeOutStart, float progress) {
        // the logo redirect is require 0. if another loading screen mod got that call first, mojang's logo is still
        // there and ours would sit right on top of it. two logos is worse than theirs alone
        boolean ours = logoSkipped;
        logoSkipped = false;
        if (!drawing() || !ours) {
            return;
        }
        long now = Util.getMillis();
        float out = fadeOutStart > -1L ? (now - fadeOutStart) / 1000f : -1f;
        if (out >= 2f) {
            // vanilla just took the overlay off. nothing of ours was queued this frame, so the pictures can go
            finish();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Window win = mc.getWindow();
        int w = win.getWidth();
        int h = win.getHeight();
        // minimised: nothing to draw into, and not a reason to give up
        if (w < 1 || h < 1) {
            return;
        }
        float in = fadeInStart > -1L ? (now - fadeInStart) / 500f : -1f;
        float bg = background(out, fadeIn, in);
        boolean anim = SopranoMenus.animations();
        float barA = 1f;
        float markA = 1f;
        float grow = 1f;
        if (out >= 0f) {
            float ms = out * 1000f;
            float e = Easing.outCubic(ms / 600f);
            barA = clamp01(1f - ms / 250f);
            markA = anim ? 1f - e : clamp01(1f - ms / 600f);
            grow = anim ? 1f + MARK_GROW * e : 1f;
        }
        markA = Math.min(markA, bg);
        barA = Math.min(barA, bg);
        if (markA <= 0f && barA <= 0f) {
            return;
        }
        int gs = Math.max(1, win.getGuiScale());
        float inv = 1f / gs;
        g.pose().pushMatrix();
        g.pose().scale(inv, inv);
        boolean began = false;
        try {
            if (!ensureCanvas()) {
                broken = true;
                return;
            }
            canvas.begin(g, w, h);
            began = true;
            paint(w, h, Theme.scaleFor(h), markA, barA, grow, progress);
        } catch (Throwable t) {
            Safe.report("boot screen", t);
            broken = true;
        } finally {
            try {
                if (began) {
                    canvas.end();
                }
            } catch (Throwable t) {
                Safe.report("boot screen end", t);
                broken = true;
            }
            g.pose().popMatrix();
        }
        if (canvas != null && canvas.broken) {
            broken = true;
        }
    }

    // vanilla's own background alpha, so our things never outlive the black under them: gone over the second half
    // of the fade out, coming in with a reload's fade in
    private static float background(float out, boolean fadeIn, float in) {
        if (out >= 1f) {
            return 1f - clamp01(out - 1f);
        }
        return fadeIn ? clamp01(in) : 1f;
    }

    private static void paint(int w, int h, float s, float markA, float barA, float grow, float progress) {
        int box = Math.round(MARK * s);
        int cy = Math.round(h / 2f - Math.round(MARK_LIFT * s));
        if (markA > 0f) {
            bake(box);
            int m = Math.round(box * grow);
            PixelImage img = m == box ? mark : markBig;
            canvas.image(img, Math.round(w / 2f - m / 2f), Math.round(cy - m / 2f), m, m, Theme.alpha(0xFFFFFFFF, markA));
        }
        if (barA > 0f) {
            int bw = Math.round(BAR_W * s);
            int bh = Math.round(BAR_H * s);
            int br = Math.round(BAR_H / 2f * s);
            int top = Math.round(cy - box / 2f) + box + Math.round(BAR_GAP * s);
            int x = Math.round(w / 2f - bw / 2f);
            canvas.fillRoundRect(x, top, bw, bh, br, Theme.fade(TRACK, barA));
            int fw = Math.round(bw * clamp01(progress));
            if (fw > 0) {
                canvas.fillRoundRect(x, top, fw, bh, Math.min(br, fw / 2), Theme.fade(FILL, barA));
            }
        }
    }

    // one McCanvas for the whole boot. KitSurface isn't used on purpose: it reads baritone's settings, and the first
    // frames come before baritone exists. a canvas without atlases would draw the pictures as grey boxes, so no
    // atlases means vanilla
    private static boolean ensureCanvas() {
        if (canvas != null) {
            return true;
        }
        McAtlases at = McAtlases.get();
        if (at == null || !at.ok) {
            return false;
        }
        draw = new McDraw();
        canvas = new McCanvas(at, draw);
        return true;
    }

    private static void bake(int box) {
        if (mark != null && markPx == box) {
            return;
        }
        McAtlases.release(mark);
        McAtlases.release(markBig);
        mark = BrandArt.logo(box);
        markBig = BrandArt.logo((int) Math.ceil(box * (1f + MARK_GROW)));
        markPx = box;
    }

    private static void finish() {
        McAtlases.release(mark);
        McAtlases.release(markBig);
        mark = null;
        markBig = null;
        markPx = 0;
        canvas = null;
        draw = null;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
