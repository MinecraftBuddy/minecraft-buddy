package adris.altoclef.ui.gui.kit;

// everything the gui draws goes through this. units are device pixels, origin top left of the window. two backends:
// the game (GuiGraphics, pose scaled by 1/guiScale) and a BufferedImage for previews and tests. the rounded rect,
// hairline, shadow, text and icon calls are implemented once in TexturedCanvas on top of fillRect + blitTinted, so a
// backend only has to copy tinted pixels and clip. no minecraft imports in here, ever
public interface Canvas extends TextMeasurer {

    default void setTheme(Theme theme) {
    }

    default int color(int token) {
        return token;
    }

    int width();

    int height();

    void fillRect(float x, float y, float w, float h, int argb);

    void fillRoundRect(float x, float y, float w, float h, float radius, int argb);

    // one pixel hairline drawn inside the box
    void strokeRoundRect(float x, float y, float w, float h, float radius, int argb);

    // soft shadow under a rounded box: blur about 1.5x the radius, pushed down by half the radius
    void shadow(float x, float y, float w, float h, float radius, int argb);

    // draws at the baseline, returns the advance
    float text(String s, float x, float baseline, TextStyle style, int argb);

    void icon(Icon icon, float x, float y, float size, int argb);

    // registry id like "minecraft:oak_log". the game draws the real item through its own item renderer, the
    // preview reads the flat texture out of the game jar. an id nothing resolves draws a tile with the name's
    // first letter: never a blank square, never the purple missing texture. size is a whole multiple of 16
    void itemIcon(String itemId, float x, float y, float size);

    // the player's skin face (the game asks PlayerFaceRenderer, the preview draws the player glyph in a tile)
    default void playerFace(String playerName, float x, float y, float size) {
        fillRoundRect(x, y, size, size, size / 6f, color(Theme.RAISED));
        icon(Icon.PLAYER, x + size * 0.15f, y + size * 0.15f, size * 0.7f, color(Theme.TEXT_2));
    }

    // a hand drawn picture stretched over a pixel rect, nearest neighbour so a block stays a crisp square. tint
    // multiplies (0xFFFFFFFF draws it as is, a lower alpha fades it). the game uploads it once per version
    void image(PixelImage image, float x, float y, float w, float h, int tint);

    void pushClip(float x, float y, float w, float h);

    void popClip();
}
