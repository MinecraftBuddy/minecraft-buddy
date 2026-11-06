package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.FadeCanvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.update.UpdateCard;
import baritone.utils.update.Snapshot;
import baritone.utils.update.Updater;
import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

// the update card as a vanilla widget on the title screen, so clicks, tab focus and the narrator come from the screen
// like for any other button. the widget's box is in gui units and the kit draws in framebuffer pixels under 1/guiScale,
// the two agree because the box is the card's pixel size rounded up. nothing in here may throw into the title screen
public final class UpdateCardWidget extends AbstractWidget {

    // gui units in from the corner. vanilla's version line is bottom left and the logo is centred, so this is free. 4
    // and not more because at 1080p on gui scale 4 every unit here is one less for the card before the logo starts
    private static final int MARGIN = 4;
    // the logo texture is 256 wide and centred, anything to its left is ours
    private static final int LOGO_HALF_W = 128;

    private final Minecraft mc = Minecraft.getInstance();
    private final Screen screen;
    private final int guiScale;
    private KitSurface surface;
    private UpdateCard card;
    private final FadeCanvas fade = new FadeCanvas();
    private boolean ok;
    // a click focuses the widget too, and that must not put the ring on the first button
    private boolean mouseFocus;
    private boolean tabBackwards;
    private long lastNanos;

    public UpdateCardWidget(Screen screen) {
        super(MARGIN, MARGIN, 1, 1, Component.literal("Soprano update"));
        this.screen = screen;
        this.guiScale = Math.max(1, mc.getWindow().getGuiScale());
        try {
            surface = new KitSurface(KitSurface.scaleFor(guiScale));
            card = new UpdateCard(surface.theme, new Actions());
            card.setMaxWidth(maxDesignWidth());
            card.show(Updater.get().snapshot());
            sync();
            ok = true;
        } catch (Throwable t) {
            Safe.report("update card build", t);
            visible = false;
            active = false;
        }
    }

    // the title screen is going away, its textures go with it (re-uploaded on demand next time)
    public static void released() {
        McAtlases.releaseAll();
    }

    // the room between the margin and where the logo starts, in design px, so the card never sits under the logo
    private int maxDesignWidth() {
        int logoLeft = screen.width / 2 - LOGO_HALF_W;
        int availPx = (logoLeft - 2 * MARGIN) * guiScale;
        return (int) Math.floor(availPx / surface.theme.scale);
    }

    private int px(int gui) {
        return gui * guiScale;
    }

    // the kit card's size, rounded up to gui units so the clickable box covers all of it
    private void sync() {
        card.setBounds(px(getX()), px(getY()), card.preferredWidth(), card.preferredHeight());
        int w = Math.max(1, (int) Math.ceil(card.preferredWidth() / (double) guiScale));
        int h = Math.max(1, (int) Math.ceil(card.preferredHeight() / (double) guiScale));
        if (w != getWidth() || h != getHeight()) {
            setSize(w, h);
        }
        // a card with no room is not just invisible, its 1x1 box shouldn't eat clicks either
        visible = !card.hidden();
        active = visible;
    }

    // ----- drawing

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
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
            Snapshot s = Updater.get().snapshot();
            card.setMaxWidth(maxDesignWidth());
            card.show(s);
            sync();
            surface.begin(g, winW, winH);
            began = true;
            surface.syncPreferences(now);
            surface.theme.update(now);
            float mx = KitSurface.pointerX();
            float my = KitSurface.pointerY();
            card.mouseMove(mx, my);
            // the first title screen fades its widgets in through setAlpha, the card comes along
            Canvas c = alpha < 1f ? fade.reset(surface.canvas(), alpha, 0f) : surface.canvas();
            card.draw(c, new Frame(now, dt, mx, my));
        } catch (Throwable t) {
            Safe.report("update card render", t);
        } finally {
            try {
                if (began) {
                    surface.end();
                }
            } catch (Throwable t) {
                Safe.report("update card render end", t);
            }
            g.pose().popMatrix();
        }
        surface.afterFrame();
    }

    // ----- mouse. the screen already checked the click is on our box and it was the left button

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (!ok) {
            return;
        }
        mouseFocus = true;
        Safe.run("update card mouse down", () -> card.mouseDown(KitSurface.pointerX(), KitSurface.pointerY(), 0, McKeys.mods(event)));
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (!ok) {
            return;
        }
        mouseFocus = false;
        Safe.run("update card mouse up", () -> card.mouseUp(KitSurface.pointerX(), KitSurface.pointerY(), 0, McKeys.mods(event)));
    }

    @Override
    public void playDownSound(SoundManager manager) {
        // the kit buttons are silent everywhere else, and a click on the card's text is not a press
    }

    // ----- keyboard focus. tab from vanilla lands on our first (or last) button, tab inside walks them, tab off the
    // end hands focus back to the screen

    @Override
    public ComponentPath nextFocusPath(FocusNavigationEvent event) {
        if (!ok || !Safe.test("update card focusable", card::hasFocusable)) {
            return null;
        }
        if (event instanceof FocusNavigationEvent.TabNavigation tab) {
            tabBackwards = !tab.forward();
        }
        return super.nextFocusPath(event);
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!ok) {
            return;
        }
        Safe.run("update card focus", () -> {
            if (!focused) {
                card.blur();
            } else if (mouseFocus) {
                mouseFocus = false;
            } else {
                card.focusEnd(tabBackwards);
            }
        });
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!ok || !isFocused()) {
            return false;
        }
        return Safe.test("update card key", () -> {
            int key = McKeys.toKit(event.key());
            int mods = McKeys.mods(event);
            if (key == Keys.TAB) {
                return card.cycleFocus(Keys.shift(mods));
            }
            return card.keyDown(key, mods);
        });
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        if (!ok) {
            return;
        }
        Safe.run("update card narration", () -> {
            out.add(NarratedElementType.TITLE, Component.literal("Soprano update. " + card.summary()));
            String label = card.focusedLabel();
            if (label != null) {
                out.add(NarratedElementType.USAGE, Component.literal("press enter to " + label.toLowerCase(Locale.ROOT)));
            }
        });
    }

    // ----- what the buttons do

    private final class Actions implements UpdateCard.Actions {
        @Override
        public void update() {
            Updater.get().download();
        }

        @Override
        public void recheck() {
            Updater.get().recheck();
        }

        @Override
        public void whatsNew() {
            mc.gui.setScreen(new WhatsNewScreen(screen, Updater.get().snapshot()));
        }

        // through vanilla's "open this link?" screen, which comes back to the title screen afterwards
        @Override
        public void openPage(String url) {
            ConfirmLinkScreen.confirmLinkNow(screen, URI.create(url), true);
        }

        @Override
        public void quit() {
            mc.stop();
        }

        @Override
        public void openMods(Path folder) {
            Blaze3D.openPath(folder);
        }

        @Override
        public void beginSwitch() {
            Updater.get().beginSwitch();
        }

        @Override
        public void cancelSwitch() {
            Updater.get().cancelSwitch();
        }
    }
}
