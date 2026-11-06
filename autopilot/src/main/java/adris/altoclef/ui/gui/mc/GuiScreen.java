package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.DeferredContext;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.Shell;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.tabs.Tabs;
import baritone.Baritone;
import baritone.api.Settings;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

// the click gui as a vanilla screen: all the kit gets from here is a canvas, input in framebuffer pixels and the time.
// everything the game does with a screen (pause, esc, tab focus, narration) is switched off or ignored, except the
// menu blur, which we ask for ourselves.
//
// keybinds: MixinMinecraft.passEvents hands Minecraft.tick a null screen while pathing so handleKeybinds still runs,
// but those only fire from KeyMapping.click, which only happens with no screen open. typing in here cannot leak
public class GuiScreen extends Screen {

    public interface TabFactory {
        List<Tab> create(BotFacade facade, Theme theme, DeferredContext ctx);
    }

    // the tabs' door: given the facade, the theme and a context that reaches the shell once it exists, hands back the
    // sidebar. a direct reference, not a lookup by name: nothing else points at Tabs and a shrinker would drop it
    public static TabFactory tabFactory = Tabs::create;

    private final Minecraft mc = Minecraft.getInstance();
    private final McDraw draw = new McDraw();
    private final Anim scrim = new Anim(0f);

    private McAtlases atlases;
    private McCanvas atlasCanvas;
    private FallbackCanvas plainCanvas;
    private Canvas canvas;
    private Theme theme;
    private BotFacade facade;
    private Shell shell;

    private boolean plainOnly;
    private boolean rebuild;
    private boolean opened;
    private boolean closeNow;
    private long lastNanos;
    private float failedScale = -1f;
    private int badFrames;

    public GuiScreen() {
        super(Component.literal("soprano"));
        build(mc.getWindow().getHeight());
    }

    // ----- building

    // the fields only change once the whole set exists, so a throw half way leaves the old screen as it was
    private void build(int windowHeight) {
        McAtlases at = plainOnly ? null : McAtlases.get();
        McCanvas ac = null;
        FallbackCanvas pc = null;
        Canvas c;
        if (at != null && at.ok) {
            ac = new McCanvas(at, draw);
            c = ac;
        } else {
            pc = new FallbackCanvas(draw);
            c = pc;
        }
        Settings s = Baritone.settings();
        Anim.enabled = s.guiAnimations.value;
        Theme t = new Theme(Theme.scaleFor(windowHeight), c);
        t.setDark(s.guiDarkMode.value);
        c.setTheme(t);
        if (ac != null) {
            at.prewarm(t);
        }
        BotFacade f = McFacade.create();
        // the tabs go in the shell's constructor but want the shell for toasts, so they get a context that is
        // pointed at it right after
        DeferredContext ctx = new DeferredContext(t, f.clipboard());
        Shell sh = new Shell(t, makeTabs(f, t, ctx), f.status(), f.keyNames(), s.guiKey.value, this::rebind,
                f.clipboard(), this::closeRequested);
        ctx.attach(sh);
        sh.setOpenKeyEnabled(s.guiKeyEnabled.value);
        sh.setOnAppearanceChanged(dark -> {
            Result result = f.settings().set("guiDarkMode", Boolean.toString(dark));
            if (result != null && !result.ok()) {
                sh.setDarkMode(s.guiDarkMode.value);
                sh.toast(result);
            }
        });

        atlases = at;
        atlasCanvas = ac;
        plainCanvas = pc;
        canvas = c;
        theme = t;
        facade = f;
        shell = sh;
        failedScale = -1f;
    }

    private static List<Tab> makeTabs(BotFacade f, Theme th, DeferredContext ctx) {
        try {
            List<Tab> tabs = tabFactory.create(f, th, ctx);
            return tabs == null ? List.of() : tabs;
        } catch (Throwable t) {
            Safe.report("gui tabs", t);
            return List.of();
        }
    }

    // a different gui scale (the window got a lot taller or shorter) needs every size redone, which the theme bakes in.
    // the active tab comes along, what was typed in it does not
    private void swapBuild(int windowHeight, long now) {
        Shell old = shell;
        int tab = old.activeIndex();
        boolean wasClosing = old.closing();
        try {
            build(windowHeight);
        } catch (Throwable t) {
            // keep drawing the old one, and do not try this size again every frame
            Safe.report("gui rebuild", t);
            failedScale = Theme.scaleFor(windowHeight);
            return;
        }
        Safe.run("gui dispose", old::dispose);
        shell.switchTab(tab);
        shell.open(now);
        if (wasClosing) {
            shell.close(now);
        }
    }

    // the key was captured by the footer control: same road as typing #set guiKey, then say so
    private void rebind(int key) {
        Result r = facade.settings().set("guiKey", Integer.toString(key));
        shell.toast(r);
    }

    private void closeRequested() {
        closeNow = true;
    }

    // ----- lifecycle

    @Override
    protected void init() {
        if (!opened) {
            opened = true;
            long now = System.nanoTime();
            lastNanos = now;
            scrim.snap(0f);
            scrim.set(1f, now, Theme.ANIM_SLOW);
            shell.open(now);
            // sdl only sends characters while text input is on, and vanilla turns it on per edit box. ours are not edit
            // boxes it knows about, so it stays on for as long as the screen is up (setScreen turns it off again)
            mc.textInputManager().startTextInput(this);
        }
    }

    @Override
    public void tick() {
        try {
            syncPreferences();
            shell.tick();
            // the close request only fires from inside a draw that works, this covers a screen that cannot draw
            if (shell.closed(System.nanoTime())) {
                closeNow = true;
            }
            if (closeNow && mc.gui.screen() == this) {
                mc.gui.setScreen(null);
            }
        } catch (Throwable t) {
            Safe.report("gui tick", t);
        }
    }

    @Override
    public void removed() {
        try {
            shell.dispose();
        } catch (Throwable t) {
            Safe.report("gui dispose", t);
        }
        // not through our own reference: after a switch to the plain canvas that is null and the textures are not
        McAtlases.releaseAll();
    }

    // vanilla's background would blur the world and then paint its dim texture on top. we want the blur and not the
    // texture, so this asks for the blur alone and the scrim is ours. the game calls this from
    // extractRenderStateWithTooltipAndSubtitles on a fresh stratum right before extractRenderState: the blur covers
    // everything in the strata before it (the world and the hud) and leaves the ones after it sharp, and it quietly does
    // nothing when the menu blur option is at 0
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        extractBlurredBackground(g);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    // the viewport is read from the window every frame, so all a resize has to do is not rebuild the screen
    @Override
    public void resize(int w, int h) {
        this.width = w;
        this.height = h;
    }

    // files dropped on the window land in the schematics folder whichever tab is showing, and the toast says what
    // happened. the schematics tab sees the new rows by itself, the import refreshes the list
    @Override
    public void onFilesDrop(List<java.nio.file.Path> files) {
        Safe.run("gui drop", () -> shell.toast(facade.schematics().importDropped(files).toResult()));
    }

    // ----- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        Window win = mc.getWindow();
        int winW = win.getWidth();
        int winH = win.getHeight();
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;

        boolean began = false;
        // the kit works in real pixels, so the whole screen draws under 1/guiScale
        float inv = 1f / Math.max(1, win.getGuiScale());
        g.pose().pushMatrix();
        g.pose().scale(inv, inv);
        try {
            float wanted = Theme.scaleFor(winH);
            if (rebuild || (wanted != theme.scale && wanted != failedScale)) {
                rebuild = false;
                swapBuild(winH, now);
            }

            begin(g, winW, winH);
            began = true;

            scrim.set(shell.closing() ? 0f : 1f, now, Theme.ANIM_SLOW);
            theme.update(now);
            float s = scrim.value(now);
            if (s > 0.003f) {
                canvas.fillRect(0, 0, winW, winH, Theme.fade(theme.color(Theme.SCRIM), s));
            }

            syncPreferences();
            shell.setViewport(winW, winH);
            float px = pointerX();
            float py = pointerY();
            shell.mouseMove(px, py);
            shell.draw(canvas, new Frame(now, dt, px, py));
            badFrames = 0;
        } catch (Throwable t) {
            Safe.report("gui render", t);
            // a screen that cannot draw and eats every key is worse than no screen
            if (++badFrames >= 60) {
                closeNow = true;
            }
        } finally {
            try {
                if (began) {
                    end();
                }
            } catch (Throwable t) {
                Safe.report("gui render end", t);
            }
            g.pose().popMatrix();
        }

        if (atlasCanvas != null && atlasCanvas.broken) {
            // the atlas would not upload, go plain rather than draw a panel with no words in it
            plainOnly = true;
            rebuild = true;
        }
        if (closeNow && mc.gui.screen() == this) {
            mc.gui.setScreen(null);
        }
    }

    private void begin(GuiGraphicsExtractor g, int w, int h) {
        if (atlasCanvas != null) {
            atlasCanvas.begin(g, w, h);
        } else {
            plainCanvas.begin(g, w, h);
        }
    }

    private void end() {
        if (atlasCanvas != null) {
            atlasCanvas.end();
        } else {
            plainCanvas.end();
        }
    }

    // the settings tab and #set can change these while the panel is open
    private void syncPreferences() {
        Settings s = Baritone.settings();
        if (shell.openKey() != s.guiKey.value && !shell.keyCapture().capturing()) {
            shell.setOpenKey(s.guiKey.value);
        }
        shell.setOpenKeyEnabled(s.guiKeyEnabled.value);
        Anim.enabled = s.guiAnimations.value;
        if (theme.dark() != s.guiDarkMode.value) {
            shell.setDarkMode(s.guiDarkMode.value);
        }
    }

    // ----- input. the game hands us gui units, the kit lives in framebuffer pixels

    // the cursor in framebuffer pixels, from the same place render reads it. the x and y the game passes in are gui
    // units, and multiplying those back up lands a few pixels off at the far edge of the window
    private float pointerX() {
        Window win = mc.getWindow();
        return (float) (mc.mouseHandler.xpos() * win.getWidth() / Math.max(1, win.getScreenWidth()));
    }

    private float pointerY() {
        Window win = mc.getWindow();
        return (float) (mc.mouseHandler.ypos() * win.getHeight() / Math.max(1, win.getScreenHeight()));
    }

    private double scale() {
        return mc.getWindow().getGuiScale();
    }

    // the game counts buttons from 1 (left, middle, right), the kit from 0. McKeys does the remap
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        guarded("mouse down", () -> shell.mouseDown(pointerX(), pointerY(), McKeys.buttonToKit(event.button()), McKeys.mods(event)));
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        guarded("mouse up", () -> shell.mouseUp(pointerX(), pointerY(), McKeys.buttonToKit(event.button()), McKeys.mods(event)));
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double s = scale();
        guarded("mouse drag", () -> shell.mouseDrag(pointerX(), pointerY(), McKeys.buttonToKit(event.button()),
                (float) (dx * s), (float) (dy * s)));
        return true;
    }

    @Override
    public void mouseMoved(double x, double y) {
        guarded("mouse move", () -> shell.mouseMove(pointerX(), pointerY()));
    }

    // wheel up is positive in the game and in the kit
    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        guarded("scroll", () -> shell.scroll(pointerX(), pointerY(), (float) dy));
        return true;
    }

    // always true: a key the shell did not want still must not reach the game or the default screen handling
    // event.key() is an sdl scancode (escape is 41), the kit wants glfw's numbers (escape is 256). the modifiers come in
    // the event, McKeys turns them into the kit's bits
    @Override
    public boolean keyPressed(KeyEvent event) {
        guarded("key down", () -> shell.keyDown(McKeys.toKit(event.key()), McKeys.mods(event)));
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        guarded("key up", () -> shell.keyUp(McKeys.toKit(event.key())));
        return true;
    }

    // the game hands over a code point now, the shell still takes the utf-16 chars it always got
    @Override
    public boolean charTyped(CharacterEvent event) {
        guarded("char", () -> {
            for (char c : Character.toChars(event.codepoint())) {
                shell.charTyped(c);
            }
        });
        return true;
    }

    private static void guarded(String where, Runnable r) {
        Safe.run(where, r);
    }
}
