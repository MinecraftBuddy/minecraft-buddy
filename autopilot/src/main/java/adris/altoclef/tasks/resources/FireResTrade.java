package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.AbstractDoToEntityTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.FireRes;
import adris.altoclef.util.helpers.FireResRules;
import adris.altoclef.util.helpers.FireResTradeRules;
import adris.altoclef.util.helpers.FireResTradeRules.Form;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.Tuning;
import adris.altoclef.util.slots.Slot;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.CombatAim;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
// none, and is a pearl and obsidian ResourceTask whose finish counts any potion, a water bottle too
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
    private int _progressLogged;
    private String _hud = "Trading gold with piglins for fire resistance";
    // the fire resistance potions seen on the ground (entity id), and the ones the pickup gave up on
    private final Map<Integer, Form> _drops = new HashMap<>();
    private final Set<Integer> _dropsGivenUp = new HashSet<>();
    private int _bagPotionsBefore;
    private final GiveGoldTask _give = new GiveGoldTask();
    private PickupDroppedItemTask _pickup;
    private TimeoutWanderTask _wander;
    private Task _equip;
    // after the trade: whether it got its potion, then the way to the blazes and the drink there
    private boolean _succeeded;
    private long _endedAt;
    private boolean _arrivalLogged;
    private boolean _drinkLogged;
    private boolean _hadEffect;
    private int _lastDrink;
    private int _lastThrow;
    private ThrowAtFeetTask _throw;

    String hud() {
        return _hud;
    }

    boolean live() {
        return _state != State.ENDED;
    }

    // the trade's step this tick: a task to run, or null for the blaze rods' own way (the walk to the fortress while no piglin
    // is in sight, the fight when a blaze is near, everything once the trade has ended)
    Task tick(AltoClef mod, BlockPos spawner) {
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
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: begun, %d gold (a potion by the last ingot %.0f%%), rods %d, search %s s, cap %s s, %s",
                    gold, 100 * FireResTradeRules.chanceWithin(gold), mod.getItemStorage().getItemCount(Items.BLAZE_ROD),
                    FireResTradeRules.seconds(searchTicks()), FireResTradeRules.seconds(maxTicks()), wander() ? "wandering for piglins" : "on the way to the fortress meanwhile"));
        }

        int given = Math.max(0, _goldAtBegin - gold);
        if (gold <= 0 && _goldOutAt < 0) _goldOutAt = now;
        if (gold > 0) _goldOutAt = -1;
        Optional<ItemEntity> drop = watchDrops(mod, given, drink + thrown);
        boolean admiring = admiringNear(mod);
        boolean pending = admiring || drop.isPresent();
        double piglin = _give.nearestTradeable(mod);
        _piglinNear = FireResTradeRules.piglinNear(_piglinNear, piglin);
        if (_piglinNear || admiring) {
            if (!_piglinLogged && _piglinNear) {
                _piglinLogged = true;
                Debug.logInternal(String.format(Locale.ROOT, "strategy trade: a piglin found after %s s, %.0f blocks off",
                        FireResTradeRules.seconds(now - _begunAt), piglin));
            }
            _piglinSeenAt = now;
        }
        boolean worn = PiglinAi.isWearingSafeArmor(player);
        Item piece = goldPieceInBag(mod);
        FireResTradeRules.Verdict verdict = FireResTradeRules.decide(potion, gold, pending, worn || piece != null, now - _begunAt,
                _goldOutAt < 0 ? Long.MAX_VALUE : now - _goldOutAt, now - _piglinSeenAt, _piglinNear, atFortress, blazeNear,
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
            if (_pickup == null) {
                _pickup = new PickupDroppedItemTask(new ItemTarget[]{new ItemTarget(Items.POTION, 64), new ItemTarget(Items.SPLASH_POTION, 64),
                        new ItemTarget(Items.LINGERING_POTION, 64)}, true);
            }
            if (_pickup.gaveUpReason() != null) {
                Debug.logInternal("strategy trade: the potion on the ground given up (" + _pickup.gaveUpReason() + ")");
                _dropsGivenUp.add(drop.get().getId());
                _pickup = null;
                return null;
            }
            _hud = "Picking up the piglin's fire resistance potion";
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
        if (FireRes.holdsTraded(player, blaze, asked, now) || FireRes.holdsForBox(player, blaze, asked, now)) return null;
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
            if (nearest == null || item.distanceTo(player) < nearest.distanceTo(player)) nearest = item;
        }
        for (var it = _drops.entrySet().iterator(); it.hasNext(); ) {
            var seen = it.next();
            if (present.contains(seen.getKey())) continue;
            it.remove();
            if (bagPotions <= _bagPotionsBefore) {
                Debug.logInternal("strategy trade: a fire resistance potion on the ground was lost before the pickup");
            }
        }
        _bagPotionsBefore = bagPotions;
        return Optional.ofNullable(nearest);
    }

    private static boolean wander() {
        return Tuning.get("fr_trade_wander", 0) > 0;
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

        private final Set<Integer> _blacklisted = new HashSet<>();
        private final Map<Integer, Integer> _clicks = new HashMap<>();
        private int _currentId = -1;
        private long _clickedAt = Long.MIN_VALUE / 2;

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
            Optional<Entity> hoglin = mod.getEntityTracker().getClosestEntity(piglin.position(), Hoglin.class);
            return hoglin.isEmpty() || !hoglin.get().closerThan(piglin, HOGLIN_AVOID_TRADE_RADIUS);
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
                setDebugState("Turning to the piglin");
                return null;
            }
            // the point the server's look meets the piglin's box, as the crosshair would send it
            Rotation look = mod.getClientBaritone().getLookBehavior().serverRotation()
                    .orElseGet(() -> new Rotation(player.getYRot(), player.getXRot()));
            Vec3 eye = player.getEyePosition();
            Vec3 dir = Vec3.directionFromRotation(look.getPitch(), look.getYaw());
            AABB box = piglin.getBoundingBox().inflate(piglin.getPickRadius());
            Vec3 at = box.contains(eye) ? eye : box.clip(eye, eye.add(dir.scale(mod.getControllerExtras().swingReach()))).orElse(null);
            if (at == null) return null;
            _clickedAt = now;
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
