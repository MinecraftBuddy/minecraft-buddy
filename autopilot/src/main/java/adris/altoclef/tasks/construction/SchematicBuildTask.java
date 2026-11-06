package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.construction.schematic.BuildMemory;
import adris.altoclef.tasks.construction.schematic.BuildNightSleep;
import adris.altoclef.tasks.construction.schematic.CountStep;
import adris.altoclef.tasks.construction.schematic.GatherGuard;
import adris.altoclef.tasks.construction.schematic.GatherPlan;
import adris.altoclef.tasks.construction.schematic.GoBack;
import adris.altoclef.tasks.construction.schematic.NetherPrompt;
import adris.altoclef.tasks.construction.schematic.SchematicDiff;
import adris.altoclef.tasks.construction.schematic.SkipItems;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.squashed.GatherBillTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.process.IBuilderProcess;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.utils.Dimension;
import baritone.api.utils.Helper;
import baritone.process.BuilderProcess;
import baritone.utils.schematic.library.InventoryCounts;
import baritone.utils.schematic.library.MaterialLine;
import baritone.utils.schematic.library.MaterialList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

// "build here" in survival: count what the rest of the build takes, go get it, let the builder place it, and when the
// builder runs dry go get the next lot. the builder itself is the stock one, this only feeds it
public class SchematicBuildTask extends Task {

    // negative so they never meet a builder serial (those count up from 1) or the builder's "nothing" (0). the tab
    // tells our build from a chat #build by this number, see McSchematics.building
    private static final AtomicLong SERIALS = new AtomicLong();
    private static final AtomicLong ASKS = new AtomicLong();
    // cells the diff looks at per tick. a 64^3 build is 8 ticks, nobody waits on that
    private static final int DIFF_BUDGET = 32_768;
    // chat lines list this many items, then "and 4 more"
    private static final int SAY_AT_MOST = 6;
    // how often the builder's progress gets counted while it works: a watch diff starts this many ticks after the last
    // one finished. GatherGuard only believes the diff, and an hour long build shouldn't look stuck for want of a count
    private static final int WATCH_EVERY = 30 * 20;
    // a walk back longer than this gets a chat line, a short one only the debug state
    private static final int SAY_TRIP_OVER = 100;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private enum Phase {
        COUNT,
        GATHER,
        // walk back to the build, then start the builder
        RETURN,
        // the builder has it, scaffold cleanup included: BuilderProcess keeps the ledger of what went up and takes it
        // down once the rest is done
        BUILD
    }

    private final long serial = -SERIALS.incrementAndGet();
    private final String name;
    private final IStaticSchematic schematic;
    private final BlockPos origin;
    private final Rotation rotation;
    private final Mirror mirror;
    // the tab's memory of this build: the unchecked items and the nether answer, saved per world
    private final BuildMemory memory;
    // the items the player unchecked in the tab. the builder gets the file without their cells (SkipItems.leaveOut),
    // so it never walks over to them, and the count doesn't bill them (SkipItems.leftOut)
    private final Set<String> excluded = new LinkedHashSet<>();
    // the file minus the excluded cells, what the builder gets (with the skipped cut on top)
    private IStaticSchematic cut;
    // the whole file laid out where it goes, unchecked cells and all: the count still sees the ones already standing,
    // so the gather's pickaxe stays off half a wall of planks somebody unticked after building it
    private final ISchematic placed;
    private final GatherGuard guard = new GatherGuard();
    private final GoBack goBack = new GoBack();
    // altoSleepAtNight, the gamer's night for a build. it cuts in ahead of every phase
    private final BuildNightSleep night = new BuildNightSleep();
    // items nothing in the catalogue makes and the bag has none of. the builder gets the file with them cut out
    // (SkipItems). one we hold some of stays in, the builder places what there is and the pause says what's short
    private final Set<String> skipped = new LinkedHashSet<>();

    // volatile: the footprint rules below are read by the path search off the game thread
    private volatile Phase phase = Phase.COUNT;
    // set on the game thread every tick, so the footprint rules don't ask the level from the path thread
    private volatile boolean away;
    // cells already right as of the last count, BlockPos.asLong. swapped whole, never changed in place
    private volatile LongOpenHashSet built = new LongOpenHashSet();
    // the dimension the build is in, set on the first start. a gather may take us to the nether, the count and the
    // builder only ever run back here
    private Dimension home;
    private Task goHome;
    // the walk back to the build, null while there isn't one
    private Task trip;
    // we're at the build with its chunks in. cleared whenever we might leave (a gather, an interrupt) and when the
    // builder starts, so every count and builder start asks again. never asked mid build, the builder walks all over
    private boolean near;
    // the "heading back" line went out for this trip. survives an interrupt, a fight halfway home is the same trip
    private boolean toldTrip;
    private SchematicDiff diff;
    private SchematicDiff watch;
    private int watchWait;
    // the builder's missing items that sent us back to counting, null when it wasn't a pause
    private Map<String, Integer> missing;
    // the builder let go without a pause (done, or something took it): the count decides which
    private boolean builderEnded;
    // what the rest of the build takes by item, as of the last count
    private Map<String, Integer> lastLeft = Map.of();
    // the bag when the builder got it, so a night that cuts the builder can tell what it has placed since. not the
    // count's bag, a gather sits between the two
    private Map<String, Integer> haveAtBuild = Map.of();
    private boolean toldUngettable;
    private int batches;
    private GatherBillTask gather;
    // item -> how many the bag should hold once the batch is in
    private Map<String, Integer> batchTargets = Map.of();
    // the serial the builder gave our build, 0 while it has none of ours
    private long builderSerial;
    // our level of the behaviour stack, null while we hold none (always while the builder runs)
    private BotBehaviour.State level;
    private boolean done;
    // the night had the wheel last tick
    private boolean nightHad;
    // the next count comes from a night cutting the builder off, not from the build getting nowhere, so it isn't a round
    private boolean nightRecount;
    // "may I go to the nether for these?", null until somebody answers. one answer covers the whole build, and a stop
    // and start (or a restart of the game) gets the stored one back from the memory
    private Boolean nether;
    // the question waiting for an answer, 0 while there isn't one. counts up over every build this session, so a click
    // on an old question's button never answers a new one (chat doesn't outlive the game, so no need to go further)
    private long asking;
    // the placed items the question was about, what a "no" leaves out
    private Set<String> askedAbout = Set.of();
    public SchematicBuildTask(String name, IStaticSchematic schematic, BlockPos origin, Rotation rotation, Mirror mirror,
                              BuildMemory memory) {
        this.name = name;
        this.schematic = schematic;
        this.origin = origin.immutable();
        this.rotation = rotation;
        this.mirror = mirror;
        this.memory = memory;
        excluded.addAll(memory.excluded());
        nether = memory.nether();
        placed = BuilderProcess.placed(schematic, rotation, mirror);
        recut();
    }

    // on the unturned file: the builder takes that one and turns it itself
    private void recut() {
        cut = SkipItems.leaveOut(schematic, excluded);
    }

    public long serial() {
        return serial;
    }

    // false once it has decided it's done, a tick before the chain gets round to dropping it
    public boolean running() {
        return !done;
    }

    @Override
    protected void onStart(AltoClef mod) {
        if (done) {
            return;
        }
        if (home == null) {
            home = WorldHelper.getCurrentDimension();
            warnAboutSettings();
        }
        // a fight or a meal can drag us anywhere, so the next count or builder start checks where we are first. a half
        // done count goes too, its far cells may have been read from wherever the fight went
        near = false;
        trip = null;
        diff = null;
        goBack.reset();
        // back from an interrupt. onStop let go of the builder, so it gets the build again, fresh, once we're back. the
        // builder still has its scaffold ledger, same build same origin
        if (phase == Phase.BUILD) {
            phase = atHome() ? Phase.RETURN : Phase.COUNT;
        }
        protect(mod);
    }

    // the builder has the wheel, so our behaviour level is off
    private boolean builderOn() {
        return phase == Phase.BUILD;
    }

    // these make the builder call cells done (or build them from something else) that the count still prices, so the
    // count asks for more than the builder will use. it still works, it just fetches too much
    private void warnAboutSettings() {
        Settings s = Baritone.settings();
        if (s.buildOnlySelection.value || !s.okIfAir.value.isEmpty() || s.okIfWater.value || s.buildIgnoreExisting.value
            || !s.buildSubstitutes.value.isEmpty() || !s.buildValidSubstitutes.value.isEmpty()) {
            say("Some builder settings may skip cells; gathering may overshoot");
        }
    }

    private boolean atHome() {
        return WorldHelper.getCurrentDimension() == home;
    }

    private Task goHome() {
        if (goHome == null) {
            goHome = new DefaultGoToDimensionTask(home);
        }
        setDebugState("Going back to the build", "Going back to " + name);
        return goHome;
    }

    // our own level of the behaviour stack while we count and gather, never while the builder runs: the builder has
    // to break and place inside the footprint and pillar with the build's own blocks, and these rules reach its path
    // costs too. the gather's pickaxe stays off the cells that are already right, its scaffolding out of the cells
    // that should stay empty (not the whole box: "build here" starts with you standing in it, and a box you can't
    // dig or pillar out of is a trap), and what the rest of the build needs is a reserve floor: the paths may use what
    // is held above it, the junk dump and the throwaway picks leave the rest alone. whatever the builder burns on
    // scaffolding the next count simply asks for again
    private void protect(AltoClef mod) {
        if (level != null) {
            return;
        }
        level = mod.getBehaviour().push();
        // only at home: in the nether the same coordinates are somebody else's blocks
        mod.getBehaviour().avoidBlockBreaking(pos -> !away && phase != Phase.BUILD && built.contains(pos.asLong()));
        mod.getBehaviour().avoidBlockPlacing(pos -> phase != Phase.BUILD && !away && wantsAir(pos));
        mod.getBehaviour().setReserveFloor(level, floor());
        // a lone bed is first in line for the junk dump, and it is the whole night. nothing in a build ever wants one gone
        mod.getBehaviour().addProtectedItems(ItemHelper.BED);
    }

    // pop only our own level. the child (gather, the trip home) is stopped first so it pops its own, otherwise our pop
    // would take the child's level and the child's late pop would take whatever sits under ours
    private void unprotect(AltoClef mod) {
        if (level == null) {
            return;
        }
        dropChild(mod);
        if (mod.getBehaviour().isTop(level)) {
            mod.getBehaviour().pop();
        } else {
            // can't pop it without taking somebody else's, so at least it stops holding our items back
            mod.getBehaviour().setReserveFloor(level, Map.of());
            Debug.logInternal("SchematicBuildTask: our behaviour level isn't on top, left it alone");
        }
        level = null;
    }

    private Map<Item, Integer> floor() {
        Map<Item, Integer> out = new LinkedHashMap<>();
        lastLeft.forEach((id, n) -> {
            Item item = item(id);
            if (item != Items.AIR && !skipped.contains(id)) {
                out.merge(item, n, Integer::sum);
            }
        });
        return out;
    }

    // a cell of the file that is meant to stay empty. pure schematic lookups, safe from the path thread
    private boolean wantsAir(BlockPos pos) {
        BlockState want = desired(pos);
        return want != null && want.isAir();
    }

    // the whole file's cell, null outside it
    private BlockState desired(BlockPos pos) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        if (x < 0 || y < 0 || z < 0 || x >= placed.widthX() || y >= placed.heightY() || z >= placed.lengthZ()
            || !placed.inSchematic(x, y, z, AIR)) {
            return null;
        }
        return placed.desiredState(x, y, z, AIR, List.of());
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (done) {
            return null;
        }
        away = !atHome();
        // ahead of the guard: a night is no reason to call the build stuck, it ticks the guard not at all
        Task bed = night.tick(mod, away);
        BuildNightSleep.Turn turn = BuildNightSleep.turn(bed != null || night.holding(), builderOn());
        if (turn != BuildNightSleep.Turn.BUILD) {
            return nightTurn(mod, turn) ? bed : null;
        }
        nightHad = false;
        // the nether question holds everything until it's answered, however long that takes. after the night so a
        // night still gets slept, before the guard so waiting on a person is never "stuck"
        if (asking != 0) {
            protect(mod);
            if (away) {
                // a respawn or a fight's portal took us off while asking: wait at home, not wherever that was
                return goHome();
            }
            setDebugState("Waiting for your answer: nether?", "Waiting for your answer: nether?");
            return null;
        }
        if (phase == Phase.GATHER) {
            // the bill fetches every raw material before it crafts any of the batch, so its own progress counts too
            guard.gathering(towardBatch(have(mod)) + (gather == null ? 0 : gather.progress(mod)));
        }
        if (guard.tick()) {
            finish(mod, "No progress on " + name + " for " + GatherGuard.MAX_IDLE_TICKS / 1200 + " minutes, stopped");
            return null;
        }
        if (builderOn() && away) {
            // a respawn somewhere else. the builder would build the same coordinates in the wrong world
            stopBuilder(mod);
            phase = Phase.COUNT;
        }
        if (!builderOn()) {
            protect(mod);
        }
        return switch (phase) {
            case COUNT -> away ? goHome() : count(mod);
            case GATHER -> gather(mod);
            case RETURN -> away ? goHome() : returnToBuild(mod);
            case BUILD -> build(mod);
        };
    }

    // the night has the wheel. the builder can't keep it (its pathing would fight the trip's), so a build in progress goes back
    // to counting: the footprint rules come back on for the night and the morning walks back and recounts, same as after
    // a gather. false when it turns out the build is over (a chat #build took the builder)
    private boolean nightTurn(AltoClef mod, BuildNightSleep.Turn turn) {
        if (!nightHad) {
            nightHad = true;
            // a sheep, a bed, a pillar: the night can walk us anywhere, so the morning checks where we are first (same as
            // onStart after an interrupt)
            near = false;
            trip = null;
            diff = null;
            goBack.reset();
        }
        if (turn == BuildNightSleep.Turn.CUT_BUILDER && !cutBuilder(mod)) {
            return false;
        }
        protect(mod);
        setDebugState("The night has the wheel", "Night, " + name + " waits");
        return true;
    }

    // what build() asks the builder, asked once more before the night takes it away: a chat #build that took over is
    // still theirs to keep, and a builder that just let go still gets the count's "is it done"
    private boolean cutBuilder(AltoClef mod) {
        long now = builder(mod).buildSerial();
        if (now != 0 && now != builderSerial) {
            builderSerial = 0;
            finish(mod, "Another build took over, stopped " + name);
            return false;
        }
        if (now == 0 && finished(mod, builderSerial)) {
            // it finished the same tick the night came in, cleanup and all. the morning's count gets to call it
            builderEnded = true;
        }
        stopBuilder(mod);
        phase = Phase.COUNT;
        nightRecount = true;
        return true;
    }

    private long towardBatch(Map<String, Integer> have) {
        long sum = 0;
        for (Map.Entry<String, Integer> e : batchTargets.entrySet()) {
            sum += Math.min(have.getOrDefault(e.getKey(), 0), e.getValue());
        }
        return sum;
    }

    // what the count left that we can't get, priced, and what the player unchecked, for the last line
    private String skippedLine() {
        Map<String, Integer> out = new LinkedHashMap<>();
        lastLeft.forEach((id, n) -> {
            if (skipped.contains(id)) {
                out.put(id, n);
            }
        });
        // no counts for these, their cells were never in the count to begin with
        String leftOut = excluded.isEmpty() ? null : "left out: " + names(excluded);
        if (out.isEmpty()) {
            return leftOut == null ? null : "Built " + name + "; " + leftOut;
        }
        return "Built " + name + " except " + list(out) + (leftOut == null ? "" : "; " + leftOut);
    }

    private SchematicDiff newDiff(boolean watching) {
        Predicate<Block> skip = block -> Baritone.settings().buildSkipBlocks.value.contains(block);
        // a copy: a "no" grows the set between two counts, never during one
        Set<String> out = Set.copyOf(excluded);
        Predicate<BlockState> leftOut = state -> SkipItems.leftOut(out, state);
        return watching ? SchematicDiff.watching(placed, origin.getX(), origin.getY(), origin.getZ(), skip, leftOut)
            : new SchematicDiff(placed, origin.getX(), origin.getY(), origin.getZ(), skip, leftOut);
    }

    private Task count(AltoClef mod) {
        setDebugState("Counting what is left", "Counting what " + name + " still needs");
        ClientLevel world = mod.getWorld();
        if (world == null) {
            return null;
        }
        // a count from out here sees the build's chunks unloaded, calls them air and bills the whole thing again
        if (diff == null && !near) {
            return reach(mod);
        }
        if (diff == null) {
            diff = newDiff(false);
        }
        if (!diff.step(world(world), DIFF_BUDGET)) {
            return null;
        }
        Map<String, Integer> left = byItem(diff.result().items());
        long correct = diff.correct();
        long wrong = diff.wrong();
        built = diff.built();
        diff = null;
        // ahead of everything that eats the count's flags (ended, missing, the guard's round): the count after the
        // answer is the one that gets to use them
        if (askNether(GatherPlan.need(left, missing), have(mod))) {
            return null;
        }
        boolean ended = builderEnded;
        builderEnded = false;
        Map<String, Integer> have = have(mod);
        GatherPlan.Plan plan = GatherPlan.plan(GatherPlan.need(left, missing), have, SchematicBuildTask::gettable,
            slots(mod), SchematicBuildTask::stackSize);
        plan.ungettable().keySet().forEach(id -> {
            if (have.getOrDefault(id, 0) == 0) {
                skipped.add(id);
            }
        });
        lastLeft = left;
        // the whole rest of the build, not just this batch: the stone for round three burns as well as round one's
        mod.getBehaviour().setReserveFloor(level, floor());
        if (!toldUngettable && !plan.ungettable().isEmpty()) {
            toldUngettable = true;
            say("Can't get " + list(plan.ungettable()) + " for " + name + ", building around them");
        }
        Map<String, Integer> rest = new LinkedHashMap<>(left);
        rest.keySet().removeAll(skipped);
        // every count is one round for the guard, whatever sent us here (a pause, the builder letting go, the start). except
        // the night's: a builder cut off every evening isn't stuck, and the tick clock still catches one that really is
        boolean stuck;
        if (nightRecount) {
            nightRecount = false;
            guard.seen(correct);
            stuck = false;
        } else {
            stuck = guard.round(correct);
        }
        switch (CountStep.next(ended, rest.isEmpty(), stuck, plan.bagFull(), plan.nothingToGet(), missing != null)) {
            case DONE -> {
                memory.finished();
                finish(mod, skippedLine());
            }
            case DONE_BUT_DIFFERS -> {
                memory.finished();
                finish(mod, "The builder says " + name + " is done; " + wrong + " cells differ (likely builder settings)");
            }
            case STUCK -> finish(mod, "No progress on " + name + " after " + GatherGuard.MAX_IDLE_ROUNDS
                + " rounds, stopped. Still missing " + list(rest));
            case BAG_FULL -> finish(mod, "Inventory is full, make some room and build " + name + " again");
            // the builder said it's out of these, but the bag has them. that's not a gathering problem
            case CANT_PLACE -> finish(mod, "The builder can't place " + list(missing) + " even with them in the inventory, stopped " + name);
            case BUILD -> startBuilder(mod);
            case GATHER -> {
                return startGather(plan, have);
            }
        }
        return null;
    }

    // true when this count has to go: the build now waits on the nether question, or a "no" just cut more cells. it's
    // about the shortfall, everything the rest of the build still has to fetch, not plan.batch(): that one is capped
    // and in order, and the blaze rods could sit two batches down. an item the bag already has enough of is nobody's
    // trip, and one we can't get at all is skipped anyway
    private boolean askNether(Map<String, Integer> left, Map<String, Integer> have) {
        if (Boolean.TRUE.equals(nether)) {
            return false;
        }
        Set<String> found = NetherPrompt.shortfall(left, have, id -> !skipped.contains(id) && !excluded.contains(id)
            && gettable(id) && TaskCatalogue.needsNether(item(id)));
        if (found.isEmpty()) {
            return false;
        }
        if (Boolean.FALSE.equals(nether)) {
            // already said no for this build. something new turned up short (the bag had it last time), and a no is a
            // no: it goes out like the rest instead of sneaking off to the nether or asking twice
            leaveOut(found);
            return true;
        }
        asking = ASKS.incrementAndGet();
        askedAbout = Set.copyOf(found);
        NetherPrompt.ask(asking, names(found));
        return true;
    }

    // the placed items (the brewing stand, not its blaze rod), so the tab shows the same rows unchecked
    private void leaveOut(Set<String> items) {
        excluded.addAll(items);
        recut();
        // a pause for the brewing stand would put it right back on the gather's list (GatherPlan.need)
        if (missing != null) {
            Map<String, Integer> rest = new LinkedHashMap<>(missing);
            rest.keySet().removeAll(items);
            missing = rest.isEmpty() ? null : rest;
        }
        memory.netherAnswered(false, items);
        say("Building " + name + " without " + names(items) + ". Check them again in the schematics tab to build them next time");
        // the count that found them is gone, the next tick counts again without their cells
        diff = null;
    }

    // a question is out and nobody has answered it
    public boolean askingNether() {
        return asking != 0;
    }

    // the chat buttons and #schem nether. id 0 = whatever is being asked now (a typed answer). false when nothing is
    // being asked or the id is an older question's, that click is stale and does nothing
    public boolean answerNether(boolean yes, long id) {
        if (asking == 0 || (id != 0 && id != asking)) {
            return false;
        }
        asking = 0;
        nether = yes;
        if (yes) {
            memory.netherAnswered(true, Set.of());
            say("Okay, I'll go to the nether for them when it's their turn");
        } else {
            leaveOut(askedAbout);
        }
        askedAbout = Set.of();
        // the count that asked is gone, the next tick counts again
        diff = null;
        return true;
    }

    private Task startGather(GatherPlan.Plan plan, Map<String, Integer> have) {
        batches++;
        List<ItemTarget> targets = new ArrayList<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        plan.batch().forEach((id, more) -> {
            int total = have.getOrDefault(id, 0) + more;
            totals.put(id, total);
            targets.add(new ItemTarget(item(id), total));
        });
        batchTargets = totals;
        guard.newBatch();
        near = false;
        toldTrip = false;
        // the batch is what GatherPlan says, the bill only decides how to fetch it: all the raw stuff in one trip, then
        // the crafting, instead of every recipe level wandering off for its own iron
        gather = new GatherBillTask(targets.toArray(new ItemTarget[0]));
        say("Gathering " + list(plan.batch()) + " for " + name + " (batch " + batches + ")");
        phase = Phase.GATHER;
        return gather;
    }

    private Task gather(AltoClef mod) {
        if (!gather.isFinished(mod)) {
            setDebugState("Gathering batch " + batches, "Gathering for " + name);
            return gather;
        }
        if (!atHome()) {
            return goHome();
        }
        gather = null;
        // the gather ended wherever the last tree was, and the builder only heads over once it holds what it wants
        phase = Phase.RETURN;
        return returnToBuild(mod);
    }

    private Task returnToBuild(AltoClef mod) {
        Task go = reach(mod);
        if (near) {
            startBuilder(mod);
        }
        return go;
    }

    // walks us back to the build and waits for its chunks. near goes true once we're there, until then this hands back
    // the walk (or null while the chunks come in)
    private Task reach(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        double dist = GoBack.distance(player.getX(), player.getZ(), origin.getX(), origin.getZ(), maxX(), maxZ());
        int view = viewChunks();
        int range = GoBack.range(view);
        // only asked once we're in range, a walk doesn't care about chunks
        boolean loaded = dist <= range && footprintLoaded(mod, view);
        switch (goBack.step(dist, range, loaded, trip != null && trip.isFinished(mod))) {
            case TRAVEL -> {
                if (goBack.gotCloser()) {
                    guard.closer();
                }
                if (trip == null) {
                    diff = null;
                    // the nearest column of the footprint, any height. we call it there at half the range, well before
                    // the walk would ever stand on that exact column
                    BlockPos at = nearest(player);
                    trip = new GetToXZTask(at.getX(), at.getZ());
                    // a trip that gets interrupted and picked up again is still the same trip, one line is plenty
                    if (dist > SAY_TRIP_OVER && !toldTrip) {
                        toldTrip = true;
                        say("Heading back to " + name + " at " + HudText.pos(origin));
                    }
                }
                setDebugState("Going back to the build", "Going back to " + name);
                return trip;
            }
            case WAIT -> {
                trip = null;
                setDebugState("Waiting for the build's chunks", "Waiting for " + name + " to load");
                return null;
            }
            case THERE -> {
                trip = null;
                toldTrip = false;
                near = true;
                return null;
            }
            case BAIL -> {
                trip = null;
                toldTrip = false;
                finish(mod, "Can't get back to " + name + " at " + HudText.pos(origin) + ", stopped");
                return null;
            }
        }
        return null;
    }

    private int maxX() {
        return origin.getX() + placed.widthX() - 1;
    }

    private int maxZ() {
        return origin.getZ() + placed.lengthZ() - 1;
    }

    // the footprint column closest to the player, the walk's target. y is the player's, nobody reads it
    private BlockPos nearest(LocalPlayer player) {
        BlockPos at = player.blockPosition();
        return new BlockPos(Math.clamp(at.getX(), origin.getX(), maxX()), at.getY(),
            Math.clamp(at.getZ(), origin.getZ(), maxZ()));
    }

    // every chunk of the footprint the server should have sent by now. one short of the view distance, the edge ring
    // shows up late or not at all. a build wider than the view distance only gets its near part checked, the far
    // part is the diff's problem (it calls unloaded cells air)
    private boolean footprintLoaded(AltoClef mod, int view) {
        ClientLevel world = mod.getWorld();
        if (world == null) {
            return false;
        }
        int reach = Math.max(0, view - 1);
        int px = mod.getPlayer().blockPosition().getX() >> 4;
        int pz = mod.getPlayer().blockPosition().getZ() >> 4;
        int x0 = Math.max(origin.getX() >> 4, px - reach);
        int x1 = Math.min(maxX() >> 4, px + reach);
        int z0 = Math.max(origin.getZ() >> 4, pz - reach);
        int z1 = Math.min(maxZ() >> 4, pz + reach);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                // not world.hasChunk, ClientLevel's is a flat 'return true'
                if (!world.getChunkSource().hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    // same number the move camera's leash reads (MoveSession.leash): the server's view distance can be under the option
    private static int viewChunks() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance();
    }

    private Task build(AltoClef mod) {
        IBuilderProcess builder = builder(mod);
        long now = builder.buildSerial();
        if (now == 0) {
            // the builder let go without pausing. done ("Done building", its scaffold cleanup and all), or knocked off
            // (DestroyBlockTask lets go of the builder whenever it swings, so does a #stop). only done counts as ended:
            // knocked off with only scaffold left, the count would see nothing to build and call it done with every
            // pillar still up. so that's a plain round, and the count hands the builder the build again
            builderEnded = finished(mod, builderSerial);
            builderSerial = 0;
            watch = null;
            missing = null;
            phase = Phase.COUNT;
            return null;
        }
        if (now != builderSerial) {
            // a #build from chat took the builder. it's theirs now, hands off
            builderSerial = 0;
            finish(mod, "Another build took over, stopped " + name);
            return null;
        }
        if (builder.isPaused()) {
            onPause(mod, builder);
            return null;
        }
        setDebugState("Building", "Building " + name);
        watch(mod);
        return null;
    }

    // a quiet count while the builder works, only so the guard sees the build getting further
    private void watch(AltoClef mod) {
        ClientLevel world = mod.getWorld();
        if (world == null) {
            return;
        }
        // the wait only starts over when a watch gets to the end. a builder restarted every few seconds drops the watch
        // half way, and a wait that started over each time would never let one finish
        if (watch == null) {
            if (++watchWait < WATCH_EVERY) {
                return;
            }
            watch = newDiff(true);
        }
        if (watch.step(world(world), DIFF_BUDGET)) {
            guard.seen(watch.correct());
            watchWait = 0;
            watch = null;
        }
    }

    private void onPause(AltoClef mod, IBuilderProcess builder) {
        Map<String, Integer> lacking = SchematicDiff.priced(builder.missingAtPause());
        if (lacking.isEmpty()) {
            finish(mod, "The builder stopped on " + name + " and it isn't for materials, it can't reach or place what's left");
            return;
        }
        if (lacking.keySet().stream().noneMatch(SchematicBuildTask::gettable)) {
            // SkipItems cuts these out of what the builder gets, so this is one we hold a few of and ran out
            finish(mod, "Built what I can get for " + name + ", still missing " + list(lacking));
            return;
        }
        // a paused builder still holds on to its process slot, and the gather's own pathing would win it off the
        // builder and wipe it anyway. so it goes now on purpose and comes back fresh once the next batch is in. the
        // count that follows is the guard's round
        stopBuilder(mod);
        missing = lacking;
        phase = Phase.COUNT;
    }

    private void startBuilder(AltoClef mod) {
        // the builder gets the whole bag, the reserve floor and the footprint rules go first
        unprotect(mod);
        IBuilderProcess builder = builder(mod);
        if (builder instanceof BuilderProcess process) {
            // a meal or a night in between doesn't make the cells it was stuck on any less stuck
            process.keepGiveUpsOnce();
        }
        builder.build(name, SkipItems.of(cut, skipped), origin, rotation, mirror);
        builderSerial = builder.buildSerial();
        haveAtBuild = have(mod);
        watch = null;
        near = false;
        toldTrip = false;
        // whatever the pause or the gather was about, it's in the past now
        missing = null;
        phase = Phase.BUILD;
    }

    // whether the builder got our build all the way to "Done building". a builder that isn't ours to ask (never, in
    // the game) gets the old answer: let go means done
    private static boolean finished(AltoClef mod, long serial) {
        return !(builder(mod) instanceof BuilderProcess process) || process.finished(serial);
    }

    // only ever our own build, a chat #build that took over is left alone
    private void stopBuilder(AltoClef mod) {
        watch = null;
        if (builderSerial != 0) {
            // the floor is the last count's bill, and the builder has been placing since. left as is it holds back every
            // block of the bag, and a night (or a fight at dusk, then the night) finds nothing spare for its pillar. what
            // went on scaffolding comes off too, so it's a low guess: the next count bills whatever is really missing
            if (mod.getPlayer() != null) {
                lastLeft = BuildNightSleep.leftAfterUse(lastLeft, haveAtBuild, have(mod));
            }
            IBuilderProcess builder = builder(mod);
            if (builder.buildSerial() == builderSerial) {
                builder.onLostControl();
            }
            builderSerial = 0;
        }
    }

    // the chain drops a finished task without calling onStop, so everything onStop would undo happens here
    private void finish(AltoClef mod, String why) {
        if (why != null) {
            say(why);
        }
        stopBuilder(mod);
        unprotect(mod);
        night.drop();
        night.untrack(mod);
        done = true;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // stop, cancel, another task or an interrupt: the builder goes too. a builder left running under a fight
        // would fight the fight for the wheel. onStart puts it back after an interrupt
        stopBuilder(mod);
        unprotect(mod);
        // an interrupt keeps tonight's trip and its stand down, it picks up where it was when we're back (a meal on the
        // pillar is no reason to fight our way off it). a real stop lets go of both
        if (!isInterrupting()) {
            night.drop();
            night.untrack(mod);
        }
    }

    // #stop while a fight or a meal had us paused: onStop already ran as an interrupt and kept the night
    @Override
    protected void onStopWhilePaused(AltoClef mod) {
        night.drop();
        night.untrack(mod);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return done;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SchematicBuildTask task && task.serial == serial;
    }

    @Override
    protected String toHudString() {
        if (nightHad) {
            return "Night, " + name + " waits";
        }
        if (asking != 0) {
            return "Waiting for your answer: nether?";
        }
        return switch (phase) {
            case COUNT -> "Counting what " + name + " needs";
            case GATHER -> "Gathering for " + name;
            case RETURN -> "Going back to " + name;
            case BUILD -> "Building " + name;
        };
    }

    @Override
    protected String toDebugString() {
        return "Schematic build " + name + " at " + origin.toShortString();
    }

    // ----- the game side

    private static IBuilderProcess builder(AltoClef mod) {
        return mod.getClientBaritone().getBuilderProcess();
    }

    private static SchematicDiff.World world(ClientLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return new SchematicDiff.World() {
            @Override
            public boolean loaded(int chunkX, int chunkZ) {
                // not level.hasChunk, ClientLevel's is a flat 'return true'
                return level.getChunkSource().hasChunk(chunkX, chunkZ);
            }

            @Override
            public BlockState state(int x, int y, int z) {
                return level.getBlockState(pos.set(x, y, z));
            }
        };
    }

    private static Map<String, Integer> have(AltoClef mod) {
        return InventoryCounts.of(mod.getPlayer().getInventory());
    }

    private static List<GatherPlan.Slot> slots(AltoClef mod) {
        List<GatherPlan.Slot> out = new ArrayList<>(36);
        List<ItemStack> main = mod.getPlayer().getInventory().getNonEquipmentItems();
        for (int i = 0; i < Math.min(36, main.size()); i++) {
            ItemStack stack = main.get(i);
            out.add(stack.isEmpty() ? GatherPlan.Slot.empty()
                : new GatherPlan.Slot(MaterialList.itemId(stack.getItem()), stack.getCount(), stack.getMaxStackSize()));
        }
        return out;
    }

    private static Map<String, Integer> byItem(List<MaterialLine> lines) {
        Map<String, Integer> out = new LinkedHashMap<>();
        lines.forEach(line -> out.merge(line.item(), line.count(), Integer::sum));
        return out;
    }

    private static Item item(String id) {
        Identifier key = Identifier.tryParse(id);
        return key == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(key);
    }

    // taskExists too, not just isObtainable: an item only reachable through a group entry (no single item task)
    // would hand CataloguedResourceTask a null
    static boolean gettable(String id) {
        Item item = item(id);
        return item != Items.AIR && TaskCatalogue.isObtainable(item) && TaskCatalogue.taskExists(item);
    }

    private static int stackSize(String id) {
        Item item = item(id);
        return item == Items.AIR ? 64 : item.getDefaultInstance().getMaxStackSize();
    }

    private static String list(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (shown++ == SAY_AT_MOST) {
                parts.add((counts.size() - SAY_AT_MOST) + " more");
                break;
            }
            parts.add(HudText.count(e.getValue(), HudText.item(item(e.getKey()))));
        }
        return HudText.list(parts);
    }

    // list() without the numbers
    private static String names(Set<String> ids) {
        List<String> parts = new ArrayList<>();
        int shown = 0;
        for (String id : ids) {
            if (shown++ == SAY_AT_MOST) {
                parts.add((ids.size() - SAY_AT_MOST) + " more");
                break;
            }
            parts.add(HudText.item(item(id)));
        }
        return HudText.list(parts);
    }

    private static void say(String message) {
        Helper.HELPER.logDirect(message);
    }
}
