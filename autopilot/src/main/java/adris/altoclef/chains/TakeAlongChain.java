package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.takealong.TakeAlong;
import adris.altoclef.takealong.TakeAlongKinds;
import adris.altoclef.takealong.TakeAlongKinds.Kind;
import adris.altoclef.takealong.TakeAlongRules;
import adris.altoclef.takealong.TakeAlongRules.Choice;
import adris.altoclef.takealong.TakeAlongRules.Cluster;
import adris.altoclef.takealong.TakeAlongRules.Drop;
import adris.altoclef.takealong.TakeAlongRules.Part;
import adris.altoclef.takealong.TakeAlongRules.Prey;
import adris.altoclef.takealong.TakeAlongRules.Seen;
import adris.altoclef.takealong.TakeAlongRules.Spot;
import adris.altoclef.takealong.TakeAlongRules.State;
import adris.altoclef.takealong.TakeAlongRules.Take;
import adris.altoclef.takealong.TakeAlongRules.Wish;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.resources.KillAndLootTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.speedrun.gamer.DetourRules;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Offset;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Step;
import adris.altoclef.tasks.speedrun.gamer.GamerTask;
import adris.altoclef.tasks.speedrun.gamer.ResourceDetour;
import adris.altoclef.tasks.speedrun.gamer.config.TakeAlongConfig;
import adris.altoclef.tasks.squashed.GatherBillTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemPickupRules;
import adris.altoclef.util.helpers.RecipeBill;
import adris.altoclef.util.helpers.WalkCost;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

// taking things along on the way (TakeAlong, TakeAlongRules): under any user task, a cluster of blocks the caller's order
// still lacks, seen close by, gets a short detour when what it brings is worth k times what it costs. 51 while a detour runs:
// above the user task, which is interrupted and starts again from its kept state, and below everything that keeps us alive
// (CombatBrain's recovery 53, eating 55, the survival chains). -inf otherwise. the detour runs on DetourRules' state machine,
// the gamer's coal, gravel, diamond and gold detours run on it too; this is its world half for any raw item the caller
// wishes for. only blocks in sight right now are started on (DetourSight), and the mining is the catalogue's own
// MineAndCollectTask. drops on the floor of anything the order still wants (a raw item, or a made one such as an iron ingot,
// worth what its inputs are) get the same measure without the breaking, and a pickup of just the drops seen. and animals
// in sight, while the caller wishes for food (TakeAlong.food), are food points by the same measure: a hunt of just those.
// off while #gamer runs, which has detours of its own
public class TakeAlongChain extends SingleTaskChain {
    public static final float PRIORITY = 51;
    // the wishes are worked out again this often even with the same order, the bag changes under us (game ticks)
    private static final long REFRESH_TICKS = 100;
    // at most this many blocks seen go into the clusters, the nearest first
    private static final int MAX_SEEN = 256;
    // the running status is written again this often (game ticks), for whoever reads it
    private static final long STATUS_TICKS = 20;

    private DetourRules _rules;
    private long _rulesCooldown = Long.MIN_VALUE;
    private final DetourRules.Tally _tally = new DetourRules.Tally();
    private Map<String, Wish> _wishes = Map.of();
    // the wishes a drop answers: the bill's made items too (TakeAlongRules.dropWishes)
    private Map<String, Wish> _dropWishes = Map.of();
    private long _wishesOrder = -1;
    private long _wishesAt = Long.MIN_VALUE;
    private int _bagSig;
    // one per getPriority, so a detour that asked for the wheel and did not get it knows (see getPriority)
    private long _asked;
    private long _tickedAt = -1;
    private long _startedAsked = -1;
    private int _looks;
    // what the last start look found, the detour begins on it. for drops, the drops it counted by their spots
    private Choice _found;
    private Map<Spot, ItemEntity> _foundDrops;
    private Map<Spot, Entity> _foundPrey;
    private Detour _detour;
    // [start, end] game ticks of the detours in the share window
    private final ArrayDeque<long[]> _spent = new ArrayDeque<>();
    // the mining task asks the sight question for every candidate, every tick: same block same tick is the same answer
    private final Map<Long, Boolean> _sight = new HashMap<>();
    private long _sightTick = Long.MIN_VALUE;
    // the best cluster or drop group of the last look that was not worth it, for the "skipping" line
    private Choice _skipBlocks;
    private Choice _skipDrops;
    private Choice _skipPrey;
    // what the last "skipping" line was about (the cluster's nearest block) and when it went out
    private String _skipSaid;
    private long _skipSaidAt;
    private static final long SKIP_REPEAT_TICKS = 60 * 20;

    // one detour under way. lo/hi = the cluster's box (world coordinates), anchor = where it began: the keep reach, the leash
    // and the ban are measured from there, the player is what wanders off
    private static final class Detour {
        final long id;
        final Choice choice;
        final BlockPos anchor;
        final BlockPos lo;
        final BlockPos hi;
        final double budget;
        final Map<Kind, Integer> target;
        final Map<Kind, Integer> heldAtStart;
        final long startTick;
        // the drops it set out for, null for a detour for blocks
        final List<ItemEntity> drops;
        // the animals it set out to hunt, null otherwise; then the target is in food points
        List<Entity> animals;
        int foodTarget;
        double foodAtStart;
        long statusAt;

        Detour(long id, Choice choice, BlockPos anchor, BlockPos lo, BlockPos hi, double budget, Map<Kind, Integer> target,
               Map<Kind, Integer> heldAtStart, long startTick, List<ItemEntity> drops) {
            this.id = id;
            this.choice = choice;
            this.anchor = anchor;
            this.lo = lo;
            this.hi = hi;
            this.budget = budget;
            this.target = target;
            this.heldAtStart = heldAtStart;
            this.startTick = startTick;
            this.drops = drops;
            this.statusAt = startTick;
        }
    }

    public TakeAlongChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public float getPriority(AltoClef mod) {
        _asked++;
        LocalPlayer player = mod.getPlayer();
        ClientLevel level = mod.getWorld();
        TakeAlong.Order order = TakeAlong.order();
        if (player == null || level == null || order == null || !underUserTask(mod)) {
            if (_detour != null) {
                end(mod, level == null ? _detour.startTick : level.getGameTime(), "stopped",
                        order == null ? "taking along was switched off" : "no user task to take things along for");
            }
            return Float.NEGATIVE_INFINITY;
        }
        long now = level.getGameTime();
        // asked for the wheel and a chain above had it instead: that ends the detour as onInterrupt would have. the tick it
        // started on is not counted, the runner had not picked yet
        if (_detour != null && _tickedAt < _asked - 1 && _startedAsked < _asked - 1) {
            end(mod, now, "preempted", "a chain above had the wheel");
            return Float.NEGATIVE_INFINITY;
        }
        TakeAlongConfig cfg = TakeAlong.config();
        // looking is the part that costs (thousands of block reads), a detour that is going is asked about every tick
        if (_detour == null && ++_looks % Math.max(1, cfg.lookTicks) != 0) {
            return Float.NEGATIVE_INFINITY;
        }
        refreshWishes(mod, player, order, now);
        DetourRules rules = rules(cfg);
        State state = state(mod, player, level, now, cfg);
        Step step = rules.tick(now, inputs(mod, player, state, cfg), limits(cfg), ore(mod, player, state, cfg));
        switch (step) {
            case START:
                begin(mod, player, now, cfg);
                return PRIORITY;
            case KEEP:
            case SETTLE:
                if (_detour == null) {
                    // the start never got its detour (it threw halfway): nothing to keep going
                    rules.preempted(now);
                    return Float.NEGATIVE_INFINITY;
                }
                if (now - _detour.statusAt >= STATUS_TICKS) {
                    _detour.statusAt = now;
                    TakeAlong.running(status(mod, _detour, now, "running", null));
                }
                return PRIORITY;
            case TIMEOUT:
            case STRAYED:
                int banned = ban(mod, cfg);
                end(mod, now, step.name().toLowerCase(), (step == Step.TIMEOUT ? "out of time" : "led too far from where it began")
                        + ", " + banned + (_detour == null ? " blocks" : _detour.animals != null ? " animals" : _detour.drops != null ? " drops"
                        : " blocks") + " banned for a while");
                return Float.NEGATIVE_INFINITY;
            case DONE:
                end(mod, now, "done", "the cluster is mined or the bag holds what it came for");
                return Float.NEGATIVE_INFINITY;
            case CAPPED:
                end(mod, now, "capped", "dug the blocks it was counted for");
                return Float.NEGATIVE_INFINITY;
            case BLOCKED:
                String why = TakeAlongRules.stopping(state, cfg);
                end(mod, now, "blocked", why != null ? why : "no tool for what is left");
                return Float.NEGATIVE_INFINITY;
            default:
                return Float.NEGATIVE_INFINITY;
        }
    }

    @Override
    protected void onTick(AltoClef mod) {
        _tickedAt = _asked;
        super.onTick(mod);
    }

    // a chain above took the wheel (a fight, eating, the survival chains): the detour ends at once and is not a pause. the
    // user task gets the wheel back after them, and if the cluster is still there after the rest it is a fresh detour
    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {
        if (_detour != null) {
            ClientLevel level = mod.getWorld();
            end(mod, level == null ? _detour.startTick : level.getGameTime(), "preempted",
                    other == null ? "interrupted" : other.getName() + " took over");
        }
    }

    @Override
    protected void onStop(AltoClef mod) {
        if (_detour != null) {
            ClientLevel level = mod.getWorld();
            end(mod, level == null ? _detour.startTick : level.getGameTime(), "stopped", "the task runner stopped");
        }
        super.onStop(mod);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // the rules end the detour on the next tick (enough), the finished task waits for that
    }

    // always asked: the look for a cluster happens in getPriority, and -inf never takes the wheel
    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getName() {
        return "Take Along";
    }

    @Override
    public String getHudName() {
        return "Taking along";
    }

    // ---- the rules' inputs

    private DetourRules rules(TakeAlongConfig cfg) {
        long cooldown = Math.round(cfg.cooldownSeconds * 20);
        if (_rules == null || cooldown != _rulesCooldown) {
            _rules = new DetourRules(cooldown);
            _rulesCooldown = cooldown;
        }
        return _rules;
    }

    private static boolean underUserTask(AltoClef mod) {
        Task task = mod.getUserTaskChain().getCurrentTask();
        return task != null && !(task instanceof GamerTask);
    }

    private DetourRules.Limits limits(TakeAlongConfig cfg) {
        return new DetourRules.Limits(Integer.MAX_VALUE, Math.round(cfg.seconds * 20), _detour == null ? 0 : _detour.choice.mineCap());
    }

    // idle: the never list keeps a look from starting, the rest is the look's (0 held of 1 is never enough). under way: the
    // never list, a tool for what is still short, and held against the target summed over the raw items (enough = every one met)
    private DetourRules.Inputs inputs(AltoClef mod, LocalPlayer player, State state, TakeAlongConfig cfg) {
        if (_detour == null) {
            boolean free = TakeAlongRules.blocked(state, cfg) == null && (!_wishes.isEmpty() || !_dropWishes.isEmpty() || TakeAlong.food() != null);
            return new DetourRules.Inputs(free, true, 0, 1, false, false, false, false, false, 0);
        }
        if (_detour.animals != null) {
            int held = (int) Math.floor(CollectFoodTask.foodPotential(mod));
            return new DetourRules.Inputs(TakeAlongRules.stopping(state, cfg) == null, true, Math.min(held, _detour.foodTarget),
                    _detour.foodTarget, false, false, false, false, false, 0);
        }
        if (_detour.drops == null && _detour.animals == null) {
            countBreaks(mod);
        }
        int held = 0;
        int need = 0;
        boolean shortWithTool = false;
        boolean allMet = true;
        for (Map.Entry<Kind, Integer> e : _detour.target.entrySet()) {
            int have = count(mod, e.getKey());
            held += Math.min(have, e.getValue());
            need += e.getValue();
            if (have < e.getValue()) {
                allMet = false;
                // a drop wants no tool, only a block does
                if (_detour.drops != null || speed(player, e.getKey()) > 0) {
                    shortWithTool = true;
                }
            }
        }
        boolean free = TakeAlongRules.stopping(state, cfg) == null;
        return new DetourRules.Inputs(free, shortWithTool || allMet, held, need, false, false, false, false, false, _tally.count());
    }

    private DetourRules.Ore ore(AltoClef mod, LocalPlayer player, State state, TakeAlongConfig cfg) {
        return new DetourRules.Ore(() -> (_found = look(mod, player, state, cfg)) != null, () -> keep(mod, cfg),
                () -> dropNear(mod, player), () -> strayed(player));
    }

    private State state(AltoClef mod, LocalPlayer p, ClientLevel level, long now, TakeAlongConfig cfg) {
        BlockPos head = p.blockPosition().above();
        boolean overworld = WorldHelper.getCurrentDimension() == Dimension.OVERWORLD;
        boolean wet = p.isInWater() || p.isInLava() || p.isOnFire();
        boolean nightSky = !level.isBrightOutside() && level.canSeeSky(head);
        return new State(overworld, p.getHealth(), wet, nightSky, foes(mod, p, cfg), TakeAlong.hold(), freeSlots(p),
                shareSeconds(now, cfg));
    }

    // a hostile close by that we can see, or that can see us
    private static boolean foes(AltoClef mod, LocalPlayer p, TakeAlongConfig cfg) {
        double r = cfg.foeRange * cfg.foeRange;
        for (Entity e : mod.getEntityTracker().getHostiles()) {
            if (e != null && e.isAlive() && e.distanceToSqr(p) <= r && p.hasLineOfSight(e)) {
                return true;
            }
        }
        return false;
    }

    // detour seconds within the share window, the one under way included
    private double shareSeconds(long now, TakeAlongConfig cfg) {
        long from = now - Math.round(cfg.shareWindowSeconds * 20);
        if (!_spent.isEmpty() && _spent.peekLast()[1] > now) {
            // a world with an earlier clock (a new world, a rejoin elsewhere), the old ones say nothing about it
            _spent.clear();
        }
        while (!_spent.isEmpty() && _spent.peekFirst()[1] < from) {
            _spent.pollFirst();
        }
        long ticks = 0;
        for (long[] s : _spent) {
            ticks += s[1] - Math.max(s[0], from);
        }
        if (_detour != null && now >= _detour.startTick) {
            ticks += now - Math.max(_detour.startTick, from);
        }
        return ticks / 20.0;
    }

    // ---- the wishes

    // the order's items as raw leaves with counts, through the bill GatherBillTask would work out. again when the order
    // changed, when the bag changed, or every REFRESH_TICKS. a detour under way keeps the targets it began with
    private void refreshWishes(AltoClef mod, LocalPlayer p, TakeAlong.Order order, long now) {
        if (_detour != null && order.id() == _wishesOrder) {
            return;
        }
        int sig = bagSignature(p);
        if (order.id() == _wishesOrder && sig == _bagSig && now >= _wishesAt && now - _wishesAt < REFRESH_TICKS) {
            return;
        }
        _wishesOrder = order.id();
        _bagSig = sig;
        _wishesAt = now;
        try {
            RecipeBill.Bill nowBill = order.now().isEmpty() ? null : GatherBillTask.billFor(mod, order.now());
            Map<String, Integer> all = order.all();
            RecipeBill.Bill allBill = all.isEmpty() ? null : GatherBillTask.billFor(mod, all);
            _wishes = TakeAlongRules.wishes(nowBill, allBill, order.values());
            _dropWishes = TakeAlongRules.dropWishes(nowBill, allBill, order.values());
        } catch (RuntimeException e) {
            Debug.logWarning("take along: the bill for the wishes failed, " + e);
            _wishes = Map.of();
            _dropWishes = Map.of();
        }
    }

    private static int bagSignature(LocalPlayer p) {
        int h = 1;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            h = 31 * h + (s.isEmpty() ? 0 : System.identityHashCode(s.getItem()) * 67 + s.getCount());
        }
        return h;
    }

    // the raw items the user task is mining itself right now: its own miner goes for them, close ones first
    // (altoResourceMineRange), and a detour for the same ore would only cost the switch
    private static Set<Kind> busy(AltoClef mod) {
        Set<Kind> out = new HashSet<>();
        for (Item item : busyItems(mod)) {
            Kind kind = TakeAlongKinds.ofItem(item);
            if (kind != null) {
                out.add(kind);
            }
        }
        return out;
    }

    // the items the user task's own resource tasks are after: they pick up their own drops (altoResourcePickupDropRange)
    private static Set<Item> busyItems(AltoClef mod) {
        Set<Item> out = new HashSet<>();
        for (Task t : mod.getUserTaskChain().getTasks()) {
            if (!(t instanceof ResourceTask rt) || rt.getItemTargets() == null) {
                continue;
            }
            for (ItemTarget target : rt.getItemTargets()) {
                if (target != null) {
                    out.addAll(List.of(target.getMatches()));
                }
            }
        }
        return out;
    }

    // ---- looking

    // the clusters and the drops in sight, valued, and the one to take: of the two the larger value - k x cost. null for none,
    // and then a line on the best one passed by
    private Choice look(AltoClef mod, LocalPlayer p, State state, TakeAlongConfig cfg) {
        _foundDrops = null;
        _foundPrey = null;
        _skipBlocks = null;
        _skipDrops = null;
        _skipPrey = null;
        if (TakeAlongRules.blocked(state, cfg) != null) {
            return null;
        }
        Choice blocks = lookBlocks(mod, p, state, cfg);
        Map<Spot, ItemEntity> drops = new IdentityHashMap<>();
        Choice fromDrops = lookDrops(mod, p, state, cfg, drops);
        Map<Spot, Entity> prey = new IdentityHashMap<>();
        Choice fromPrey = lookPrey(mod, p, state, cfg, prey);
        Choice choice = TakeAlongRules.better(TakeAlongRules.better(blocks, fromDrops, cfg), fromPrey, cfg);
        if (choice != null) {
            _foundDrops = choice == fromDrops ? drops : null;
            _foundPrey = choice == fromPrey ? prey : null;
            return choice;
        }
        Choice best = TakeAlongRules.better(TakeAlongRules.better(_skipBlocks, _skipDrops, cfg), _skipPrey, cfg);
        if (best != null) {
            String what = best == _skipPrey ? "food from " + animalsText(best) : (best == _skipDrops ? "drops of " : "") + best.items();
            String unit = best == _skipPrey ? " animals" : best == _skipDrops ? " drops" : " blocks";
            Spot nearest = best.takes().get(0).blocks().get(0);
            BlockPos at = p.blockPosition().offset(nearest.dx(), nearest.dy(), nearest.dz());
            // one line per cluster passed by, not one per look: the same cluster's cost changes with every step we take
            String key = what + " " + at.toShortString();
            long t = mod.getWorld().getGameTime();
            if (!key.equals(_skipSaid) || t - _skipSaidAt > SKIP_REPEAT_TICKS || t < _skipSaidAt) {
                _skipSaid = key;
                _skipSaidAt = t;
                Debug.logInternal("take along: skipping " + what + ", " + best.blocks() + unit + " at " + at.toShortString() + " worth "
                        + round(best.value()) + " s against a cost of " + round(best.cost()) + " s (k " + cfg.k + ")");
            }
        }
        return null;
    }

    // the animals that are food, in sight, grown, within the reach ceiling and a few blocks of our height, as groups standing
    // together, and the group to take while the caller wishes for food. not one the take-along gave up on, not one in water
    private Choice lookPrey(AltoClef mod, LocalPlayer p, State state, TakeAlongConfig cfg, Map<Spot, Entity> found) {
        TakeAlong.Food food = TakeAlong.food();
        if (food == null) {
            return null;
        }
        TakeAlongRules.Wish wish = TakeAlongRules.foodWish(CollectFoodTask.foodPotential(mod), food.needNow(), food.needAll(), food.value());
        if (TakeAlongRules.takeable(wish, cfg) <= 0) {
            return null;
        }
        BlockPos feet = p.blockPosition();
        Vec3 eyes = p.getEyePosition();
        double sight = cfg.sightRange * cfg.sightRange;
        List<Prey> seen = new ArrayList<>();
        for (Class<? extends Entity> type : PREY) {
            for (Entity e : mod.getEntityTracker().getTrackedEntities(type)) {
                if (!preyOk(mod, e)) {
                    continue;
                }
                String kind = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
                Double points = TakeAlongRules.PREY_POINTS.get(kind);
                if (points == null) {
                    continue;
                }
                BlockPos at = e.blockPosition();
                int dx = at.getX() - feet.getX(), dy = at.getY() - feet.getY(), dz = at.getZ() - feet.getZ();
                if (Math.abs(dy) > cfg.preyHeight || !WalkCost.within(dx, dy, dz, cfg.reachCeiling) || e.getEyePosition().distanceToSqr(eyes) > sight
                        || !p.hasLineOfSight(e)) {
                    continue;
                }
                Spot spot = new Spot(dx, dy, dz, 0);
                seen.add(new Prey(kind, points, spot));
                found.put(spot, e);
            }
        }
        if (seen.isEmpty()) {
            return null;
        }
        List<List<Prey>> groups = TakeAlongRules.groupPrey(seen, cfg.clusterGap + 1);
        Choice choice = TakeAlongRules.choosePrey(groups, wish, state, cfg);
        if (choice == null) {
            for (List<Prey> g : groups) {
                _skipPrey = TakeAlongRules.better(_skipPrey, TakeAlongRules.evaluatePrey(g, wish, state, cfg), cfg);
            }
        }
        return choice;
    }

    // the animals that are food, as the food task hunts them (a mooshroom is a cow)
    private static final List<Class<? extends Entity>> PREY = List.of(Cow.class, Pig.class, Sheep.class, Chicken.class);

    // an animal the hunt may go for: alive, grown, nobody riding it, not somebody's (named, on a lead, in a pen), not banned
    // by anybody or by us, dry
    private static boolean preyOk(AltoClef mod, Entity e) {
        if (!(e instanceof LivingEntity living) || !living.isAlive() || living.isBaby() || e.isVehicle() || e.isInWater()
                || adris.altoclef.tasks.resources.PreyScan.keptAnimal(e)) {
            return false;
        }
        return mod.getEntityTracker().isEntityReachable(e) && !BanPolicy.takeAlongEntityBanned(mod.getBans(), e.getId());
    }

    // the drops on the floor of anything still wished for, in sight and within the reach ceiling, as groups lying together,
    // and the group to take. not one the user task's own pickup goes for, not one we threw away ourselves (the caller says),
    // not in water or by lava, not one the take-along gave up on. `found` gets the drops by their spots
    private Choice lookDrops(AltoClef mod, LocalPlayer p, State state, TakeAlongConfig cfg, Map<Spot, ItemEntity> found) {
        if (_dropWishes.isEmpty()) {
            return null;
        }
        Set<Item> busy = busyItems(mod);
        BlockPos feet = p.blockPosition();
        Vec3 eyes = p.getEyePosition();
        double sight = cfg.sightRange * cfg.sightRange;
        List<Drop> seen = new ArrayList<>();
        for (ItemEntity e : mod.getEntityTracker().getDroppedItems()) {
            if (e == null || !e.isAlive() || e.getItem().isEmpty()) {
                continue;
            }
            Item item = e.getItem().getItem();
            String raw = TakeAlongRules.raw(BuiltInRegistries.ITEM.getKey(item).getPath());
            if (!_dropWishes.containsKey(raw) || busy.contains(item)) {
                continue;
            }
            BlockPos at = e.blockPosition();
            int dx = at.getX() - feet.getX(), dy = at.getY() - feet.getY(), dz = at.getZ() - feet.getZ();
            if (!WalkCost.within(dx, dy, dz, cfg.reachCeiling) || e.position().distanceToSqr(eyes) > sight) {
                continue;
            }
            if (!dropOk(mod, e) || !p.hasLineOfSight(e)) {
                continue;
            }
            Spot spot = new Spot(dx, dy, dz, 0);
            seen.add(new Drop(raw, e.getItem().getCount(), spot, stacks(p, TakeAlongKinds.ofDrop(raw, item))));
            found.put(spot, e);
            if (seen.size() >= MAX_SEEN) {
                break;
            }
        }
        if (seen.isEmpty()) {
            return null;
        }
        List<List<Drop>> groups = TakeAlongRules.groupDrops(seen, cfg.clusterGap);
        Choice choice = TakeAlongRules.chooseDrops(groups, _dropWishes, state, cfg);
        if (choice == null) {
            for (List<Drop> g : groups) {
                _skipDrops = TakeAlongRules.better(_skipDrops, TakeAlongRules.evaluateDrops(g, _dropWishes, state, cfg), cfg);
            }
        }
        return choice;
    }

    // a drop the pickup may go for: not banned by anybody or by us, not one of our own throws, dry and clear of lava
    private static boolean dropOk(AltoClef mod, ItemEntity e) {
        if (!mod.getEntityTracker().isEntityReachable(e) || BanPolicy.takeAlongEntityBanned(mod.getBans(), e.getId())) {
            return false;
        }
        if (e.isInWater() || e.isInLava() || ItemPickupRules.lavaBlocksPickup(e)) {
            return false;
        }
        return !TakeAlong.thrown(e);
    }

    // the clusters of blocks in sight, valued, and the one to take (TakeAlongRules.choose), null for none
    private Choice lookBlocks(AltoClef mod, LocalPlayer p, State state, TakeAlongConfig cfg) {
        if (_wishes.isEmpty()) {
            return null;
        }
        Set<Kind> busy = busy(mod);
        Map<Block, Kind> wanted = new HashMap<>();
        for (Wish w : _wishes.values()) {
            Kind kind = TakeAlongKinds.of(w.raw());
            if (kind == null || busy.contains(kind) || speed(p, kind) <= 0) {
                continue;
            }
            for (Block b : kind.blocks()) {
                wanted.put(b, kind);
            }
        }
        if (wanted.isEmpty()) {
            return null;
        }
        ClientLevel level = mod.getWorld();
        BlockPos feet = p.blockPosition();
        boolean surface = level.canSeeSky(feet.above());
        List<Seen> seen = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (Offset o : TakeAlongRules.offsets(cfg.reachCeiling, cfg.sightRange)) {
            at.set(feet.getX() + o.dx(), feet.getY() + o.dy(), feet.getZ() + o.dz());
            BlockState bs = level.getBlockState(at);
            Kind kind = wanted.get(bs.getBlock());
            if (kind == null || kind.surface() && !surface) {
                continue;
            }
            BlockPos pos = at.immutable();
            if (!pickable(mod, pos, kind, cfg)) {
                continue;
            }
            seen.add(new Seen(kind.raw(), new Spot(o.dx(), o.dy(), o.dz(), bs.getDestroySpeed(level, pos))));
            if (seen.size() >= MAX_SEEN) {
                break;
            }
        }
        if (seen.isEmpty()) {
            return null;
        }
        List<Cluster> clusters = new ArrayList<>();
        for (List<Seen> group : TakeAlongRules.group(seen, cfg.clusterGap)) {
            Map<String, List<Spot>> byRaw = new LinkedHashMap<>();
            for (Seen s : group) {
                byRaw.computeIfAbsent(s.raw(), k -> new ArrayList<>()).add(s.spot());
            }
            List<Part> parts = new ArrayList<>();
            byRaw.forEach((raw, spots) -> {
                Kind kind = TakeAlongKinds.of(raw);
                double speed = speed(p, kind);
                parts.add(new Part(raw, TakeAlongRules.YIELD.get(raw), speed > 0, Math.max(speed, 0), stacks(p, kind), spots));
            });
            clusters.add(new Cluster(parts));
        }
        Choice choice = TakeAlongRules.choose(clusters, _wishes, state, cfg);
        if (choice == null) {
            // the best one that was not worth it, for the log: what it was worth against what it cost
            for (Cluster c : clusters) {
                _skipBlocks = TakeAlongRules.better(_skipBlocks, TakeAlongRules.evaluate(c, _wishes, state, cfg), cfg);
            }
        }
        return choice;
    }

    // a block we may start on: not banned, breakable, no lava touching it, for gravel not a tall column over the head, and in
    // sight from the eyes right now (DetourSight, the take-along's range). the sight test last, it is the costly one
    private boolean pickable(AltoClef mod, BlockPos pos, Kind kind, TakeAlongConfig cfg) {
        if (BanPolicy.takeAlongBanned(mod.getBans(), WorldHelper.getCurrentDimension(), pos.getX(), pos.getY(), pos.getZ())) {
            return false;
        }
        if (!WorldHelper.canBreak(mod, pos) || ResourceDetour.lavaBeside(mod.getWorld(), pos)) {
            return false;
        }
        if (kind.column() && ResourceDetour.underTallColumn(mod, pos)) {
            return false;
        }
        return seen(mod, pos, cfg.sightRange);
    }

    private boolean seen(AltoClef mod, BlockPos pos, double range) {
        // the gui clock and not game time, which stands still under /tick freeze while we keep walking
        long now = Minecraft.getInstance().gui.hud.getGuiTicks();
        if (now != _sightTick) {
            _sight.clear();
            _sightTick = now;
        }
        return _sight.computeIfAbsent(pos.asLong(), k -> ResourceDetour.inSight(mod, pos, range));
    }

    // ---- the detour

    private void begin(AltoClef mod, LocalPlayer p, long now, TakeAlongConfig cfg) {
        Choice ch = _found;
        BlockPos feet = p.blockPosition();
        int loX = Integer.MAX_VALUE, loY = Integer.MAX_VALUE, loZ = Integer.MAX_VALUE;
        int hiX = Integer.MIN_VALUE, hiY = Integer.MIN_VALUE, hiZ = Integer.MIN_VALUE;
        for (Part part : ch.cluster().parts()) {
            for (Spot s : part.spots()) {
                loX = Math.min(loX, s.dx());
                loY = Math.min(loY, s.dy());
                loZ = Math.min(loZ, s.dz());
                hiX = Math.max(hiX, s.dx());
                hiY = Math.max(hiY, s.dy());
                hiZ = Math.max(hiZ, s.dz());
            }
        }
        int gap = cfg.clusterGap;
        BlockPos lo = feet.offset(loX - gap, loY - gap, loZ - gap);
        BlockPos hi = feet.offset(hiX + gap, hiY + gap, hiZ + gap);
        Map<Kind, Integer> target = new LinkedHashMap<>();
        Map<Kind, Integer> heldAtStart = new LinkedHashMap<>();
        double budget = Math.max(cfg.minBudget, ch.far());
        if (_foundDrops != null) {
            beginDrops(mod, ch, feet, lo, hi, budget, now, cfg);
            return;
        }
        if (_foundPrey != null) {
            beginHunt(mod, ch, feet, lo, hi, budget, now, cfg);
            return;
        }
        List<MineAndCollectTask> parts = new ArrayList<>();
        for (Take t : ch.takes()) {
            Kind kind = TakeAlongKinds.of(t.raw());
            target.put(kind, t.target());
            heldAtStart.put(kind, count(mod, kind));
            // arrays of our own: two miners with the same blocks are the same task to the task system, and the user task's
            // must never be ours
            parts.add(new MineAndCollectTask(new ItemTarget(kind.drops().clone(), t.target()), kind.blocks().clone(), kind.requirement()));
        }
        Detour d = new Detour(TakeAlong.nextId(), ch, feet, lo, hi, budget, target, heldAtStart, now, null);
        _detour = d;
        _tally.reset();
        _startedAsked = _asked;
        MineAndCollectTask mine = MineAndCollectTask.together(parts).onlyWhere(pos -> {
            Kind kind = kindAt(mod, pos);
            return kind != null && d.target.containsKey(kind) && inBox(d, pos) && pickable(mod, pos, kind, cfg);
        });
        setTask(mine);
        Debug.logInternal("take along: started on " + ch.items() + ", " + ch.blocks() + " blocks worth " + round(ch.value())
                + " s against a cost of " + round(ch.cost()) + " s, nearest " + round(ch.near()) + " away, targets " + targetsText(d));
        TakeAlong.running(status(mod, d, now, "running", null));
    }

    // a detour for drops: the drops the choice counted, a pickup of just those (the nearest one still wanted first)
    private void beginDrops(AltoClef mod, Choice ch, BlockPos feet, BlockPos lo, BlockPos hi, double budget, long now, TakeAlongConfig cfg) {
        Map<Kind, Integer> target = new LinkedHashMap<>();
        Map<Kind, Integer> heldAtStart = new LinkedHashMap<>();
        List<ItemEntity> drops = new ArrayList<>();
        Set<Item> items = new java.util.LinkedHashSet<>();
        for (Take t : ch.takes()) {
            Kind kind = null;
            for (Spot s : t.blocks()) {
                ItemEntity e = _foundDrops.get(s);
                if (e == null) {
                    continue;
                }
                drops.add(e);
                Item item = e.getItem().getItem();
                items.add(item);
                kind = TakeAlongKinds.ofDrop(t.raw(), item);
            }
            if (kind != null) {
                target.put(kind, t.target());
                heldAtStart.put(kind, count(mod, kind));
            }
        }
        _foundDrops = null;
        Detour d = new Detour(TakeAlong.nextId(), ch, feet, lo, hi, budget, target, heldAtStart, now, List.copyOf(drops));
        _detour = d;
        _tally.reset();
        _startedAsked = _asked;
        ItemTarget[] targets = items.stream().map(i -> new ItemTarget(i, Integer.MAX_VALUE)).toArray(ItemTarget[]::new);
        setTask(new PickupTheseDrops(d.drops, targets, e -> wantedDrop(mod, d, e, cfg)));
        Debug.logInternal("take along: started on drops of " + ch.items() + ", " + ch.blocks() + " drops worth " + round(ch.value())
                + " s against a cost of " + round(ch.cost()) + " s, nearest " + round(ch.near()) + " away, targets " + targetsText(d));
        TakeAlong.running(status(mod, d, now, "running", null));
    }

    // a hunt for food: the animals the choice counted, killed one by one (the nearest one still wanted first), their meat
    // picked up; the target is in food points
    private void beginHunt(AltoClef mod, Choice ch, BlockPos feet, BlockPos lo, BlockPos hi, double budget, long now, TakeAlongConfig cfg) {
        List<Entity> animals = new ArrayList<>();
        for (Take t : ch.takes()) {
            for (Spot s : t.blocks()) {
                Entity e = _foundPrey.get(s);
                if (e != null) {
                    animals.add(e);
                }
            }
        }
        _foundPrey = null;
        Detour d = new Detour(TakeAlong.nextId(), ch, feet, lo, hi, budget, Map.of(), Map.of(), now, null);
        d.animals = List.copyOf(animals);
        d.foodAtStart = CollectFoodTask.foodPotential(mod);
        d.foodTarget = ch.takes().get(0).target();
        _detour = d;
        _tally.reset();
        _startedAsked = _asked;
        setTask(new HuntThese(d.animals, e -> wantedAnimal(mod, d, e)));
        Debug.logInternal("take along: started on food from " + animalsText(ch) + ", " + animals.size() + " animals worth "
                + round(ch.value()) + " s against a cost of " + round(ch.cost()) + " s, nearest " + round(ch.near()) + " away, food "
                + Math.round(d.foodAtStart) + "->" + d.foodTarget + " points");
        TakeAlong.running(status(mod, d, now, "running", null));
    }

    // an animal of the hunt still worth going for: there and grown, not given up on, the bag short of the food target,
    // within the keep reach of where the hunt began
    private boolean wantedAnimal(AltoClef mod, Detour d, Entity e) {
        if (e == null || !preyOk(mod, e) || CollectFoodTask.foodPotential(mod) >= d.foodTarget) {
            return false;
        }
        BlockPos at = e.blockPosition();
        return WalkCost.estimate(at.getX() - d.anchor.getX(), at.getY() - d.anchor.getY(), at.getZ() - d.anchor.getZ())
                <= DetourRules.keepBudget(d.budget);
    }

    // the animal kinds of a hunt, for the log ("cow+sheep")
    private static String animalsText(Choice ch) {
        StringBuilder out = new StringBuilder();
        for (Part part : ch.cluster().parts()) {
            out.append(out.isEmpty() ? "" : "+").append(part.raw());
        }
        return out.toString();
    }

    // a drop of the detour still worth going for: there, not given up on, its item still short, within the keep reach of
    // where the detour began
    private boolean wantedDrop(AltoClef mod, Detour d, ItemEntity e, TakeAlongConfig cfg) {
        if (e == null || !e.isAlive() || e.getItem().isEmpty() || !dropOk(mod, e)) {
            return false;
        }
        Kind kind = null;
        for (Kind k : d.target.keySet()) {
            for (Item item : k.drops()) {
                if (e.getItem().is(item)) {
                    kind = k;
                }
            }
        }
        if (kind == null || count(mod, kind) >= d.target.get(kind)) {
            return false;
        }
        BlockPos at = e.blockPosition();
        return WalkCost.estimate(at.getX() - d.anchor.getX(), at.getY() - d.anchor.getY(), at.getZ() - d.anchor.getZ())
                <= DetourRules.keepBudget(d.budget);
    }

    // the detour is over, whoever says so. the rules rest from here (their cooldown), the task goes, the status stays
    private void end(AltoClef mod, long now, String state, String why) {
        if (_rules != null) {
            _rules.preempted(now);
        }
        Detour d = _detour;
        if (d == null) {
            return;
        }
        _detour = null;
        if (now >= d.startTick) {
            _spent.addLast(new long[]{d.startTick, now});
        }
        TakeAlong.Status s = status(mod, d, now, state, why);
        TakeAlong.ended(s);
        Debug.logInternal("take along: " + state + " (" + why + "), " + s.items() + " got " + s.got() + " in " + round(s.seconds()) + " s");
        if (_mainTask != null) {
            _mainTask.stop(mod);
            _mainTask = null;
        }
        _tally.reset();
    }

    private TakeAlong.Status status(AltoClef mod, Detour d, long now, String state, String why) {
        Map<String, Integer> got = new LinkedHashMap<>();
        d.heldAtStart.forEach((kind, held) -> got.put(kind.raw(), Math.max(0, count(mod, kind) - held)));
        if (d.animals != null) {
            got.put(TakeAlongRules.FOOD, (int) Math.max(0, Math.round(CollectFoodTask.foodPotential(mod) - d.foodAtStart)));
        }
        return new TakeAlong.Status(d.id, d.choice.items(), d.choice.blocks(), round(d.choice.value()), round(d.choice.cost()),
                Math.max(0, now - d.startTick) / 20.0, got, state, why, d.drops != null);
    }

    // any block of a raw item still short, in the cluster's box, in sight, within the keep reach of where we began
    private boolean keep(AltoClef mod, TakeAlongConfig cfg) {
        Detour d = _detour;
        if (d == null) {
            return false;
        }
        if (d.drops != null) {
            for (ItemEntity e : d.drops) {
                if (wantedDrop(mod, d, e, cfg)) {
                    return true;
                }
            }
            return false;
        }
        if (d.animals != null) {
            for (Entity e : d.animals) {
                if (wantedAnimal(mod, d, e)) {
                    return true;
                }
            }
            return false;
        }
        Set<Kind> short_ = new HashSet<>();
        d.target.forEach((kind, target) -> {
            if (count(mod, kind) < target) {
                short_.add(kind);
            }
        });
        if (short_.isEmpty()) {
            return false;
        }
        double keep = DetourRules.keepBudget(d.budget);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        ClientLevel level = mod.getWorld();
        for (int x = d.lo.getX(); x <= d.hi.getX(); x++) {
            for (int y = d.lo.getY(); y <= d.hi.getY(); y++) {
                for (int z = d.lo.getZ(); z <= d.hi.getZ(); z++) {
                    Kind kind = TakeAlongKinds.ofBlock(level.getBlockState(at.set(x, y, z)).getBlock());
                    if (kind == null || !short_.contains(kind)) {
                        continue;
                    }
                    if (WalkCost.estimate(x - d.anchor.getX(), y - d.anchor.getY(), z - d.anchor.getZ()) > keep) {
                        continue;
                    }
                    if (pickable(mod, at.immutable(), kind, cfg)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // a drop we came for on the floor close by that the bag has room for (the same test the mining task leaves a drop by)
    private boolean dropNear(AltoClef mod, LocalPlayer p) {
        Detour d = _detour;
        // a detour for drops: keep says whether one is still to be had
        if (d == null || d.drops != null) {
            return false;
        }
        // a hunt: the meat of a kill on the floor close by, while the bag is short of the target
        if (d.animals != null) {
            return CollectFoodTask.foodPotential(mod) < d.foodTarget && meatNear(mod, p);
        }
        List<Item> items = new ArrayList<>();
        d.target.forEach((kind, target) -> {
            if (count(mod, kind) < target) {
                items.addAll(List.of(kind.drops()));
            }
        });
        if (items.isEmpty()) {
            return false;
        }
        Vec3 me = p.position();
        return mod.getEntityTracker().getClosestItemDrop(me, e -> e.isAlive() && e.position().distanceTo(me) <= DetourRules.DROP_RADIUS
                && !mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(e.getItem(), false).isEmpty(), items.toArray(Item[]::new)).isPresent();
    }

    private boolean strayed(LocalPlayer p) {
        Detour d = _detour;
        if (d == null) {
            return false;
        }
        BlockPos me = p.blockPosition();
        return DetourRules.strayed(me.getX() - d.anchor.getX(), me.getY() - d.anchor.getY(), me.getZ() - d.anchor.getZ(), d.budget);
    }

    // what is left of the cluster goes on the ban list, not just the block the miner was stuck on: which one that was is the
    // mining task's secret, and its neighbours are stuck the same way more often than not
    private int ban(AltoClef mod, TakeAlongConfig cfg) {
        Detour d = _detour;
        if (d == null) {
            return 0;
        }
        if (d.animals != null) {
            int n = 0;
            for (Entity e : d.animals) {
                if (e.isAlive() && BanPolicy.takeAlongEntity(mod.getBans(), e.getId(), EntityTracker.describe(e), cfg.banTicks)) {
                    n++;
                }
            }
            return n;
        }
        if (d.drops != null) {
            int n = 0;
            for (ItemEntity e : d.drops) {
                if (e.isAlive() && BanPolicy.takeAlongEntity(mod.getBans(), e.getId(), EntityTracker.describe(e), cfg.banTicks)) {
                    n++;
                }
            }
            return n;
        }
        ClientLevel level = mod.getWorld();
        Dimension dim = WorldHelper.getCurrentDimension();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int added = 0;
        for (int x = d.lo.getX(); x <= d.hi.getX(); x++) {
            for (int y = d.lo.getY(); y <= d.hi.getY(); y++) {
                for (int z = d.lo.getZ(); z <= d.hi.getZ(); z++) {
                    Kind kind = TakeAlongKinds.ofBlock(level.getBlockState(at.set(x, y, z)).getBlock());
                    if (kind != null && d.target.containsKey(kind) && BanPolicy.takeAlong(mod.getBans(), dim, x, y, z, cfg.banTicks)) {
                        added++;
                    }
                }
            }
        }
        return added;
    }

    // the blocks broken since the detour began, for the cap of a chance drop (DetourRules.Tally)
    private void countBreaks(AltoClef mod) {
        Detour d = _detour;
        ClientLevel level = mod.getWorld();
        BlockPos at = mod.getControllerExtras().getBreakingBlockPos();
        boolean breaking = at != null && mod.getControllerExtras().isBreakingBlock();
        _tally.tick(breaking, breaking ? at.asLong() : 0L, pos -> {
            Kind kind = TakeAlongKinds.ofBlock(level.getBlockState(BlockPos.of(pos)).getBlock());
            return kind != null && d.target.containsKey(kind);
        });
    }

    // ---- the bag

    private static int count(AltoClef mod, Kind kind) {
        return mod.getItemStorage().getItemCount(kind.drops());
    }

    // the best speed something in the bag breaks the kind's block with while still getting the drop: 1 by hand where a hand
    // gets it, -1 when nothing in the bag does (an ore without the pick for it)
    private static double speed(LocalPlayer p, Kind kind) {
        if (kind.blocks().length == 0) {
            // a drop's own kind (an iron ingot): nothing to break
            return 1;
        }
        BlockState state = kind.blocks()[0].defaultBlockState();
        boolean needsTool = state.requiresCorrectToolForDrops();
        double best = needsTool ? -1 : 1;
        for (ItemStack stack : p.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty() || needsTool && !stack.isCorrectToolForDrops(state)) {
                continue;
            }
            best = Math.max(best, stack.getDestroySpeed(state));
        }
        return best;
    }

    // a stack of the kind's drop in the bag with room on it
    private static boolean stacks(LocalPlayer p, Kind kind) {
        for (ItemStack stack : p.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty() || stack.getCount() >= stack.getMaxStackSize()) {
                continue;
            }
            for (Item drop : kind.drops()) {
                if (stack.is(drop)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int freeSlots(LocalPlayer p) {
        int free = 0;
        for (ItemStack stack : p.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    // ---- small things

    private static Kind kindAt(AltoClef mod, BlockPos pos) {
        return TakeAlongKinds.ofBlock(mod.getWorld().getBlockState(pos).getBlock());
    }

    private static boolean inBox(Detour d, BlockPos pos) {
        return pos.getX() >= d.lo.getX() && pos.getX() <= d.hi.getX() && pos.getY() >= d.lo.getY() && pos.getY() <= d.hi.getY()
                && pos.getZ() >= d.lo.getZ() && pos.getZ() <= d.hi.getZ();
    }

    private static String targetsText(Detour d) {
        StringBuilder out = new StringBuilder();
        d.target.forEach((kind, target) -> out.append(out.isEmpty() ? "" : ", ").append(kind.raw()).append(' ')
                .append(d.heldAtStart.get(kind)).append("->").append(target));
        return out.toString();
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    // the stock pickup goes for the nearest item of a kind anywhere. this one only sees the drops the detour set out for and
    // still wants, and keeps the rest (a full bag, the water watchdog, the stuck shimmy). a drop it got nowhere with is banned
    // in the take-along's scope only, the user task's own pickup may still try it
    private static final class PickupTheseDrops extends PickupDroppedItemTask {
        private final List<ItemEntity> _drops;
        private final Predicate<ItemEntity> _wanted;

        PickupTheseDrops(List<ItemEntity> drops, ItemTarget[] targets, Predicate<ItemEntity> wanted) {
            super(targets, true);
            _drops = drops;
            _wanted = wanted;
        }

        @Override
        protected Optional<ItemEntity> getClosestTo(AltoClef mod, Vec3 pos) {
            ItemEntity best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (ItemEntity e : _drops) {
                if (!_wanted.test(e) || !isValid(mod, e)) {
                    continue;
                }
                double sq = e.position().distanceToSqr(pos);
                if (sq < bestSq) {
                    best = e;
                    bestSq = sq;
                }
            }
            return Optional.ofNullable(best);
        }

        @Override
        protected void gaveUpOnDrop(AltoClef mod, ItemEntity drop) {
            BanPolicy.takeAlongEntity(mod.getBans(), drop.getId(), EntityTracker.describe(drop), BanPolicy.TASK_GAVE_UP);
        }

        @Override
        protected void noPathToDrop(AltoClef mod, ItemEntity drop) {
            BanPolicy.takeAlongEntity(mod.getBans(), drop.getId(), "no path to " + EntityTracker.describe(drop), BanPolicy.TASK_GAVE_UP);
        }

        // a detour on a 30 s clock has no time for a stone pickaxe trip
        @Override
        protected boolean mayGetPickaxeFirst() {
            return false;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }
    }

    // the meat the animals drop, raw or cooked (an animal that burned)
    private static final Item[] MEAT = {Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.COOKED_BEEF, Items.COOKED_PORKCHOP,
            Items.COOKED_MUTTON, Items.COOKED_CHICKEN};

    private static Item[] meatOf(Entity e) {
        if (e instanceof Cow) {
            return new Item[]{Items.BEEF, Items.COOKED_BEEF};
        }
        if (e instanceof Pig) {
            return new Item[]{Items.PORKCHOP, Items.COOKED_PORKCHOP};
        }
        if (e instanceof Sheep) {
            return new Item[]{Items.MUTTON, Items.COOKED_MUTTON};
        }
        return new Item[]{Items.CHICKEN, Items.COOKED_CHICKEN};
    }

    // meat on the floor within the drop radius that the bag has room for
    private static boolean meatNear(AltoClef mod, LocalPlayer p) {
        Vec3 me = p.position();
        return mod.getEntityTracker().getClosestItemDrop(me, e -> e.isAlive() && e.position().distanceTo(me) <= DetourRules.DROP_RADIUS
                && !mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(e.getItem(), false).isEmpty(), MEAT).isPresent();
    }

    // a hunt of just these animals: the meat of a kill close by first, then the nearest animal still wanted, killed by the
    // food task's own kill-and-loot for that one animal. it never finishes by itself, the detour's rules end it
    private static final class HuntThese extends Task {
        private final List<Entity> _animals;
        private final Predicate<Entity> _wanted;
        private final PickupDroppedItemTask _pickup = new PickupDroppedItemTask(new ItemTarget(MEAT, Integer.MAX_VALUE), true);
        private Entity _on;
        private Task _kill;

        HuntThese(List<Entity> animals, Predicate<Entity> wanted) {
            _animals = animals;
            _wanted = wanted;
        }

        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            LocalPlayer p = mod.getPlayer();
            if (p == null) {
                return null;
            }
            if (meatNear(mod, p)) {
                setDebugState("Picking up the meat");
                return _pickup;
            }
            Entity next = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (Entity e : _animals) {
                if (!_wanted.test(e)) {
                    continue;
                }
                double sq = e.distanceToSqr(p);
                if (sq < bestSq) {
                    next = e;
                    bestSq = sq;
                }
            }
            if (next == null) {
                setDebugState("Nothing left to hunt");
                return null;
            }
            if (next != _on || _kill == null) {
                _on = next;
                _kill = new KillAndLootTask(next, new ItemTarget(meatOf(next), Integer.MAX_VALUE));
            }
            setDebugState("Hunting " + EntityTracker.describe(next));
            return _kill;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return false;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }

        @Override
        protected String toDebugString() {
            return "Taking food along: " + _animals.size() + " animals";
        }
    }
}
