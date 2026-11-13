package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.AbstractDoToEntityTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.phases.NetherPhase;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.FireRes;
import adris.altoclef.util.helpers.FireResRules;
import adris.altoclef.util.helpers.FireResTradeRules;
import adris.altoclef.util.helpers.FireResTradeRules.Form;
import adris.altoclef.util.helpers.FireResTradeRules.Spot;
import adris.altoclef.util.helpers.ItemPickupRules;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.RouteRules;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.Tuning;
import adris.altoclef.util.slots.Slot;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.CombatAim;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.monster.piglin.PiglinArmPose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

// the buddy's fork, Tuning fr_trade, on by default since the route (steering 118; steering 89): the first fire resistance bartered from piglins before the
// fortress. in the nether with gold in the bag, no fire resistance potion and fewer than two rods, the buddy gives gold to the
// piglins it meets until a fire resistance potion is in the bag (drinkable or splash), then goes on to the fortress; at the
// blazes the potion goes down before the fight, a drinkable one by the food chain as the brewed one does, a splash one thrown at
// the feet here. given up, with the reason, on running out of gold, on no piglin in sight for Tuning fr_trade_search seconds,
// at the fortress with none in sight, or past Tuning fr_trade_max seconds; the rods and the brew then go as before. with Tuning
// fr_trade_hold the traded potion is kept for the blazes: on the way, merely on fire, it waits (FireRes.holdsTraded).
// CollectBlazeRodsTask owns one of these per order and asks it every tick with the switch on. the giving reuses the rules of
// TradeWithPiglinsTask's PerformTradeWithPiglin (the nearest adult piglin not already admiring, none with a hoglin near, one
// that never takes gold left alone), aimed and paced: that task clicks every tick without a look, fetches gold when it has
// none, and is a pearl and obsidian ResourceTask whose finish counts any potion, a water bottle too. with Tuning fr_trade_biome
// (on by default) a search begun out of nether wastes first walks to the wastes the client has loaded (FireResTradeRules
// .wastesTarget), and the fr_trade_search clock counts from the arrival there. with Tuning fr_trade_stay (on by default) the
// piglin the gold went to keeps it: the buddy waits by it while it admires and takes another free one only within a few
// blocks (FireResTradeRules.pick). the pickup takes a piglin's ender pearls too while the goal asks for more, or always with
// Tuning fr_trade_pearls (off by default; FireResTradeRules.takesPearls). a line for every ingot handed and every pearl picked up
public final class FireResTrade {

    // by the spawner: the trade is for before the fortress
    static final double FORTRESS_NEAR = FireResTradeRules.FORTRESS_NEAR;
    // a piglin this close holding gold is admiring an ingot (ours, as a rule): its throw is still to come
    static final double PENDING_RANGE = 16;
    // a fire resistance potion on the ground this close is picked up
    static final double PICKUP_RANGE = 24;
    // a line of how the giving goes every this many ingots
    static final int PROGRESS_EVERY = 8;
    // the first look decides, a kit landing a moment late still counts (game ticks)
    static final long BEGIN_GRACE_TICKS = 100;
    // the walk to the wastes reaches this close to the middle of the chosen chunk first, in them or not (blocks)
    static final double WASTES_ARRIVE = 8;
    // past that ring out of the wastes, the walk goes on to within this of the sampled block (blocks, any direction)
    static final int WASTES_MIDDLE = 2;

    enum State {
        WAITING,
        BEGUN,
        ENDED
    }

    private State _state = State.WAITING;
    private long _firstLookAt = -1;
    private long _begunAt;
    private int _goldAtBegin;
    private long _goldOutAt = -1;
    private long _piglinSeenAt;
    private boolean _piglinNear;
    private boolean _piglinLogged;
    private boolean _searchLogged;
    private boolean _yieldLogged;
    private boolean _yielding;
    private long _yieldTakenAt = -1;
    private boolean _blazeOnLogged;
    private int _progressLogged;
    private String _hud = "Trading gold with piglins for fire resistance";
    // the fire resistance potions seen on the ground (entity id), and the ones the pickup gave up on
    private final Map<Integer, Form> _drops = new HashMap<>();
    private final Set<Integer> _dropsGivenUp = new HashSet<>();
    private int _bagPotionsBefore;
    private final GiveGoldTask _give = new GiveGoldTask();
    private PickupDroppedItemTask _pickup;
    // the pickup above takes ender pearls too (FireResTradeRules.takesPearls); built again when that changes
    private boolean _pickupPearls;
    // the pearls the goal still asks for (EyeMath.pearlsNeeded; 0 for an order of rods alone), and the bag's pearls as last
    // seen during the trade (-1 before the first look)
    private final IntSupplier _pearlsNeeded;
    private int _pearlsSeen = -1;
    private TimeoutWanderTask _wander;
    private Task _equip;
    // where the trade began (the lines' distances), and Tuning fr_trade_biome's walk to the wastes: settled once in them, with
    // none loaded, or after the walk ended out of them
    private double _startX;
    private double _startZ;
    private Task _toWastes;
    // the sampled block the target was picked by (in the wastes at the height the trade began), and whether the walk has
    // gone on from the ring to it
    private BlockPos _wastesAt;
    private boolean _toWastesMiddle;
    private long _walkTicks;
    private boolean _wastesSettled;
    // after the trade: whether it got its potion, then the way to the blazes and the drink there
    private boolean _succeeded;
    private long _endedAt;
    private boolean _arrivalLogged;
    private boolean _drinkLogged;
    private boolean _hadEffect;
    private int _lastDrink;
    private int _lastThrow;
    private ThrowAtFeetTask _throw;
    // Tuning route_trade (on by default, steering 120): the last ingot a piglin took (the dead end's clock), the ingots counted
    // then, whether this tick's step is the search (a walk the rods task may break off for a magma cube, route_cream_trade),
    // and the wider search: its centre, the angle of its first point, the point under way, its walk and when it began, and
    // the last ring said
    private boolean _everBegun;
    private long _handedAt;
    private int _handedSeen;
    private boolean _searchStep;
    private BlockPos _searchCentre;
    private double _searchFrom;
    private int _searchK;
    private Task _searchWalk;
    private long _searchWalkAt;
    private long _searchWalkTicks = FireResTradeRules.POINT_TICKS;
    // the search's walks to wastes come into view (trade_search_wastes): how many so far, the last one's target; a new one
    // must lie SEARCH_WASTES_APART from it, at most SEARCH_WASTES_TRIES a trade
    private int _searchWastesTries;
    private Spot _searchWastesLast;
    private static final int SEARCH_WASTES_TRIES = 3;
    private static final double SEARCH_WASTES_APART = 48;
    private int _searchRingSaid;
    // Tuning route_trade_known (route jar 4): the piglins seen free to take gold within KEEP_RANGE (entity id, the last game
    // tick), the game ticks with none known since the last ingot taken, this tick's clock, the hoglin's wait and its line, and
    // why the trade ended without its potion (the fallback's line)
    private final Map<Integer, Long> _freeSeen = new HashMap<>();
    private long _noneKnown;
    private long _lastTickAt = -1;
    private Task _idle;
    private boolean _waitLogged;
    private String _endWhy;

    FireResTrade() {
        this(() -> 0);
    }

    FireResTrade(IntSupplier pearlsNeeded) {
        _pearlsNeeded = pearlsNeeded;
    }

    String hud() {
        return _hud;
    }

    boolean live() {
        return _state != State.ENDED;
    }

    // the trade began (it may have ended since)
    boolean begun() {
        return _everBegun;
    }

    // the trade ended with its potion
    boolean succeeded() {
        return _state == State.ENDED && _succeeded;
    }

    // (Tuning route_trade_fire) the fire in the bag when the trade ended, and whether it reached the target: the route skips the
    // wart, the cream and the brew then
    private int _fireAtEnd;

    boolean fireEnough() {
        int target = adris.altoclef.util.helpers.RouteRules.tradeFireTicks();
        return target > 0 && succeeded() && _fireAtEnd >= target;
    }

    int fireAtEnd() {
        return _fireAtEnd;
    }

    // every fire resistance potion in the bag, the hotbar and the offhand, as ticks of the effect (a stack counts each potion)
    static int fireTicks(AltoClef mod) {
        if (mod.getPlayer() == null) return 0;
        Inventory inventory = mod.getPlayer().getInventory();
        int ticks = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (FireResTradeRules.form(stack) == Form.NONE) continue;
            net.minecraft.world.item.alchemy.PotionContents contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            if (contents == null) continue;
            for (MobEffectInstance effect : contents.getAllEffects()) {
                if (effect.is(MobEffects.FIRE_RESISTANCE)) {
                    ticks += effect.getDuration() * stack.getCount();
                    break;
                }
            }
        }
        return ticks;
    }

    // this tick's step was the search for a piglin, a walk with no piglin in sight and no ingot being admired
    boolean searching() {
        return _searchStep;
    }

    static boolean routeOn() {
        return Tuning.get("route_trade", 0) > 0;
    }

    private static long deadTicks() {
        return Math.round(Tuning.get("route_trade_dead_s", FireResTradeRules.DEAD_S) * 20);
    }

    private static boolean knownOn() {
        return Tuning.get("route_trade_known", 0) > 0;
    }

    private static long stallTicks() {
        return Math.round(Tuning.get("route_trade_stall_s", FireResTradeRules.STALL_S) * 20);
    }

    // why the trade ended without its potion, for the route's fallback line: all the gold handed, or the bound and the gold left
    String endWhy() {
        return _endWhy != null ? _endWhy : _everBegun ? "the trade still on" : "no trade begun";
    }

    // the trade's step this tick: a task to run, or null for the blaze rods' own way (the walk to the fortress while no piglin
    // is in sight, the fight when a blaze is near, everything once the trade has ended)
    // fight: whether the blaze fight would take a blaze within the given blocks now (BlazeFight.takesWithin)
    Task tick(AltoClef mod, BlockPos spawner, java.util.function.DoublePredicate fight) {
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        _searchStep = false;
        int drink = count(mod, Form.DRINK);
        int thrown = count(mod, Form.THROW);
        boolean potion = drink + thrown > 0;
        // (Tuning route_trade_fire: the trade's target is the fire in the bag, not the first potion, RouteRules.tradeEnough)
        int fireTarget = adris.altoclef.util.helpers.RouteRules.tradeFireTicks();
        int fire = fireTarget > 0 ? fireTicks(mod) : 0;
        boolean enough = adris.altoclef.util.helpers.RouteRules.tradeEnough(fire, fireTarget, potion);
        double blaze = nearestBlaze(mod);
        boolean blazeNear = blaze <= FireResRules.BLAZE_RANGE;
        boolean atFortress = spawner != null && player.position().distanceTo(Vec3.atCenterOf(spawner)) <= FORTRESS_NEAR;
        if (_state == State.ENDED) {
            if (_succeeded) {
                // (Tuning fr_trade_hold: the potion is kept for the blazes, FireRes.holdsTraded)
                FireRes.traded(now, spawner);
                watchAfter(mod, now, drink, thrown, blaze, blazeNear || atFortress);
            }
            return null;
        }
        int gold = mod.getItemStorage().getItemCount(Items.GOLD_INGOT);
        if (_state == State.WAITING) {
            if (_firstLookAt < 0) _firstLookAt = now;
            String why = fireTarget > 0 && enough ? "fire resistance for " + adris.altoclef.util.helpers.RouteRules.clock(fire) + " in the bag already"
                    : FireResTradeRules.whyNot(gold, fireTarget > 0 ? false : potion, mod.getItemStorage().getItemCount(Items.BLAZE_ROD));
            if (why == null && (atFortress || blazeNear)) why = "at the fortress already";
            if (why != null) {
                if (now - _firstLookAt >= BEGIN_GRACE_TICKS || now < _firstLookAt) {
                    Debug.logInternal("strategy trade: not begun (" + why + ")");
                    _endWhy = "no trade begun, " + why;
                    _state = State.ENDED;
                }
                return null;
            }
            _state = State.BEGUN;
            _begunAt = now;
            _everBegun = true;
            _handedAt = now;
            _handedSeen = 0;
            _noneKnown = 0;
            _lastTickAt = now;
            _goldAtBegin = gold;
            _piglinSeenAt = now;
            _bagPotionsBefore = 0;
            _startX = player.getX();
            _startZ = player.getZ();
            Debug.logInternal("strategy trade: biome at start " + biomeAt(mod, player.blockPosition()));
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: begun, %d gold (a potion by the last ingot %.0f%%), rods %d, search %s s, cap %s s, %s",
                    gold, 100 * FireResTradeRules.chanceWithin(gold), mod.getItemStorage().getItemCount(Items.BLAZE_ROD),
                    FireResTradeRules.seconds(searchTicks()), FireResTradeRules.seconds(maxTicks()), wander() ? "wandering for piglins" : "on the way to the fortress meanwhile"));
            if (routeOn() && knownOn()) {
                Debug.logInternal(String.format(Locale.ROOT, "strategy trade: the route's trade, until a potion or all the gold handed; the bound %s s with no piglin known"
                        + " or %s s with none taking gold, the search widening meanwhile, a hoglin keeping us off a piglin within %s (the search and cap above unused)",
                        FireResTradeRules.seconds(deadTicks()), FireResTradeRules.seconds(stallTicks()), GiveGoldTask.hoglinRule()));
            } else if (routeOn()) {
                Debug.logInternal("strategy trade: the route's trade, until a potion or all the gold handed; a dead end after "
                        + FireResTradeRules.seconds(deadTicks()) + " s with no ingot taken, the search widening meanwhile (the search and cap above unused)");
            }
        }

        int given = Math.max(0, _goldAtBegin - gold);
        // (the dead end's clock: from the last ingot gone from the bag; gold picked up again does not count back)
        if (given > _handedSeen) {
            _handedSeen = given;
            _handedAt = now;
            _noneKnown = 0;
        }
        if (now < _handedAt) _handedAt = now;
        long dt = _lastTickAt < 0 || now < _lastTickAt ? 0 : now - _lastTickAt;
        _lastTickAt = now;
        if (gold <= 0 && _goldOutAt < 0) _goldOutAt = now;
        if (gold > 0) _goldOutAt = -1;
        _give.watchHanded(gold, now);
        int pearls = mod.getItemStorage().getItemCount(Items.ENDER_PEARL);
        watchPearls(pearls);
        boolean takesPearls = FireResTradeRules.takesPearls(Tuning.get("fr_trade_pearls", 0) > 0, _pearlsNeeded.getAsInt(), pearls);
        // the potion first: it can burn in lava, and it is what the trade is for
        Optional<ItemEntity> drop = watchDrops(mod, given, drink + thrown);
        if (drop.isEmpty() && takesPearls) drop = nearestPearl(mod);
        boolean admiring = admiringNear(mod);
        boolean pending = admiring || drop.isPresent();
        double piglin = _give.nearestTradeable(mod);
        // (Tuning route_trade_known: the dead end's clock runs only with no piglin known; a known one a hoglin keeps us off
        // just now is waited for)
        boolean known = noteKnown(mod, now);
        if (!known && !pending) _noneKnown += dt;
        Piglin held = knownOn() ? _give.hoglinHeld(mod, _freeSeen.keySet()) : null;
        boolean wasNear = _piglinNear;
        _piglinNear = FireResTradeRules.piglinNear(_piglinNear, piglin);
        // (the last piglin near can stop counting the moment it is done admiring, and the search walks off that tick:
        // the line says why, 8 Nov 2026)
        if (wasNear && !_piglinNear && !admiring) Debug.logInternal("strategy trade: no piglin free any more: " + _give.whyNone(mod));
        // (Tuning fr_trade_biome: the walk to the wastes does not count against the search)
        if (walkingToWastes()) _piglinSeenAt = now;
        if (_piglinNear || admiring) {
            if (!_piglinLogged && _piglinNear) {
                _piglinLogged = true;
                Debug.logInternal(String.format(Locale.ROOT, "strategy trade: a piglin found after %s s, %.0f blocks off",
                        FireResTradeRules.seconds(now - _begunAt), piglin));
                Debug.logInternal(String.format(Locale.ROOT, "strategy trade: piglin met in %s at %.0f blocks from the start",
                        biomeAt(mod, player.blockPosition()), fromStart(player)));
            }
            _piglinSeenAt = now;
        }
        // (look 28: the yield to a blaze only while the fight would take one, with a margin in blocks and in time,
        // FireResTradeRules.yields)
        double line = FireResTradeRules.yieldLine(_yielding);
        boolean fightTakes = blaze <= line && fight.test(line);
        if (fightTakes) _yieldTakenAt = now;
        _yielding = FireResTradeRules.yields(fightTakes, _yieldTakenAt < 0 || now < _yieldTakenAt ? Long.MAX_VALUE : now - _yieldTakenAt);
        if (blazeNear && !_yielding && !_blazeOnLogged) {
            _blazeOnLogged = true;
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: a blaze %.0f blocks off, out of the fight's reach: the trade goes on", blaze));
        }
        if (!blazeNear) _blazeOnLogged = false;
        boolean worn = PiglinAi.isWearingSafeArmor(player);
        Item piece = goldPieceInBag(mod);
        // (Tuning route_trade: no give-up on the search's clock, the cap or the fortress; only all the gold handed, or the dead end)
        FireResTradeRules.Verdict verdict = routeOn()
                ? FireResTradeRules.decideRoute(enough, gold, pending, worn || piece != null, _goldOutAt < 0 ? Long.MAX_VALUE : now - _goldOutAt,
                now - _handedAt, _noneKnown, _piglinNear, held != null, _yielding, knownOn(), deadTicks(), stallTicks())
                : FireResTradeRules.decide(potion, gold, pending, worn || piece != null, now - _begunAt,
                _goldOutAt < 0 ? Long.MAX_VALUE : now - _goldOutAt, now - _piglinSeenAt, _piglinNear, atFortress, _yielding,
                searchTicks(), maxTicks());
        if (given >= _progressLogged + PROGRESS_EVERY && verdict.step() != FireResTradeRules.Step.DONE) {
            _progressLogged = given - given % PROGRESS_EVERY;
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: %d ingots given, %d left, %d piglins in sight, %s s",
                    given, gold, _give.tradeableCount(mod), FireResTradeRules.seconds(now - _begunAt)));
        }
        switch (verdict.step()) {
            case DONE -> {
                String form = drink > 0 && thrown > 0 ? "drinkable and splash" : drink > 0 ? "drinkable" : "splash";
                Debug.logInternal("strategy trade: done, a potion after " + given + " ingots in " + FireResTradeRules.seconds(now - _begunAt)
                        + " seconds (" + form + ", " + gold + " gold left)" + (fireTarget > 0 ? String.format(Locale.ROOT,
                        "; fire resistance %s in the bag, the target %s, %d drinkable and %d splash", adris.altoclef.util.helpers.RouteRules.clock(fire),
                        adris.altoclef.util.helpers.RouteRules.clock(fireTarget), drink, thrown) : ""));
                _fireAtEnd = fire;
                end(player, now, true, drink, thrown);
                FireRes.traded(now, spawner);
                return null;
            }
            case GIVE_UP -> {
                // (Tuning route_trade_fire: all the gold handed with some fire but under the target: done with what it got, the
                // route goes on as before)
                if (fireTarget > 0 && potion && FireResTradeRules.OUT_OF_GOLD.equals(verdict.reason())) {
                    Debug.logInternal(String.format(Locale.ROOT, "strategy trade: done, a potion after %d ingots in %s seconds, all the gold handed;"
                                    + " fire resistance %s in the bag, under the target %s, %d drinkable and %d splash", given,
                            FireResTradeRules.seconds(now - _begunAt), adris.altoclef.util.helpers.RouteRules.clock(fire),
                            adris.altoclef.util.helpers.RouteRules.clock(fireTarget), drink, thrown));
                    _fireAtEnd = fire;
                    end(player, now, true, drink, thrown);
                    FireRes.traded(now, spawner);
                    return null;
                }
                Debug.logInternal("strategy trade: given up (" + verdict.reason() + ") after " + given + " ingots in "
                        + FireResTradeRules.seconds(now - _begunAt) + " seconds, " + gold + " gold left");
                _endWhy = FireResTradeRules.OUT_OF_GOLD.equals(verdict.reason()) ? "all the gold handed, " + given + " ingots"
                        : verdict.reason() + ", " + given + " ingots handed, " + gold + " gold left";
                end(player, now, false, drink, thrown);
                return null;
            }
            case YIELD -> {
                if (!_yieldLogged) {
                    _yieldLogged = true;
                    Debug.logInternal(String.format(Locale.ROOT, "strategy trade: a blaze %.0f blocks off, the fight first", blaze));
                }
                return null;
            }
            case WAIT -> {
                _yieldLogged = false;
                _searchLogged = false;
                if (!_waitLogged) {
                    _waitLogged = true;
                    Debug.logInternal("strategy trade: waiting by " + _give.hoglinWhy(mod, held) + ", the mob defense takes one that comes");
                }
                _hud = "Waiting for a hoglin to leave the piglins";
                if (_idle == null) _idle = new IdleTask();
                return _idle;
            }
            case SEARCH -> {
                _yieldLogged = false;
                _waitLogged = false;
                if (!_searchLogged) {
                    _searchLogged = true;
                    Debug.logInternal("strategy trade: no piglin in sight, " + (wander() ? "wandering for one"
                            : routeOn() ? "searching on" : "on the way to the fortress meanwhile"));
                }
                _searchStep = true;
                // (Tuning fr_trade_biome: out of the wastes, first the walk to them)
                Task walk = biomeWalk() ? toWastes(mod, now, spawner) : null;
                if (walk != null) {
                    _hud = "Walking to the nether wastes for piglins";
                    return walk;
                }
                // (Tuning route_trade: then wider and wider round where the walk ended, never to the fortress meanwhile)
                if (routeOn() && !wander()) {
                    _hud = "Looking further for piglins to trade with";
                    return widerSearch(mod, now);
                }
                if (!wander()) return null;
                if (_wander == null) _wander = new TimeoutWanderTask(false);
                _hud = "Looking for piglins to trade with";
                return _wander;
            }
            default -> {
                _yieldLogged = false;
                _searchLogged = false;
                _waitLogged = false;
            }
        }
        // TRADE. piglins attack a player with no gold on
        if (!worn) {
            if (_equip == null) _equip = new EquipArmorTask(piece);
            _hud = "Putting on gold for the piglins";
            return _equip;
        }
        _equip = null;
        // (Tuning route_trade_cube, route jar 7: a magma cube near and the trade hands nothing, it walks off the cubes first,
        // RouteRules.tradeCubeAway)
        Task offCubes = cubeAway(mod, now);
        if (offCubes != null) return offCubes;
        // what a piglin threw first: a potion on the ground can burn in lava or be out of reach in a moment
        if (drop.isPresent()) {
            boolean pearl = drop.get().getItem().is(Items.ENDER_PEARL);
            // (Tuning route_potion_edge, route jar 7: a potion by lava is fetched from its stand with no lava beside it)
            Task edge = pearl ? null : edgeFetch(mod, drop.get(), now);
            if (edge != null) return edge;
            if (_pickup == null || _pickupPearls != takesPearls) {
                List<ItemTarget> targets = new ArrayList<>(List.of(new ItemTarget(Items.POTION, 64), new ItemTarget(Items.SPLASH_POTION, 64),
                        new ItemTarget(Items.LINGERING_POTION, 64)));
                if (takesPearls) targets.add(new ItemTarget(Items.ENDER_PEARL, 64));
                _pickup = new PickupDroppedItemTask(targets.toArray(new ItemTarget[0]), true);
                _pickupPearls = takesPearls;
            }
            if (_pickup.gaveUpReason() != null) {
                Debug.logInternal("strategy trade: the " + (pearl ? "ender pearl" : "potion") + " on the ground given up (" + _pickup.gaveUpReason() + ")");
                _dropsGivenUp.add(drop.get().getId());
                _pickup = null;
                return null;
            }
            _hud = pearl ? "Picking up the piglin's ender pearls" : "Picking up the piglin's fire resistance potion";
            return _pickup;
        }
        _hud = gold > 0 ? "Trading gold with piglins for fire resistance" : "Waiting for the piglin's last throw";
        return _give;
    }

    // Tuning route_trade_known: every piglin free to take gold within KEEP_RANGE is noted as seen now; one seen dead or left
    // alone since is forgotten, one out of sight is kept till KNOWN_TICKS have passed (it may well be alive round a wall).
    // whether a piglin is known
    private boolean noteKnown(AltoClef mod, long now) {
        LocalPlayer player = mod.getPlayer();
        for (Piglin piglin : mod.getEntityTracker().getTrackedEntities(Piglin.class)) {
            if (_give.tradeable(mod, piglin) && piglin.distanceTo(player) <= FireResTradeRules.KEEP_RANGE) _freeSeen.put(piglin.getId(), now);
        }
        _freeSeen.entrySet().removeIf(e -> {
            Entity seen = mod.getWorld().getEntity(e.getKey());
            return !FireResTradeRules.known(now - e.getValue()) || _give.leftAlone(e.getKey()) || seen != null && !seen.isAlive();
        });
        return !_freeSeen.isEmpty();
    }

    private void end(LocalPlayer player, long now, boolean succeeded, int drink, int thrown) {
        _state = State.ENDED;
        _succeeded = succeeded;
        _endedAt = now;
        _lastDrink = drink;
        _lastThrow = thrown;
        // (an effect already on is not the trade's drink)
        _hadEffect = player.hasEffect(MobEffects.FIRE_RESISTANCE);
    }

    // after the trade got its potion: one line when the buddy is at the blazes with it (the trial's end), and one when the effect
    // comes on, by the drink or the splash, with how far the nearest blaze was
    private void watchAfter(AltoClef mod, long now, int drink, int thrown, double blaze, boolean atBlazes) {
        LocalPlayer player = mod.getPlayer();
        if (!_arrivalLogged && atBlazes && drink + thrown > 0) {
            _arrivalLogged = true;
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: at the blazes with a fire resistance potion (%s), nearest blaze %s, %s s after the trade",
                    drink > 0 ? "drinkable" : "splash", distance(blaze), FireResTradeRules.seconds(now - _endedAt)));
        }
        MobEffectInstance fx = player.getEffect(MobEffects.FIRE_RESISTANCE);
        boolean has = fx != null;
        if (has && !_hadEffect && !_drinkLogged) {
            _drinkLogged = true;
            String how = drink < _lastDrink ? "drunk" : thrown < _lastThrow ? "splashed" : "on";
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: fire resistance %s, nearest blaze %s, on fire %s, %s s of it",
                    how, distance(blaze), player.isOnFire() ? "yes" : "no", FireResTradeRules.seconds(fx.getDuration())));
        }
        _hadEffect = has;
        _lastDrink = drink;
        _lastThrow = thrown;
    }

    // a splash or lingering fire resistance potion thrown at the feet when the drink would go down: a blaze in drinking range or
    // fire on the buddy, no effect left to speak of, and no drinkable one (the food chain drinks that). null when not now
    Task throwStep(AltoClef mod) {
        if (_throw != null && _throw.isActive() && !_throw.isFinished(mod)) return _throw;
        _throw = null;
        LocalPlayer player = mod.getPlayer();
        if (count(mod, Form.DRINK) > 0 || count(mod, Form.THROW) == 0 || player.isBlocking()) return null;
        MobEffectInstance fx = player.getEffect(MobEffects.FIRE_RESISTANCE);
        double blaze = nearestBlaze(mod);
        long now = mod.getWorld().getGameTime();
        boolean asked = FireRes.asked(now);
        if (!FireResRules.wantsToDrink(true, fx == null ? 0 : fx.getDuration(), player.isOnFire() || asked, blaze)) return null;
        // (Tuning fr_trade_hold, off by default: merely on fire on the way, the potion waits for the blazes)
        if (FireRes.holdsTraded(player, blaze, asked, now) || FireRes.holdsForBox(player, blaze, asked, now)
                || FireRes.holdsAny(player, blaze, asked, now) || FireRes.holdsRoute(player, blaze, asked, now)) return null;
        _throw = new ThrowAtFeetTask(blaze);
        return _throw;
    }

    // Tuning route_hold_fight, on by default (route jar 2): a splash or lingering fire resistance potion thrown at the feet in
    // shallow lava, by the lava escape's chain. the throw lived in the rods task alone, which the lava escape interrupts, so
    // a splash potion never went down in lava (a drinkable one does: the food chain drinks in lava). shallow: ground within
    // LAVA_THROW_DEPTH under the feet, where the splash lands within its reach; in deep lava it would sink past it. null when
    // not now
    static final int LAVA_THROW_DEPTH = 2;
    private static ThrowAtFeetTask _lavaThrow;

    public static Task lavaThrow(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (player == null || !adris.altoclef.util.helpers.RouteRules.holdFightOn()) return null;
        if (_lavaThrow != null && _lavaThrow.isActive() && !_lavaThrow.isFinished(mod)) return _lavaThrow;
        _lavaThrow = null;
        if (!player.isInLava() || FireRes.has(player) || count(mod, Form.DRINK) > 0 || count(mod, Form.THROW) == 0 || player.isBlocking()) return null;
        net.minecraft.core.BlockPos feet = player.blockPosition();
        boolean shallow = false;
        for (int i = 1; i <= LAVA_THROW_DEPTH && !shallow; i++) {
            var state = mod.getWorld().getBlockState(feet.below(i));
            if (!state.isAir() && state.getFluidState().isEmpty()) shallow = true;
        }
        if (!shallow) return null;
        // (Tuning route_hold_way, route jar 3: on the way the potion waits in lava too, till RouteRules.wayLetGo lets it go;
        // the escape goes on meanwhile)
        long now = mod.getWorld().getGameTime();
        if (adris.altoclef.util.helpers.RouteRules.holdWayOn() && FireRes.holdsRoute(player, nearestBlaze(mod), FireRes.asked(now), now)) return null;
        _lavaThrow = new ThrowAtFeetTask(nearestBlaze(mod));
        Debug.logInternal(String.format(Locale.ROOT, "strategy trade: in lava at %.1f hp, the splash potion thrown at the feet", player.getHealth()));
        return _lavaThrow;
    }

    // Tuning route_way_throw, on by default (route jar 7): on the way (before the spawner fight) a splash or lingering fire
    // resistance potion goes down by the survival chain, over the mob defense, the moment the way's rule lets it go burning
    // out of the lava (RouteRules.wayLetGo). the throw lived in the rods task, and the mob defense held the tick from the
    // first blow to the death: route jar 6's run 3 ran from two wither skeletons and a blaze burning at 8 hp with the splash
    // potion in the bag, the rule's say never asked, and died 7 s later. null when not now
    private static ThrowAtFeetTask _burnThrow;

    public static Task burnThrow(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (player == null || Tuning.get("route_way_throw", 0) <= 0) return null;
        if (_burnThrow != null && _burnThrow.isActive() && !_burnThrow.isFinished(mod)) return _burnThrow;
        _burnThrow = null;
        if (!player.isOnFire() || player.isInLava() || FireRes.has(player) || count(mod, Form.DRINK) > 0 || count(mod, Form.THROW) == 0) return null;
        long now = mod.getWorld().getGameTime();
        double blaze = nearestBlaze(mod);
        if (!FireRes.wayLets(player, blaze, FireRes.asked(now), now)) return null;
        _burnThrow = new ThrowAtFeetTask(blaze);
        Debug.logInternal(String.format(Locale.ROOT, "strategy trade: burning at %.1f hp on the way, the splash potion thrown at the feet over the fight", player.getHealth()));
        return _burnThrow;
    }

    // (route jar 3) a splash potion's throw under way and not yet thrown: the food chain starts no bite meanwhile
    public static boolean throwDue(long now) {
        return ThrowAtFeetTask.due(now);
    }

    // every fire resistance potion of this form in the bag, the hotbar and the offhand
    static int count(AltoClef mod, Form form) {
        if (mod.getPlayer() == null) return 0;
        Inventory inventory = mod.getPlayer().getInventory();
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (FireResTradeRules.form(stack) == form) n += stack.getCount();
        }
        return n;
    }

    private static double nearestBlaze(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        return mod.getEntityTracker().getClosestEntity(player.position(), Blaze.class)
                .map(b -> (double) b.distanceTo(player)).orElse(Double.POSITIVE_INFINITY);
    }

    private static String distance(double d) {
        return FireResTradeRules.distance(d);
    }

    private static boolean admiringNear(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        for (Piglin piglin : mod.getEntityTracker().getTrackedEntities(Piglin.class)) {
            if (piglin.isAlive() && piglin.distanceTo(player) <= PENDING_RANGE && EntityHelper.isTradingPiglin(piglin)) return true;
        }
        return false;
    }

    private static Item goldPieceInBag(AltoClef mod) {
        for (Item piece : new Item[]{Items.GOLDEN_HELMET, Items.GOLDEN_BOOTS, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS}) {
            if (mod.getItemStorage().getItemCount(piece) > 0) return piece;
        }
        return null;
    }

    // the fire resistance potions on the ground near: a line for each new one (the barter's result that matters, at which
    // ingot), one for each that went without reaching the bag; the nearest one still worth picking up
    private Optional<ItemEntity> watchDrops(AltoClef mod, int given, int bagPotions) {
        LocalPlayer player = mod.getPlayer();
        AABB around = player.getBoundingBox().inflate(PICKUP_RANGE);
        Set<Integer> present = new HashSet<>();
        ItemEntity nearest = null;
        for (ItemEntity item : mod.getWorld().getEntitiesOfClass(ItemEntity.class, around)) {
            if (!item.isAlive()) continue;
            Form form = FireResTradeRules.form(item.getItem());
            if (form == Form.NONE) continue;
            present.add(item.getId());
            if (_drops.put(item.getId(), form) == null) {
                Debug.logInternal(String.format(Locale.ROOT, "strategy trade: a fire resistance potion (%s) thrown by a piglin at ingot %d, %.0f blocks off",
                        form == Form.DRINK ? "drinkable" : "splash", given, item.distanceTo(player)));
            }
            if (_dropsGivenUp.contains(item.getId())) continue;
            if (lavaGivenUp(item, "potion")) continue;
            if (nearest == null || item.distanceTo(player) < nearest.distanceTo(player)) nearest = item;
        }
        for (var it = _drops.entrySet().iterator(); it.hasNext(); ) {
            var seen = it.next();
            if (present.contains(seen.getKey())) continue;
            // (out of the box is not gone: a potion on its edge left and came back with each step, a new line each time,
            // 269 times in five minutes on 8 Nov 2026)
            Entity still = mod.getWorld().getEntity(seen.getKey());
            if (still != null && still.isAlive()) continue;
            it.remove();
            if (bagPotions <= _bagPotionsBefore) {
                Debug.logInternal("strategy trade: a fire resistance potion on the ground was lost before the pickup");
            }
        }
        _bagPotionsBefore = bagPotions;
        return Optional.ofNullable(nearest);
    }

    // the nearest ender pearl on the ground within the potion's range that the pickup has not given up on
    private Optional<ItemEntity> nearestPearl(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        ItemEntity nearest = null;
        for (ItemEntity item : mod.getWorld().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(PICKUP_RANGE))) {
            if (!item.isAlive() || !item.getItem().is(Items.ENDER_PEARL) || _dropsGivenUp.contains(item.getId())) continue;
            if (lavaGivenUp(item, "ender pearl")) continue;
            if (nearest == null || item.distanceTo(player) < nearest.distanceTo(player)) nearest = item;
        }
        return Optional.ofNullable(nearest);
    }

    // a drop the pickup will not go for, every spot it could be grabbed from being next to lava (the entity tracker leaves
    // it out, so the pickup wanders and never gives up; the trade then stood still on it until it despawned): given up at
    // once, so the barter goes on
    private boolean lavaGivenUp(ItemEntity item, String what) {
        if (!ItemPickupRules.lavaBlocksPickup(item)) return false;
        // (Tuning route_potion_edge, route jar 7: a potion with a stand by it that has no lava beside it is fetched from there,
        // edgeFetch)
        if (potionEdgeOn() && "potion".equals(what) && edgeStandOf(item) != null) return false;
        _dropsGivenUp.add(item.getId());
        Debug.logInternal("strategy trade: the " + what + " on the ground given up (next to lava, the pickup leaves it)");
        return true;
    }

    // ---- the potion by lava (Tuning route_potion_edge, on by default; route jar 7). route jar 6's run 6: a piglin 3 blocks off
    // threw the splash potion by lava at ingot 57, the pickup left it ("next to lava") and the trade went on until all 64 gold
    // was gone, no potion. now one whose every grab spot is next to lava (ItemPickupRules.lavaBlocksPickup) is fetched by a
    // walk to its stand with no lava beside it (ItemPickupRules.edgeStand); standing there picks it up. no ingot is handed
    // meanwhile (a drop on the ground comes first). given up after EDGE_FETCH_S, or at once with no such stand
    static final double EDGE_FETCH_S = 20;
    private int _edgeItem = -1;
    private long _edgeAt;
    private adris.altoclef.tasks.movement.GetToBlockTask _edgeWalk;
    private BlockPos _edgeStand;

    private static boolean potionEdgeOn() {
        return Tuning.get("route_potion_edge", 0) > 0;
    }

    private static BlockPos edgeStandOf(ItemEntity item) {
        int[] s = ItemPickupRules.edgeStand(ItemPickupRules.terrain(item.level()), ItemPickupRules.lava(item.level()), item.getX(), item.getY(), item.getZ());
        return s == null ? null : new BlockPos(s[0], s[1], s[2]);
    }

    // the walk to a potion by lava, null when the plain pickup takes it (or it is given up now)
    private Task edgeFetch(AltoClef mod, ItemEntity item, long now) {
        if (!potionEdgeOn() || !ItemPickupRules.lavaBlocksPickup(item)) {
            _edgeItem = -1;
            return null;
        }
        if (_edgeItem != item.getId() || now < _edgeAt) {
            _edgeItem = item.getId();
            _edgeAt = now;
            _edgeWalk = null;
            _edgeStand = null;
        }
        BlockPos stand = edgeStandOf(item);
        String why = stand == null ? "no stand without lava beside it any more"
                : now - _edgeAt > EDGE_FETCH_S * 20 ? "not got in " + Math.round(EDGE_FETCH_S) + " s" : null;
        if (why != null) {
            _dropsGivenUp.add(item.getId());
            _edgeItem = -1;
            Debug.logInternal("strategy trade: the potion by lava given up (" + why + ")");
            return null;
        }
        if (!stand.equals(_edgeStand)) {
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: the potion by lava fetched from %s, no lava beside it (%.0f blocks off)",
                    stand.toShortString(), item.distanceTo(mod.getPlayer())));
            _edgeStand = stand;
            _edgeWalk = new adris.altoclef.tasks.movement.GetToBlockTask(stand, false);
        }
        _hud = "Fetching the piglin's fire resistance potion from the lava's edge";
        return _edgeWalk;
    }

    // ---- magma cubes by the trade (Tuning route_trade_cube, the distance; route jar 7, RouteRules.tradeCubeAway): with a cube
    // within the distance the trade hands nothing and walks off the cubes, TRADE_CUBE_RUN from where they stand (the run's
    // goal made again every CUBE_RUN_TICKS as they move), on till none is within the distance and TRADE_CUBE_CLEAR more
    static final long CUBE_RUN_TICKS = 40;
    private boolean _cubeAway;
    private Task _cubeRun;
    private long _cubeRunAt;

    private Task cubeAway(AltoClef mod, long now) {
        double near = RouteRules.tradeCube();
        if (!routeOn() || near <= 0) {
            _cubeAway = false;
            return null;
        }
        LocalPlayer player = mod.getPlayer();
        List<BlockPos> cubes = new ArrayList<>();
        double nearest = Double.POSITIVE_INFINITY;
        for (MagmaCube cube : mod.getEntityTracker().getTrackedEntities(MagmaCube.class)) {
            if (!cube.isAlive()) continue;
            double d = cube.distanceTo(player);
            if (d <= near + RouteRules.TRADE_CUBE_CLEAR + RouteRules.TRADE_CUBE_RUN) cubes.add(cube.blockPosition());
            nearest = Math.min(nearest, d);
        }
        boolean away = RouteRules.tradeCubeAway(_cubeAway, nearest, near);
        if (away != _cubeAway) {
            Debug.logInternal(away ? String.format(Locale.ROOT, "strategy trade: a magma cube %.0f blocks off, walking off the cubes (%d about) before the next ingot", nearest, cubes.size())
                    : "strategy trade: clear of the magma cubes, back to the piglins");
            _cubeAway = away;
            _cubeRun = null;
        }
        if (!away) return null;
        if (_cubeRun == null || _cubeRun.isFinished(mod) || now - _cubeRunAt >= CUBE_RUN_TICKS || now < _cubeRunAt) {
            _cubeRun = new RunAwayFromPositionTask(RouteRules.TRADE_CUBE_RUN, cubes.toArray(new BlockPos[0]));
            _cubeRunAt = now;
        }
        _hud = "Walking off the magma cubes";
        return _cubeRun;
    }

    // one line per ender pearl that came into the bag during the trade, whatever picked it up
    private void watchPearls(int pearls) {
        for (int n = _pearlsSeen + 1; _pearlsSeen >= 0 && n <= pearls; n++) {
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: an ender pearl picked up, %d in the bag", n));
        }
        _pearlsSeen = pearls;
    }

    private static boolean wander() {
        return Tuning.get("fr_trade_wander", 0) > 0;
    }

    private static boolean biomeWalk() {
        return Tuning.get("fr_trade_biome", 0) > 0;
    }

    private boolean walkingToWastes() {
        return biomeWalk() && _toWastes != null && !_wastesSettled;
    }

    // Tuning fr_trade_biome: the walk to the nether wastes while the search is out of them, null once settled (in them, none
    // loaded, or the walk ended out of them): the search then goes as before, its clock counting from here. one walk a trade,
    // to the target picked when it begins; a piglin met on the way is traded with, and the walk goes on after it. the walk
    // itself gets fr_trade_search seconds, so one that gets nowhere does not hold the trade until fr_trade_max
    private Task toWastes(AltoClef mod, long now, BlockPos spawner) {
        if (_wastesSettled) return null;
        LocalPlayer player = mod.getPlayer();
        if (inWastes(mod, player.blockPosition())) {
            settleWastes(now, _toWastes == null ? "strategy trade: in the wastes already, searching here"
                    : String.format(Locale.ROOT, "strategy trade: in the wastes after %s s of walking, %.0f blocks from the start",
                    FireResTradeRules.seconds(_walkTicks), fromStart(player)));
            return null;
        }
        if (_toWastes == null) {
            Spot to = spawner == null ? null : new Spot(spawner.getX(), spawner.getZ());
            Spot target = FireResTradeRules.wastesTarget(wastesChunks(mod, player.getBlockY(), false), player.getX(), player.getZ(), to,
                    knownBastions(mod));
            if (target == null) {
                settleWastes(now, "strategy trade: no wastes in the loaded chunks, searching here");
                return null;
            }
            String extra = to == null ? "unknown" : String.valueOf(FireResTradeRules.extraOnTheWay(player.getX(), player.getZ(), target, to));
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: to the wastes at %d %d, %.0f blocks, extra %s on the way",
                    target.x(), target.z(), target.distanceTo(player.getX(), player.getZ()), extra));
            _toWastes = new GetToXZTask(target.x(), target.z(), WASTES_ARRIVE);
            _wastesAt = new BlockPos(target.x(), player.getBlockY(), target.z());
            // (from here the search clock waits for the arrival)
            _piglinSeenAt = now;
        }
        boolean ended = _toWastes.isFinished(mod) || ++_walkTicks > searchTicks();
        if (ended && !_toWastesMiddle && _walkTicks <= searchTicks()) {
            // the ring is reached from the near side, mostly short of the biome's edge, and at whatever height the path
            // took (nether biomes change with the height): on to the sampled block itself, which is in the wastes; the
            // check above settles the walk as soon as the buddy's feet are in them
            _toWastesMiddle = true;
            _toWastes = new GetWithinRangeOfBlockTask(_wastesAt, WASTES_MIDDLE);
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: at the ring of the wastes target out of them (in %s), on to %s",
                    biomeAt(mod, player.blockPosition()), _wastesAt.toShortString()));
            ended = _toWastes.isFinished(mod);
        }
        if (ended) {
            settleWastes(now, String.format(Locale.ROOT, "strategy trade: the walk to the wastes ended out of them (in %s) after %s s, searching here",
                    biomeAt(mod, player.blockPosition()), FireResTradeRules.seconds(_walkTicks)));
            return null;
        }
        return _toWastes;
    }

    // Tuning route_trade: the search once the wastes walk is over, rings round where it began (the sampled wastes block, or
    // where the buddy stood), RING_STEP further out each (FireResTradeRules.searchPoint). a point is passed over when it lies in
    // a loaded chunk of a biome where no piglin spawns, or near a known bastion (its brutes); one not reached in POINT_TICKS
    // gives way to the next. a piglin met on the way is traded with (the verdict's TRADE comes first), and the search goes on
    // from its point after it. a line per ring begun
    private Task widerSearch(AltoClef mod, long now) {
        LocalPlayer player = mod.getPlayer();
        if (_searchCentre == null) {
            _searchCentre = _wastesAt != null ? _wastesAt : player.blockPosition();
            _searchFrom = Math.atan2(player.getZ() - _searchCentre.getZ(), player.getX() - _searchCentre.getX());
            // (Tuning trade_search_onward, a fix of 13 Nov 2026, on: after a walk to wastes the rings start on the bearing walked,
            // from the trade's start through the wastes and on, not from the buddy at the target back the way it came: a run of
            // route-58 reached the wastes in 9 s, and the first point lay back toward the start, through the basalt deltas)
            if (onward() && Math.hypot(_searchCentre.getX() - _startX, _searchCentre.getZ() - _startZ) > 8) {
                _searchFrom = Math.atan2(_searchCentre.getZ() - _startZ, _searchCentre.getX() - _startX);
            }
            _searchK = 0;
            _searchWalk = null;
            _searchRingSaid = 0;
        }
        if (_searchWalk != null && (_searchWalk.isFinished(mod) || now - _searchWalkAt >= _searchWalkTicks || now < _searchWalkAt)) {
            _searchK++;
            _searchWalk = null;
        }
        if (_searchWalk == null) {
            List<Spot> bastions = knownBastions(mod);
            // (Tuning trade_search_wastes, a fix of 12 Nov 2026, on: at each new point, wastes come into the loaded chunks
            // are walked to first, and the rings start again round them. a basalt deltas start found no wastes loaded at the
            // begin, searched ten minutes 100 to 210 blocks round its start inside the deltas, and the wastes lay 212 blocks
            // off: the eerstedrank measurement's run 28, as 11 runs of the same start before)
            if (adris.altoclef.util.helpers.Tuning.get("trade_search_wastes", 1) > 0 && _searchWastesTries < SEARCH_WASTES_TRIES
                    && !inWastes(mod, player.blockPosition())) {
                Spot w = FireResTradeRules.wastesTarget(wastesChunks(mod, player.getBlockY(), true), player.getX(), player.getZ(), null, bastions);
                if (w != null && (_searchWastesLast == null || w.distanceTo(_searchWastesLast.x(), _searchWastesLast.z()) > SEARCH_WASTES_APART)) {
                    _searchWastesTries++;
                    _searchWastesLast = w;
                    Debug.logInternal(String.format(Locale.ROOT, "strategy trade: wastes in the loaded chunks at %d %d, %.0f blocks, the search goes there and on round them",
                            w.x(), w.z(), w.distanceTo(player.getX(), player.getZ())));
                    // (trade_search_onward: the rings round them start on the side away from where the buddy stands now)
                    if (onward()) _searchFrom = Math.atan2(w.z() - player.getZ(), w.x() - player.getX());
                    _searchCentre = new BlockPos(w.x(), player.getBlockY(), w.z());
                    _searchK = 0;
                    _searchRingSaid = 0;
                    _searchWalk = new GetToXZTask(w.x(), w.z(), WASTES_ARRIVE);
                    _searchWalkAt = now;
                    _searchWalkTicks = FireResTradeRules.pointTicks(w.distanceTo(player.getX(), player.getZ()));
                    return _searchWalk;
                }
            }
            Spot at = null;
            boolean out = adris.altoclef.util.helpers.Tuning.get("trade_search_out", 1) > 0;
            for (int tries = 0; tries < FireResTradeRules.RING_POINTS * FireResTradeRules.RINGS; tries++, _searchK++) {
                Spot s = FireResTradeRules.searchPoint(_searchK, _searchCentre.getX(), _searchCentre.getZ(), _searchFrom);
                if (noPiglinsAt(mod, s, player.getBlockY())) {
                    // (Tuning trade_search_out, a fix of 12 Nov 2026, on: the same bearing further out, ring by ring, to the first
                    // point that is not known to spawn no piglins. a start in the basalt deltas passed over every point of the
                    // first three rings and went round the fourth a point a minute, 122 blocks between them, and never got
                    // more than 126 blocks out in ten minutes: 15 runs ended the trade with all 96 ingots, 11 of them one start)
                    Spot further = null;
                    for (int r = FireResTradeRules.ring(_searchK) + 1; out && further == null && r <= FireResTradeRules.RINGS; r++) {
                        Spot o = FireResTradeRules.searchPointOnRing(_searchK, r, _searchCentre.getX(), _searchCentre.getZ(), _searchFrom);
                        if (!noPiglinsAt(mod, o, player.getBlockY())) further = o;
                    }
                    if (further == null) continue;
                    s = further;
                }
                boolean bastion = false;
                for (Spot b : bastions) bastion |= s.distanceTo(b.x(), b.z()) <= FireResTradeRules.BASTION_KEEP_OFF;
                if (bastion) continue;
                at = s;
                break;
            }
            // (every point passed over: the next one all the same, a walk is better than a stand)
            if (at == null) at = FireResTradeRules.searchPoint(_searchK, _searchCentre.getX(), _searchCentre.getZ(), _searchFrom);
            int ring = FireResTradeRules.ring(_searchK);
            if (ring != _searchRingSaid) {
                _searchRingSaid = ring;
                Debug.logInternal(String.format(Locale.ROOT, "strategy trade: searching wider, ring %d at %.0f blocks round %s, %s s since the last ingot taken",
                        ring, ring * FireResTradeRules.RING_STEP, _searchCentre.toShortString(), FireResTradeRules.seconds(now - _handedAt)));
            }
            _searchWalk = new GetToXZTask(at.x(), at.z(), WASTES_ARRIVE);
            _searchWalkAt = now;
            // (Tuning trade_search_out: the point's time by its distance, a far one through the deltas took more than a minute)
            _searchWalkTicks = adris.altoclef.util.helpers.Tuning.get("trade_search_out", 1) > 0
                    ? FireResTradeRules.pointTicks(at.distanceTo(player.getX(), player.getZ())) : FireResTradeRules.POINT_TICKS;
        }
        return _searchWalk;
    }

    // a column in a loaded chunk whose biome at height y spawns no piglin (piglins spawn in the wastes and crimson forests)
    private static boolean noPiglinsAt(AltoClef mod, Spot s, int y) {
        BlockPos pos = new BlockPos(s.x(), y, s.z());
        if (!mod.getChunkTracker().isChunkLoaded(pos)) return false;
        var biome = mod.getWorld().getBiome(pos);
        return biome.is(Biomes.SOUL_SAND_VALLEY) || biome.is(Biomes.BASALT_DELTAS) || biome.is(Biomes.WARPED_FOREST);
    }

    private void settleWastes(long now, String line) {
        _wastesSettled = true;
        _piglinSeenAt = now;
        Debug.logInternal(line);
    }

    private double fromStart(LocalPlayer player) {
        return Math.hypot(player.getX() - _startX, player.getZ() - _startZ);
    }

    // the biome as the client has it at pos; nether biomes change with the height, so the buddy's own height is the one asked
    private static String biomeAt(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBiome(pos).getRegisteredName();
    }

    private static boolean inWastes(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBiome(pos).is(Biomes.NETHER_WASTES);
    }

    // the middle of every loaded chunk with nether wastes at height y
    // (Tuning trade_wastes_heights, a fix of 13 Nov 2026, on: in the search after the first walk, or 16 or 32 above or below
    // it, inside the nether's floor and roof. nether biomes change with the height: a basalt deltas start of route-58 found no
    // wastes chunk at its own height in ten minutes of search, and wastes lay about 220 blocks east, where another run of that
    // start traded. the first walk keeps the buddy's own height: its last stretch walks to the sampled block at that height)
    private static List<Spot> wastesChunks(AltoClef mod, int y, boolean heights) {
        List<Spot> out = new ArrayList<>();
        int[] dy = heights && Tuning.get("trade_wastes_heights", 0) > 0 ? new int[]{0, 16, -16, 32, -32} : new int[]{0};
        for (ChunkPos chunk : mod.getChunkTracker().getLoadedChunks()) {
            for (int d : dy) {
                int h = y + d;
                if (h < 8 || h > 120) continue;
                BlockPos middle = new BlockPos(chunk.getMiddleBlockX(), h, chunk.getMiddleBlockZ());
                if (inWastes(mod, middle)) {
                    out.add(new Spot(middle.getX(), middle.getZ()));
                    break;
                }
            }
        }
        return out;
    }

    private static boolean onward() {
        return Tuning.get("trade_search_onward", 0) > 0;
    }

    // the bastion blocks the code knows of: the fortress sweep's memory, and what the block tracker holds while it tracks them
    private static List<Spot> knownBastions(AltoClef mod) {
        List<Spot> out = new ArrayList<>();
        for (RunState.Pos p : ObtainSweep.knownBastions()) out.add(new Spot(p.x, p.z));
        if (mod.getBlockTracker().isTracking(NetherPhase.BASTION[0])) {
            for (BlockPos p : mod.getBlockTracker().getKnownLocations(NetherPhase.BASTION)) out.add(new Spot(p.getX(), p.getZ()));
        }
        return out;
    }

    private static long searchTicks() {
        return Math.round(Tuning.get("fr_trade_search", FireResTradeRules.SEARCH_S) * 20);
    }

    private static long maxTicks() {
        return Math.round(Tuning.get("fr_trade_max", FireResTradeRules.MAX_S) * 20);
    }

    // one gold ingot at a time into a piglin's hand: walk into reach, turn the head onto it through the combat aim, and click
    // only when the look the server has is on it within reach with no block in between, at most every CLICK_GAP ticks, never at
    // one already admiring. no piglin is hit meanwhile: the aura and the mob defense leave them all alone
    static final class GiveGoldTask extends AbstractDoToEntityTask {

        // a new piglin is one near the last (as TradeWithPiglinsTask); a hoglin keeps us off a piglin by Tuning
        // route_trade_hoglin (FireResTradeRules.hoglinBars)
        static final double PIGLIN_NEARBY_RADIUS = 10;
        // a piglin taken up that the buddy gets no closer than reach in this long (game ticks of the task's own) is left
        // alone: one across lava or up a cliff stays free to take gold, so known, and would hold the trade's dead end off
        static final long APPROACH_STALL_TICKS = 60 * 20;
        // the walk stops this close and backs off only inside it: in reach is the swing's own reach (inKillReach)
        static final double KEEP_OFF = 1;
        // between two clicks, game ticks: two a second at most
        static final long CLICK_GAP = 10;
        // clicks a piglin may take without ever holding the gold before it is left alone
        static final int REFUSED_CLICKS = 3;

        // a click's ingot shows as the bag's gold going down a few ticks later (the server's slot update); a click not
        // answered within this many game ticks took nothing
        static final long HAND_SEEN_TICKS = 40;

        // a piglin in reach the look cannot land on: a step up to it after this many game ticks, left alone after twice that
        static final long TURN_STALL_TICKS = 60;
        static final double STEP_UP_DISTANCE = 1.5;

        private final Set<Integer> _blacklisted = new HashSet<>();
        private final Map<Integer, Integer> _clicks = new HashMap<>();
        private int _turnId = -1;
        private long _turnSince;
        private long _turnLast;
        private boolean _stepUpLogged;
        private int _currentId = -1;
        private long _clickedAt = Long.MIN_VALUE / 2;
        // the clicks not yet seen to take an ingot, oldest first ({piglin id, game tick}), the bag's gold as last seen, and the
        // ingots handed so far: each ingot gone from the bag goes to the oldest click still waiting
        private final java.util.ArrayDeque<long[]> _unanswered = new java.util.ArrayDeque<>();
        private int _lastGold = -1;
        private int _handed;
        private int _approachId = -1;
        private long _approachTicks;

        GiveGoldTask() {
            super(KEEP_OFF);
        }

        @Override
        protected void onStart(AltoClef mod) {
            super.onStart(mod);
            mod.getBehaviour().push();
            mod.getBehaviour().addProtectedItems(Items.GOLD_INGOT);
            mod.getBehaviour().addForceFieldExclusion(entity -> entity instanceof Piglin);
            mod.getBehaviour().addMobDefenseExclusion(entity -> entity instanceof Piglin);
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            super.onStop(mod, interruptTask);
            mod.getBehaviour().pop();
        }

        @Override
        protected boolean inReach(AltoClef mod, Entity entity) {
            return mod.getControllerExtras().inKillReach(entity);
        }

        boolean tradeable(AltoClef mod, Entity entity) {
            return freeButHoglin(entity) && !hoglinNear(mod, entity) && !cubeNear(mod, entity);
        }

        // (Tuning route_trade_cube_pick, a fix, on by default: a piglin with a live magma cube within the trade's cube distance
        // and its clear margin, RouteRules.tradeCube and TRADE_CUBE_CLEAR, is passed over as one by a hoglin. picked, the walk
        // to it crossed into the cube's distance, cubeAway walked off the cubes and came back once clear: 146 turns in seven
        // minutes with no ingot handed, the piglins 4 blocks from a cube that never moved, route-29-mc3 run 43, 11 Nov 2026)
        private static boolean cubeNear(AltoClef mod, Entity piglin) {
            double near = RouteRules.tradeCube();
            if (near <= 0 || Tuning.get("route_trade_cube_pick", 1) <= 0) return false;
            double bar = near + RouteRules.TRADE_CUBE_CLEAR;
            for (MagmaCube cube : mod.getEntityTracker().getTrackedEntities(MagmaCube.class)) {
                if (cube.isAlive() && cube.distanceTo(piglin) <= bar) return true;
            }
            return false;
        }

        // an adult piglin alive, not left alone and not admiring an ingot: free to take gold but for a hoglin
        private boolean freeButHoglin(Entity entity) {
            return entity instanceof Piglin piglin && piglin.isAlive() && !piglin.isBaby() && !_blacklisted.contains(piglin.getId())
                    && !EntityHelper.isTradingPiglin(piglin) && piglin.getArmPose() != PiglinArmPose.ADMIRING_ITEM;
        }

        boolean leftAlone(int id) {
            return _blacklisted.contains(id);
        }

        private static double hoglinRange() {
            return Tuning.get("route_trade_hoglin", FireResTradeRules.HOGLIN_NEAR);
        }

        // the rule in force, for the lines
        static String hoglinRule() {
            double range = hoglinRange();
            return range > 0 ? String.format(Locale.ROOT, "%.0f of it or of the buddy", range)
                    : String.format(Locale.ROOT, "%.0f of it (the old rule)", FireResTradeRules.HOGLIN_OLD);
        }

        private static double hoglinTo(AltoClef mod, Entity from) {
            return mod.getEntityTracker().getClosestEntity(from.position(), Hoglin.class)
                    .map(h -> (double) h.distanceTo(from)).orElse(Double.POSITIVE_INFINITY);
        }

        private static boolean hoglinNear(AltoClef mod, Entity piglin) {
            double range = hoglinRange();
            // (Tuning route_trade_hoglin_buddy, 0 since a fix of 12 Nov 2026: a hoglin 24 from the piglin but 12 from the buddy
            // kept the trade standing still in WAIT for the whole 1200 s bound, route-49-mc2 run 11; the mob defense takes a
            // hoglin that comes, so only its distance to the piglin bars the piglin)
            boolean byBuddy = range > 0 && adris.altoclef.util.helpers.Tuning.get("route_trade_hoglin_buddy", 0) > 0;
            return FireResTradeRules.hoglinBars(hoglinTo(mod, piglin), byBuddy ? hoglinTo(mod, mod.getPlayer()) : Double.POSITIVE_INFINITY, range);
        }

        // the nearest piglin of the known ones (ids) within KEEP_RANGE that is free but for a hoglin just now, null for none
        Piglin hoglinHeld(AltoClef mod, Set<Integer> known) {
            LocalPlayer player = mod.getPlayer();
            Piglin best = null;
            for (Piglin piglin : mod.getEntityTracker().getTrackedEntities(Piglin.class)) {
                if (!known.contains(piglin.getId()) || !freeButHoglin(piglin) || piglin.distanceTo(player) > FireResTradeRules.KEEP_RANGE
                        || !hoglinNear(mod, piglin)) continue;
                if (best == null || piglin.distanceTo(player) < best.distanceTo(player)) best = piglin;
            }
            return best;
        }

        // the wait's line: the piglin and how far the nearest hoglin is from it and from the buddy
        String hoglinWhy(AltoClef mod, Piglin piglin) {
            if (piglin == null) return "a piglin with a hoglin by it";
            return String.format(Locale.ROOT, "piglin %d at %.0f blocks, a hoglin %s from it and %s from the buddy (the rule %s)", piglin.getId(),
                    piglin.distanceTo(mod.getPlayer()), FireResTradeRules.distance(hoglinTo(mod, piglin)).replace(" off", ""),
                    FireResTradeRules.distance(hoglinTo(mod, mod.getPlayer())).replace(" off", ""), hoglinRule());
        }

        // a piglin taken up to give gold to: left alone after APPROACH_STALL_TICKS with the buddy never in reach of it
        private Optional<Entity> approached(AltoClef mod, Entity piglin) {
            if (inReach(mod, piglin)) {
                _approachId = -1;
                return Optional.of(piglin);
            }
            if (piglin.getId() != _approachId) {
                _approachId = piglin.getId();
                _approachTicks = 0;
            }
            if (++_approachTicks <= APPROACH_STALL_TICKS) return Optional.of(piglin);
            _blacklisted.add(piglin.getId());
            _approachId = -1;
            _currentId = -1;
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: not into reach of piglin %d in %d s, %.0f blocks off, left alone",
                    piglin.getId(), APPROACH_STALL_TICKS / 20, piglin.distanceTo(mod.getPlayer())));
            return Optional.empty();
        }

        private static boolean stayOn() {
            return Tuning.get("fr_trade_stay", 0) > 0;
        }

        // every tick of the trade, with the bag's gold: one line per ingot gone from the bag since the last look, for the
        // piglin of the oldest click still waiting. gold going down with no click waiting (none expected) is not counted
        void watchHanded(int gold, long now) {
            int gone = _lastGold < 0 ? 0 : _lastGold - gold;
            _lastGold = gold;
            while (gone > 0 && !_unanswered.isEmpty()) {
                long[] click = _unanswered.poll();
                _handed++;
                gone--;
                // it took the gold: its count of refusals starts over (the reset on its admiring pose only came when the
                // buddy stayed to look; walking on to the next piglin, a piglin that took every ingot was left alone on
                // its fourth: 29 times in 10 trades of tsure)
                _clicks.remove((int) click[0]);
                Debug.logInternal(String.format(Locale.ROOT, "strategy trade: ingot %d handed to piglin %d", _handed, click[0]));
            }
            _unanswered.removeIf(click -> now - click[1] > HAND_SEEN_TICKS || now < click[1]);
        }

        // the nearest adult piglin and why it takes no gold now, for the line when the last one stops counting
        String whyNone(AltoClef mod) {
            LocalPlayer player = mod.getPlayer();
            Piglin nearest = null;
            for (Piglin piglin : mod.getEntityTracker().getTrackedEntities(Piglin.class)) {
                if (!piglin.isAlive() || piglin.isBaby()) continue;
                if (nearest == null || piglin.distanceTo(player) < nearest.distanceTo(player)) nearest = piglin;
            }
            if (nearest == null) return "no adult piglin tracked";
            String why = _blacklisted.contains(nearest.getId()) ? "left alone"
                    : EntityHelper.isTradingPiglin(nearest) || nearest.getArmPose() == PiglinArmPose.ADMIRING_ITEM ? "admiring"
                    : hoglinNear(mod, nearest) ? "a hoglin within " + hoglinRule()
                    : nearest.distanceTo(player) > FireResTradeRules.KEEP_RANGE ? "out of range" : "free";
            return String.format(Locale.ROOT, "the nearest piglin %d at %.0f blocks, %s", nearest.getId(), nearest.distanceTo(player), why);
        }

        double nearestTradeable(AltoClef mod) {
            LocalPlayer player = mod.getPlayer();
            double best = Double.POSITIVE_INFINITY;
            for (Piglin piglin : mod.getEntityTracker().getTrackedEntities(Piglin.class)) {
                if (tradeable(mod, piglin)) best = Math.min(best, piglin.distanceTo(player));
            }
            return best;
        }

        int tradeableCount(AltoClef mod) {
            LocalPlayer player = mod.getPlayer();
            int n = 0;
            for (Piglin piglin : mod.getEntityTracker().getTrackedEntities(Piglin.class)) {
                if (tradeable(mod, piglin) && piglin.distanceTo(player) <= FireResTradeRules.KEEP_RANGE) n++;
            }
            return n;
        }

        @Override
        protected Optional<Entity> getEntityTarget(AltoClef mod) {
            LocalPlayer player = mod.getPlayer();
            Entity current = _currentId < 0 ? null : mod.getWorld().getEntity(_currentId);
            boolean gold = mod.getItemStorage().getItemCount(Items.GOLD_INGOT) > 0;
            if (gold && stayOn()) {
                // (Tuning fr_trade_stay: the current piglin keeps the gold while it is there; another free one only close by,
                // FireResTradeRules.pick)
                Piglin held = current instanceof Piglin p && p.isAlive() ? p : null;
                Optional<Entity> other = mod.getEntityTracker().getClosestEntity(player.position(), entity -> tradeable(mod, entity)
                        && entity != current && entity.distanceTo(player) <= FireResTradeRules.KEEP_RANGE, Piglin.class);
                FireResTradeRules.Pick pick = FireResTradeRules.pick(true, held != null,
                        held != null && _blacklisted.contains(held.getId()), held != null && hoglinNear(mod, held),
                        held == null ? Double.POSITIVE_INFINITY : held.distanceTo(player), held != null && tradeable(mod, held),
                        other.map(entity -> (double) entity.distanceTo(player)).orElse(Double.POSITIVE_INFINITY));
                if (pick == FireResTradeRules.Pick.STAY) return approached(mod, current);
                if (pick == FireResTradeRules.Pick.OTHER) {
                    _currentId = other.get().getId();
                    return approached(mod, other.get());
                }
            }
            if (gold) {
                // the nearest one free to take gold, near the last one first so the throws all land by us
                Optional<Entity> found = mod.getEntityTracker().getClosestEntity(player.position(), entity -> tradeable(mod, entity)
                        && entity.distanceTo(player) <= FireResTradeRules.KEEP_RANGE
                        && (current == null || !current.isAlive() || entity.closerThan(current, PIGLIN_NEARBY_RADIUS)), Piglin.class);
                if (found.isEmpty()) {
                    found = mod.getEntityTracker().getClosestEntity(player.position(), entity -> tradeable(mod, entity)
                            && entity.distanceTo(player) <= FireResTradeRules.KEEP_RANGE, Piglin.class);
                }
                if (found.isPresent()) {
                    _currentId = found.get().getId();
                    return approached(mod, found.get());
                }
            }
            // none free: stay by the one admiring our ingot, its loot comes our way
            if (current instanceof Piglin piglin && piglin.isAlive() && EntityHelper.isTradingPiglin(piglin)) return Optional.of(current);
            return mod.getEntityTracker().getClosestEntity(player.position(), entity -> entity.isAlive()
                    && EntityHelper.isTradingPiglin(entity) && entity.distanceTo(player) <= PENDING_RANGE, Piglin.class);
        }

        @Override
        protected Task onEntityInteract(AltoClef mod, Entity entity) {
            LocalPlayer player = mod.getPlayer();
            long now = mod.getWorld().getGameTime();
            if (!(entity instanceof Piglin piglin)) return null;
            if (EntityHelper.isTradingPiglin(piglin) || piglin.getArmPose() == PiglinArmPose.ADMIRING_ITEM) {
                // it took the gold: its count of refusals starts over
                _clicks.remove(piglin.getId());
                // (Tuning fr_trade_stay: the buddy waits by it looking at it, no click, so the next ingot goes the moment it is free)
                if (stayOn()) mod.getControllerExtras().aimAt(piglin, CombatAim.Priority.KILL_TASK);
                setDebugState("A piglin admiring the gold");
                return null;
            }
            if (!mod.getSlotHandler().forceEquipItem(Items.GOLD_INGOT)) {
                setDebugState("Gold in hand");
                return null;
            }
            mod.getControllerExtras().aimAt(piglin, CombatAim.Priority.KILL_TASK);
            if (now - _clickedAt < CLICK_GAP && now >= _clickedAt) return null;
            if (!mod.getControllerExtras().onTarget(piglin) || Minecraft.getInstance().gui.screen() != null) {
                // in reach by distance, but the look never lands on it (a block in between): after TURN_STALL_TICKS a step up
                // to it, after twice that it is left alone (a buddy stood 500 s turning to one piglin, gold in hand)
                // (a new piglin, or the turning broken off a second or more: the clock starts again)
                if (piglin.getId() != _turnId || now - _turnLast > 20 || now < _turnLast) {
                    _turnId = piglin.getId();
                    _turnSince = now;
                    _stepUpLogged = false;
                }
                _turnLast = now;
                long turning = now - _turnSince;
                if (turning > 2 * TURN_STALL_TICKS) {
                    _blacklisted.add(piglin.getId());
                    _turnId = -1;
                    Debug.logInternal("strategy trade: no clear click on a piglin for " + (2 * TURN_STALL_TICKS / 20) + " s, left alone");
                    return null;
                }
                if (turning > TURN_STALL_TICKS && piglin.distanceTo(player) > STEP_UP_DISTANCE) {
                    if (!_stepUpLogged) {
                        _stepUpLogged = true;
                        Debug.logInternal("strategy trade: no clear click on a piglin for " + (TURN_STALL_TICKS / 20) + " s, stepping up to it");
                    }
                    setDebugState("Stepping up to the piglin");
                    return new GetToEntityTask(piglin, STEP_UP_DISTANCE);
                }
                setDebugState("Turning to the piglin");
                return null;
            }
            _turnId = -1;
            _stepUpLogged = false;
            // the point the server's look meets the piglin's box, as the crosshair would send it
            Rotation look = mod.getClientBaritone().getLookBehavior().serverRotation()
                    .orElseGet(() -> new Rotation(player.getYRot(), player.getXRot()));
            Vec3 eye = player.getEyePosition();
            Vec3 dir = Vec3.directionFromRotation(look.getPitch(), look.getYaw());
            AABB box = piglin.getBoundingBox().inflate(piglin.getPickRadius());
            Vec3 at = box.contains(eye) ? eye : box.clip(eye, eye.add(dir.scale(mod.getControllerExtras().swingReach()))).orElse(null);
            if (at == null) return null;
            _clickedAt = now;
            _unanswered.add(new long[]{piglin.getId(), now});
            InteractionResult result = mod.getController().interact(player, piglin, new EntityHitResult(piglin, at), InteractionHand.MAIN_HAND);
            // vanilla swings the hand for a click the client saw take (Minecraft.startUseItem)
            if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
                player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getInteractAnimation(), false);
            }
            int clicks = _clicks.merge(piglin.getId(), 1, Integer::sum);
            if (clicks > REFUSED_CLICKS) {
                _blacklisted.add(piglin.getId());
                _clicks.remove(piglin.getId());
                Debug.logInternal("strategy trade: a piglin took no gold in " + REFUSED_CLICKS + " clicks, left alone");
            }
            setDebugState("Giving a piglin gold");
            return null;
        }

        @Override
        protected boolean isSubEqual(AbstractDoToEntityTask other) {
            return other instanceof GiveGoldTask;
        }

        @Override
        protected String toHudString() {
            return "Trading gold with piglins";
        }

        @Override
        protected String toDebugString() {
            return "Giving piglins gold for fire resistance";
        }
    }

    // a splash fire resistance potion thrown at the feet: in hand, the head down through the look helper, the path paused for
    // it, then one right click. done when the effect is on or a second after the throw
    static final class ThrowAtFeetTask extends Task {

        // the pitch the head must have come down to: thrown from here the potion lands by the feet, in full strength
        static final float THROW_PITCH = 80;
        static final long SETTLE_TICKS = 20;

        private final double _blaze;
        private long _thrownAt = -1;

        ThrowAtFeetTask(double blaze) {
            _blaze = blaze;
        }

        @Override
        protected void onStart(AltoClef mod) {
            _thrownAt = -1;
        }

        @Override
        protected Task onTick(AltoClef mod) {
            LocalPlayer player = mod.getPlayer();
            long now = mod.getWorld().getGameTime();
            if (_thrownAt >= 0) {
                setDebugState("Waiting for the splash");
                return null;
            }
            Optional<Slot> slot = throwSlot(mod);
            if (slot.isEmpty()) return null;
            // (route jar 3: the bite stops for the throw, FoodChain.eatingBlocked, and the potion comes into the hand only with
            // the use key up. swapped in under a bite's held key, the game threw it ahead at eye level, run 1 of route jar 2:
            // the potion gone with no line and the buddy burning on)
            _dueAt = now;
            if (player.isUsingItem() || mod.getInputControls().isHeldDown(Input.CLICK_RIGHT)) {
                if (player.isUsingItem() && FireResTradeRules.form(player.getUseItem()) != Form.THROW) mod.getController().releaseUsingItem(player);
                mod.getInputControls().release(Input.CLICK_RIGHT);
                setDebugState("Letting go of the use key to throw");
                return null;
            }
            if (FireResTradeRules.form(player.getMainHandItem()) != Form.THROW) {
                mod.getSlotHandler().forceEquipSlot(slot.get());
                setDebugState("The splash potion in hand");
                return null;
            }
            mod.getClientBaritone().getPathingBehavior().requestPause();
            LookHelper.lookAt(mod, new Rotation(player.getYRot(), 90));
            if (player.getXRot() < THROW_PITCH || player.isUsingItem() || mod.getFoodChain().isTryingToEat()
                    || Minecraft.getInstance().gui.screen() != null) {
                setDebugState("Looking down to throw");
                return null;
            }
            mod.getInputControls().tryPress(Input.CLICK_RIGHT);
            _thrownAt = now;
            Debug.logInternal("strategy trade: the splash potion thrown at the feet, nearest blaze " + distance(_blaze)
                    + (player.isOnFire() ? ", on fire" : ""));
            return null;
        }

        private static Optional<Slot> throwSlot(AltoClef mod) {
            List<Slot> slots = mod.getItemStorage().getSlotsWithItemScreen(Items.SPLASH_POTION, Items.LINGERING_POTION);
            for (Slot slot : slots) {
                if (FireResTradeRules.form(StorageHelper.getItemStackInSlot(slot)) == Form.THROW) return Optional.of(slot);
            }
            return Optional.empty();
        }

        // (route jar 3) stopped before the throw with the potion in hand, the hand goes to the sword (else the pickaxe or an empty
        // slot): the next right click of anything else would throw it
        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            _dueAt = Long.MIN_VALUE / 2;
            LocalPlayer player = mod.getPlayer();
            if (_thrownAt >= 0 || player == null || FireResTradeRules.form(player.getMainHandItem()) != Form.THROW) return;
            int away = adris.altoclef.control.SlotHandler.nonFoodSlot(player.getInventory());
            if (away >= 0) player.getInventory().setSelectedSlot(away);
        }

        // a throw under way and not yet thrown, stamped each tick: the food chain starts no bite meanwhile
        private static volatile long _dueAt = Long.MIN_VALUE / 2;

        public static boolean due(long now) {
            return now >= _dueAt && now - _dueAt <= 2;
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            if (_thrownAt < 0) return throwSlot(mod).isEmpty();
            long now = mod.getWorld().getGameTime();
            return mod.getPlayer().hasEffect(MobEffects.FIRE_RESISTANCE) || now - _thrownAt >= SETTLE_TICKS || now < _thrownAt;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }

        @Override
        protected String toHudString() {
            return "Throwing fire resistance at the feet";
        }

        @Override
        protected String toDebugString() {
            return "Throwing a splash fire resistance potion at the feet";
        }
    }
}
