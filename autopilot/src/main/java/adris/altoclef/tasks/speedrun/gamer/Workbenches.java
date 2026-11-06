package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.container.CollectFromFurnaceTask;
import adris.altoclef.tasks.container.DoStuffInContainerTask;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StationChoice;
import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.StationHook.Kind;
import adris.altoclef.util.helpers.WalkCost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

// the one place that knows about every table, furnace and smoker this run put down: where they are, whether they are being used,
// and when to go back for them. the rules are WorkbenchRules (pure and tested), the entries are Bench, the positions are the
// RunState lists the save file already had (placedTables / placedFurnaces / placedSmokers), so an old save loads as it was.
//
// two halves in one class: the static registry (record, forget, sync: RunState in, RunState out, no game) and the instance that
// drives a pickup in the world. the instance holds nothing that matters across phases, the pickup state is on the Bench, so every
// phase can have its own and a pickup carries on in whichever one gets the tick next. every log line starts with "bench:"
public final class Workbenches {
    public static final String OVERWORLD = "OVERWORLD";

    // the destroy task first, then the pickup of what it dropped. one at a time
    private Task destroy;
    private PickupDroppedItemTask collect;
    // runs of the drop pickup in a row that had nothing to chase, see WorkbenchRules.dropGone
    private int dropWander;
    private Bench taskFor;
    private String hud;
    // the tick a station screen was first seen open with nothing in the task tree working at it, -1 = not now
    private long strayScreenSince = -1;

    public String hud() {
        return hud;
    }

    // a fresh phase: the task objects go, the pickup itself does not. it was never the phase's to drop (PrepSupport.onExit used to
    // reset the slots and with them an in-flight pickup)
    public void reset() {
        destroy = null;
        collect = null;
        dropWander = 0;
        taskFor = null;
        hud = null;
    }

    // ---- the registry: RunState in, RunState out

    static void log(String line) {
        Debug.logInternal("bench: " + line);
    }

    static List<RunState.Pos> listOf(RunState state, Kind kind) {
        return switch (kind) {
            case TABLE -> state.placedTables;
            case FURNACE -> state.placedFurnaces;
            case SMOKER -> state.placedSmokers;
        };
    }

    // the kind is part of the key: a table and a furnace on the same x y z in two dimensions are two entries
    private static String key(Kind kind, RunState.Pos pos) {
        return kind.name() + ":" + legacyKey(pos);
    }

    // what saves wrote before the kind was in the key
    private static String legacyKey(RunState.Pos pos) {
        return pos.x + "," + pos.y + "," + pos.z;
    }

    static String dimensionOf(RunState state, Kind kind, RunState.Pos pos) {
        return state.placedDimension.getOrDefault(key(kind, pos), OVERWORLD);
    }

    // an old "x,y,z" entry goes to every kind standing there (it could not tell them apart, which is the bug), then it is gone
    private static void upgradeKeys(RunState state) {
        if (state.placedDimension.keySet().stream().noneMatch(k -> k.indexOf(':') < 0)) {
            return;
        }
        for (String old : new ArrayList<>(state.placedDimension.keySet())) {
            if (old.indexOf(':') >= 0) {
                continue;
            }
            String dimension = state.placedDimension.remove(old);
            for (Kind kind : Kind.values()) {
                for (RunState.Pos pos : listOf(state, kind)) {
                    if (legacyKey(pos).equals(old)) {
                        state.placedDimension.putIfAbsent(key(kind, pos), dimension);
                    }
                }
            }
        }
    }

    public static Bench find(RunState state, Kind kind, RunState.Pos pos) {
        for (Bench b : state.benches) {
            if (b.is(kind, pos)) {
                return b;
            }
        }
        return null;
    }

    // any kind at this spot (a furnace job names the block by its registry path, FurnaceWatch asks by position)
    public static Bench findAt(RunState state, RunState.Pos pos) {
        for (Bench b : state.benches) {
            if (b.pos.equals(pos)) {
                return b;
            }
        }
        return null;
    }

    // the lists are the truth (they are what is saved): an entry the lists do not have is dropped, a position they have that has no
    // entry gets one. a pickup in flight keeps its entry whatever the list says, its block is already down
    public static void sync(RunState state, long now) {
        upgradeKeys(state);
        for (Kind kind : Kind.values()) {
            for (RunState.Pos pos : listOf(state, kind)) {
                if (find(state, kind, pos) == null) {
                    state.benches.add(new Bench(kind, pos, dimensionOf(state, kind, pos), now));
                }
            }
        }
        state.benches.removeIf(b -> b.state != Bench.State.PICKING_UP && !listOf(state, b.kind).contains(b.pos));
        for (Bench b : state.benches) {
            if (WorkbenchRules.dropStaleDrive(b, now)) {
                log(b + ": nobody ran the pickup for " + WorkbenchRules.DRIVE_GRACE_TICKS / 20 + " s, back to standing");
            }
        }
    }

    // a placement the hook saw. false when we already knew it (a table that was placed, picked up and placed on the same spot is
    // one entry at a time)
    public static boolean record(RunState state, Kind kind, RunState.Pos pos, String dimension, long now) {
        sync(state, now);
        List<RunState.Pos> list = listOf(state, kind);
        if (list.contains(pos)) {
            Bench old = find(state, kind, pos);
            if (old == null || old.dimension.equals(dimension)) {
                return false;
            }
            // the same x y z in another dimension: the lists key on x y z alone, so the old one has to go even if it was busy (the log
            // line says so), or the new block would be mistaken for it
            forget(state, old, "a " + kind.word() + " went down on the same coordinates in the " + dimension.toLowerCase(java.util.Locale.ROOT));
        }
        list.add(pos);
        if (!OVERWORLD.equals(dimension)) {
            state.placedDimension.put(key(kind, pos), dimension);
        } else {
            // the map says nothing for the overworld, a stale key from an earlier station of this kind would read as the nether
            state.placedDimension.remove(key(kind, pos));
        }
        Bench placed = new Bench(kind, pos, dimension, now);
        state.benches.add(placed);
        log(placed + " placed");
        // this is the line to grep for the "second table next to the first" bug
        for (Bench other : state.benches) {
            if (other != placed && other.kind == kind && other.dimension.equals(dimension) && other.state != Bench.State.PICKING_UP
                    && WalkCost.nearStation(other.pos.x - pos.x, other.pos.y - pos.y, other.pos.z - pos.z)) {
                // a split smelt puts the next furnace next to the one cooking on purpose (SmeltSplit), not the bug this line hunts
                boolean split = kind == Kind.FURNACE && FurnaceJobs.isBusy(state, other.pos, dimension);
                log((split ? "furnace for a split smelt " : "DUPLICATE ") + placed + ": " + other + (split ? " (cooking) is " : " is ")
                        + Math.round(WalkCost.distance3d(other.pos.x - pos.x, other.pos.y - pos.y, other.pos.z - pos.z)) + " blocks from it");
            }
        }
        return true;
    }

    // a job at this spot was dropped as stale (FurnaceWatch.housekeeping): the container tracker still remembers our items in it, and
    // adopting them back would undo the give up and send a trip every stale period. not adopted until the station is seen empty
    public static void giveUp(RunState state, RunState.Pos pos, String dimension) {
        for (Bench b : state.benches) {
            if (b.pos.equals(pos) && b.dimension.equals(dimension) && b.kind != Kind.TABLE && !b.givenUp) {
                b.givenUp = true;
                log(b + ": the job here went stale and was dropped, not adopting what is still in it");
            }
        }
    }

    // the entry and its position are gone, with the reason in the log. everything that used to quietly drop a station from a list
    // comes through here
    public static void forget(RunState state, Bench b, String why) {
        listOf(state, b.kind).remove(b.pos);
        state.placedDimension.remove(key(b.kind, b.pos));
        state.benches.remove(b);
        log("forgot " + b + ": " + why);
    }

    // it is in the bag again, the entry's last state
    private static void retire(RunState state, Bench b) {
        log(b + " -> " + Bench.State.IN_BAG + " (picked up, " + (b.tries > 0 ? b.tries + " failed tries before" : "first try") + ")");
        b.state = Bench.State.IN_BAG;
        listOf(state, b.kind).remove(b.pos);
        state.placedDimension.remove(key(b.kind, b.pos));
        state.benches.remove(b);
    }

    // a spot added to a list of blocks we placed, once (blast furnaces and the like: the village loot must not take ours)
    public static void addOnce(List<RunState.Pos> list, RunState.Pos pos) {
        if (!list.contains(pos)) {
            list.add(pos);
        }
    }

    // ---- what the planner and the container tasks ask

    // a station of ours is held for planning purposes: standing (or about to be back in the bag) within `radius` of the point, in
    // this dimension, and `stands` says the block is still there (an unloaded chunk is given the benefit of the doubt)
    public static boolean heldNear(RunState state, Kind kind, String dimension, double px, double py, double pz, double radius,
                                   Predicate<RunState.Pos> stands) {
        for (Bench b : state.benches) {
            if (b.kind != kind || !b.dimension.equals(dimension)) {
                continue;
            }
            boolean held = b.state == Bench.State.PICKING_UP || stands.test(b.pos);
            if (held && WalkCost.stationDistance(b.pos.x, b.pos.y, b.pos.z, px, py, pz) <= radius) {
                return true;
            }
        }
        return false;
    }

    // there is one of this kind in the dimension at all, so a miss in heldNear means "out of range" and not "none"
    public static boolean any(RunState state, Kind kind, String dimension) {
        for (Bench b : state.benches) {
            if (b.kind == kind && b.dimension.equals(dimension)) {
                return true;
            }
        }
        return false;
    }

    // the rule a phase's isDone asks: nothing of ours is standing idle or coming down in this dimension
    public static boolean phaseMayEnd(RunState state, String dimension, long now) {
        sync(state, now);
        Predicate<Bench> hasJob = b -> FurnaceJobs.isBusy(state, b.pos, b.dimension);
        boolean mayEnd = WorkbenchRules.phaseMayEnd(state.benches, dimension, hasJob);
        if (mayEnd) {
            // the phase does not wait for these on purpose (the job was not worth a walk), but the station and what is in it stay
            // behind, and that should be in the log and not a surprise. once per bench per give up
            for (Bench b : WorkbenchRules.leftGivenUp(state.benches, dimension, hasJob)) {
                if (!b.givenUpLogged) {
                    b.givenUpLogged = true;
                    log(b + ": the phase may end with it still holding our items (its job went stale and was given up), leaving it standing");
                }
            }
        }
        return mayEnd;
    }

    // the nearest of ours of this kind standing in the world (not coming down, not in the bag) within `radius`, null for none. what
    // the container tasks get as standingWithin, and what the planner's walk back reads, so both pick the same block
    public static Bench nearestStanding(RunState state, Kind kind, String dimension, double x, double y, double z, double radius) {
        return nearestStanding(state, kind, dimension, x, y, z, radius, b -> true);
    }

    // the nearest that `ok` likes, a split smelt's next load asks for the nearest idle furnace (the busy one next to us is load 1's)
    public static Bench nearestStanding(RunState state, Kind kind, String dimension, double x, double y, double z, double radius,
                                        Predicate<Bench> ok) {
        Bench best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Bench b : state.benches) {
            if (!standing(b, kind, dimension)) {
                continue;
            }
            double d = WalkCost.stationDistance(b.pos.x, b.pos.y, b.pos.z, x, y, z);
            if (d <= radius && d < bestDistance && ok.test(b)) {
                best = b;
                bestDistance = d;
            }
        }
        return best;
    }

    // how many standing ones within `radius` that `ok` likes (SmeltSplit counts the idle furnaces it can use for free)
    public static int countStanding(RunState state, Kind kind, String dimension, double x, double y, double z, double radius, Predicate<Bench> ok) {
        int n = 0;
        for (Bench b : state.benches) {
            if (standing(b, kind, dimension) && WalkCost.stationDistance(b.pos.x, b.pos.y, b.pos.z, x, y, z) <= radius && ok.test(b)) {
                n++;
            }
        }
        return n;
    }

    // package private so the station golden can ask it per bench, nothing else calls it from outside this class
    static boolean standing(Bench b, Kind kind, String dimension) {
        return b.kind == kind && b.dimension.equals(dimension) && b.state != Bench.State.PICKING_UP && b.state != Bench.State.IN_BAG;
    }

    private static BlockPos blockPos(Bench b) {
        return new BlockPos(b.pos.x, b.pos.y, b.pos.z);
    }

    // the furnace or smoker StationChoice walks back to (DoStuffInContainerTask's far one of ours) when the bag cannot make one: the
    // nearest standing within the forget line, and `usable` (not coming down, nothing of ours cooking in it, reachable) says the
    // task would take it. a parked one (three pickups ran out on time) does not count: the task might still walk to it, but the
    // planner leaning short is a few cobble too many, leaning long is a furnace nobody budgeted
    public static Bench walkBackTo(RunState state, Kind kind, String dimension, double x, double y, double z, Predicate<Bench> usable) {
        return walkBackTo(state, kind, dimension, x, y, z, WorkbenchRules.FORGET_DISTANCE, usable);
    }

    // the same out to `radius`. the nearest standing one counts or none does, like the task's oursFar (a parked nearest one is not
    // swapped for the next one out)
    public static Bench walkBackTo(RunState state, Kind kind, String dimension, double x, double y, double z, double radius, Predicate<Bench> usable) {
        Bench far = nearestStanding(state, kind, dimension, x, y, z, radius);
        return far != null && !far.pickupFailed && usable.test(far) ? far : null;
    }

    // the planner's half of StationChoice's walk back: no item in the bag and ours standing within oursReach (WALK_BACK when the bag
    // could make one, the forget line when it can't). MinecraftFacts counts the furnace or smoker as held on this, so the plan never
    // budgets a furnace the smelt is going to walk past. `canMake` is DoStuffInContainerTask.bagCanMake, `usable` its walkBackUsable
    public static boolean plannerWalksBack(RunState state, Kind kind, String dimension, double x, double y, double z, boolean inBag,
                                           boolean canMake, Predicate<Bench> usable) {
        return plannerWalksBack(state, kind, dimension, x, y, z, inBag, canMake, 0, usable);
    }

    // `hold`: how far past that line it still counts, for a station the planner already counted last look (StationChoice.HOLD is the
    // task's own, it keeps the one it is walking to that far past the line). only with the bag able to make one: without, the line is
    // the forget line, and a station out there goes with the forget (decide) and not with a slack
    public static boolean plannerWalksBack(RunState state, Kind kind, String dimension, double x, double y, double z, boolean inBag,
                                           boolean canMake, double hold, Predicate<Bench> usable) {
        double reach = StationChoice.oursReach(kind, canMake) + (canMake ? hold : 0);
        return !inBag && walkBackTo(state, kind, dimension, x, y, z, reach, usable) != null;
    }

    // what the container tasks see (StationHook), wired in by the run
    public static StationHook.Source source(RunState state, GamerFacts facts) {
        return source(state, facts, () -> 0);
    }

    // `cobbleOwed`: what the stone kit still eats (GamerTask, KitPlanner.toolCobble), kept out of "the bag can make a furnace"
    public static StationHook.Source source(RunState state, GamerFacts facts, IntSupplier cobbleOwed) {
        return source(state, facts, cobbleOwed, () -> { });
    }

    // `recorded`: a new station went into the book (the run saves, same as when the block event hears one)
    public static StationHook.Source source(RunState state, GamerFacts facts, IntSupplier cobbleOwed, Runnable recorded) {
        return new StationHook.Source() {
            @Override
            public int cobbleOwed() {
                return cobbleOwed.getAsInt();
            }

            // the same record the block event uses, so a station that was reported and heard (either way round) is one bench
            @Override
            public void placed(Kind kind, BlockPos pos, String dimension, long now) {
                RunState.Pos at = new RunState.Pos(pos.getX(), pos.getY(), pos.getZ());
                if (record(state, kind, at, dimension, now)) {
                    recorded.run();
                }
                // putting it down is the first use of it: the task goes on to the craft or the load, and a tick with nothing in the
                // tree in between must not look like it is over
                Workbenches.touch(state, kind, at, dimension, now);
            }

            @Override
            public void touch(Kind kind, BlockPos pos, String dimension, long now) {
                Workbenches.touch(state, kind, new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()), dimension, now);
            }

            @Override
            public BlockPos standingWithin(Kind kind, double x, double y, double z, double radius) {
                Bench best = nearestStanding(state, kind, facts.dimension().name(), x, y, z, radius);
                return best == null ? null : new BlockPos(best.pos.x, best.pos.y, best.pos.z);
            }

            @Override
            public BlockPos standingWithin(Kind kind, double x, double y, double z, double radius, Predicate<BlockPos> ok) {
                Bench best = nearestStanding(state, kind, facts.dimension().name(), x, y, z, radius, b -> ok.test(blockPos(b)));
                return best == null ? null : blockPos(best);
            }

            @Override
            public int countWithin(Kind kind, double x, double y, double z, double radius, Predicate<BlockPos> ok) {
                return countStanding(state, kind, facts.dimension().name(), x, y, z, radius, b -> ok.test(blockPos(b)));
            }

            @Override
            public boolean pickingUp(Kind kind) {
                return WorkbenchRules.placeVetoed(state.benches, kind, facts.gameTime());
            }

            @Override
            public boolean pickingUp(BlockPos pos) {
                return WorkbenchRules.targetVetoed(state.benches, new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()), facts.gameTime());
            }

            @Override
            public boolean ours(BlockPos pos) {
                return here(pos) != null;
            }

            @Override
            public boolean givenUp(BlockPos pos) {
                Bench b = here(pos);
                return b != null && b.givenUp;
            }

            @Override
            public boolean parked(BlockPos pos) {
                Bench b = here(pos);
                return b != null && b.pickupFailed;
            }

            private Bench here(BlockPos pos) {
                String dimension = facts.dimension().name();
                for (Bench b : state.benches) {
                    if (b.dimension.equals(dimension) && b.pos.x == pos.getX() && b.pos.y == pos.getY() && b.pos.z == pos.getZ()) {
                        return b;
                    }
                }
                return null;
            }
        };
    }

    // ---- the world half

    // the pickup that is in flight, if there is one, and nothing else: the phases call this first every tick so a pickup that
    // started finishes before the kit task gets a say (the next craft used to walk off with a furnace half taken)
    public Task resume(AltoClef mod, GamerContext ctx) {
        return run(mod, ctx, List.of(), false, false);
    }

    // every bench gets its decision, an in-flight pickup is driven, and when none is in flight the first one that is owed
    // starts. `plan` is the phase's need list in order. returns the pickup task or null. this is the call of the phases that go
    // back for a furnace (GATHER, IRON, PORTAL), so it is also where a load that was cut off gets adopted as a job
    public Task tick(AltoClef mod, GamerContext ctx, List<KitNeed> plan) {
        return run(mod, ctx, plan, true, true);
    }

    // a phase that does not run the plan side of this (everything after the portal): the same decisions with no needs, so an idle
    // station that nothing is using comes down once its screen has been shut for a second. no adoption: nothing there would ever
    // collect the job
    public Task sweep(AltoClef mod, GamerContext ctx) {
        return run(mod, ctx, List.of(), true, false);
    }

    private Task run(AltoClef mod, GamerContext ctx, List<KitNeed> plan, boolean startNew, boolean adopt) {
        RunState state = ctx.state();
        GamerFacts f = ctx.facts();
        long now = f.gameTime();
        hud = null;
        sync(state, now);
        if (state.benches.isEmpty()) {
            return null;
        }
        String dimension = f.dimension().name();
        Player player = mod.getPlayer();
        Vec3 me = player.position();
        stampUse(mod, state, dimension, me, now);
        if (startNew) {
            closeStrayScreen(mod, now);
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Math.min(plan.size(), 1 + WorkbenchRules.LOOKAHEAD); i++) {
            names.add(plan.get(i).catalogueName());
        }
        boolean wooden = KitPlanner.have(f, "wooden_pickaxe") > 0;
        boolean stone = KitPlanner.have(f, "stone_pickaxe") > 0;
        long limit = WorkbenchRules.PICKUP_TRY_TICKS;
        Bench active = null;
        Bench start = null;
        WorkbenchRules.Look startLook = null;
        Predicate<Bench> hasJob = x -> FurnaceJobs.isBusy(state, x.pos, x.dimension);
        // a pickup may start from far off only when the phase is waiting on nothing but the benches. sweep never: no phase end waits
        // on it, a far one there just stands until the forget line has it
        boolean lastChance = adopt && WorkbenchRules.lastChance(plan.isEmpty(), !f.furnaceJobs().isEmpty());
        // every station's "are we coming back to it" first: a table's anchor reads its furnace's answer. only the looks that have the
        // plan (tick, sweep): resume() runs first every tick with no plan and would let go of everything and take it back again
        if (startNew) {
            comingBack(state, f, ctx.stations(), names, dimension, me, now);
        }
        // a furnace or smoker load in the tree or in flight: the furnace it goes in is not busy yet (the job is recorded when the
        // load is done), so "done with the table" waits for it, or the table could come down before the furnace anchors it
        boolean smelting = smeltingNow(mod, now);
        for (Bench b : new ArrayList<>(state.benches)) {
            boolean flying = b.state == Bench.State.PICKING_UP;
            if (!flying && !startNew) {
                continue;
            }
            Bench.State before = b.state;
            anchor(state, b, WorkbenchRules.anchorOf(b, state.benches, hasJob), now);
            WorkbenchRules.Look look = look(mod, ctx, b, me, dimension, WorkbenchRules.neededSoon(b.kind, names, wooden, stone), limit);
            if (startNew && !smelting && WorkbenchRules.doneUsing(b, look)) {
                doneWith(mod, b, look, names, wooden, stone);
            }
            if (adopt && WorkbenchRules.adoptable(b, look)) {
                adoptLoad(mod, ctx, b, now);
            }
            WorkbenchRules.Call call = WorkbenchRules.decide(b, look);
            if (before != b.state) {
                log(b.kind.word() + " at " + b.pos + ": " + before + " -> " + b.state
                        + (b.state == Bench.State.BUSY ? (look.jobHere() ? " (a job is cooking)" : " (our items are in it and no job points at it)") : ""));
            }
            if (call != WorkbenchRules.Call.WAIT) {
                b.waitLogged = "";
            }
            if (call != WorkbenchRules.Call.ELSEWHERE) {
                b.elsewhereLogged = false;
            }
            switch (call) {
                case FORGET_GONE -> dropEntry(state, b, "the block is gone (broken or blown up)");
                case FORGET_TOO_FAR -> dropEntry(state, b, "it is " + Math.round(look.distance()) + " blocks away (more than " + Math.round(WorkbenchRules.FORGET_DISTANCE) + ")");
                case FORGET_FAR_TABLE -> dropEntry(state, b, "it is " + Math.round(look.distance()) + " blocks away (more than "
                        + Math.round(WorkbenchRules.FAR_TABLE_DISTANCE) + ") and the bag can make another");
                case FORGET_LEFT_DIMENSION -> dropEntry(state, b, "we left the " + b.dimension.toLowerCase(java.util.Locale.ROOT));
                case ELSEWHERE -> {
                    if (!b.elsewhereLogged) {
                        b.elsewhereLogged = true;
                        log(b + ": busy and we are not in the " + b.dimension.toLowerCase(java.util.Locale.ROOT)
                                + " any more, keeping it for the visit when we are back");
                    }
                }
                case DROP_LOST -> dropEntry(state, b, "the block is down and its drop never made it into the bag");
                case ABORT_BUSY -> abort(b, "it has our items in it now");
                case TIMED_OUT -> failed(state, b, now, "ran out of time (" + Math.round(limit / 20.0) + " s of trying)", true);
                case UNREACHABLE -> failed(state, b, now, "cannot be broken at all", false);
                case CONTINUE -> active = b;
                case PICK_UP -> {
                    if (WorkbenchRules.tooFarForLastTrip(look, lastChance)) {
                        dropEntry(state, b, "it is " + Math.round(look.distance()) + " blocks away (more than " + Math.round(StationChoice.WALK_BACK)
                                + ") and only the phase end wants it, not walking back across the map for that");
                        break;
                    }
                    if (WorkbenchRules.holdFar(b, look, lastChance)) {
                        if (!b.farHeldLogged) {
                            b.farHeldLogged = true;
                            log(b + ": should come down (" + WorkbenchRules.reason(b, look) + ") but we are " + Math.round(look.distance())
                                    + " blocks from it, no trip back mid-work, leaving it standing until we pass within "
                                    + Math.round(WorkbenchRules.NEAR) + " of it");
                        }
                        break;
                    }
                    // the table first (the kit wants it for the crafts after), then the nearest
                    if (start == null || better(b, start, me)) {
                        start = b;
                        startLook = look;
                    }
                }
                case WAIT -> waiting(b, look, now);
                default -> {
                }
            }
        }
        if (active == null && start != null && startNew) {
            WorkbenchRules.beginPickup(start, now, f.count(itemOf(start.kind)), WorkbenchRules.reason(start, startLook));
            log(start + ": picking it up, " + start.why + " (try " + (start.tries + 1) + " of " + WorkbenchRules.MAX_TRIES + ")");
            active = start;
        }
        return active == null ? null : drive(mod, ctx, active);
    }

    // a table, furnace or smoker screen that nothing in the task tree is working at keeps its station "in use" for ever (a craft that
    // ended with the menu up), and a station that is in use is never picked up, so a phase waiting on it would wait for the stall
    // timer. a second of that and the screen is shut
    private void closeStrayScreen(AltoClef mod, long now) {
        AbstractContainerMenu menu = mod.getPlayer().containerMenu;
        boolean stationScreen = menu instanceof CraftingMenu || menu instanceof FurnaceMenu || menu instanceof SmokerMenu;
        Task root = mod.getUserTaskChain().getCurrentTask();
        // a loot task counts as working too: its click can land on the furnace next to a village chest, and it shuts that screen
        // itself before the next click (InteractWithBlockTask), so us shutting it as well is two closes racing one re-click
        boolean working = root != null && root.thisOrChildSatisfies(t -> t instanceof DoStuffInContainerTask || t instanceof CollectFromFurnaceTask
                || t instanceof LootContainerTask);
        if (!stationScreen || working) {
            strayScreenSince = -1;
            return;
        }
        if (strayScreenSince < 0) {
            strayScreenSince = now;
        } else if (now - strayScreenSince >= WorkbenchRules.SETTLE_TICKS) {
            log("a " + menu.getClass().getSimpleName() + " has been open for a second with nothing working at it, closing it");
            StorageHelper.closeScreen();
            strayScreenSince = -1;
        }
    }

    // the one smoker or furnace of each kind the plan comes back to is the nearest of ours in this dimension (the one CookGate and
    // the smelt would use), and the meat goes in the furnace only when no smoker of ours stands near or sits in the bag, the same
    // test CookGate makes
    private static void comingBack(RunState state, GamerFacts f, StationVerdict stations, List<String> names, String dimension, Vec3 me, long now) {
        boolean smokerOfOurs = stations.smoker().heldForPlanner() || f.count(Items.SMOKER) > 0;
        for (Bench b : state.benches) {
            String back = b.dimension.equals(dimension) && b == nearestOf(state, b.kind, dimension, me)
                    ? WorkbenchRules.comingBack(b.kind, names, smokerOfOurs) : null;
            switch (WorkbenchRules.updateComingBack(b, back, now)) {
                case ANCHORED -> log(b + ": keeping it, the plan brings us back " + back);
                case RELEASED -> log(b + ": nothing brings us back to it now, deciding it again");
                default -> {
                }
            }
        }
    }

    private static Bench nearestOf(RunState state, Kind kind, String dimension, Vec3 me) {
        Bench best = null;
        for (Bench b : state.benches) {
            if (b.kind == kind && b.dimension.equals(dimension) && b.state != Bench.State.PICKING_UP && (best == null
                    || WalkCost.stationDistance(b.pos.x, b.pos.y, b.pos.z, me.x, me.y, me.z) < WalkCost.stationDistance(best.pos.x, best.pos.y, best.pos.z, me.x, me.y, me.z))) {
                best = b;
            }
        }
        return best;
    }

    // one line each way: it waits beside a cooking furnace now, or that furnace is done and this one gets decided again
    private static void anchor(RunState state, Bench b, Bench anchor, long now) {
        Bench was = b.anchor;
        switch (WorkbenchRules.updateAnchor(b, anchor, now, WorkbenchRules.anchorLeft(was, state.benches))) {
            case ANCHORED -> log("keeping " + b.kind.word() + " at " + at(b) + (anchor.state == Bench.State.BUSY
                    ? ", the " + anchor.kind.word() + " at " + at(anchor) + " is cooking and we'll be back for it"
                    : ", we'll be back at the " + anchor.kind.word() + " at " + at(anchor) + " " + anchor.comingBack));
            case RELEASED -> log(b.kind.word() + " at " + at(b) + ": we are not coming back to the " + was.kind.word() + " at " + at(was)
                    + " any more, deciding this one again");
            default -> {
            }
        }
    }

    private static String at(Bench b) {
        return b.pos.x + " " + b.pos.y + " " + b.pos.z;
    }

    // we just finished with it (screen shut a second ago, nothing at it): is the next job close to it or a walk away. by now the next
    // need's own task has had that second to start, and its trackBlock is what makes the logs or the stone show up here, so this asks
    // the trackers what they already have and scans nothing itself
    private void doneWith(AltoClef mod, Bench b, WorkbenchRules.Look look, List<String> names, boolean wooden, boolean stone) {
        String next = names.isEmpty() ? null : names.get(0);
        boolean usesThis = WorkbenchRules.needsStation(b.kind, next, wooden, stone);
        double site = usesThis || !look.neededSoon() ? WorkbenchRules.UNKNOWN_SITE : siteDistance(mod, b, WorkbenchRules.siteOf(next));
        // "we are standing at both" only holds when we are: a release from far off (a stale give up, a furnace that blew up) is a
        // plain unknown and the outside clock has it
        boolean released = b.redecide && look.distance() <= WorkbenchRules.NEAR;
        WorkbenchRules.Done done = WorkbenchRules.doneWith(look.neededSoon(), usesThis, site, released);
        WorkbenchRules.settleDone(b, done);
        String head = "done with " + b.kind.word() + " at " + at(b) + ", ";
        log(head + switch (done) {
            case PICK_UP_UNWANTED -> "nothing in the next " + (WorkbenchRules.LOOKAHEAD + 1) + " needs wants it -> picking it up";
            case KEEP_UNKNOWN -> "next job (" + next + ") unknown, keeping it for now";
            case KEEP -> usesThis ? "next job (" + next + ") is at this " + b.kind.word() + " -> keeping it"
                    : "next job (" + next + ") is " + Math.round(site) + " blocks away -> keeping it";
            case PICK_UP_FAR -> "next job (" + next + ") is " + Math.round(site) + " blocks away -> picking it up";
            case PICK_UP_RELEASED -> "next job (" + next + ") unknown and what we kept it for is done -> picking it up";
        });
    }

    // straight line from the middle of the station to the nearest place the trackers know for that work, UNKNOWN_SITE when they know none
    private static double siteDistance(AltoClef mod, Bench b, WorkbenchRules.Site site) {
        return siteDistance(mod, new Vec3(b.pos.x + 0.5, b.pos.y + 0.5, b.pos.z + 0.5), site);
    }

    // the same from any point, for the furnace plan's "is the next trip a long one" (from where we stand)
    public static double siteFrom(AltoClef mod, Vec3 at, String need) {
        return siteDistance(mod, at, WorkbenchRules.siteOf(need));
    }

    private static double siteDistance(AltoClef mod, Vec3 at, WorkbenchRules.Site site) {
        return switch (site) {
            case NONE -> WorkbenchRules.UNKNOWN_SITE;
            case SHEEP -> mobDistance(mod, at, Sheep.class);
            case ANIMALS -> mobDistance(mod, at, Cow.class, Pig.class, Sheep.class, Chicken.class);
            default -> blockDistance(mod, at, blocksOf(site));
        };
    }

    private static Block[] blocksOf(WorkbenchRules.Site site) {
        return switch (site) {
            case LOGS -> ItemHelper.itemsToBlocks(ItemHelper.LOG);
            case STONE -> new Block[]{Blocks.STONE, Blocks.COBBLESTONE};
            case COAL -> new Block[]{Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE};
            case IRON -> new Block[]{Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE};
            case GRAVEL -> new Block[]{Blocks.GRAVEL};
            case DIAMOND -> new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE};
            default -> new Block[0];
        };
    }

    // only the blocks somebody is already tracking: asking for an untracked one is a warning in the log and an empty answer anyway
    private static double blockDistance(AltoClef mod, Vec3 at, Block[] blocks) {
        List<Block> tracked = new ArrayList<>();
        for (Block block : blocks) {
            if (mod.getBlockTracker().isTracking(block)) {
                tracked.add(block);
            }
        }
        if (tracked.isEmpty()) {
            return WorkbenchRules.UNKNOWN_SITE;
        }
        return mod.getBlockTracker().getNearestTracking(at, tracked.toArray(new Block[0]))
                .map(p -> WalkCost.stationDistance(p.getX(), p.getY(), p.getZ(), at.x, at.y, at.z)).orElse(WorkbenchRules.UNKNOWN_SITE);
    }

    private static double mobDistance(AltoClef mod, Vec3 at, Class<?>... types) {
        return mod.getEntityTracker().getClosestEntity(at, e -> e instanceof LivingEntity l && !l.isBaby(), (Class[]) types)
                .map(e -> WalkCost.distance3d(e.getX() - at.x, e.getY() - at.y, e.getZ() - at.z)).orElse(WorkbenchRules.UNKNOWN_SITE);
    }

    // the table before the furnace before the smoker, and within a kind whichever is closer
    private static boolean better(Bench candidate, Bench best, Vec3 me) {
        if (candidate.kind != best.kind) {
            return candidate.kind.ordinal() < best.kind.ordinal();
        }
        return WalkCost.stationDistance(candidate.pos.x, candidate.pos.y, candidate.pos.z, me.x, me.y, me.z)
                < WalkCost.stationDistance(best.pos.x, best.pos.y, best.pos.z, me.x, me.y, me.z);
    }

    // FurnaceWatch's moment: a visit just emptied this station and we are standing next to it, so it comes down before we leave.
    // no gates, the screen is about to close and nothing is cooking. false when it is not ours or cannot be broken
    public boolean pickUpNow(AltoClef mod, GamerContext ctx, RunState.Pos pos, String why) {
        RunState state = ctx.state();
        sync(state, ctx.facts().gameTime());
        Bench b = findAt(state, pos);
        if (b == null || b.state == Bench.State.PICKING_UP || !b.dimension.equals(ctx.facts().dimension().name())) {
            return b != null && b.state == Bench.State.PICKING_UP;
        }
        // one pickup at a time, the one in flight finishes first and this one is the next look's business
        for (Bench other : state.benches) {
            if (other != b && other.state == Bench.State.PICKING_UP) {
                log(b + ": emptied, but " + other + " is still coming down, this one waits its turn");
                return false;
            }
        }
        if (!breakable(mod, bp(b.pos))) {
            log(b + ": emptied but cannot be broken at all, the next look decides");
            return false;
        }
        // emptied in the middle of a hunt or with more iron to come: we are coming back to it, so it stays for the next batch
        GamerFacts f = ctx.facts();
        boolean nothingAhead = WorkbenchRules.nothingAhead(b.kind, CookGate.ironOwed(f, ctx.cfg().overworld), CookGate.raw(f),
                ctx.stations().smokerStandingNear() || f.count(Items.SMOKER) > 0, ctx.food().shortOf(ctx.food().huntsTo()));
        if (WorkbenchRules.keepForComingBack(b, nothingAhead)) {
            log(b + ": emptied, but the plan brings us back " + b.comingBack + ", leaving it standing");
            return false;
        }
        if (b.comingBack != null) {
            log(b + ": emptied and nothing is left for it, the \"" + b.comingBack + "\" was only the plan's hold, taking it now");
            b.comingBack = null;
            b.comingBackLostSince = WorkbenchRules.NEVER;
        }
        b.state = Bench.State.STANDING;
        WorkbenchRules.beginPickup(b, ctx.facts().gameTime(), ctx.facts().count(itemOf(b.kind)), why);
        log(b + ": picking it up, " + why);
        return true;
    }

    private void abort(Bench b, String why) {
        log(b + ": pickup called off, " + why);
        b.state = Bench.State.BUSY;
        b.pickupStart = WorkbenchRules.NEVER;
        b.broken = false;
        if (taskFor == b) {
            reset();
        }
    }

    // `onTime`: the try ran out of its (driven) time, the block is still there. those never forget it, three of them park it until we
    // come back near. only a block that cannot be broken at all is written off
    private void failed(RunState state, Bench b, long now, String why, boolean onTime) {
        boolean last = WorkbenchRules.failedTry(b, now);
        if (taskFor == b) {
            reset();
        }
        if (last && onTime) {
            WorkbenchRules.parkFailed(b);
            log(b + ": pickup " + why + ", " + WorkbenchRules.MAX_TRIES + " tries used up and the block still stands, keeping it and"
                    + " trying again once we have been away and come back near");
            return;
        }
        if (last) {
            dropEntry(state, b, "pickup " + why + ", " + WorkbenchRules.MAX_TRIES + " tries used up");
            return;
        }
        log(b + ": pickup " + why + ", try " + b.tries + " of " + WorkbenchRules.MAX_TRIES + ", again in "
                + WorkbenchRules.RETRY_GAP_TICKS / 20 + " s");
    }

    // forget, and let go of the task objects if they were for this one
    private void dropEntry(RunState state, Bench b, String why) {
        if (taskFor == b) {
            reset();
        }
        forget(state, b, why);
    }

    // a station of ours in this dimension that the task tree is working at right now: a screen open at it, a load in flight, or a
    // craft or smelt of its kind under the user task. the same judgement the bench makes for its own wait, so a phase that has handed
    // the wheel to a task can ask it before holding that task for the stations (the hold would end the work that keeps them in use)
    public boolean inUse(AltoClef mod, GamerContext ctx) {
        RunState state = ctx.state();
        String dimension = ctx.facts().dimension().name();
        long now = ctx.facts().gameTime();
        for (Bench b : state.benches) {
            if (b.dimension.equals(dimension) && (b.state == Bench.State.STANDING || b.state == Bench.State.BUSY) && !idle(mod, ctx, b, now)) {
                return true;
            }
        }
        return false;
    }

    // one line per reason, a wait is a state and not an event
    private static void waiting(Bench b, WorkbenchRules.Look look, long now) {
        String why = !look.idle() ? "it is in use" : now - b.placedTick < WorkbenchRules.PLACE_GUARD_TICKS ? "just placed"
                : b.retryAt != WorkbenchRules.NEVER && now < b.retryAt ? "retry gap" : "screen was open a moment ago";
        if (!why.equals(b.waitLogged)) {
            b.waitLogged = why;
            log(b + ": should come down (" + WorkbenchRules.reason(b, look) + ") but holding: " + why);
        }
    }

    private WorkbenchRules.Look look(AltoClef mod, GamerContext ctx, Bench b, Vec3 me, String dimension, boolean neededSoon, long limit) {
        long now = ctx.facts().gameTime();
        double distance = WalkCost.stationDistance(b.pos.x, b.pos.y, b.pos.z, me.x, me.y, me.z);
        if (!b.dimension.equals(dimension)) {
            // the block coordinates mean nothing in this world. a recorded job in that dimension is still evidence it holds our items
            return new WorkbenchRules.Look(now, distance, false, false, true, false, jobHere(ctx, b), neededSoon, false, limit);
        }
        BlockPos at = bp(b.pos);
        boolean loaded = mod.getChunkTracker().isChunkLoaded(at);
        boolean gone = loaded && !mod.getWorld().getBlockState(at).is(blockOf(b.kind));
        boolean idle = idle(mod, ctx, b, now);
        boolean canBreak = !loaded || gone || breakable(mod, at);
        boolean canRecraft = b.kind == Kind.TABLE && WorkbenchRules.canRecraftTable(ctx.facts().count(ItemHelper.PLANKS), ctx.facts().count(ItemHelper.LOG));
        return new WorkbenchRules.Look(now, distance, true, gone, idle, holdsStuff(mod, ctx, b), jobHere(ctx, b), neededSoon, canBreak, limit, canRecraft);
    }

    // can the pickup break this block at all. not WorldHelper.canBreak: that one says no to anything on the "don't mine this" list,
    // and every furnace that ever had a job is on it (FurnaceWatch keeps our digging off it, the collect task too while it is at
    // it), so a smoker we had just emptied was "cannot be broken from here" three times and forgotten standing. it also says no to
    // a spot the tracker marked unreachable, which is a walk that failed, and walking into reach is DestroyBlockTask's job. a
    // furnace, a smoker or a table can always be broken
    static boolean breakable(AltoClef mod, BlockPos at) {
        return mod.getWorld().getBlockState(at).getDestroySpeed(mod.getWorld(), at) >= 0;
    }

    // an interrupted load: our items are in this one and no job points at it. the job it becomes is stranded (nothing is known to be
    // cooking) and due now, so the phase's own collect trip walks there, takes what is in it and the empty-after-visit rule takes
    // the station down. what the cache saw is only the dominant item and a count, the visit reads the real slots
    private void adoptLoad(AltoClef mod, GamerContext ctx, Bench b, long now) {
        ContainerCache cache = mod.getItemStorage().getContainerAtPosition(bp(b.pos)).orElse(null);
        Item item = cache == null ? null : cache.mostOfNonFuel();
        if (item == null) {
            return;
        }
        int count = cache.nonFuelCount();
        String input = BuiltInRegistries.ITEM.getKey(item).getPath();
        RunState.FurnaceJob job = FurnaceJobs.adopted(b.pos, b.dimension, b.kind == Kind.SMOKER ? "smoker" : "furnace", input, count, now);
        job.unitsEach = AsyncSmelting.unitsOfOutput(job.output);
        FurnaceJobs.record(ctx.state().furnaceJobs, job);
        ctx.save();
        log(b + ": holds " + count + " of our " + input + " and no job points at it (a load that was cut off), adopting it as a stranded job"
                + " so the next visit empties it");
    }

    private static boolean smeltingNow(AltoClef mod, long now) {
        if (WorkbenchRules.loadInFlight(mod.getPlayer().containerMenu instanceof AbstractFurnaceMenu, AsyncSmelting.lastWork(), now)) {
            return true;
        }
        Task root = mod.getUserTaskChain().getCurrentTask();
        return root != null && root.thisOrChildSatisfies(t -> t instanceof DoStuffInContainerTask d
                && (d.stationKind() == Kind.FURNACE || d.stationKind() == Kind.SMOKER));
    }

    // the screen is shut, nothing in the task tree is working at this kind of station, and no furnace load is half done
    private boolean idle(AltoClef mod, GamerContext ctx, Bench b, long now) {
        // the container task working at this very station said so lately (a lease), and that holds on a tick the tree is empty
        if (WorkbenchRules.leased(b, now)) {
            return false;
        }
        AbstractContainerMenu menu = mod.getPlayer().containerMenu;
        boolean screen = menuOf(b.kind, menu);
        // the table too: its pickup takes the wheel and would shut a furnace screen halfway through the load like any other
        boolean load = WorkbenchRules.loadInFlight(menu instanceof AbstractFurnaceMenu, AsyncSmelting.lastWork(), now);
        // the tree is last tick's, which is exactly what we want to look at: a craft or a smelt anywhere under the user task
        // means a station of this kind is about to be used (collecting its ingredients does not count, the plan lookahead has that).
        // per kind, not per station, so it still holds every bench of the kind (see WorkbenchRules.idle)
        Task root = mod.getUserTaskChain().getCurrentTask();
        boolean tree = !screen && !load && root != null
                && root.thisOrChildSatisfies(t -> t instanceof DoStuffInContainerTask d && d.stationKind() == b.kind);
        return WorkbenchRules.idle(b, now, screen, load, tree);
    }

    // a container task is working at or walking to this station: its lease starts over. false when the book has no such bench (a
    // village's table, one already forgotten)
    public static boolean touch(RunState state, Kind kind, RunState.Pos pos, String dimension, long now) {
        Bench b = find(state, kind, pos);
        if (b == null || !b.dimension.equals(dimension)) {
            return false;
        }
        b.leasedTick = now;
        return true;
    }

    // a furnace or smoker with a job in it, or that the container tracker last saw with something in it (we are the only ones who
    // put things in these). a table holds nothing
    private boolean holdsStuff(AltoClef mod, GamerContext ctx, Bench b) {
        if (b.kind == Kind.TABLE) {
            return false;
        }
        return jobHere(ctx, b) || mod.getItemStorage().getContainerAtPosition(bp(b.pos)).map(ContainerCache::holdsMoreThanFuel).orElse(false);
    }

    // a recorded job is the hard evidence. the container tracker's last look can lag the slots by a tick or two, so it may keep a
    // station out of the pickup's start but never calls one off that is already coming down
    private static boolean jobHere(GamerContext ctx, Bench b) {
        return b.kind != Kind.TABLE && FurnaceJobs.isBusy(ctx.state(), b.pos, b.dimension);
    }

    // an open screen of the kind is use. the closest bench of the kind is the one it belongs to
    private void stampUse(AltoClef mod, RunState state, String dimension, Vec3 me, long now) {
        AbstractContainerMenu menu = mod.getPlayer().containerMenu;
        for (Kind kind : Kind.values()) {
            if (!menuOf(kind, menu)) {
                continue;
            }
            Bench closest = null;
            for (Bench b : state.benches) {
                if (b.kind == kind && b.dimension.equals(dimension) && (closest == null || WalkCost.stationDistance(b.pos.x, b.pos.y, b.pos.z, me.x, me.y, me.z)
                        < WalkCost.stationDistance(closest.pos.x, closest.pos.y, closest.pos.z, me.x, me.y, me.z))) {
                    closest = b;
                }
            }
            if (closest != null) {
                closest.lastUsedTick = now;
                // a new use, the next "done with it" decides again
                closest.leaveNow = false;
                // a smelt task has the screen open again, so whatever job it records is a new story and a load that gets cut off
                // is adopted like any other
                closest.clearGivenUp();
            }
        }
    }

    private static boolean menuOf(Kind kind, AbstractContainerMenu menu) {
        return switch (kind) {
            case TABLE -> menu instanceof CraftingMenu;
            case FURNACE -> menu instanceof FurnaceMenu;
            case SMOKER -> menu instanceof SmokerMenu;
        };
    }

    // break the block, then collect what it dropped. runs until the item is in the bag or WorkbenchRules says otherwise
    private Task drive(AltoClef mod, GamerContext ctx, Bench b) {
        GamerFacts f = ctx.facts();
        long now = f.gameTime();
        WorkbenchRules.drove(b, now);
        BlockPos at = bp(b.pos);
        Item item = itemOf(b.kind);
        if (taskFor != b) {
            destroy = null;
            collect = null;
            dropWander = 0;
            taskFor = b;
        }
        if (!b.broken) {
            if (destroy == null) {
                destroy = new DestroyBlockTask(at);
            }
            // an unloaded chunk reads as air, so "finished" means nothing until it is loaded: walk there first
            boolean loaded = mod.getChunkTracker().isChunkLoaded(at);
            boolean down = loaded && (!mod.getWorld().getBlockState(at).is(blockOf(b.kind)) || destroy.isFinished(mod));
            if (down) {
                WorkbenchRules.blockBroken(b, now);
                log(b + ": the block is down, collecting the drop");
            } else {
                hud = "Picking up the " + name(b.kind);
                return destroy;
            }
        }
        if (WorkbenchRules.pickupDone(true, f.count(item), b.bagBefore)) {
            reset();
            retire(ctx.state(), b);
            ctx.progress("took the " + b.kind.word() + " back");
            return null;
        }
        if (collect == null) {
            collect = new PickupDroppedItemTask(item, 1);
        }
        if (collect.isFinished(mod)) {
            // the pickup says there is nothing left to pick up and the bag disagrees: it fell somewhere we cannot see, or a creeper
            // got it. nothing to wait for
            reset();
            forget(ctx.state(), b, "the pickup found no drop and the bag has none");
            return null;
        }
        // the pickup's own wander for a missing drop is an explore with no end, and a refused drop sits there banned. either way
        // the station is gone for us, let it go now and the plan crafts another
        dropWander = WorkbenchRules.dropWanderStreak(dropWander, collect.wasWandering());
        String gone = WorkbenchRules.dropGone(collect.gaveUpReason(), dropWander);
        if (gone != null) {
            reset();
            forget(ctx.state(), b, gone);
            return null;
        }
        hud = "Picking up the " + name(b.kind);
        return collect;
    }

    private static String name(Kind kind) {
        return kind == Kind.TABLE ? "crafting table" : kind.word();
    }

    static BlockPos bp(RunState.Pos pos) {
        return new BlockPos(pos.x, pos.y, pos.z);
    }

    static Block blockOf(Kind kind) {
        return switch (kind) {
            case TABLE -> Blocks.CRAFTING_TABLE;
            case FURNACE -> Blocks.FURNACE;
            case SMOKER -> Blocks.SMOKER;
        };
    }

    static Item itemOf(Kind kind) {
        return switch (kind) {
            case TABLE -> Items.CRAFTING_TABLE;
            case FURNACE -> Items.FURNACE;
            case SMOKER -> Items.SMOKER;
        };
    }
}
