/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.cache.ICachedWorld;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.process.IExploreProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.MyChunkPos;
import baritone.cache.CachedWorld;
import baritone.utils.BaritoneProcessHelper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ExploreProcess extends BaritoneProcessHelper implements IExploreProcess {

    // one scan a second, two empty ones in a row mean no land frontier
    private static final int FRONTIER_SCAN_TICKS = 20;
    private static final int FRONTIER_EMPTY_SCANS = 2;
    // half a million chunks a scan, 8 km out from the origin. past that the ring stops and it reads as nothing found: a
    // crossing, whose first look at dry land scans around the player and re-anchors there
    static final int FRONTIER_MAX_RING = 512;
    // ten seconds on one goal. a member changing is what makes forceRevalidate soft cancel the path, and a fresh scan changed
    // one about once per chunk. the same members in a new array were never the problem, GoalComposite compares by value
    static final int GOAL_HOLD_TICKS = 200;

    private BlockPos explorationOrigin;

    private IChunkFilter filter;

    private int distanceCompleted;

    // only the wander sets one. no gate is plain #explore, every line of the old path as it always was
    private LandGate gate;
    private boolean gateOn;
    private boolean crossing;
    private List<ChunkPos> scannedFrontier = List.of();
    private Goal[] frontierGoals;
    private int frontierAge;
    private int emptyScans;
    private final GoalHold goalHold = new GoalHold();

    public ExploreProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        return explorationOrigin != null;
    }

    @Override
    public void explore(int centerX, int centerZ) {
        explorationOrigin = new BlockPos(centerX, 0, centerZ);
        distanceCompleted = 0;
        clearGate();
    }

    // the same explore, but it only heads for chunks next to land the gate has seen, and says so when there are none left
    public void explore(int centerX, int centerZ, LandGate gate) {
        explore(centerX, centerZ);
        this.gate = gate;
        gateOn = gate.active();
    }

    private void clearGate() {
        gate = null;
        crossing = false;
        frontierGoals = null;
        frontierAge = 0;
        emptyScans = 0;
        goalHold.clear();
    }

    // nothing left on this side of the water. a plain explore around a point way out there, so the rings pull us across
    public void crossFrom(int x, int z) {
        if (gate != null) {
            explorationOrigin = gate.startCrossing(x, z);
            distanceCompleted = 0;
            crossing = true;
            goalHold.clear();
        }
    }

    public boolean isCrossing() {
        return crossing;
    }

    public BlockPos crossingTarget() {
        return explorationOrigin;
    }

    // what the goal we are on was made from, not what the last scan said
    public List<ChunkPos> lastFrontier() {
        return goalHold.frontier();
    }

    public boolean landFrontierExists(int x, int z) {
        return gate != null && !scanFrontier(x >> 4, z >> 4, 1, gate, new BaritoneChunkCache()).found().isEmpty();
    }

    @Override
    public void applyJsonFilter(Path path, boolean invert) throws Exception {
        filter = new JsonChunkFilter(path, invert);
    }

    public IChunkFilter calcFilter() {
        IChunkFilter filter;
        if (this.filter != null) {
            filter = new EitherChunk(this.filter, new BaritoneChunkCache());
        } else {
            filter = new BaritoneChunkCache();
        }
        return filter;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (calcFailed) {
            logDirect("Failed");
            if (Baritone.settings().notificationOnExploreFinished.value) {
                logNotification("Exploration failed", true);
            }
            onLostControl();
            return null;
        }
        IChunkFilter filter = calcFilter();
        if (!Baritone.settings().disableCompletionCheck.value && filter.countRemain() == 0) {
            logDirect("Explored all chunks");
            if (Baritone.settings().notificationOnExploreFinished.value) {
                logNotification("Explored all chunks", false);
            }
            onLostControl();
            return null;
        }
        if (gate != null) {
            boolean on = gate.active();
            if (on != gateOn) {
                // a wander can walk through a portal, and then these are the overworld's numbers in the nether. a crossing
                // target would be a very long walk for nothing. let go, the wander asks again from here
                onLostControl();
                return null;
            }
            if (on && !crossing) {
                PathingCommand land = landTick();
                if (land != null) {
                    return land;
                }
            }
        }
        // the rings walk out from the origin every single time, no point while we still like the goal we have
        PathingCommand held = tickHold();
        if (held != null) {
            return held;
        }
        Goal[] closestUncached = closestUncachedChunks(explorationOrigin, filter);
        if (closestUncached == null) {
            logDebug("awaiting region load from disk");
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return goalHold.hold(closestUncached);
    }

    // null means a crossing just started and the old path below takes it from here. the scan walks the rings from the
    // middle every time, so once a second it is. a goal doesn't need to change faster than chunks load
    private PathingCommand landTick() {
        if (--frontierAge < 0) {
            int chunkX = explorationOrigin.getX() >> 4;
            int chunkZ = explorationOrigin.getZ() >> 4;
            // the real cache and not calcFilter: an inverted json filter calls everything off its list explored, and the scan
            // would take all of that for old land and follow it out forever. a wander isn't working through someone's list
            FrontierScan scan = scanFrontier(chunkX, chunkZ, Baritone.settings().exploreChunkSetMinimumSize.value, gate, new BaritoneChunkCache());
            frontierAge = FRONTIER_SCAN_TICKS;
            frontierGoals = null;
            switch (ScanOutcome.of(scan, emptyScans)) {
                case GO -> {
                    emptyScans = 0;
                    scannedFrontier = scan.found();
                    frontierGoals = scan.found().stream().map(chunk -> frontierGoal(chunk, chunkX, chunkZ)).toArray(Goal[]::new);
                }
                case AWAIT -> frontierAge = 0;
                case EMPTY -> emptyScans++;
                case CROSS -> {
                    // never a pause from here, that would be standing on the beach until the heat death of the server
                    BlockPos me = ctx.playerFeet();
                    crossFrom(me.getX(), me.getZ());
                    return null;
                }
            }
        }
        if (frontierGoals == null) {
            // the scan says nothing's left, a held goal would walk us at land it just crossed off. and the pause is what
            // lets emptyScans get to a crossing
            goalHold.clear();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        // the scan keeps its once a second, emptyScans needs it. the goal just doesn't have to follow every time
        PathingCommand held = tickHold();
        return held != null ? held : goalHold.hold(frontierGoals, scannedFrontier);
    }

    // not playerFeet, that's two objects and a block read a tick. these goals only look at x and z and those come out the same
    private PathingCommand tickHold() {
        return goalHold.tick(ctx.player().getBlockX(), ctx.player().getBlockY(), ctx.player().getBlockZ());
    }

    private static Goal frontierGoal(ChunkPos chunk, int originChunkX, int originChunkZ) {
        int offset = Baritone.settings().worldExploringChunkOffset.value << 4;
        int centerX = (chunk.x() << 4) + 8 + (chunk.x() < originChunkX ? -offset : offset);
        int centerZ = (chunk.z() << 4) + 8 + (chunk.z() < originChunkZ ? -offset : offset);
        return createGoal(centerX, centerZ);
    }

    // the rings, but only out to the land we know plus one. waiting for 10 chunks out here is what froze the client: with
    // nothing to find the old loop just keeps going. fewer than 10 is fine, none is an answer. a region still loading doesn't
    // hold up the chunks we do have
    static FrontierScan scanFrontier(int chunkX, int chunkZ, int want, LandGate gate, IChunkFilter filter) {
        List<ChunkPos> found = new ArrayList<>();
        boolean awaiting = false;
        // reach is read every ring, explored() can move it
        for (int dist = 0; dist <= Math.min(gate.reach(chunkX, chunkZ) + 1, FRONTIER_MAX_RING); dist++) {
            for (int dx = -dist; dx <= dist; dx++) {
                int zval = dist - Math.abs(dx);
                // no second go at zval 0, -0 is the same chunk
                for (int mult = zval == 0 ? 1 : 0; mult < 2; mult++) {
                    int x = chunkX + dx;
                    int z = chunkZ + (mult * 2 - 1) * zval;
                    if (!gate.frontier(x, z)) {
                        continue;
                    }
                    switch (filter.isAlreadyExplored(x, z)) {
                        case UNKNOWN -> awaiting = true;
                        case NOT_EXPLORED -> found.add(new ChunkPos(x, z));
                        case EXPLORED -> gate.explored(x, z);
                    }
                }
            }
            if (!found.isEmpty() && found.size() >= want) {
                break;
            }
        }
        return new FrontierScan(found, awaiting);
    }

    private Goal[] closestUncachedChunks(BlockPos center, IChunkFilter filter) {
        int chunkX = center.getX() >> 4;
        int chunkZ = center.getZ() >> 4;
        int count = Math.min(filter.countRemain(), Baritone.settings().exploreChunkSetMinimumSize.value);
        List<BlockPos> centers = new ArrayList<>();
        int renderDistance = Baritone.settings().worldExploringChunkOffset.value;
        for (int dist = distanceCompleted; ; dist++) {
            for (int dx = -dist; dx <= dist; dx++) {
                int zval = dist - Math.abs(dx);
                for (int mult = 0; mult < 2; mult++) {
                    int dz = (mult * 2 - 1) * zval; // dz can be either -zval or zval
                    int trueDist = Math.abs(dx) + Math.abs(dz);
                    if (trueDist != dist) {
                        throw new IllegalStateException(String.format(
                                "Offset %s %s has distance %s, expected %s",
                                dx, dz, trueDist, dist));
                    }
                    switch (filter.isAlreadyExplored(chunkX + dx, chunkZ + dz)) {
                        case UNKNOWN:
                            return null; // awaiting load
                        case NOT_EXPLORED:
                            break; // note: this breaks the switch not the for
                        case EXPLORED:
                            continue; // note: this continues the for
                        default:
                    }
                    int centerX = ((chunkX + dx) << 4) + 8;
                    int centerZ = ((chunkZ + dz) << 4) + 8;
                    int offset = renderDistance << 4;
                    if (dx < 0) {
                        centerX -= offset;
                    } else {
                        centerX += offset;
                    }
                    if (dz < 0) {
                        centerZ -= offset;
                    } else {
                        centerZ += offset;
                    }
                    centers.add(new BlockPos(centerX, 0, centerZ));
                }
            }
            if (dist % 10 == 0) {
                count = Math.min(filter.countRemain(), Baritone.settings().exploreChunkSetMinimumSize.value);
            }
            if (centers.size() >= count) {
                return centers.stream().map(pos -> createGoal(pos.getX(), pos.getZ())).toArray(Goal[]::new);
            }
            if (centers.isEmpty()) {
                // we have explored everything from 0 to dist inclusive
                // next time we should start our check at dist+1
                distanceCompleted = dist + 1;
            }
        }
    }

    private static Goal createGoal(int x, int z) {
        if (Baritone.settings().exploreMaintainY.value == -1) {
            return new GoalXZ(x, z);
        }
        // don't use a goalblock because we still want isInGoal to return true if X and Z are correct
        // we just want to try and maintain Y on the way there, not necessarily end at that specific Y
        return new GoalXZ(x, z) {
            @Override
            public double heuristic(int x, int y, int z) {
                return super.heuristic(x, y, z) + GoalYLevel.calculate(Baritone.settings().exploreMaintainY.value, y);
            }
        };
    }

    // one command kept and handed out again, so a tick of walking at the same goal costs nothing but the isInGoal
    static final class GoalHold {

        private PathingCommand command;
        private int age;
        // survives a clear on purpose. every interrupt cancels every process, and a turn back still has to know what it swam at
        private List<ChunkPos> frontier = List.of();

        // once per tick. null means go pick a new goal: it's old, or our feet are in it and it has nothing left to give
        PathingCommand tick(int x, int y, int z) {
            if (command != null && (++age >= GOAL_HOLD_TICKS || command.goal.isInGoal(x, y, z))) {
                command = null;
            }
            return command;
        }

        PathingCommand hold(Goal[] goals) {
            command = new PathingCommand(new GoalComposite(goals), PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
            age = 0;
            return command;
        }

        PathingCommand hold(Goal[] goals, List<ChunkPos> madeFrom) {
            frontier = madeFrom;
            return hold(goals);
        }

        List<ChunkPos> frontier() {
            return frontier;
        }

        Goal goal() {
            return command == null ? null : command.goal;
        }

        void clear() {
            command = null;
        }
    }

    enum Status {
        EXPLORED, NOT_EXPLORED, UNKNOWN;
    }

    // what the wander knows about land, in chunk coordinates. it lives on the alto side so this file doesn't have to know what
    // a wander is
    public interface LandGate {

        // false off the overworld: plain rings, nothing to gate
        boolean active();

        // an unseen chunk next to land, not banned
        boolean frontier(int chunkX, int chunkZ);

        // the chunk passed frontier() and turned out to be explored already, so it is from before this session
        void explored(int chunkX, int chunkZ);

        // the farthest ring from the origin that has known land on it, -1 for no land at all
        int reach(int originChunkX, int originChunkZ);

        // write the crossing down and say where it goes. this process forgets it on every cancel, the gate doesn't
        BlockPos startCrossing(int x, int z);
    }

    record FrontierScan(List<ChunkPos> found, boolean awaiting) {
    }

    enum ScanOutcome {
        GO, AWAIT, EMPTY, CROSS;

        // two empties in a row, the chunks of a world we just joined are still arriving on the first one
        static ScanOutcome of(FrontierScan scan, int emptyScansBefore) {
            if (!scan.found().isEmpty()) {
                return GO;
            }
            if (scan.awaiting()) {
                return AWAIT;
            }
            return emptyScansBefore + 1 >= FRONTIER_EMPTY_SCANS ? CROSS : EMPTY;
        }
    }

    // loaded check first: a loaded chunk the cache never got is still explored
    static Status decide(boolean loadedNonEmpty, boolean cached, boolean regionLoaded) {
        if (loadedNonEmpty || cached) {
            return Status.EXPLORED;
        }
        if (!regionLoaded) {
            return Status.UNKNOWN; // we still need to load regions from disk in order to decide properly
        }
        return Status.NOT_EXPLORED;
    }

    interface IChunkFilter {

        Status isAlreadyExplored(int chunkX, int chunkZ);

        int countRemain();
    }

    private class BaritoneChunkCache implements IChunkFilter {

        private final ICachedWorld cache = baritone.getWorldProvider().getCurrentWorld().getCachedWorld();

        @Override
        public Status isAlreadyExplored(int chunkX, int chunkZ) {
            int centerX = chunkX << 4;
            int centerZ = chunkZ << 4;
            // the packer queue drops chunks at high render distance, so ask the client world first
            LevelChunk loaded = ctx.world().getChunkSource().getChunk(chunkX, chunkZ, false);
            boolean loadedNonEmpty = loaded != null && !loaded.isEmpty();
            boolean cached = cache.isCached(centerX, centerZ);
            boolean regionLoaded = ((CachedWorld) cache).regionLoaded(centerX, centerZ);
            Status status = decide(loadedNonEmpty, cached, regionLoaded);
            if (status == Status.UNKNOWN) {
                ((CachedWorld) cache).tryLoadFromDiskAsync(centerX >> 9, centerZ >> 9);
            }
            return status;
        }

        @Override
        public int countRemain() {
            return Integer.MAX_VALUE;
        }
    }

    private class JsonChunkFilter implements IChunkFilter {

        private final boolean invert; // if true, the list is interpreted as a list of chunks that are NOT explored, if false, the list is interpreted as a list of chunks that ARE explored
        private final LongOpenHashSet inFilter;
        private final MyChunkPos[] positions;

        private JsonChunkFilter(Path path, boolean invert) throws Exception { // ioexception, json exception, etc
            this.invert = invert;
            Gson gson = new GsonBuilder().create();
            positions = gson.fromJson(new InputStreamReader(Files.newInputStream(path)), MyChunkPos[].class);
            logDirect("Loaded " + positions.length + " positions");
            inFilter = new LongOpenHashSet();
            for (MyChunkPos mcp : positions) {
                inFilter.add(ChunkPos.pack(mcp.x, mcp.z));
            }
        }

        @Override
        public Status isAlreadyExplored(int chunkX, int chunkZ) {
            if (inFilter.contains(ChunkPos.pack(chunkX, chunkZ)) ^ invert) {
                // either it's on the list of explored chunks, or it's not on the list of unexplored chunks
                // either way, we have it
                return Status.EXPLORED;
            } else {
                // either it's not on the list of explored chunks, or it's on the list of unexplored chunks
                // either way, it depends on if baritone has cached it so defer to that
                return Status.UNKNOWN;
            }
        }

        @Override
        public int countRemain() {
            if (!invert) {
                // if invert is false, anything not on the list is uncached
                return Integer.MAX_VALUE;
            }
            // but if invert is true, anything not on the list IS assumed cached
            // so we are done if everything on our list is cached!
            int countRemain = 0;
            BaritoneChunkCache bcc = new BaritoneChunkCache();
            for (MyChunkPos pos : positions) {
                if (bcc.isAlreadyExplored(pos.x, pos.z) != Status.EXPLORED) {
                    // either waiting for it or dont have it at all
                    countRemain++;
                    if (countRemain >= Baritone.settings().exploreChunkSetMinimumSize.value) {
                        return countRemain;
                    }
                }
            }
            return countRemain;
        }
    }

    private class EitherChunk implements IChunkFilter {

        private final IChunkFilter a;
        private final IChunkFilter b;

        private EitherChunk(IChunkFilter a, IChunkFilter b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public Status isAlreadyExplored(int chunkX, int chunkZ) {
            if (a.isAlreadyExplored(chunkX, chunkZ) == Status.EXPLORED) {
                return Status.EXPLORED;
            }
            return b.isAlreadyExplored(chunkX, chunkZ);
        }

        @Override
        public int countRemain() {
            return Math.min(a.countRemain(), b.countRemain());
        }
    }

    @Override
    public void onLostControl() {
        explorationOrigin = null;
        clearGate();
    }

    @Override
    public String displayName0() {
        if (gate != null && gateOn && !crossing) {
            return "Exploring land around " + explorationOrigin + ", " + lastFrontier().size() + " frontier chunks";
        }
        // the held one is where we really are going, and a status line shouldn't cost a ring scan
        Goal going = goalHold.goal();
        if (going == null) {
            // null is regions still loading, a GoalComposite of null is a crash in a debug string
            Goal[] goals = closestUncachedChunks(explorationOrigin, calcFilter());
            going = goals == null ? null : new GoalComposite(goals);
        }
        return (crossing ? "Crossing to " : "Exploring around ") + explorationOrigin + ", distance completed " + distanceCompleted + ", currently going to " + (going == null ? "nowhere yet" : going);
    }
}
