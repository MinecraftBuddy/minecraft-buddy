package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Theme;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// the part of the atlas setup that needs no game: awt has to be able to load inter and rasterise it headless, which is
// how the client runs. uploading needs a gl context and is not covered here
public class McAtlasesTest {

    @Test
    public void fontsLoadAndPrewarmFillsPages() {
        System.setProperty("java.awt.headless", "true");
        McAtlases atlases = McAtlases.get();
        assertTrue("atlases should come up headless", atlases.ok);

        Theme theme = new Theme(1f, atlases.glyphs);
        atlases.prewarm(theme);
        assertFalse(atlases.glyphs.pages().isEmpty());
        assertTrue(atlases.glyphs.measure("Right Shift", theme.body) > 0f);
    }

    @Test
    public void aWorkingBuildIsKept() {
        McAtlases first = McAtlases.get();
        assertTrue(first.ok);
        assertSame(first, McAtlases.get());
    }
}
