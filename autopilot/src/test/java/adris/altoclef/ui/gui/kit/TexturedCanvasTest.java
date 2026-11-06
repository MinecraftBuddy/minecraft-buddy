package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.ui.gui.preview.Java2dCanvas;

import java.awt.FontFormatException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class TexturedCanvasTest {

    private static final int BG = 0xFF0A0A0B;

    private GlyphAtlas glyphs;
    private ShapeAtlas shapes;
    private Theme theme;

    @Before
    public void setUp() throws IOException, FontFormatException {
        glyphs = new GlyphAtlas(AwtFonts.load());
        shapes = new ShapeAtlas();
        theme = new Theme(1f, glyphs);
    }

    private Java2dCanvas canvas(int w, int h) {
        Java2dCanvas c = new Java2dCanvas(w, h, glyphs, shapes);
        c.clear(BG);
        return c;
    }

    private static int px(Java2dCanvas c, int x, int y) {
        return c.image().getRGB(x, y);
    }

    // text centred on a box's middle line, the way every widget will do it
    private float centredText(Java2dCanvas c, String s, TextStyle st, float x, float y, float h, int argb) {
        float base = y + (h - (c.ascent(st) + c.descent(st))) * 0.5f + c.ascent(st);
        return c.text(s, x, base, st, argb);
    }

    private void button(Java2dCanvas c, String s, float x, float y, int fill, int hairline, int textColor) {
        float w = c.measure(s, theme.label) + 28;
        if (fill != 0) {
            c.fillRoundRect(x, y, w, 30, 6, fill);
        }
        if (hairline != 0) {
            c.strokeRoundRect(x, y, w, 30, 6, hairline);
        }
        centredText(c, s, theme.label, x + 14, y, 30, textColor);
    }

    @Test
    public void pathDotsGetARingOnlyWhenTheGlyphIsDrawnInOnAccent() {
        int size = 48;
        float cx = 10 + Icons.toPx(size, 5);
        float cy = 10 + Icons.toPx(size, 19);
        // six pixels above the start dot's centre: outside the dot itself, inside where a ring would be, and clear of
        // the path, which leaves the dot to the right
        int x = Math.round(cx);
        int y = Math.round(cy) - 6;
        Java2dCanvas plain = canvas(80, 80);
        plain.icon(Icon.GOTO, 10, 10, size, Theme.TEXT_2);
        assertEquals("no ring on a neutral surface", BG, px(plain, x, y));
        Java2dCanvas lit = canvas(80, 80);
        lit.icon(Icon.GOTO, 10, 10, size, Theme.ON_ACCENT);
        int p = px(lit, x, y);
        assertTrue("ring in the glyph's white on the accent", (p & 255) > 200 && ((p >> 8) & 255) > 200 && ((p >> 16) & 255) > 200);
        // and the dot itself is still green
        int d = px(lit, Math.round(cx), Math.round(cy));
        assertTrue("start dot stays green", ((d >> 8) & 255) > (d & 255) + 40 && ((d >> 8) & 255) > ((d >> 16) & 255) + 40);
    }

    // the whole family at every size the gui draws it, zoomed, so a heavy 12px or a drifting dot is visible at a
    // glance. one row per size, every icon in enum order
    private void iconSheet() throws IOException {
        Icon[] all = Icon.values();
        int[] sizes = {12, 16, 22, 24, 32};
        int cell = 40;
        int rows = sizes.length;
        Java2dCanvas c = canvas(cell * all.length + 20, cell * rows + 20);
        for (int r = 0; r < rows; r++) {
            int s = sizes[r];
            for (int i = 0; i < all.length; i++) {
                c.icon(all[i], 10 + i * cell + (cell - s) / 2f, 10 + r * cell + (cell - s) / 2f, s, Theme.TEXT);
            }
        }
        c.save(Paths.get("build", "gui-preview", "_smoke_icon_sheet.png"));
        zoom(c.image(), 0, 0, c.width() / 2, c.height(), 3, Paths.get("build", "gui-preview", "_smoke_icon_sheet_a.png"));
        zoom(c.image(), c.width() / 2, 0, c.width() - c.width() / 2, c.height(), 3, Paths.get("build", "gui-preview", "_smoke_icon_sheet_b.png"));
    }

    @Test
    public void smoke() throws IOException {
        Java2dCanvas c = canvas(900, 560);

        c.shadow(20, 20, 860, 520, 10, Theme.SHADOW);
        c.fillRoundRect(20, 20, 860, 520, 10, Theme.PANEL);
        c.strokeRoundRect(20, 20, 860, 520, 10, Theme.HAIRLINE);

        // type scale
        float x = 48;
        c.text("Get", x, 64, theme.title, Theme.TEXT);
        c.text("Label medium 13, Waypoints", x, 94, theme.label, Theme.TEXT);
        c.text("Body regular 13: Café · 1 234 … “quoted” ’ – 5 × 3 and some more words",
                x, 118, theme.body, Theme.TEXT);
        c.text("Small regular 11, secondary text", x, 140, theme.small, Theme.TEXT_2);
        c.text("SMALL LABEL MEDIUM 11", x, 160, theme.smallLabel, Theme.TEXT_3);
        c.text("The quick brown fox jumps over the lazy dog 0123456789", x, 184, theme.body, Theme.TEXT_2);
        c.text("AV To We Ty kerning check", x, 206, theme.body, Theme.TEXT_2);
        float fallbackEnd = c.text("odd name 中文 ok", x, 228, theme.body, Theme.TEXT);
        // the preview feeds its fallback font's widths back to the atlas, so layout and ink end in the same place
        assertEquals(c.measure("odd name 中文 ok", theme.body), fallbackEnd, 0.01f);
        assertTrue(fallbackEnd > glyphs.measure("odd name ok", theme.body) + 2 * glyphs.missingAdvance(theme.body));

        // tabular coordinates, right aligned in three columns, must line up digit for digit
        TextStyle tab = theme.body.asTabular();
        float[] cols = {x + 60, x + 110, x + 170};
        String[][] rows = {{"120", "64", "-30"}, {"7", "-58", "1000"}, {"-1234", "0", "99"}};
        float ty = 262;
        for (String[] row : rows) {
            for (int i = 0; i < 3; i++) {
                c.text(row[i], cols[i] - c.measure(row[i], tab), ty, tab, Theme.TEXT);
            }
            ty += 20;
        }
        // same numbers proportional, for comparison
        ty = 262;
        for (String[] row : rows) {
            for (int i = 0; i < 3; i++) {
                c.text(row[i], cols[i] + 150 - c.measure(row[i], theme.body), ty, theme.body, Theme.TEXT_2);
            }
            ty += 20;
        }

        // icons, 16 then 20
        Icon[] all = Icon.values();
        for (int i = 0; i < all.length; i++) {
            c.icon(all[i], x + i * 30, 358, 16, Theme.TEXT);
            c.icon(all[i], x + i * 30, 386, 20, Theme.TEXT);
            c.icon(all[i], x + i * 30, 418, 16, Theme.TEXT_3);
        }
        c.icon(Icon.DOT, x, 446, 12, Theme.ACCENT);
        c.icon(Icon.RING, x + 20, 446, 12, Theme.HAIRLINE_STRONG);
        c.icon(Icon.TRASH, x + 40, 444, 16, Theme.DANGER);

        // buttons
        button(c, "Get 67 items", x, 470, Theme.ACCENT, 0, Theme.ON_ACCENT);
        button(c, "Follow Steve", x + 130, 470, Theme.RAISED, 0, Theme.TEXT);
        button(c, "Reset", x + 260, 470, 0, Theme.HAIRLINE_STRONG, Theme.TEXT_2);
        button(c, "Stop", x + 330, 470, Theme.DANGER_FILL, 0, Theme.DANGER);

        // right side: a card with rows, a slot, a toast
        float rx = 520;
        c.fillRoundRect(rx, 48, 330, 200, 8, Theme.RAISED);
        c.strokeRoundRect(rx, 48, 330, 200, 8, Theme.HAIRLINE);
        c.text("Basket", rx + 16, 76, theme.label, Theme.TEXT);
        for (int i = 0; i < 4; i++) {
            float ry = 90 + i * 36;
            if (i == 1) {
                c.fillRoundRect(rx + 8, ry, 314, 36, 4, Theme.HOVER);
            }
            c.itemIcon("minecraft:oak_log", rx + 16, ry + 6, 24);
            centredText(c, i % 2 == 0 ? "oak_log" : "iron_ingot", theme.body, rx + 50, ry, 36, Theme.TEXT);
            String n = String.valueOf(64 - i * 17);
            c.text(n, rx + 300 - c.measure(n, tab), ry + 23, tab, Theme.TEXT_2);
        }
        c.fillRoundRect(rx, 262, 330, 44, 6, Theme.RAISED);
        c.strokeRoundRect(rx, 262, 330, 44, 6, Theme.HAIRLINE);
        c.text("Followed Steve", rx + 14, 281, theme.body, Theme.TEXT);
        c.text("#follow Steve", rx + 14, 297, theme.small, Theme.TEXT_2);

        // a search-ish field
        c.fillRoundRect(rx, 316, 330, 30, 6, Theme.SIDEBAR);
        c.strokeRoundRect(rx, 316, 330, 30, 6, Theme.HAIRLINE_STRONG);
        c.icon(Icon.SEARCH, rx + 10, 323, 16, Theme.TEXT_3);
        centredText(c, "Search items", theme.body, rx + 34, 316, 30, Theme.TEXT_3);

        // slots (real textures when the game jar is on the classpath, a letter tile when nothing resolves),
        // and a clipped line that must not leak
        c.itemIcon("minecraft:diamond", rx, 452, 32);
        c.itemIcon("minecraft:diamond", rx + 44, 452, 48);
        c.itemIcon("minecraft:dirt", rx + 100, 452, 16);
        c.itemIcon("minecraft:iron_sword", rx + 124, 452, 16);
        c.itemIcon("minecraft:chest", rx + 148, 452, 16);
        c.itemIcon("minecraft:zombie_spawn_egg", rx + 172, 452, 16);
        c.itemIcon("soprano:nothing_here", rx + 196, 452, 16);
        // the go to glyph on both surfaces and both tints, plus big for the zoom
        c.icon(Icon.GOTO, rx + 230, 452, 16, Theme.TEXT);
        c.icon(Icon.GOTO, rx + 254, 452, 16, Theme.TEXT_2);
        c.icon(Icon.GOTO, rx + 278, 448, 24, Theme.TEXT);
        c.icon(Icon.GOTO, rx + 306, 444, 32, Theme.TEXT);
        c.pushClip(rx + 110, 500, 120, 24);
        c.text("this text is clipped at the right edge of the clip box", rx + 110, 516, theme.body, Theme.TEXT);
        c.popClip();

        Path out = Paths.get("build", "gui-preview", "_smoke.png");
        c.save(out);
        iconSheet();
        zoom(c.image(), 36, 36, 250, 200, 4, Paths.get("build", "gui-preview", "_smoke_text_zoom.png"));
        zoom(c.image(), 40, 352, 330, 78, 4, Paths.get("build", "gui-preview", "_smoke_icons_zoom.png"));
        zoom(c.image(), 370, 352, 340, 78, 4, Paths.get("build", "gui-preview", "_smoke_icons2_zoom.png"));
        zoom(c.image(), 500, 40, 380, 120, 3, Paths.get("build", "gui-preview", "_smoke_card_zoom.png"));
        zoom(c.image(), 36, 440, 480, 70, 3, Paths.get("build", "gui-preview", "_smoke_buttons_zoom.png"));
        zoom(c.image(), 516, 440, 350, 50, 4, Paths.get("build", "gui-preview", "_smoke_items_goto_zoom.png"));

        // panel centre is panel, the rounded corner is not, the far corner of the image is untouched background
        assertEquals(Theme.PANEL, px(c, 450, 300));
        assertNotEquals(Theme.PANEL, px(c, 20, 20));
        assertEquals(BG, px(c, 0, 0));
        assertEquals(BG, px(c, 899, 559));
        // the hairline is one pixel: on the edge row, not the row inside
        assertEquals(Theme.HAIRLINE, px(c, 450, 20));
        assertEquals(Theme.PANEL, px(c, 450, 21));
        // the shadow darkens the margin under the panel but not past its reach
        int below = px(c, 450, 543) & 0xFF;
        assertTrue("shadow under the panel should be darker than the background", below < (BG & 0xFF));
        // text drew something non-background inside its box
        assertTrue(countNot(c, (int) x, 44, 120, 24, Theme.PANEL) > 40);
        // clipped text did not leak past the clip
        assertEquals(Theme.PANEL, px(c, (int) rx + 250, 460));
    }

    @Test
    public void tabularColumnsAlignPixelForPixel() {
        Java2dCanvas c = canvas(200, 80);
        TextStyle tab = theme.body.asTabular();
        c.text("1111", 10, 30, tab, 0xFFFFFFFF);
        c.text("0000", 10, 60, tab, 0xFFFFFFFF);
        // each digit cell is the same width, so the ink of the last digit ends in the same cell for both rows
        float cell = c.measure("1", tab);
        assertEquals(4 * cell, c.measure("0000", tab), 0.01f);
        int lastCellStart = Math.round(10 + 3 * cell);
        int lastCellEnd = Math.round(10 + 4 * cell);
        for (int row : new int[]{30, 60}) {
            boolean ink = false;
            for (int y = row - 10; y < row; y++) {
                for (int x = lastCellStart; x < lastCellEnd; x++) {
                    if (px(c, x, y) != BG) {
                        ink = true;
                    }
                }
            }
            assertTrue("last digit of the row at " + row + " should sit in the last cell", ink);
        }
    }

    @Test
    public void shadowStretchesOverSmallBoxes() {
        Java2dCanvas c = canvas(120, 120);
        c.clear(0xFFFFFFFF);
        // smaller than two corners; must not blow up, and 50 percent black on white can never get darker than
        // 50 percent unless two slices landed on the same pixel
        int half = 0x80000000;
        c.shadow(50, 50, 6, 6, 3, half);
        c.shadow(50, 50, 0.4f, 0.4f, 3, half);
        c.shadow(10, 10, 1, 1, 0, half);
        for (int y = 0; y < 120; y++) {
            for (int x = 0; x < 120; x++) {
                assertTrue("double darkened at " + x + "," + y, (px(c, x, y) & 0xFF) >= 0x7E);
            }
        }
        c.fillRoundRect(10, 10, 1, 1, 8, 0xFFFFFFFF);
        c.strokeRoundRect(10, 10, 2, 2, 8, 0xFFFFFFFF);
        c.fillRoundRect(10, 10, 0, 0, 8, 0xFFFFFFFF);
    }

    @Test
    public void roundRectRadiusIsClamped() {
        Java2dCanvas c = canvas(100, 60);
        // radius way past half the height turns into a pill, ends are round and the middle is solid
        c.fillRoundRect(10, 10, 80, 20, 500, 0xFFFFFFFF);
        assertEquals(0xFFFFFFFF, px(c, 50, 20));
        assertEquals(BG, px(c, 10, 10));
        assertEquals(BG, px(c, 89, 29));
        assertTrue((px(c, 20, 20) & 0xFFFFFF) > 0x808080);
    }

    @Test
    public void translucentFillDoesNotDoubleUpAtSeams() {
        Java2dCanvas c = canvas(80, 60);
        int tint = 0x80FFFFFF;
        c.fillRoundRect(10, 10, 60, 40, 8, tint);
        int mid = px(c, 40, 30);
        assertEquals(mid, px(c, 40, 12));
        assertEquals(mid, px(c, 40, 47));
        assertEquals(mid, px(c, 12, 30));
        // corner seam: first solid pixel past the curve matches too
        assertEquals(mid, px(c, 18, 10));
    }

    // nearest neighbour blow up of a crop, because judging a 1px stroke at 1x on a monitor is guessing
    private static void zoom(BufferedImage src, int x, int y, int w, int h, int k, Path png) throws IOException {
        BufferedImage big = new BufferedImage(w * k, h * k, BufferedImage.TYPE_INT_ARGB);
        for (int yy = 0; yy < h * k; yy++) {
            for (int xx = 0; xx < w * k; xx++) {
                big.setRGB(xx, yy, src.getRGB(x + xx / k, y + yy / k));
            }
        }
        java.nio.file.Files.createDirectories(png.toAbsolutePath().getParent());
        javax.imageio.ImageIO.write(big, "png", png.toFile());
    }

    private static int countNot(Java2dCanvas c, int x, int y, int w, int h, int colour) {
        BufferedImage img = c.image();
        int n = 0;
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                if (img.getRGB(xx, yy) != colour) {
                    n++;
                }
            }
        }
        return n;
    }
}
