package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.AwtFonts;
import adris.altoclef.ui.gui.kit.DeferredContext;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.GlyphAtlas;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShapeAtlas;
import adris.altoclef.ui.gui.kit.Shell;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.Theme;

import java.awt.FontFormatException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Function;

// renders the real Shell with fake data into build/gui-preview/<name>.png. this is the proof of the look: the
// game backend only copies tinted pixels, so what comes out here is what the player sees
public final class Preview {

    public static final Path OUT = Paths.get("build", "gui-preview");
    public static final long T0 = 10_000_000_000L;
    private static final int WORLD = 0xFF2B3A2E;
    private static final int WORLD_2 = 0xFF203022;

    public final int winW;
    public final int winH;
    public final Theme theme;
    public final GlyphAtlas glyphs;
    public final ShapeAtlas shapes;
    public final FakeFacade facade;
    // hand this to a tab's constructor; it points at the shell once the shell exists
    public final DeferredContext ctx;
    public final Shell shell;
    public float mouseX = -100;
    public float mouseY = -100;
    public long now = T0;

    public Preview(int winW, int winH, FakeFacade facade, Function<Preview, List<Tab>> tabs) throws IOException, FontFormatException {
        this(winW, winH, Theme.scaleFor(winH), facade, tabs);
    }

    // an explicit scale, for renders at a pixel density the window height would not pick on its own (2x product
    // shots: 2160 tall lands on 1.75 through scaleFor)
    public Preview(int winW, int winH, float scale, FakeFacade facade, Function<Preview, List<Tab>> tabs) throws IOException, FontFormatException {
        this.winW = winW;
        this.winH = winH;
        this.facade = facade;
        glyphs = new GlyphAtlas(AwtFonts.load());
        shapes = new ShapeAtlas();
        theme = new Theme(scale, glyphs);
        ctx = new DeferredContext(theme, facade.clipboard());
        // the tabs need the theme and a context, the shell needs the tabs
        shell = new Shell(theme, tabs.apply(this), facade.status(), facade.keyNames(), 344, k -> facade.actions.add("rebind " + k),
                facade.clipboard(), () -> facade.actions.add("close"));
        ctx.attach(shell);
        shell.setViewport(winW, winH);
        // land on the fully open state unless a test asks for a mid-animation frame. the game sends the key up
        // of the opening key right after; without it the shell keeps swallowing that key and its char
        shell.open(now);
        shell.keyUp(344);
        now += Anim.ms(Theme.ANIM_SLOW + 50);
    }

    public Preview(FakeFacade facade, Function<Preview, List<Tab>> tabs) throws IOException, FontFormatException {
        this(1600, 900, facade, tabs);
    }

    public Frame frame() {
        return new Frame(now, 1f / 60f, mouseX, mouseY);
    }

    public void advance(int millis) {
        now += Anim.ms(millis);
    }

    public void hover(float x, float y) {
        mouseX = x;
        mouseY = y;
        shell.mouseMove(x, y);
    }

    public void click(float x, float y) {
        click(x, y, 0);
    }

    public void click(float x, float y, int mods) {
        hover(x, y);
        shell.mouseDown(x, y, 0, mods);
        shell.mouseUp(x, y, 0, mods);
        advance(16);
    }

    public void type(String s) {
        for (char c : s.toCharArray()) {
            shell.charTyped(c);
        }
        advance(16);
    }

    public void key(int key) {
        shell.keyDown(key, 0);
        // a real key press releases too, and the shell relies on the up to clear a key it was swallowing (a
        // rebind, a capture cancel). without it the next typed char vanishes and the search box reads empty
        shell.keyUp(key);
        advance(16);
    }

    public void tick() {
        shell.tick();
    }

    // draws a throwaway frame so things that start their clock on first draw (toasts, row fades) get going
    public void settle() {
        render();
        advance(16);
    }

    // a mouse down without the up, for pressed states and drags
    public void press(float x, float y) {
        hover(x, y);
        shell.mouseDown(x, y, 0, 0);
        advance(16);
    }

    public void release(float x, float y) {
        shell.mouseUp(x, y, 0, 0);
        advance(16);
    }

    // the pointer moving with the button held, after press(): the shell gets a drag, not a move
    public void drag(float x, float y) {
        float dx = x - mouseX;
        float dy = y - mouseY;
        mouseX = x;
        mouseY = y;
        shell.mouseDrag(x, y, 0, dx, dy);
        advance(16);
    }

    public void scroll(float x, float y, float notches) {
        hover(x, y);
        shell.scroll(x, y, notches);
        advance(16);
    }

    public Java2dCanvas render() {
        return render(true);
    }

    // world false leaves everything outside the panel transparent: no stripes, no scrim, just the panel and its
    // shadow on alpha, for dropping onto a page
    public Java2dCanvas render(boolean world) {
        Java2dCanvas c = new Java2dCanvas(winW, winH, glyphs, shapes);
        c.setTheme(theme);
        theme.update(now);
        if (world) {
            // a dim green world behind the scrim so the panel edge and shadow are judged against something
            c.clear(WORLD);
            for (int i = 0; i < winW; i += 64) {
                c.fillRect(i, 0, 32, winH, WORLD_2);
            }
            c.fillRect(0, 0, winW, winH, theme.color(Theme.SCRIM));
        } else {
            c.clear(0);
        }
        int before = Safe.reports();
        shell.mouseMove(mouseX, mouseY);
        shell.draw(c, frame());
        // the shell swallows draw exceptions so the game never crashes; a preview must not swallow them too,
        // or a broken tab saves a quietly wrong png
        if (Safe.reports() != before) {
            throw new AssertionError("a draw or input handler threw during the preview: " + Safe.lastReport());
        }
        return c;
    }

    public Path save(String name) throws IOException {
        Files.createDirectories(OUT);
        Path p = OUT.resolve(name + ".png");
        render().save(p);
        return p;
    }

    // crops the panel out at 1:1 so the png is the panel and not a screenful of scrim
    public Path savePanel(String name) throws IOException {
        return savePanel(name, true);
    }

    // the same crop with a transparent surround instead of the painted world
    public Path savePanelCutout(String name) throws IOException {
        return savePanel(name, false, 24);
    }

    // a cutout with a margin wide enough for the whole shadow (it reaches about 40 design px below the panel), for
    // shots that go on a page. name may have a subdirectory in it
    public Path savePanelCutout(String name, int padDesign) throws IOException {
        return savePanel(name, false, theme.px(padDesign));
    }

    private Path savePanel(String name, boolean world) throws IOException {
        return savePanel(name, world, 24);
    }

    private Path savePanel(String name, boolean world, int pad) throws IOException {
        Path p = OUT.resolve(name + ".png");
        Files.createDirectories(p.getParent());
        Java2dCanvas c = render(world);
        int x = Math.max(0, shell.x() - pad);
        int y = Math.max(0, shell.y() - pad);
        int w = Math.min(winW - x, shell.w() + 2 * pad);
        int h = Math.min(winH - y, shell.h() + 2 * pad);
        javax.imageio.ImageIO.write(c.image().getSubimage(x, y, w, h), "png", p.toFile());
        return p;
    }
}
