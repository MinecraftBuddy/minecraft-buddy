package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.update.WhatsNewPanel;
import baritone.utils.update.Snapshot;
import baritone.utils.update.Updater;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;

// the release notes over the title screen's panorama. vanilla draws the background (panorama, blur, the dim), the kit
// draws the card. back and esc go to the title screen that opened it, update starts the download and goes back too so
// the card's progress bar is what you watch
public final class WhatsNewScreen extends Screen {

    private final Minecraft mc = Minecraft.getInstance();
    private final Screen parent;
    private final Snapshot snapshot;
    private final int guiScale;
    private KitSurface surface;
    private WhatsNewPanel panel;
    private boolean ok;
    private long lastNanos;

    public WhatsNewScreen(Screen parent, Snapshot snapshot) {
        super(Component.literal("What's new in Soprano"));
        this.parent = parent;
        this.snapshot = snapshot;
        this.guiScale = Math.max(1, mc.getWindow().getGuiScale());
        try {
            surface = new KitSurface(KitSurface.scaleFor(guiScale));
            panel = new WhatsNewPanel(surface.theme, snapshot, new WhatsNewPanel.Actions() {
                @Override
                public void update() {
                    // a refused download (state moved on, can't install) leaves the notes up instead of eating the click
                    if (Updater.get().download()) {
                        back();
                    }
                }

                @Override
                public void openPage(String url) {
                    ConfirmLinkScreen.confirmLinkNow(WhatsNewScreen.this, URI.create(url), true);
                }

                @Override
                public void back() {
                    onClose();
                }
            });
            ok = true;
        } catch (Throwable t) {
            Safe.report("whats new build", t);
        }
    }

    @Override
    public void onClose() {
        mc.gui.setScreen(parent);
    }

    // the kit would not come up: nothing to read here, so back to the title screen. from the tick and not from the
    // render, swapping screens in the middle of extraction is asking for it
    @Override
    public void tick() {
        if (!ok) {
            onClose();
        }
    }

    @Override
    public void removed() {
        McAtlases.releaseAll();
    }

    @Override
    public void resize(int w, int h) {
        this.width = w;
        this.height = h;
    }

    // ----- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        if (!ok) {
            return;
        }
        Window win = mc.getWindow();
        int winW = win.getWidth();
        int winH = win.getHeight();
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;
        float inv = 1f / guiScale;
        g.pose().pushMatrix();
        g.pose().scale(inv, inv);
        boolean began = false;
        try {
            surface.begin(g, winW, winH);
            began = true;
            surface.syncPreferences(now);
            surface.theme.update(now);
            if (panel.w() != winW || panel.h() != winH) {
                panel.setBounds(0, 0, winW, winH);
            }
            float mx = KitSurface.pointerX();
            float my = KitSurface.pointerY();
            panel.mouseMove(mx, my);
            panel.draw(surface.canvas(), new Frame(now, dt, mx, my));
        } catch (Throwable t) {
            Safe.report("whats new render", t);
        } finally {
            try {
                if (began) {
                    surface.end();
                }
            } catch (Throwable t) {
                Safe.report("whats new render end", t);
            }
            g.pose().popMatrix();
        }
        surface.afterFrame();
    }

    // ----- input, in framebuffer pixels like the gui. always consumed, there are no vanilla widgets on this screen

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (ok) {
            Safe.run("whats new mouse down", () -> panel.mouseDown(KitSurface.pointerX(), KitSurface.pointerY(),
                    McKeys.buttonToKit(event.button()), McKeys.mods(event)));
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (ok) {
            Safe.run("whats new mouse up", () -> panel.mouseUp(KitSurface.pointerX(), KitSurface.pointerY(),
                    McKeys.buttonToKit(event.button()), McKeys.mods(event)));
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (ok) {
            double s = guiScale;
            Safe.run("whats new mouse drag", () -> panel.mouseDrag(KitSurface.pointerX(), KitSurface.pointerY(),
                    McKeys.buttonToKit(event.button()), (float) (dx * s), (float) (dy * s)));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (ok) {
            Safe.run("whats new scroll", () -> panel.scroll(KitSurface.pointerX(), KitSurface.pointerY(), (float) dy));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!ok) {
            return true;
        }
        Safe.run("whats new key", () -> {
            int key = McKeys.toKit(event.key());
            if (key == Keys.ESCAPE) {
                onClose();
                return;
            }
            panel.keyDown(key, McKeys.mods(event));
        });
        return true;
    }
}
