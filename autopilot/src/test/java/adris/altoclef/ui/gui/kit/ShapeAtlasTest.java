package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShapeAtlasTest {

    private static int alpha(ShapeAtlas.Slice s, int x, int y) {
        return s.page().image().getRGB(s.sx() + x, s.sy() + y) >>> 24;
    }

    @Test
    public void discCornerIsOpaqueInsideAndEmptyOutside() {
        ShapeAtlas atlas = new ShapeAtlas();
        int r = 10;
        ShapeAtlas.Slice[] d = atlas.disc(r);
        assertEquals(4, d.length);
        // top left slice: the inner corner is the disc's centre, the outer corner is outside the curve
        assertEquals(255, alpha(d[ShapeAtlas.TL], r - 1, r - 1));
        assertEquals(0, alpha(d[ShapeAtlas.TL], 0, 0));
        // and the others mirror it
        assertEquals(255, alpha(d[ShapeAtlas.TR], 0, r - 1));
        assertEquals(0, alpha(d[ShapeAtlas.TR], r - 1, 0));
        assertEquals(255, alpha(d[ShapeAtlas.BL], r - 1, 0));
        assertEquals(0, alpha(d[ShapeAtlas.BL], 0, r - 1));
        assertEquals(255, alpha(d[ShapeAtlas.BR], 0, 0));
        assertEquals(0, alpha(d[ShapeAtlas.BR], r - 1, r - 1));
        // the straight edge runs flush: the pixel next to the join is full
        assertTrue(alpha(d[ShapeAtlas.TL], r - 1, 0) > 240);
    }

    @Test
    public void discsAreCached() {
        ShapeAtlas atlas = new ShapeAtlas();
        assertTrue(atlas.disc(6) == atlas.disc(6));
        assertTrue(atlas.ring(6) == atlas.ring(6));
        assertTrue(atlas.shadow(10, 15) == atlas.shadow(10, 15));
        assertEquals(1, atlas.pages().size());
    }

    @Test
    public void ringIsOnlyNearTheEdge() {
        ShapeAtlas atlas = new ShapeAtlas();
        int r = 10;
        ShapeAtlas.Slice[] ring = atlas.ring(r);
        // inside the ring and outside it are both empty
        assertEquals(0, alpha(ring[ShapeAtlas.TL], r - 1, r - 1));
        assertEquals(0, alpha(ring[ShapeAtlas.TL], 0, 0));
        // straight sections where it meets the edge rects are solid
        assertTrue(alpha(ring[ShapeAtlas.TL], r - 1, 0) > 200);
        assertTrue(alpha(ring[ShapeAtlas.TL], 0, r - 1) > 200);
        // and a 45 degree pixel carries some ink
        int diag = (int) Math.round(r - r / Math.sqrt(2));
        assertTrue(alpha(ring[ShapeAtlas.TL], diag, diag) > 40);
        // nothing a few pixels in from the curve
        assertEquals(0, alpha(ring[ShapeAtlas.TL], r / 2 + 2, r / 2 + 2));
    }

    @Test
    public void shadowIsDensestInTheMiddleAndFadesOut() {
        ShapeAtlas atlas = new ShapeAtlas();
        int r = 10;
        int blur = 15;
        ShapeAtlas.Slice[] s = atlas.shadow(r, blur);
        int cs = ShapeAtlas.shadowCorner(r, blur);
        assertEquals(9, s.length);
        assertEquals(cs, s[ShapeAtlas.SH_TL].sw());
        assertEquals(1, s[ShapeAtlas.SH_C].sw());
        assertEquals(1, s[ShapeAtlas.SH_T].sw());
        assertTrue(alpha(s[ShapeAtlas.SH_C], 0, 0) > 250);
        // the tile's outer rim is the margin, which the blur should not have reached
        assertTrue(alpha(s[ShapeAtlas.SH_TL], 0, 0) <= 2);
        assertTrue(alpha(s[ShapeAtlas.SH_T], 0, 0) <= 2);
        assertTrue(alpha(s[ShapeAtlas.SH_L], 0, 0) <= 2);
        // monotonic down the top edge slice: darker the closer to the box
        int prev = -1;
        for (int y = 0; y < cs; y++) {
            int a = alpha(s[ShapeAtlas.SH_T], 0, y);
            assertTrue("row " + y + " went from " + prev + " to " + a, a >= prev);
            prev = a;
        }
        // the box edge sits at 50 percent, give or take the blur shape
        int edge = alpha(s[ShapeAtlas.SH_T], 0, blur);
        assertTrue("edge alpha " + edge, edge > 80 && edge < 180);
    }

    @Test
    public void zeroRadiusShadowStillBuilds() {
        ShapeAtlas atlas = new ShapeAtlas();
        ShapeAtlas.Slice[] s = atlas.shadow(0, 4);
        assertTrue(alpha(s[ShapeAtlas.SH_C], 0, 0) > 250);
    }
}
