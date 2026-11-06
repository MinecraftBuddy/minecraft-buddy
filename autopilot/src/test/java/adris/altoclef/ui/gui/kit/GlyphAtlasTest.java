package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.FontFormatException;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;

public class GlyphAtlasTest {

    private static final TextStyle BODY = new TextStyle(Weight.REGULAR, 13);

    private GlyphAtlas atlas;

    @Before
    public void setUp() throws IOException, FontFormatException {
        atlas = new GlyphAtlas(AwtFonts.load());
    }

    @Test
    public void emptyStringHasNoWidth() {
        assertEquals(0f, atlas.measure("", BODY), 0f);
        assertEquals(0f, atlas.measure(null, BODY), 0f);
    }

    @Test
    public void narrowLettersMeasureNarrower() {
        assertTrue(atlas.measure("iii", BODY) < atlas.measure("mmm", BODY));
    }

    @Test
    public void kerningPullsPairsTogether() {
        float apart = atlas.measure("A", BODY) + atlas.measure("V", BODY);
        assertTrue("AV should be tighter than A + V", atlas.measure("AV", BODY) < apart);
    }

    @Test
    public void tabularDigitsShareOneAdvance() {
        TextStyle tab = BODY.asTabular();
        float one = atlas.measure("1", tab);
        for (char d = '0'; d <= '9'; d++) {
            assertEquals("digit " + d, one, atlas.measure(String.valueOf(d), tab), 0.001f);
        }
        assertEquals(atlas.measure("0000", tab), atlas.measure("1111", tab), 0.001f);
        // some faces already have equal digit advances; either way tabular layout must preserve the widest one
        for (char d = '0'; d <= '9'; d++) {
            assertTrue(one >= atlas.measure(String.valueOf(d), BODY));
        }
    }

    @Test
    public void tabularDigitIsCentredInItsCell() {
        TextStyle tab = BODY.asTabular();
        GlyphAtlas.Shaped one = atlas.shape("1", tab);
        GlyphAtlas.Shaped zero = atlas.shape("0", tab);
        GlyphAtlas.Glyph g1 = one.glyph(0);
        GlyphAtlas.Glyph g0 = zero.glyph(0);
        float mid1 = one.x(0) + g1.bearingX() + g1.sw() * 0.5f;
        float mid0 = zero.x(0) + g0.bearingX() + g0.sw() * 0.5f;
        // within a pixel and a bit: ink is not always centred on its own advance
        assertEquals(mid0, mid1, 1.5f);
    }

    @Test
    public void metricsArePositiveWholePixels() {
        for (Weight w : Weight.values()) {
            TextStyle s = new TextStyle(w, 13);
            assertTrue(atlas.ascent(s) > 0);
            assertTrue(atlas.descent(s) > 0);
            assertEquals(Math.rint(atlas.ascent(s)), atlas.ascent(s), 0f);
            assertEquals(Math.rint(atlas.descent(s)), atlas.descent(s), 0f);
            assertEquals(atlas.ascent(s) + atlas.descent(s), atlas.lineHeight(s), 0f);
        }
    }

    @Test
    public void glyphOutsideTheFontIsMissing() {
        assertNull(atlas.glyph(BODY, 0x4E2D));
        assertNotNull(atlas.glyph(BODY, 'A'));
        GlyphAtlas.Shaped sh = atlas.shape("a中b", BODY);
        assertEquals(3, sh.count());
        assertNull(sh.glyph(1));
        assertEquals(0x4E2D, sh.codePoint(1));
        assertEquals(atlas.missingAdvance(BODY), sh.x(2) - sh.x(1), 0.01f);
    }

    @Test
    public void accentsAndPunctuationAreInTheFont() {
        for (int cp : new int[]{0xE9, 0xB7, 0x2026, 0x2013, 0x2019, 0x201C, 0x201D, 0xD7}) {
            assertNotNull("U+" + Integer.toHexString(cp), atlas.glyph(BODY, cp));
        }
    }

    @Test
    public void spaceAndControlsDrawNothing() {
        GlyphAtlas.Glyph space = atlas.glyph(BODY, ' ');
        assertNotNull(space);
        assertEquals(0, space.sw());
        assertTrue(space.advance() > 0);
        GlyphAtlas.Shaped sh = atlas.shape("a\tb", BODY);
        assertEquals(-1, sh.codePoint(1));
        assertEquals(atlas.measure("ab", BODY), sh.advance(), 0.01f);
    }

    @Test
    public void glyphBitmapsAreWhiteWithCoverageInAlpha() {
        GlyphAtlas.Glyph g = atlas.glyph(new TextStyle(Weight.MEDIUM, 18), 'O');
        boolean sawPartial = false;
        for (int y = 0; y < g.sh(); y++) {
            for (int x = 0; x < g.sw(); x++) {
                int argb = g.page().image().getRGB(g.sx() + x, g.sy() + y);
                assertEquals(0xFFFFFF, argb & 0xFFFFFF);
                int a = argb >>> 24;
                sawPartial |= a > 0 && a < 255;
            }
        }
        assertTrue("an O should be antialiased", sawPartial);
    }

    @Test
    public void shapingIsCached() {
        GlyphAtlas.Shaped a = atlas.shape("Waypoints", BODY);
        assertTrue(a == atlas.shape("Waypoints", BODY));
    }

    @Test
    public void ellipsizeFits() {
        String s = "a fairly long waypoint name that will not fit";
        String cut = atlas.ellipsize(s, BODY, 120f);
        assertTrue(cut.endsWith("…"));
        assertTrue(atlas.measure(cut, BODY) <= 120f);
    }

    @Test
    public void pagesGrowWhenFull() {
        assertEquals(0, atlas.pages().size());
        for (Weight w : Weight.values()) {
            for (int size = 8; size <= 72; size += 2) {
                atlas.measure("Hello world", new TextStyle(w, size));
            }
        }
        assertTrue("expected more than one page, got " + atlas.pages().size(), atlas.pages().size() > 1);
        int version = atlas.pages().get(0).version();
        assertTrue(version > 0);
        // a new size touches a page, which is how the game knows to upload again
        int total = 0;
        for (AtlasPage p : atlas.pages()) {
            total += p.version();
        }
        atlas.measure("Hello world", new TextStyle(Weight.REGULAR, 91));
        int after = 0;
        for (AtlasPage p : atlas.pages()) {
            after += p.version();
        }
        assertTrue(after > total);
    }

    @Test
    public void halvesOfPairsAndInvisibleCharsDrawNothing() {
        // lone surrogate, soft hyphen, a c1 control: no ink, no width, and nothing for the canvas to fake
        String text = "a\uD83Db­c\u0085d​e";
        GlyphAtlas.Shaped sh = atlas.shape(text, BODY);
        for (int i = 0; i < sh.count(); i++) {
            char c = text.charAt(i);
            if (c == 'a' || c == 'b' || c == 'c' || c == 'd' || c == 'e') {
                assertTrue(sh.codePoint(i) >= 0);
            } else {
                assertEquals(-1, sh.codePoint(i));
            }
        }
        assertEquals(atlas.measure("abcde", BODY), sh.advance(), 0.01f);
    }

    @Test
    public void pairsStayOneCodePoint() {
        GlyphAtlas.Shaped sh = atlas.shape("a😀b", BODY);
        assertEquals(4, sh.count());
        assertEquals(0x1F600, sh.codePoint(1));
        assertNull(sh.glyph(1));
        assertEquals(-1, sh.codePoint(2));
        assertEquals('b', sh.codePoint(3));
    }

    @Test
    public void missingWidthSourceDrivesLayout() {
        float plain = atlas.measure("a中b", BODY);
        atlas.setMissingWidth((cp, style) -> 20f);
        assertEquals(plain - atlas.missingAdvance(BODY) + 20f, atlas.measure("a中b", BODY), 0.01f);
    }

    @Test
    public void packerNeverOverlapsOrLeavesThePage() {
        java.util.List<AtlasPage> pages = new java.util.ArrayList<>();
        java.util.List<int[]> placed = new java.util.ArrayList<>();
        java.util.Random rng = new java.util.Random(7);
        int[] out = new int[2];
        for (int i = 0; i < 800; i++) {
            int w = 1 + rng.nextInt(i % 50 == 0 ? 700 : 60);
            int h = 1 + rng.nextInt(60);
            AtlasPage p = AtlasPage.place(pages, w, h, out);
            assertTrue(out[0] >= 0 && out[1] >= 0 && out[0] + w <= p.width() && out[1] + h <= p.height());
            for (int[] q : placed) {
                if (q[0] != p.id()) {
                    continue;
                }
                // a one pixel gap counts as touching, so the test wants the rects apart by at least one
                boolean apart = out[0] + w < q[1] || q[1] + q[3] < out[0] || out[1] + h < q[2] || q[2] + q[4] < out[1];
                assertTrue("rect " + i + " touches an earlier one", apart);
            }
            placed.add(new int[]{p.id(), out[0], out[1], w, h});
        }
        assertTrue(pages.size() > 1);
    }

    @Test
    public void everyIconHasInkAtTheSizesWeUse() {
        for (Icon icon : Icon.values()) {
            for (int size : new int[]{12, 16, 20, 24}) {
                GlyphAtlas.Glyph g = atlas.icon(icon, size);
                int ink = 0;
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        if ((g.page().image().getRGB(g.sx() + x, g.sy() + y) >>> 24) > 128) {
                            ink++;
                        }
                    }
                }
                assertTrue(icon + " at " + size + " has only " + ink + " solid pixels", ink >= size / 2);
            }
        }
    }

    @Test
    public void iconsRasteriseOncePerSize() {
        GlyphAtlas.Glyph a = atlas.icon(Icon.SEARCH, 16);
        assertTrue(a == atlas.icon(Icon.SEARCH, 16));
        assertEquals(16, a.sw());
        boolean ink = false;
        for (int y = 0; y < 16 && !ink; y++) {
            for (int x = 0; x < 16; x++) {
                if ((a.page().image().getRGB(a.sx() + x, a.sy() + y) >>> 24) != 0) {
                    ink = true;
                    break;
                }
            }
        }
        assertTrue(ink);
    }
}
