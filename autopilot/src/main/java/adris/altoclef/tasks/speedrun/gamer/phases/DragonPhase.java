package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.speedrun.DragonBreathTracker;
import adris.altoclef.tasks.speedrun.KillEnderDragonTask;
import adris.altoclef.tasks.speedrun.KillEnderDragonWithBedsTask;
import adris.altoclef.tasks.speedrun.WaitForDragonAndPearlTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.end.DragonDeadLatch;
import adris.altoclef.tasks.speedrun.gamer.end.DragonLogGate;
import adris.altoclef.tasks.speedrun.gamer.end.DragonStrat;
import adris.altoclef.tasks.speedrun.gamer.end.EndDropCache;
import adris.altoclef.tasks.speedrun.gamer.end.EndGear;
import adris.altoclef.tasks.speedrun.gamer.end.EndRules;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.BreathDodge;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// the fight. in the End: breath first, the exit portal once the dragon is dead, then crossing the void from the
// arrival platform, gear we left lying around, armor, end stone for the stand, and the strat (beds or sword, picked
// by DragonStrat). the waiting for a landing has no stall timer (stallSeconds 0), and running out of budget only starts the
// attempt over: dying is what ends a dragon fight
public class DragonPhase implements PhaseHandler {
    // the main island is a disk of about this radius around (0,0), the arrival platform sits at x=100
    private static final double ISLAND_RADIUS = 55;
    // knocked this far out and we are off the island again. hysteresis, so the edge does not flicker
    private static final double ISLAND_LOST_RADIUS = 80;
    // where the bridge ends up: inside the ring of obsidian towers (radius ~42), not on the fountain
    private static final int BRIDGE_X = 28;
    private static final double GEAR_SEARCH_RADIUS = 64;
    private static final double BLOCKS_BUDGET_SECONDS = 150;
    // the heal beam reaches this far past the dragon's box
    private static final double HEAL_BEAM_RANGE = 32;

    private final DragonBreathTracker _breath = new DragonBreathTracker();
    private final Map<Item, Task> _pickups = new HashMap<>();
    private final Map<Item, Task> _equip = new HashMap<>();
    // built in init, not here: the pure parts (isDone, regressTo) get tested with no game and no task machinery
    private Task _exitPortal;
    private Task _bridge;
    private MineAndCollectTask _endStone;
    private Task _runAway;
    private int _breathOutTicks;
    private DragonDeadLatch _latch;
    private EndDropCache _drops;
    private KillEnderDragonWithBedsTask _bedTask;
    private KillEnderDragonTask _swordTask;
    private DragonStrat _strat;
    private final DragonLogGate _statusLog = new DragonLogGate();
    private final Map<Integer, BlockPos> _crystalSpots = new HashMap<>();

    private boolean _wasInEnd;
    private boolean _onIsland;
    // set by the step that wants to walk over the exit portal, written to the engine ONCE at the end of the tick
    private boolean _walkWanted;
    private double _pickupSeconds;
    private double _blockSeconds;
    private double _lastTick;
    private double _lastHealth;
    private String _hudState;

    @Override
    public GamerPhase phase() {
        return GamerPhase.DRAGON;
    }

    @Override
    public String hud() {
        return GamerPhase.DRAGON.hud();
    }

    @Override
    public String hudState() {
        return _hudState;
    }

    // out of the End after the dragon died (the exit portal drops us in the overworld), or the credits already rolled
    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return (state.dragonDead && facts.dimension() != Dimension.END) || facts.creditsShown();
    }

    // back in the overworld and the dragon still lives: we died. the gear check counts what we left in the End
    @Override
    public Optional<GamerPhase> regressTo(GamerFacts facts, RunState state, GamerConfig cfg) {
        if (facts.dimension() != Dimension.END && !state.dragonDead && EndRules.attemptsLeft(state, cfg.end)) {
            return Optional.of(GamerPhase.END_PREP);
        }
        return Optional.empty();
    }

    // no progress is not an error while the dragon circles, the budget is the limit
    @Override
    public double stallSeconds() {
        return 0;
    }

    // only the End deaths can end this. a slow fight is still the fight, the dragon has all day and so do we
    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        return EndRules.attemptsLeft(ctx.state(), ctx.cfg().end) ? Timeout.RETRY : Timeout.STUCK_NO_RESTART;
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        init(ctx);
        _hudState = null;
        _wasInEnd = false;
        _walkWanted = false;
    }

    // END_PORTAL is in the engine's tracked set (GamerTask.TRACKED), a handler level trackBlock leaked its ref count when
    // the task was stopped while interrupted (onExit never ran)
    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
        ctx.walkOnEndPortal(false);
        if (_drops != null) {
            _drops.saveTo(ctx.state().endDrops);
        }
    }

    private void init(GamerContext ctx) {
        EndConfig cfg = ctx.cfg().end;
        if (_latch == null) {
            _latch = new DragonDeadLatch(cfg.dragonGoneSeconds);
            _drops = new EndDropCache(cfg.dropEmptyWaitSeconds);
            _bedTask = new KillEnderDragonWithBedsTask(new WaitForDragonAndPearlTask(true));
            _swordTask = new KillEnderDragonTask();
            _exitPortal = new DoToClosestBlockTask(blockPos -> new GetToBlockTask(blockPos.above(), false), Blocks.END_PORTAL);
            _bridge = new GetToXZTask(BRIDGE_X, 0);
            _endStone = new MineAndCollectTask(new ItemTarget(Items.END_STONE, 64), new Block[]{Blocks.END_STONE}, MiningRequirement.WOOD)
                    .onlyWhere(p -> closeEndStone(AltoClef.getInstance(), p))
                    .dropsOnlyWhere(p -> closeEndStone(AltoClef.getInstance(), p));
        }
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        init(ctx);
        _walkWanted = false;
        Task step;
        if (ctx.facts().dimension() != Dimension.END) {
            step = outsideEnd(ctx);
        } else {
            if (!_wasInEnd) {
                arrive(ctx);
            }
            step = inEnd(mod, ctx);
        }
        // one write per tick, true only while walking into the exit portal (an interrupt clears the Baritone side, this call puts it back)
        ctx.walkOnEndPortal(_walkWanted);
        return step;
    }

    // dragon alive and we are not in the End: regressTo sends us back to END_PREP unless the attempts are gone
    private Task outsideEnd(GamerContext ctx) {
        _wasInEnd = false;
        _hudState = null;
        if (!ctx.state().dragonDead && !EndRules.attemptsLeft(ctx.state(), ctx.cfg().end)) {
            ctx.fail("the dragon killed us " + ctx.cfg().end.attempts + " times");
        }
        return null;
    }

    private void arrive(GamerContext ctx) {
        RunState state = ctx.state();
        _wasInEnd = true;
        _latch.reset();
        if (state.dragonDead) {
            _latch.markDead();
        }
        _drops.load(state.endDrops);
        _drops.restartWait(seconds(ctx));
        _strat = null;
        _statusLog.reset();
        _crystalSpots.clear();
        // children are cached per item and remember how the last visit ended (a finished pickup stays finished), a death
        // and a second trip would otherwise reuse the corpses
        _pickups.clear();
        _equip.clear();
        _runAway = null;
        _breathOutTicks = 0;
        _onIsland = false;
        _pickupSeconds = 0;
        _blockSeconds = 0;
        _lastHealth = -1;
        _lastTick = seconds(ctx);
    }

    private Task inEnd(AltoClef mod, GamerContext ctx) {
        double now = seconds(ctx);
        double dt = Math.min(1, Math.max(0, now - _lastTick));
        _lastTick = now;
        boolean dead = updateDead(mod, ctx, now);
        watchDragon(mod, ctx, now);

        Task step = breathStep(mod);
        if (step == null) {
            step = dead ? exitStep(mod) : fightPrepStep(mod, ctx, dt, now);
        }
        if (step != null || dead) {
            return step;
        }
        return stratStep(mod, ctx);
    }

    // dead = the exit portal blocks exist. the latch's soft "dragon gone for 5 s" guess is not trusted here: from the arrival
    // platform the dragon is out of tracking range for half of every lap, and a saved "dead" turns the next fall into the
    // void into DONE. the soft guess still lives inside the bed task, where a wrong one is undone when the dragon shows up
    private boolean updateDead(AltoClef mod, GamerContext ctx, double now) {
        RunState state = ctx.state();
        _latch.update(exitPortalExists(mod), mod.getEntityTracker().entityFound(EnderDragon.class),
                mod.getChunkTracker().isChunkLoaded(new BlockPos(0, 64, 0)), now);
        boolean dead = _latch.exitPortalSeen();
        if (dead && !state.dragonDead) {
            state.dragonDead = true;
            ctx.progress("dragon is dead");
            ctx.save();
        }
        return dead;
    }

    private static boolean exitPortalExists(AltoClef mod) {
        return KillEnderDragonWithBedsTask.exitPortalExists(mod);
    }

    // progress for the watchdog: every dent in the dragon counts
    private void watchDragon(AltoClef mod, GamerContext ctx, double now) {
        Optional<Entity> dragon = mod.getEntityTracker().getClosestEntity(EnderDragon.class);
        if (dragon.isEmpty()) {
            return;
        }
        EnderDragon ed = (EnderDragon) dragon.get();
        double health = ed.getHealth();
        if (_lastHealth >= 0 && health < _lastHealth - 0.5) {
            ctx.progress("hurt the dragon");
        }
        _lastHealth = health;
        EnderDragonPhase<?> phase = ed.getPhaseManager().getCurrentPhase().getPhase();
        List<EndCrystal> crystals = mod.getEntityTracker().getTrackedEntities(EndCrystal.class);
        logCrystalsGone(crystals);
        if (_statusLog.shouldLog(now, phase)) {
            // the fight was blind in the logs: nothing said how the dragon, the crystals or we were doing
            LocalPlayer me = mod.getPlayer();
            // a crystal near the dragon is healing it, so a bed that does damage can still lose ground
            AABB healBox = ed.getBoundingBox().inflate(HEAL_BEAM_RANGE);
            boolean healing = crystals.stream().anyMatch(c -> healBox.contains(c.position()));
            ctx.log(String.format("dragon: hp %.0f, phase %s, crystals %d, healing=%b, us hp %.1f food %d at %d %d %d, strat %s",
                    health, phase, crystals.size(), healing, me.getHealth(),
                    me.getFoodData().getFoodLevel(), me.getBlockX(), me.getBlockY(), me.getBlockZ(), _strat));
        }
    }

    // a crystal that was tracked and is not any more (blown up, or out of tracking range, the log cannot tell)
    private void logCrystalsGone(List<EndCrystal> now) {
        Map<Integer, BlockPos> seen = new HashMap<>();
        for (EndCrystal c : now) seen.put(c.getId(), c.blockPosition());
        for (Map.Entry<Integer, BlockPos> old : _crystalSpots.entrySet()) {
            if (!seen.containsKey(old.getKey())) {
                BlockPos p = old.getValue();
                Debug.logMessage(String.format("crystal gone at %d %d %d, %d left", p.getX(), p.getY(), p.getZ(), seen.size()));
            }
        }
        _crystalSpots.clear();
        _crystalSpots.putAll(seen);
    }

    private Task breathStep(AltoClef mod) {
        _breath.updateBreath(mod);
        boolean inCloud = touchingBreath(mod);
        LocalPlayer me = mod.getPlayer();
        _breathOutTicks = inCloud ? 0 : _breathOutTicks + 1;
        // the chain's dodge does this too but lets go in the air, so a hop out of the cloud handed us straight back to the fight,
        // and the fight walked us straight back in
        boolean run = BreathDodge.keepRunning(inCloud, _runAway != null, _breath.isNearDragonBreath(me.blockPosition(), BreathDodge.CLEAR_BLOCKS),
                _breathOutTicks, me.onGround(), me.getDeltaMovement().y);
        // drop the task when it's over, its goal is frozen on the first cloud (CustomBaritoneGoalTask caches it) and the next cloud
        // needs a fresh one. a run sitting at its goal counts as over: the runner keeps the old task for anything of the same
        // class, so a tick of nothing is what gets a new goal looked at
        if (!run || _runAway != null && _runAway.isFinished(mod)) {
            _runAway = null;
            return null;
        }
        _hudState = "Dodging dragon's breath";
        if (_runAway == null) {
            _runAway = _breath.getRunAwayTask();
        }
        return _runAway;
    }

    private boolean touchingBreath(AltoClef mod) {
        for (BlockPos in : WorldHelper.getBlocksTouchingPlayer(mod)) {
            if (_breath.isTouchingDragonBreath(in)) {
                return true;
            }
        }
        return false;
    }

    // dragon dead: into the exit portal as soon as it exists
    private Task exitStep(AltoClef mod) {
        if (exitPortalExists(mod)) {
            _walkWanted = true;
            _hudState = "Walking into the exit portal";
            return _exitPortal;
        }
        _hudState = "Waiting for the exit portal";
        return null;
    }

    // everything that comes before the strat: bridge, gear on the floor, armor, building blocks. a perched dragon
    // beats all of it, nobody walks off to fetch a pickaxe in the middle of a perch
    private Task fightPrepStep(AltoClef mod, GamerContext ctx, double dt, double now) {
        EndConfig cfg = ctx.cfg().end;
        GamerFacts facts = ctx.facts();
        updateIsland(facts);
        if (!_onIsland) {
            _hudState = "Crossing the void to the island";
            return _bridge;
        }
        if (dragonPerched(mod)) {
            return null;
        }
        feedCache(mod, ctx, now);
        Task step = pickupStep(mod, ctx, cfg, dt);
        if (step == null) {
            step = armorStep(mod, facts);
        }
        if (step == null) {
            step = blocksStep(mod, facts, cfg, dt);
        }
        return step;
    }

    private void updateIsland(GamerFacts facts) {
        double dist = Math.hypot(facts.x(), facts.z());
        if (dist < ISLAND_RADIUS) {
            _onIsland = true;
        } else if (dist > ISLAND_LOST_RADIUS) {
            _onIsland = false;
        }
    }

    private void feedCache(AltoClef mod, GamerContext ctx, double now) {
        Map<String, Integer> dropped = new HashMap<>();
        for (ItemEntity entity : mod.getEntityTracker().getDroppedItems()) {
            dropped.merge(EndRules.key(entity.getItem().getItem()), entity.getItem().getCount(), Integer::sum);
        }
        if (_drops.update(dropped, now, nearDeathSite(ctx))) {
            _drops.saveTo(ctx.state().endDrops);
            ctx.save();
        }
    }

    // an empty entity list only means "gone" when the place we died at is close enough to be loaded for us
    private boolean nearDeathSite(GamerContext ctx) {
        RunState.Death death = EndRules.lastEndDeath(ctx.state());
        if (death == null) {
            return true;
        }
        return Math.hypot(ctx.facts().x() - death.x, ctx.facts().z() - death.z) < GEAR_SEARCH_RADIUS;
    }

    private Task pickupStep(AltoClef mod, GamerContext ctx, EndConfig cfg, double dt) {
        if (_pickupSeconds > cfg.pickupBudgetSeconds) {
            return null;
        }
        Item next = EndGear.nextPickup(ctx.facts(), cfg, item -> mod.getEntityTracker().itemDropped(item));
        if (next == null) {
            return null;
        }
        _pickupSeconds += dt;
        _hudState = "Picking up gear we dropped";
        int count = isBed(next) ? cfg.beds : 1;
        return _pickups.computeIfAbsent(next, item -> new PickupDroppedItemTask(item, count, true));
    }

    private static boolean isBed(Item item) {
        for (Item bed : ItemHelper.BED) {
            if (bed == item) {
                return true;
            }
        }
        return false;
    }

    // the pumpkin sits on the head, putting the helmet back on would just swap them forever
    private Task armorStep(AltoClef mod, GamerFacts facts) {
        List<Item> wear = new ArrayList<>(EndGear.armorToWear(facts));
        if (KillEnderDragonTask.pumpkinWorn(mod)) {
            wear.removeIf(piece -> piece == Items.IRON_HELMET || piece == Items.DIAMOND_HELMET || piece == Items.NETHERITE_HELMET);
        }
        if (wear.isEmpty()) {
            return null;
        }
        _hudState = "Putting on armor";
        return _equip.computeIfAbsent(wear.get(0), piece -> new EquipArmorTask(piece));
    }

    // the fight only needs blocks for the stand's floor, so this is just that much, and only from stone right here: a stack from
    // the far side of a long drop is us missing the landing. the arrival platform is no place to mine so this only runs on the island
    private Task blocksStep(AltoClef mod, GamerFacts facts, EndConfig cfg, double dt) {
        boolean running = _endStone.isActive() && !_endStone.isFinished(mod) && facts.buildBlocks() < cfg.endMinBlocks * 2;
        boolean low = facts.buildBlocks() < cfg.endMinBlocks;
        if (!running && !low || _blockSeconds > BLOCKS_BUDGET_SECONDS) {
            return null;
        }
        if (!StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.WOOD) || !endStoneClose(mod)) {
            return null;
        }
        _blockSeconds += dt;
        _hudState = "Collecting end stone";
        return _endStone;
    }

    // without the scan an empty pick sends the task off wandering, and that's the long drop again
    private static boolean endStoneClose(AltoClef mod) {
        BlockPos feet = mod.getPlayer().blockPosition();
        int r = EndRules.END_STONE_RANGE;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-r, -EndRules.END_STONE_DROP, -r), feet.offset(r, EndRules.END_STONE_RISE, r))) {
            if (mod.getWorld().getBlockState(p).is(Blocks.END_STONE) && !mod.getBlockTracker().unreachable(p) && WorldHelper.canBreak(mod, p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean closeEndStone(AltoClef mod, BlockPos p) {
        BlockPos feet = mod.getPlayer().blockPosition();
        return EndRules.endStoneClose(p.getX() - feet.getX(), p.getY() - feet.getY(), p.getZ() - feet.getZ());
    }

    // beds or sword. the tasks are built once
    private Task stratStep(AltoClef mod, GamerContext ctx) {
        boolean perched = dragonPerched(mod);
        DragonStrat next = DragonStrat.choose(_bedTask.bedsLeft(mod));
        if (next != _strat) {
            ctx.log(next == DragonStrat.BEDS ? "fighting the dragon with beds" : "fighting the dragon with the sword, out of beds");
            ctx.progress("strat " + next);
            _strat = next;
        }
        if (_strat == DragonStrat.BEDS) {
            _hudState = perched ? "Blowing up the dragon with beds" : "Waiting for the dragon to perch";
            return _bedTask;
        }
        _hudState = perched ? "Fighting the dragon" : "Waiting for the dragon to perch";
        return _swordTask;
    }

    private static boolean dragonPerched(AltoClef mod) {
        Optional<Entity> dragon = mod.getEntityTracker().getClosestEntity(EnderDragon.class);
        if (dragon.isEmpty()) {
            return false;
        }
        DragonPhaseInstance phase = ((EnderDragon) dragon.get()).getPhaseManager().getCurrentPhase();
        return phase.getPhase() == EnderDragonPhase.LANDING || phase.isSitting() || phase.getPhase() == EnderDragonPhase.LANDING_APPROACH;
    }

    private static double seconds(GamerContext ctx) {
        return ctx.facts().gameTime() / 20.0;
    }
}
