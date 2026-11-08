package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.AbstractDoToEntityTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
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

// the buddy's fork, Tuning fr_trade, off by default, steering 89: the first fire resistance bartered from piglins before the
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
final class FireResTrade {

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

    // the trade's step this tick: a task to run, or null for the blaze rods' own way (the walk to the fortress while no piglin
    // is in sight, the fight when a blaze is near, everything once the trade has ended)
    // fight: whether the blaze fight would take a blaze within the given blocks now (BlazeFight.takesWithin)
    Task tick(AltoClef mod, BlockPos spawner, java.util.function.DoublePredicate fight) {
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        int drink = count(mod, Form.DRINK);
        int thrown = count(mod, Form.THROW);
        boolean potion = drink + thrown > 0;
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
            String why = FireResTradeRules.whyNot(gold, potion, mod.getItemStorage().getItemCount(Items.BLAZE_ROD));
            if (why == null && (atFortress || blazeNear)) why = "at the fortress already";
            if (why != null) {
                if (now - _firstLookAt >= BEGIN_GRACE_TICKS || now < _firstLookAt) {
                    Debug.logInternal("strategy trade: not begun (" + why + ")");
                    _state = State.ENDED;
                }
                return null;
            }
            _state = State.BEGUN;
            _begunAt = now;
            _goldAtBegin = gold;
            _piglinSeenAt = now;
            _bagPotionsBefore = 0;
            _startX = player.getX();
            _startZ = player.getZ();
            Debug.logInternal("strategy trade: biome at start " + biomeAt(mod, player.blockPosition()));
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: begun, %d gold (a potion by the last ingot %.0f%%), rods %d, search %s s, cap %s s, %s",
                    gold, 100 * FireResTradeRules.chanceWithin(gold), mod.getItemStorage().getItemCount(Items.BLAZE_ROD),
                    FireResTradeRules.seconds(searchTicks()), FireResTradeRules.seconds(maxTicks()), wander() ? "wandering for piglins" : "on the way to the fortress meanwhile"));
        }

        int given = Math.max(0, _goldAtBegin - gold);
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
        FireResTradeRules.Verdict verdict = FireResTradeRules.decide(potion, gold, pending, worn || piece != null, now - _begunAt,
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
                        + " seconds (" + form + ", " + gold + " gold left)");
                end(player, now, true, drink, thrown);
                FireRes.traded(now, spawner);
                return null;
            }
            case GIVE_UP -> {
                Debug.logInternal("strategy trade: given up (" + verdict.reason() + ") after " + given + " ingots in "
                        + FireResTradeRules.seconds(now - _begunAt) + " seconds, " + gold + " gold left");
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
            case SEARCH -> {
                _yieldLogged = false;
                if (!_searchLogged) {
                    _searchLogged = true;
                    Debug.logInternal("strategy trade: no piglin in sight, " + (wander() ? "wandering for one" : "on the way to the fortress meanwhile"));
                }
                // (Tuning fr_trade_biome: out of the wastes, first the walk to them)
                Task walk = biomeWalk() ? toWastes(mod, now, spawner) : null;
                if (walk != null) {
                    _hud = "Walking to the nether wastes for piglins";
                    return walk;
                }
                if (!wander()) return null;
                if (_wander == null) _wander = new TimeoutWanderTask(false);
                _hud = "Looking for piglins to trade with";
                return _wander;
            }
            default -> {
                _yieldLogged = false;
                _searchLogged = false;
            }
        }
        // TRADE. piglins attack a player with no gold on
        if (!worn) {
            if (_equip == null) _equip = new EquipArmorTask(piece);
            _hud = "Putting on gold for the piglins";
            return _equip;
        }
        _equip = null;
        // what a piglin threw first: a potion on the ground can burn in lava or be out of reach in a moment
        if (drop.isPresent()) {
            boolean pearl = drop.get().getItem().is(Items.ENDER_PEARL);
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
                || FireRes.holdsAny(player, blaze, asked, now)) return null;
        _throw = new ThrowAtFeetTask(blaze);
        return _throw;
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
        _dropsGivenUp.add(item.getId());
        Debug.logInternal("strategy trade: the " + what + " on the ground given up (next to lava, the pickup leaves it)");
        return true;
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
            Spot target = FireResTradeRules.wastesTarget(wastesChunks(mod, player.getBlockY()), player.getX(), player.getZ(), to,
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
    private static List<Spot> wastesChunks(AltoClef mod, int y) {
        List<Spot> out = new ArrayList<>();
        for (ChunkPos chunk : mod.getChunkTracker().getLoadedChunks()) {
            BlockPos middle = new BlockPos(chunk.getMiddleBlockX(), y, chunk.getMiddleBlockZ());
            if (inWastes(mod, middle)) out.add(new Spot(middle.getX(), middle.getZ()));
        }
        return out;
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

        // as TradeWithPiglinsTask: a hoglin this near a piglin keeps us off it, and a new piglin is one near the last
        static final double HOGLIN_AVOID_TRADE_RADIUS = 64;
        static final double PIGLIN_NEARBY_RADIUS = 10;
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
            if (!(entity instanceof Piglin piglin) || !piglin.isAlive() || piglin.isBaby() || _blacklisted.contains(piglin.getId())
                    || EntityHelper.isTradingPiglin(piglin) || piglin.getArmPose() == PiglinArmPose.ADMIRING_ITEM) {
                return false;
            }
            return !hoglinNear(mod, piglin);
        }

        private static boolean hoglinNear(AltoClef mod, Entity piglin) {
            Optional<Entity> hoglin = mod.getEntityTracker().getClosestEntity(piglin.position(), Hoglin.class);
            return hoglin.isPresent() && hoglin.get().closerThan(piglin, HOGLIN_AVOID_TRADE_RADIUS);
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
                    : hoglinNear(mod, nearest) ? String.format(Locale.ROOT, "a hoglin within %.0f of it", HOGLIN_AVOID_TRADE_RADIUS)
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
                if (pick == FireResTradeRules.Pick.STAY) return Optional.of(current);
                if (pick == FireResTradeRules.Pick.OTHER) {
                    _currentId = other.get().getId();
                    return other;
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
                    return found;
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

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
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
