package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.speedrun.gamer.NetherGold;
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
        int target = target();
        if (!_begun) {
            if (WorldHelper.getCurrentDimension() != Dimension.NETHER) return null;
            String why = TradeGoldRules.whyNot(ingots, nuggets, target, enough, atFortress || blazeNear,
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
        String end = ingots >= target ? "the target reached" : atFortress ? "at the fortress"
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
        // the mine: the nearest exposed gold ore of the step's own scan (scanOres), clear of piglins and the spawner; its nuggets
        // picked up after the break; with none, the search walks and the clock of no ore runs
        refreshOres(mod, now);
        BlockPos feet = player.blockPosition();
        if (_target != null && (!mod.getWorld().getBlockState(_target).is(Blocks.NETHER_GOLD_ORE)
                || !TradeGoldRules.clearOfPiglins(_target, _piglins))) {
            if (!mod.getWorld().getBlockState(_target).is(Blocks.NETHER_GOLD_ORE)) {
                _broken++;
                _pickupSince = now;
                _scannedAt = Long.MIN_VALUE;
            }
            _target = null;
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
            _target = nearestOre(mod, feet);
            _targetSince = now;
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
        BlockPos at = _target;
        return step(mod, "ore " + at.toShortString(), 0, () -> new adris.altoclef.tasks.construction.DestroyBlockTask(at),
                "Digging gold for the piglins");
    }

    // the step's own look for ore, every ORE_SCAN_TICKS and at once after a break (the vein's next ore shows once the broken
    // one opens it up)
    private void refreshOres(AltoClef mod, long now) {
        if (_scannedAt != Long.MIN_VALUE && now >= _scannedAt && now - _scannedAt < ORE_SCAN_TICKS) return;
        _scannedAt = now;
        _ores = scanOres(mod.getWorld(), mod.getPlayer().blockPosition(), _anchor0().getY());
    }

    // the nearest scanned ore still there, clear of piglins and the spawner, not given up on
    private BlockPos nearestOre(AltoClef mod, BlockPos feet) {
        BlockPos best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (BlockPos p : _ores) {
            if (_givenUp.contains(p) || !TradeGoldRules.clearOfPiglins(p, _piglins) || !TradeGoldRules.awayFromSpawner(p.getX(), p.getZ(), _spawner)
                    || !mod.getWorld().getBlockState(p).is(Blocks.NETHER_GOLD_ORE)) continue;
            double d = p.distSqr(feet);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
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
            while (++_searchK < FireResTradeRules.RING_POINTS * FireResTradeRules.RINGS) {
                FireResTradeRules.Spot s = FireResTradeRules.searchPoint(_searchK, _anchor0().getX(), _anchor0().getZ(), _searchFrom);
                if (TradeGoldRules.awayFromSpawner(s.x(), s.z(), _spawner)) {
                    point = s;
                    break;
                }
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
        unguard();
        int got = TradeGoldRules.ninths(ingots, nuggets) - _ninthsAtBegin;
        double hours = Math.max(1e-9, (now - _begunAt) / 72000.0);
        Debug.logInternal(String.format(Locale.ROOT, "trade gold: over (%s), %d gold, %d nuggets left, %d gold dug in %s s (%.1f an hour), %d trips, %d ores broken",
                why, ingots, nuggets, got / 9, seconds(now - _begunAt), got / 9.0 / hours, _anchors, _broken));
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
