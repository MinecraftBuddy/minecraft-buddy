package adris.altoclef.ui.gui.mc.move;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Placement;
import adris.altoclef.ui.gui.kit.AwtFonts;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.GlyphAtlas;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShapeAtlas;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.preview.Java2dCanvas;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MoveBarTest {

    private final List<String> actions = new ArrayList<>();
    private GlyphAtlas glyphs;
    private ShapeAtlas shapes;
    private Theme theme;
    private MoveBar bar;

    private void open(int w, int h) throws Exception {
        glyphs = new GlyphAtlas(AwtFonts.load());
        shapes = new ShapeAtlas();
        theme = new Theme(Theme.scaleFor(h), glyphs);
        bar = new MoveBar(theme, new MoveBar.Actions() {
            @Override
            public void rotate(boolean clockwise) {
                actions.add(clockwise ? "cw" : "ccw");
            }

            @Override
            public void mirror() {
                actions.add("mirror");
            }

            @Override
            public void toMe() {
                actions.add("feet");
            }

            @Override
            public void done() {
                actions.add("done");
            }
        });
        bar.setBounds(0, 0, w, h);
        bar.show(new Placement(100, 64, -40, 1, true, "minecraft:overworld", true));
    }

    private void click(Button b) {
        float x = b.x() + b.w() / 2f;
        float y = b.y() + b.h() / 2f;
        assertTrue(bar.covers(x, y));
        bar.mouseMove(x, y);
        bar.mouseDown(x, y, 0, 0);
        bar.mouseUp(x, y, 0, 0);
    }

    private Path save(String name, int w, int h) throws Exception {
        Java2dCanvas c = new Java2dCanvas(w, h, glyphs, shapes);
        c.setTheme(theme);
        c.clear(0xFF5B7F4A);
        int before = Safe.reports();
        bar.draw(c, new Frame(Preview.T0, 1f / 60f, -100, -100));
        assertEquals("the bar threw while drawing: " + Safe.lastReport(), before, Safe.reports());
        Files.createDirectories(Preview.OUT);
        Path p = Preview.OUT.resolve(name + ".png");
        c.save(p);
        return p;
    }

    @Test
    public void labelSaysWhereAndHow() {
        assertEquals("x 100  y 64  z -40 · turned 90° · mirrored",
            MoveBar.label(new Placement(100, 64, -40, 1, true, "minecraft:overworld", true)));
        assertEquals("x 0  y -3  z 7", MoveBar.label(new Placement(0, -3, 7, 0, false, "minecraft:overworld", true)));
        assertEquals("", MoveBar.label(null));
    }

    @Test
    public void everyButtonFiresItsAction() throws Exception {
        open(1600, 900);
        click(bar.control("turnLeft"));
        click(bar.control("turnRight"));
        click(bar.control("mirror"));
        click(bar.control("feet"));
        click(bar.control("done"));
        assertEquals(List.of("ccw", "cw", "mirror", "feet", "done"), actions);
        assertEquals("Mirror", bar.control("mirror").text());
        assertEquals("Move to me", bar.control("feet").text());
        // the world around the card is the camera's, not the bar's
        assertFalse(bar.covers(10, 10));
        assertFalse(bar.covers(800, 450));
        assertTrue(Files.size(save("move_bar", 1600, 900)) > 1000);
    }

    @Test
    public void aNarrowWindowDropsTheWordsAndKeepsEverything() throws Exception {
        open(480, 400);
        for (String name : List.of("turnLeft", "turnRight", "mirror", "feet", "done")) {
            Button b = bar.control(name);
            assertTrue(name, b.x() >= 0 && b.x() + b.w() <= 480);
        }
        assertEquals("", bar.control("mirror").text());
        assertEquals("", bar.control("feet").text());
        assertEquals("Done", bar.control("done").text());
        click(bar.control("done"));
        assertEquals(List.of("done"), actions);
        assertTrue(Files.size(save("move_bar_narrow", 480, 400)) > 1000);
    }
}
