package adris.altoclef.util.snapshot;

import adris.altoclef.AltoClef;
import adris.altoclef.BuildInfo;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.Tuning;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.precompute.Ternary;
import baritone.utils.BlockStateInterface;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// the buddy's fork: snapshot regression tests, the capture half. every death in the nether so far became one more special rule, the
// rules meet, and a fix in one layer breaking another showed only in the drills hours later. so while an order runs in the nether
// this keeps the last 30 s as frames (SnapshotFrame: the buddy, what lives near, the blocks of a box round the buddy, what the
// autopilot decided), one every EVERY ticks and one at every cell decided (chose; a swing, hit, rides on the next frame). on a
// death, and a few seconds after the hp falls below LOW_HP, the frames go to one gzip JSON file (SnapshotWriter) in the game
// folder's logs/snapshots. the harness in the tests rebuilds the cell world from each frame and runs the pure planners and the
// invariants over it. off unless the tuning key snapshots is 1 or more: no real buddy writes these unless asked
public final class Snapshots {
    public static final String KEY = "snapshots";
    // a frame this often (ticks) while recording, and the frames kept (ticks back)
    static final int EVERY = 10;
    static final int KEEP_TICKS = 600;
    // the hp a near miss starts at, the file written this long after (the aftermath in it too), at most one such file per KEEP_TICKS
    static final float LOW_HP = 6;
    static final int LOW_AFTER = 60;
    // a death this long after the last frame has no frames before it
    static final int STALE_TICKS = 100;
    // the box round the buddy: HALF each way flat from a centre on a grid of GRID (so a buddy that sways a block keeps the same box
    // and a frame can say "same"), DOWN under and UP over the feet's level (on a grid of two); under the box each open column is
    // followed down BELOW more cells for what ends the fall. HALF covers BlazeCover's walk (RADIUS 14) and a knock (2.5) from it
    static final int HALF = 18;
    static final int GRID = 4;
    static final int DOWN = 8;
    static final int UP = 9;
    static final int BELOW = 48;
    // living things and shots this close; ghasts and blazes as far as they shoot from, or the cover's sight test looks
    // (BlazeCover.SIGHT + RADIUS)
    static final double NEAR = 24;
    static final double GHASTS = 64;
    static final double BLAZES = 48;
    static final int MOST_ENTITIES = 64;
    // the folder keeps this many files, the oldest go
    static final int MOST_FILES = 200;
    // the per-state reading is looked at afresh this often (captures): Baritone's blocksToAvoid can change
    static final int PALETTE_FRESH = 60;

    private static final ArrayDeque<SnapshotFrame> ring = new ArrayDeque<>();
    private static final List<SnapshotPick> pending = new ArrayList<>();
    private static final SnapshotPalette palette = new SnapshotPalette();
    private static final Reference2IntOpenHashMap<BlockState> fixed = new Reference2IntOpenHashMap<>();
    // states whose walk reading depends on the cell (a carpet, a snow layer): the index of the open reading and of the solid one
    private static final java.util.IdentityHashMap<BlockState, int[]> byCell = new java.util.IdentityHashMap<>();
    private static ExecutorService writer;

    private static boolean recording;
    // a cell was decided this tick: a frame now, the world as it was decided in (a swing waits for the next frame)
    private static boolean decided;
    private static long tick;
    private static long lastCapture = Long.MIN_VALUE / 2;
    private static boolean wasDead;
    private static float lastHp = Float.MAX_VALUE;
    private static long lowAt = -1;
    private static long lastLowWrite = Long.MIN_VALUE / 2;
    private static long captures;
    private static long boxes;
    private static double captureNanos;
    private static double maxNanos;
    // the columns under the box, kept while the box stays put (they hardly change and a lava sea is a long way down)
    private static int[] belowCache;
    private static long belowKey = Long.MIN_VALUE;
    private static long belowAt;

    static {
        fixed.defaultReturnValue(-1);
    }

    private Snapshots() {
    }

    public static boolean on() {
        return Tuning.get(KEY, 0) >= 1;
    }

    // a decision: the cell chosen, kept with the frame captured at the end of this tick (the world as it was decided in). OFF when
    // not recording, whose setters do nothing
    public static SnapshotPick chose(String kind, int x, int y, int z) {
        if (!recording) return SnapshotPick.OFF;
        SnapshotPick p = new SnapshotPick(kind);
        p.tick = tick;
        p.cell = new int[]{x, y, z};
        pending.add(p);
        decided = true;
        return p;
    }

    public static SnapshotPick chose(String kind, BlockPos pos) {
        return pos == null ? SnapshotPick.OFF : chose(kind, pos.getX(), pos.getY(), pos.getZ());
    }

    public static SnapshotPick chose(String kind, int[] c) {
        return c == null ? SnapshotPick.OFF : chose(kind, c[0], c[1], c[2]);
    }

    // a swing that went out (PlayerExtraController.attack): from where, at what box, with what reach
    public static void hit(LocalPlayer player, Entity target) {
        if (!recording || player == null || target == null) return;
        SnapshotPick p = new SnapshotPick("hit");
        p.tick = tick;
        p.target = target.getId();
        p.targetType = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath();
        Vec3 eye = player.getEyePosition();
        p.eye = new double[]{eye.x, eye.y, eye.z};
        AABB b = target.getBoundingBox();
        p.box = new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
        p.reach = player.entityInteractionRange();
        pending.add(p);
    }

    // every client tick, after the task runner (AltoClef.onClientTick)
    public static void tick(AltoClef mod) {
        try {
            tickInner(mod);
        } catch (Throwable t) {
            // a recorder that throws must never take the tick down with it
            recording = false;
            decided = false;
            pending.clear();
            Debug.logInternal("snapshots: off for this tick, " + t);
        }
    }

    private static void tickInner(AltoClef mod) {
        if (!on()) {
            if (!ring.isEmpty() || recording) {
                ring.clear();
                pending.clear();
                recording = false;
            }
            return;
        }
        LocalPlayer player = mod.getPlayer();
        ClientLevel level = mod.getWorld();
        if (player == null || level == null) {
            recording = false;
            pending.clear();
            return;
        }
        tick++;
        boolean dead = player.isDeadOrDying();
        if (dead && !wasDead) {
            wasDead = true;
            lowAt = -1;
            if (!ring.isEmpty() && tick - ring.peekLast().tick <= STALE_TICKS) {
                // (the frame of the death itself, the blocks and the foes as they were)
                capture(mod, player, level);
                write("death");
            }
            pending.clear();
            decided = false;
            recording = false;
            return;
        }
        wasDead = dead;
        if (dead) {
            recording = false;
            pending.clear();
            return;
        }
        boolean active = WorldHelper.getCurrentDimension() == Dimension.NETHER && mod.getUserTaskChain() != null && mod.getUserTaskChain().isActive();
        if (active && (tick - lastCapture >= EVERY || decided)) {
            capture(mod, player, level);
            pending.clear();
        } else if (!active) {
            pending.clear();
        }
        decided = false;
        recording = active;
        while (!ring.isEmpty() && tick - ring.peekFirst().tick > KEEP_TICKS) ring.pollFirst();

        float hp = player.getHealth();
        if (active && hp < LOW_HP && lastHp >= LOW_HP && lowAt < 0 && tick - lastLowWrite > KEEP_TICKS) {
            lowAt = tick + LOW_AFTER;
        }
        lastHp = hp;
        if (lowAt >= 0 && tick >= lowAt) {
            lowAt = -1;
            if (!ring.isEmpty()) {
                lastLowWrite = tick;
                write("low");
            }
        }
    }

    // ---- the capture

    private static void capture(AltoClef mod, LocalPlayer player, ClientLevel level) {
        long start = System.nanoTime();
        SnapshotFrame f = new SnapshotFrame();
        f.tick = tick;
        f.gameTime = level.getGameTime();
        f.wallMillis = System.currentTimeMillis();
        me(f, player);
        entities(f, mod, player, level);
        blocks(f, mod, player, level);
        decision(f, mod);
        f.picks.addAll(pending);
        ring.addLast(f);
        lastCapture = tick;
        double took = System.nanoTime() - start;
        captures++;
        captureNanos += took;
        maxNanos = Math.max(maxNanos, took);
    }

    private static void me(SnapshotFrame f, LocalPlayer p) {
        f.x = p.getX();
        f.y = p.getY();
        f.z = p.getZ();
        f.eyeY = p.getEyeY();
        Vec3 v = p.getDeltaMovement();
        f.vx = v.x;
        f.vy = v.y;
        f.vz = v.z;
        f.yaw = p.getYRot();
        f.pitch = p.getXRot();
        f.hp = p.getHealth();
        f.absorption = p.getAbsorptionAmount();
        f.reach = p.entityInteractionRange();
        f.fireTicks = p.getRemainingFireTicks();
        f.hurtTime = p.hurtTime;
        f.air = p.getAirSupply();
        f.onGround = p.onGround();
        f.inLava = p.isInLava();
        f.held = item(p.getMainHandItem());
        f.offhand = item(p.getOffhandItem());
        EquipmentSlot[] slots = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
        f.armour = new String[slots.length];
        for (int i = 0; i < slots.length; i++) f.armour[i] = item(p.getItemBySlot(slots[i]));
        for (MobEffectInstance e : p.getActiveEffects()) {
            var key = BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value());
            f.effects.add((key == null ? "?" : key.getPath()) + " " + e.getDuration() + " " + e.getAmplifier());
        }
        var inv = p.getInventory();
        TreeMap<String, Integer> bag = new TreeMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) bag.merge(item(s), s.getCount(), Integer::sum);
        }
        f.bag.putAll(bag);
        DamageSource d = p.getLastDamageSource();
        if (d != null) {
            f.damage = d.getMsgId();
            Entity by = d.getEntity();
            if (by != null) {
                f.damageBy = BuiltInRegistries.ENTITY_TYPE.getKey(by.getType()).getPath();
                f.damageById = by.getId();
            }
        }
    }

    private static String item(ItemStack s) {
        return s == null || s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    private static void entities(SnapshotFrame f, AltoClef mod, LocalPlayer player, ClientLevel level) {
        AABB area = player.getBoundingBox().inflate(GHASTS);
        for (Entity e : level.getEntities(player, area, e -> e instanceof LivingEntity || e instanceof Projectile)) {
            if (f.entities.size() >= MOST_ENTITIES) break;
            if (e.distanceTo(player) > (e instanceof Ghast ? GHASTS : e instanceof Blaze ? BLAZES : NEAR)) continue;
            SnapshotFrame.Ent o = new SnapshotFrame.Ent();
            o.type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            o.id = e.getId();
            o.x = e.getX();
            o.y = e.getY();
            o.z = e.getZ();
            Vec3 v = e.getDeltaMovement();
            o.vx = v.x;
            o.vy = v.y;
            o.vz = v.z;
            o.eye = e.getEyeHeight();
            o.width = e.getBbWidth();
            o.height = e.getBbHeight();
            o.fire = e.isOnFire();
            if (e instanceof LivingEntity living) {
                o.hp = living.getHealth();
                try {
                    o.angry = EntityHelper.isAngryAtPlayer(mod, e);
                } catch (Throwable ignored) {
                    // (a judgement that throws for one kind leaves the flag off)
                }
                o.los = player.hasLineOfSight(e);
                if (e instanceof Blaze blaze) o.charge = blaze.isOnFire() ? 1 : 0;
                else if (e instanceof Ghast ghast) o.charge = ghast.isCharging() ? 1 : 0;
                else if (e instanceof Creeper creeper) o.charge = creeper.getSwelling(1);
            }
            f.entities.add(o);
        }
    }

    private static void blocks(SnapshotFrame f, AltoClef mod, LocalPlayer player, ClientLevel level) {
        if (++boxes % PALETTE_FRESH == 0) {
            fixed.clear();
            byCell.clear();
        }
        BlockPos feet = player.blockPosition();
        int sx = 2 * HALF + 1, sz = 2 * HALF + 1, sy = DOWN + UP + 1;
        int ox = Math.floorDiv(feet.getX(), GRID) * GRID + GRID / 2 - HALF;
        int oz = Math.floorDiv(feet.getZ(), GRID) * GRID + GRID / 2 - HALF;
        int oy = Math.floorDiv(feet.getY(), 2) * 2 - DOWN;
        short[] cells = new short[sx * sy * sz];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        BlockStateInterface[] bsi = new BlockStateInterface[1];
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                int wx = ox + x, wz = oz + z;
                boolean loaded = level.hasChunk(wx >> 4, wz >> 4);
                LevelChunk chunk = loaded ? level.getChunk(wx >> 4, wz >> 4) : null;
                for (int y = 0; y < sy; y++) {
                    int i = (y * sz + z) * sx + x;
                    if (chunk == null) {
                        cells[i] = 0;
                        continue;
                    }
                    at.set(wx, oy + y, wz);
                    cells[i] = (short) index(mod, level, chunk.getBlockState(at), at, bsi);
                }
            }
        }
        // (one array for a box that did not change: the ring holds a minute of standing still for the price of one)
        SnapshotFrame last = ring.peekLast();
        if (last != null && last.cells != null && last.ox == ox && last.oy == oy && last.oz == oz && java.util.Arrays.equals(last.cells, cells)) {
            cells = last.cells;
        }
        f.ox = ox;
        f.oy = oy;
        f.oz = oz;
        f.sx = sx;
        f.sy = sy;
        f.sz = sz;
        f.cells = cells;
        f.below = below(mod, level, f, at, bsi, last);
    }

    // the columns under the box (SnapshotFrame.below), kept for 40 ticks while the box stays where it is
    private static int[] below(AltoClef mod, ClientLevel level, SnapshotFrame f, BlockPos.MutableBlockPos at, BlockStateInterface[] bsi, SnapshotFrame last) {
        long key = ((long) f.ox & 0xFFFFFF) << 40 | ((long) f.oz & 0xFFFFFF) << 16 | ((long) f.oy & 0xFFFF);
        if (belowCache != null && key == belowKey && tick - belowAt < 40) return belowCache;
        int[] out = new int[f.sx * f.sz];
        List<SnapshotPalette.Entry> entries = null;
        for (int x = 0; x < f.sx; x++) {
            for (int z = 0; z < f.sz; z++) {
                short bottom = f.cells[z * f.sx + x];
                if (entries == null || bottom >= entries.size()) entries = palette.entries();
                SnapshotPalette.Entry e = entries.get(bottom);
                if (e.walk() != 'O' || e.body() != 'O') continue;
                int wx = f.ox + x, wz = f.oz + z;
                LevelChunk chunk = level.getChunk(wx >> 4, wz >> 4);
                int found = SnapshotFrame.NONE_BELOW;
                for (int d = 1; d <= BELOW; d++) {
                    at.set(wx, f.oy - d, wz);
                    if (level.isOutsideBuildHeight(at)) break;
                    int idx = index(mod, level, chunk.getBlockState(at), at, bsi);
                    if (idx >= entries.size()) entries = palette.entries();
                    SnapshotPalette.Entry c = entries.get(idx);
                    if (c.walk() == 'O' && c.body() == 'O') continue;
                    found = d << 16 | idx;
                    break;
                }
                out[z * f.sx + x] = found;
            }
        }
        if (last != null && last.below != null && java.util.Arrays.equals(last.below, out)) out = last.below;
        belowCache = out;
        belowKey = key;
        belowAt = tick;
        return out;
    }

    // the palette index of a state at a cell, read both ways the planners read it (SnapshotPalette)
    private static int index(AltoClef mod, ClientLevel level, BlockState state, BlockPos at, BlockStateInterface[] bsi) {
        int known = fixed.getInt(state);
        if (known >= 0) return known;
        int[] pair = byCell.get(state);
        if (pair != null) return walkOpenHere(mod, state, at, bsi) ? pair[0] : pair[1];
        char body = state.getFluidState().is(FluidTags.LAVA) ? 'L' : state.getFluidState().is(FluidTags.WATER) ? 'W'
                : state.getCollisionShape(level, at).isEmpty() ? 'O' : 'S';
        boolean full = state.isCollisionShapeFullBlock(level, at);
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String text = state.toString();
        int open = text.indexOf('[');
        String props = open >= 0 && text.endsWith("]") ? text.substring(open + 1, text.length() - 1) : "";
        char walk;
        if (MovementHelper.isLava(state)) walk = 'L';
        else if (MovementHelper.isWater(state)) walk = 'W';
        else {
            Ternary t = MovementHelper.canWalkThroughBlockState(state);
            if (t == Ternary.MAYBE) {
                pair = new int[]{palette.add(new SnapshotPalette.Entry(id, props, 'O', body, full)),
                        palette.add(new SnapshotPalette.Entry(id, props, 'S', body, full))};
                byCell.put(state, pair);
                return walkOpenHere(mod, state, at, bsi) ? pair[0] : pair[1];
            }
            walk = t == Ternary.YES ? 'O' : 'S';
        }
        int idx = palette.add(new SnapshotPalette.Entry(id, props, walk, body, full));
        fixed.put(state, idx);
        return idx;
    }

    private static boolean walkOpenHere(AltoClef mod, BlockState state, BlockPos at, BlockStateInterface[] bsi) {
        if (bsi[0] == null) {
            var ctx = mod.getClientBaritone().getPlayerContext();
            if (ctx == null) return false;
            bsi[0] = new BlockStateInterface(ctx, true);
        }
        return MovementHelper.canWalkThroughPosition(bsi[0], at.getX(), at.getY(), at.getZ(), state);
    }

    private static void decision(SnapshotFrame f, AltoClef mod) {
        TaskChain top = mod.getTaskRunner() == null ? null : mod.getTaskRunner().getCurrentTaskChain();
        if (top != null) {
            f.chain = top.getName();
            List<Task> tasks = top.getTasks();
            for (Task t : tasks) f.tasks.add(name(t));
            if (!tasks.isEmpty()) {
                String state = tasks.get(tasks.size() - 1).getDebugState();
                f.leaf = state == null ? null : state.length() > 120 ? state.substring(0, 120) : state;
            }
        }
        var user = mod.getUserTaskChain();
        if (user != null && user != top && user.isActive()) {
            for (Task t : user.getTasks()) f.userTasks.add(name(t));
        }
        var defence = mod.getMobDefenseChain();
        if (defence != null) {
            var mode = defence.combatMode();
            f.combat = mode == null ? null : mode.name();
            f.fight = defence.combatFightName();
            f.target = defence.combatTargetId();
        }
    }

    private static String name(Task t) {
        String n = t.getClass().getSimpleName();
        return n.isEmpty() ? t.getClass().getName() : n;
    }

    // ---- the file

    private static void write(String reason) {
        List<SnapshotFrame> frames = new ArrayList<>(ring);
        if (frames.isEmpty()) return;
        SnapshotWriter.Header h = new SnapshotWriter.Header();
        h.reason = reason;
        h.written = LocalDateTime.now().toString();
        h.build = BuildInfo.id();
        h.dimension = String.valueOf(WorldHelper.getCurrentDimension()).toLowerCase(java.util.Locale.ROOT);
        h.tuning = Tuning.all();
        h.captures = captures;
        h.avgMicros = captures == 0 ? 0 : captureNanos / captures / 1000;
        h.maxMicros = maxNanos / 1000;
        h.half = HALF;
        h.down = DOWN;
        h.up = UP;
        h.below = BELOW;
        List<SnapshotPalette.Entry> entries = palette.entries();
        Path dir = folder();
        String name = "snap-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + "-" + reason + ".json.gz";
        if (writer == null) {
            writer = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "buddy-snapshots");
                t.setDaemon(true);
                return t;
            });
        }
        writer.submit(() -> {
            try {
                Files.createDirectories(dir);
                Path part = dir.resolve(name + ".part");
                try (OutputStream out = Files.newOutputStream(part)) {
                    SnapshotWriter.write(out, h, entries, frames);
                }
                Path done = dir.resolve(name);
                Files.move(part, done, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                prune(dir);
                Debug.logInternal(String.format(java.util.Locale.ROOT, "snapshots: wrote %s (%d frames, %d KB, a capture %.0f us on average, %.0f at most)",
                        name, frames.size(), Files.size(done) / 1024, h.avgMicros, h.maxMicros));
            } catch (Throwable t) {
                Debug.logInternal("snapshots: could not write " + name + ": " + t);
            }
        });
    }

    // the game folder's logs/snapshots; the system property buddy.snapshots puts them elsewhere (a rig that brings back
    // another folder)
    static Path folder() {
        String set = System.getProperty("buddy.snapshots");
        if (set != null && !set.isBlank()) return Path.of(set);
        File game = Minecraft.getInstance().gameDirectory;
        return game.toPath().resolve("logs").resolve("snapshots");
    }

    private static void prune(Path dir) throws java.io.IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = new ArrayList<>(s.filter(p -> p.getFileName().toString().endsWith(".json.gz")).sorted().toList());
        }
        for (int i = 0; i < files.size() - MOST_FILES; i++) Files.deleteIfExists(files.get(i));
    }
}
