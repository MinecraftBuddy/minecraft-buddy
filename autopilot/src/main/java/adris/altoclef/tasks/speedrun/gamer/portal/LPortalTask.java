package adris.altoclef.tasks.speedrun.gamer.portal;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceStructureBlockTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.speedrun.gamer.SmeltSurface;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Inv;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Kind;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Layout;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Mat;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.P;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Scoop;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Step;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.trackers.BlockTracker;
import adris.altoclef.util.helpers.FluidSources;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.IPlayerContext;
import baritone.cache.FasterWorldScanner;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

// the lava bucket portal (see LPortalPlan for the picture). this class is the hands: it asks the plan what comes next
// from nothing but what the world looks like, does that one step, and asks again. a fight or a snack in the middle
// just means the next tick reads the world and carries on.
//
// it only ever makes the portal faster. anything that goes wrong (no site, a cell that won't cast, the clock) ends in
// failed(), and the phase falls back to the old per block cast
public class LPortalTask extends Task {
    // the search looks at every lake in the loaded chunks, walks to one that passed and builds there. BuildClock.poolOut says when
    // that has gone on long enough
    private static final int SCAN_GAP = 20 * 3;
    // layouts surveyed a tick, each full check is a couple hundred reads
    private static final int LAYOUTS_PER_TICK = 6;
    private static final int CLEANUP_TICKS = 20 * 8;
    private static final int SCOOP_MISSES = 12;
    // one step that does not change the world for this long is stuck
    private static final int STEP_TICKS = 20 * 14;
    // the water has this long to show up next to a cell. the fall from the source and the spill into the pit take seconds
    private static final int WAIT_TICKS = 20 * 12;
    // strikes are per cell, one bad cell shouldn't use up the budget of the others
    private static final int CELL_STRIKES = 3;
    private static final int REACH_TICKS = 20;
    private static final int BLOCKED_READS = 5;
    private static final int CLICK_GAP = 8;

    private int tick;
    private int droppedSites;
    // the build clock and the clearing clock (BuildClock), and whether the last step was clearing
    private BuildClock clock = new BuildClock();
    private boolean prepping;
    // the base is set, the lava cells under it are obsidian and nobody has to keep blocks out of them anymore
    private boolean baseSet;
    private Layout layout;
    // the lake being looked at, a few layouts a tick
    private LakeSurvey lakeSurvey;
    private String failReason;
    // why we are giving up, while the loose water and lava come back in the buckets first. failReason is set once that is done
    private String failing;
    private int failingSince;
    // lava sources we could not scoop from where we stood, and how many times that happened
    private final Set<P> badSources = new HashSet<>();
    private int scoopMisses;
    private boolean finished;
    private final StepClock stepClock = new StepClock();
    private final Map<String, Integer> strikes = new HashMap<>();
    private TimeoutWanderTask unstick;
    private int lastClickTick = -100;
    // the click we hand out from a stand, kept as the same object so its click status is the one it really had last tick
    private InteractWithBlockTask reachChild;
    private String reachKey = "";
    private int reachLastTick = -1;
    private String standKey = "";
    private int standSince;
    private boolean reachClicked;
    private int blockedReads;
    private String lastDebug = "";
    private List<P> lavaScan;
    private int lavaScanTick;
    // the lava hunt. the scans run on the baritone executor and hand their seeds over through the volatiles, never on the tick
    private final LavaHunt hunt;
    private volatile List<P> bandSeeds = List.of();
    private volatile boolean scanning;
    // a scan has finished, so an empty seed list means no lava rather than not looked yet
    private volatile boolean scanned;
    private int lastKick = -100000;
    private String huntHud = "";
    // what the log was already told. a half read lake comes back every 10 s with the same news
    private final Set<String> saidLakes = new HashSet<>();
    private P saidTarget;

    // rejected = the lakes earlier tries gave up on (the phase keeps the list, so a retry doesn't walk to them again)
    public LPortalTask(List<P> rejected) {
        hunt = new LavaHunt(rejected);
    }

    // where the cast should start when the pool gave up: the nearest lake in the scan it can flood from, or null (no scan, no lava).
    // the cast only sees the tracker's 25 nearest sources, often all deep ones, so it is walked to the lake instead of wandering
    public BlockPos castLake(AltoClef mod) {
        BlockPos me = mod.getPlayer().blockPosition();
        Set<BlockPos> explored = new HashSet<>();
        P lake = LavaHunt.castLake(bandSeeds, me.getX(), me.getY(), me.getZ(), ConstructNetherPortalBucketTask.LAKE_MIN_SOURCES,
                seed -> ConstructNetherPortalBucketTask.lakeSize(toPos(seed), ConstructNetherPortalBucketTask::isFullLavaSource, explored));
        return lake == null ? null : toPos(lake);
    }

    public String failReason() {
        return failReason;
    }

    public boolean failed() {
        return failReason != null;
    }

    public boolean searching() {
        return layout == null;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.LAVA);
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.BUCKET, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);
        // the walls and the cast frame are the whole point, nothing gets to path through them
        // while clearing the spot nothing in front of the shore row gets dug, that is where the lava is
        mod.getBehaviour().avoidBlockBreaking(p -> layout != null && (prepping ? isFront(p) : (isWall(p) || isCastMade(mod, p))));
        // the cells the water and the frame need empty: no scaffolding in them
        mod.getBehaviour().avoidBlockPlacing(p -> layout != null && (isReserved(p) || (!baseSet && isLavaCell(p))));
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.LAVA);
        mod.getBehaviour().pop();
    }

    private boolean isWall(BlockPos p) {
        if (layout.cell(LPortalPlan.ARM).equals(toP(p))) {
            return true;
        }
        for (int[] c : LPortalPlan.WALLS) {
            if (layout.cell(c).equals(toP(p))) {
                return true;
            }
        }
        return false;
    }

    private boolean isCastMade(AltoClef mod, BlockPos p) {
        boolean obsidian = mod.getWorld().getBlockState(p).getBlock() == Blocks.OBSIDIAN;
        if (obsidian && (layout.cell(LPortalPlan.CORNER_LEFT).equals(toP(p)) || layout.cell(LPortalPlan.CORNER_RIGHT).equals(toP(p))
                || layout.cell(LPortalPlan.PLATFORM_LEFT).equals(toP(p)) || layout.cell(LPortalPlan.PLATFORM_RIGHT).equals(toP(p)))) {
            return true;
        }
        for (LPortalPlan.Round round : LPortalPlan.ROUNDS) {
            for (LPortalPlan.Cast c : round.casts()) {
                if (obsidian && layout.cell(c.t()).equals(toP(p))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isReserved(BlockPos p) {
        P q = toP(p);
        for (int[] c : LPortalPlan.keepClear()) {
            if (layout.cell(c).equals(q)) {
                return true;
            }
        }
        return layout.cell(LPortalPlan.BASE_WATER).equals(q);
    }

    private boolean isFront(BlockPos p) {
        P q = toP(p);
        for (int[] c : LPortalPlan.keepClear()) {
            if (c[2] <= 0 && layout.cell(c).equals(q)) {
                return true;
            }
        }
        // and the shore the site stands on: the top, what is under it, the backing
        for (int u = 0; u <= 3; u++) {
            if (layout.cell(u, 0, 1).equals(q) || layout.cell(u, -1, 1).equals(q) || layout.cell(u, 0, 2).equals(q)) {
                return true;
            }
        }
        return false;
    }

    // the row and the platform lane in front of it: a bridging block dropped into one of those before the base would cook
    private boolean isLavaCell(BlockPos p) {
        P q = toP(p);
        for (int u = 0; u <= 3; u++) {
            if (layout.cell(u, 0, 0).equals(q) || (u >= 1 && u <= 2 && layout.cell(u, 0, -1).equals(q))) {
                return true;
            }
        }
        return false;
    }

    private static Direction faceOf(int dx, int dy, int dz) {
        for (Direction d : Direction.values()) {
            if (d.getUnitVec3i().getX() == dx && d.getUnitVec3i().getY() == dy && d.getUnitVec3i().getZ() == dz) {
                return d;
            }
        }
        throw new IllegalArgumentException("not a face: " + dx + " " + dy + " " + dz);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static P toP(BlockPos p) {
        return new P(p.getX(), p.getY(), p.getZ());
    }

    private static BlockPos toPos(P p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    @Override
    protected Task onTick(AltoClef mod) {
        tick++;
        if (failReason != null || finished) {
            return null;
        }
        if (failing != null) {
            return cleanUp(mod);
        }
        String out = BuildClock.poolOut(tick, droppedSites, layout != null && !prepping);
        if (out != null) {
            return giveUp(out + " (" + hunt.tally() + ")");
        }
        if (unstick != null && unstick.isActive() && !unstick.isFinished(mod)) {
            setDebugState("shaking loose");
            return unstick;
        }
        if (layout == null) {
            return search(mod);
        }
        BuildClock.Verdict verdict = clock.tick(prepping);
        if (verdict == BuildClock.Verdict.BUILD_OUT) {
            return giveUp("ran out of time");
        }
        if (verdict == BuildClock.Verdict.PREP_OUT) {
            return dropSite();
        }
        LiveTerrain terrain = new LiveTerrain(mod.getWorld());
        List<P> lava = lavaCells(mod);
        var eyes = mod.getPlayer().getEyePosition(1.0F);
        Scoop spot = LPortalPlan.scoopSpot(terrain, lava, layout, eyes.x, eyes.y, eyes.z, badSources);
        int empty = mod.getItemStorage().getItemCount(Items.BUCKET);
        Inv inv = new Inv(mod.getItemStorage().getItemCount(Items.LAVA_BUCKET), mod.getItemStorage().hasItem(Items.WATER_BUCKET), empty,
                blocksInBag(mod), spot != null);
        baseSet = LPortalPlan.baseDone(layout, terrain);
        Step step = LPortalPlan.next(layout, terrain, inv);
        prepping = LPortalPlan.PREP_CLEAR.equals(step.why()) || LPortalPlan.PREP_FILL.equals(step.why());
        lastDebug = step.toString();
        watchdog(mod, step, spot);
        if (failing != null || layout == null) {
            return null;
        }
        setDebugState(step.toString());
        return doStep(mod, step, spot);
    }

    // the first throwaway block we are holding
    private static Item stackBlock(AltoClef mod) {
        for (Item i : mod.getClientBaritoneSettings().acceptableThrowawayItems.value) {
            if (mod.getItemStorage().getItemCount(i) > 0) {
                return i;
            }
        }
        return null;
    }

    private static int blocksInBag(AltoClef mod) {
        int n = 0;
        for (Item i : mod.getClientBaritoneSettings().acceptableThrowawayItems.value) {
            n += mod.getItemStorage().getItemCount(i);
        }
        return n;
    }

    // once the build is on its own we read the row layer around it ourselves, the scan's seeds are only a few cells per chunk
    private List<P> lavaCells(AltoClef mod) {
        if (layout != null) {
            if (tick - lavaScanTick >= 8 || lavaScan == null) {
                lavaScanTick = tick;
                lavaScan = new ArrayList<>();
                P mid = layout.cell(1, 0, 0);
                for (int x = mid.x() - LPortalPlan.SCAN; x <= mid.x() + LPortalPlan.SCAN; x++) {
                    for (int z = mid.z() - LPortalPlan.SCAN; z <= mid.z() + LPortalPlan.SCAN; z++) {
                        BlockPos p = new BlockPos(x, mid.y(), z);
                        if (mod.getWorld().isLoaded(p) && mod.getWorld().getBlockState(p).is(Blocks.LAVA)) {
                            lavaScan.add(toP(p));
                        }
                    }
                }
            }
            return lavaScan;
        }
        return List.of();
    }

    // ---- finding the spot ----

    private Task search(AltoClef mod) {
        kickScan(mod);
        BlockPos me = mod.getPlayer().blockPosition();
        // the flag first: the scan sets the seeds before it, so these seeds are at least as new as the scan it announces
        boolean looked = scanned;
        List<P> seeds = bandSeeds;
        LiveTerrain terrain = new LiveTerrain(mod.getWorld());
        int blocks = blocksInBag(mod);
        if (lakeSurvey == null) {
            P next = hunt.nextToSurvey(seeds, me.getX(), me.getY(), me.getZ(), tick);
            if (next != null) {
                lakeSurvey = LakeSurvey.of(terrain, next, me.getX(), me.getZ(), p -> mod.getWorld().isLoaded(toPos(p)), p -> groundOver(mod, p));
            }
        }
        if (lakeSurvey != null && lakeSurvey.step(LAYOUTS_PER_TICK, terrain, l -> boxLoaded(mod, l))) {
            hunt.surveyed(lakeSurvey.lake(), tick);
            logLake(lakeSurvey);
            lakeSurvey = null;
        }
        // a perfect site is taken at once, a rough one when every lake has been looked at
        boolean more = lakeSurvey != null || hunt.nextToSurvey(seeds, me.getX(), me.getY(), me.getZ(), tick) != null;
        Layout site = looked ? hunt.pick(me.getX(), me.getY(), me.getZ(), blocks, more) : null;
        if (site != null) {
            // the verdict may be old, the terrain is read again before anything is built on it
            LPortalPlan.Survey s = LPortalPlan.survey(site, terrain);
            if (s.ok() && blocks >= 8 + s.fills() && boxLoaded(mod, site)) {
                return choose(site);
            }
            hunt.drop(site);
            return null;
        }
        switch (hunt.decide(seeds, looked, lakeSurvey != null, me.getX(), me.getY(), me.getZ(), tick, blocks)) {
            case WALK -> {
                huntHud = "Going to lava";
                logWalk();
                setDebugState("walking to lava " + hunt.target());
                // near the lava cell and never on it, a goal inside lava is a goal baritone refuses
                return new GetWithinRangeOfBlockTask(toPos(hunt.target()).above(), 4);
            }
            case WAIT -> {
                huntHud = "Looking for a lava row";
                setDebugState("looking for a lava row");
                return null;
            }
            default -> {
                // every lake in the loaded chunks was looked at and none is good: the cast finds its own lava
                return giveUp("no lake here fits the build (" + hunt.tally() + ")");
            }
        }
    }

    // the heightmap counts the lava, so an open pool tops out right on its own surface and reads 0. an unloaded chunk has nothing
    // to say, those layouts are skipped before anybody asks anyway
    private static int groundOver(AltoClef mod, P p) {
        if (!mod.getWorld().isLoaded(toPos(p))) {
            return 0;
        }
        int top = mod.getWorld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.x(), p.z());
        return Math.max(0, SmeltSurface.depth(top, p.y() + 1));
    }

    private void logLake(LakeSurvey survey) {
        LakeSurvey.Lake lake = survey.lake();
        P a = lake.anchor();
        String verdict = lake.good() ? "best cost " + lake.cost() + ", fills " + lake.fills() + ", depth " + lake.depth() : lake.unknown() ? "half read" : "no site";
        String line = "lava hunt: lake at " + a.x() + " " + a.y() + " " + a.z() + ", " + survey.sources() + " sources, " + verdict;
        // the tally moves every time a half read lake gets read a bit further, that's no reason to say the lake again
        if (saidLakes.add(line)) {
            Debug.logInternal(line + " | " + survey.tally());
        }
    }

    private void logWalk() {
        P to = hunt.target();
        if (!Objects.equals(to, saidTarget)) {
            saidTarget = to;
            Debug.logInternal("lava hunt: walking to " + to.x() + " " + to.y() + " " + to.z() + (hunt.blindWalk() ? " to read it" : " to build there"));
        }
    }

    private Task choose(Layout l) {
        layout = l;
        lavaScan = null;
        clock = new BuildClock();
        prepping = false;
        baseSet = false;
        strikes.clear();
        return null;
    }

    // the corners of the box the survey reads are in loaded chunks
    private static boolean boxLoaded(AltoClef mod, Layout l) {
        for (int u = -2; u <= 5; u += 7) {
            for (int d = -3; d <= 3; d += 6) {
                if (!mod.getWorld().isLoaded(toPos(l.cell(u, 0, d)))) {
                    return false;
                }
            }
        }
        return true;
    }

    // the site we were clearing is no good after all (lava showed up, the clock ran out, a block won't break). back to
    // looking, and not at this lake again
    private Task dropSite() {
        Debug.logInternal("lava hunt: dropped the site at " + layout.cell(1, 0, 0) + ", was at " + lastDebug);
        hunt.siteFailed(layout.cell(1, 0, 0));
        layout = null;
        droppedSites++;
        lakeSurvey = null;
        prepping = false;
        baseSet = false;
        strikes.clear();
        lastDebug = "";
        return null;
    }

    // the lava seeds come from a scan of everything loaded, off the tick, within the band around the surface. no deeper pass:
    // cave lakes always fail the floor rule and the dig down to one is a good way to meet the lava
    private void kickScan(AltoClef mod) {
        if (scanning || tick - lastKick < SCAN_GAP) {
            return;
        }
        lastKick = tick;
        scanning = true;
        int py = LavaHunt.bandCentre(mod.getPlayer().blockPosition().getY());
        IPlayerContext ctx = mod.getClientBaritone().getPlayerContext();
        Baritone.getExecutor().execute(() -> {
            try {
                bandSeeds = scanSeeds(ctx, py);
                scanned = true;
            } catch (RuntimeException e) {
                // a chunk going away under the scan. the next one will do
            } finally {
                scanning = false;
            }
        });
    }

    private static List<P> scanSeeds(IPlayerContext ctx, int py) {
        // the cap is counted in the filter, the scanner stops a chunk at max so a count afterwards is too late
        LavaHunt.ChunkCap cap = new LavaHunt.ChunkCap();
        List<BlockPos> hits = FasterWorldScanner.INSTANCE.scanNearest(ctx, new BlockOptionalMetaLookup(Blocks.LAVA), LavaHunt.SEED_MAX,
                BlockTracker.FLUID_SCAN_CHUNK_RADIUS, (chunk, x, y, z) -> {
                    if (Math.abs(y - py) > LavaHunt.BAND) {
                        return false;
                    }
                    // a full chunk is the cheap no, before the fluid reads
                    if (cap.full(x, z)) {
                        return false;
                    }
                    return FluidSources.exposedSource(chunk.getFluidState(x, y, z), chunk.getFluidState(x, y + 1, z))
                            && chunk.getBlockState(new BlockPos(x, y + 1, z)).isAir() && cap.take(x, z);
                });
        List<P> out = new ArrayList<>(hits.size());
        for (BlockPos p : hits) {
            out.add(toP(p));
        }
        return out;
    }

    // ---- one step ----

    private Task doStep(AltoClef mod, Step step, Scoop spot) {
        switch (step.kind()) {
            case DONE -> {
                finished = true;
                return null;
            }
            case WAIT -> {
                return null;
            }
            case STUCK -> {
                return giveUp(step.why());
            }
            case ABANDON -> {
                return dropSite();
            }
            case SCOOP -> {
                return scoopLava(mod, step, spot);
            }
            case PLACE_BLOCK -> {
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                return new PlaceStructureBlockTask(toPos(step.cell()));
            }
            case BREAK -> {
                Task near = step.stand() == null ? null : goStand(mod, step.stand());
                if (near != null) {
                    return near;
                }
                BlockPos junk = toPos(step.cell());
                if (WorldHelper.tooSlowToBreak(mod, junk)) {
                    return prepping ? dropSite() : giveUp("something too hard to break at " + junk.toShortString());
                }
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                return new DestroyBlockTask(junk);
            }
            case LAVA_PLACE, WATER_PLACE -> {
                Task there = goStand(mod, step.stand());
                if (there != null) {
                    return there;
                }
                if (tick - lastClickTick < CLICK_GAP) {
                    return null;
                }
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                Item bucket = step.kind() == Kind.LAVA_PLACE ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
                Direction face = faceOf(step.fx(), step.fy(), step.fz());
                return watchReach(mod, step, () -> new InteractWithBlockTask(new ItemTarget(bucket, 1), face, toPos(step.support()), false).stayPut());
            }
            case PLACE_STACK -> {
                Task there = goStand(mod, step.stand());
                if (there != null) {
                    return there;
                }
                Item block = stackBlock(mod);
                if (block == null) {
                    return giveUp("no block for the placeholder");
                }
                if (tick - lastClickTick < CLICK_GAP) {
                    return null;
                }
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                return watchReach(mod, step, () -> new InteractWithBlockTask(new ItemTarget(block, 1), faceOf(step.fx(), step.fy(), step.fz()), toPos(step.support()), false).stayPut());
            }
            case WATER_SCOOP, LAVA_UNDO -> {
                Task there = goStand(mod, step.stand());
                if (there != null) {
                    return there;
                }
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);
                boolean lava = step.kind() == Kind.LAVA_UNDO;
                return watchReach(mod, step, () -> (lava ? InteractWithBlockTask.scoop(new ItemTarget(Items.BUCKET, 1), toPos(step.cell()), false)
                        : new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), toPos(step.cell()), false)).stayPut());
            }
            case LIGHT -> {
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                return new InteractWithBlockTask(new ItemTarget(new Item[]{Items.FLINT_AND_STEEL, Items.FIRE_CHARGE}, 1),
                        Direction.UP, toPos(step.support()), true);
            }
            default -> {
                return giveUp("unknown step " + step);
            }
        }
    }

    // a bucket on a lava source, from where we stand when it is in reach, else from the stand the plan picked. no spot means
    // the plan shouldn't have asked. the scoop click, so it doesn't sneak and a scoop the server never took gets caught
    private Task scoopLava(AltoClef mod, Step step, Scoop spot) {
        if (spot == null) {
            return null;
        }
        Task there = spot.stand() == null ? null : goStand(mod, spot.stand());
        if (there != null) {
            return there;
        }
        mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);
        Step keyed = new Step(Kind.SCOOP, spot.source(), null, 0, 0, 0, spot.stand(), "lava");
        return watchReach(mod, keyed, () -> InteractWithBlockTask.scoop(new ItemTarget(Items.BUCKET, 1), toPos(spot.source()), false).stayPut());
    }

    // the click from a stand, which never walks (stayPut). if it can't reach or can't aim for REACH_TICKS from the first
    // tick we stood there, that's a strike on this cell and we shake loose and stand again
    private Task watchReach(AltoClef mod, Step step, Supplier<InteractWithBlockTask> make) {
        String key = step.kind() + String.valueOf(step.cell());
        if (!key.equals(standKey)) {
            standKey = key;
            standSince = tick;
            reachClicked = false;
        }
        if (reachChild == null || !key.equals(reachKey) || reachLastTick != tick - 1) {
            reachChild = make.get();
            reachKey = key;
            blockedReads = 0;
        } else {
            InteractWithBlockTask.ClickResponse r = reachChild.getClickStatus();
            if (r == InteractWithBlockTask.ClickResponse.CLICK_ATTEMPTED) {
                reachClicked = true;
                lastClickTick = tick;
            }
            boolean blocked = r == InteractWithBlockTask.ClickResponse.CANT_REACH || reachChild.stillAiming();
            blockedReads = blocked ? blockedReads + 1 : 0;
            if (blockedReads >= BLOCKED_READS && !reachClicked && tick - standSince >= REACH_TICKS) {
                reachLastTick = -1;
                standKey = "";
                if (step.kind() == Kind.SCOOP) {
                    // not that one then, the next tick picks another (or walks to the shore for it). no shaking loose here, we may be
                    // standing on the platform
                    badSources.add(step.cell());
                    return ++scoopMisses > SCOOP_MISSES ? giveUp("no lava we can reach") : null;
                }
                return strike(key, "cant reach");
            }
        }
        reachLastTick = tick;
        return reachChild;
    }

    // per cell: one stubborn cell must not use up the budget of the others
    private Task strike(String key, String why) {
        int n = strikes.merge(key, 1, Integer::sum);
        if (n >= CELL_STRIKES) {
            return prepping ? dropSite() : giveUp(why + " " + key);
        }
        unstick = new TimeoutWanderTask(3);
        return null;
    }

    // null when we are already on the cell, otherwise the walk there
    private Task goStand(AltoClef mod, P stand) {
        if (stand == null) {
            return null;
        }
        BlockPos me = mod.getPlayer().blockPosition();
        if (me.getX() == stand.x() && me.getZ() == stand.z() && Math.abs(me.getY() - stand.y()) <= 1) {
            return null;
        }
        setDebugState("walking to " + stand);
        return new GetToBlockTask(toPos(stand), false);
    }

    // ---- when a step goes nowhere ----

    private void watchdog(AltoClef mod, Step step, Scoop spot) {
        // a lava trip has no cell of its own, the source it is for stands in for it so a new trip is a new step
        String where = step.kind() == Kind.SCOOP && spot != null ? String.valueOf(spot.source()) : String.valueOf(step.cell());
        String key = step.kind() + where + step.why();
        P stand = step.kind() == Kind.SCOOP && spot != null ? spot.stand() : step.stand();
        double toStand = Double.NaN;
        if (stand != null) {
            var pos = mod.getPlayer().position();
            toStand = Math.sqrt(sq(pos.x - stand.x() - 0.5) + sq(pos.y - stand.y()) + sq(pos.z - stand.z() - 0.5));
        }
        int limit = step.kind() == Kind.WAIT ? WAIT_TICKS : STEP_TICKS;
        if (!stepClock.stuck(key, toStand, tick, limit)) {
            return;
        }
        if (step.kind() == Kind.WAIT) {
            giveUp(step.why());
            return;
        }
        if (step.kind() == Kind.SCOOP && spot != null) {
            // a lava click that never filled: not that source then, and no shaking loose on the platform
            badSources.add(spot.source());
            if (++scoopMisses > SCOOP_MISSES) {
                giveUp("no lava we can take");
            }
            return;
        }
        strike(step.kind() + where, "not going anywhere");
    }

    // the water and lava we put out must not be left running at the cast site, so the buckets take them back first (a short
    // while only), and then the phase gets the failure
    private Task giveUp(String why) {
        if (failReason == null && failing == null) {
            failing = why;
            failingSince = tick;
        }
        return null;
    }

    private Task cleanUp(AltoClef mod) {
        if (layout == null || tick - failingSince > CLEANUP_TICKS) {
            failReason = failing;
            return null;
        }
        Step loose = LPortalPlan.cleanup(layout, new LiveTerrain(mod.getWorld()), mod.getItemStorage().getItemCount(Items.BUCKET));
        if (loose == null) {
            failReason = failing;
            return null;
        }
        setDebugState("taking the loose " + loose.why() + " back");
        return doStep(mod, loose, null);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof LPortalTask;
    }

    @Override
    protected String toHudString() {
        if (prepping && !searching()) {
            return "Clearing the portal spot";
        }
        return searching() ? (huntHud.isEmpty() ? "Looking for a lava row" : huntHud) : "Building a lava bucket portal";
    }

    @Override
    protected String toDebugString() {
        return "Lava bucket portal " + (layout == null ? "(searching)" : lastDebug);
    }

    // the live level as a plan terrain. unloaded chunks read as rock so nothing gets planned into them
    private static final class LiveTerrain implements LPortalPlan.Terrain {
        private final ClientLevel level;

        LiveTerrain(ClientLevel level) {
            this.level = level;
        }

        @Override
        public Mat at(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)) {
                return Mat.SOLID;
            }
            BlockState s = level.getBlockState(pos);
            if (s.isAir()) {
                return Mat.AIR;
            }
            if (s.is(Blocks.LAVA)) {
                return s.getFluidState().isSource() ? Mat.LAVA : Mat.LAVA_FLOW;
            }
            if (s.is(Blocks.WATER)) {
                return s.getFluidState().isSource() ? Mat.WATER : Mat.WATER_FLOW;
            }
            if (s.is(Blocks.OBSIDIAN)) {
                return Mat.OBSIDIAN;
            }
            if (s.is(Blocks.NETHER_PORTAL)) {
                return Mat.PORTAL;
            }
            if (!s.getFluidState().isEmpty()) {
                // waterlogged things and kelp: wet, not ours to build in
                return Mat.WATER_FLOW;
            }
            if (s.getCollisionShape(level, pos).isEmpty()) {
                return Mat.AIR;
            }
            // bedrock and friends: solid, and nobody is mining it
            if (s.getDestroySpeed(level, pos) < 0) {
                return Mat.OBSIDIAN;
            }
            if (s.getBlock() instanceof FallingBlock) {
                return Mat.FALLS;
            }
            return Mat.SOLID;
        }
    }
}
