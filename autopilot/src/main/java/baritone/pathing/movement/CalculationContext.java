/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.cache.WorldData;
import baritone.pathing.path.PathExecutor;
import baritone.pathing.precompute.PrecomputedData;
import baritone.utils.BridgeBudget;
import baritone.utils.ExperimentalMovement;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.SearchCache;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * @author Brady
 * @since 8/7/2018
 */
public class CalculationContext {

    private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);

    public final boolean safeForThreadedUse;
    public final IBaritone baritone;
    public final Level world;
    public final WorldData worldData;
    public final BlockStateInterface bsi;
    public final ToolSet toolSet;
    public final boolean hasWaterBucket;
    // a ladder or vine on the hotbar and allowLadderClutch on, see MovementDescend.dynamicFallCost
    // (not final so a test can hand one over without a hotbar)
    public boolean hasClutchItem;
    // hp plus absorption right now, for falls that hurt. same snapshot rule as hasWaterBucket
    public final double health;
    public final boolean fastMode;
    // fastMode's falls that hurt, minus the nether where the landing is lava and there is no water to catch us
    // (not final so a test can put a context in the nether without a world to ask)
    public boolean hurtingFallsOk;
    public final double fastModeMinHealth;
    public final double fallDamageCost;
    // 1 unless fastMode wants the jumpy movements to look a bit cheaper, see biasJump
    public final double jumpBias;
    public final boolean preferFasterPathing;
    // the clutch item is a ladder (vines win if both are there) and pickupLadders is on, so a clutch also costs the pickup
    public boolean clutchPicksUp;
    public final float blockReach;
    public final boolean hasThrowaway;
    public final boolean canSprint;
    protected double placeBlockCost; // protected because you should call the function instead. only the real bag constructor rescales it
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    public final boolean allowParkour;
    public final boolean allowParkourPlace;
    // back place bridging at walk speed instead of the sneak crawl, see GodBridge
    public final boolean allowGodBridge;
    public final boolean allowJumpAtBuildLimit;
    public final boolean allowParkourAscend;
    public final boolean allowNeos;
    public final boolean allowClimbJumps;
    public final boolean allowMomentumJumps;
    public final boolean assumeWalkOnWater;
    public boolean allowFallIntoLava;
    // on fire right now, so the lava is already our problem and whatever block gets us out wins, see costOfPlacingAt
    public boolean burning;
    // the rule costs about 5% of the whole search, asked on every node that could place. lava is what the nether is made of and no water
    // gets you out of it there, the overworld has a bucket and doesn't pay for it. true by default, a hand built context has no world
    public boolean lavaWakes = true;
    // one per context so the cost code isn't building a lambda per bridge candidate
    public final LavaWake.Cells lavaCells = (x, y, z) -> LavaWake.cell(get(x, y, z));
    public final int frostWalker;
    // what a block of walking costs over soul sand, which soul speed boots change from slow to fast
    public final double soulSandWalkCost;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    public int minFallHeight;
    public int maxFallHeightNoWater;
    // a context that raises maxFallHeightNoWater because it wants the fall (the elytra walk off) doesn't want it priced as
    // damage or swapped for a ladder either, see MovementDescend.dynamicFallCost
    public boolean plainFallsAreFree;
    public final int maxFallHeightBucket;
    public final double waterWalkSpeed;
    // lava is not water. 0.02 accel * 0.98 input, then the 0.5 drag: 2 * 0.0196 = 0.0392 blocks a tick, so 25.5 ticks a block.
    // sprint does nothing in lava, so there is no faster gear to assume either
    public static final double LAVA_SWIM_ONE_BLOCK_COST = 1 / (2 * 0.02 * 0.98);
    // armor 16 drains about 3.4 hp a second, bare is more like 8. in between, and priced at fallDamageCost like fall hp is
    public static final double LAVA_HP_PER_TICK = 0.2;
    // a block of lava swimming, ticks plus the hearts it burns. what makes the escape take the shortest dip and not
    // the cheapest looking lake crossing
    public final double lavaSwimCost;
    // the executor will sprint swim through water, same conditions as Movement.shouldSwim minus the live ones
    public final boolean allowSwimming;
    // moves a swimmer just got pinned on, they cost extra for a bit. see SwimStalls
    public final SwimStalls.Snapshot swimStalls;
    // moves the executor just gave up on, see FailedMoves. only A* adds it, never calculateCost, or the executor's per tick
    // cost recheck would cancel a perfectly good path for walking near an old failure
    public final FailedMoves.Snapshot failedMoves;
    // the nether and the End: price the nodes next to a drop, see CliffAvoidance. like failedMoves it is A* only, never calculateCost
    // (not final so a test can put a context in the nether without a world to ask)
    public boolean cliffEdges;
    // outside the nether: price the nodes next to lava below, see CliffAvoidance.lavaNear. A* only, as cliffEdges
    public boolean lavaEdges;
    // rowing, when there's a boat to row. equal to waterWalkSpeed when there isn't so the min is a no-op
    public final double boatWaterSpeed;
    // BoatTrip won't get the boat out for a shorter row of water than this, so the planner doesn't price one either
    private final int boatMinWater;
    // the two ends of a row: getting the boat out and climbing in, and breaking it and picking it up. see boatHandoff
    private final double boatBoardCost;
    private final double boatExitCost;
    // boatCell is a few dozen block reads and every move out of a node asks about the same src and a dest that three other
    // nodes asked about too. direct mapped, and the searching thread's alone like the mining tables (see claimSearchCaches)
    private static final int BOAT_CACHE_SIZE = 128;
    private long[] boatKeys;
    private boolean[] boatAnswers;
    // the walk-through rules run a predicate loop per walkability check and every neighbour asks the same block. same shape as
    // the boat cache: the claiming thread's alone, null when no rule is registered. it also pins each block's answer for the
    // rest of the search, so a rule that reads a task field the main thread rewrites can't flip mid way (that is a behaviour
    // change on purpose, a slot that got evicted can still be asked again and see the newer answer)
    private static final int WALK_THROUGH_CACHE_BITS = 12;
    private long[] walkThroughKeys;
    private boolean[] walkThroughAnswers;
    public final double breakBlockAdditionalCost;
    // ticks the bot sits idle after a block that took more than one tick to break, see BlockBreakHelper
    public final double breakIdleTicks;
    // snapshotted like the rest, so one search can't price some breaks with the old value and some with the new
    public final double waterBreakCostMultiplier;
    public double backtrackCostFavoringCoefficient;
    public double jumpPenalty;
    public final double walkOnWaterOnePenalty;
    public final boolean allowWalkOnMagmaBlocks;
    public final BetterWorldBorder worldBorder;
    // world.dimensionType() goes through a Holder and we were calling it twice per block. per block!
    public final int minY;
    public final int maxY;

    public final PrecomputedData precomputedData;

    // altoclef's position rules, frozen here so a search sees one answer all the way through and never takes a lock.
    // the arrays are null when altoclef isn't holding any of that kind and altoActive is false when none of them are, so
    // idle costs a field check per question. (it used to be a mutex, a new BlockPos and a stream. per call. in A*)
    public final boolean altoActive;
    private final LongOpenHashSet altoBreakPositions;
    private final Predicate<BlockPos>[] altoBreakAvoiders;
    private final Predicate<BlockPos>[] altoPlaceAvoiders;
    private final Predicate<BlockPos>[] altoWalkOn;
    private final Predicate<BlockPos>[] altoAvoidWalkThrough;
    // one pos for the searching thread to reuse. predicates must not keep it, it's about to become somewhere else
    private final BlockPos.MutableBlockPos altoPos;

    // memo for getMiningDurationTicks by position
    // the block under you gets its break cost worked out by four descends, a downward, and then all your neighbours' descends
    // and every one of those reads five more blocks for avoidBreaking. the answer doesn't change mid search so just remember it
    // two of everything because includeFalling is part of the question
    // only exists while a search has claimed it, see claimSearchCaches
    public static final int MINING_CACHE_BITS = 16;
    final int miningCacheBits;
    private SearchCache.MiningBuffers miningBuffers;
    long[] miningKeys;
    long[] miningKeysFalling;
    double[] miningVals;
    double[] miningValsFalling;
    Thread miningOwner;

    public CalculationContext(IBaritone baritone) {
        this(baritone, false);
    }

    public CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread) {
        this(baritone, forUseOnAnotherThread, true);
    }

    // scaleByBag off is for the trip ask's real side, which has to price blocks like the pretend side does
    public CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread, boolean scaleByBag) {
        this(
                baritone,
                forUseOnAnotherThread,
                new ToolSet(baritone.getPlayerContext().player()),
                Baritone.settings().allowPlace.value && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway()
        );
        if (scaleByBag) {
            // 0 stays 0 (the lava escape zeroes the penalty) and infinity stays infinity
            this.placeBlockCost *= BridgeBudget.placeCostScale(((Baritone) baritone).getInventoryBehavior().spareThrowawayCount(), ExperimentalMovement.inNether(world));
        }
    }

    // the real player with a different bag: the what-if search (trip asks) hands in pretend blocks and a pretend pickaxe,
    // everything else is read off the player exactly like the real context so the two paths are comparable
    protected CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread, ToolSet toolSet, boolean hasThrowaway) {
        this(
                baritone,
                forUseOnAnotherThread,
                baritone.getPlayerContext().world(),
                (WorldData) baritone.getPlayerContext().worldData(),
                new BlockStateInterface(baritone.getPlayerContext(), forUseOnAnotherThread),
                toolSet,
                hasThrowaway,
                Baritone.settings().allowWaterBucketFall.value && Inventory.isHotbarSlot(baritone.getPlayerContext().player().getInventory().findSlotMatchingItem(STACK_BUCKET_WATER)) && baritone.getPlayerContext().world().dimension() != Level.NETHER,
                Baritone.settings().allowSprint.value && baritone.getPlayerContext().player().getFoodData().getFoodLevel() > 6,
                frostWalkerLevel(baritone.getPlayerContext().player()),
                soulSpeedLevel(baritone.getPlayerContext().player()),
                waterSpeedMultiplier(baritone.getPlayerContext().player()),
                baritone.getPlayerContext().player().getHealth() + baritone.getPlayerContext().player().getAbsorptionAmount(),
                hasBoat(baritone, baritone.getPlayerContext().player())
        );
        // not with the other arguments because a context built by hand has no fire to be on
        this.burning = baritone.getPlayerContext().player().isOnFire();
        this.lavaWakes = baritone.getPlayerContext().world().dimension() == Level.NETHER;
    }

    // no soul speed boots, which is every test and bench that was written before they existed
    protected CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread, Level world, WorldData worldData, BlockStateInterface bsi, ToolSet toolSet, boolean hasThrowaway, boolean hasWaterBucket, boolean canSprint, int frostWalker, float waterSpeedMultiplier, double health, boolean hasBoat) {
        this(baritone, forUseOnAnotherThread, world, worldData, bsi, toolSet, hasThrowaway, hasWaterBucket, canSprint, frostWalker, 0, waterSpeedMultiplier, health, hasBoat);
    }

    // everything that needs a player or a world comes in as a parameter so you can build one of these with no game running
    // all the settings get snapshotted in here so nobody can accidentally read them differently
    @SuppressWarnings("unchecked")
    protected CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread, Level world, WorldData worldData, BlockStateInterface bsi, ToolSet toolSet, boolean hasThrowaway, boolean hasWaterBucket, boolean canSprint, int frostWalker, int soulSpeed, float waterSpeedMultiplier, double health, boolean hasBoat) {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        AltoClefSettings.Snapshot altoRules = alto.snapshot();
        boolean paused = alto.isInteractionPaused();
        this.altoBreakPositions = altoRules.breakPositions;
        this.altoBreakAvoiders = altoRules.breakAvoiders;
        this.altoPlaceAvoiders = altoRules.placeAvoiders;
        this.altoWalkOn = altoRules.forceWalkOn;
        this.altoAvoidWalkThrough = altoRules.avoidWalkThrough;
        this.altoActive = altoRules.hasPathingRules();
        this.altoPos = altoActive ? new BlockPos.MutableBlockPos() : null;
        this.precomputedData = PrecomputedData.forCurrentSettings();
        this.miningCacheBits = SearchCache.bitsForSize(Baritone.settings().pathingCacheSize.value);
        this.safeForThreadedUse = forUseOnAnotherThread;
        this.baritone = baritone;
        this.world = world;
        this.worldData = worldData;
        this.bsi = bsi;
        this.toolSet = toolSet;
        this.hasThrowaway = hasThrowaway && !paused; // paused means no placing, whatever's in the hotbar
        this.hasWaterBucket = hasWaterBucket;
        // same idea as the bucket, except it isn't banned in the nether. the setting goes first so nobody scans the hotbar for nothing
        // (no baritone means a test building one by hand, with fastMode on it would otherwise go looking for a hotbar)
        Item clutchItem = baritone != null && ((Baritone) baritone).getInventoryBehavior().clutchAllowed() ? ((Baritone) baritone).getInventoryBehavior().pickClutchItem(false) : null;
        this.hasClutchItem = clutchItem != null;
        this.clutchPicksUp = clutchItem == Items.LADDER && Baritone.settings().pickupLadders.value;
        this.health = health;
        this.fastMode = ExperimentalMovement.on();
        // one read of the dimension, off the world we were handed and not the player's, so a hand built context agrees with itself
        boolean nether = ExperimentalMovement.inNether(world);
        this.hurtingFallsOk = ExperimentalMovement.hurtingFallsOk(nether, fastMode);
        // the End's edges too: every drop there is the void, and the dragon's wings, an enderman's blow and a shulker's bullet
        // all knock sideways
        this.cliffEdges = nether || ExperimentalMovement.inEnd(world);
        // (the buddy's fork, Tuning nether_lava_edges, on by default, route jar 3: the nether prices the nodes beside a lava
        // pool at the feet's height too, where no drop is. the fortress search walked a bastion's bridge by a pool and stepped
        // into its edge at full health, and the potion kept for the blazes nearly went for it, route jar 2 run 1)
        this.lavaEdges = !nether || adris.altoclef.util.helpers.Tuning.get("nether_lava_edges", 0) > 0;
        this.fastModeMinHealth = Baritone.settings().fastModeMinHealth.value;
        this.fallDamageCost = Baritone.settings().fallDamageCost.value;
        this.jumpBias = ExperimentalMovement.jumpBias();
        this.preferFasterPathing = ExperimentalMovement.preferFasterPathing();
        this.blockReach = Baritone.settings().blockReachDistance.value;
        this.canSprint = canSprint;
        this.minY = bsi.minY;
        this.maxY = bsi.maxY;
        this.placeBlockCost = ExperimentalMovement.blockPlacementPenalty();
        this.allowBreak = !paused && Baritone.settings().allowBreak.value;
        // allowBreakAnyway would sneak past a pause otherwise
        this.allowBreakAnyway = paused ? new ArrayList<>() : new ArrayList<>(Baritone.settings().allowBreakAnyway.value);
        // combat stamps the jumps off while it has the wheel (a jump that misses next to a piglin is lava, fastMode or not).
        // read once here on the tick thread, never live from the cost code on the search thread
        long jumpsOffUntil = alto.jumpsOffUntilTick();
        long now = world == null ? 0 : world.getGameTime();
        this.allowParkour = ExperimentalMovement.jumpMoveAllowed(nether, fastMode, Baritone.settings().allowParkour.value, jumpsOffUntil, now);
        this.allowParkourPlace = ExperimentalMovement.jumpMoveAllowed(nether, fastMode, Baritone.settings().allowParkourPlace.value, jumpsOffUntil, now);
        this.allowGodBridge = ExperimentalMovement.allowGodBridge();
        this.allowJumpAtBuildLimit = Baritone.settings().allowJumpAtBuildLimit.value;
        this.allowParkourAscend = ExperimentalMovement.jumpMoveAllowed(nether, fastMode, Baritone.settings().allowParkourAscend.value, jumpsOffUntil, now);
        this.allowNeos = ExperimentalMovement.jumpMoveAllowed(nether, fastMode, Baritone.settings().allowNeos.value, jumpsOffUntil, now);
        this.allowClimbJumps = ExperimentalMovement.jumpMoveAllowed(nether, fastMode, Baritone.settings().allowClimbJumps.value, jumpsOffUntil, now);
        this.allowMomentumJumps = ExperimentalMovement.jumpMoveAllowed(nether, fastMode, Baritone.settings().allowMomentumJumps.value, jumpsOffUntil, now);
        this.assumeWalkOnWater = Baritone.settings().assumeWalkOnWater.value;
        this.allowFallIntoLava = false; // Super secret internal setting for ElytraBehavior
        this.frostWalker = frostWalker;
        // soul speed boots can make sprinting over the stuff cheaper than costHeuristic, and a move priced under the
        // heuristic stops A* being A* (same reason boats are clamped below), so it bottoms out at plain walking
        this.soulSandWalkCost = Math.max(soulSandWalkCost(soulSpeed), Baritone.settings().costHeuristic.value / ActionCosts.SPRINT_MULTIPLIER);
        this.allowDiagonalDescend = ExperimentalMovement.allowDiagonalDescend();
        this.allowDiagonalAscend = ExperimentalMovement.allowDiagonalAscend();
        this.allowDownward = Baritone.settings().allowDownward.value;
        this.minFallHeight = 3; // Minimum fall height used by MovementFall
        this.maxFallHeightNoWater = Baritone.settings().maxFallHeightNoWater.value;
        this.maxFallHeightBucket = Baritone.settings().maxFallHeightBucket.value;
        double waterSpeed = ActionCosts.WALK_ONE_IN_WATER_COST * (1 - waterSpeedMultiplier) + ActionCosts.WALK_ONE_BLOCK_COST * waterSpeedMultiplier;
        this.allowSwimming = ExperimentalMovement.allowSwimming() && this.canSprint;
        // no baritone means a test building one by hand, nobody has been swimming
        this.swimStalls = baritone == null ? SwimStalls.Snapshot.NONE
                : ((Baritone) baritone).getPathingBehavior().swimStalls().snapshot(world, System.currentTimeMillis());
        this.failedMoves = baritone == null ? FailedMoves.Snapshot.NONE
                : ((Baritone) baritone).getPathingBehavior().failedMoves().snapshot(world, System.currentTimeMillis());
        if (this.allowSwimming) {
            // sprint swimming beats wading on the bottom, unless depth strider is good enough to flip that
            waterSpeed = Math.min(waterSpeed, ActionCosts.SWIM_ONE_BLOCK_COST);
        }
        this.waterWalkSpeed = waterSpeed;
        this.lavaSwimCost = LAVA_SWIM_ONE_BLOCK_COST * (1 + LAVA_HP_PER_TICK * Baritone.settings().fallDamageCost.value);
        // a boat really is faster than sprinting, but the heuristic assumes nothing is. price it under
        // costHeuristic and A* stops being A* and wanders off on scenic detours across the lake. so rowing
        // costs what the heuristic thinks a block costs and not a tick less. still way under swimming
        double boatSpeed = Math.max(ActionCosts.BOAT_ONE_BLOCK_COST, Baritone.settings().costHeuristic.value);
        this.boatWaterSpeed = ExperimentalMovement.allowBoats() && hasBoat ? Math.min(waterSpeed, boatSpeed) : waterSpeed;
        this.boatMinWater = Baritone.settings().boatMinWaterLength.value;
        this.boatBoardCost = Baritone.settings().boatBoardCost.value;
        this.boatExitCost = Baritone.settings().boatExitCost.value;
        this.breakBlockAdditionalCost = Baritone.settings().blockBreakAdditionalPenalty.value;
        this.breakIdleTicks = Math.max(0, Baritone.settings().blockBreakSpeed.value - 1);
        this.waterBreakCostMultiplier = Baritone.settings().waterBreakCostMultiplier.value;
        this.backtrackCostFavoringCoefficient = Baritone.settings().backtrackCostFavoringCoefficient.value;
        this.jumpPenalty = Baritone.settings().jumpPenalty.value;
        this.walkOnWaterOnePenalty = Baritone.settings().walkOnWaterOnePenalty.value;
        this.allowWalkOnMagmaBlocks = Baritone.settings().allowWalkOnMagmaBlocks.value;
        // why cache these things here, why not let the movements just get directly from settings?
        // because if some movements are calculated one way and others are calculated another way,
        // then you get a wildly inconsistent path that isn't optimal for either scenario.
        this.worldBorder = bsi.worldBorder;
    }

    // the cheapest a block of descent can be, for GoalYLevel's lower bound. read when a search starts and not in the
    // constructor, because the elytra walk off context raises the fall limits after super() returns.
    // this is what a block of going down really costs, not how fast gravity is. the old floor was the free fall time of the
    // longest fall we could plan (1.16 a block in fastMode), but a fall past 3 blocks hurts and the hearts are in the price
    // (hurtingFall). a floor under what descending costs makes bestSoFar read digging down as the cheapest way to lose
    // height, and a dig step is ~20 ticks, so a downward partial never beat standing still and a deep target was never pathed
    public double descentTicksPerBlock() {
        // the one block descend is always there, and it is the free fall rate with the walk off on top
        double best = ActionCosts.FALL_N_BLOCKS_COST[1];
        int cap = ActionCosts.FALL_N_BLOCKS_COST.length - 1;
        // plain falls are free while they deal no damage (3), past that they cost the same hearts hurtingFall charges.
        // plainFallsAreFree contexts (the elytra walk off) skip the damage for everything inside the limit, and they are
        // the only ones that plan a plain fall past MAX_HURT_FALL. fastMode takes hurting falls on top of the limit
        int plainTop = plainFallsAreFree ? maxFallHeightNoWater : Math.min(maxFallHeightNoWater, ExperimentalMovement.MAX_HURT_FALL);
        int top = Math.min(cap, Math.max(plainTop, hurtingFallsOk ? ExperimentalMovement.MAX_HURT_FALL : 0));
        for (int n = 2; n <= top; n++) {
            boolean free = plainFallsAreFree && n <= maxFallHeightNoWater;
            double damage = free ? 0 : ExperimentalMovement.fallDamage(n) * fallDamageCost;
            best = Math.min(best, (ActionCosts.FALL_N_BLOCKS_COST[n] + damage) / n);
        }
        // the bucket and the clutch are left out on purpose, so this isn't a true lower bound for someone carrying them.
        // pricing the bucket in made the floor 1.49 and a bucket carrier could never path down again (a clutch was worse,
        // under 0.6). those falls only exist where there's a big drop anyway, so the cost of an estimate that's too high
        // is a slightly worse route near cliffs. anyone who raises costHeuristic past its 3.563 default already lives with that
        // falls into deep water or a lava splash have no cap at all, and so does a fall that an existing ladder or vine
        // catches (Descend resets the speed on the cell), so those undercut too. nothing to do about them without the terrain
        return best;
    }

    // whether a move through these two cells (feet, head) is a swim in lava. no toggle check on purpose: lava only gets
    // past canWalkThrough when the toggle is on, and reading the live flag here too is how a search flipped halfway
    // through priced the same lake as water again
    public boolean swimsLava(BlockState feet, BlockState head) {
        return MovementHelper.isLava(feet) || MovementHelper.isLava(head);
    }

    // the cost of one block of water at this node: rowing if a boat could float here and the water is big enough, otherwise
    // whatever wading or swimming costs. close to what BoatTrip does, not identical, see boatRowLongEnough
    public double waterCost(int x, int y, int z) {
        if (boatWaterSpeed >= waterWalkSpeed) {
            return waterWalkSpeed; // no boat, don't go reading nine blocks for nothing
        }
        return boatCell(x, y, z) ? boatWaterSpeed : waterWalkSpeed;
    }

    // whether waterCost prices this cell as a row. only ever true when there's a boat to row
    public boolean boatCell(int x, int y, int z) {
        if (boatWaterSpeed >= waterWalkSpeed) {
            return false;
        }
        if (Thread.currentThread() != miningOwner || boatKeys == null) {
            return MovementHelper.canFloatBoat(bsi, x, y, z) && boatRowLongEnough(x, y, z);
        }
        long key = BlockPos.asLong(x, y, z);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 57);
        if (boatKeys[slot] != key) {
            boatKeys[slot] = key;
            boatAnswers[slot] = MovementHelper.canFloatBoat(bsi, x, y, z) && boatRowLongEnough(x, y, z);
        }
        return boatAnswers[slot];
    }

    // what a move pays for stepping onto or off of a row. the rate per block says nothing about the place and mount at one end
    // and the scuttle and pickup at the other, which A* otherwise gets for free: a 5 block island to walk over was cheaper than
    // any lap round it past about 6 blocks of water. landing in the row is a board, leaving it is an exit, row to row is free
    public double boatHandoff(int x, int y, int z, int destX, int destY, int destZ) {
        if (boatWaterSpeed >= waterWalkSpeed) {
            return 0; // no boat, and no reading nine blocks for nothing
        }
        boolean from = boatCell(x, y, z);
        boolean to = boatCell(destX, destY, destZ);
        if (from == to) {
            return 0;
        }
        return to ? boatBoardCost : boatExitCost;
    }

    // BoatTrip.plan wades a run shorter than boatMinWaterLength instead of getting the boat out, so a pond must not be priced
    // as a boat ride. the planner has no idea what path it's on, so it asks about the straight rows through this block along
    // BOTH axes: a river 4 wide and 100 long has a long row one way and a short one the other, and a crossing is the short one.
    // it can still be off either way: a run down a river is priced as a swim and rowed anyway, and a short clip across the corner of
    // big water is priced as a row and waded. both are small errors. the lookups stop the second a row is long enough: a lake pays for a few, a puddle for even fewer
    private boolean boatRowLongEnough(int x, int y, int z) {
        return boatRow(x, y, z, 1, 0) >= boatMinWater && boatRow(x, y, z, 0, 1) >= boatMinWater;
    }

    private int boatRow(int x, int y, int z, int dx, int dz) {
        int run = 1;
        for (int dir = -1; dir <= 1; dir += 2) {
            for (int i = 1; run < boatMinWater && MovementHelper.canFloatBoat(bsi, x + dx * i * dir, y, z + dz * i * dir); i++) {
                run++;
            }
        }
        return run;
    }

    // a boat in the bag, or one we're sitting in, or a trip between placing one and boarding it. without the last two a
    // replan from the middle of a crossing priced the lake as wading and the shore looked great
    private static boolean hasBoat(IBaritone baritone, LocalPlayer player) {
        if (player.getVehicle() instanceof AbstractBoat) {
            return true;
        }
        PathExecutor current = ((Baritone) baritone).getPathingBehavior().getCurrent();
        if (current != null && current.boatTripActive()) {
            return true;
        }
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.getItem() instanceof BoatItem) {
                return true;
            }
        }
        return false;
    }

    private static int frostWalkerLevel(LocalPlayer player) {
        // todo: technically there can now be datapack enchants that replace blocks with any other at any range
        return enchantLevel(player, Enchantments.FROST_WALKER);
    }

    private static int soulSpeedLevel(LocalPlayer player) {
        // boots only, it does nothing from a hand
        return enchantLevel(player, Enchantments.SOUL_SPEED, EquipmentSlot.FEET);
    }

    private static int enchantLevel(LocalPlayer player, ResourceKey<Enchantment> wanted) {
        return enchantLevel(player, wanted, EquipmentSlot.values());
    }

    private static int enchantLevel(LocalPlayer player, ResourceKey<Enchantment> wanted, EquipmentSlot... slots) {
        int level = 0;
        for (EquipmentSlot slot : slots) {
            ItemEnchantments itemEnchantments = player.getItemBySlot(slot).getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                if (enchant.is(wanted)) {
                    level = itemEnchantments.getLevel(enchant);
                }
            }
        }
        return level;
    }

    // soul sand multiplies your velocity by 0.4 every tick, and the ground drag on top is 0.6 * 0.91, so a walker settles
    // at (1 - 0.546) / (1 - 0.4 * 0.546) = 58% of normal speed, sprint or not (2.5 blocks a second against 4.3).
    // soul speed of any level turns the slowdown off completely (movement_efficiency 1 in its data file) and adds walking
    // speed on top while you are on the stuff, 0.0405 for the first level and 0.0105 for each after that, against a base of 0.1
    public static double soulSandWalkCost(int soulSpeedLevel) {
        if (soulSpeedLevel <= 0) {
            return ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST;
        }
        return ActionCosts.WALK_ONE_BLOCK_COST / (1 + (0.0405 + 0.0105 * (soulSpeedLevel - 1)) / 0.1);
    }

    private static float waterSpeedMultiplier(LocalPlayer player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = player.getItemBySlot(slot).getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects = enchant.value().getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect effect : effects) {
                    if (effect.attribute().is(Attributes.WATER_MOVEMENT_EFFICIENCY.unwrapKey().get())) {
                        return effect.amount().calculate(itemEnchantments.getLevel(enchant));
                    }
                }
            }
        }
        // no depth strider is a 0 here, which prices water at the slow end of the blend. it used to return 1 for "none",
        // which is the full efficiency value, so every lake was planned at the speed of dry land. vanilla with no
        // efficiency drags a walker at 0.8 with a 0.02 push, which is 1.96 blocks a second
        return 0.0f;
    }

    // the memo and the bsi's block cache used to be allocated in the constructor. that's 2.8mb, and PathingBehavior
    // builds one of these every tick whether it paths or not, so it was 50mb/s of garbage for contexts that ask three
    // questions and die. searches now borrow a worker's arrays and detach them when they're done
    // it's also the only thread allowed to touch them. PathExecutor checks costs with this same context on the main thread,
    // and two threads scribbling on one open addressed table is how you get the key from one block and the value from another
    public synchronized void claimSearchCaches() {
        if (miningOwner != null) {
            return; // somebody else is searching with this context. they keep it, we go without
        }
        miningBuffers = SearchCache.acquireMining(1 << miningCacheBits);
        miningKeys = miningBuffers.keys;
        miningKeysFalling = miningBuffers.fallingKeys;
        miningVals = miningBuffers.values;
        miningValsFalling = miningBuffers.fallingValues;
        boatKeys = new long[BOAT_CACHE_SIZE];
        java.util.Arrays.fill(boatKeys, Long.MIN_VALUE);
        boatAnswers = new boolean[BOAT_CACHE_SIZE];
        if (altoAvoidWalkThrough != null) {
            walkThroughKeys = new long[1 << WALK_THROUGH_CACHE_BITS];
            java.util.Arrays.fill(walkThroughKeys, Long.MIN_VALUE);
            walkThroughAnswers = new boolean[1 << WALK_THROUGH_CACHE_BITS];
        }
        miningOwner = Thread.currentThread();
        bsi.claimCache();
    }

    public synchronized void releaseSearchCaches() {
        if (miningOwner != Thread.currentThread()) {
            return;
        }
        miningOwner = null;
        boatKeys = null;
        boatAnswers = null;
        walkThroughKeys = null;
        walkThroughAnswers = null;
        miningKeys = null;
        miningKeysFalling = null;
        miningVals = null;
        miningValsFalling = null;
        SearchCache.release(miningBuffers);
        miningBuffers = null;
        bsi.releaseCache();
    }

    public final IBaritone getBaritone() {
        return baritone;
    }

    public BlockState get(int x, int y, int z) {
        return bsi.get0(x, y, z); // laughs maniacally
    }

    public boolean isLoaded(int x, int z) {
        return bsi.isLoaded(x, z);
    }

    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (!hasThrowaway) { // only true if allowPlace is true, see constructor
            return COST_INF;
        }
        if (isPlaceProtected(x, y, z)) {
            return COST_INF;
        }
        if (!worldBorder.canPlaceAt(x, z)) {
            return COST_INF;
        }
        if (!Baritone.settings().allowPlaceInFluidsSource.value && current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (!Baritone.settings().allowPlaceInFluidsFlow.value && !current.getFluidState().isEmpty() && !current.getFluidState().isSource()) {
            return COST_INF;
        }
        // last on purpose, this is the only check here that has to go ask the world anything
        if (lavaWakes && !burning && LavaWake.wakes(lavaCells, x, y, z)) {
            return COST_INF;
        }
        return placeBlockCost;
    }

    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        if (isBreakProtected(x, y, z)) {
            return COST_INF;
        }
        return 1;
    }

    // parkour, neo and climb all run their cost through this where it's produced, so the planner and calculateCost agree
    public double biasJump(double cost) {
        return cost * jumpBias;
    }

    public double placeBucketCost() {
        return placeBlockCost; // shrug
    }

    // the two halves of what used to be one isPossiblyProtected stub. altoclef is the only thing that fills them in so far
    // (see #220 for anyone who wants to add their own)
    public boolean isBreakProtected(int x, int y, int z) {
        if (altoBreakPositions != null && altoBreakPositions.contains(BlockPos.asLong(x, y, z))) {
            return true;
        }
        return altoBreakAvoiders != null && altoAnyMatch(altoBreakAvoiders, x, y, z);
    }

    public boolean isPlaceProtected(int x, int y, int z) {
        return altoPlaceAvoiders != null && altoAnyMatch(altoPlaceAvoiders, x, y, z);
    }

    // callers check altoActive first so the idle case doesn't even get here
    public boolean altoForcesWalkOn(int x, int y, int z) {
        return altoWalkOn != null && altoAnyMatch(altoWalkOn, x, y, z);
    }

    public boolean altoAvoidsWalkThrough(int x, int y, int z) {
        if (altoAvoidWalkThrough == null) {
            return false;
        }
        if (Thread.currentThread() != miningOwner || walkThroughKeys == null) {
            return altoAnyMatch(altoAvoidWalkThrough, x, y, z);
        }
        long key = BlockPos.asLong(x, y, z);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - WALK_THROUGH_CACHE_BITS));
        if (walkThroughKeys[slot] != key) {
            walkThroughKeys[slot] = key;
            walkThroughAnswers[slot] = altoAnyMatch(altoAvoidWalkThrough, x, y, z);
        }
        return walkThroughAnswers[slot];
    }

    private boolean altoAnyMatch(Predicate<BlockPos>[] predicates, int x, int y, int z) {
        // the executor checks costs with the same context while a search is running, so the shared pos only goes to the
        // thread that claimed the search caches. anybody else pays for a fresh one
        BlockPos pos = Thread.currentThread() == miningOwner ? altoPos.set(x, y, z) : new BlockPos(x, y, z);
        return AltoClefSettings.Snapshot.anyMatch(predicates, pos);
    }
}
