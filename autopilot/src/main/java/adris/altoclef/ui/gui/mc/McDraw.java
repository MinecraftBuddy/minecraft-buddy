package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Safe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3x2f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

// the few things both canvases need from the game's gui renderer, so McCanvas and FallbackCanvas stay one liners. the pose is
// scaled by 1/guiScale for the whole screen, so every number in here is a framebuffer pixel.
//
// 26.3 draws in two phases: everything submitted here is only a description until the frame is over. the extractor's own
// fill/blit would work, but it files every element by bounds into layers, and walking that for the thousands of glyph
// quads a panel is made of is slow, and its bounds round to whole gui units so touching quads can swap order. so fills
// and blits go straight into the current layer's glyph list instead: that list is never sorted or searched, it draws in
// exactly the order it was filled, which is the painter's order the kit assumes (end() closes the stratum so nothing
// the game extracts afterwards gets filed into that same layer). the extractor's own things (items,
// faces, vanilla text) each get a stratum to themselves on both sides. strata draw in order, so they land over
// everything submitted before them and under everything after
final class McDraw {

    // vanilla's font is 9 tall with the baseline 7 down from the top
    private static final float VANILLA_H = 9f;
    private static final float VANILLA_BASE = 7f;

    private final Minecraft mc = Minecraft.getInstance();
    private final Map<String, ItemStack> items = new HashMap<>();
    // x0, y0, x1, y1 of each active clip, already intersected with its parent
    private final ArrayDeque<int[]> clips = new ArrayDeque<>();

    GuiGraphicsExtractor g;
    private GuiRenderState state;
    // the pose at the start of the frame, shared by every quad. nothing may touch it afterwards
    private Matrix3x2f pose;

    void begin(GuiGraphicsExtractor graphics) {
        this.g = graphics;
        this.state = mc.gameRenderer.gameRenderState().guiRenderState;
        this.pose = new Matrix3x2f(graphics.pose());
        clips.clear();
        // our quads go into the current layer, so make sure that is an empty one of our own
        graphics.nextStratum();
    }

    void end() {
        // whatever the game extracts after the screen (toasts, the debug overlay) is filed by bounds and never looks at our
        // glyph list, so it must not land in the layer our last quads are in or it would draw under them
        if (g != null) {
            g.nextStratum();
        }
        clips.clear();
        g = null;
        state = null;
        pose = null;
    }

    // snap the edges, not the size, and never let a real hairline round down to nothing
    void fill(float x, float y, float w, float h, int argb) {
        if (w <= 0f || h <= 0f || (argb >>> 24) == 0) {
            return;
        }
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int x1 = Math.round(x + w);
        int y1 = Math.round(y + h);
        if (x1 <= x0) {
            x1 = x0 + 1;
        }
        if (y1 <= y0) {
            y1 = y0 + 1;
        }
        int[] c = clips.peek();
        if (c != null) {
            x0 = Math.max(x0, c[0]);
            y0 = Math.max(y0, c[1]);
            x1 = Math.min(x1, c[2]);
            y1 = Math.min(y1, c[3]);
            if (x1 <= x0 || y1 <= y0) {
                return;
            }
        }
        // the far corner goes first, same as GuiGraphicsExtractor.fill does before it builds the quad, so the winding matches
        state.addGlyphToCurrentLayer(new ColoredRectangleRenderState(RenderPipelines.GUI, TextureSetup.noTexture(), pose,
                x1, y1, x0, y0, argb, argb, null));
    }

    // a tinted piece of an atlas page onto a pixel rect. a clip cuts the rect and the source with it, so a glyph half
    // under the edge of a scroll list is half a glyph and not a whole one
    void blit(TextureSetup setup, int pageW, int pageH, int sx, int sy, int sw, int sh,
              int x0, int y0, int w, int h, int argb) {
        if ((argb >>> 24) == 0) {
            return;
        }
        int cx0 = x0;
        int cy0 = y0;
        int cx1 = x0 + w;
        int cy1 = y0 + h;
        int[] c = clips.peek();
        if (c != null) {
            cx0 = Math.max(cx0, c[0]);
            cy0 = Math.max(cy0, c[1]);
            cx1 = Math.min(cx1, c[2]);
            cy1 = Math.min(cy1, c[3]);
            if (cx1 <= cx0 || cy1 <= cy0) {
                return;
            }
        }
        float u0 = (sx + (cx0 - x0) * (float) sw / w) / pageW;
        float u1 = (sx + (cx1 - x0) * (float) sw / w) / pageW;
        float v0 = (sy + (cy0 - y0) * (float) sh / h) / pageH;
        float v1 = (sy + (cy1 - y0) * (float) sh / h) / pageH;
        state.addGlyphToCurrentLayer(new BlitRenderState(RenderPipelines.GUI_TEXTURED, setup, pose,
                cx0, cy0, cx1, cy1, u0, u1, v0, v1, argb, null));
    }

    // pixel exact for the fills and blits above. the extractor's scissor is whole gui units through the pose, so a clip
    // the extractor has to honour (items, faces, vanilla text) can only be approximated, see scissorBegin
    void pushClip(float x, float y, float w, float h) {
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int x1 = Math.round(x + w);
        int y1 = Math.round(y + h);
        int[] parent = clips.peek();
        if (parent != null) {
            x0 = Math.max(x0, parent[0]);
            y0 = Math.max(y0, parent[1]);
            x1 = Math.min(x1, parent[2]);
            y1 = Math.min(y1, parent[3]);
        }
        clips.push(new int[]{x0, y0, Math.max(x0, x1), Math.max(y0, y1)});
    }

    void popClip() {
        if (!clips.isEmpty()) {
            clips.pop();
        }
    }

    // 0 draws it as is (no clip, or it sits wholly inside), -1 means it is wholly outside and there is nothing to draw,
    // 1 means it straddles the edge and a scissor is on, which the caller must drop again. the scissor rounds to gui
    // units so the cut can be off by up to a gui scale's worth of pixels, only the things that straddle pay that
    private int scissorBegin(int x, int y, int w, int h) {
        int[] c = clips.peek();
        if (c == null) {
            return 0;
        }
        if (x >= c[2] || y >= c[3] || x + w <= c[0] || y + h <= c[1]) {
            return -1;
        }
        if (x >= c[0] && y >= c[1] && x + w <= c[2] && y + h <= c[3]) {
            return 0;
        }
        g.enableScissor(c[0], c[1], c[2], c[3]);
        return 1;
    }

    // false when the id is not a registered item, the caller draws its own stand-in tile
    boolean item(String id, float x, float y, float size) {
        ItemStack stack = stack(id);
        if (stack.isEmpty()) {
            return false;
        }
        if (size <= 0f) {
            return true;
        }
        int px = Math.round(x);
        int py = Math.round(y);
        int side = Math.max(1, Math.round(size));
        int clip = scissorBegin(px, py, side, side);
        if (clip < 0) {
            return true;
        }
        // an item is a flat blit of a pre rendered model out of the item atlas on 26.3, so there is no depth to wipe after
        g.nextStratum();
        g.pose().pushMatrix();
        g.pose().translate(px, py);
        g.pose().scale(size / 16f, size / 16f);
        try {
            g.item(stack, 0, 0);
        } finally {
            g.pose().popMatrix();
            if (clip > 0) {
                g.disableScissor();
            }
            g.nextStratum();
        }
        return true;
    }

    // the face from the tab list's skin. false when we have no skin for the name, or the renderer throws
    boolean face(String name, float x, float y, float size) {
        try {
            if (mc.getConnection() == null || name == null) {
                return false;
            }
            PlayerInfo info = mc.getConnection().getPlayerInfo(name);
            if (info == null) {
                return false;
            }
            int px = Math.round(x);
            int py = Math.round(y);
            int side = Math.max(1, Math.round(size));
            int clip = scissorBegin(px, py, side, side);
            if (clip < 0) {
                return true;
            }
            g.nextStratum();
            try {
                PlayerFaceExtractor.extractRenderState(g, info.getSkin(), px, py, side);
            } finally {
                if (clip > 0) {
                    g.disableScissor();
                }
                g.nextStratum();
            }
            return true;
        } catch (Throwable t) {
            Safe.report("gui player face", t);
            return false;
        }
    }

    // the letter for an item we cannot draw: "minecraft:oak_log" is O
    static String letterOf(String id) {
        if (id == null || id.isEmpty()) {
            return "?";
        }
        String path = id.substring(id.indexOf(':') + 1);
        return path.isEmpty() ? "?" : path.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }

    private ItemStack stack(String id) {
        ItemStack cached = items.get(id);
        if (cached != null) {
            return cached;
        }
        ItemStack stack = ItemStack.EMPTY;
        Identifier rl = id == null ? null : Identifier.tryParse(id);
        if (rl != null) {
            Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);
            if (item != Items.AIR) {
                stack = new ItemStack(item);
            }
        }
        items.put(id, stack);
        return stack;
    }

    // vanilla treats alpha under 4 as fully opaque, which is how a fade out ends with a bang
    void vanillaText(String s, float x, float baseline, float size, int argb) {
        if ((argb >>> 24) < 4 || s.isEmpty()) {
            return;
        }
        float scale = size / VANILLA_H;
        float top = baseline - VANILLA_BASE * scale;
        int clip = scissorBegin((int) Math.floor(x), (int) Math.floor(top), (int) Math.ceil(vanillaWidth(s, size)) + 1,
                (int) Math.ceil(size) + 1);
        if (clip < 0) {
            return;
        }
        g.nextStratum();
        g.pose().pushMatrix();
        g.pose().translate(x, top);
        g.pose().scale(scale, scale);
        try {
            g.text(mc.font, s, 0, 0, argb, false);
        } finally {
            g.pose().popMatrix();
            if (clip > 0) {
                g.disableScissor();
            }
            g.nextStratum();
        }
    }

    float vanillaWidth(String s, float size) {
        return mc.font.width(s) * size / VANILLA_H;
    }

    float vanillaAscent(float size) {
        return VANILLA_BASE * size / VANILLA_H;
    }

    float vanillaDescent(float size) {
        return (VANILLA_H - VANILLA_BASE) * size / VANILLA_H;
    }
}
