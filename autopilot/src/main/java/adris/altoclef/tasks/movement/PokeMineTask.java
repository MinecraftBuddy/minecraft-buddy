package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BreakRules;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.PokePattern;
import adris.altoclef.util.helpers.ToolPick;
import adris.altoclef.util.helpers.WorldHelper;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

// (Tuning gold_poke_shafts, 14 Nov 2026) the gold mine's poke hole pattern (PokePattern) in the world, in place of
// baritone's legit mine, which digs one 1x2 tunnel straight away from where it starts. the branch mine hands its tick here
// once its own checks pass (the place watch, water and ice, the sculk, a spot given up, the band), so those keep the tick
// first. what the buddy does here:
// - a hole's cell is broken from the station's cell, through the fork's DestroyBlockTask, only while a ray from the eye
//   reaches it; never walked into. a cell that is open (not by the buddy's own digging), holds or touches water or lava, has
//   open rock beside it, sand or gravel over it, or will not break, is not dug, and its hole ends there
// - a step of the main tunnel is walked to by baritone, which digs its two cells, as the old tunnel did; a step that would
//   open into a cave, water, lava or a drop is not taken, and the run turns (a connector, its other side, or a run of its own)
// - the pattern stays in solid rock: caves are poor in gold (ore that would touch air is dropped half the time when the world
//   is made), so a hole that meets a cave turns the run away from it after the station, never along the cave
// - an ore whose face shows is taken as before: the collect task's pursuit sees it in the block tracker and takes the tick;
//   the run is kept across tasks and the next branch mine resumes it where it left
// - a cell just opened shows its real neighbours a moment later (the server hides buried blocks below y 64 as ores): the next
//   cell is looked at REVEAL ticks after a dig, and a dug cell's neighbours are counted then
public class PokeMineTask extends Task {

    // ticks after a dig before the next cell is looked at and the dug one's neighbours are counted
    static final long REVEAL = 4;
    // a hole cell with no ray to it from the station for this long is passed over, its hole ends
    static final long NO_REACH = 10;
    // a hole cell not broken in this long is passed over (DestroyBlockTask's own clocks ban, they do not end our wait)
    static final long HOLE_STUCK = 300;
    // a step not reached in ten game seconds closes the way, as for the old tunnel
    static final long STEP_STUCK = 200;
    // the way back to the run's cell after an ore taken or a fight; past this, a run of its own from where the buddy stands
    static final long BACK_STUCK = 400;
    // a run is resumed within this many blocks of its cell, and within FRESH game ticks of its last tick
    static final int RESUME = 16;
    static final long FRESH = 12000;
    // runs that found nothing to turn to, this many in RESET_WINDOW ticks: the old tunnel of its own for a game minute instead
    static final int RESETS = 4;
    static final long RESET_WINDOW = 1200;

    private final Block[] _blocks;
    private final int _y;
    // the heading a new run starts with: the branch mine's
    private int _dx = 1, _dz;
    private boolean _fallback;

    private BlockPos _digAt;
    private long _digSince;
    private Task _dig;
    private BlockPos _noReachAt;
    private long _noReachSince;
    private BlockPos _stepAt;
    private long _stepSince;
    private Task _walk;
    private BlockPos _backTo;
    private long _backSince;

    // what a run learned, kept across tasks in one level: the collect task takes an ore with a task of its own, and the branch
    // mine and this task are made anew after it
    private static volatile Memory MEM;

    private static final class Watched {
        String name;
        final int what;
        final long since;

        Watched(String name, int what, long since) {
            this.name = name;
            this.what = what;
            this.since = since;
        }
    }

    private static final int TUNNEL = 0, HOLE = 1, CONNECTOR = 2;

    private static final class Memory {
        final Object level;
        PokePattern.Run run;
        long last = Long.MIN_VALUE;
        // cells the pattern dug, and cells shown solid beside one (dug later by the buddy, an ore taken: open, but no cave)
        final LongOpenHashSet dug = new LongOpenHashSet();
        final LongOpenHashSet shown = new LongOpenHashSet();
        // ores whose face the pattern showed, for the collect task's reach rule
        final LongOpenHashSet ores = new LongOpenHashSet();
        // dug cells and the game time they went, {pos, time, counted}: their neighbours looked at REVEAL ticks on
        final ArrayDeque<long[]> reveal = new ArrayDeque<>();
        // cells of the move under way, watched until they go: their block while it stands, and what they count as
        final Map<Long, Watched> watch = new HashMap<>();
        long lastDig = Long.MIN_VALUE / 2;
        final ArrayDeque<Long> resets = new ArrayDeque<>();
        // the segment under way: cells dug by kind, hole cells passed over, caves met, cells shown, ores seen, which pick broke what
        int tunnel, holes, connector, skipped, caves, faces, oresSeen, wanted, segments;
        final TreeMap<String, Integer> picks = new TreeMap<>();

        Memory(Object level) {
            this.level = level;
        }
    }

    public PokeMineTask(Block[] blocks, int y) {
        _blocks = blocks;
        _y = y;
    }

    public int y() {
        return _y;
    }

    // the heading a new run starts with, from the branch mine each tick
    public void heading(int dx, int dz) {
        if (dx != 0 || dz != 0) {
            _dx = dx;
            _dz = dz;
        }
    }

    // the way the run under way goes, for the branch mine's own heading (its place watch turns from there), or null
    public static int[] runHeading() {
        Memory m = MEM;
        return m == null || m.run == null ? null : new int[]{m.run.dx(), m.run.dz()};
    }

    // true once: runs kept finding nothing to turn to, and the branch mine digs its old tunnel for a while
    public boolean takeFallback() {
        boolean f = _fallback;
        _fallback = false;
        return f;
    }

    // the run is over (the branch mine's place watch fired, or it gave the depth up here): the next poke mine starts its own
    // run where it stands, and never walks back to this one's cell
    public static void dropRun(String why) {
        Memory m = MEM;
        if (m == null || m.run == null) return;
        logSegment(m, why);
        Debug.logInternal("branch mine: poke holes left at " + m.run.x() + ", " + m.run.y() + ", " + m.run.z() + " (" + why + ", gold_poke_shafts)");
        m.run = null;
    }

    // an ore whose face this pattern showed: the collect task takes it wherever it lies, not only within 5 blocks
    public static boolean oreShown(BlockPos pos) {
        Memory m = MEM;
        return m != null && m.ores.contains(pos.asLong());
    }

    // a start or a resume after another task (a fight, an ore taken): every clock afresh, so time away is no stall here
    @Override
    protected void onStart(AltoClef mod) {
        endDig();
        _stepAt = null;
        _walk = null;
        _backTo = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        var level = mod.getWorld();
        long now = level.getGameTime();
        BlockPos feet = mod.getPlayer().blockPosition();
        Memory m = MEM;
        if (m == null || m.level != level) {
            m = new Memory(level);
            MEM = m;
        }
        watchTick(mod, m, now);
        revealTick(mod, m, now);
        if (m.run == null || m.run.y() != _y || now < m.last || now - m.last > FRESH
                || Math.abs(feet.getY() - _y) > 3 || horizontal(feet, m.run.x(), m.run.z()) > RESUME) {
            String why = m.run == null ? "a new mine" : m.run.y() != _y ? "a new height" : now < m.last || now - m.last > FRESH
                    ? "the last run is old" : "the last run's cell is far";
            logSegment(m, "a new run");
            newRun(mod, m, feet, _dx, _dz, why);
        }
        m.last = now;
        for (int i = 0; i < 24; i++) {
            PokePattern.Run run = m.run;
            PokePattern.Move mv = run.next();
            BlockPos at = new BlockPos(run.x(), run.y(), run.z());
            if (mv.kind() == PokePattern.Kind.HOLE) {
                if (!feet.equals(at)) return back(mod, m, feet, at, now);
                _backTo = null;
                BlockPos cell = new BlockPos(mv.x(), mv.y(), mv.z());
                BlockState st = level.getBlockState(cell);
                if (st.isAir() && ours(m, cell)) {
                    endDig();
                    boolean was = run.connecting();
                    run.holeDone();
                    if (!was && run.connecting()) logTurn(m, "after the station");
                    continue;
                }
                // the real block of a cell just opened next to it comes a moment after the dig
                if (now - m.lastDig < REVEAL && now >= m.lastDig) {
                    setDebugState("Poke holes: a look at the opened rock");
                    return null;
                }
                String bad = holeBad(mod, m, cell, mv, at);
                boolean breaking = mod.getControllerExtras().isBreakingBlock() && cell.equals(mod.getControllerExtras().getBreakingBlockPos());
                if (bad == null && !breaking && LookHelper.getReach(cell).isEmpty()) {
                    if (!cell.equals(_noReachAt)) {
                        _noReachAt = cell;
                        _noReachSince = now;
                    }
                    if (now - _noReachSince <= NO_REACH) {
                        setDebugState("Poke holes: no ray to " + cell.toShortString() + " yet");
                        return null;
                    }
                    bad = "out of reach";
                }
                if (bad == null && cell.equals(_digAt) && _dig != null && now - _digSince > HOLE_STUCK) bad = "not broken in 15 s";
                if (bad != null) {
                    m.skipped++;
                    boolean cave = bad.startsWith("open") || bad.startsWith("water");
                    if (cave) m.caves++;
                    endDig();
                    boolean was = run.connecting();
                    run.skipSide(cave);
                    Debug.logInternal("branch mine: poke hole cell " + cell.toShortString() + " (" + (mv.side() == 0 ? "left" : "right")
                            + ", " + mv.depth() + " deep) not dug: " + bad + " (gold_poke_shafts)");
                    if (!was && run.connecting()) logTurn(m, cave ? "a hole met a cave, away from it" : "after the station");
                    continue;
                }
                _noReachAt = null;
                if (_dig == null || !cell.equals(_digAt)) {
                    _digAt = cell;
                    _digSince = now;
                    _dig = new DestroyBlockTask(cell);
                }
                watch(m, cell, st, HOLE, now);
                setDebugState("Poke holes: the " + (mv.side() == 0 ? "left" : "right") + " hole, " + mv.depth() + " of " + run.depth() + " deep");
                return _dig;
            }
            endDig();
            BlockPos step = new BlockPos(mv.x(), mv.y(), mv.z());
            if (feet.equals(step)) {
                _stepAt = null;
                _walk = null;
                if (run.stepped()) logSegment(m, run.station() ? "a station" : "a new leg");
                continue;
            }
            if (!feet.equals(at)) return back(mod, m, feet, at, now);
            _backTo = null;
            if (!step.equals(_stepAt)) {
                if (now - m.lastDig < REVEAL && now >= m.lastDig) {
                    setDebugState("Poke holes: a look at the opened rock");
                    return null;
                }
                String bad = stepBad(mod, m, at, step);
                if (bad != null) {
                    if (turn(mod, m, feet, step, bad, now)) return null;
                    continue;
                }
                _stepAt = step;
                _stepSince = now;
                _walk = new GetToBlockTask(step);
                int what = run.connecting() ? CONNECTOR : TUNNEL;
                watch(m, step, level.getBlockState(step), what, now);
                watch(m, step.above(), level.getBlockState(step.above()), what, now);
            }
            if (now - _stepSince > STEP_STUCK) {
                _stepAt = null;
                if (turn(mod, m, feet, step, "not reached in 10 s", now)) return null;
                continue;
            }
            setDebugState(run.connecting() ? "Poke holes: " + run.steps() + " of " + PokePattern.GAP + " to the next leg"
                    : "Poke holes: leg " + run.legs() + ", step " + (run.steps() + 1));
            return _walk;
        }
        return null;
    }

    private void endDig() {
        _dig = null;
        _digAt = null;
        _noReachAt = null;
    }

    // back to the run's cell (after an ore taken, a fight, a push); not reached in BACK_STUCK, a run of its own from here
    private Task back(AltoClef mod, Memory m, BlockPos feet, BlockPos at, long now) {
        if (!at.equals(_backTo)) {
            _backTo = at;
            _backSince = now;
        } else if (now - _backSince > BACK_STUCK) {
            _backTo = null;
            logSegment(m, "could not get back");
            newRun(mod, m, feet, m.run.dx(), m.run.dz(), "could not get back to " + at.toShortString() + " in 20 s");
            return null;
        }
        setDebugState("Poke holes: back to " + at.toShortString());
        return new GetToBlockTask(at);
    }

    // the step is not taken: the run turns, or a run of its own here. true when the tick ends (the old tunnel's turn)
    private boolean turn(AltoClef mod, Memory m, BlockPos feet, BlockPos step, String why, long now) {
        _stepAt = null;
        _walk = null;
        if (why.startsWith("open") || why.startsWith("water")) m.caves++;
        logSegment(m, "the way closed");
        PokePattern.Turn t = m.run.blocked();
        if (t == PokePattern.Turn.TURNED) {
            logTurn(m, "the step to " + step.toShortString() + " is not taken: " + why);
            return false;
        }
        while (!m.resets.isEmpty() && (now < m.resets.peekFirst() || now - m.resets.peekFirst() > RESET_WINDOW)) m.resets.removeFirst();
        m.resets.addLast(now);
        if (m.resets.size() >= RESETS) {
            m.resets.clear();
            m.run = null;
            _fallback = true;
            Debug.logInternal("branch mine: poke holes found no way into the rock " + RESETS + " times in a game minute at "
                    + feet.toShortString() + " (" + why + "), the old tunnel for a game minute (gold_poke_shafts)");
            return true;
        }
        // a quarter turned from the way that closed
        newRun(mod, m, feet, -m.run.dz(), m.run.dx(), "nothing to turn to before " + step.toShortString() + ": " + why);
        return false;
    }

    // a run from the feet, along the first way from (dx, dz), a quarter at a time, whose step may be taken into rock not dug
    // yet (not back along the buddy's own tunnel), else into any it may be taken
    private void newRun(AltoClef mod, Memory m, BlockPos feet, int dx, int dz, String why) {
        endDig();
        _stepAt = null;
        _walk = null;
        _backTo = null;
        if (m.dug.size() + m.shown.size() > 200_000) {
            m.dug.clear();
            m.shown.clear();
        }
        if (m.ores.size() > 4096) m.ores.clear();
        BlockPos at = new BlockPos(feet.getX(), _y, feet.getZ());
        // where the buddy stands is no cave, and what it shows round it counts for no segment
        m.dug.add(at.asLong());
        m.dug.add(at.above().asLong());
        m.reveal.add(new long[]{at.asLong(), Long.MIN_VALUE / 2, 0});
        m.reveal.add(new long[]{at.above().asLong(), Long.MIN_VALUE / 2, 0});
        revealTick(mod, m, mod.getWorld().getGameTime());
        if (dx == 0 && dz == 0) dx = 1;
        int hx = dx, hz = dz;
        int[] way = null;
        for (int pass = 0; pass < 2 && way == null; pass++) {
            int wx = dx, wz = dz;
            for (int turn = 0; turn < 4; turn++) {
                BlockPos step = at.offset(wx, 0, wz);
                if (stepBad(mod, m, at, step) == null && (pass == 1 || !mod.getWorld().getBlockState(step).isAir())) {
                    way = new int[]{wx, wz};
                    break;
                }
                int t = wx;
                wx = -wz;
                wz = t;
            }
        }
        if (way != null) {
            hx = way[0];
            hz = way[1];
        }
        double reach = mod.getClientBaritone().getPlayerContext().playerController().getBlockReachDistance();
        m.run = new PokePattern.Run(at.getX(), _y, at.getZ(), hx, hz, PokePattern.depthFor(reach));
        Debug.logInternal("branch mine: poke holes from " + at.toShortString() + " heading " + hx + ", " + hz + " (" + why
                + "): a station every " + PokePattern.SPACING + " steps, holes " + m.run.depth() + " deep each side at head height, legs of "
                + PokePattern.LEG + " steps " + PokePattern.GAP + " apart (gold_poke_shafts)");
    }

    // why this hole cell is not dug, null when it may be. open rock beside it (a cave) counts only when the buddy's own
    // digging did not open it; the cell toward the tunnel is the hole's own
    private String holeBad(AltoClef mod, Memory m, BlockPos cell, PokePattern.Move mv, BlockPos at) {
        var level = mod.getWorld();
        BlockState st = level.getBlockState(cell);
        if (!st.getFluidState().isEmpty()) return "water or lava in it";
        if (st.isAir()) return "open, a cave";
        BlockPos prev = mv.depth() == 1 ? at.above() : cell.offset(-Integer.signum(cell.getX() - at.getX()), 0, -Integer.signum(cell.getZ() - at.getZ()));
        for (Direction d : Direction.values()) {
            BlockPos n = cell.relative(d);
            if (n.equals(prev)) continue;
            BlockState ns = level.getBlockState(n);
            if (!ns.getFluidState().isEmpty()) return "water or lava beside it";
            if (ns.isAir() && !ours(m, n)) return "open rock beside it, a cave";
        }
        if (level.getBlockState(cell.above()).getBlock() instanceof FallingBlock) return "sand or gravel over it";
        if (st.getDestroySpeed(level, cell) < 0 || st.hasBlockEntity() || BreakRules.tooSlow(WorldHelper.bagOf(mod), st)) {
            return "will not break (" + name(st) + ")";
        }
        return null;
    }

    // why the tunnel's step from at to step is not taken, null when it is: its two cells, the floor under it, the four
    // sides and the cell over the head. open only by the buddy's own digging, no water or lava, a solid floor
    private String stepBad(AltoClef mod, Memory m, BlockPos at, BlockPos step) {
        var level = mod.getWorld();
        BlockPos head = step.above();
        for (BlockPos c : new BlockPos[]{step, head}) {
            BlockState st = level.getBlockState(c);
            if (!st.getFluidState().isEmpty()) return "water or lava ahead";
            if (st.isAir() && !ours(m, c)) return "open ahead, a cave";
        }
        BlockState floor = level.getBlockState(step.below());
        if (!floor.getFluidState().isEmpty()) return "water or lava under the step";
        if (!floor.isSolid()) return floor.isAir() && !ours(m, step.below()) ? "open under the step, a cave" : "no floor";
        for (BlockPos c : new BlockPos[]{step, head}) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = c.relative(d);
                if (n.equals(at) || n.equals(at.above())) continue;
                BlockState ns = level.getBlockState(n);
                if (!ns.getFluidState().isEmpty()) return "water or lava beside the step";
                if (ns.isAir() && !ours(m, n)) return "open rock beside the step, a cave";
            }
        }
        BlockState over = level.getBlockState(head.above());
        if (!over.getFluidState().isEmpty()) return "water or lava over the step";
        if (over.isAir() && !ours(m, head.above())) return "open over the step, a cave";
        return null;
    }

    // open by the buddy's own digging: a cell the pattern dug, or one it showed solid that is open now (an ore taken)
    private static boolean ours(Memory m, BlockPos pos) {
        long k = pos.asLong();
        return m.dug.contains(k) || m.shown.contains(k);
    }

    // a cell of the move under way: its block while it stands (the real one once its neighbour is open), and what it counts as
    private static void watch(Memory m, BlockPos cell, BlockState st, int what, long now) {
        if (st.isAir() || !st.getFluidState().isEmpty()) return;
        Watched w = m.watch.get(cell.asLong());
        if (w == null) m.watch.put(cell.asLong(), new Watched(name(st), what, now));
    }

    // the watched cells that went this tick: dug, by which pick, their neighbours to look at REVEAL ticks on
    private void watchTick(AltoClef mod, Memory m, long now) {
        if (m.watch.isEmpty()) return;
        var level = mod.getWorld();
        String pick = ToolPick.nameOf(mod.getPlayer().getMainHandItem());
        Iterator<Map.Entry<Long, Watched>> it = m.watch.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Watched> e = it.next();
            Watched w = e.getValue();
            BlockPos pos = BlockPos.of(e.getKey());
            BlockState st = level.getBlockState(pos);
            if (!st.isAir()) {
                if (!st.getFluidState().isEmpty() || now < w.since || now - w.since > 1200) it.remove();
                else w.name = name(st);
                continue;
            }
            it.remove();
            m.dug.add(e.getKey());
            m.reveal.add(new long[]{e.getKey(), now, 1});
            m.lastDig = now;
            if (w.what == HOLE) m.holes++;
            else if (w.what == CONNECTOR) m.connector++;
            else m.tunnel++;
            m.picks.merge(w.name + " " + pick, 1, Integer::sum);
        }
    }

    // the neighbours of the cells dug REVEAL ticks ago and more: each shown solid once, an ore among them seen
    private void revealTick(AltoClef mod, Memory m, long now) {
        var level = mod.getWorld();
        while (!m.reveal.isEmpty()) {
            long[] r = m.reveal.peekFirst();
            if (now >= r[1] && now - r[1] < REVEAL) break;
            m.reveal.removeFirst();
            BlockPos pos = BlockPos.of(r[0]);
            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                long k = n.asLong();
                if (m.dug.contains(k) || m.shown.contains(k)) continue;
                BlockState st = level.getBlockState(n);
                if (st.isAir() || !st.getFluidState().isEmpty()) continue;
                m.shown.add(k);
                if (r[2] == 0) continue;
                m.faces++;
                if (name(st).endsWith("_ore")) {
                    m.ores.add(k);
                    m.oresSeen++;
                    if (Arrays.asList(_blocks).contains(st.getBlock())) m.wanted++;
                }
            }
        }
    }

    // one line a segment (a station's holes and the steps to the next, a connector, or up to a turn): what was dug and
    // shown, so a read can count the ores seen a block dug, and which pick broke which rock
    private static void logSegment(Memory m, String why) {
        if (m.run == null || m.tunnel + m.holes + m.connector + m.skipped == 0) return;
        m.segments++;
        StringBuilder picks = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.picks.entrySet()) {
            if (picks.length() > 0) picks.append(", ");
            picks.append(e.getKey()).append(' ').append(e.getValue());
        }
        int dug = m.tunnel + m.holes + m.connector;
        Debug.logInternal("branch mine: poke segment " + m.segments + " (leg " + m.run.legs() + ", to " + m.run.x() + ", " + m.run.y()
                + ", " + m.run.z() + ", " + why + "): " + dug + " dug (" + m.tunnel + " tunnel, " + m.holes + " holes, " + m.connector
                + " connector), " + m.skipped + " hole cells not dug, " + m.caves + " caves met, " + m.faces + " cells shown, "
                + m.oresSeen + " ores seen, " + m.wanted + " wanted; picks " + (picks.length() == 0 ? "none" : picks) + " (gold_poke_shafts)");
        m.tunnel = m.holes = m.connector = m.skipped = m.caves = m.faces = m.oresSeen = m.wanted = 0;
        m.picks.clear();
    }

    private static void logTurn(Memory m, String why) {
        PokePattern.Run r = m.run;
        Debug.logInternal("branch mine: poke holes turn at " + r.x() + ", " + r.y() + ", " + r.z() + " (" + why + "): "
                + (r.connecting() ? "a connector heading " + r.dx() + ", " + r.dz() + " to the next leg " + PokePattern.GAP + " off"
                : "leg " + r.legs() + " heading " + r.dx() + ", " + r.dz()) + " (gold_poke_shafts)");
    }

    private static String name(BlockState st) {
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
    }

    private static double horizontal(BlockPos a, int x, int z) {
        return Math.hypot(a.getX() - x, a.getZ() - z);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PokeMineTask p && p._y == _y && Arrays.equals(p._blocks, _blocks);
    }

    @Override
    protected String toDebugString() {
        return "Poke holes at y " + _y;
    }
}
