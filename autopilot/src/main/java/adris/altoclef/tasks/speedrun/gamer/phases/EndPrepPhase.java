package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.chains.MLGBucketFallChain;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.resources.CollectBedTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.resources.CollectWoolTask;
import adris.altoclef.tasks.resources.CraftBedTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasks.speedrun.gamer.FoodDry;
import adris.altoclef.tasks.speedrun.gamer.FoodPlan;
import adris.altoclef.tasks.speedrun.gamer.FoodTripRules;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.KitPlanner;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.Respawn;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.end.EndGear;
import adris.altoclef.tasks.speedrun.gamer.tasks.PortalDig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BlockTracker;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WaterUpkeep;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.ArrayUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

// overworld, portal open. the beds (EndGear.mayEnter is the law, the search has no give up), a spawn bed by the portal, the gear
// gate (EndGear says what is missing, counting what we left in the End), THEN walk into the portal. done = we are in the End: the
// gear being fine is not done, walking in is the last step of this phase
public class EndPrepPhase implements PhaseHandler {
    private final Map<String, Task> _catalogue = new HashMap<>();
    private final Map<Item, Task> _equip = new HashMap<>();
    // built on first use, not here: the pure parts (isDone) get tested with no game and no task machinery
    private PlaceBedAndSetSpawnTask _setSpawn;
    // the spawn bed task finished and noteSpawnSet has read it, once per visit (the task stays finished)
    private boolean _spawnNoted;
    private Task _enterPortal;
    private Task _buildBlocks;
    private Task _food;
    private boolean _topUpSaid;
    private int _foodTarget;
    private Task _toPortal;
    private BlockPos _toPortalAt;
    // when the spawn bed step began (game time in seconds, holdsBudget stands the phase clock still), -1 = not yet: its give-up clock
    // runs from there (EndGear.spawnClockExpired)
    private double _spawnStartedAt = -1;
    // how far the errand this tick is from the portal, for the food-first rule
    private double _errandBlocks;
    // only the walk in is left (the End has no night), and a long walk back to the portal is not the phase budget's business
    private boolean _walkingIn;
    private boolean _farWalk;
    // short of the bed target: the budget and the stall clock both stay out of it, this search ends when the beds are there
    private boolean _shortOnBeds;
    private long _shortSince = -1;
    private long _shortSaid = -1;
    // retries spent while short on beds, free: they must not eat the attempts the gear steps get afterwards (same idea as RoomPhase's moving ones)
    private int _shortRetries;
    // the bed we are walking to. one at a time, see bedTarget
    private BlockPos _bedTarget;
    private boolean _gateOpenSaid;
    // the spawn step was let through once (EndGear.spawnNow): it runs to its end, placing the bed changes what the check sees
    private boolean _spawnGo;
    // the bed mine task that was running, kept through the pickup of the bed it just broke (the lookup is empty by then)
    private Task _bedMine;
    private int _bedMineTail;
    private String _hudState;
    // the gap the last gate line named, so a line goes out only when it changes
    private String _lastGate;
    // the water bucket errand, per attempt: ticks the step ran, the pond it is on, and whether it gave up (see EndGear.wantsWater).
    // onEnter resets it, and so does a new death (the overworld death does not re-enter the phase)
    private int _bucketTicks;
    private boolean _bucketGaveUp;
    private int _bucketDeaths;
    private BlockPos _bucketPond;
    private Task _bucketTask;
    // the armor floor (EndGear.armorFloorPiece), per visit like the bucket: ticks the step ran, and whether the clock ran out
    private int _armorTicks;
    private boolean _armorGaveUp;
    private Task _armorTask;
    // only RoomPhase's instance sets this (prepAhead), see portalCenter
    private BlockPos _prepAt;
    // the spare bed's waiver has said its line, until it stops applying
    private boolean _spareSaid;
    // set while the spare is all that's missing: the pick has to stand within reach of this. null = any distance
    private BlockPos _spareAt;
    private PortalDig _portalDig = new PortalDig();

    @Override
    public GamerPhase phase() {
        return GamerPhase.END_PREP;
    }

    @Override
    public String hud() {
        return GamerPhase.END_PREP.hud();
    }

    @Override
    public String hudState() {
        return _hudState;
    }

    // the engine only advances on the dimension: the walk into the portal is a step of this phase, so gear alone is not "done"
    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return facts.dimension() == Dimension.END || state.dragonDead || facts.creditsShown();
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        ensureTasks();
        _lastGate = null;
        // the lambdas in these capture the portal and the config of the last visit, and a finished task stays finished
        _catalogue.clear();
        _equip.clear();
        _food = null;
        _buildBlocks = null;
        _toPortal = null;
        _toPortalAt = null;
        // the task keeps its "spawn is set" flag from the last visit, a death in the End means it may be gone
        _setSpawn.resetSleep();
        _spawnNoted = false;
        _walkingIn = false;
        _farWalk = false;
        _hudState = null;
        _gateOpenSaid = false;
        if (ctx.attempt() == 1) {
            _shortRetries = 0;
        }
        resetOnDeath(ctx.state().deaths.size());
        _portalDig.walkStalled();
    }

    // everything a visit remembers about its errands: onEnter, and a death in the overworld (it does not re-enter the phase)
    public void resetOnDeath(int deaths) {
        // onEnter comes through here too, and a phase restart must not throw away a dig that's going fine: only a new death counts
        if (deaths != _bucketDeaths) {
            _portalDig = new PortalDig();
        }
        _spawnStartedAt = -1;
        _spawnGo = false;
        _bedTarget = null;
        _bedMine = null;
        _bedMineTail = 0;
        _armorTicks = 0;
        _armorGaveUp = false;
        _armorTask = null;
        resetBucket(deaths);
    }

    // test hooks
    public double spawnStartedAt() {
        return _spawnStartedAt;
    }

    public int bucketDeaths() {
        return _bucketDeaths;
    }

    // END_PORTAL is in the engine's tracked set (GamerTask.TRACKED), a handler level trackBlock leaked its ref count when
    // the task was stopped while interrupted (onExit never ran)
    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
        _walkingIn = false;
        _farWalk = false;
        ctx.walkOnEndPortal(false);
    }

    // once the gate is clear all that is left is the walk in, and the End has no night: sleeping first would only cost time
    @Override
    public boolean nightHold() {
        return _walkingIn;
    }

    // the walk back to the portal from far away is not what the budget is for (same as OpenPhase's walk to the ring)
    @Override
    public boolean holdsBudget() {
        return _farWalk || _shortOnBeds;
    }

    // holdsBudget leaves the stall clock running, and a long empty walk looking for sheep is exactly what that clock hates
    @Override
    public double stallSeconds() {
        return _shortOnBeds ? 0 : PhaseHandler.super.stallSeconds();
    }

    // the clocks can still catch us short (a gap where another chain has the wheel only gets 5 s of budget back). one more go, however
    // many it takes: stuck or skipped is not how the bed search ends
    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        if (bedsToGet(ctx, ctx.cfg().end) > 0) {
            _shortRetries++;
            return Timeout.RETRY;
        }
        return PhaseHandler.super.onTimeout(ctx, Math.max(1, attempt - _shortRetries), reason);
    }

    public boolean shortOnBeds() {
        return _shortOnBeds;
    }

    private void ensureTasks() {
        if (_setSpawn == null) {
            _setSpawn = new PlaceBedAndSetSpawnTask();
            _enterPortal = new DoToClosestBlockTask(blockPos -> new GetToBlockTask(blockPos.above()), Blocks.END_PORTAL);
        }
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        ensureTasks();
        if (ctx.facts().dimension() != Dimension.OVERWORLD) {
            _hudState = null;
            _walkingIn = false;
            _farWalk = false;
            _shortOnBeds = false;
            ctx.walkOnEndPortal(false);
            return null;
        }
        EndConfig cfg = ctx.cfg().end;
        RunState state = ctx.state();
        // first: the spawn clock of the life before the death must not run out on the new one
        if (state.deaths.size() != _bucketDeaths) {
            resetOnDeath(state.deaths.size());
        }
        noteSpawnSet(mod, ctx);
        giveUpSpawnWhenSlow(ctx, cfg);

        EndGear.Gap gap = EndGear.missing(ctx.facts(), state, cfg, ctx.food(), _bucketGaveUp);
        Item armorFloor = armorFloor(ctx, cfg);
        noteGate(mod, ctx, gap, armorFloor);
        noteErrandBlocks(mod, state);
        noteShort(mod, ctx, bedsToGet(ctx, cfg));
        Task step = bedStep(mod, ctx, cfg);
        if (step == null) {
            step = gearStep(mod, ctx, cfg, gap, armorFloor);
        }
        // the last word before the portal, and it reads the bag: "nothing left to do" is never permission to go in short. the spawn
        // step giving up this very tick lands here, and so would anything we didn't think of
        int bag = ctx.facts().bedsInBag();
        if (step == null && !EndGear.mayEnter(bag, cfg)) {
            step = bedErrand(mod, ctx, cfg.beds - bag);
        }
        step = foodFirstThenCount(ctx, cfg, gap, step);
        noteGateOpen(mod, step == null, bag);
        _walkingIn = step == null;
        _farWalk = (step == null || step == _toPortal) && _errandBlocks > cfg.farErrandBlocks;
        // one write per tick (the path thread reads this flag): true only when everything else is done and we walk in
        ctx.walkOnEndPortal(step == null);
        return step != null ? step : walkIn(mod, ctx);
    }

    // the gate, for RoomPhase's own instance before the dig: at stands in for the portal while no ring is known. the spawn bed is
    // roomBedStep's to place (it only spends a spare, so we buy it one), no walk in (there's no portal yet). gearToo = false once the
    // gear's cap ran out, the beds don't have one. null = nothing left
    public Task prepAhead(AltoClef mod, GamerContext ctx, BlockPos at, boolean gearToo) {
        _prepAt = at;
        ensureTasks();
        if (ctx.facts().dimension() != Dimension.OVERWORLD) {
            _shortOnBeds = false;
            return null;
        }
        EndConfig cfg = ctx.cfg().end;
        RunState state = ctx.state();
        if (state.deaths.size() != _bucketDeaths) {
            resetOnDeath(state.deaths.size());
        }
        EndGear.Gap gap = EndGear.missing(ctx.facts(), state, cfg, ctx.food(), _bucketGaveUp);
        Item armorFloor = armorFloor(ctx, cfg);
        noteGate(mod, ctx, gap, armorFloor);
        noteErrandBlocks(mod, state);
        int lacking = EndGear.bedShortfall(ctx.facts(), cfg.beds, EndGear.roomBedOwed(state, cfg));
        // ahead of the errand and the clocks, or going without would still look like a search that's short
        boolean spareOnly = lacking > 0 && ctx.facts().bedsInBag() >= cfg.beds;
        boolean waived = spareOnly && spareWaived(mod, ctx, cfg, at);
        if (waived) {
            lacking = 0;
        }
        _spareAt = spareOnly && !waived ? at : null;
        noteShort(mod, ctx, lacking);
        Task step = lacking > 0 ? bedErrand(mod, ctx, lacking) : waived ? spareOnFloor(mod) : null;
        if (step == null && gearToo) {
            step = gearStep(mod, ctx, cfg, gap, armorFloor);
        }
        if (topUpsNow(step, gearToo)) {
            step = topUpStep(ctx, cfg);
        }
        return foodFirstThenCount(ctx, cfg, gap, step);
    }

    // last in line: the beds and the gear went first, and gearToo going false (the cap) ends this along with the gear
    static boolean topUpsNow(Task picked, boolean gearToo) {
        return picked == null && gearToo;
    }

    // over the End's floor but not by enough for the fight. gap.food stays the hard line on purpose: foodFirstThenCount reads it, and
    // a soft line in there would send a bot that's short on beds off hunting instead
    private Task topUpStep(GamerContext ctx, EndConfig cfg) {
        FoodPlan food = ctx.food();
        GamerFacts f = ctx.facts();
        if (!EndGear.wantsTopUp(food.shortOfEnd(cfg.wantFoodUnits), f.bagFull(), FoodDry.defers(f.foodDryLatched(), f.preyNear()))) {
            return null;
        }
        if (!_topUpSaid) {
            _topUpSaid = true;
            ctx.log("room: topping food up from " + (food.held() - food.pending()) + ", want " + cfg.wantFoodUnits);
        }
        return foodStep(ctx, cfg.wantFoodUnits);
    }

    private boolean spareWaived(AltoClef mod, GamerContext ctx, EndConfig cfg, BlockPos at) {
        GamerFacts f = ctx.facts();
        // an actual empty slot, not f.bagFull(): that one calls a bag with junk in it roomy, and this isn't worth throwing things for
        boolean noFreeSlot = StorageHelper.freeBagSlots(mod) == 0;
        // from the start and not from us: that's where the room bed goes, and the bot may be off on an errand
        boolean near = mod.getBlockTracker().getNearestTracking(Vec3.atCenterOf(at),
                p -> EndGear.spareBedInReach(at.getX(), at.getZ(), p.getX(), p.getZ()) && bedAllowed(mod, p), CollectBedTask.BEDS).isPresent();
        boolean waived = EndGear.spareBedWaived(EndGear.roomBedOwed(ctx.state(), cfg), f.bedsInBag(), cfg.beds, KitPlanner.usableWool(f) / 3, near, noFreeSlot);
        if (waived && !_spareSaid) {
            ctx.log("room: going without the spare bed, " + (near ? "no room for it in the bag" : "none within " + EndGear.SPARE_BED_REACH + " of the start"));
        }
        _spareSaid = waived;
        return waived;
    }

    // the spare we just broke can be the last one close by, and it's still on the floor. only that though: the miner also reads as busy
    // on its way to a bed that's still standing, and going without means stop walking
    private Task spareOnFloor(AltoClef mod) {
        boolean broken = _bedTarget != null && mod.getWorld().hasChunkAt(_bedTarget)
                && !(mod.getWorld().getBlockState(_bedTarget).getBlock() instanceof BedBlock);
        return broken ? bedMineTail(mod) : null;
    }

    private void noteErrandBlocks(AltoClef mod, RunState state) {
        BlockPos portal = portalCenter(mod, state);
        _errandBlocks = portal == null ? 0 : Math.hypot(mod.getPlayer().getX() - portal.getX(), mod.getPlayer().getZ() - portal.getZ());
    }

    private Task foodFirstThenCount(GamerContext ctx, EndConfig cfg, EndGear.Gap gap, Task picked) {
        Task step = picked;
        // a far errand eats the food on the way, so the food floor comes first (the gear steps go on the same rule: far from the
        // portal is far, whatever the errand). the walk back and the spawn bed are the way home, food does not replace them
        if (step != null && step != _food
                && EndGear.foodFirst(gap.food(), _errandBlocks, cfg.farErrandBlocks, step == _toPortal || step == _setSpawn)) {
            step = foodStep(ctx);
        }
        // the clock counts ticks the step really ran: a far errand hands the tick to the food, and the bench sweep in GamerTask
        // can swap us out for a pickup (that stops the task, so it is not active anymore)
        if (step != null && step == _armorTask && _armorTask.isActive()) {
            _armorTicks++;
        }
        return step;
    }

    // a line when the gap we are working on changes, with the numbers the gate went by (not every tick)
    private void noteGate(AltoClef mod, GamerContext ctx, EndGear.Gap gap, Item armorFloor) {
        String gate = EndGear.gate(gap, armorFloor);
        // a block collection that already started runs past the gap (blocksStep), so it is still the step
        if ((gate.equals("armor") || gate.equals("food") || gate.equals("none")) && _buildBlocks != null && _buildBlocks.isActive()
                && !_buildBlocks.isFinished(mod)) {
            gate = "blocks";
        }
        if (gate.equals(_lastGate)) {
            return;
        }
        _lastGate = gate;
        Debug.logInternal("endprep: gate " + gate + " (beds short " + gap.beds() + ", blocks to get " + gap.buildBlocks() + ", food "
                + FoodTripRules.units(ctx.food()) + " units / " + ctx.food().held() + " held" + (gap.food() ? " SHORT" : "")
                + ", weapon " + (gap.weapon() ? "missing" : "ok") + ", pickaxe " + (gap.pickaxe() ? "missing" : "ok")
                + ", armor to wear " + gap.armorToWear().size() + ", armor " + ctx.facts().armorPoints() + " points"
                + (armorFloor != null ? " (making " + ItemHelper.stripItemName(armorFloor) + ")" : "") + ", bucket " + (gap.waterBucket() ? "missing" : "ok") + ")");
    }

    // beds still to get with the spawn bed counted, the number the search goes by. RoomPhase's prep asks its own version
    private static int bedsToGet(GamerContext ctx, EndConfig cfg) {
        return EndGear.bedShortfall(ctx.facts(), cfg.beds, EndGear.wantSpawnBed(ctx.state(), cfg));
    }

    // the clocks' switch, the HUD's number and the line every 5 minutes
    private void noteShort(AltoClef mod, GamerContext ctx, int bedsShort) {
        _shortOnBeds = bedsShort > 0;
        if (!_shortOnBeds) {
            _shortSince = -1;
            _shortSaid = -1;
            return;
        }
        long now = ctx.facts().gameTime();
        if (_shortSince < 0) {
            _shortSince = now;
        }
        if (EndGear.shortLogDue(_shortSaid, now)) {
            _shortSaid = now;
            ctx.log(EndGear.shortLine(bedsShort, (now - _shortSince) / 1200, mod.getPlayer().getBlockX(), mod.getPlayer().getBlockY(), mod.getPlayer().getBlockZ()));
        }
    }

    // a stack of unstackable beds takes a lot of bag, this is the number to look at when something got thrown on the way
    private void noteGateOpen(AltoClef mod, boolean open, int bag) {
        if (!open) {
            _gateOpenSaid = false;
        } else if (!_gateOpenSaid) {
            _gateOpenSaid = true;
            Debug.logInternal("endprep: gate open with " + bag + " beds in the bag, " + StorageHelper.freeBagSlots(mod) + " free slots");
        }
    }

    // the spawn bed by the portal once EndGear.spawnNow says so, and the beds. returns null when both are done
    private Task bedStep(AltoClef mod, GamerContext ctx, EndConfig cfg) {
        RunState state = ctx.state();
        boolean wantSpawn = EndGear.wantSpawnBed(state, cfg);
        int lacking = EndGear.bedShortfall(ctx.facts(), cfg.beds, wantSpawn);
        // the click already moved our respawn, which is what wantSpawn reads: the task is still lying in the bed though, and it is
        // done when it says so
        boolean finishing = !wantSpawn && !_spawnNoted && !state.spawnBedGaveUp && cfg.placeSpawnNearPortal
                && _setSpawn.isActive() && !_setSpawn.isFinished(mod);
        int bag = ctx.facts().bedsInBag();
        if (!wantSpawn) {
            _spawnGo = false;
        }
        if (finishing || wantSpawn && (_spawnGo || EndGear.spawnNow(bag, lacking, _errandBlocks, cfg.farErrandBlocks))) {
            _spawnGo = wantSpawn;
            return spawnStep(mod, ctx, cfg);
        }
        return lacking > 0 ? bedErrand(mod, ctx, lacking) : null;
    }

    // the one search and it always has a next step: wool in the bag gets crafted, a known bed gets broken however far off it is, and
    // with neither CollectWoolTask finds sheep (its wander does the exploring). a village or a sheep is out there, we just keep walking
    private Task bedErrand(AltoClef mod, GamerContext ctx, int lacking) {
        GamerFacts f = ctx.facts();
        int held = f.count(ItemHelper.BED);
        BlockPos portal = portalCenter(mod, ctx.state());
        BlockPos bed = bedTarget(mod);
        int woolBeds = KitPlanner.usableWool(f) / 3;
        EndGear.BedPlan plan = EndGear.bedPlan(woolBeds, bed != null);
        // the bed we just broke is still on the floor: the lookup is empty but the pickup is not done (bounded, a drop that never
        // shows up must not hold us)
        if (plan != EndGear.BedPlan.MINE) {
            Task tail = bedMineTail(mod);
            if (tail != null) {
                return tail;
            }
        }
        switch (plan) {
            case MINE -> {
                _hudState = shortHud(lacking, "breaking a bed");
                _bedMineTail = 0;
                // the bed's own distance from the portal, not ours: standing far away is the walk back's business
                if (portal != null) {
                    _errandBlocks = Math.hypot(bed.getX() - portal.getX(), bed.getZ() - portal.getZ());
                }
                int want = held + 1;
                // the filter reads the target off the field: task equality ignores the filter, so a new target keeps the old miner
                _bedMine = catalogue("bedmine", want, () -> new MineAndCollectTask(new ItemTarget(ItemHelper.BED, want), CollectBedTask.BEDS,
                        MiningRequirement.HAND).onlyWhere(p -> p.equals(_bedTarget)));
                return _bedMine;
            }
            case CRAFT -> {
                _hudState = shortHud(lacking, "crafting beds");
                int want = held + Math.min(lacking, woolBeds);
                return catalogue("bedcraft", want, () -> new CraftBedTask(want));
            }
            default -> {
                _hudState = shortHud(lacking, "looking for sheep");
                // the wool task counts every colour, so ask for what we hold plus the shortfall or it stops too early (same as KitPlanner.addWool)
                int want = f.count(ItemHelper.WOOL) + KitPlanner.woolShortfall(f, held + lacking);
                return catalogue("bedwool", want, () -> new CollectWoolTask(want));
            }
        }
    }

    private Task bedMineTail(AltoClef mod) {
        return _bedMine != null && _bedMine.isActive() && !_bedMine.isFinished(mod) && _bedMineTail++ < 400 ? _bedMine : null;
    }

    private static String shortHud(int lacking, String doing) {
        return "Short " + lacking + (lacking == 1 ? " bed: " : " beds: ") + doing;
    }

    // one bed at a time: the nearest known one stays the target until it's gone or the miner gave up on it, so two houses at about the
    // same distance don't get to play tennis with us
    private BlockPos bedTarget(AltoClef mod) {
        BlockTracker tracker = mod.getBlockTracker();
        if (_bedTarget == null || tracker.unreachable(_bedTarget) || !bedOk(mod, _bedTarget) || !spareFits(_bedTarget)
                || !tracker.getKnownLocations(CollectBedTask.BEDS).contains(_bedTarget)) {
            _bedTarget = nearestBed(mod);
        }
        return _bedTarget;
    }

    private BlockPos nearestBed(AltoClef mod) {
        Predicate<BlockPos> ok = p -> spareFits(p) && bedAllowed(mod, p);
        return mod.getBlockTracker().getNearestTracking(ok, CollectBedTask.BEDS).orElse(null);
    }

    // the waiver only says a close one exists, the pick goes by distance from us. and the target sticks, so one picked for the
    // fight's beds would carry on across the map as the spare
    private boolean spareFits(BlockPos p) {
        return _spareAt == null || EndGear.spareBedInReach(_spareAt.getX(), _spareAt.getZ(), p.getX(), p.getZ());
    }

    private static boolean bedAllowed(AltoClef mod, BlockPos p) {
        return bedOk(mod, p) && WorldHelper.canBreak(mod, p);
    }

    // any distance, but not the bed we respawn at, not one way down low, and not one on copper (that's a trial chamber, CollectBedTask
    // blacklists those too)
    private static boolean bedOk(AltoClef mod, BlockPos p) {
        return !Respawn.isSpawnBed(p.getX(), p.getY(), p.getZ())
                && !EndGear.bedTooLow(p.getY())
                && !ArrayUtils.contains(ItemHelper.itemsToBlocks(ItemHelper.COPPER_BLOCKS), mod.getWorld().getBlockState(p.below()).getBlock());
    }

    private Task spawnStep(AltoClef mod, GamerContext ctx, EndConfig cfg) {
        if (_spawnStartedAt < 0) {
            _spawnStartedAt = ctx.facts().gameTime() / 20.0;
        }
        BlockPos center = portalCenter(mod, ctx.state());
        if (center == null) {
            giveUpSpawn(ctx, "no portal to set the spawn near");
            return null;
        }
        if (_setSpawn.isActive() && !_setSpawn.isFinished(mod)) {
            _hudState = "Setting spawn by the portal";
            return _setSpawn;
        }
        if (!WorldHelper.inRangeXZ(mod.getPlayer(), center, cfg.spawnBedRange)) {
            _hudState = "Heading to the portal to set spawn";
            if (_toPortal == null || !center.equals(_toPortalAt)) {
                _toPortalAt = center;
                _toPortal = new GetToXZTask(center.getX(), center.getZ());
            }
            return _toPortal;
        }
        _hudState = "Setting spawn by the portal";
        return _setSpawn;
    }

    // the place task finished = the bed took our spawn. getBedSleptPos throws when it never saw a bed, so ask nicely
    private void noteSpawnSet(AltoClef mod, GamerContext ctx) {
        RunState state = ctx.state();
        // (the chat line lands on the click, long before the task is done, so the tracker is no sign that the task is finished)
        if (_spawnNoted || !_setSpawn.isFinished(mod)) {
            return;
        }
        // finished because the bed task found nowhere to put a bed, which is not a spawn. the spawn is optional, so skip it
        // (once: the task stays finished and this runs every tick)
        if (_setSpawn.gaveUp()) {
            if (!state.spawnBedGaveUp) {
                giveUpSpawn(ctx, "nowhere to put the spawn bed");
            }
            return;
        }
        try {
            BlockPos at = _setSpawn.getBedSleptPos();
            state.spawnBed = new RunState.Pos(at.getX(), at.getY(), at.getZ());
        } catch (IllegalStateException e) {
            // spawn set by a bed we never walked to, there is no position to remember
        }
        // the chat line did not put our respawn by the portal (it never came, or the game kept another bed): this is as good as
        // it gets, and not a reason to place beds forever
        _spawnNoted = true;
        if (!Respawn.coversPortal(state)) {
            state.spawnBedGaveUp = true;
        }
        ctx.progress("spawn bed set");
        ctx.save();
    }

    private void giveUpSpawnWhenSlow(GamerContext ctx, EndConfig cfg) {
        if (EndGear.wantSpawnBed(ctx.state(), cfg) && EndGear.spawnClockExpired(ctx.facts().gameTime() / 20.0, _spawnStartedAt, cfg.spawnBedBudgetSeconds)) {
            giveUpSpawn(ctx, "the spawn bed is taking too long");
        }
    }

    private void giveUpSpawn(GamerContext ctx, String why) {
        ctx.state().spawnBedGaveUp = true;
        ctx.log("skipping the spawn bed: " + why);
        ctx.save();
    }

    private BlockPos portalCenter(AltoClef mod, RunState state) {
        if (state.endPortalCenter != null) {
            RunState.Pos c = state.endPortalCenter;
            return new BlockPos(c.x, c.y, c.z);
        }
        // (the prep ahead of the dig has no ring yet, the far errand rule measures from the stronghold start instead)
        if (_prepAt != null) {
            return _prepAt;
        }
        return mod.getBlockTracker().getNearestTracking(Blocks.END_PORTAL).orElse(null);
    }

    // the iron piece the armor floor wants, null once it is fine or its clock ran out (one line when it does, then the visit goes
    // in with what it has)
    private Item armorFloor(GamerContext ctx, EndConfig cfg) {
        if (_armorGaveUp) {
            return null;
        }
        Item piece = EndGear.armorFloorPiece(ctx.facts(), ctx.cfg());
        if (piece != null && EndGear.armorFloorTimedOut(_armorTicks, cfg)) {
            _armorGaveUp = true;
            ctx.log("endprep: armor floor out of time at " + ctx.facts().armorPoints() + " armor points, going anyway");
            return null;
        }
        return piece;
    }

    // weapon, bucket, pickaxe, armor floor, blocks, armor, food. null = all there
    private Task gearStep(AltoClef mod, GamerContext ctx, EndConfig cfg, EndGear.Gap gap, Item armorFloor) {
        if (gap.weapon()) {
            _hudState = "Getting a sword";
            return catalogueItem(Items.IRON_SWORD);
        }
        if (gap.waterBucket()) {
            Task water = waterStep(mod);
            if (water != null) {
                return water;
            }
        }
        if (gap.pickaxe()) {
            _hudState = "Getting a pickaxe";
            return catalogueItem(Items.IRON_PICKAXE);
        }
        List<Item> wear = gap.armorToWear();
        if (armorFloor != null) {
            // the points only count what is worn, so a piece in the bag goes on first. otherwise the floor makes all four
            // when a chestplate and leggings already clear the line
            if (!wear.isEmpty()) {
                _hudState = "Putting on armor";
                return _equip.computeIfAbsent(wear.get(0), piece -> new EquipArmorTask(piece));
            }
            _hudState = "Making iron armor";
            _armorTask = catalogueItem(armorFloor);
            return _armorTask;
        }
        Task blocks = blocksStep(mod, cfg, gap);
        if (blocks != null) {
            return blocks;
        }
        if (!wear.isEmpty()) {
            _hudState = "Putting on armor";
            return _equip.computeIfAbsent(wear.get(0), piece -> new EquipArmorTask(piece));
        }
        return gap.food() ? foodStep(ctx) : null;
    }

    private Task foodStep(GamerContext ctx) {
        return foodStep(ctx, ctx.food().endFloor());
    }

    private Task foodStep(GamerContext ctx, int line) {
        _hudState = "Getting food";
        // the gate counts food we would eat, CollectFoodTask counts rotten flesh too, so a bag of junk makes it finish
        // instantly while the gate stays short and we idle. FoodPlan adds the junk (the same call KitRunner.foodTarget makes),
        // rebuilt only when the number moves
        int target = ctx.food().endCollect(line);
        if (_food == null || target != _foodTarget) {
            _foodTarget = target;
            _food = new CollectFoodTask(target);
        }
        return _food;
    }

    private void resetBucket(int deaths) {
        _bucketTicks = 0;
        _bucketGaveUp = false;
        _bucketDeaths = deaths;
        _bucketPond = null;
        _bucketTask = null;
    }

    // fill the empty bucket from a pond we already know of, never the wandering collector. no pond within reach, or the
    // clock ran out, and the attempt goes in without (the gap stops asking next tick). null = nothing to do here
    private Task waterStep(AltoClef mod) {
        // a full bag and a stack of empties drops the filled bucket on the floor (see fillHasRoom), so that is a no
        if (EndGear.bucketTimedOut(_bucketTicks) || !MLGBucketFallChain.fillHasRoom(mod)) {
            _bucketGaveUp = true;
            return null;
        }
        if (_bucketPond == null || !WaterUpkeep.pondSource(mod, _bucketPond) || mod.getBlockTracker().unreachable(_bucketPond)) {
            // 32 and not the 16 the upkeep uses: a trip is fine here, there is a clock on it
            _bucketPond = WaterUpkeep.nearestPond(mod, 32, p -> false);
            _bucketTask = null;
            if (_bucketPond == null) {
                _bucketGaveUp = true;
                return null;
            }
        }
        if (_bucketTask == null) {
            _bucketTask = new MLGBucketFallChain.WaterUpkeepTask(_bucketPond);
        }
        _bucketTicks++;
        _hudState = "Filling the water bucket";
        return _bucketTask;
    }

    // collects up to cfg.buildBlocks once it starts, not just up to the gate (the bridge off the arrival platform eats ~30)
    private Task blocksStep(AltoClef mod, EndConfig cfg, EndGear.Gap gap) {
        if (_buildBlocks == null) {
            _buildBlocks = new GetBuildingMaterialsTask(cfg.buildBlocks);
        }
        boolean running = _buildBlocks.isActive() && !_buildBlocks.isFinished(mod);
        if (gap.buildBlocks() == 0 && !running) {
            return null;
        }
        _hudState = "Collecting building blocks";
        return _buildBlocks;
    }

    private Task walkIn(AltoClef mod, GamerContext ctx) {
        Task dig = _portalDig.step(mod, ctx, portalCenter(mod, ctx.state()));
        if (dig != null) {
            _hudState = "Digging down to the End portal";
            return dig;
        }
        _hudState = "Walking into the End portal";
        return _enterPortal;
    }

    private Task catalogueItem(Item item) {
        return catalogue(item.toString(), 1, () -> TaskCatalogue.getItemTask(item, 1));
    }

    // TaskCatalogue.getItemTask builds a new task every call, and a new one per tick restarts its state
    private Task catalogue(String name, int count, java.util.function.Supplier<Task> make) {
        return _catalogue.computeIfAbsent(name + ":" + count, k -> make.get());
    }
}
