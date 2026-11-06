package adris.altoclef.ui.gui.mc.ghost;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Placement;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.mc.McSchematics;
import adris.altoclef.ui.gui.mc.ghost.GhostScan.Ghost;
import adris.altoclef.ui.gui.mc.ghost.GhostScan.Kind;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.events.ChunkEvent;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.schematic.ISchematic;
import baritone.utils.IRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

// litematica style see-through blocks for the schematic picked in the gui (McSchematics.ghost()). the tick keeps a
// GhostField of baked quads in step with the world, the frame only pushes those quads. drawn at the end of
// LevelRenderer.render like the rest of baritone's stuff, through translucentMovingBlock: the block atlas, depth
// tested against the world, alpha from the vertex colour
public final class SchematicGhostRenderer implements AbstractGameEventListener {

    // past this the frame stops, nearest sections first. pushing quads is per frame cpu work, 50k is a couple of ms
    static final int MAX_QUADS = 50_000;
    static final int MAX_DISTANCE = 128;
    // a section is 0.6 to 1 ms to scan and bake on the bench, so two or three a tick. a 100x100x50 build at 64
    // blocks is ~200 sections and fills in over 2 or 3 seconds instead of eating one 150 ms frame
    private static final long SCAN_BUDGET_NANOS = 2_000_000L;
    private static final int FULL_BRIGHT = 15728880;
    // wrong blocks sit in the same cube as the real one, the faces would fight. half a hundredth out is plenty
    private static final float INFLATE = 1.01f;
    private static final double BOX_GROW = 0.005;

    // a slight blue-white cast over the texture, like litematica's
    static final int MISSING_RGB = 0xC8DCFF;
    static final int WRONG_STATE_RGB = 0xFFB060;
    static final int WRONG_BLOCK_RGB = 0xFF6A6A;
    static final int EXTRA_RGB = 0xFF4040;
    private static final Direction[] DIRECTIONS = Direction.values();

    // one block's worth of drawing. quads null = a plain coloured box (something to remove, or a block whose model
    // has nothing to draw: water, chests and friends)
    record Placed(int x, int y, int z, BakedQuad[] model, int[] rgb, boolean inflate, int boxRgb, boolean faint, int faces)
        implements GhostField.Piece {

        @Override
        public int quads() {
            return model == null ? Integer.bitCount(faces) : model.length;
        }
    }

    private final GhostField<Placed> field = new GhostField<>(this::bake);
    private final LevelWorld world = new LevelWorld();
    private final QuadInstance instance = new QuadInstance();
    private final List<Placed> visible = new ArrayList<>();
    private final List<BlockStateModelPart> parts = new ArrayList<>();
    private final List<BakedQuad> quads = new ArrayList<>();
    private final BlockPos.MutableBlockPos seedPos = new BlockPos.MutableBlockPos();

    // where the last frame looked from. the tick goes by this, the camera is a render thing (third person, spectating)
    private boolean hasCamera;
    private double camX;
    private double camY;
    private double camZ;

    // a resource pack reload swaps the model set and the atlas under the baked quads, so everything gets baked again
    private BlockStateModelSet bakedWith;
    // the path the field holds, for reparsing. the field's own copy is an Object for the tests
    private String currentPath;

    // the numbers for the log, once per file (not per nudge of the placement)
    private String loggedPath;
    private String frameLoggedPath;
    private int scanGeneration;
    private long scanNanos;
    private long worstTickNanos;
    private int scanTicks;

    private SchematicGhostRenderer() {
        instance.setLightCoords(FULL_BRIGHT);
        instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
    }

    // runs inside Minecraft's constructor with the rest of the bridge. a ghost that can't start is no reason for
    // altoclef not to
    public static void attach(IBaritone primary) {
        try {
            SchematicGhostRenderer renderer = new SchematicGhostRenderer();
            primary.getGameEventHandler().registerEventListener(renderer);
            live = renderer;
        } catch (Throwable t) {
            System.err.println("schematic ghosts could not start, they stay off this session");
            t.printStackTrace();
        }
    }

    // for the level mixin, which has no event to hand this to
    private static volatile SchematicGhostRenderer live;

    // every client side setBlock: single block packets, section packets, prediction, explosions. BlockChangeEvent
    // only sees the section packet, so a block you place by hand would sit under its ghost until the backstop
    public static void blockChanged(int x, int y, int z) {
        SchematicGhostRenderer r = live;
        if (r != null && r.field.active()) {
            Safe.run("schematic ghost block", () -> r.field.blockChanged(x, y, z));
        }
    }

    private static Settings settings() {
        return BaritoneAPI.getSettings();
    }

    // ----- the tick: what to draw and keeping it fresh

    @Override
    public void onTick(TickEvent event) {
        Safe.run("schematic ghost tick", this::update);
    }

    // what to draw right now: the ghost's turned file at its placement. null for nothing: no ghost, no world, no
    // placement, placed in another dimension (the ghost stays chosen, it just isn't drawn here), or still parsing
    public record Target(String path, ISchematic schematic, int x, int y, int z) {
    }

    public static Target target(McSchematics schematics) {
        String path = schematics.ghost();
        if (path == null) {
            return null;
        }
        Placement p = schematics.placement(path);
        if (p == null || !p.inThisDimension()) {
            return null;
        }
        ISchematic turned = schematics.transformed(path);
        return turned == null ? null : new Target(path, turned, p.x(), p.y(), p.z());
    }

    private void update() {
        ClientLevel level = Minecraft.getInstance().level;
        McSchematics schematics = McSchematics.shared();
        Target t = target(schematics);
        if (level != null && t == null && reparsing(schematics)) {
            // the parse cache only keeps two files, clicking through the list can push the ghost's out. it comes back
            // a moment later as the same file, so keep drawing the old one instead of blinking out and rescanning
            return;
        }
        if (t == null || level == null) {
            field.clear();
            return;
        }
        field.target(t.path(), t.schematic(), t.x(), t.y(), t.z());
        currentPath = t.path();
        BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (models != bakedWith) {
            bakedWith = models;
            field.dirtyAll();
        }
        // 128 tops: nothing caps what's stored, only what's drawn, and a busy build at 512 is millions of quads in memory
        field.distance(Math.max(8, Math.min(MAX_DISTANCE, settings().schematicGhostDistance.value)));
        if (hasCamera) {
            field.camera(camX, camY, camZ);
        }
        world.level = level;
        long start = System.nanoTime();
        int scanned;
        try {
            scanned = field.tick(world, System::nanoTime, SCAN_BUDGET_NANOS);
        } finally {
            world.level = null;
        }
        logScan(t.path(), scanned, System.nanoTime() - start);
    }

    // the same ghost, still in this dimension, just parsing again. not the placement moving (that's a new target)
    // and not a file that broke (previewFailed, nothing to wait for)
    private boolean reparsing(McSchematics schematics) {
        String path = schematics.ghost();
        if (path == null || !path.equals(currentPath) || schematics.previewFailed(path)) {
            return false;
        }
        Placement p = schematics.placement(path);
        return p != null && p.inThisDimension() && schematics.transformed(path) == null;
    }

    // one line per target once its first full scan is done, so there's a number to look at without a profiler
    private void logScan(String path, int scanned, long took) {
        int generation = field.generation();
        if (generation != scanGeneration) {
            scanGeneration = generation;
            scanNanos = 0;
            worstTickNanos = 0;
            scanTicks = 0;
        }
        if (scanned > 0) {
            scanNanos += took;
            worstTickNanos = Math.max(worstTickNanos, took);
            scanTicks++;
        }
        if (!path.equals(loggedPath) && field.settled()) {
            loggedPath = path;
            System.out.printf("[soprano] schematic ghost %s: %d sections, %d blocks off, %d pieces, %d quads in range (cap %d),"
                    + " scanned and baked in %.1f ms over %d ticks (worst tick %.1f ms)%n", path, field.sectionCount(),
                field.ghostCount(), field.pieceCount(), field.quadCount(), MAX_QUADS, scanNanos / 1e6, scanTicks,
                worstTickNanos / 1e6);
        }
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (field.active()) {
            event.getBlocks().forEach(pair -> field.blockChanged(pair.first().getX(), pair.first().getY(), pair.first().getZ()));
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (field.active() && event.isPostPopulate()) {
            field.chunkLoaded(event.getX(), event.getZ());
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getState() != EventState.POST) {
            return;
        }
        Safe.run("schematic ghost world", () -> {
            // any new level throws the sections away. an unsaved placement follows you through a portal with the same
            // corner, so nothing else would notice the pieces are the overworld's diff
            field.clear();
            if (event.getWorld() == null) {
                McSchematics.shared().clearGhost();
            }
        });
    }

    // ----- baking: models into quads, once per scanned section

    private List<Placed> bake(List<Ghost> ghosts) {
        Minecraft mc = Minecraft.getInstance();
        BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
        BlockColors colors = mc.getBlockColors();
        List<Placed> out = new ArrayList<>(ghosts.size());
        for (Ghost g : ghosts) {
            Placed p = g.kind() == Kind.EXTRA ? box(g, EXTRA_RGB, true) : model(g, models, colors);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private Placed model(Ghost g, BlockStateModelSet models, BlockColors colors) {
        BlockState want = g.want();
        int kindRgb = kindRgb(g.kind());
        parts.clear();
        quads.clear();
        models.get(want).collectParts(RandomSource.create(want.getSeed(seedPos.set(g.x(), g.y(), g.z()))), parts);
        boolean anyQuads = false;
        for (BlockStateModelPart part : parts) {
            for (Direction d : DIRECTIONS) {
                List<BakedQuad> side = part.getQuads(d);
                anyQuads |= !side.isEmpty();
                if ((g.faces() & (1 << d.get3DDataValue())) != 0) {
                    quads.addAll(side);
                }
            }
            List<BakedQuad> inside = part.getQuads(null);
            anyQuads |= !inside.isEmpty();
            quads.addAll(inside);
        }
        if (!anyQuads) {
            return box(g, kindRgb, false);
        }
        if (quads.isEmpty()) {
            // it has a model, every side of it just happens to be covered
            return null;
        }
        BakedQuad[] model = quads.toArray(new BakedQuad[0]);
        int[] rgb = new int[model.length];
        for (int i = 0; i < model.length; i++) {
            BakedQuad q = model[i];
            Direction shadeDir = q.materialInfo().shadeDirectionOverride() != null ? q.materialInfo().shadeDirectionOverride() : q.direction();
            rgb[i] = multiply(multiply(kindRgb, tint(colors, want, q.materialInfo().tintIndex())), shade(shadeDir));
        }
        return new Placed(g.x(), g.y(), g.z(), model, rgb, g.kind() != Kind.MISSING, 0, false, g.faces());
    }

    private static Placed box(Ghost g, int rgb, boolean faint) {
        return new Placed(g.x(), g.y(), g.z(), null, null, false, rgb, faint, g.faces());
    }

    static int kindRgb(Kind kind) {
        return switch (kind) {
            case MISSING -> MISSING_RGB;
            case WRONG_STATE -> WRONG_STATE_RGB;
            case WRONG_BLOCK -> WRONG_BLOCK_RGB;
            case EXTRA -> EXTRA_RGB;
        };
    }

    // grass and leaves are grey in the atlas, the world paints them. no world here, so the default colour
    private static int tint(BlockColors colors, BlockState state, int tintIndex) {
        if (tintIndex < 0) {
            return 0xFFFFFF;
        }
        try {
            BlockTintSource source = colors.getTintSource(state, tintIndex);
            return source == null ? 0xFFFFFF : source.color(state) & 0xFFFFFF;
        } catch (Throwable t) {
            return 0xFFFFFF;
        }
    }

    // the same side shading the world uses, without it a ghost is a flat sticker of a block
    static float shade(Direction d) {
        if (d == null) {
            return 1f;
        }
        return switch (d) {
            case DOWN -> 0.5f;
            case UP -> 1f;
            case NORTH, SOUTH -> 0.8f;
            case EAST, WEST -> 0.6f;
        };
    }

    static int multiply(int a, int b) {
        int r = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255;
        int g = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255;
        int bl = (a & 0xFF) * (b & 0xFF) / 255;
        return r << 16 | g << 8 | bl;
    }

    static int multiply(int rgb, float f) {
        int r = Math.round(((rgb >> 16) & 0xFF) * f);
        int g = Math.round(((rgb >> 8) & 0xFF) * f);
        int b = Math.round((rgb & 0xFF) * f);
        return r << 16 | g << 8 | b;
    }

    // ----- the frame

    @Override
    public void onRenderPass(RenderEvent event) {
        camX = IRenderer.renderManager.renderPosX();
        camY = IRenderer.renderManager.renderPosY();
        camZ = IRenderer.renderManager.renderPosZ();
        hasCamera = true;
        if (field.active()) {
            Safe.run("schematic ghost draw", () -> draw(event));
        }
    }

    private void draw(RenderEvent event) {
        float alpha = Math.max(0f, Math.min(1f, settings().schematicGhostAlpha.value));
        // the block shader throws away anything under 0.1 anyway
        if (alpha < 0.1f) {
            return;
        }
        long start = System.nanoTime();
        double cx = camX;
        double cy = camY;
        double cz = camZ;
        visible.clear();
        int quadCount = field.forEachVisible(cx, cy, cz, MAX_QUADS, visible::add);
        if (visible.isEmpty()) {
            return;
        }
        PoseStack stack = event.getModelViewStack();
        int a = Math.round(alpha * 255) << 24;
        boolean anyModel = false;
        boolean anyBox = false;
        for (Placed p : visible) {
            anyModel |= p.model() != null;
            anyBox |= p.model() == null;
        }
        if (anyModel) {
            drawModels(stack, a, cx, cy, cz);
        }
        if (anyBox) {
            drawBoxes(stack, alpha, cx, cy, cz);
        }
        String logged = loggedPath;
        if (logged != null && !logged.equals(frameLoggedPath)) {
            frameLoggedPath = logged;
            System.out.printf("[soprano] schematic ghost frame: %d blocks, %d quads in %.2f ms%n", visible.size(),
                quadCount, (System.nanoTime() - start) / 1e6);
        }
    }

    private void drawModels(PoseStack stack, int a, double cx, double cy, double cz) {
        // lines and quads take turns in IRenderer, one draw open at a time, so this one is finished before the boxes
        // the render type goes in up front now, the staged buffer picks its quad sorting when the draw is appended
        RenderType type = RenderTypes.translucentMovingBlock();
        BufferBuilder buf = IRenderer.startBlockQuads(type);
        // a throw half way would leave IRenderer's draw open for whoever renders next, so it's always closed
        try {
            pushModels(buf, stack, a, cx, cy, cz);
        } finally {
            IRenderer.endBuffer(buf, type);
        }
    }

    private void pushModels(BufferBuilder buf, PoseStack stack, int a, double cx, double cy, double cz) {
        for (Placed p : visible) {
            if (p.model() == null) {
                continue;
            }
            stack.pushPose();
            stack.translate((float) (p.x() - cx), (float) (p.y() - cy), (float) (p.z() - cz));
            if (p.inflate()) {
                stack.translate(0.5f, 0.5f, 0.5f);
                stack.scale(INFLATE, INFLATE, INFLATE);
                stack.translate(-0.5f, -0.5f, -0.5f);
            }
            PoseStack.Pose pose = stack.last();
            BakedQuad[] model = p.model();
            int[] rgb = p.rgb();
            for (int i = 0; i < model.length; i++) {
                instance.setColor(a | rgb[i]);
                buf.putBakedQuad(pose, model[i], instance);
            }
            stack.popPose();
        }
    }

    private void drawBoxes(PoseStack stack, float alpha, double cx, double cy, double cz) {
        BufferBuilder buf = IRenderer.startQuads();
        try {
            pushBoxes(buf, stack.last(), alpha, cx, cy, cz);
        } finally {
            IRenderer.endQuads(buf, false);
        }
    }

    private void pushBoxes(BufferBuilder buf, PoseStack.Pose pose, float alpha, double cx, double cy, double cz) {
        for (Placed p : visible) {
            if (p.model() != null) {
                continue;
            }
            // something to dig out is a hint, not a block: fainter than the ghosts
            int a = Math.round(alpha * (p.faint() ? 0.5f : 1f) * 255) << 24;
            float x0 = (float) (p.x() - BOX_GROW - cx);
            float y0 = (float) (p.y() - BOX_GROW - cy);
            float z0 = (float) (p.z() - BOX_GROW - cz);
            float s = (float) (1 + 2 * BOX_GROW);
            box(buf, pose, x0, y0, z0, x0 + s, y0 + s, z0 + s, a, p.boxRgb(), p.faces());
        }
    }

    // only the sides GhostScan left open, same culling as the models
    private static void box(BufferBuilder buf, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1,
                            float z1, int a, int rgb, int faces) {
        int ns = a | multiply(rgb, 0.8f);
        int ew = a | multiply(rgb, 0.6f);
        if (open(faces, Direction.UP)) {
            quad(buf, pose, a | rgb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        }
        if (open(faces, Direction.DOWN)) {
            quad(buf, pose, a | multiply(rgb, 0.5f), x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        }
        if (open(faces, Direction.NORTH)) {
            quad(buf, pose, ns, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        }
        if (open(faces, Direction.SOUTH)) {
            quad(buf, pose, ns, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        }
        if (open(faces, Direction.WEST)) {
            quad(buf, pose, ew, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        }
        if (open(faces, Direction.EAST)) {
            quad(buf, pose, ew, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        }
    }

    private static boolean open(int faces, Direction d) {
        return (faces & (1 << d.get3DDataValue())) != 0;
    }

    private static void quad(BufferBuilder buf, PoseStack.Pose pose, int argb, float ax, float ay, float az, float bx,
                             float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
        buf.addVertex(pose, ax, ay, az).setColor(argb);
        buf.addVertex(pose, bx, by, bz).setColor(argb);
        buf.addVertex(pose, cx, cy, cz).setColor(argb);
        buf.addVertex(pose, dx, dy, dz).setColor(argb);
    }

    // the client level for one tick's scan. set and cleared around it so a dead level never hangs around in here
    private static final class LevelWorld implements GhostScan.World {
        ClientLevel level;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        @Override
        public boolean loaded(int chunkX, int chunkZ) {
            // not level.hasChunk, ClientLevel's is a flat 'return true' and the hole would be one big ghost
            return level.getChunkSource().hasChunk(chunkX, chunkZ);
        }

        @Override
        public BlockState state(int x, int y, int z) {
            return level.getBlockState(pos.set(x, y, z));
        }
    }
}
