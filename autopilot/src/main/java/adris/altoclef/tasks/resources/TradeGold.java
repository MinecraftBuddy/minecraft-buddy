package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.speedrun.gamer.NetherGold;
import adris.altoclef.tasks.speedrun.gamer.NetherPick;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherPickTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.FireResTradeRules;
import adris.altoclef.util.helpers.TradeGoldRules;
import adris.altoclef.util.helpers.Tuning;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

// the buddy's fork, Tuning nether_gold (on by default; steering 200 of 19 Nov 2026, the maintainer's word): the trade's gold dug
// in the nether. the overworld kit asks only the two golden helmets' gold; in the nether, before the trade begins, the buddy
// digs nether gold ore out of the netherrack, folds the nuggets into ingots at the crafting table of the bag (it goes back in
// the bag after each fold, CraftInTableTask), and the trade begins once the bag holds the target (Tuning nether_gold_to, 96),
// or once the step is over: no gold ore known for a minute, no new gold for Tuning nether_gold_dry_s, or Tuning nether_gold_cap_s
// in all. the overworld poke mine took 10 to 20 gold a game hour (96 in five to nine game hours). a piglin near breaks the
// trade's peace: breaking a block of #guarded_by_piglins angers every idle piglin in the player's box inflated by 16, no sight
// asked (PiglinAi.angerNearbyPiglins from Block.playerWillDestroy, 26.3), so ore with a piglin within TradeGoldRules.PIGLIN_CLEAR
// of it is never picked, and while one is in that box baritone may not break gold ore on any path (as NetherPhase.guardGold).
// FireResTrade owns one of these per order and asks it while the trade is still waiting
final class TradeGold {

    // a line of how the dig goes every this many ingots (nuggets counted as ninths)
    static final int PROGRESS_EVERY = 8;
    // the step's scan for exposed ore: this many chunks round the feet' chunk, again every this many ticks
    static final int SCAN_CHUNKS = 5;
    static final long ORE_SCAN_TICKS = 100;
    // an ore not broken in this long is given up for another (game ticks)
    static final long ORE_GIVE_UP = 60 * 20;
    // the nuggets of a break are picked up from within this many blocks for at most this long (game ticks)
    static final double PICKUP_BLOCKS = 8;
    static final double PICKUP_DY = 3;
    static final long PICKUP_TICKS = 10 * 20;
    // an ore further off than this is a trip, said in a line
    static final double TRIP_BLOCKS = 16;
    // a piglin left the box this long ago and baritone may break gold ore again (a piglin on the box's edge would flip it)
    static final int GUARD_HOLD_TICKS = 20;
    private static final Block[] GUARDED = {Blocks.NETHER_GOLD_ORE, Blocks.GILDED_BLACKSTONE};

    private boolean _begun;
    private boolean _over;
    private long _begunAt;
    private long _riseAt;
    private long _noOreSince = -1;
    // (gold_danger_hold) when a hostile was last within DANGER_BLOCKS
    private long _dangerAt = Long.MIN_VALUE / 2;
    static final long DANGER_HOLD = 200;
    private int _bestNinths;
    private int _ninthsAtBegin;
    private int _progressLogged;
    private int _anchors;
    private BlockPos _anchor;
    // the step's own scan of exposed ore (scanOres), when it ran, the ore under way and since when, the ores given up, the
    // ores broken, and when the last one broke (its nuggets' pickup)
    private List<BlockPos> _ores = List.of();
    private long _scannedAt = Long.MIN_VALUE;
    private BlockPos _target;
    private long _targetSince;
    // (gold_clock_near) the ore's clock was started again on arrival; (gold_path_fail) baritone's failed searches when the ore
    // step last ran, and the tick it ran (a gap re-arms: another step's searches are not the ore's)
    private boolean _targetNear;
    private int _oreFails;
    private long _oreTick = Long.MIN_VALUE / 2;
    private final java.util.Set<BlockPos> _givenUp = new java.util.HashSet<>();
    private int _broken;
    private long _pickupSince = Long.MIN_VALUE;
    private Task _step;
    private String _stepKind;
    private int _stepTarget;
    private boolean _guarding;
    private int _guardHold;
    private List<BlockPos> _piglins = List.of();
    // the search for ore while none is known: rings round where the step began, away from the spawner (FireResTradeRules'
    // points); the point under way, since when, and its walk
    private int _searchK = -1;
    private long _pointSince;
    private Task _searchWalk;
    private double _searchFrom;
    private BlockPos _spawner;
    private BlockPos _start;
    private String _hud = "Digging gold for the piglins";
    // (Tuning gold_rounds) a round after the first: what it digs to (-1: Tuning's target), and the tick the first round began,
    // from which the cap counts (Long.MIN_VALUE: this round's own begin); why the step ended, once it has
    private final int _roundTarget;
    private final long _capFrom;
    private String _overWhy;
    // (gold_stone_dig) the stone pickaxe's craft under way, how many were begun, and whether one failed
    private NetherPickTask _stonePick;
    private int _stonePicks;
    private boolean _stonePickFailed;
    private int _stonePickFails;
    static final int STONE_PICK_TRIES = 3;

    TradeGold() {
        this(-1, java.util.Set.of(), Long.MIN_VALUE);
    }

    // a further round (FireResTrade, gold_rounds): its own target, the ores the rounds before gave up on, and the first round's
    // begin for the cap
    TradeGold(int roundTarget, java.util.Set<BlockPos> givenUp, long capFrom) {
        _roundTarget = roundTarget;
        _givenUp.addAll(givenUp);
        _capFrom = capFrom;
    }

    // why the step ended, or null while it has not
    String overWhy() {
        return _overWhy;
    }

    // the step ended for a reason another round cannot change: the pickaxes kept for the way on, or the cap of all the rounds
    boolean deadEnd() {
        return _overWhy != null && (_overWhy.startsWith("the pickaxes kept") || _overWhy.startsWith("the cap"));
    }

    java.util.Set<BlockPos> givenUp() {
        return java.util.Set.copyOf(_givenUp);
    }

    // the tick the cap counts from: the first round's begin
    long capFrom() {
        return _capFrom != Long.MIN_VALUE ? _capFrom : _begunAt;
    }

    static boolean on() {
        return Tuning.get("nether_gold", 1) > 0;
    }

    static int target() {
        return (int) Math.max(0, Tuning.get("nether_gold_to", TradeGoldRules.TARGET));
    }

    private static long capTicks() {
        return (long) (20 * Tuning.get("nether_gold_cap_s", TradeGoldRules.CAP_S));
    }

    private static long dryTicks() {
        return (long) (20 * Tuning.get("nether_gold_dry_s", TradeGoldRules.DRY_S));
    }

    boolean begun() {
        return _begun;
    }

    boolean running() {
        return _begun && !_over;
    }

    String hud() {
        return _hud;
    }

    // the dig's step this tick, or null: once there is nothing (more) to dig, the trade then going on with the bag's gold, or
    // for this tick while a blaze is near (the rods' own fight has it; the step goes on after). enough: the trade's target is in
    // the bag already (a potion); atFortress: by the spawner; spawner: the one known, or null
    Task tick(AltoClef mod, boolean enough, boolean atFortress, boolean blazeNear, BlockPos spawner) {
        if (_over || !on()) return null;
        _spawner = spawner;
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        int ingots = mod.getItemStorage().getItemCount(Items.GOLD_INGOT);
        int nuggets = mod.getItemStorage().getItemCount(Items.GOLD_NUGGET);
        int target = _roundTarget >= 0 ? _roundTarget : target();
        if (!_begun) {
            if (WorldHelper.getCurrentDimension() != Dimension.NETHER) return null;
            String why = TradeGoldRules.whyNot(ingots, nuggets, target, enough, atFortress || blazeNear,
                    mod.getItemStorage().getItemCount(Items.BLAZE_ROD), tableOrWood(mod));
            if (why != null) {
                Debug.logInternal("trade gold: not begun (" + why + ")");
                _over = true;
                _overWhy = "not begun (" + why + ")";
                return null;
            }
            _begun = true;
            _begunAt = now;
            _riseAt = now;
            _anchor = player.blockPosition();
            _start = _anchor;
            _ninthsAtBegin = _bestNinths = TradeGoldRules.ninths(ingots, nuggets);
            Debug.logInternal(String.format(Locale.ROOT, "trade gold: begun, %d gold, target %d, %d nuggets, at %s; cap %s s, %s s with no new gold",
                    ingots, target, nuggets, _anchor.toShortString(), (long) (capTicks() / 20), (long) (dryTicks() / 20)));
        }
        if (now < _riseAt) _riseAt = now;
        int ninths = TradeGoldRules.ninths(ingots, nuggets);
        if (ninths > _bestNinths) {
            _bestNinths = ninths;
            _riseAt = now;
        }
        if (ninths / 9 >= _progressLogged + PROGRESS_EVERY && ingots < target) {
            _progressLogged = ninths / 9 - ninths / 9 % PROGRESS_EVERY;
            Debug.logInternal(String.format(Locale.ROOT, "trade gold: %d gold so far (%d ingots, %d nuggets) after %s s",
                    ninths / 9, ingots, nuggets, seconds(now - _begunAt)));
        }
        guard(mod);
        // a blaze near: the rods' own fight has the tick, the step goes on after it (an end said now would also find the trade
        // with a blaze near, which never begins then)
        if (blazeNear && !atFortress) {
            _hud = "Digging gold for the piglins: a blaze near, the fight first";
            return null;
        }
        // the end: the target in ingots, the fortress (the walk there went on while no ore was known), or a clock; the last
        // nuggets folded first
        // (Tuning gold_need_pick, a fix of 19 Nov 2026, on) and the pickaxes of iron or better down to PICK_KEEP uses left in
        // all: the trips through the netherrack to ore 17 to 73 blocks off wore out a diamond pickaxe of 1137 uses, a fresh iron,
        // a stone and a wooden one (112 trips, 159 ores), then the step dug with netherrack in hand, which drops nothing, and the
        // buddy went on through the nether with no pickaxe at all (heel-54-mc3, live watch)
        int picksLeft = goldNeedPickOn() ? pickUsesLeft(mod) : Integer.MAX_VALUE;
        boolean picksKept = picksLeft < PICK_KEEP;
        // (Tuning gold_stone_dig, a fix of 20 Nov 2026, on) under the reserve a stone pickaxe takes over: one in the bag digs on
        // (the wear chain swings it before the kit's), else one is made from the bag's cobblestone with no danger near. 7 of 8
        // steps since gold_rounds ended on the pickaxes kept at 0 to 54 gold, a dead end for another round, and 3 of those runs
        // went to the blazes or stood with no potion (heel-78-mc1, heel-78-mc2, heel-80-mc2)
        if (picksKept && ingots < target && !atFortress) {
            boolean dangerNow = dangerNear(mod) || now - _dangerAt < DANGER_HOLD || player.isOnFire() || player.isInLava();
            switch (TradeGoldRules.pickWay(goldStoneDigOn(), picksLeft, PICK_KEEP, PICK_FLOOR, stonePickUsable(mod),
                    NetherPick.covered(bagOf(mod), NetherPick.Tier.STONE), _stonePickFailed, dangerNow)) {
                case DIG -> picksKept = false;
                case MAKE -> {
                    Task make = stonePick(mod, picksLeft);
                    if (make != null) return make;
                }
                case END -> {
                }
            }
        }
        String end = ingots >= target ? "the target reached" : atFortress ? "at the fortress"
                : picksKept ? "the pickaxes kept for the way on (" + picksLeft + " uses left)"
                : TradeGoldRules.overWhy(Math.max(0, now - capFrom()), now - _riseAt, _noOreSince < 0 ? 0 : now - _noOreSince, capTicks(), dryTicks());
        // (Tuning gold_danger_hold, a fix of 19 Nov 2026, on) a hostile seen within DANGER_BLOCKS counts for DANGER_HOLD after: a mob
        // on the edge of the box, in as the buddy walked to the table and out as the dig walked away, flipped the fold and the dig
        // every second for three minutes, and neither the table nor the ore was reached (heel-55-mc3, live watch)
        boolean danger = dangerNear(mod);
        if (goldDangerHoldOn()) {
            if (danger || now < _dangerAt) _dangerAt = now;
            danger = now - _dangerAt < DANGER_HOLD;
        }
        // (20 Nov 2026, look 157) burning or in lava is a danger for the fold too: the buddy folded nuggets at a table with fire on
        // it after a lava stream beside the way, and went from 20 hp to 1
        if (goldDangerHoldOn() && (player.isOnFire() || player.isInLava())) danger = true;
        if (TradeGoldRules.fold(ingots, nuggets, target, end != null) && !danger) {
            int to = Math.min(target, ingots + nuggets / NetherGold.NUGGETS_PER_INGOT);
            return step(mod, "fold", to, () -> {
                ItemTarget n = new ItemTarget(Items.GOLD_NUGGET);
                CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("gold_ingot", new ItemTarget[]{n, n, n, n, n, n, n, n, n}, 1);
                return new CraftInTableTask(new RecipeTarget(Items.GOLD_INGOT, to, recipe), false, true);
            }, "Folding gold nuggets into ingots for the piglins");
        }
        if (end != null && (!danger || nuggets < NetherGold.NUGGETS_PER_INGOT)) {
            over(mod, now, end, ingots, nuggets);
            return null;
        }
        // the mine: the nearest exposed gold ore of the step's own scan (scanOres), clear of piglins and the spawner; its nuggets
        // picked up after the break; with none, the search walks and the clock of no ore runs
        refreshOres(mod, now);
        BlockPos feet = player.blockPosition();
        // (Tuning gold_target_keep, a fix of 19 Nov 2026, on) the piglins round the ore are asked again only once the feet are
        // within PIGLIN_CLEAR of it: asked from afar, every piglin that wandered near a target 50 to 90 blocks off dropped it,
        // and the buddy turned to another ore every few seconds, twelve trips in two minutes and none reached (heel-54, the
        // live watch). at the ore a piglin in the anger box still holds the break (guard)
        boolean piglinsAsked = !goldTargetKeepOn() || _target == null
                || _target.distSqr(feet) <= TradeGoldRules.PIGLIN_CLEAR * TradeGoldRules.PIGLIN_CLEAR;
        if (_target != null && (!mod.getWorld().getBlockState(_target).is(Blocks.NETHER_GOLD_ORE)
                || (piglinsAsked && !TradeGoldRules.clearOfPiglins(_target, _piglins)))) {
            if (!mod.getWorld().getBlockState(_target).is(Blocks.NETHER_GOLD_ORE)) {
                _broken++;
                _pickupSince = now;
                _scannedAt = Long.MIN_VALUE;
            }
            _target = null;
        }
        // (Tuning gold_clock_near, a fix of 19 Nov 2026, on) the ore's minute starts again once, when the feet come within
        // NEAR_BLOCKS of it: counted from the start of a 69-block walk, it ran out just as the ore came in reach, the next ore
        // one block over was taken, and its break stopped a diagonal step over an open shaft (heel-60-mc3)
        if (_target != null && !_targetNear && goldClockNearOn() && _target.distSqr(feet) <= NEAR_BLOCKS * NEAR_BLOCKS) {
            _targetNear = true;
            _targetSince = now;
        }
        if (_target != null && now >= _targetSince && now - _targetSince > ORE_GIVE_UP) {
            Debug.logInternal("trade gold: the ore at " + _target.toShortString() + " not broken in " + ORE_GIVE_UP / 20 + " s, another");
            _givenUp.add(_target);
            _target = null;
        }
        // the nuggets of the last break first, while they lie near (never past PICKUP_TICKS: one in lava stays there)
        int onGround = nuggetsNear(mod);
        if (onGround > 0 && now >= _pickupSince && now - _pickupSince <= PICKUP_TICKS) {
            int want = nuggets + onGround;
            return step(mod, "pickup", want, () -> new adris.altoclef.tasks.movement.PickupDroppedItemTask(Items.GOLD_NUGGET, want),
                    "Picking up gold nuggets for the piglins");
        }
        if (_target == null) {
            _target = nearestOre(mod, feet, now);
            _targetSince = now;
            _targetNear = false;
            _oreTick = Long.MIN_VALUE / 2;
            if (_target != null && Math.sqrt(_target.distSqr(feet)) > TRIP_BLOCKS) {
                _anchors++;
                Debug.logInternal(String.format(Locale.ROOT, "trade gold: on to ore at %s, %.0f blocks off (trip %d, %d exposed ore known)",
                        _target.toShortString(), Math.sqrt(_target.distSqr(feet)), _anchors, _ores.size()));
            }
        }
        if (_target == null) {
            if (_noOreSince < 0) {
                _noOreSince = now;
                Debug.logInternal("trade gold: no exposed gold ore within reach with no piglin near it, searching");
            }
            _step = null;
            _hud = "Looking for gold ore for the piglins";
            return search(mod, now);
        }
        _noOreSince = -1;
        _searchWalk = null;
        // (Tuning gold_path_fail, a fix of 19 Nov 2026, on) a failed search for a way to the ore, away from it, gives it up at
        // once: after a bridge over the lava sea the search found no way on, eleven times, and the buddy stood 22 s on the tip
        // of the bridge waiting out the ore's minute until a ghast's ball blew it off (heel-59-mc2)
        if (goldPathFailOn()) {
            int fails = mod.getClientBaritone().getPathingBehavior().calcFailures();
            if (now - _oreTick > 2 || fails < _oreFails) {
                _oreFails = fails;
            } else if (fails > _oreFails && _target.distSqr(feet) > NEAR_BLOCKS * NEAR_BLOCKS) {
                Debug.logInternal("trade gold: no way to the ore at " + _target.toShortString() + ", another");
                _givenUp.add(_target);
                _target = null;
                _oreTick = Long.MIN_VALUE / 2;
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                return null;
            }
            _oreTick = now;
        }
        BlockPos at = _target;
        return step(mod, "ore " + at.toShortString(), 0, () -> new adris.altoclef.tasks.construction.DestroyBlockTask(at),
                "Digging gold for the piglins");
    }

    static boolean goldNeedPickOn() {
        return adris.altoclef.util.helpers.Tuning.get("gold_need_pick", 0) > 0;
    }

    // (gold_need_pick) the uses kept back for the rest of the nether (the fortress, the covers, the way out): about a fresh
    // iron pickaxe and a bit
    static final int PICK_KEEP = 300;
    // (gold_stone_dig) with danger near the stone pickaxe's craft waits and the kit's dig on down to this many uses, then the step ends
    static final int PICK_FLOOR = 240;

    static boolean goldStoneDigOn() {
        return adris.altoclef.util.helpers.Tuning.get("gold_stone_dig", 0) > 0;
    }

    // (gold_stone_dig) a stone pickaxe in the bag that is not worn out
    private static boolean stonePickUsable(AltoClef mod) {
        var inv = mod.getPlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack st = inv.getItem(i);
            if (st.is(Items.STONE_PICKAXE) && !adris.altoclef.util.helpers.ToolWear.wornOut(st.getDamageValue(), st.getMaxDamage())) {
                return true;
            }
        }
        return false;
    }

    // (gold_stone_dig) what the bag holds for a pickaxe of its own (NetherPick's recipe inputs)
    static NetherPick.Bag bagOf(AltoClef mod) {
        var st = mod.getItemStorage();
        int planks = 0;
        for (var item : adris.altoclef.util.helpers.ItemHelper.PLANKS) planks += st.getItemCount(item);
        int logs = 0;
        for (var item : adris.altoclef.util.helpers.ItemHelper.LOG) logs += st.getItemCount(item);
        int cobble = st.getItemCount(Items.COBBLESTONE);
        // (Tuning gold_stone_any, a fix of 21 Nov 2026, on) every stone a stone pickaxe takes (cobblestone, cobbled deepslate,
        // blackstone: CollectStoneMaterialTask.MATERIALS, the craft's own stone_material), not cobblestone alone
        if (adris.altoclef.util.helpers.Tuning.get("gold_stone_any", 0) > 0) {
            cobble = 0;
            for (var item : CollectStoneMaterialTask.MATERIALS) cobble += st.getItemCount(item);
        }
        // (Tuning gold_keep_stand_stone, a fix of 21 Nov 2026, on) the brewing stand's stone (brew_keep_stone) is never the
        // stone pickaxe's: the boxes kept it, the gold step's pickaxes did not, so the brew found 0 of 3 and was skipped
        // (the brew gate fired 3 times in 51 heel runs, 20-21 Nov)
        if (adris.altoclef.util.helpers.Tuning.get("gold_keep_stand_stone", 0) > 0) {
            cobble = TradeGoldRules.cobbleFree(cobble, BlazePillboxTask.standStoneInBag(mod), BlazePillboxTask.keptStone(mod));
        }
        return new NetherPick.Bag(st.getItemCount(Items.IRON_INGOT), cobble, planks,
                st.getItemCount(Items.STICK), logs, st.getItemCount(Items.CRAFTING_TABLE) > 0 || tableNear(mod));
    }

    // (gold_stone_dig, 20 Nov 2026) a table of ours standing within the bench's reach counts as the craft's table: the first
    // try placed the bag's table, a danger broke the craft off, the dig went on, and 2.5 minutes later the bag had no table
    // and too few planks for one, so the craft failed at once (cascade heel-86 run 1, look 170)
    static final double TABLE_REACH = 21;

    static boolean tableNear(AltoClef mod) {
        var p = mod.getPlayer();
        return adris.altoclef.util.helpers.StationHook.standingWithin(adris.altoclef.util.helpers.StationHook.Kind.TABLE,
                p.getX(), p.getY(), p.getZ(), TABLE_REACH) != null;
    }

    // (gold_stone_dig) the stone pickaxe's craft, stage by stage from the bag (NetherPickTask); null once a craft failed, and the
    // step then ends on the pickaxes kept
    private Task stonePick(AltoClef mod, int picksLeft) {
        // (a failed craft is tried again up to STONE_PICK_TRIES in all: one broken off by a danger came back to a bag that had
        // set its table down, look 170)
        if (_stonePick != null && _stonePick.failed()) {
            _stonePickFails++;
            _stonePick = null;
            if (_stonePickFails >= STONE_PICK_TRIES) {
                _stonePickFailed = true;
                Debug.logInternal("trade gold: the stone pickaxe's craft failed " + _stonePickFails + " times, the step ends on the pickaxes kept");
                return null;
            }
            Debug.logInternal("trade gold: the stone pickaxe's craft failed (" + _stonePickFails + " of " + STONE_PICK_TRIES + "), again");
        }
        if (_stonePick == null || _stonePick.isFinished(mod)) {
            _stonePick = new NetherPickTask(NetherPick.Tier.STONE, () -> bagOf(mod), () -> stonePickUsable(mod));
            _stonePicks++;
            Debug.logInternal(String.format(Locale.ROOT, "trade gold: the pickaxes of iron or better at %d uses, a stone pickaxe from the bag's %d cobblestone (%d made)",
                    picksLeft, mod.getItemStorage().getItemCount(Items.COBBLESTONE), _stonePicks));
        }
        _step = _stonePick;
        _stepKind = "stone pick";
        _hud = "Making a stone pickaxe for the gold";
        return _stonePick;
    }

    // the uses left on the bag's pickaxes of iron or better, all together
    private static int pickUsesLeft(AltoClef mod) {
        var inv = mod.getPlayer().getInventory();
        int left = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack st = inv.getItem(i);
            if (st.is(Items.IRON_PICKAXE) || st.is(Items.DIAMOND_PICKAXE) || st.is(Items.NETHERITE_PICKAXE)) {
                left += Math.max(0, st.getMaxDamage() - st.getDamageValue());
            }
        }
        return left;
    }

    static boolean goldDangerHoldOn() {
        return adris.altoclef.util.helpers.Tuning.get("gold_danger_hold", 0) > 0;
    }

    static boolean goldTargetKeepOn() {
        return adris.altoclef.util.helpers.Tuning.get("gold_target_keep", 0) > 0;
    }

    // the step's own look for ore, every ORE_SCAN_TICKS and at once after a break (the vein's next ore shows once the broken
    // one opens it up)
    private void refreshOres(AltoClef mod, long now) {
        if (_scannedAt != Long.MIN_VALUE && now >= _scannedAt && now - _scannedAt < ORE_SCAN_TICKS) return;
        _scannedAt = now;
        _ores = scanOres(mod.getWorld(), mod.getPlayer().blockPosition(), _anchor0().getY());
    }

    // the nearest scanned ore still there, clear of piglins and the spawner, not given up on; (gold_way_open) and with its
    // straight way from the feet open to a fall in no more than wayOpenMax columns, the nearest WAY_TESTS asked in turn
    private BlockPos nearestOre(AltoClef mod, BlockPos feet, long now) {
        // the ways were asked a moment ago and none would do: not again every tick (40 ways of up to 90 columns each)
        if (now >= _wayLeftAt && now - _wayLeftAt < WAY_AGAIN_TICKS) return null;
        List<BlockPos> ok = new ArrayList<>();
        for (BlockPos p : _ores) {
            if (_givenUp.contains(p) || !TradeGoldRules.clearOfPiglins(p, _piglins) || !TradeGoldRules.awayFromSpawner(p.getX(), p.getZ(), _spawner)
                    || !mod.getWorld().getBlockState(p).is(Blocks.NETHER_GOLD_ORE)) continue;
            ok.add(p);
        }
        ok.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(feet)));
        int max = TradeGoldRules.wayOpenMax();
        if (max <= 0 || ok.isEmpty()) return ok.isEmpty() ? null : ok.get(0);
        var level = mod.getWorld();
        int skipped = 0;
        for (int i = 0; i < ok.size() && i < WAY_TESTS; i++) {
            BlockPos p = ok.get(i);
            int low = Math.min(feet.getY(), p.getY());
            int open = TradeGoldRules.openColumns(feet.getX(), feet.getY(), feet.getZ(), p.getX(), p.getY(), p.getZ(),
                    (x, y, z) -> openUnder(level, x, y, z, low), max);
            if (open <= max) {
                if (skipped > 0) {
                    Debug.logInternal("trade gold: " + skipped + " nearer ore left, the way to it open to a fall (gold_way_open)");
                }
                return p;
            }
            skipped++;
        }
        _wayLeftAt = now;
        if (skipped != _wayLeftLogged) {
            _wayLeftLogged = skipped;
            Debug.logInternal("trade gold: the nearest " + skipped + " ore left, the way to each open to a fall (gold_way_open)");
        }
        return null;
    }

    // (gold_way_open) when every way asked was open last, and how many ores that left (said again only when it changes)
    private long _wayLeftAt = Long.MIN_VALUE / 2;
    private int _wayLeftLogged = -1;
    static final long WAY_AGAIN_TICKS = 40;

    // (gold_way_open) a column of the way with no floor from 2 over the way's height y down to WAY_FLOOR_DOWN under the way's lower
    // end (the feet's or the ore's height, whichever is lower), or lava the first thing there: a way over it is a bridge or a
    // jump, and a knock off it a fall into the sea. (20 Nov 2026, look 157) under the lower end, not the line: a straight line
    // from a basin at y 44 up to ore at y 80 floats over the slope it climbs, and 38% of the open columns were such climbs
    private static boolean openUnder(net.minecraft.client.multiplayer.ClientLevel level, int x, int y, int z, int low) {
        if (!level.hasChunk(x >> 4, z >> 4)) return false;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int yy = y + 2; yy >= Math.min(y, low) - TradeGoldRules.WAY_FLOOR_DOWN; yy--) {
            at.set(x, yy, z);
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(at);
            if (st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return true;
            if (!st.getCollisionShape(level, at).isEmpty()) return false;
        }
        return true;
    }

    // (gold_way_open) the nearest ores whose way is asked at each pick; past them the step searches as with none known
    static final int WAY_TESTS = 40;
    // (gold_clock_near, gold_path_fail) within this many blocks of the ore the buddy is at it
    static final double NEAR_BLOCKS = 6;

    static boolean goldClockNearOn() {
        return adris.altoclef.util.helpers.Tuning.get("gold_clock_near", 0) > 0;
    }

    static boolean goldPathFailOn() {
        return adris.altoclef.util.helpers.Tuning.get("gold_path_fail", 0) > 0;
    }

    // (19 Nov 2026, the second smoke: the block tracker's scan takes the 25 nearest gold ores and only then drops the ones with
    // no open face; with Paper's anti-xray in engine mode 2, as the rig's nether and Tweakcraft run it, every hidden block of
    // the nether is sent as a random one of its hidden list, so the 25 were fakes in the walls and real ore was known only a
    // block or two off: 0 or 1 gold in five to nine minutes, four runs of four.) the nether gold ore with an open face (air or
    // a fluid next to it, which such a server always sends as it is) in the loaded chunks within SCAN_CHUNKS of the feet, and
    // the band TradeGoldRules.bandLow/bandHigh round the step's start height (bandY), never down to the lava sea. (the third
    // smoke, 19 Nov 2026: a band round the feet went with them, up a cliff to y 93 and a fall of 32 blocks, and down from a
    // start at y 84 to ore at y 23, under the lava sea's top, and into it; both runs died in the step.) ore with lava within
    // LAVA_NEAR of it is left: a break by lava lets it in
    static List<BlockPos> scanOres(net.minecraft.client.multiplayer.ClientLevel level, BlockPos feet, int bandY) {
        List<BlockPos> out = new ArrayList<>();
        int fcx = feet.getX() >> 4, fcz = feet.getZ() >> 4;
        int minY = Math.max(level.getMinY(), TradeGoldRules.bandLow(bandY, level.getSeaLevel()));
        int maxY = Math.min(level.getMinY() + level.getHeight() - 1, TradeGoldRules.bandHigh(bandY));
        java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> gold = st -> st.is(Blocks.NETHER_GOLD_ORE);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int cx = fcx - SCAN_CHUNKS; cx <= fcx + SCAN_CHUNKS; cx++) {
            for (int cz = fcz - SCAN_CHUNKS; cz <= fcz + SCAN_CHUNKS; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
                net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
                for (int y = minY; y <= maxY; y++) {
                    int si = (y - chunk.getMinY()) >> 4;
                    if (si < 0 || si >= sections.length) continue;
                    net.minecraft.world.level.chunk.LevelChunkSection sec = sections[si];
                    if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(gold)) {
                        y |= 15;
                        continue;
                    }
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            if (!sec.getBlockState(x, y & 15, z).is(Blocks.NETHER_GOLD_ORE)) continue;
                            at.set((cx << 4) + x, y, (cz << 4) + z);
                            if (openFace(level, at) && !lavaNear(level, at) && !overADrop(level, at)) out.add(at.immutable());
                        }
                    }
                }
            }
        }
        return out;
    }

    // air or a fluid on a side: the server sends a block as it is then
    private static boolean openFace(net.minecraft.client.multiplayer.ClientLevel level, BlockPos pos) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            net.minecraft.world.level.block.state.BlockState n = level.getBlockState(pos.relative(d));
            if (n.isAir() || !n.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    // (19 Nov 2026, nethergoud4 run 6) open to a fall under the ore: TradeGoldRules.DROP_MAX cells or more with nothing to stand on
    // (air, or lava) right under it. a ledge's ore over the lava sea was broken from on top, the buddy standing on it, and the fall
    // of 45 blocks ended in the lava
    private static boolean overADrop(net.minecraft.client.multiplayer.ClientLevel level, BlockPos ore) {
        int open = 0;
        for (int i = 1; i <= TradeGoldRules.DROP_MAX; i++) {
            BlockPos p = ore.below(i);
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
            if (!st.getCollisionShape(level, p).isEmpty() && st.getFluidState().isEmpty()) break;
            open++;
        }
        return TradeGoldRules.dropTooDeep(open);
    }

    // lava within LAVA_NEAR of the ore, any way
    private static boolean lavaNear(net.minecraft.client.multiplayer.ClientLevel level, BlockPos ore) {
        int r = TradeGoldRules.LAVA_NEAR;
        for (BlockPos p : BlockPos.betweenClosed(ore.offset(-r, -r, -r), ore.offset(r, r, r))) {
            if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA)) return true;
        }
        return false;
    }

    // gold nuggets lying within PICKUP_BLOCKS sideways and PICKUP_DY up or down: one that fell down a cliff is left there (the
    // pickup walked off a ledge once)
    private static int nuggetsNear(AltoClef mod) {
        int n = 0;
        LocalPlayer p = mod.getPlayer();
        AABB box = new AABB(p.getX() - PICKUP_BLOCKS, p.getY() - PICKUP_DY, p.getZ() - PICKUP_BLOCKS,
                p.getX() + PICKUP_BLOCKS, p.getY() + PICKUP_DY + 2, p.getZ() + PICKUP_BLOCKS);
        for (net.minecraft.world.entity.item.ItemEntity e : mod.getWorld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box,
                e -> e.isAlive() && e.getItem().is(Items.GOLD_NUGGET))) {
            n += e.getItem().getCount();
        }
        return n;
    }

    // no ore known: on to the next point of the rings round where the step began (FireResTradeRules.searchPoint), the first
    // ring's bearing away from the spawner, a point passed over near the spawner, one not reached in its time given up; the
    // tracker sees more of the nether on the way, and the mine takes over as soon as it knows ore. null once all are walked
    private Task search(AltoClef mod, long now) {
        LocalPlayer player = mod.getPlayer();
        if (_searchK < 0) {
            _searchFrom = TradeGoldRules.searchFrom(_anchor0().getX(), _anchor0().getZ(), _spawner, Math.toRadians(player.getYRot() + 90));
        }
        boolean next = _searchWalk == null || _searchWalk.isFinished(mod) || now < _pointSince
                || now - _pointSince > FireResTradeRules.POINT_TICKS;
        if (next) {
            FireResTradeRules.Spot point = null;
            BlockPos feet = player.blockPosition();
            // (gold_search_way) the points left are said in one line: a line each made the live watch's "over and over"
            int seaLeft = 0, seaFirst = -1;
            while (++_searchK < FireResTradeRules.RING_POINTS * FireResTradeRules.RINGS) {
                FireResTradeRules.Spot s = FireResTradeRules.searchPoint(_searchK, _anchor0().getX(), _anchor0().getZ(), _searchFrom);
                if (!TradeGoldRules.awayFromSpawner(s.x(), s.z(), _spawner)) continue;
                // (gold_search_way) a point across the lava sea is left as the ore trips leave such ore (gold_way_open, his 55)
                int max = TradeGoldRules.wayOpenMax();
                if (TradeGoldRules.searchWayOn() && max > 0) {
                    var level = mod.getWorld();
                    int sea = level.getSeaLevel();
                    int open = TradeGoldRules.openColumns(feet.getX(), feet.getY(), feet.getZ(), s.x(), feet.getY(), s.z(),
                            (x, y, z) -> openUnder(level, x, y, z, sea), max);
                    if (open > max) {
                        if (seaLeft++ == 0) seaFirst = _searchK;
                        continue;
                    }
                }
                point = s;
                break;
            }
            if (seaLeft > 0) {
                Debug.logInternal(String.format(Locale.ROOT, "trade gold: %d search points from %d on left, their way over the lava sea (gold_search_way)",
                        seaLeft, seaFirst));
            }
            if (point == null) return null;
            _pointSince = now;
            _searchWalk = new adris.altoclef.tasks.movement.GetToXZTask(point.x(), point.z(), 4);
            Debug.logInternal(String.format(Locale.ROOT, "trade gold: searching for ore, point %d at %d %d, %.0f blocks off",
                    _searchK, point.x(), point.z(), point.distanceTo(player.getX(), player.getZ())));
        }
        return _searchWalk;
    }

    // where the step began (the first anchor), the search's centre
    private BlockPos _anchor0() {
        return _start != null ? _start : _anchor;
    }

    // a new task object only when its kind or target changes, or its pathing and crafting would start again every tick
    private Task step(AltoClef mod, String kind, int target, java.util.function.Supplier<Task> make, String hud) {
        if (_step == null || !kind.equals(_stepKind) || target != _stepTarget || _step.isFinished(mod)) {
            _step = make.get();
            _stepKind = kind;
            _stepTarget = target;
        }
        _hud = hud;
        return _step;
    }

    private void over(AltoClef mod, long now, String why, int ingots, int nuggets) {
        _over = true;
        _overWhy = why;
        unguard();
        int got = TradeGoldRules.ninths(ingots, nuggets) - _ninthsAtBegin;
        double hours = Math.max(1e-9, (now - _begunAt) / 72000.0);
        Debug.logInternal(String.format(Locale.ROOT, "trade gold: over (%s), %d gold, %d nuggets left, %d gold dug in %s s (%.1f an hour), %d trips, %d ores broken; %d stone pickaxes begun, %d cobblestone left",
                why, ingots, nuggets, got / 9, seconds(now - _begunAt), got / 9.0 / hours, _anchors, _broken, _stonePicks,
                mod.getItemStorage().getItemCount(Items.COBBLESTONE)));
    }

    // a died buddy, or an order dropped: the guard off and the tracker freed (the trade's own end does not ask it)
    void stop(AltoClef mod) {
        if (_begun && !_over) unguard();
    }

    // the piglins we can see of (the client's entity list) within the range the pick asks, and baritone kept off gold ore
    // while one is in the box a break would anger
    private void guard(AltoClef mod) {
        LocalPlayer p = mod.getPlayer();
        List<BlockPos> seen = new ArrayList<>();
        AABB wide = p.getBoundingBox().inflate(TradeGoldRules.PIGLIN_CLEAR + NetherGold.ORE_BLOCKS);
        for (Piglin piglin : mod.getWorld().getEntitiesOfClass(Piglin.class, wide, Entity::isAlive)) {
            seen.add(piglin.blockPosition());
        }
        _piglins = seen;
        boolean inBox = !mod.getWorld().getEntitiesOfClass(Piglin.class, NetherGold.angerBox(p.getBoundingBox()), Entity::isAlive).isEmpty();
        if (inBox) {
            _guardHold = GUARD_HOLD_TICKS;
            if (!_guarding) {
                for (Block b : GUARDED) baritone.api.BaritoneAPI.getSettings().blocksToDisallowBreaking.value.add(b);
                _guarding = true;
                Debug.logInternal("trade gold: a piglin within " + (int) NetherGold.ANGER_BLOCKS + " blocks, no gold ore broken till it is off");
            }
        } else if (_guarding && --_guardHold <= 0) {
            unguard();
        }
    }

    private void unguard() {
        if (!_guarding) return;
        for (Block b : GUARDED) baritone.api.BaritoneAPI.getSettings().blocksToDisallowBreaking.value.remove(b);
        _guarding = false;
    }

    // a hostile within TradeGoldRules.DANGER_BLOCKS: no craft out in the open then (the mine goes on, the defence chain fights)
    static boolean dangerNear(AltoClef mod) {
        AABB box = mod.getPlayer().getBoundingBox().inflate(TradeGoldRules.DANGER_BLOCKS);
        return !mod.getWorld().getEntities((Entity) null, box,
                e -> e.isAlive() && e instanceof Enemy && !(e instanceof ZombifiedPiglin) && !(e instanceof Piglin)).isEmpty();
    }

    private static boolean tableOrWood(AltoClef mod) {
        var st = mod.getItemStorage();
        if (st.getItemCount(Items.CRAFTING_TABLE) > 0) return true;
        int planks = 0;
        for (var item : adris.altoclef.util.helpers.ItemHelper.PLANKS) planks += st.getItemCount(item);
        int logs = 0;
        for (var item : adris.altoclef.util.helpers.ItemHelper.LOG) logs += st.getItemCount(item);
        return planks + NetherGold.PLANKS_PER_LOG * logs >= NetherGold.PLANKS_PER_TABLE;
    }

    private static String seconds(long ticks) {
        return Long.toString(ticks / 20);
    }
}
