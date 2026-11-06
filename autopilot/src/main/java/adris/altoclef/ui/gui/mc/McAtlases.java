package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.AtlasPage;
import adris.altoclef.ui.gui.kit.AwtFonts;
import adris.altoclef.ui.gui.kit.GlyphAtlas;
import adris.altoclef.ui.gui.kit.PixelImage;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShapeAtlas;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

// the cpu side atlases (they are kept between opens, rasterising inter is the slow part) and the gpu side of each page.
// textures only live while the screen is open: release() on removed, they are re-uploaded on demand next time. if
// anything in here throws, ok stays false and the screen draws with vanilla's font and plain fills instead
final class McAtlases {

    private static McAtlases instance;

    private static final class Slot {
        final DynamicTexture texture;
        // built once, the renderer batches draws by comparing these and a fresh record per glyph would be a lot of garbage
        final TextureSetup setup;
        int version = -1;

        Slot(DynamicTexture texture) {
            this(texture, false);
        }

        Slot(DynamicTexture texture, boolean smooth) {
            this.texture = texture;
            // a dynamic texture already samples nearest and repeat, which is what a pixel exact atlas wants. a smooth
            // picture gets drawn a bit off its baked size (a dock tile mid magnification), nearest would make its
            // round corners crawl, so that one borrows the shared linear clamp sampler (the cache owns it, never close it)
            this.setup = TextureSetup.singleTexture(texture.getTextureView(),
                    smooth ? RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR) : texture.getSampler());
        }
    }

    final boolean ok;
    final AwtFonts fonts;
    final GlyphAtlas glyphs;
    final ShapeAtlas shapes;

    private final Map<Integer, Slot> slots = new HashMap<>();
    // hand drawn pictures (the placement map), one texture each, keyed by the picture itself
    private final Map<PixelImage, Slot> images = new IdentityHashMap<>();
    private AtlasPage lastPage;
    private Slot lastSlot;

    private McAtlases(AwtFonts fonts, GlyphAtlas glyphs, ShapeAtlas shapes) {
        this.ok = fonts != null;
        this.fonts = fonts;
        this.glyphs = glyphs;
        this.shapes = shapes;
    }

    // a failed build is not cached as a success, the next open gets to try again
    static McAtlases get() {
        if (instance == null || !instance.ok) {
            instance = build();
        }
        return instance;
    }

    private static McAtlases build() {
        try {
            AwtFonts fonts = AwtFonts.load();
            return new McAtlases(fonts, new GlyphAtlas(fonts), new ShapeAtlas());
        } catch (Throwable t) {
            Safe.report("gui atlases", t);
            return new McAtlases(null, null, null);
        }
    }

    // rasterise the plain ascii once up front so the first frames are not one texture upload per new word
    void prewarm(Theme theme) {
        if (!ok) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (char c = 32; c < 127; c++) {
            sb.append(c);
        }
        String ascii = sb.toString();
        TextStyle[] styles = {theme.title, theme.label, theme.body, theme.small, theme.smallLabel};
        for (TextStyle s : styles) {
            try {
                glyphs.shape(ascii, s);
                glyphs.shape("0123456789", s.asTabular());
            } catch (Throwable t) {
                Safe.report("gui prewarm", t);
                return;
            }
        }
    }

    // what to bind for this page, with the latest pixels in it
    TextureSetup setup(AtlasPage page) {
        return ensureUploaded(page).setup;
    }

    private Slot ensureUploaded(AtlasPage page) {
        // text is thousands of blits from the same page in a row
        if (page == lastPage && lastSlot.version == page.version()) {
            return lastSlot;
        }
        Slot slot = slots.get(page.id());
        if (slot == null) {
            // not registered with the texture manager: the draws carry the view themselves, so nothing needs a name
            DynamicTexture tex = new DynamicTexture(() -> "soprano gui atlas " + page.id(),
                    new NativeImage(page.width(), page.height(), true));
            slot = new Slot(tex);
            slots.put(page.id(), slot);
        }
        int version = page.version();
        if (slot.version != version) {
            copy(page, slot.texture);
            slot.version = version;
        }
        lastPage = page;
        lastSlot = slot;
        return slot;
    }

    // pages are white with coverage in alpha, so whether setPixel wants argb or abgr makes no difference to them
    private static void copy(AtlasPage page, DynamicTexture texture) {
        NativeImage image = texture.getPixels();
        int[] argb = ((DataBufferInt) page.image().getRaster().getDataBuffer()).getData();
        copy(argb, page.width(), page.height(), image);
        texture.upload();
    }

    // setPixel takes argb (setPixelABGR is the other one), which is what the map colours are
    private static void copy(int[] argb, int w, int h, NativeImage image) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                image.setPixel(x, y, argb[row + x]);
            }
        }
    }

    // what to bind for a picture, uploaded on first sight and again whenever its version moved
    TextureSetup setup(PixelImage picture) {
        Slot slot = images.get(picture);
        if (slot == null) {
            DynamicTexture tex = new DynamicTexture(() -> "soprano gui picture",
                    new NativeImage(picture.width(), picture.height(), true));
            slot = new Slot(tex, picture.isSmooth());
            images.put(picture, slot);
        }
        int version = picture.version();
        if (slot.version != version) {
            copy(picture.pixels(), picture.width(), picture.height(), slot.texture.getPixels());
            slot.texture.upload();
            slot.version = version;
        }
        return slot.setup;
    }

    static void releaseAll() {
        if (instance != null) {
            instance.release();
        }
    }

    // the frame that closes the screen has already queued blits against these views, and they only draw after extraction is
    // done. closing now would hand the renderer a dead texture. scheduled tasks run at the top of the next tick, before that
    // tick extracts, so by then this frame has drawn. the second hop is belt and braces in case a drain loops
    private void release() {
        Minecraft mc = Minecraft.getInstance();
        List<DynamicTexture> dead = new ArrayList<>();
        for (Slot slot : slots.values()) {
            dead.add(slot.texture);
        }
        for (Slot slot : images.values()) {
            dead.add(slot.texture);
        }
        slots.clear();
        images.clear();
        lastPage = null;
        lastSlot = null;
        closeLater(mc, dead);
    }

    // one picture that is done for good (a dock tile baked at a size the window no longer has), same delayed close as
    // release(): this frame may already have queued a blit of it
    static void release(PixelImage picture) {
        if (instance == null || picture == null) {
            return;
        }
        Slot slot = instance.images.remove(picture);
        if (slot != null) {
            List<DynamicTexture> dead = new ArrayList<>();
            dead.add(slot.texture);
            closeLater(Minecraft.getInstance(), dead);
        }
    }

    private static void closeLater(Minecraft mc, List<DynamicTexture> dead) {
        if (!dead.isEmpty()) {
            mc.schedule(() -> mc.schedule(() -> {
                for (DynamicTexture tex : dead) {
                    try {
                        tex.close();
                    } catch (Throwable t) {
                        Safe.report("gui texture release", t);
                    }
                }
            }));
        }
    }
}
