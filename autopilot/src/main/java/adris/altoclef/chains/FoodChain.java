package adris.altoclef.chains;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.Debug;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.speedrun.DragonBreathTracker;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.*;
import adris.altoclef.util.slots.PlayerSlot;
import baritone.api.utils.input.Input;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import baritone.api.utils.Pair;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import adris.altoclef.util.slots.Slot;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class FoodChain extends SingleTaskChain {
    private static FoodChainConfig _config;
    private static boolean _hasFood;

    static {
        ConfigHelper.loadConfig("configs/food_chain_settings.json", FoodChainConfig::new, FoodChainConfig.class, newConfig -> _config = newConfig);
    }

    private final DragonBreathTracker _dragonBreathTracker = new DragonBreathTracker();
    private boolean _isTryingToEat = false;
    private boolean _requestFillup = false;
    private boolean _needsFood = false;
    private Optional<Item> _cachedPerfectFood = Optional.empty();
    private static final int FOOD_RECALC_TICKS = 10;
    private int _cachedFoodScore = 0;
    private int _foodCalcAge = FOOD_RECALC_TICKS;
    private int _lastFoodHunger = -1;
    private float _lastFoodSaturation = -1;
    private float _lastFoodHealth = -1;
    private boolean shouldStop = false;
    private boolean _eatBlocked = false;
    // where the fillup latch stops. a full bar for any meal the older rules asked for, the regen line for one that only the heal
    // rule wanted (hurt, hunger under 18): that is the point of the meal, the rest is food spent for nothing
    private int _fillTo = 20;
    // what we are chewing and how things stood when the bite started, so the log line can say what a meal was for
    private Item _bite;
    // the food the current meal picked, see FoodSelector.latch
    private Item _mealFood;
    private int _biteCount = -1;
    private int _biteHunger;
    private float _biteSaturation;
    private float _biteHealth;
    // BiteRules has the rules, these are what the chain remembers between ticks: whether we hold the use key, whether the
    // meal began standing (and so the pause is ours until stopEat), and the counters for the log lines
    private final BiteRules.Waiting _waiting = new BiteRules.Waiting();
    private final BiteRules.UseWatch _useWatch = new BiteRules.UseWatch();
    private boolean _biting = false;
    private boolean _standing = false;
    // the starving escape is on: this meal is eaten on the move, no matter what the table says, until stopEat
    private boolean _onTheMove = false;
    // the last hit by something with a body (a mob, an arrow), and when. getLastDamageSource forgets after 40 ticks and
    // is replaced by a poison tick, this keeps the hit. a fresh source object is a fresh hit, same as the combat log goes by
    private DamageSource _hitSource;
    private long _hitAt = Long.MIN_VALUE;
    // the same, minus arrows and fireballs: only a hit in contact ends a standing meal (BiteRules.contactHit)
    private long _contactHitAt = Long.MIN_VALUE;
    private LocalPlayer _hitPlayer;
    // the rule said not now this tick (somebody just hit us): needsToEat() keeps its mouth shut about it
    private boolean _notNow = false;
    // the "use did not start" line is for the first press of a stuck bite, not every lap of the back off
    private boolean _stuckLogged = false;

    private final AltoClef _mod;

    public FoodChain(TaskRunner runner) {
        super(runner);
        _mod = runner.getMod();
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // Nothing.
    }

    // once a tick that the eat branch wants to chew. BiteRules says whether that is now: outside a fight a bite only starts
    // standing, because chewing is a 0.2 move multiplier and no movement knows about it
    private void tryEat(AltoClef mod, Item food, boolean avoidInteractable) {
        if (_useWatch.backingOff()) {
            // the use would not start, leave it be for a bit (the path walks on, the key is up)
            stopEat(mod);
            return;
        }
        // a last meal that is still putting the shield back is not a bite, finish that before we judge anything
        if (_isTryingToEat && !_biting) {
            stopEat(mod);
        }
        BiteRules.Scene scene = scene(mod);
        BiteRules.Verdict verdict = BiteRules.verdict(scene);
        if (verdict == BiteRules.Verdict.WAIT) {
            verdict = starvingEscape(mod, scene);
        }
        boolean waiting = verdict == BiteRules.Verdict.ASK_STOP || verdict == BiteRules.Verdict.WAIT;
        String line = _waiting.tick(verdict, waiting ? BiteRules.why(scene) : null);
        if (line != null) {
            Debug.logInternal(line);
        }
        switch (verdict) {
            // nothing but the pause: a slot swap now would fight the tool the path is using, and the use key would
            // freeze the clicks it needs. the latch is only granted at a tick where the movement says it is safe
            case ASK_STOP -> mod.getClientBaritone().getPathingBehavior().requestPause();
            case NOT_NOW -> {
                // key up, muzzle off, no pause: the path moves this very tick, and the fight code decides what happens next
                _notNow = true;
                stopEat(mod);
            }
            case WAIT -> {
                // a container left open is how the old code ate too: looking at the screen closes it (or puts the cursor
                // away), and then the bite can start. the player's own inventory is the task's to close
                if (scene.screen() && !(mod.getPlayer().containerMenu instanceof InventoryMenu)) {
                    LookHelper.tryAvoidingInteractable(mod);
                }
            }
            case EAT_NOW -> {
                // asked again every tick of the meal, like the shield does, so one stop covers every bite of a fillup.
                // with nothing pathing there is nothing to pause, and a latch with nobody to grant it just sits there
                // until some later path trips over it
                _standing = true;
                if (scene.pathLive()) mod.getClientBaritone().getPathingBehavior().requestPause();
                if (avoidInteractable) LookHelper.tryAvoidingInteractable(mod);
                startEat(mod, food);
            }
            case CHEW_ON_THE_MOVE -> {
                // the fight owns the wheel and the legs. no pause here, ever: it must not hold us still under a mob
                _standing = false;
                if (avoidInteractable) LookHelper.tryAvoidingInteractable(mod);
                startEat(mod, food);
            }
        }
    }

    // a swim or a boat ride can outlast any hunger bar. after long enough of that and starving, the bite goes ahead on the
    // move, the same way a fight's does (the combat path of the rule). never in the air or on a ladder, those end soon
    private BiteRules.Verdict starvingEscape(AltoClef mod, BiteRules.Scene scene) {
        LocalPlayer player = mod.getPlayer();
        String why = BiteRules.why(scene);
        int hunger = player.getFoodData().getFoodLevel();
        if (!BiteRules.starvingEscape(_waiting.ticks(), hunger, player.getHealth(), why)) {
            return BiteRules.Verdict.WAIT;
        }
        _onTheMove = true;
        Debug.logInternal("food: no safe stop for " + _waiting.ticks() + "t, eating on the move (" + why + ")");
        return BiteRules.Verdict.CHEW_ON_THE_MOVE;
    }

    private void noteHit(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (player == null) return;
        if (player != _hitPlayer) {
            _hitPlayer = player;
            _hitSource = null;
            _hitAt = Long.MIN_VALUE;
            _contactHitAt = Long.MIN_VALUE;
        }
        DamageSource source = player.getLastDamageSource();
        if (source == null || source == _hitSource) return;
        _hitSource = source;
        long now = mod.getWorld().getGameTime();
        boolean attacker = source.getEntity() != null;
        boolean direct = source.getDirectEntity() != null;
        if (BiteRules.entityHit(attacker, direct)) {
            _hitAt = now;
        }
        if (BiteRules.contactHit(attacker, direct, source.getDirectEntity() instanceof Projectile)) {
            _contactHitAt = now;
        }
    }

    // ticks since a mob or an arrow last hit us, Integer.MAX_VALUE for never. the nether death trip's walk reads it
    public int ticksSinceBodyHit(AltoClef mod) {
        return age(mod, _hitAt);
    }

    // the bite's own clock: arrows and fireballs left out
    private int hitAge(AltoClef mod) {
        return age(mod, _contactHitAt);
    }

    // a bite is going (the shield asks, it does not want to yank the food out of the hand under it)
    public boolean biting() {
        return _biting;
    }

    private static int age(AltoClef mod, long at) {
        if (at == Long.MIN_VALUE) return BiteRules.NO_HIT;
        return (int) Math.max(Math.min(mod.getWorld().getGameTime() - at, BiteRules.NO_HIT - 1L), Integer.MIN_VALUE);
    }

    private BiteRules.Scene scene(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        Baritone baritone = mod.getClientBaritone();
        // a search that has not landed yet counts: the bot is standing between segments and about to walk
        boolean hasCurrent = baritone.getPathingBehavior().getCurrent() != null;
        boolean pathLive = hasCurrent || baritone.getPathingBehavior().getInProgress().isPresent();
        MobDefenseChain defense = mod.getMobDefenseChain();
        // a committed run or fight, or a stance that is not calm (the quick bite and the gapple live there)
        boolean combat = defense != null && (defense.combatMode() != CombatCommit.Mode.NONE || stance(mod) != CombatRules.Stance.CALM);
        // (an escape in progress rides the same road as a fight's meal until it is done: no pause, no standing)
        return new BiteRules.Scene(combat || _onTheMove, _biting, Minecraft.getInstance().gui.screen() != null, player.onGround(), player.isInWater(),
                player.onClimbable(), player.isPassenger(), player.getDeltaMovement().horizontalDistance(), pathLive,
                hasCurrent && !baritone.getPathingBehavior().isPathing(), baritone.getPathingBehavior().isSafeToCancel(), hitAge(mod));
    }

    // a potion that gives fire resistance, short or long
    static boolean isFireRes(ItemStack stack) {
        if (!stack.is(Items.POTION)) return false;
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return false;
        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (effect.is(MobEffects.FIRE_RESISTANCE)) return true;
        }
        return false;
    }

    private static Optional<Slot> fireResSlot(AltoClef mod) {
        for (Slot slot : mod.getItemStorage().getSlotsWithItemScreen(Items.POTION)) {
            if (isFireRes(StorageHelper.getItemStackInSlot(slot))) return Optional.of(slot);
        }
        return Optional.empty();
    }

    // in lava with a fire resistance drink that is to go down: the shields are lowered for it (the drink waits while
    // blocking, and the shield keys are not ours to let go: a buddy in lava with the potion in the bag never drank)
    public boolean drinkInLava(AltoClef mod) {
        return mod.getPlayer() != null && mod.getPlayer().isInLava() && wantsFireRes(mod);
    }

    private boolean wantsFireRes(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        boolean has = isFireRes(player.getMainHandItem()) || fireResSlot(mod).isPresent();
        if (!has) return false;
        MobEffectInstance effect = player.getEffect(MobEffects.FIRE_RESISTANCE);
        double blaze = mod.getEntityTracker().getClosestEntity(player.position(), Blaze.class)
                .map(b -> (double) b.distanceTo(player)).orElse(Double.POSITIVE_INFINITY);
        // (the buddy's fork: a drink a task asked for counts as the fire, FireRes.ask: the brew box's potion goes down inside)
        long now = mod.getWorld().getGameTime();
        boolean asked = adris.altoclef.util.helpers.FireRes.asked(now);
        if (!FireResRules.wantsToDrink(true, effect == null ? 0 : effect.getDuration(), player.isOnFire() || asked, blaze)) return false;
        // (the buddy's fork, Tuning fr_trade_hold, off by default: the traded potion, merely on fire on the way, waits for the
        // blazes; in lava or low on health it goes down)
        // (Tuning box_home, off by default: any potion waits for the first blaze in the box, FireRes.holdsForBox)
        // (Tuning fr_hold, off by default: any potion, the kit's too, waits as the traded one, FireRes.holdsAny)
        return !adris.altoclef.util.helpers.FireRes.holdsTraded(player, blaze, asked, now)
                && !adris.altoclef.util.helpers.FireRes.holdsForBox(player, blaze, asked, now)
                && !adris.altoclef.util.helpers.FireRes.holdsAny(player, blaze, asked, now)
                // (Tuning route_hold: any potion waits while the route's phase 3 runs, FireRes.holdsRoute)
                && !adris.altoclef.util.helpers.FireRes.holdsRoute(player, blaze, asked, now);
    }

    // the drink: no stop asked for and no hit rules, the same press and watch as a bite. a screen still keeps the use key from
    // starting anything, so that waits
    private void tryDrink(AltoClef mod) {
        if (_useWatch.backingOff()) {
            stopEat(mod);
            return;
        }
        if (_isTryingToEat && !_biting) stopEat(mod);
        if (Minecraft.getInstance().gui.screen() != null && !(mod.getPlayer().containerMenu instanceof InventoryMenu)) {
            LookHelper.tryAvoidingInteractable(mod);
            return;
        }
        _standing = false;
        startEat(mod, Items.POTION);
    }

    private void startEat(AltoClef mod, Item food) {
        //Debug.logInternal("EATING " + toUse.getTranslationKey() + " : " + test);
        // a stale server side use (a shield that broke while up) would swallow the bite's press. letting go first is free
        if (!_isTryingToEat && !mod.getPlayer().isUsingItem()) {
            mod.getController().releaseUsingItem(mod.getPlayer());
        }
        _isTryingToEat = true;
        // (a drink is one swallow, not a meal to top up)
        if (food != Items.POTION) _requestFillup = true;
        // (the gapple meal is not a pick, it never gets latched)
        if (_mealFood == null && _cachedPerfectFood.isPresent() && _cachedPerfectFood.get() == food) _mealFood = food;
        if (food != _bite) {
            snapshotBite(mod, food);
        }
        if (food == Items.POTION) {
            // the stack by what is in it: a bag can hold more than one kind of potion
            if (!isFireRes(mod.getPlayer().getMainHandItem())) fireResSlot(mod).ifPresent(slot -> mod.getSlotHandler().forceEquipSlot(slot));
        } else {
            mod.getSlotHandler().forceEquipItem(new Item[]{food}, true); //"true" because it's food
        }
        watchUse(mod);
    }

    // the bite that never starts: the key is down and the game never begins using the item (a stale key with no rising edge
    // is the lead suspect). so after a while we let go of it for a tick and press it again
    private void watchUse(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        // (anything eaten or drunk: a potion has no food component, only the consumable one)
        boolean using = player.isUsingItem() && player.getUseItem().has(DataComponents.CONSUMABLE);
        BiteRules.Press press = _useWatch.step(using, player.getUseItemRemainingTicks());
        if (_useWatch.startedThisTick()) {
            _stuckLogged = false;
            logBiteStart(mod, player);
        }
        switch (press) {
            case HOLD -> {
                // the key with a pickaxe in hand is a block placed or a door opened. the swap lands before the bite
                if (player.getMainHandItem().getItem() == _bite) {
                    holdBite(mod);
                } else {
                    mod.getInputControls().release(Input.CLICK_RIGHT);
                    mod.getExtraBaritoneSettings().setInteractionPaused(true);
                    _biting = true;
                }
            }
            case RELEASE -> {
                // the server never finished this use, so tell it to drop it before the next press
                mod.getController().releaseUsingItem(player);
                mod.getInputControls().release(Input.CLICK_RIGHT);
                mod.getExtraBaritoneSettings().setInteractionPaused(true);
                _biting = true;
            }
            case PRESS_AGAIN -> {
                // once per stuck bite is plenty
                if (!_stuckLogged) {
                    _stuckLogged = true;
                    Screen screen = Minecraft.getInstance().gui.screen();
                    Debug.logInternal(String.format("food: use did not start (held=%s using=false key=%s screen=%s), pressing again",
                            BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).getPath(),
                            mod.getInputControls().isHeldDown(Input.CLICK_RIGHT) ? "down" : "up",
                            screen == null ? "none" : screen.getClass().getSimpleName()));
                }
                // the muzzle stays on, a click getting through while the key is up for a tick would break or place something
                mod.getInputControls().release(Input.CLICK_RIGHT);
                mod.getExtraBaritoneSettings().setInteractionPaused(true);
                _biting = true;
            }
            case BACK_OFF -> {
                Debug.logInternal(String.format("food: use still not starting after %d presses, backing off %dt",
                        BiteRules.RETRIES, BiteRules.BACKOFF_TICKS));
                stopEat(mod);
            }
        }
    }

    private void holdBite(AltoClef mod) {
        _biting = true;
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        // the click muzzle goes on with the key and not before: asking for a stop is not chewing, and the movement that
        // wants to place or break while we wait for the stop has to be able to
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
    }

    private void logBiteStart(AltoClef mod, LocalPlayer player) {
        Item item = _bite != null ? _bite : player.getUseItem().getItem();
        String when = _standing ? "stopped " + _useWatch.ticksToStart() + "t ago" : "on the move";
        Debug.logInternal(String.format("food: bite started %s (%s, hp %.1f, hunger %d)",
                BuiltInRegistries.ITEM.getKey(item).getPath(), when, player.getHealth(), player.getFoodData().getFoodLevel()));
    }

    private void stopEat(AltoClef mod) {
        // whatever the meal was, the next one starts from scratch (a back off in progress is not part of the meal)
        _waiting.reset();
        _useWatch.reset();
        _biting = false;
        _standing = false;
        _onTheMove = false;
        if (_isTryingToEat) {
            if (mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD)) {
                if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() != Items.SHIELD) {
                    mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
                } else {
                    _isTryingToEat = false;
                    _requestFillup = false;
                }
            } else {
                _isTryingToEat = false;
                _requestFillup = false;
            }
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
    }

    public boolean isTryingToEat() {
        return _isTryingToEat;
    }

    private void snapshotBite(AltoClef mod, Item food) {
        LocalPlayer player = mod.getPlayer();
        _bite = food;
        _biteCount = mod.getItemStorage().getItemCount(food);
        _biteHunger = player.getFoodData().getFoodLevel();
        _biteSaturation = player.getFoodData().getSaturationLevel();
        _biteHealth = player.getHealth();
    }

    // one line per item actually eaten, with the hunger it was eaten at. the stack going down is the bite landing: the hunger bar
    // can't tell a gapple at full hunger, and starting to chew is not eating (a fight spits it out). the gamer reads these to see
    // how fast a cave leg burns food
    private void watchBite(AltoClef mod) {
        if (_bite == null) {
            return;
        }
        int count = mod.getItemStorage().getItemCount(_bite);
        if (count < _biteCount) {
            Debug.logInternal(String.format("food: ate %s at hunger %d (saturation %.1f, hp %.1f)",
                    BuiltInRegistries.ITEM.getKey(_bite).getPath(), _biteHunger, _biteSaturation, _biteHealth));
            // the next one in a fillup starts from here, and picks its food fresh
            snapshotBite(mod, _bite);
            _mealFood = null;
        } else if (count > _biteCount) {
            // picked one up mid bite, or the stack would have to drop twice to show the bite
            _biteCount = count;
        }
        if (!_isTryingToEat) {
            _bite = null;
            _biteCount = -1;
            _mealFood = null;
        }
    }

    @Override
    public float getPriority(AltoClef mod) {
        _dragonBreathTracker.updateBreath(mod);
        // the clock runs whether or not anybody is eating, that is what a back off is
        _useWatch.tickBackoff();
        _notNow = false;
        noteHit(mod);
        watchBite(mod);
        // one list of reasons we will not eat right now. the eat branch below and needsToEat() both go by it, so
        // everything that pauses for a meal (progress checkers, container waits) stops waiting when we refuse to eat.
        // before, "needs to eat" stayed true through a fall or a lava dip and no watchdog could fire
        _eatBlocked = eatingBlocked(mod);
        // blocking is NOT in the shared list (the creeper and projectile shields are, they ask this chain to stay off the key): counting it
        // would make a shielding bot unable to ever get hungry enough to put the shield down
        if (_eatBlocked) {
            stopEat(mod);
            return Float.NEGATIVE_INFINITY;
        }

        /*
        - Eats if:
        - We're hungry and have food that fits
            - We're low on health and maybe a little bit hungry
            - We're very low on health and are even slightly hungry
        - We're kind of hungry and have food that fits perfectly
         */
        if (shouldRecalculateFood(mod)) {
            Pair<Integer, Optional<Item>> calculation = calculateFood(mod);
            _cachedFoodScore = calculation.first();
            _cachedPerfectFood = FoodSelector.latch(calculation.second(), _mealFood, _isTryingToEat,
                    _mealFood == null ? 0 : mod.getItemStorage().getItemCount(_mealFood));
            // a last resort food (rotten flesh while starving) counts as having food but adds no score
            _hasFood = _cachedPerfectFood.isPresent();
            _foodCalcAge = 0;
        } else {
            _foodCalcAge++;
        }
        // If we requested a fillup but we're full, stop.
        if (_requestFillup && mod.getPlayer().getFoodData().getFoodLevel() >= _fillTo) {
            _requestFillup = false;
        }
        // If we no longer have food, we no longer can eat.
        if (!_hasFood) {
            _requestFillup = false;
        }
        // mid fight we do not start a meal, and one already going gets spat out. you cannot swing a sword while chewing
        // and the thing hitting us does not wait for the bar. once the fight is over _requestFillup picks it back up
        CombatRules.Stance stance = stance(mod);
        if (wantsFireRes(mod) && !mod.getPlayer().isBlocking()) {
            // a potion of fire resistance with fire about (FireResRules): on the move and whatever the fight is doing, the drink
            // is the point of it like the gapple, and a fireball would put any bite's rules on NOT_NOW for good
            tryDrink(mod);
        } else if ((stance == CombatRules.Stance.EAT_GAPPLE || burnGapple(mod, stance)) && !mod.getPlayer().isBlocking()) {
            // not in the food list on purpose, this is the one fight where the gapple is the point
            Item gapple = mod.getItemStorage().hasItem(Items.GOLDEN_APPLE) ? Items.GOLDEN_APPLE : Items.ENCHANTED_GOLDEN_APPLE;
            // (a gapple meal tops up like an ordinary one afterwards, not to the last heal meal's line)
            _fillTo = 20;
            tryEat(mod, gapple, true);
        } else if (stance.mayEat() && _hasFood && (wantsToEat() || _requestFillup) && _cachedPerfectFood.isPresent() &&
                !mod.getMLGBucketChain().isChorusFruiting() && !mod.getPlayer().isBlocking()) {
            Item toUse = _cachedPerfectFood.get();
            // (healing up fills the bar: saturation regen only runs at 20, and that is the fast one)
            if (!_requestFillup) _fillTo = rawNeedsToEat(false) || recoverMeal() ? RecoverHold.FILL_TO : CombatRules.REGEN_HUNGER;
            // (tryEat makes sure we're not facing a container, once it is actually time to chew: looking around while a
            // path is still walking is not ours to do)
            tryEat(mod, toUse, true);
        } else {
            stopEat(mod);
        }

        // read once so a #set in the middle of this can't make the checks below disagree with each other
        int minimumFood = Baritone.settings().altoMinimumFoodAllowed.value;
        int foodToCollect = Baritone.settings().altoFoodUnitsToCollect.value;

        if (_needsFood || _cachedFoodScore < minimumFood) {
            _needsFood = _cachedFoodScore < foodToCollect;

            // Only collect if we don't have enough food.
            // If the user inputs invalid settings, the bot would get stuck here.
            if (_cachedFoodScore < foodToCollect) {
                setTask(new CollectFoodTask(foodToCollect));
                return 55f;
            }
        }


        // Food eating is handled asynchronously.
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public boolean isActive() {
        // We're always checking for food.
        return true;
    }

    @Override
    public String getName() {
        return "Food";
    }

    @Override
    public String getHudName() {
        return "Eating";
    }

    @Override
    protected void onStop(AltoClef mod) {
        super.onStop(mod);
        stopEat(mod);
        _useWatch.clear();
    }

    // everything that makes the eat branch back off. cheap enough to read from getPriority, needsToEat() uses the cached answer
    private boolean eatingBlocked(AltoClef mod) {
        if (WorldHelper.isInNetherPortal(mod)) return true;
        for (BlockPos playerIn : WorldHelper.getBlocksTouchingPlayer(mod)) {
            if (_dragonBreathTracker.isTouchingDragonBreath(playerIn)) return true;
        }
        if (!Baritone.settings().altoAutoEat.value) return true;
        // a lit creeper with nowhere to run: the shield or the cap has the hands
        if (mod.getMobDefenseChain().creeperBoxedIn()) return true;
        // a fireball or an arrow is on its way and the shield is up for it: a bite swaps the food into the hand and lets go of the use
        // key, and that is the shield gone
        if (mod.getMobDefenseChain().projectileDodging(mod)) return true;
        // a splash fire resistance potion about to be thrown at the feet: the bite holds the use key, and the potion swapped in
        // under it was thrown ahead (FireResTrade.ThrowAtFeetTask, route jar 3)
        if (mod.getWorld() != null && adris.altoclef.tasks.resources.FireResTrade.throwDue(mod.getWorld().getGameTime())) return true;
        // do NOT eat while in lava if we are escaping it (spaghetti code dependencies go brrrr)
        // (the buddy's fork: but a fire resistance drink goes down in lava too. sinking in a lava column under the potion, the
        // third potion stayed in the bag and the buddy died in the lava as the second ran out, realfort-ab-fpreach-1 run 8)
        boolean inLava = mod.getPlayer().isInLava();
        boolean lavaDrink = inLava && wantsFireRes(mod);
        if (inLava && !lavaDrink) return true;
        // We're in danger, don't eat now!!
        // (the buddy's fork: the lava escape's stop let the drink above in and then held it here, so a buddy knocked into the
        // lava sea died there in 5 s with the potion in the bag, row W-lavadrink. FireResRules.stopHolds lets that drink by.
        // the escape's stair and Baritone's block placing do not fight it for the hand or the use key: every tick of the press
        // sets the click muzzle (holdBite, watchUse), this chain asks after the ones that clear it and before the escape ticks,
        // and selectThrowawayForLocation picks nothing under the muzzle; the escape keeps swimming and goes on after the drink)
        return !mod.getMLGBucketChain().doneMLG() || mod.getMLGBucketChain().isFallingOhNo(mod)
                || FireResRules.stopHolds(shouldStop, inLava, lavaDrink);
    }

    // this is also "are we busy chewing", half the codebase asks it to know whether it may swing or click. so it says no
    // when a fight stops us eating, and yes when the fight is the reason we are eating (the gapple)
    // (a meal we are standing for counts as needing to eat even after the rules stop asking for it: the fillup tail is
    // still chewing, and the progress checkers that forgive a meal must not see 6 seconds of a bot standing still)
    // (a stop that does not come for a few seconds, or a use that will not start, is not a meal: the checkers that forgive
    // eating, the defense and the swings get to carry on while we keep trying)
    public boolean needsToEat() {
        if (_notNow) return false;
        boolean stuck = BiteRules.waitedTooLong(_waiting.ticks()) || _useWatch.backingOff();
        return (!stuck && wantsToEat()) || (_standing && !shouldStop && !_eatBlocked && stance(_mod).mayEat());
    }

    // what the eat branch goes by. needsToEat() adds the standing meal on top, and the branch cannot go by that or a meal
    // would keep itself alive forever
    private boolean wantsToEat() {
        if (shouldStop || _eatBlocked) return false;
        CombatRules.Stance stance = stance(_mod);
        if (stance == CombatRules.Stance.EAT_GAPPLE || burnGapple(_mod, stance)) return true;
        if (!stance.mayEat()) return false;
        return rawNeedsToEat(true) || recoverMeal();
    }

    // the heal-up hold is on and the bar is not full: eat, the bar being full is what makes the hold short. the stance has
    // already said whether anything is close enough to stop us (the hold eats by the run's bite lines)
    private boolean recoverMeal() {
        if (_mod == null || _mod.getMobDefenseChain() == null || !_mod.getMobDefenseChain().isRecovering()) return false;
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getFoodData().getFoodLevel() < RecoverHold.FILL_TO && hasHealFood();
    }

    // the food we would pick heals without hurting us. the heal-up hold only starts on this (or a bar already at the regen
    // line), same filter the heal meal uses
    public boolean hasHealFood() {
        return _hasFood && _cachedPerfectFood.isPresent() && healFood(_cachedPerfectFood.get());
    }

    // (raw chicken is the same kind of bad idea as chorus fruit, the hunger effect eats the bar we are trying to fill. rotten
    // flesh too, it is only ever the pick when starving, and the older rules eat it there anyway)
    static boolean healFood(Item item) {
        return item != Items.CHORUS_FRUIT && item != Items.CHICKEN && item != Items.ROTTEN_FLESH;
    }

    // on fire with nothing putting it out and half the hearts gone: a gapple is the one food vanilla lets us eat on a full
    // bar, and its regen outheals the burn for a while. ordinary food still waits for the bar to drop
    static boolean burnGapple(boolean burning, float health, boolean hasGapple, boolean mayEat) {
        return burning && health <= 10 && hasGapple && mayEat;
    }

    private static boolean burnGapple(AltoClef mod, CombatRules.Stance stance) {
        LocalPlayer player = mod == null ? null : mod.getPlayer();
        return player != null && burnGapple(player.isOnFire() && !adris.altoclef.util.helpers.FireRes.has(player), player.getHealth(),
                mod.getItemStorage().hasItem(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE), stance.mayEat());
    }

    // the chains are built one after another, so early on there may be no mob defense to ask yet
    private static CombatRules.Stance stance(AltoClef mod) {
        if (mod == null || mod.getMobDefenseChain() == null) return CombatRules.Stance.CALM;
        return mod.getMobDefenseChain().getCombatStance(mod);
    }

    // the heal rule fires a lot more often than the old ones (any hp under 18), so it is the careful one
    private boolean healsWithoutHarm() {
        if (_cachedPerfectFood.isPresent() && !healFood(_cachedPerfectFood.get())) return false;
        return _mod == null || _mod.getMobDefenseChain() == null || !_mod.getMobDefenseChain().isShotAt(_mod);
    }

    // heal: whether the hurt-and-under-the-regen-line rule counts (the meal's fillup line asks without it)
    private boolean rawNeedsToEat(boolean heal) {
        if (!hasFood()) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        assert player != null;
        int foodLevel = player.getFoodData().getFoodLevel();
        float health = player.getHealth();

        if (CombatRules.desperateMeal(health, foodLevel)) {
            return true;
        }
        // hurt with a hunger bar too low to regenerate: eat until it is back at 18. not on last resort food (chorus fruit
        // throws us across the map), not in front of a shooter, and the meal stops at the regen line (_fillTo)
        if (heal && CombatRules.healingMeal(health, foodLevel) && healsWithoutHarm()) {
            return true;
        }
        //Debug.logMessage("FOOD: " + foodLevel + " -- HEALTH: " + health);
        if (foodLevel >= 20) {
            // We can't eat.
            return false;
        } else {
            // Eat if we're desperate/need to heal ASAP
            if (player.isOnFire() || player.hasEffect(MobEffects.WITHER) || health < _config.alwaysEatWhenWitherOrFireAndHealthBelow) {
                return true;
            } else if (foodLevel > _config.alwaysEatWhenBelowHunger) {
                if (health < _config.alwaysEatWhenBelowHealth) {
                    return true;
                }
            } else {
                // We have half hunger
                return true;
            }
        }

        // Eat if we're  units hungry and we have a perfect fit.
        if (foodLevel < _config.alwaysEatWhenBelowHungerAndPerfectFit && _cachedPerfectFood.isPresent()) {
            int need = 20 - foodLevel;
            Item best = _cachedPerfectFood.get();
            int fills = (best.components().get(DataComponents.FOOD) != null) ? Objects.requireNonNull(best.components().get(DataComponents.FOOD)).nutrition() : -1;
            return fills == need;
        }

        return false;
    }

    private Pair<Integer, Optional<Item>> calculateFood(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        float health = player != null ? player.getHealth() : 20;
        float hunger = player != null ? player.getFoodData().getFoodLevel() : 20;
        float saturation = player != null ? player.getFoodData().getSaturationLevel() : 20;
        // the buddy's fork: "may we eat it" is not "may we throw it away". the food task protects every food from the bin while
        // it runs (and a frame that is not popped keeps doing so), and with canThrowAwayStack as the test the selector found
        // nothing to eat in a bag of eight cooked porkchops: it stood at 5 hp with hunger 16 for six game minutes, never
        // healed, and a skeleton shot it (the runner, 2 Nov 2026, attempt 49). only the important items stay out of a meal
        FoodSelector.Result result = FoodSelector.select(mod.getItemStorage().getItemStacksPlayerInventory(true),
                FoodChain::mayEat, health, hunger, saturation, _config);
        return new Pair<>(result.foodTotal(), result.best());
    }

    // "may we eat it" is not "may we throw it away". CollectFoodTask protects every food from the bin while it runs, so
    // with canThrowAwayStack here a bot out getting food sat at 5 hp next to eight cooked porkchops and never ate one.
    // only altoImportantItems stay out of a meal, the selector's own kinds already keep gapples and pufferfish out
    static boolean mayEat(ItemStack stack) {
        return !stack.isEmpty() && !AltoSettings.isImportant(stack.getItem());
    }

    // walking the whole inventory and scoring every stack 20 times a second is a lot of work for an answer
    // that only changes when we eat, pick something up or the bars move. so: recompute when the bars move,
    // while eating (the stack we're chewing on is about to run out) or every FOOD_RECALC_TICKS otherwise
    private boolean shouldRecalculateFood(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (player == null) return true;
        int hunger = player.getFoodData().getFoodLevel();
        float saturation = player.getFoodData().getSaturationLevel();
        float health = player.getHealth();
        boolean barsMoved = hunger != _lastFoodHunger || saturation != _lastFoodSaturation || health != _lastFoodHealth;
        if (barsMoved || _isTryingToEat || _foodCalcAge >= FOOD_RECALC_TICKS) {
            _lastFoodHunger = hunger;
            _lastFoodSaturation = saturation;
            _lastFoodHealth = health;
            return true;
        }
        return false;
    }

    // If we need to eat like, NOW.
    public boolean needsToEatCritical() {
        return false;
    }

    public boolean hasFood() {
        return _hasFood;
    }

    public void shouldStop(boolean shouldStopInput) {
        shouldStop = shouldStopInput;
    }

    public boolean isShouldStop() {
        return shouldStop;
    }

    static class FoodChainConfig {
        public int alwaysEatWhenWitherOrFireAndHealthBelow = 6;
        public int alwaysEatWhenBelowHunger = CombatRules.HUNGRY_AT;
        public int alwaysEatWhenBelowHealth = CombatRules.BRUISED_HEALTH;
        public int alwaysEatWhenBelowHungerAndPerfectFit = 20 - 5;
        public int prioritizeSaturationWhenBelowHealth = 8;
        public float foodPickPrioritizeSaturationSaturationMultiplier = 8;
        public float foodPickSaturationWastePenaltyMultiplier = 1;
        public float foodPickHungerWastePenaltyMultiplier = 2;
        public float foodPickHungerNotFilledPenaltyMultiplier = 1;
        public float foodPickRottenFleshPenalty = 100;
        public float runDontEatMaxHealth = 3;
        public int runDontEatMaxHunger = 3;
        public int canTankHitsAndEatArmor = 15;
        public int canTankHitsAndEatMaxHunger = 3;
    }
}
