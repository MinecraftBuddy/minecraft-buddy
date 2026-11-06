package adris.altoclef.ui.gui.kit;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

// the bundled faces as awt fonts. createFont does not register them with the system, so nothing leaks into
// anybody else's font menu. derived fonts are cached because deriveFont(Map) is not free and the atlas asks a lot
public final class AwtFonts {

    private final Font[] base;
    private final Map<Integer, Font>[] derived;

    @SuppressWarnings("unchecked")
    private AwtFonts(Font[] base) {
        this.base = base;
        this.derived = new Map[base.length];
        for (int i = 0; i < derived.length; i++) {
            derived[i] = new HashMap<>();
        }
    }

    public static AwtFonts load() throws IOException, FontFormatException {
        Weight[] weights = Weight.values();
        Font[] fonts = new Font[weights.length];
        for (Weight w : weights) {
            String path = Weight.RESOURCE_DIR + w.file;
            try (InputStream in = AwtFonts.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IOException("font missing from the classpath: " + path);
                }
                fonts[w.ordinal()] = Font.createFont(Font.TRUETYPE_FONT, in);
            }
        }
        return new AwtFonts(fonts);
    }

    public synchronized Font font(Weight w, int sizePx) {
        Map<Integer, Font> bySize = derived[w.ordinal()];
        Font f = bySize.get(sizePx);
        if (f == null) {
            Map<TextAttribute, Object> attrs = new HashMap<>();
            attrs.put(TextAttribute.SIZE, (float) sizePx);
            // kerning is off by default in awt
            attrs.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
            f = base[w.ordinal()].deriveFont(attrs);
            bySize.put(sizePx, f);
        }
        return f;
    }
}
