package adris.altoclef.ui.gui.preview;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

// flat item and block textures read straight out of the minecraft client jar on the test classpath, so the
// preview pngs show pixel art instead of grey tiles. nothing is copied anywhere: this only runs in tests, and
// a texture that is not there (3d models, entity blocks, a jar that is not on the classpath) just means null
final class ItemTextures {

    private static final Map<String, BufferedImage> CACHE = new HashMap<>();
    private static final BufferedImage MISSING = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    // where a block's flat face lives, in the order a player would recognise it
    private static final String[] BLOCK_SUFFIXES = {"", "_front", "_top", "_side", "_still"};

    private ItemTextures() {
    }

    static BufferedImage get(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        BufferedImage cached = CACHE.get(itemId);
        if (cached != null) {
            return cached == MISSING ? null : cached;
        }
        String path = itemId.substring(itemId.indexOf(':') + 1);
        BufferedImage found = load("/assets/minecraft/textures/item/" + path + ".png");
        for (int i = 0; found == null && i < BLOCK_SUFFIXES.length; i++) {
            found = load("/assets/minecraft/textures/block/" + path + BLOCK_SUFFIXES[i] + ".png");
        }
        if (found == null && path.endsWith("_spawn_egg")) {
            found = egg(path.substring(0, path.length() - "_spawn_egg".length()));
        }
        // animated textures are a vertical strip of frames, the first one is the icon
        if (found != null && found.getHeight() > found.getWidth()) {
            found = found.getSubimage(0, 0, found.getWidth(), found.getWidth());
        }
        CACHE.put(itemId, found == null ? MISSING : found);
        return found;
    }

    // the game colours its eggs in code, not in the texture: the two grey layers get the mob's pair. these are
    // vanilla's numbers for the mobs a preview is likely to show, anything else gets a stable pair from its name
    private static final Map<String, int[]> EGG_COLOURS = Map.ofEntries(
            Map.entry("zombie", new int[]{0x00AFAF, 0x799C65}),
            Map.entry("skeleton", new int[]{0xC1C1C1, 0x494949}),
            Map.entry("creeper", new int[]{0x0DA70B, 0x000000}),
            Map.entry("spider", new int[]{0x342D27, 0xA80E0E}),
            Map.entry("enderman", new int[]{0x161616, 0x000000}),
            Map.entry("cow", new int[]{0x443626, 0xA1A1A1}),
            Map.entry("pig", new int[]{0xF0A5A2, 0xDB635F}),
            Map.entry("sheep", new int[]{0xE7E7E7, 0xFFB5B5}),
            Map.entry("chicken", new int[]{0xA1A1A1, 0xFF0000}),
            Map.entry("wolf", new int[]{0xD7D3D3, 0xCEAF96}),
            Map.entry("villager", new int[]{0x563C33, 0xBD8B72}),
            Map.entry("bat", new int[]{0x4C3E30, 0x0F0F0F}),
            Map.entry("horse", new int[]{0xC09E7D, 0xEEE500}),
            Map.entry("cat", new int[]{0xEFC88E, 0x957256}),
            Map.entry("blaze", new int[]{0xF6B201, 0xFFF87E}),
            Map.entry("piglin", new int[]{0x995F40, 0xF9F3A4}));

    private static BufferedImage egg(String mob) {
        BufferedImage base = load("/assets/minecraft/textures/item/spawn_egg.png");
        BufferedImage overlay = load("/assets/minecraft/textures/item/spawn_egg_overlay.png");
        if (base == null) {
            return null;
        }
        int[] pair = EGG_COLOURS.get(mob);
        if (pair == null) {
            int h = mob.hashCode();
            pair = new int[]{0x404040 | (h & 0x7F7F7F), 0x202020 | ((h >>> 8) & 0xBFBFBF)};
        }
        int w = base.getWidth();
        int hh = base.getHeight();
        BufferedImage out = new BufferedImage(w, hh, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < w; x++) {
                int p = tint(base.getRGB(x, y), pair[0]);
                if (overlay != null && x < overlay.getWidth() && y < overlay.getHeight()) {
                    int o = tint(overlay.getRGB(x, y), pair[1]);
                    if ((o >>> 24) > 0) {
                        p = o;
                    }
                }
                out.setRGB(x, y, p);
            }
        }
        return out;
    }

    private static int tint(int argb, int rgb) {
        int a = argb >>> 24;
        if (a == 0) {
            return 0;
        }
        int r = (argb >> 16 & 255) * (rgb >> 16 & 255) / 255;
        int g = (argb >> 8 & 255) * (rgb >> 8 & 255) / 255;
        int b = (argb & 255) * (rgb & 255) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static BufferedImage load(String resource) {
        try (InputStream in = ItemTextures.class.getResourceAsStream(resource)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }
}
