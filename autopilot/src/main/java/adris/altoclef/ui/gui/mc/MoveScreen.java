package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.mc.move.MoveBar;
import adris.altoclef.ui.gui.mc.move.MoveSession;
import baritone.Baritone;
import baritone.api.Settings;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

// the move mode screen: see-through, no blur, no pause, so the world is the whole picture and the free camera
// (MoveSession) is what draws it. the mouse is free the whole time, the arrows and the camera both want it. the bar
// along the bottom is the click gui's kit on the same canvases GuiScreen uses
public final class MoveScreen extends Screen implements MoveSession.MoveScreenMarker {

    // kit buttons: 0 left, 1 middle, 2 right
    private static final int LEFT = 0;
    private static final int MIDDLE = 1;
    private static final int RIGHT = 2;
    private static final int W = 87;
    private static final int A = 65;
    private static final int S = 83;
    private static final int D = 68;

    private final Minecraft mc = Minecraft.getInstance();
    private final MoveSession session;
    private final McDraw draw = new McDraw();
    private McAtlases atlases;
    private McCanvas atlasCanvas;
    private FallbackCanvas plainCanvas;
    private Canvas canvas;
    private Theme theme;
    private MoveBar bar;
    // the buttons that went down off the bar, one bit each, so their drags and releases go to the camera and not the
    // buttons. a bitmask and not one int: right going down mid arrow drag must not orphan the left release
    private int worldButtons;
    private boolean closing;

    private MoveScreen(MoveSession session) {
        super(Component.literal("soprano move"));
        this.session = session;
        build();
    }

    // from McSchematics.startMoving, a tick after the click so the gui's own click handling is done with it
    public static void open(String path) {
        Minecraft mc = Minecraft.getInstance();
        McSchematics schematics = McSchematics.shared();
        try {
            // the tab's stop moving, or a world change, got there first
            if (!path.equals(schematics.moving())) {
                return;
            }
            MoveSession s = MoveSession.start(path);
            if (s == null) {
                schematics.stopMoving();
                return;
            }
            mc.gui.setScreen(new MoveScreen(s));
        } catch (Throwable t) {
            Safe.report("move screen open", t);
            MoveSession.end();
            schematics.stopMoving();
        }
    }

    private void build() {
        McAtlases at = McAtlases.get();
        float scale = Theme.scaleFor(mc.getWindow().getHeight());
        Settings s = Baritone.settings();
        Anim.enabled = s.guiAnimations.value;
        if (at != null && at.ok) {
            atlases = at;
            atlasCanvas = new McCanvas(at, draw);
            canvas = atlasCanvas;
        } else {
            plainCanvas = new FallbackCanvas(draw);
            canvas = plainCanvas;
        }
        theme = new Theme(scale, canvas);
        theme.setDark(s.guiDarkMode.value);
        canvas.setTheme(theme);
        if (atlases != null) {
            atlases.prewarm(theme);
        }
        bar = new MoveBar(theme, new MoveBar.Actions() {
            @Override
            public void rotate(boolean clockwise) {
                session.rotate(clockwise);
            }

            @Override
            public void mirror() {
                session.mirror();
            }

            @Override
            public void toMe() {
                session.toMe();
            }

            @Override
            public void done() {
                finish();
            }
        });
    }

    // ----- lifecycle

    @Override
    public void tick() {
        try {
            if (!closing && !session.tick()) {
                finish();
            }
        } catch (Throwable t) {
            Safe.report("move screen tick", t);
            finish();
        }
    }

    private void finish() {
        closing = true;
        MoveSession.end();
        if (mc.gui.screen() == this) {
            mc.gui.setScreen(null);
        }
    }

    // every way off this screen (done, esc, the gui key, death, a disconnect, another screen) comes through here
    @Override
    public void removed() {
        MoveSession.end();
        McAtlases.releaseAll();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    // no blur and no dim, the world is what you're here to look at
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
    }

    @Override
    public void resize(int w, int h) {
        this.width = w;
        this.height = h;
    }

    // ----- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        Window win = mc.getWindow();
        int winW = win.getWidth();
        int winH = win.getHeight();
        float inv = 1f / Math.max(1, win.getGuiScale());
        g.pose().pushMatrix();
        g.pose().scale(inv, inv);
        boolean began = false;
        try {
            if (atlasCanvas != null) {
                atlasCanvas.begin(g, winW, winH);
            } else {
                plainCanvas.begin(g, winW, winH);
            }
            began = true;
            long now = System.nanoTime();
            theme.update(now);
            bar.show(session.placement());
            if (bar.w() != winW || bar.h() != winH) {
                bar.setBounds(0, 0, winW, winH);
            }
            float px = pointerX();
            float py = pointerY();
            bar.mouseMove(px, py);
            bar.draw(canvas, new Frame(now, 0f, px, py));
        } catch (Throwable t) {
            Safe.report("move screen render", t);
        } finally {
            try {
                if (began) {
                    if (atlasCanvas != null) {
                        atlasCanvas.end();
                    } else {
                        plainCanvas.end();
                    }
                }
            } catch (Throwable t) {
                Safe.report("move screen render end", t);
            }
            g.pose().popMatrix();
        }
        if (atlasCanvas != null && atlasCanvas.broken) {
            // same way out as GuiScreen: the bar goes plain rather than wordless
            atlasCanvas = null;
            plainCanvas = new FallbackCanvas(draw);
            canvas = plainCanvas;
            canvas.setTheme(theme);
        }
    }

    // ----- input, in framebuffer pixels like the gui

    private float pointerX() {
        Window win = mc.getWindow();
        return (float) (mc.mouseHandler.xpos() * win.getWidth() / Math.max(1, win.getScreenWidth()));
    }

    private float pointerY() {
        Window win = mc.getWindow();
        return (float) (mc.mouseHandler.ypos() * win.getHeight() / Math.max(1, win.getScreenHeight()));
    }

    private double fbW() {
        return mc.getWindow().getWidth();
    }

    private double fbH() {
        return mc.getWindow().getHeight();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        Safe.run("move mouse down", () -> {
            int button = McKeys.buttonToKit(event.button());
            float px = pointerX();
            float py = pointerY();
            if (bar.covers(px, py)) {
                bar.mouseDown(px, py, button, McKeys.mods(event));
                return;
            }
            worldButtons |= 1 << button;
            if (button == LEFT) {
                session.grab(px, py, fbW(), fbH());
            }
        });
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        Safe.run("move mouse up", () -> {
            int button = McKeys.buttonToKit(event.button());
            float px = pointerX();
            float py = pointerY();
            boolean world = (worldButtons & (1 << button)) != 0;
            worldButtons &= ~(1 << button);
            // a left release always lets go of the arrow, wherever the press was routed
            if (button == LEFT && session.dragging()) {
                session.release();
            }
            if (!world) {
                bar.mouseUp(px, py, button, McKeys.mods(event));
            }
        });
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        Safe.run("move mouse drag", () -> {
            int button = McKeys.buttonToKit(event.button());
            if ((worldButtons & (1 << button)) == 0) {
                return;
            }
            double s = mc.getWindow().getGuiScale();
            if (button == LEFT) {
                session.drag(pointerX(), pointerY(), fbW(), fbH());
            } else if (button == RIGHT) {
                session.turn(dx * s, dy * s);
            } else if (button == MIDDLE) {
                session.slide(dx * s, dy * s, fbH());
            }
        });
        return true;
    }

    @Override
    public void mouseMoved(double x, double y) {
        Safe.run("move mouse move", () -> {
            float px = pointerX();
            float py = pointerY();
            session.hover(px, py, fbW(), fbH());
            bar.mouseMove(px, py);
        });
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        Safe.run("move scroll", () -> session.zoom(dy));
        return true;
    }

    // always true, nothing typed here reaches the game. esc and the gui key both leave
    @Override
    public boolean keyPressed(KeyEvent event) {
        Safe.run("move key down", () -> {
            int key = McKeys.toKit(event.key());
            if (key == Keys.ESCAPE) {
                finish();
                return;
            }
            Settings s = Baritone.settings();
            if (s.guiKeyEnabled.value && key == s.guiKey.value && key >= 0) {
                // back to the gui: move mode ends (removed() does it), the tab's move button is how you get back in.
                // straight to the gui, no null screen in between, or the mouse gets grabbed and let go and the
                // cursor jumps to the middle
                closing = true;
                mc.gui.setScreen(new GuiScreen());
                return;
            }
            held(key, true);
        });
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        Safe.run("move key up", () -> held(McKeys.toKit(event.key()), false));
        return true;
    }

    private void held(int key, boolean down) {
        switch (key) {
            case W -> session.held(MoveSession.FORWARD, down);
            case S -> session.held(MoveSession.BACK, down);
            case A -> session.held(MoveSession.LEFT, down);
            case D -> session.held(MoveSession.RIGHT, down);
            case Keys.SPACE -> session.held(MoveSession.UP, down);
            case Keys.LEFT_SHIFT, Keys.RIGHT_SHIFT -> session.held(MoveSession.DOWN, down);
            default -> {
            }
        }
    }
}
