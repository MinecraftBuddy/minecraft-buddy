package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.speedrun.gamer.NetherGold;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.RecipeTarget;
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
    // nothing passes the mine's pick for this long, and the next ore is looked for further off (game ticks)
    static final long WANDER_TICKS = 4 * 20;
    // a piglin left the box this long ago and baritone may break gold ore again (a piglin on the box's edge would flip it)
    static final int GUARD_HOLD_TICKS = 20;
    private static final Block[] GUARDED = {Blocks.NETHER_GOLD_ORE, Blocks.GILDED_BLACKSTONE};

    private boolean _begun;
    private boolean _over;
    private long _begunAt;
    private long _riseAt;
    private long _noOreSince = -1;
    private long _wanderSince = -1;
    private int _bestNinths;
    private int _ninthsAtBegin;
    private int _progressLogged;
    private int _anchors;
    private BlockPos _anchor;
    // anchors whose ore the mine would not take (out of its reach, or given up): their reach is not an anchor again
    private final List<BlockPos> _spent = new ArrayList<>();
    private Task _step;
    private String _stepKind;
    private int _stepTarget;
    private boolean _guarding;
    private int _guardHold;
    private List<BlockPos> _piglins = List.of();
    private String _hud = "Digging gold for the piglins";

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

    // the dig's step this tick, or null once there is nothing (more) to dig: the trade then goes on with the bag's gold.
    // enough: the trade's target is in the bag already (a potion); atFortress: by the spawner or a blaze near
    Task tick(AltoClef mod, boolean enough, boolean atFortress) {
        if (_over || !on()) return null;
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        int ingots = mod.getItemStorage().getItemCount(Items.GOLD_INGOT);
        int nuggets = mod.getItemStorage().getItemCount(Items.GOLD_NUGGET);
        int target = target();
        if (!_begun) {
            if (WorldHelper.getCurrentDimension() != Dimension.NETHER) return null;
            String why = TradeGoldRules.whyNot(ingots, nuggets, target, enough, atFortress,
                    mod.getItemStorage().getItemCount(Items.BLAZE_ROD), tableOrWood(mod));
            if (why != null) {
                Debug.logInternal("trade gold: not begun (" + why + ")");
                _over = true;
                return null;
            }
            _begun = true;
            _begunAt = now;
            _riseAt = now;
            _anchor = player.blockPosition();
            _ninthsAtBegin = _bestNinths = TradeGoldRules.ninths(ingots, nuggets);
            mod.getBlockTracker().trackBlock(Blocks.NETHER_GOLD_ORE);
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
        // the end: the target in ingots, the fortress (the walk there went on while no ore was known), or a clock; the last
        // nuggets folded first
        String end = ingots >= target ? "the target reached" : atFortress ? "at the fortress or a blaze near"
                : TradeGoldRules.overWhy(now - _begunAt, now - _riseAt, _noOreSince < 0 ? 0 : now - _noOreSince, capTicks(), dryTicks());
        boolean danger = dangerNear(mod);
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
        // the mine: known ore within reach of the anchor with no piglin near it; with none, the nearest known ore further off
        // becomes the anchor; with none of that either, the clock of no ore runs
        if (!oreHere(mod)) {
            BlockPos next = nextAnchor(mod, player.blockPosition());
            if (next != null) {
                _anchors++;
                Debug.logInternal(String.format(Locale.ROOT, "trade gold: on to ore at %s, %.0f blocks off (anchor %d)",
                        next.toShortString(), Math.sqrt(next.distSqr(player.blockPosition())), _anchors));
                _anchor = next;
                _step = null;
            } else {
                if (_noOreSince < 0) {
                    _noOreSince = now;
                    Debug.logInternal("trade gold: no gold ore known within reach with no piglin near it");
                }
                _step = null;
                _hud = "Looking for gold ore for the piglins";
                return null;
            }
        }
        _noOreSince = -1;
        int need = TradeGoldRules.nuggetsWanted(ingots, nuggets, target);
        BlockPos anchor = _anchor;
        Task mine = step(mod, "mine", need, () -> new MineAndCollectTask(new ItemTarget(Items.GOLD_NUGGET, need),
                new Block[]{Blocks.NETHER_GOLD_ORE}, MiningRequirement.WOOD)
                .onlyWhere(pos -> NetherGold.oreInReach(pos.getX(), pos.getY(), pos.getZ(), anchor.getX(), anchor.getY(), anchor.getZ())
                        && TradeGoldRules.clearOfPiglins(pos, _piglins)), "Digging gold for the piglins");
        if (mine instanceof MineAndCollectTask m && m.wandering()) {
            if (_wanderSince < 0) _wanderSince = now;
            if (now - _wanderSince > WANDER_TICKS) {
                // nothing here passes the pick any more (a piglin came, or the tracker lists ore the mine gave up on): next anchor
                _wanderSince = -1;
                _spent.add(_anchor);
                BlockPos next = nextAnchor(mod, player.blockPosition());
                if (next != null) {
                    _anchors++;
                    Debug.logInternal(String.format(Locale.ROOT, "trade gold: the ore here is out of reach, on to ore at %s, %.0f blocks off (anchor %d)",
                            next.toShortString(), Math.sqrt(next.distSqr(player.blockPosition())), _anchors));
                    _anchor = next;
                    _step = null;
                } else if (_noOreSince < 0) {
                    _noOreSince = now;
                    Debug.logInternal("trade gold: the mine finds no ore it may take here");
                }
            }
        } else {
            _wanderSince = -1;
        }
        return mine;
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
        unguard();
        mod.getBlockTracker().stopTracking(Blocks.NETHER_GOLD_ORE);
        int got = TradeGoldRules.ninths(ingots, nuggets) - _ninthsAtBegin;
        double hours = Math.max(1e-9, (now - _begunAt) / 72000.0);
        Debug.logInternal(String.format(Locale.ROOT, "trade gold: over (%s), %d gold, %d nuggets left, %d gold dug in %s s (%.1f an hour), %d anchors",
                why, ingots, nuggets, got / 9, seconds(now - _begunAt), got / 9.0 / hours, _anchors));
    }

    // a died buddy, or an order dropped: the guard off and the tracker freed (the trade's own end does not ask it)
    void stop(AltoClef mod) {
        if (_begun && !_over) {
            unguard();
            mod.getBlockTracker().stopTracking(Blocks.NETHER_GOLD_ORE);
        }
    }

    private boolean oreHere(AltoClef mod) {
        for (BlockPos p : mod.getBlockTracker().getKnownLocations(Blocks.NETHER_GOLD_ORE)) {
            if (NetherGold.oreInReach(p.getX(), p.getY(), p.getZ(), _anchor.getX(), _anchor.getY(), _anchor.getZ())
                    && !spent(p) && TradeGoldRules.clearOfPiglins(p, _piglins) && mod.getWorld().getBlockState(p).is(Blocks.NETHER_GOLD_ORE)) {
                return true;
            }
        }
        return false;
    }

    // the nearest known gold ore off the anchor's reach, about the feet' height and clear of piglins, as the next anchor
    private BlockPos nextAnchor(AltoClef mod, BlockPos feet) {
        BlockPos best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (BlockPos p : mod.getBlockTracker().getKnownLocations(Blocks.NETHER_GOLD_ORE)) {
            if (!TradeGoldRules.anchorOk(p.getX(), p.getY(), p.getZ(), feet.getX(), feet.getY(), feet.getZ())) continue;
            if (spent(p)) continue;
            if (!TradeGoldRules.clearOfPiglins(p, _piglins) || !mod.getWorld().getBlockState(p).is(Blocks.NETHER_GOLD_ORE)) continue;
            double d = p.distSqr(feet);
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }

    private boolean spent(BlockPos p) {
        for (BlockPos a : _spent) {
            if (NetherGold.oreInReach(p.getX(), p.getY(), p.getZ(), a.getX(), a.getY(), a.getZ())) return true;
        }
        return false;
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
    private static boolean dangerNear(AltoClef mod) {
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
