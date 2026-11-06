package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Offset;
import adris.altoclef.tasks.speedrun.gamer.DetourSpec.Resource;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.Bans;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

// the world half of DetourRules: finds a block worth a detour near the player (coal ore, gravel, diamond ore), runs a MineAndCollectTask of
// its own at it, and hands the task back to the phase until the rules say stop. it is the last side job there is, everything
// ranked above it wins the tick. the mining task asks the tracker for the nearest block wherever it is, so the rules end the job
// when the blocks near the spot it began at are gone (a count target would send it off to a vein 80 blocks away, and "hold N" is
// a bad question anyway: the cluster grows as we dig into it). when the task gives up on a block it sets off for a far vein, and
// the leash from that spot cuts the walk short and the ban keeps us off that cluster. the bans go in the shared book (Bans) scoped
// to the detour: our mining task skips them through onlyWhere, and the task that really needs the thing still gets offered them.
// the tracker is the mining task's own business, it tracks on start and lets go on stop, so nothing here needs releasing when
// the phase leaves, only forgetting
public final class ResourceDetour {
    // a few thousand block reads per look, twice a second is a lot of watching already
    private static final int LOOK_EVERY_TICKS = 10;

    private final DetourSpec spec;
    // an array of our own per detour: the iron task's list must not learn about coal, two mine tasks with the same blocks are the
    // same task to the task system and coal would count towards the iron
    private final Block[] blocks;
    // what we came for: coal from coal ore, flint from gravel, diamonds from diamond ore (the gravel itself is junk, see altoThrowawayItems)
    private final Item item;
    private final MiningRequirement requirement;
    private final DetourRules rules;
    private final DetourRules.Tally tally = new DetourRules.Tally();
    private Task mine;
    // where the detour began. the keep reach, the leash and the ban are all measured from here and not from the player: the player
    // is the thing that wanders off when the mining task gives up on a block
    private BlockPos anchor;
    private int ticks;
    private int heldAtStart;
    private int heldLast;
    private String hud;
    // the block the rules last looked at, for the card's "N blocks". the look happens anyway, this just keeps the answer
    private BlockPos ore;
    // the "we hold enough" line went out and is still true
    private boolean enoughSaid;
    private String ended;

    private ResourceDetour(DetourSpec spec, Block[] blocks, Item item, MiningRequirement requirement) {
        this.spec = spec;
        this.blocks = blocks;
        this.item = item;
        this.requirement = requirement;
        this.rules = new DetourRules(spec.cooldownTicks);
    }

    // the same two blocks TaskCatalogue mines for "coal"
    public static ResourceDetour coal() {
        return new ResourceDetour(DetourSpec.COAL, new Block[]{Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE}, Items.COAL, MiningRequirement.WOOD);
    }

    // plain gravel only. suspicious gravel drops nothing to a hand or a shovel (it wants a brush and gives loot, not flint), and
    // it sits in trail ruins and ocean ruins we are not digging up anyway
    public static ResourceDetour gravel() {
        return new ResourceDetour(DetourSpec.GRAVEL, new Block[]{Blocks.GRAVEL}, Items.FLINT, MiningRequirement.HAND);
    }

    // the two blocks TaskCatalogue mines for "diamond". it needs an iron pick, and the tool gate in inputs() means we only get
    // here holding one, so the mining task never goes and makes a pick of its own
    public static ResourceDetour diamond() {
        return new ResourceDetour(DetourSpec.DIAMOND, new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE}, Items.DIAMOND, MiningRequirement.IRON);
    }

    // the two overworld gold ores (badlands has plain gold_ore up high, everywhere else it is deepslate or stone). raw gold needs an iron pick like the
    // diamonds do, and the tool gate in inputs() means we only get here holding one
    public static ResourceDetour gold() {
        return new ResourceDetour(DetourSpec.GOLD, new Block[]{Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE}, Items.RAW_GOLD, MiningRequirement.IRON);
    }

    public DetourSpec spec() {
        return spec;
    }

    public String hud() {
        return hud;
    }

    // how the last detour ended, in words, for the activity line
    public String ended() {
        return ended;
    }

    // ---- for the card (GamerHud), plain reads

    public boolean active() {
        return rules.running() && mine != null;
    }

    public long startTick() {
        return rules.startTick();
    }

    // blocks broken since the detour began (gravel's cap counts these)
    public int dug() {
        return tally.count();
    }

    // the block we are on our way to, null when the last look found none
    public BlockPos ore() {
        return ore;
    }

    // a detour that is going, over (see DetourRules.preempted)
    public void preempted(long now) {
        if (rules.running()) {
            Debug.logInternal(spec.name + " side job: something else took over, ending the detour");
        }
        rules.preempted(now);
        mine = null;
        anchor = null;
    }

    // the phase is entering or leaving. the bans stay, they are about the world and not the phase (and live in Bans anyway)
    public void reset() {
        rules.reset();
        mine = null;
        anchor = null;
        hud = null;
        ticks = 0;
        tally.reset();
    }

    // the mining task while a detour is on, null when there is none
    public Task tick(AltoClef mod, GamerContext ctx, KitNeed head) {
        // looking for a vein is the part that costs, a detour that is going is asked about every tick
        if (!rules.running() && ++ticks % LOOK_EVERY_TICKS != 0) {
            return null;
        }
        GamerFacts f = ctx.facts();
        OverworldConfig cfg = ctx.cfg().overworld;
        long now = f.gameTime();
        int held = f.count(item);
        BlockPos me = mod.getPlayer().blockPosition();
        double budget = spec.budget(cfg);
        double keep = DetourRules.keepBudget(budget);
        BlockPos from = anchor == null ? me : anchor;
        if (rules.running()) {
            countBreaks(mod);
        }
        DetourRules.Ore ore = new DetourRules.Ore(() -> (this.ore = nearest(mod, me, budget)) != null,
                () -> (this.ore = nearest(mod, from, keep)) != null, () -> dropNear(mod), () -> strayed(me, from, budget));
        DetourRules.Inputs in = inputs(mod, ctx, head, held);
        DetourRules.Limits lim = spec.limits(cfg);
        noteEnough(in, lim);
        DetourRules.Step step = rules.tick(now, in, lim, ore);
        switch (step) {
            case START:
                start(mod, ctx, cfg, me, held);
                return mine;
            case KEEP:
                if (held > heldLast) {
                    // a piece in the bag is the stall timer's kind of progress, a long detour is not
                    ctx.progress(spec.progress);
                }
                heldLast = held;
                return mine;
            case SETTLE:
                // the mining task has its own wait for the drop of a block it just broke, we only keep it alive through it
                return mine;
            case TIMEOUT:
            case STRAYED:
                Debug.logInternal(spec.name + " side job: " + step + " with " + held + " " + spec.itemWord + " held, banning what is left around " + from.toShortString());
                ban(mod, from, keep);
                finish(held, step);
                return null;
            case DONE:
            case CAPPED:
            case BLOCKED:
                finish(held, step);
                return null;
            default:
                return null;
        }
    }

    private void start(AltoClef mod, GamerContext ctx, OverworldConfig cfg, BlockPos me, int held) {
        // the target is the cap in total, so the task never calls itself finished before the rules do
        // and it only ever picks blocks we can see from where we stand, so a vein behind the wall is not a tunnel (DetourSight).
        // our own give-ups too: they are scoped to the detour, the tracker still offers them to everybody else
        mine = new MineAndCollectTask(new ItemTarget(item, spec.mineTarget(cfg)), blocks, requirement)
                .onlyWhere(pos -> !banned(mod, pos.getX(), pos.getY(), pos.getZ()) && pickable(mod, pos));
        anchor = me;
        heldAtStart = held;
        heldLast = held;
        tally.reset();
        hud = spec.hud;
        // a restart on the cluster a bed or a chest just interrupted is the same trip as far as chat goes (DetourRules.announce)
        boolean said = rules.announce(ctx.facts().gameTime(), me.getX(), me.getY(), me.getZ());
        if (said) {
            ctx.log(spec.chat);
        }
        BlockPos first = nearest(mod, me, spec.budget(cfg));
        String tool = spec.resource == Resource.GRAVEL ? ", with " + DetourSpec.gravelTool(hasShovel(ctx.facts())) : "";
        Debug.logInternal(spec.name + " side job: started with " + held + " " + spec.itemWord + " held" + tool
                + (first == null ? "" : ", nearest " + spec.spotWord + " " + first.toShortString()) + (said ? "" : ", chat line skipped, near the last one"));
    }

    private void finish(int held, DetourRules.Step why) {
        Debug.logInternal(spec.name + " side job: " + why + ", " + heldAtStart + " -> " + held + " " + spec.itemWord + " held"
                + (spec.resource == Resource.GRAVEL ? ", " + tally.count() + " gravel dug" : ""));
        ended = spec.ended(why);
        mine = null;
        anchor = null;
        hud = null;
        ore = null;
    }

    // the break we are on, counted once it is gone (DetourRules.Tally). only the gravel cap reads it, coal counts it for nothing
    private void countBreaks(AltoClef mod) {
        ClientLevel level = mod.getWorld();
        BlockPos at = mod.getControllerExtras().getBreakingBlockPos();
        boolean breaking = at != null && mod.getControllerExtras().isBreakingBlock();
        tally.tick(breaking, breaking ? at.asLong() : 0L, pos -> isTarget(level.getBlockState(BlockPos.of(pos))));
    }

    // one line when what we hold covers the plan, not one per look. said again only after it stopped being true
    private void noteEnough(DetourRules.Inputs in, DetourRules.Limits lim) {
        boolean enough = DetourRules.enough(in, lim);
        if (enough && !enoughSaid && !rules.running()) {
            Debug.logInternal(spec.name + " side job: skipping, we hold " + in.held() + " " + spec.itemWord + " and the plan needs "
                    + Math.min(in.need(), lim.heldCap()));
        }
        enoughSaid = enough;
    }

    // DetourSpec.coalNeed over the facts. raw iron in the bag is still owed (ingotsNeeded counts the missing items, not the ore)
    static int coalNeed(GamerContext ctx) {
        GamerFacts f = ctx.facts();
        OverworldConfig cfg = ctx.cfg().overworld;
        return DetourSpec.coalNeed(KitPlanner.ingotsNeeded(f, cfg), f.count(Items.IRON_INGOT), f.pendingOutput(Items.IRON_INGOT),
                CookGate.raw(f), CookGate.woodSmeltsAfterClaims(f, cfg, ctx.wood()), KitPlanner.smeltLoads(f, cfg));
    }

    // DetourSpec.goldNeed over the facts. a gold piece worn or in the bag means the gate has what it needs
    static int goldNeed(GamerContext ctx) {
        GamerFacts f = ctx.facts();
        return DetourSpec.goldNeed(ctx.cfg().overworld.goldSide, PiglinGold.worn(f) || PiglinGold.holdsPiece(f), PiglinGold.available(f),
                f.count(Items.RAW_GOLD));
    }

    static int flintNeed(GamerFacts f) {
        return DetourSpec.flintNeed(f.count(Items.FLINT), f.count(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE) > 0);
    }

    private DetourRules.Inputs inputs(AltoClef mod, GamerContext ctx, KitNeed head, int held) {
        GamerFacts f = ctx.facts();
        long now = f.gameTime();
        boolean overworld = f.dimension() == Dimension.OVERWORLD;
        boolean screen = mod.getPlayer().containerMenu instanceof AbstractFurnaceMenu;
        // the same "due" the furnace plan uses, so a detour never sits on a collect trip it is about to hold up. only in the
        // overworld: from the nether a due furnace is a furnace nobody is walking to, it must not keep gravel off for good. and only
        // from close by: a far one waits for the end of what we are doing (FurnacePlan.COLLECT_CUT_IN), ending the detour for it
        // would just be the coal we did not dig
        boolean due = overworld && FurnacePlan.anyDueClose(f.furnaceJobs(), now, FurnaceWatch.where(mod));
        boolean cook = overworld && f.cookStation() != null;
        boolean load = overworld && WorkbenchRules.loadInFlight(screen, AsyncSmelting.lastWork(), now);
        String need = head == null ? null : head.catalogueName();
        // the food need and the cook both have latches that count the time they lead for, a detour in the middle of one eats it
        boolean food = KitNeed.FOOD.equals(need) || KitNeed.isCookName(need);
        boolean place = spec.resource == Resource.GRAVEL ? DetourSpec.gravelPhase(ctx.state().phase, f.dimension()) : overworld;
        return new DetourRules.Inputs(place, hasTool(f), held, want(ctx), cook, due, load, food, spec.kitIsOnIt(need), tally.count());
    }

    // the spec's tool gate. gravel needs nothing but a hand
    private boolean hasTool(GamerFacts f) {
        return switch (spec.resource) {
            case GRAVEL -> true;
            case COAL -> hasPickaxe(f);
            case DIAMOND, GOLD -> hasIronPickaxe(f);
        };
    }

    // what we stop at, see each resource's need
    private int want(GamerContext ctx) {
        return switch (spec.resource) {
            case GRAVEL -> flintNeed(ctx.facts());
            case COAL -> coalNeed(ctx);
            case DIAMOND -> DetourSpec.diamondNeed(ctx.cfg().overworld.diamondSide, ctx.cfg().overworld.diamondSideCap);
            case GOLD -> goldNeed(ctx);
        };
    }

    // stone or better. a wooden pick could mine coal, but the wooden pick days are for getting cobble and leaving, and its 59
    // uses are spoken for. gold is wood tier with worse durability, so it doesn't count either
    private static boolean hasPickaxe(GamerFacts f) {
        return f.has(Items.STONE_PICKAXE) || f.has(Items.IRON_PICKAXE) || f.has(Items.DIAMOND_PICKAXE) || f.has(Items.NETHERITE_PICKAXE);
    }

    // iron or better, which is what diamond ore wants. no pick of that kind is no side job, it never makes a need for one
    static boolean hasIronPickaxe(GamerFacts f) {
        return f.count(Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE) > 0;
    }

    private static boolean hasShovel(GamerFacts f) {
        return f.count(Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.GOLDEN_SHOVEL, Items.DIAMOND_SHOVEL,
                Items.NETHERITE_SHOVEL) > 0;
    }

    private boolean isTarget(BlockState state) {
        for (Block b : blocks) {
            if (state.is(b)) {
                return true;
            }
        }
        return false;
    }

    private boolean banned(AltoClef mod, int x, int y, int z) {
        Bans bans = mod.getBans();
        return switch (spec.resource) {
            case GRAVEL -> BanPolicy.gravelBanned(bans, WorldHelper.getCurrentDimension(), x, y, z);
            case COAL -> BanPolicy.coalBanned(bans, x, y, z);
            case DIAMOND -> BanPolicy.diamondBanned(bans, x, y, z);
            case GOLD -> BanPolicy.goldBanned(bans, x, y, z);
        };
    }

    private boolean banOne(AltoClef mod, int x, int y, int z) {
        Bans bans = mod.getBans();
        return switch (spec.resource) {
            case GRAVEL -> BanPolicy.gravel(bans, WorldHelper.getCurrentDimension(), x, y, z);
            case COAL -> BanPolicy.coal(bans, x, y, z);
            case DIAMOND -> BanPolicy.diamond(bans, x, y, z);
            case GOLD -> BanPolicy.gold(bans, x, y, z);
        };
    }

    // the nearest block within the budget of `center` that we could mine, have not given up on, and can see right now (DetourSight:
    // an air face and a clear line from the eyes, within 8). start and keep both ask it, so the detour ends when the only ones left
    // in the cluster are behind stone. "seen once" (SeenFilter) was not enough: a glimpse through a crack 30 blocks off counted.
    // a banned block (ours, scoped to the detour, or anybody's) is skipped here and by the detour's mining task alike (onlyWhere)
    private BlockPos nearest(AltoClef mod, BlockPos center, double budget) {
        ClientLevel level = mod.getWorld();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (Offset o : DetourRules.offsets(budget)) {
            at.set(center.getX() + o.dx(), center.getY() + o.dy(), center.getZ() + o.dz());
            if (!isTarget(level.getBlockState(at)) || banned(mod, at.getX(), at.getY(), at.getZ())) {
                continue;
            }
            BlockPos found = at.immutable();
            if (WorldHelper.canBreak(mod, found) && pickable(mod, found)) {
                return found;
            }
        }
        return null;
    }

    // in sight, and for gravel not a column hanging over our head. DestroyBlockTask already digs loose columns from the side and
    // never swings from under one, this keeps the detour from picking that block in the first place. the one that falls into
    // the hole we dug from the side is gravel in plain view, it is simply the next pick. for diamonds no lava touching the ore
    private boolean pickable(AltoClef mod, BlockPos pos) {
        if (!visible(mod, pos)) {
            return false;
        }
        if (spec.resource == Resource.DIAMOND || spec.resource == Resource.GOLD) {
            return !lavaBeside(mod.getWorld(), pos);
        }
        if (spec.resource != Resource.GRAVEL) {
            return true;
        }
        return !underTallColumn(mod, pos);
    }

    // a loose column of more than DetourSpec.TALL_COLUMN straight over the player's head (DetourSpec.underTallColumn). public
    // for the take-along, which digs gravel by the same rule
    public static boolean underTallColumn(AltoClef mod, BlockPos pos) {
        BlockPos feet = mod.getPlayer().blockPosition();
        int stack = 1 + WorldHelper.fallingAbove(mod.getWorld(), pos);
        return DetourSpec.underTallColumn(feet.getX(), feet.getY(), feet.getZ(), pos.getX(), pos.getY(), pos.getZ(), stack);
    }

    // (KitRunner's diamond dig skips the same ore, and the take-along every ore)
    public static boolean lavaBeside(ClientLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        return DetourSpec.lavaTouches((x, y, z) -> level.getFluidState(at.set(x, y, z)).is(FluidTags.LAVA), pos.getX(), pos.getY(), pos.getZ());
    }

    // the mining task asks this for every candidate, twice a pick, every tick. same block same tick is the same answer
    private final Map<Long, Boolean> sight = new HashMap<>();
    private long sightTick = Long.MIN_VALUE;

    private boolean visible(AltoClef mod, BlockPos pos) {
        // the gui clock and not game time, which stands still under /tick freeze while we keep walking
        long now = Minecraft.getInstance().gui.hud.getGuiTicks();
        if (now != sightTick) {
            sight.clear();
            sightTick = now;
        }
        return sight.computeIfAbsent(pos.asLong(), k -> inSight(mod, pos, DetourSight.RANGE));
    }

    // DetourSight from the player's eyes right now, with the caller's range. public for the take-along (a range of its own)
    public static boolean inSight(AltoClef mod, BlockPos pos, double range) {
        ClientLevel level = mod.getWorld();
        Vec3 eye = mod.getPlayer().getEyePosition();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        DetourSight.Cells cells = new DetourSight.Cells() {
            @Override
            public boolean air(int x, int y, int z) {
                return level.getBlockState(at.set(x, y, z)).isAir();
            }

            @Override
            public boolean blocksView(int x, int y, int z) {
                return level.getBlockState(at.set(x, y, z)).canOcclude();
            }
        };
        return DetourSight.visible(cells, eye.x, eye.y, eye.z, pos.getX(), pos.getY(), pos.getZ(), range);
    }

    // further from where the detour began than the leash, as the crow flies
    private static boolean strayed(BlockPos me, BlockPos from, double budget) {
        return DetourRules.strayed(me.getX() - from.getX(), me.getY() - from.getY(), me.getZ() - from.getZ(), budget);
    }

    // everything of ours around `center` goes on the ban list, not just the block we were stuck on: which one that was is the
    // mining task's secret, and its neighbours are stuck in the same way more often than not
    private void ban(AltoClef mod, BlockPos center, double budget) {
        ClientLevel level = mod.getWorld();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int added = 0;
        for (Offset o : DetourRules.offsets(budget)) {
            at.set(center.getX() + o.dx(), center.getY() + o.dy(), center.getZ() + o.dz());
            if (isTarget(level.getBlockState(at)) && banOne(mod, at.getX(), at.getY(), at.getZ())) {
                added++;
            }
        }
        Debug.logInternal(spec.name + " side job: banned " + added + " " + spec.spotWord + " blocks");
    }

    // an item we came for on the floor close by that the bag has room for. one it has no room for would hold the detour until its
    // clock ran out, same test the mining task uses to leave a drop alone
    private boolean dropNear(AltoClef mod) {
        Vec3 me = mod.getPlayer().position();
        return mod.getEntityTracker().getClosestItemDrop(me, d -> d.isAlive() && d.position().distanceTo(me) <= DetourRules.DROP_RADIUS
                && !mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(d.getItem(), false).isEmpty(), item).isPresent();
    }
}
