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

package baritone.api;

import baritone.api.utils.BlockRange;
import baritone.api.utils.ForceFieldStrategy;
import baritone.api.utils.Helper;
import baritone.api.utils.NotificationHelper;
import baritone.api.utils.OverworldToNetherBehaviour;
import baritone.api.utils.SettingsUtil;
import baritone.api.utils.TypeUtils;
import baritone.api.utils.gui.BaritoneToast;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Baritone's settings. Settings apply to all Baritone instances.
 *
 * @author leijurv
 */
public final class Settings {
    private static final Logger LOGGER = LoggerFactory.getLogger("Baritone");

    /**
     * Allow Baritone to break blocks
     */
    public final Setting<Boolean> allowBreak = new Setting<>(true);

    /**
     * Blocks that baritone will be allowed to break even with allowBreak set to false
     */
    public final Setting<List<Block>> allowBreakAnyway = new Setting<>(new ArrayList<>());

    /**
     * Allow Baritone to sprint
     */
    public final Setting<Boolean> allowSprint = new Setting<>(true);

    /**
     * Allow Baritone to place blocks
     */
    public final Setting<Boolean> allowPlace = new Setting<>(true);

    /**
     * Allow Baritone to place blocks in fluid source blocks
     */
    public final Setting<Boolean> allowPlaceInFluidsSource = new Setting<>(true);

    /**
     * Allow Baritone to place blocks in flowing fluid
     */
    public final Setting<Boolean> allowPlaceInFluidsFlow = new Setting<>(true);

    /**
     * Allow Baritone to move items in your inventory to your hotbar
     */
    public final Setting<Boolean> allowInventory = new Setting<>(false);

    /**
     * Wait this many ticks between InventoryBehavior moving inventory items
     */
    public final Setting<Integer> ticksBetweenInventoryMoves = new Setting<>(1);

    /**
     * Come to a halt before doing any inventory moves. Intended for anticheat such as 2b2t
     */
    public final Setting<Boolean> inventoryMoveOnlyIfStationary = new Setting<>(false);

    /**
     * Disable baritone's auto-tool at runtime, but still assume that another mod will provide auto tool functionality
     * <p>
     * Specifically, path calculation will still assume that an auto tool will run at execution time, even though
     * Baritone itself will not do that.
     */
    public final Setting<Boolean> assumeExternalAutoTool = new Setting<>(false);

    /**
     * Automatically select the best available tool
     */
    public final Setting<Boolean> autoTool = new Setting<>(true);

    /**
     * It doesn't actually take twenty ticks to place a block, this cost is so high
     * because we want to generally conserve blocks which might be limited.
     * <p>
     * Decrease to make Baritone more often consider paths that would require placing blocks
     */
    public final Setting<Double> blockPlacementPenalty = new Setting<>(20D);

    /**
     * This is just a tiebreaker to make it less likely to break blocks if it can avoid it.
     * For example, fire has a break cost of 0, this makes it nonzero, so all else being equal
     * it will take an otherwise equivalent route that doesn't require it to put out fire.
     */
    public final Setting<Double> blockBreakAdditionalPenalty = new Setting<>(2D);

    /**
     * How much slower breaking blocks is while standing in water, as far as the path cost is concerned.
     * Vanilla mines 5x slower with your eyes in water (no aqua affinity) and another 5x slower when you are
     * not on the ground, so treading water is up to 25x. Movements that break something while the player
     * stands in water pay this much (times 5 again if there is nothing solid under the feet) for the break
     * part of their cost, so the pathfinder would rather get out of the water first and mine on land.
     * <p>
     * It only ever makes things more expensive, it never makes a move impossible. Set it to 1 to turn it off.
     */
    public final Setting<Double> waterBreakCostMultiplier = new Setting<>(5D);

    /**
     * Additional penalty for hitting the space bar (ascend, pillar, or parkour) because it uses hunger
     */
    public final Setting<Double> jumpPenalty = new Setting<>(2D);

    /**
     * Walking on water uses up hunger really quick, so penalize it
     */
    public final Setting<Double> walkOnWaterOnePenalty = new Setting<>(3D);

    /**
     * Don't allow breaking blocks next to liquids.
     * <p>
     * Enable if you have mods adding custom fluid physics.
     */
    public final Setting<Boolean> strictLiquidCheck = new Setting<>(false);

    /**
     * Allow Baritone to fall up to {@link #maxFallHeightBucket} blocks and place a water bucket beneath it.
     * Reliability: questionable, and a miss is the whole fall, so the planner only offers a drop while you would survive that.
     */
    public final Setting<Boolean> allowWaterBucketFall = new Setting<>(true);

    /**
     * Allow Baritone to survive a long fall by placing a ladder or vine on a wall next to the fall at the last moment, like
     * a water bucket clutch but with a block you can carry in the nether.
     * <p>
     * Needs a ladder or vine on the hotbar and a wall beside the last few blocks of the fall. The water bucket is still
     * preferred when you have one. Reliability: also questionable, it's all about the timing, and a clutch that misses is
     * just the whole fall. The planner won't offer one below {@link #fastModeMinHealth} for that reason, or for a drop a miss
     * would kill you in.
     * <p>
     * Part of {@link #fastMode}.
     */
    public final Setting<Boolean> allowLadderClutch = new Setting<>(false);

    /**
     * After a ladder clutch, break the ladder we put on the wall and pick it back up, so one ladder can save you from
     * as many long falls as you like.
     * <p>
     * Only ladders we placed ourselves are touched, never one that was already there. Vines need shears to drop anything,
     * so those stay on the wall. If the ladder can't be reached or it takes too long, we just leave it and carry on.
     * Costs a second or so per clutch, which the pathing knows about.
     */
    public final Setting<Boolean> pickupLadders = new Setting<>(true);

    /**
     * Hit back while a Baritone job (mine, tunnel, build, farm, follow, goto, explore) is running. When a hostile mob
     * hits you and is within melee reach, the job pauses where it is, the bot takes out its best sword (or axe) from the
     * hotbar and hits the mob until it dies or walks off, then puts the old item back and carries on with the job.
     * <p>
     * It never chases or paths anywhere, it only swings at what is already in reach, and it gives up on a mob it can't
     * hit or can't hurt after a few seconds. Creepers, wardens, withers, creakings, the dragon and the flying ones
     * (phantoms, vexes, ghasts, blazes) are left alone. It stays out of the way in water, in lava, while flying an elytra
     * and while a chest or other container is open. Tasks like {@code #get} and {@code #gamer} have their own combat, so
     * this does nothing while one of those is running.
     */
    public final Setting<Boolean> selfDefense = new Setting<>(true);

    /**
     * Allow Baritone to assume it can walk on still water just like any other block.
     * This functionality is assumed to be provided by a separate library that might have imported Baritone.
     * <p>
     * Note: This will prevent some usage of the frostwalker enchantment, like pillaring up from water.
     */
    public final Setting<Boolean> assumeWalkOnWater = new Setting<>(false);

    /**
     * If you have Fire Resistance and Jesus then I guess you could turn this on lol
     */
    public final Setting<Boolean> assumeWalkOnLava = new Setting<>(false);

    /**
     * Assume step functionality; don't jump on an Ascend.
     */
    public final Setting<Boolean> assumeStep = new Setting<>(false);

    /**
     * Assume safe walk functionality; don't sneak on a backplace traverse.
     * <p>
     * Warning: if you do something janky like sneak-backplace from an ender chest, if this is true
     * it won't sneak right click, it'll just right click, which means it'll open the chest instead of placing
     * against it. That's why this defaults to off.
     */
    public final Setting<Boolean> assumeSafeWalk = new Setting<>(false);

    /**
     * Bridge backwards at walking speed without sneaking or stopping. When a bridge has nothing to place against
     * except the block you are standing on, the bot turns around, walks backwards across the gap and places each
     * block against the far face of the block underfoot the moment that face can be hit. The normal way is to
     * crouch at the edge, aim, wait, click and shuffle on.
     * <p>
     * This is riskier than the sneaking bridge. For a stretch of each block the bot hangs over the edge with no
     * sneak clamp holding it, so a missed placement depends on it catching itself by sneaking at the last moment.
     * Laggy servers and strict anticheats will not like it, and stopping the bot (or anything else that lets go of the
     * keys) in the middle of a block can drop it off the edge, because it is still moving when that happens.
     * <p>
     * Only bridges with no side placement option are affected, and only from plain footing. Anything slippery or slow
     * under your feet (ice, slime, honey) keeps the sneaking bridge, and so does footing that opens when you click it
     * (chests, furnaces, crafting tables and the like), water, and standing in anything climbable (ladders, vines, scaffolding and the like). Magma counts as plain
     * unless {@link #allowWalkOnMagmaBlocks} is on. Soul sand and half slabs can't be placed against from behind at
     * all, sneaking or not, so they never get this far.
     */
    public final Setting<Boolean> allowGodBridge = new Setting<>(false);

    /**
     * If true, parkour is allowed to make jumps when standing on blocks at the maximum height, so player feet is y=256
     * <p>
     * Defaults to false because this fails on constantiam. Please let me know if this is ever disabled. Please.
     */
    public final Setting<Boolean> allowJumpAtBuildLimit = new Setting<>(false);

    /**
     * Just here so mods that use the API don't break. Does nothing.
     */
    @Deprecated
    @JavaOnly
    public final Setting<Boolean> allowJumpAt256 = new Setting<>(false);

    /**
     * This should be monetized it's so good
     * <p>
     * Defaults to true, but only actually takes effect if allowParkour is also true
     */
    public final Setting<Boolean> allowParkourAscend = new Setting<>(true);

    /**
     * Jump around the end of a wall instead of walking the long way round, like a neo on a parkour map
     * <p>
     * Only actually takes effect if {@link #allowParkour} is also true. Needs sprinting, and a block behind the jump to run
     * up on. Handles walls one or two blocks thick, landing two to four blocks out.
     */
    public final Setting<Boolean> allowNeos = new Setting<>(false);

    /**
     * Jump onto ladders and vines across a gap, and off of them again, instead of walking the long way round
     * <p>
     * Only actually takes effect if {@link #allowParkour} is also true. Grabbing is a sprint jump (or a plain jump for the
     * short ones) from flat floor, one to three blocks of gap, catching the ladder or vine mid air. A ladder has to be
     * hanging on the far wall, vines can be caught from any side. Leaping goes the other way, climbing out sideways off a
     * ladder or vine to land one block of gap away on the same level or a bit lower (or on another ladder or vine).
     * You can't start sprinting while you're hanging on something, so leaps are short.
     */
    public final Setting<Boolean> allowClimbJumps = new Setting<>(false);

    /**
     * Run up on the blocks behind a jump (and bunny hop on them if there's room) to make jumps a standing start can't:
     * a gap of four blocks on the flat, or one block up across a gap of three (both need a hop, so three or more blocks of
     * flat floor behind the takeoff), jumps that land one to three blocks lower and reach out to five or six blocks (parkour
     * never goes down, and these don't need a long run up), and a few pairs of jumps that land on a block in the middle and
     * jump again on the very tick they touch down.
     * <p>
     * Only actually takes effect if {@link #allowParkour} is also true. Needs sprinting and a straight line along one
     * direction. Drops are limited by {@link #maxFallHeightNoWater}. The first time you play with this on, it takes a few seconds in the background to
     * work out which jumps are possible, and the result is saved to momentum-table.txt in the baritone folder.
     */
    public final Setting<Boolean> allowMomentumJumps = new Setting<>(false);

    /**
     * Allow descending diagonally
     * <p>
     * Safer than allowParkour yet still slightly unsafe, can make contact with unchecked adjacent blocks, so it's unsafe in the nether.
     * <p>
     * For a generic "take some risks" mode I'd turn on this one, parkour, and parkour place.
     */
    public final Setting<Boolean> allowDiagonalDescend = new Setting<>(false);

    /**
     * Allow diagonal ascending
     * <p>
     * Actually pretty safe, much safer than diagonal descend tbh
     */
    public final Setting<Boolean> allowDiagonalAscend = new Setting<>(false);

    /**
     * Allow mining the block directly beneath its feet
     * <p>
     * Turn this off to force it to make more staircases and less shafts
     */
    public final Setting<Boolean> allowDownward = new Setting<>(true);

    /**
     * Blocks that Baritone is allowed to place (as throwaway, for sneak bridging, pillaring, etc.)
     */
    public final Setting<List<Item>> acceptableThrowawayItems = new Setting<>(new ArrayList<>(Arrays.asList(
            Blocks.DIRT.asItem(),
            Blocks.COBBLESTONE.asItem(),
            Blocks.NETHERRACK.asItem(),
            Blocks.STONE.asItem()
    )));

    /**
     * Blocks that Baritone will attempt to avoid (Used in avoidance)
     */
    public final Setting<List<Block>> blocksToAvoid = new Setting<>(new ArrayList<>(List.of(
            Blocks.TRIPWIRE
    )));

    /**
     * Blocks that Baritone is not allowed to break
     * <p>
     * Bee nests and beehives are on it by default, because breaking one lets out a swarm of angry bees.
     * Clear the list to allow breaking them again.
     */
    public final Setting<List<Block>> blocksToDisallowBreaking = new Setting<>(new ArrayList<>(Arrays.asList(
            Blocks.BEE_NEST,
            Blocks.BEEHIVE
    )));

    /**
     * blocks that baritone shouldn't break, but can if it needs to.
     */
    public final Setting<List<Block>> blocksToAvoidBreaking = new Setting<>(new ArrayList<>(Arrays.asList( // TODO can this be a HashSet or ImmutableSet?
            Blocks.CRAFTING_TABLE,
            Blocks.FURNACE,
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST
    )));

    /**
     * this multiplies the break speed, if set above 1 it's "encourage breaking" instead
     */
    public final Setting<Double> avoidBreakingMultiplier = new Setting<>(.1);

    /**
     * A list of blocks to be treated as if they're air.
     * <p>
     * If a schematic asks for air at a certain position, and that position currently contains a block on this list, it will be treated as correct.
     */
    public final Setting<List<Block>> buildIgnoreBlocks = new Setting<>(new ArrayList<>(Arrays.asList(

    )));

    /**
     * A list of blocks to be treated as correct.
     * <p>
     * If a schematic asks for any block on this list at a certain position, it will be treated as correct, regardless of what it currently is.
     */
    public final Setting<List<Block>> buildSkipBlocks = new Setting<>(new ArrayList<>(Arrays.asList(

    )));

    /**
     * A mapping of blocks to blocks treated as correct in their position
     * <p>
     * If a schematic asks for a block on this mapping, all blocks on the mapped list will be accepted at that location as well
     * <p>
     * Syntax same as {@link #buildSubstitutes}
     */
    public final Setting<Map<Block, List<Block>>> buildValidSubstitutes = new Setting<>(new HashMap<>());

    /**
     * A mapping of blocks to blocks to be built instead
     * <p>
     * If a schematic asks for a block on this mapping, Baritone will place the first placeable block in the mapped list
     * <p>
     * Usage Syntax:
     * <pre>
     *      sourceblockA->blockToSubstituteA1,blockToSubstituteA2,...blockToSubstituteAN,sourceBlockB->blockToSubstituteB1,blockToSubstituteB2,...blockToSubstituteBN,...sourceBlockX->blockToSubstituteX1,blockToSubstituteX2...blockToSubstituteXN
     * </pre>
     * Example:
     * <pre>
     *     stone->cobblestone,andesite,oak_planks->birch_planks,acacia_planks,glass
     * </pre>
     */
    public final Setting<Map<Block, List<Block>>> buildSubstitutes = new Setting<>(new HashMap<>());

    /**
     * A list of blocks to become air
     * <p>
     * If a schematic asks for a block on this list, only air will be accepted at that location (and nothing on buildIgnoreBlocks)
     */
    public final Setting<List<Block>> okIfAir = new Setting<>(new ArrayList<>(Arrays.asList(

    )));

    /**
     * If this is true, the builder will treat all non-air blocks as correct. It will only place new blocks.
     */
    public final Setting<Boolean> buildIgnoreExisting = new Setting<>(false);

    /**
     * If this is true, the builder will ignore directionality of certain blocks like glazed terracotta.
     */
    public final Setting<Boolean> buildIgnoreDirection = new Setting<>(false);

    /**
     * A list of names of block properties the builder will ignore.
     */
    public final Setting<List<String>> buildIgnoreProperties = new Setting<>(new ArrayList<>(Arrays.asList(
    )));

    /**
     * If this setting is true, Baritone will never break a block that is adjacent to an unsupported falling block.
     * <p>
     * I.E. it will never trigger cascading sand / gravel falls
     */
    public final Setting<Boolean> avoidUpdatingFallingBlocks = new Setting<>(true);

    /**
     * Enables some more advanced vine features. They're honestly just gimmicks and won't ever be needed in real
     * pathing scenarios. And they can cause Baritone to get trapped indefinitely in a strange scenario.
     * <p>
     * Almost never turn this on lol
     */
    public final Setting<Boolean> allowVines = new Setting<>(false);

    /**
     * Slab behavior is complicated, disable this for higher path reliability. Leave enabled if you have bottom slabs
     * everywhere in your base.
     */
    public final Setting<Boolean> allowWalkOnBottomSlab = new Setting<>(true);

    /**
     * You know what it is
     * <p>
     * But it's very unreliable and falls off when cornering like all the time so.
     * <p>
     * It also overshoots the landing pretty much always (making contact with the next block over), so be careful
     */
    public final Setting<Boolean> allowParkour = new Setting<>(false);

    /**
     * Place the block to land on in mid air, while doing a parkour jump, for gaps that are too wide to clear without it.
     * <p>
     * Actually pretty reliable, but there is no taking it back: once the jump is committed, a placement that misses in the
     * air leaves nothing to land on, and that is a fall. Only actually takes effect if {@link #allowParkour} is also
     * true. Part of {@link #fastMode}.
     */
    public final Setting<Boolean> allowParkourPlace = new Setting<>(false);

    /**
     * For example, if you have Mining Fatigue or Haste, adjust the costs of breaking blocks accordingly.
     */
    public final Setting<Boolean> considerPotionEffects = new Setting<>(true);

    /**
     * Sprint and jump a block early on ascends wherever possible. The sprint also carries through a hop into the next
     * traverse, ascend or descend when the path goes straight on, instead of dropping in mid air and landing at walk
     * speed. It also carries on up a whole flight of
     * straight stairs climbed from the low side (bottom half, no corner shape, from a full height floor). Those are walked
     * and not jumped whatever this is set to, the way vanilla steps up a half block.
     */
    public final Setting<Boolean> sprintAscends = new Setting<>(true);

    /**
     * Keep sprinting down a ledge, step or small fall (3 blocks at most) when the path carries on in roughly the same direction (within 45 degrees)
     * and the blocks you would coast over after landing are safe: no lava, fire, magma, powder snow, cactus,
     * berry bushes, water or void, and no drop deeper than the one the path already planned.
     * <p>
     * Does nothing in the nether, within 3 blocks of lava, at 6 health or less, or while a nearby movement is
     * placing blocks or breaking anything that is not gone the moment it is clicked (leaves with shears, netherrack with a
     * fast pick; two such breaks at most). Those keep the old stop-sprinting-on-every-descend behavior.
     */
    public final Setting<Boolean> sprintThroughDescends = new Setting<>(true);

    /**
     * Keep sprinting through corners that need 60 degrees of turning or less, and start rotating a fraction of a block
     * early so the corner is cut instead of clipped. Sharp turns (90 degrees and up) next to hazards, and corners in
     * 1 wide corridors where cutting would clip a wall, keep their normal steering.
     * <p>
     * Does nothing in the nether, within 3 blocks of lava, at 6 health or less, or while a nearby movement is
     * placing blocks or breaking anything that is not gone the moment it is clicked.
     */
    public final Setting<Boolean> sprintThroughCorners = new Setting<>(true);

    /**
     * Sprint jump in 1x2 corridors and whenever walking under a low ceiling, bonking our head on it.
     * <p>
     * The sprint jump speed boost applies before we hit the ceiling, making this faster than just sprinting.
     */
    public final Setting<Boolean> headHitters = new Setting<>(false);

    /**
     * Also sprint jump under low ceilings on flat diagonal movements when {@link #headHitters} is enabled.
     * Enabled by default. Disable this to limit head hitters to straight movements.
     */
    public final Setting<Boolean> headHittersDiagonal = new Setting<>(true);

    /**
     * Sprint jump along straight stretches of path, up single block steps and down small hills. Every jump is simulated
     * first and only happens if it lands back on the path without fall damage.
     * <p>
     * Also sprints over 1 and 2 block parkour gaps, when you reach them already sprinting and the path carries on
     * straight for a block or two past the landing. The jump brakes in the air if it would fly past, and the planner
     * prices those jumps as the sprint hops they are. Anywhere that does not hold, they are walked up to the edge like before.
     */
    public final Setting<Boolean> sprintJumping = new Setting<>(false);

    /**
     * Also sprint jump along flat diagonal stretches of path when {@link #sprintJumping} is enabled.
     */
    public final Setting<Boolean> sprintJumpingDiagonals = new Setting<>(true);

    /**
     * If we overshoot a traverse and end up one block beyond the destination, mark it as successful anyway.
     * <p>
     * This helps with speed exceeding 20m/s
     */
    public final Setting<Boolean> overshootTraverse = new Setting<>(true);

    /**
     * Prefer paths and steering that preserve walking and sprinting momentum.
     * <p>
     * Look ahead along clear, supported straight runs instead of steering at each block center.
     * Diagonals that require edging around an obstacle receive an extra cost; open diagonals keep
     * their normal cost. Turns, terrain changes, and block interactions retain precise steering.
     * Part of {@link #fastMode}.
     */
    public final Setting<Boolean> preferFasterPathing = new Setting<>(false);

    /**
     * Take direct ground routes across bends in flat walking paths, instead of visiting every block center.
     * <p>
     * Only uses loaded terrain with clear body space and full solid support across the player's width.
     * Jumps, climbs, block interactions, and hazardous or slippery terrain retain their normal movements,
     * and an eligible sprint jump runway wins over a shortcut.
     * Rechecks the route each tick and replans if it becomes obstructed.
     * Part of {@link #fastMode}.
     */
    public final Setting<Boolean> allowGroundShortcuts = new Setting<>(false);

    /**
     * Fast mode. Move like a speedrunner instead of a robot: take the line a fast player would, accept some risk, use the flashy movement.
     * <p>
     * While this is on, these settings behave as if they were on, whatever their own value (the settings themselves
     * are left alone, so turning this off gives you your old choices back):
     * {@link #allowParkour}, {@link #allowParkourAscend}, {@link #allowParkourPlace}, {@link #allowLadderClutch}, {@link #allowNeos}, {@link #allowClimbJumps},
     * {@link #allowMomentumJumps},
     * {@link #allowDiagonalAscend}, {@link #allowDiagonalDescend}, {@link #sprintJumping}, {@link #headHitters},
     * {@link #allowGroundShortcuts}, {@link #allowGodBridge}, {@link #allowSwimming}, {@link #allowBoats},
     * {@link #elytraAutoJump} and {@link #preferFasterPathing}.
     * <p>
     * It also scales the cost of parkour, neo, climb and momentum jumps by {@link #experimentalJumpBias}, caps the cost of
     * placing a block at {@link #experimentalBlockPlacementPenalty} (so a quick pillar or short bridge can beat a
     * long walk around), and allows falls that hurt, see {@link #fastModeMinHealth} and {@link #fallDamageCost}.
     */
    public final Setting<Boolean> fastMode = new Setting<>(false);

    /**
     * Multiplier on the cost of parkour, neo, climb and momentum jumps while {@link #fastMode} is on.
     * Below 1 makes the jumpy route look a bit cheaper than the equivalent walk, which is the whole fun of it.
     */
    public final Setting<Double> experimentalJumpBias = new Setting<>(0.9D);

    /**
     * While {@link #fastMode} is on, the block placement penalty is the lower of
     * {@link #blockPlacementPenalty} and this. Placing a block really takes about a tick, so this is still pessimistic,
     * just not so much that a two block bridge loses to a twenty block walk.
     */
    public final Setting<Double> experimentalBlockPlacementPenalty = new Setting<>(5D);

    /**
     * While {@link #fastMode} is on, Baritone may take a fall that hurts when there is no water bucket or
     * clutch to make it free, but never one that would leave you with less health (plus absorption) than this.
     * Measured in half hearts, so 12 is six hearts. Damage is estimated as vanilla fall damage before armor
     * and feather falling, which is conservative. Falls inside {@link #maxFallHeightNoWater} only have to leave you alive.
     */
    public final Setting<Double> fastModeMinHealth = new Setting<>(12D);

    /**
     * Cost, in ticks, of every half heart a damaging fall takes off you, while {@link #fastMode} is on.
     * Higher makes Baritone walk around a drop more often, lower makes it jump off things.
     */
    public final Setting<Double> fallDamageCost = new Setting<>(20D);

    /**
     * When breaking blocks for a movement, wait until all falling blocks have settled before continuing
     */
    public final Setting<Boolean> pauseMiningForFallingBlocks = new Setting<>(true);

    /**
     * How many ticks between right clicks are allowed. Default in game is 4
     */
    public final Setting<Integer> rightClickSpeed = new Setting<>(4);

    /**
     * How many degrees to randomize the yaw every tick. Set to 0 to disable
     */
    public final Setting<Double> randomLooking113 = new Setting<>(2d);

    /**
     * Block reach distance
     */
    public final Setting<Float> blockReachDistance = new Setting<>(4.5f);

    /**
     * How many ticks between breaking a block and starting to break the next block. Default in game is 6 ticks.
     * Values under 1 will be clamped. The delay only applies to non-instant (1-tick) breaks.
     */
    public final Setting<Integer> blockBreakSpeed = new Setting<>(6);

    /**
     * How many degrees to randomize the pitch and yaw every tick. Set to 0 to disable
     */
    public final Setting<Double> randomLooking = new Setting<>(0.01d);

    /**
     * Turn onto mobs in melee a few degrees per tick, the way a hand on a mouse does, instead of snapping the camera
     * onto them.
     * <p>
     * The turn starts slow, speeds up and settles on a point inside the middle of the mob, then tracks it as it moves.
     * Swings only go out once the crosshair is really on the mob. {@link #randomLooking113} and {@link #randomLooking}
     * still bound how far the aim wanders while it holds on a target, as a slow drift instead of a per tick shake.
     * <p>
     * Off snaps onto the same point in one tick, with the same rules about when a swing may go out.
     */
    public final Setting<Boolean> smoothCombatAim = new Setting<>(true);

    /**
     * This is the big A* setting.
     * As long as your cost heuristic is an *underestimate*, it's guaranteed to find you the best path.
     * 3.5 is always an underestimate, even if you are sprinting.
     * If you're walking only (with allowSprint off) 4.6 is safe.
     * Any value below 3.5 is never worth it. It's just more computation to find the same path, guaranteed.
     * (specifically, it needs to be strictly slightly less than ActionCosts.WALK_ONE_BLOCK_COST, which is about 3.56)
     * <p>
     * Setting it at 3.57 or above with sprinting, or to 4.64 or above without sprinting, will result in
     * faster computation, at the cost of a suboptimal path. Any value above the walk / sprint cost will result
     * in it going straight at its goal, and not investigating alternatives, because the combined cost / heuristic
     * metric gets better and better with each block, instead of slightly worse.
     * <p>
     * Finding the optimal path is worth it, so it's the default.
     */
    public final Setting<Double> costHeuristic = new Setting<>(3.563);

    // a bunch of obscure internal A* settings that you probably don't want to change
    /**
     * The maximum number of times it will fetch outside loaded or cached chunks before assuming that
     * pathing has reached the end of the known area, and should therefore stop.
     */
    public final Setting<Integer> pathingMaxChunkBorderFetch = new Setting<>(50);

    /**
     * Set to 1.0 to effectively disable this feature
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/18">Issue #18</a>
     */
    public final Setting<Double> backtrackCostFavoringCoefficient = new Setting<>(0.5);

    /**
     * Toggle the following 4 settings
     * <p>
     * They have a noticeable performance impact, so they default off
     * <p>
     * Specifically, building up the avoidance map on the main thread before pathing starts actually takes a noticeable
     * amount of time, especially when there are a lot of mobs around, and your game jitters for like 200ms while doing so
     */
    public final Setting<Boolean> avoidance = new Setting<>(false);

    /**
     * Set to 1.0 to effectively disable this feature
     * <p>
     * Set below 1.0 to go out of your way to walk near mob spawners
     */
    public final Setting<Double> mobSpawnerAvoidanceCoefficient = new Setting<>(2.0);

    /**
     * Distance to avoid mob spawners.
     */
    public final Setting<Integer> mobSpawnerAvoidanceRadius = new Setting<>(16);

    /**
     * Set to 1.0 to effectively disable this feature
     * <p>
     * Set below 1.0 to go out of your way to walk near mobs
     */
    public final Setting<Double> mobAvoidanceCoefficient = new Setting<>(1.5);

    /**
     * Distance to avoid mobs.
     */
    public final Setting<Integer> mobAvoidanceRadius = new Setting<>(8);

    /**
     * When running a goto towards a container block (chest, ender chest, furnace, etc),
     * right click and open it once you arrive.
     */
    public final Setting<Boolean> rightClickContainerOnArrival = new Setting<>(true);

    /**
     * When running a goto towards a nether portal block, walk all the way into the portal
     * instead of stopping one block before.
     */
    public final Setting<Boolean> enterPortal = new Setting<>(true);

    /**
     * Don't repropagate cost improvements below 0.01 ticks. They're all just floating point inaccuracies,
     * and there's no point.
     */
    public final Setting<Boolean> minimumImprovementRepropagation = new Setting<>(true);

    /**
     * After calculating a path (potentially through cached chunks), artificially cut it off to just the part that is
     * entirely within currently loaded chunks. Improves path safety because cached chunks are heavily simplified.
     * <p>
     * This is much safer to leave off now, and makes pathing more efficient. More explanation in the issue.
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/114">Issue #114</a>
     */
    public final Setting<Boolean> cutoffAtLoadBoundary = new Setting<>(false);

    /**
     * If a movement's cost increases by more than this amount between calculation and execution (due to changes
     * in the environment / world), cancel and recalculate
     */
    public final Setting<Double> maxCostIncrease = new Setting<>(10D);

    /**
     * Stop 5 movements before anything that made the path COST_INF.
     * For example, if lava has spread across the path, don't walk right up to it then recalculate, it might
     * still be spreading lol
     */
    public final Setting<Integer> costVerificationLookahead = new Setting<>(5);

    /**
     * Static cutoff factor. 0.9 means cut off the last 10% of all paths, regardless of chunk load state
     */
    public final Setting<Double> pathCutoffFactor = new Setting<>(0.9);

    /**
     * Only apply static cutoff for paths of at least this length (in terms of number of movements)
     */
    public final Setting<Integer> pathCutoffMinimumLength = new Setting<>(30);

    /**
     * Start planning the next path once the remaining movements tick estimates sum up to less than this value
     */
    public final Setting<Integer> planningTickLookahead = new Setting<>(150);

    /**
     * Default size of the Long2ObjectOpenHashMap used in pathing
     */
    public final Setting<Integer> pathingMapDefaultSize = new Setting<>(1024);

    /**
     * Load factor coefficient for the Long2ObjectOpenHashMap used in pathing
     * <p>
     * Decrease for faster map operations, but higher memory usage
     */
    public final Setting<Float> pathingMapLoadFactor = new Setting<>(0.75f);

    /**
     * Number of entries in each block-state and mining-cost cache used during a path search.
     * <p>
     * Rounded up to a power of two and clamped between 1024 and 65536.
     * Smaller caches reduce memory usage but may require more block lookups.
     * The size is captured when a calculation context is created.
     * Worker threads reuse the arrays between searches, clearing their keys each time.
     */
    public final Setting<Integer> pathingCacheSize = new Setting<>(65536);

    /**
     * How far are you allowed to fall onto solid ground (without a water bucket)?
     * 3 won't deal any damage. But if you just want to get down the mountain quickly and you have
     * Feather Falling IV, you might set it a bit higher, like 4 or 5.
     * <p>
     * Only the first 3 blocks are planned as a free fall. Anything past that up to this limit is a fall that hurts, so
     * a ladder or vine clutch (see {@link #allowLadderClutch}) or a water bucket is used when one works, and otherwise
     * the damage is priced in with {@link #fallDamageCost} and the fall only happens if it leaves you alive.
     */
    public final Setting<Integer> maxFallHeightNoWater = new Setting<>(3);

    /**
     * How far are you allowed to fall onto solid ground (with a water bucket)?
     * It's not that reliable, so I've set it below what would kill an unarmored player (23)
     * <p>
     * The bucket can still miss, and a miss is the whole fall, so a drop is only planned while you would survive that
     * miss with at least half a heart left. At low health a tall drop is left out even with a bucket in hand.
     */
    public final Setting<Integer> maxFallHeightBucket = new Setting<>(20);

    /**
     * Is it okay to sprint through a descend followed by a diagonal?
     * The player overshoots the landing, but not enough to fall off. And the diagonal ensures that there isn't
     * lava or anything that's !canWalkInto in that space, so it's technically safe, just a little sketchy.
     * <p>
     * Note: this is *not* related to the allowDiagonalDescend setting, that is a completely different thing.
     */
    public final Setting<Boolean> allowOvershootDiagonalDescend = new Setting<>(true);

    /**
     * If your goal is a GoalBlock in an unloaded chunk, assume it's far enough away that the Y coord
     * doesn't matter yet, and replace it with a GoalXZ to the same place before calculating a path.
     * Once a segment ends within chunk load range of the GoalBlock, it will go back to normal behavior
     * of considering the Y coord. The reasoning is that if your X and Z are 10,000 blocks away,
     * your Y coordinate's accuracy doesn't matter at all until you get much much closer.
     */
    public final Setting<Boolean> simplifyUnloadedYCoord = new Setting<>(true);

    /**
     * Whenever a block changes, repack the whole chunk that it's in
     */
    public final Setting<Boolean> repackOnAnyBlockChange = new Setting<>(true);

    /**
     * If a movement takes this many ticks more than its initial cost estimate, cancel it
     */
    public final Setting<Integer> movementTimeoutTicks = new Setting<>(100);

    /**
     * Pathing ends after this amount of time, but only if a path has been found
     * <p>
     * If no valid path (length above the minimum) has been found, pathing continues up until the failure timeout
     */
    public final Setting<Long> primaryTimeoutMS = new Setting<>(500L);

    /**
     * Pathing can never take longer than this, even if that means failing to find any path at all
     */
    public final Setting<Long> failureTimeoutMS = new Setting<>(2000L);

    /**
     * Planning ahead while executing a segment ends after this amount of time, but only if a path has been found
     * <p>
     * If no valid path (length above the minimum) has been found, pathing continues up until the failure timeout
     */
    public final Setting<Long> planAheadPrimaryTimeoutMS = new Setting<>(4000L);

    /**
     * Planning ahead while executing a segment can never take longer than this, even if that means failing to find any path at all
     */
    public final Setting<Long> planAheadFailureTimeoutMS = new Setting<>(5000L);

    /**
     * For debugging, consider nodes much much slower
     */
    public final Setting<Boolean> slowPath = new Setting<>(false);

    /**
     * Milliseconds between each node
     */
    public final Setting<Long> slowPathTimeDelayMS = new Setting<>(100L);

    /**
     * The alternative timeout number when slowPath is on
     */
    public final Setting<Long> slowPathTimeoutMS = new Setting<>(40000L);


    /**
     * allows baritone to save bed waypoints when interacting with beds
     */
    public final Setting<Boolean> doBedWaypoints = new Setting<>(true);

    /**
     * allows baritone to save death waypoints
     */
    public final Setting<Boolean> doDeathWaypoints = new Setting<>(true);

    /**
     * The big one. Download all chunks in simplified 2-bit format and save them for better very-long-distance pathing.
     */
    public final Setting<Boolean> chunkCaching = new Setting<>(true);

    /**
     * On save, delete from RAM any cached regions that are more than 1024 blocks away from the player
     * <p>
     * Temporarily disabled
     * <p>
     * Temporarily reenabled
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/248">Issue #248</a>
     */
    public final Setting<Boolean> pruneRegionsFromRAM = new Setting<>(true);

    /**
     * The chunk packer queue can never grow to larger than this, if it does, the oldest chunks are discarded
     * <p>
     * The newest chunks are kept, so that if you're moving in a straight line quickly then stop, your immediate render distance is still included
     */
    public final Setting<Integer> chunkPackerQueueMaxSize = new Setting<>(2000);

    /**
     * Fill in blocks behind you
     */
    public final Setting<Boolean> backfill = new Setting<>(false);

    /**
     * Place torches in dark spots near you while Baritone is busy mining or walking
     * <p>
     * Needs torches in the hotbar (or the inventory with {@link #allowInventory}). It only places while another process
     * (mine, goto, explore, a task like #get...) is running, never while building, in the Nether or the End, or near water and lava.
     * Spots with sky light above {@link #autoTorchLightLevel} are left alone, so the surface does not get covered in torches.
     */
    public final Setting<Boolean> autoTorch = new Setting<>(false);

    /**
     * {@link #autoTorch} places a torch where both the block light and the sky light are at or below this (0 to 15)
     * <p>
     * Spacing between torches falls out of this: higher means more torches closer together.
     */
    public final Setting<Integer> autoTorchLightLevel = new Setting<>(7);

    /**
     * How far around you {@link #autoTorch} looks for dark spots, in blocks. Never further than {@link #blockReachDistance}
     */
    public final Setting<Integer> autoTorchRadius = new Setting<>(4);

    /**
     * Shows popup message in the upper right corner, similarly to when you make an advancement
     */
    public final Setting<Boolean> logAsToast = new Setting<>(false);

    /**
     * Print all the debug messages to chat
     */
    public final Setting<Boolean> chatDebug = new Setting<>(false);

    /**
     * Allow chat based control of Baritone. Most likely should be disabled when Baritone is imported for use in
     * something else
     */
    public final Setting<Boolean> chatControl = new Setting<>(true);

    /**
     * Some clients like Impact try to force chatControl to off, so here's a second setting to do it anyway
     */
    public final Setting<Boolean> chatControlAnyway = new Setting<>(false);

    /**
     * Measure how long every movement really takes against what the planner said it would, and keep the numbers.
     * <p>
     * For each movement the executor runs from its first tick to success without a pause, a cancel, a resync or a
     * jump ahead in the path, it writes one line to {@code movement-timings.csv} in the baritone folder of your
     * minecraft folder (next to {@code settings.txt}): the movement kind and offset, the planned ticks, the real
     * ticks, and a few flags (blocks broken and placed, sprinting, in water, standing still at the start, the floor
     * under the start, {@link #fastMode}). Lines are batched and written by a background thread a few seconds after
     * they happen, never on the game thread. The file is capped at 4 MiB: when it is full it is renamed to
     * {@code movement-timings.csv.old} (replacing the older one) and a fresh one starts, so at most about 8 MiB is
     * ever used.
     * <p>
     * Use the {@code timings} command to print which movements are off the most, and {@code timings clear} to throw
     * the data away.
     * <p>
     * This only measures. Nothing reads the numbers back to change a cost, and with this off the executor only reads
     * this setting once per movement and does nothing else.
     */
    public final Setting<Boolean> recordMovementTimings = new Setting<>(false);

    /**
     * Debug logging for sprint that drops when it should not have, and for how well the movement sim predicts the player.
     * <p>
     * Every tick the player was sprinting at the start of the last tick and is not now, while a path is being walked, one
     * line says which movement it was and the ones around it, who was steering, where the feet were, the collision flags the
     * drop was decided from (and which blocks the hitbox was leaning on), the forward input, the food, what the executor
     * wanted and why it said no, which ascend rule pressed space last and what the sim had said about it, and whether a
     * pause let go of the keys. A line follows when sprint comes back. Every ascend takeoff gets a line too. The report
     * comes when the path ends or is stopped and every 1000 path ticks: drops (the ones at the very end of a path counted
     * apart), how long sprint stayed off, by cause and by movement, so two runs can be compared.
     * <p>
     * Every tick the sim of the player's own movement is run from the state the tick started in with the inputs that were
     * really sent, the result is held against what really happened next, and a line is logged when the position is off by more
     * than 0.01 or the sprint, ground or collision flags differ.
     * <p>
     * Lines go to the debug log (chat, if {@link #chatDebug} is on) and to the game log with a {@code [sprintTelemetry]}
     * prefix. It is a lot of text, so it is off by default, and {@link #fastMode} does not turn it on.
     */
    public final Setting<Boolean> sprintTelemetry = new Setting<>(false);

    /**
     * Check the keys of a hop up a block against the movement sim, every tick, and let go of W (or of W and space) when
     * holding them would run the hitbox into the face of the step.
     * <p>
     * Up a staircase of full blocks every landing is a fraction of a block from the next face, so W and space clip it on
     * every step. The sim looks about a dozen ticks ahead and, when the keys as the movement pressed them would clip, tries
     * W released and then nothing at all. It is a safety net under the jump timing the ascend already asks the sim for, and
     * only while sprinting. It saves the dead stop against the face, not the sprint: letting go of W costs that for a tick
     * too. At most 16 ticks per movement, after that the movement gets its keys back.
     */
    public final Setting<Boolean> guardAscends = new Setting<>(true);

    /**
     * Back up before a hop up a block when the sim says the player is too close to the face to jump from where it stands.
     * <p>
     * A sprint hop clips the face of the step if it starts too close, and standing flush against it nothing but backing up
     * helps. The sim looks for a few ticks of W held while facing away from the step (backwards, so the player slows down and
     * rolls back), then forward again and the usual jump, and takes it only when that gets over the step cleanly and no later
     * than jumping on the spot would. Vanilla only ends a sprint when forward is let go of, and W held is forward whatever the
     * yaw says, so the sprint survives the whole thing. The plan is followed without searching again for as long as the player
     * does what the sim said it would.
     * <p>
     * This turns the player's facing a half circle away from where it is going for a tick or three, which an anticheat that
     * checks the sprint direction against the movement direction can look at funny. Turn it off on servers like that.
     */
    public final Setting<Boolean> brakeAscends = new Setting<>(true);

    /**
     * Render the path
     */
    public final Setting<Boolean> renderPath = new Setting<>(true);

    /**
     * Render the path as a line instead of a frickin thingy
     */
    public final Setting<Boolean> renderPathAsLine = new Setting<>(false);

    /**
     * Render the path with soft edges, rounded corners and a tail that fades out behind you.
     * <p>
     * It's still the same floating line, {@link #pathRenderLineWidthPixels} wide, with the same bit of height
     * to it unless {@link #renderPathAsLine} is on. When this is false you get the classic gl lines back.
     */
    public final Setting<Boolean> renderPathRibbon = new Setting<>(true);

    /**
     * Smooth out the rendering of the path calculation, so that the best path grows and fades instead of
     * flickering around, and the most recent nodes branch off it as a tree.
     * <p>
     * Only does anything when {@link #renderPathRibbon} is on.
     */
    public final Setting<Boolean> renderSearchSmooth = new Setting<>(true);

    /**
     * Let a faint shimmer drift down the path, in the direction of travel.
     * <p>
     * Only does anything when {@link #renderPathRibbon} is on.
     */
    public final Setting<Boolean> renderPathAnimated = new Setting<>(true);

    /**
     * Fill the goal box and the selection boxes (to break, to place, to walk into) with a faint tint,
     * instead of only drawing their outlines
     */
    public final Setting<Boolean> renderBoxFill = new Setting<>(true);

    /**
     * Render the goal
     */
    public final Setting<Boolean> renderGoal = new Setting<>(true);

    /**
     * Render the goal as a sick animated thingy instead of just a box
     * (also controls animation of GoalXZ if {@link #renderGoalXZBeacon} is enabled)
     */
    public final Setting<Boolean> renderGoalAnimated = new Setting<>(true);

    /**
     * Render selection boxes
     */
    public final Setting<Boolean> renderSelectionBoxes = new Setting<>(true);

    /**
     * Ignore depth when rendering the goal
     */
    public final Setting<Boolean> renderGoalIgnoreDepth = new Setting<>(true);

    /**
     * Renders X/Z type Goals as a no-depth beacon beam instead of the full-height goal box.
     */
    public final Setting<Boolean> renderGoalXZBeacon = new Setting<>(false);

    /**
     * Ignore depth when rendering the selection boxes (to break, to place, to walk into)
     */
    public final Setting<Boolean> renderSelectionBoxesIgnoreDepth = new Setting<>(true);

    /**
     * Ignore depth when rendering the path
     */
    public final Setting<Boolean> renderPathIgnoreDepth = new Setting<>(true);

    /**
     * Line width of the path when rendered, in pixels
     */
    public final Setting<Float> pathRenderLineWidthPixels = new Setting<>(5F);

    /**
     * Line width of the goal when rendered, in pixels
     */
    public final Setting<Float> goalRenderLineWidthPixels = new Setting<>(3F);

    /**
     * Start fading out the path at 20 movements ahead, and stop rendering it entirely 30 movements ahead.
     * Improves FPS.
     */
    public final Setting<Boolean> fadePath = new Setting<>(false);

    /**
     * Move without having to force the client-sided rotations
     */
    public final Setting<Boolean> freeLook = new Setting<>(true);

    /**
     * Break and place blocks without having to force the client-sided rotations. Requires {@link #freeLook}.
     */
    public final Setting<Boolean> blockFreeLook = new Setting<>(false);

    /**
     * Automatically elytra fly without having to force the client-sided rotations.
     */
    public final Setting<Boolean> elytraFreeLook = new Setting<>(true);

    /**
     * Forces the client-sided yaw rotation to an average of the last {@link #smoothLookTicks} of server-sided rotations.
     */
    public final Setting<Boolean> smoothLook = new Setting<>(false);

    /**
     * Same as {@link #smoothLook} but for elytra flying.
     */
    public final Setting<Boolean> elytraSmoothLook = new Setting<>(false);

    /**
     * The number of ticks to average across for {@link #smoothLook};
     */
    public final Setting<Integer> smoothLookTicks = new Setting<>(5);

    /**
     * When true, the player will remain with its existing look direction as often as possible.
     * Although, in some cases this can get it stuck, hence this setting to disable that behavior.
     */
    public final Setting<Boolean> remainWithExistingLookDirection = new Setting<>(true);

    /**
     * Will cause some minor behavioral differences to ensure that Baritone works on anticheats.
     * <p>
     * At the moment this will silently set the player's rotations when using freeLook so you're not sprinting in
     * directions other than forward, which is picken up by more "advanced" anticheats like AAC, but not NCP.
     */
    public final Setting<Boolean> antiCheatCompatibility = new Setting<>(true);

    /**
     * Exclusively use cached chunks for pathing
     * <p>
     * Never turn this on
     */
    public final Setting<Boolean> pathThroughCachedOnly = new Setting<>(false);

    /**
     * Continue sprinting while in water
     */
    public final Setting<Boolean> sprintInWater = new Setting<>(true);

    /**
     * Actually swim through water instead of bobbing along the bottom of it
     * <p>
     * Swimming works the same way it does for a player: hold sprint (ctrl) while in water to enter the swim
     * state, then steer with yaw and pitch towards the goal. Since sprinting is what keeps the swim state
     * alive, this requires {@link #allowSprint} and enough hunger to sprint; without it, baritone falls back
     * to the normal walk-on-the-bottom behavior.
     * <p>
     * Water traversal is also costed at swim speed when this is on, unless depth strider makes walking the
     * bottom the faster option.
     */
    public final Setting<Boolean> allowSwimming = new Setting<>(false);

    /**
     * Take a boat across water if there's one in the inventory
     * <p>
     * When the path is about to enter a long enough stretch of open water (see {@link #boatMinWaterLength}),
     * baritone places the boat from the shore, climbs in, rows along the path, and at the far side breaks the
     * boat and picks it back up (see {@link #boatPickup}) before carrying on on foot. Water traversal is costed
     * at rowing speed while a boat is in the inventory, so the planner will prefer going across a lake instead
     * of around it. Only water with a straight row of at least {@link #boatMinWaterLength} boatable blocks through it
     * in both directions is priced that way, so a pond or a narrow river costs what wading does.
     * <p>
     * A boat is wider than a block, so it only counts water with open water or air on every side at water
     * level; the planner routes a block off the banks for the same reason. Bubble columns (water over magma
     * blocks or soul sand) sink or launch boats, so water on or next to one is never rowed through. If the
     * boat can't be placed or gets stuck, baritone gets out and continues the path the normal way.
     */
    public final Setting<Boolean> allowBoats = new Setting<>(false);

    /**
     * Placing, boarding and scuttling a boat costs a few seconds, so don't bother for a puddle. This is the
     * number of consecutive water blocks the path has to cross before {@link #allowBoats} uses the boat.
     */
    public final Setting<Integer> boatMinWaterLength = new Setting<>(8);

    /**
     * Break the boat and pick it back up when leaving the water, so it can be used again. When off, baritone
     * just climbs out and leaves the boat behind.
     */
    public final Setting<Boolean> boatPickup = new Setting<>(true);

    /**
     * What the planner charges, in ticks, for stepping from land (or from water that isn't worth a boat) onto a stretch
     * it would row: getting the boat out, aiming it at the water and climbing in. Without it the shore end of a
     * crossing is free, so the planner would hop out of the boat for a short walk over an island and put it back in.
     * Higher means it goes around islands and spits of land more willingly, lower means it cuts across them. Only
     * applies while {@link #allowBoats} is on and there is a boat to row.
     */
    public final Setting<Double> boatBoardCost = new Setting<>(20D);

    /**
     * Same idea as {@link #boatBoardCost} for the other end of a row: breaking the boat and waiting for the item to
     * come back to us, in ticks. Slower than boarding because the boat takes five hits to break and the dropped item
     * can't be picked up right away. Charged with {@link #boatPickup} off too: leaving the boat behind for good is no
     * cheaper a thing to plan on.
     */
    public final Setting<Double> boatExitCost = new Setting<>(30D);

    /**
     * When GetToBlockProcess or MineProcess fails to calculate a path, instead of just giving up, mark the closest instance
     * of that block as "unreachable" and go towards the next closest. GetToBlock expands this search to the whole "vein"; MineProcess does not.
     * This is because MineProcess finds individual impossible blocks (like one block in a vein that has gravel on top then lava, so it can't break)
     * Whereas GetToBlock should blacklist the whole "vein" if it can't get to any of them.
     */
    public final Setting<Boolean> blacklistClosestOnFailure = new Setting<>(true);

    /**
     * 😎 Render cached chunks as semitransparent. Doesn't work with OptiFine 😭 Rarely randomly crashes, see <a href="https://github.com/cabaletta/baritone/issues/327">this issue</a>.
     * <p>
     * Can be very useful on servers with low render distance. After enabling, you may need to reload the world in order for it to have an effect
     * (e.g. disconnect and reconnect, enter then exit the nether, die and respawn, etc). This may literally kill your FPS and CPU because
     * every chunk gets recompiled twice as much as normal, since the cached version comes into range, then the normal one comes from the server for real.
     * <p>
     * Note that flowing water is cached as AVOID, which is rendered as lava. As you get closer, you may therefore see lava falls being replaced with water falls.
     * <p>
     * SOLID is rendered as stone in the overworld, netherrack in the nether, and end stone in the end
     */
    public final Setting<Boolean> renderCachedChunks = new Setting<>(false);

    /**
     * 0.0f = not visible, fully transparent (instead of setting this to 0, turn off renderCachedChunks)
     * 1.0f = fully opaque
     */
    public final Setting<Float> cachedChunksOpacity = new Setting<>(0.5f);

    /**
     * Whether or not to allow you to run Baritone commands with the prefix
     */
    public final Setting<Boolean> prefixControl = new Setting<>(true);

    /**
     * The command prefix for chat control
     */
    public final Setting<String> prefix = new Setting<>("#");

    /**
     * Use a short prefix [S] instead of [Soprano] when logging to chat
     */
    public final Setting<Boolean> shortBaritonePrefix = new Setting<>(false);

    /**
     * Use a modern message tag instead of a prefix when logging to chat
     */
    public final Setting<Boolean> useMessageTag = new Setting<>(false);

    /**
     * Echo commands to chat when they are run
     */
    public final Setting<Boolean> echoCommands = new Setting<>(true);

    /**
     * Censor coordinates in goals and block positions
     */
    public final Setting<Boolean> censorCoordinates = new Setting<>(false);

    /**
     * Censor arguments to ran commands, to hide, for example, coordinates to #goal
     */
    public final Setting<Boolean> censorRanCommands = new Setting<>(false);

    /**
     * Stop using tools just before they are going to break.
     */
    public final Setting<Boolean> itemSaver = new Setting<>(false);

    /**
     * Durability to leave on the tool when using itemSaver
     */
    public final Setting<Integer> itemSaverThreshold = new Setting<>(10);

    /**
     * Always prefer silk touch tools over regular tools. This will not sacrifice speed, but it will always prefer silk
     * touch tools over other tools of the same speed. This includes always choosing ANY silk touch tool over your hand.
     */
    public final Setting<Boolean> preferSilkTouch = new Setting<>(false);

    /**
     * Don't stop walking forward when you need to break blocks in your way
     */
    public final Setting<Boolean> walkWhileBreaking = new Setting<>(true);

    /**
     * When a new segment is calculated that doesn't overlap with the current one, but simply begins where the current segment ends,
     * splice it on and make a longer combined path. If this setting is off, any planned segment will not be spliced and will instead
     * be the "next path" in PathingBehavior, and will only start after this one ends. Turning this off hurts planning ahead,
     * because the next segment will exist even if it's very short.
     *
     * @see #planningTickLookahead
     */
    public final Setting<Boolean> splicePath = new Setting<>(true);

    /**
     * If we are more than 300 movements into the current path, discard the oldest segments, as they are no longer useful
     */
    public final Setting<Integer> maxPathHistoryLength = new Setting<>(300);

    /**
     * If the current path is too long, cut off this many movements from the beginning.
     */
    public final Setting<Integer> pathHistoryCutoffAmount = new Setting<>(50);

    /**
     * Rescan for the goal once every 5 ticks.
     * Set to 0 to disable.
     */
    public final Setting<Integer> mineGoalUpdateInterval = new Setting<>(5);

    /**
     * After finding this many instances of the target block in the cache, it will stop expanding outward the chunk search.
     */
    public final Setting<Integer> maxCachedWorldScanCount = new Setting<>(10);

    /**
     * Mine will not scan for or remember more than this many target locations.
     * Note that the number of locations retrieved from cache is additionaly
     * limited by {@link #maxCachedWorldScanCount}.
     */
    public final Setting<Integer> mineMaxOreLocationsCount = new Setting<>(64);

    /**
     * Sets the minimum y level whilst mining - set to 0 to turn off.
     * if world has negative y values, subtract the min world height to get the value to put here
     */
    public final Setting<Integer> minYLevelWhileMining = new Setting<>(0);

    /**
     * Sets the maximum y level to mine ores at.
     */
    public final Setting<Integer> maxYLevelWhileMining = new Setting<>(2031);

    /**
     * This will only allow baritone to mine exposed ores, can be used to stop ore obfuscators on servers that use them.
     */
    public final Setting<Boolean> allowOnlyExposedOres = new Setting<>(false);

    /**
     * When allowOnlyExposedOres is enabled this is the distance around to search.
     * <p>
     * It is recommended to keep this value low, as it dramatically increases calculation times.
     */
    public final Setting<Integer> allowOnlyExposedOresDistance = new Setting<>(1);

    /**
     * When GetToBlock or non-legit Mine doesn't know any locations for the desired block, explore randomly instead of giving up.
     */
    public final Setting<Boolean> exploreForBlocks = new Setting<>(true);

    /**
     * While exploring the world, offset the closest unloaded chunk by this much in both axes.
     * <p>
     * This can result in more efficient loading, if you set this to the render distance.
     */
    public final Setting<Integer> worldExploringChunkOffset = new Setting<>(0);

    /**
     * Take the 10 closest chunks, even if they aren't strictly tied for distance metric from origin.
     */
    public final Setting<Integer> exploreChunkSetMinimumSize = new Setting<>(10);

    /**
     * Attempt to maintain Y coordinate while exploring
     * <p>
     * -1 to disable
     */
    public final Setting<Integer> exploreMaintainY = new Setting<>(64);

    /**
     * Replant normal Crops while farming and leave cactus and sugarcane to regrow
     */
    public final Setting<Boolean> replantCrops = new Setting<>(true);

    /**
     * Replant nether wart while farming. This setting only has an effect when replantCrops is also enabled
     */
    public final Setting<Boolean> replantNetherWart = new Setting<>(false);

    /**
     * When enabled, farming will be restricted to the current selection.
     */
    public final Setting<Boolean> farmUsingSelection = new Setting<>(false);

    /**
     * Farming will scan for at most this many blocks.
     */
    public final Setting<Integer> farmMaxScanSize = new Setting<>(256);

    /**
     * Keep the farm process active when crops are present but none are mature
     * enough to harvest yet. Disabled by default to preserve legacy behavior.
     */
    public final Setting<Boolean> farmWaitForGrowth = new Setting<>(false);

    /**
     * When the cache scan gives less blocks than the maximum threshold (but still above zero), scan the main world too.
     * <p>
     * Only if you have a beefy CPU and automatically mine blocks that are in cache
     */
    public final Setting<Boolean> extendCacheOnThreshold = new Setting<>(false);

    /**
     * Don't consider the next layer in builder until the current one is done
     */
    public final Setting<Boolean> buildInLayers = new Setting<>(false);

    /**
     * false = build from bottom to top
     * <p>
     * true = build from top to bottom
     */
    public final Setting<Boolean> layerOrder = new Setting<>(false);

    /**
     * How high should the individual layers be?
     */
    public final Setting<Integer> layerHeight = new Setting<>(1);

    /**
     * Start building the schematic at a specific layer.
     * Can help on larger builds when schematic wants to break things its already built
     */
    public final Setting<Integer> startAtLayer = new Setting<>(0);

    /**
     * If a layer is unable to be constructed, just skip it.
     */
    public final Setting<Boolean> skipFailedLayers = new Setting<>(false);

    /**
     * Only build the selected part of schematics
     */
    public final Setting<Boolean> buildOnlySelection = new Setting<>(false);

    /**
     * How far to move before repeating the build. 0 to disable repeating on a certain axis, 0,0,0 to disable entirely
     */
    public final Setting<Vec3i> buildRepeat = new Setting<>(new Vec3i(0, 0, 0));

    /**
     * How many times to buildrepeat. -1 for infinite.
     */
    public final Setting<Integer> buildRepeatCount = new Setting<>(-1);

    /**
     * Don't notify schematics that they are moved.
     * e.g. replacing will replace the same spots for every repetition
     * Mainly for backward compatibility.
     */
    public final Setting<Boolean> buildRepeatSneaky = new Setting<>(true);

    /**
     * Allow standing above a block while mining it, in BuilderProcess
     * <p>
     * Experimental
     */
    public final Setting<Boolean> breakFromAbove = new Setting<>(false);

    /**
     * As well as breaking from above, set a goal to up and to the side of all blocks to break.
     * <p>
     * Never turn this on without also turning on breakFromAbove.
     */
    public final Setting<Boolean> goalBreakFromAbove = new Setting<>(false);

    /**
     * Build in map art mode, which makes baritone only care about the top block in each column
     */
    public final Setting<Boolean> mapArtMode = new Setting<>(false);

    /**
     * Override builder's behavior to not attempt to correct blocks that are currently water
     */
    public final Setting<Boolean> okIfWater = new Setting<>(false);

    /**
     * The set of incorrect blocks can never grow beyond this size
     */
    public final Setting<Integer> incorrectSize = new Setting<>(100);

    /**
     * Multiply the cost of breaking a block that's correct in the builder's schematic by this coefficient
     */
    public final Setting<Double> breakCorrectBlockPenaltyMultiplier = new Setting<>(10d);

    /**
     * Multiply the cost of placing a block that's incorrect in the builder's schematic by this coefficient
     */
    public final Setting<Double> placeIncorrectBlockPenaltyMultiplier = new Setting<>(2d);

    /**
     * When this setting is true, build a schematic with the highest X coordinate being the origin, instead of the lowest
     */
    public final Setting<Boolean> schematicOrientationX = new Setting<>(false);

    /**
     * When this setting is true, build a schematic with the highest Y coordinate being the origin, instead of the lowest
     */
    public final Setting<Boolean> schematicOrientationY = new Setting<>(false);

    /**
     * When this setting is true, build a schematic with the highest Z coordinate being the origin, instead of the lowest
     */
    public final Setting<Boolean> schematicOrientationZ = new Setting<>(false);

    /**
     * Rotates the schematic before building it.
     * Possible values are
     * <ul>
     *  <li> NONE - No rotation </li>
     *  <li> CLOCKWISE_90 - Rotate 90° clockwise </li>
     *  <li> CLOCKWISE_180 - Rotate 180° clockwise </li>
     *  <li> COUNTERCLOCKWISE_90 - Rotate 270° clockwise </li>
     * </ul>
     */
    public final Setting<Rotation> buildSchematicRotation = new Setting<>(Rotation.NONE);

    /**
     * Mirrors the schematic before building it.
     * Possible values are
     * <ul>
     *  <li> FRONT_BACK - mirror the schematic along its local x axis </li>
     *  <li> LEFT_RIGHT - mirror the schematic along its local z axis </li>
     * </ul>
     */
    public final Setting<Mirror> buildSchematicMirror = new Setting<>(Mirror.NONE);

    /**
     * How see-through the ghost blocks of the schematic picked in the gui's Schematics tab are, from 0 to 1. Below 0.1
     * nothing is drawn.
     */
    public final Setting<Float> schematicGhostAlpha = new Setting<>(0.45f);

    /**
     * How far from the camera, in blocks, the ghost blocks of the schematic picked in the gui's Schematics tab are
     * drawn (8 to 128).
     */
    public final Setting<Integer> schematicGhostDistance = new Setting<>(64);

    /**
     * The fallback used by the build command when no extension is specified. This may be useful if schematics of a
     * particular format are used often, and the user does not wish to have to specify the extension with every usage.
     */
    public final Setting<String> schematicFallbackExtension = new Setting<>("schematic");

    /**
     * Distance to scan every tick for updates. Expanding this beyond player reach distance (i.e. setting it to 6 or above)
     * is only necessary in very large schematics where rescanning the whole thing is costly.
     */
    public final Setting<Integer> builderTickScanRadius = new Setting<>(5);

    /**
     * While mining, should it also consider dropped items of the correct type as a pathing destination (as well as ore blocks)?
     */
    public final Setting<Boolean> mineScanDroppedItems = new Setting<>(true);

    /**
     * While mining, wait this number of milliseconds after mining an ore to see if it will drop an item
     * instead of immediately going onto the next one
     * <p>
     * Thanks Louca
     */
    public final Setting<Long> mineDropLoiterDurationMSThanksLouca = new Setting<>(250L);

    /**
     * Only build the goal from incorrect positions near you, plus the nearest few far ones when nothing near can be
     * done. Far positions are still remembered. Helps performance in very large schematics
     */
    public final Setting<Boolean> distanceTrim = new Setting<>(true);

    /**
     * Cancel the current path if the goal has changed, and the path originally ended in the goal but doesn't anymore.
     * <p>
     * Currently only runs when either MineBehavior or FollowBehavior is active.
     * <p>
     * For example, if Baritone is doing "mine iron_ore", the instant it breaks the ore (and it becomes air), that location
     * is no longer a goal. This means that if this setting is true, it will stop there. If this setting were off, it would
     * continue with its path, and walk into that location. The tradeoff is if this setting is true, it mines ores much faster
     * since it doesn't waste any time getting into locations that no longer contain ores, but on the other hand, it misses
     * some drops, and continues on without ever picking them up.
     * <p>
     * Also on cosmic prisons this should be set to true since you don't actually mine the ore it just gets replaced with stone.
     */
    public final Setting<Boolean> cancelOnGoalInvalidation = new Setting<>(true);

    /**
     * When a task hands over to another one that asks for the same goal within half a second, keep walking the path that
     * was just cancelled instead of standing still while a new one is calculated.
     * <p>
     * Only cancels from movement tasks starting and stopping are kept. A user cancel, a dimension change, a death or the
     * player ending up off the path always throw it away.
     */
    public final Setting<Boolean> keepPathOnSameGoal = new Setting<>(true);

    /**
     * On a trip you started yourself ({@code goto}, {@code come}, {@code waypoints goto}, or clicking a block in the
     * click to go screen), ask before collecting blocks the path needs. It asks when the goal can't be reached without
     * placing blocks you don't have, or when a path that needs blocks (or a stone pickaxe) is a lot faster, see
     * {@link #fasterPathMinPercent} and {@link #fasterPathMinSeconds}.
     * <p>
     * The question has clickable Yes and No buttons in chat (typing {@code #gather yes} or {@code #gather no} works
     * too) and is asked at most once per trip. The answer holds until the trip ends: arriving, {@code stop}, a new
     * trip, dying or changing dimension. Yes collects about what the path needs (through Soprano) and carries on to the
     * goal.
     * <p>
     * Off means the old behavior: the path fails and the bot stops, nothing is asked.
     */
    public final Setting<Boolean> askToGatherForPath = new Setting<>(true);

    /**
     * How much faster, in percent of the normal route's time, a route that needs blocks has to be before
     * {@link #askToGatherForPath} asks about it. The time it takes to collect the blocks counts against it. Both this
     * and {@link #fasterPathMinSeconds} have to hold.
     */
    public final Setting<Integer> fasterPathMinPercent = new Setting<>(30);

    /**
     * How many seconds a route that needs blocks has to save, after counting the time to collect them, before
     * {@link #askToGatherForPath} asks about it. Both this and {@link #fasterPathMinPercent} have to hold.
     */
    public final Setting<Integer> fasterPathMinSeconds = new Setting<>(30);

    /**
     * A path that would place more blocks than this is never worth asking about for {@link #askToGatherForPath}.
     */
    public final Setting<Integer> gatherMaxBlocks = new Setting<>(128);

    /**
     * After a yes for {@link #askToGatherForPath}, how many more times the bot may go and collect blocks on the same
     * trip without asking again (a long trip can run out halfway). Past this it stops with a message.
     */
    public final Setting<Integer> gatherMaxRetries = new Setting<>(3);

    /**
     * How many ticks collecting one block is guessed to take, for the faster route check of
     * {@link #askToGatherForPath}. On the slow side on purpose.
     */
    public final Setting<Double> gatherTicksPerBlock = new Setting<>(40D);

    /**
     * How many ticks making a stone pickaxe from nothing is guessed to take (a tree, a crafting table, some stone), for
     * the faster route check of {@link #askToGatherForPath}.
     */
    public final Setting<Double> gatherPickaxeTicks = new Setting<>(2400D);

    /**
     * The "axis" command (aka GoalAxis) will go to a axis, or diagonal axis, at this Y level.
     */
    public final Setting<Integer> axisHeight = new Setting<>(120);

    /**
     * Disconnect from the server upon arriving at your goal
     */
    public final Setting<Boolean> disconnectOnArrival = new Setting<>(false);

    /**
     * Disallow MineBehavior from using X-Ray to see where the ores are. Turn this option on to force it to mine "legit"
     * where it will only mine an ore once it can actually see it, so it won't do or know anything that a normal player
     * couldn't. If you don't want it to look like you're X-Raying, turn this on
     * This will always explore, regardless of exploreForBlocks
     */
    public final Setting<Boolean> legitMine = new Setting<>(false);

    /**
     * What Y level to go to for legit strip mining
     */
    public final Setting<Integer> legitMineYLevel = new Setting<>(-59);

    /**
     * Magically see ores that are separated diagonally from existing ores. Basically like mining around the ores that it finds
     * in case there's one there touching it diagonally, except it checks it un-legit-ly without having the mine blocks to see it.
     * You can decide whether this looks plausible or not.
     * <p>
     * This is disabled because it results in some weird behavior. For example, it can """see""" the top block of a vein of iron_ore
     * through a lava lake. This isn't an issue normally since it won't consider anything touching lava, so it just ignores it.
     * However, this setting expands that and allows it to see the entire vein so it'll mine under the lava lake to get the iron that
     * it can reach without mining blocks adjacent to lava. This really defeats the purpose of legitMine since a player could never
     * do that lol, so thats one reason why its disabled
     */
    public final Setting<Boolean> legitMineIncludeDiagonals = new Setting<>(false);

    /**
     * When mining block of a certain type, try to mine two at once instead of one.
     * If the block above is also a goal block, set GoalBlock instead of GoalTwoBlocks
     * If the block below is also a goal block, set GoalBlock to the position one down instead of GoalTwoBlocks
     */
    public final Setting<Boolean> forceInternalMining = new Setting<>(true);

    /**
     * Modification to the previous setting, only has effect if forceInternalMining is true
     * If true, only apply the previous setting if the block adjacent to the goal isn't air.
     */
    public final Setting<Boolean> internalMiningAirException = new Setting<>(true);

    /**
     * The actual GoalNear is set this distance away from the entity you're following
     * <p>
     * For example, set followOffsetDistance to 5 and followRadius to 0 to always stay precisely 5 blocks north of your follow target.
     */
    public final Setting<Double> followOffsetDistance = new Setting<>(0D);

    /**
     * The actual GoalNear is set in this direction from the entity you're following. This value is in degrees.
     */
    public final Setting<Float> followOffsetDirection = new Setting<>(0F);

    /**
     * The radius (for the GoalNear) of how close to your target position you actually have to be
     */
    public final Setting<Integer> followRadius = new Setting<>(3);

    /**
     * The maximum distance to the entity you're following
     */
    public final Setting<Integer> followTargetMaxDistance = new Setting<>(0);

    /**
     * Turn this on if your exploration filter is enormous, you don't want it to check if it's done,
     * and you are just fine with it just hanging on completion
     */
    public final Setting<Boolean> disableCompletionCheck = new Setting<>(false);

    /**
     * Cached chunks (regardless of if they're in RAM or saved to disk) expire and are deleted after this number of seconds
     * -1 to disable
     * <p>
     * I would highly suggest leaving this setting disabled (-1).
     * <p>
     * The only valid reason I can think of enable this setting is if you are extremely low on disk space and you play on multiplayer,
     * and can't take (average) 300kb saved for every 512x512 area. (note that more complicated terrain is less compressible and will take more space)
     * <p>
     * However, simply discarding old chunks because they are old is inadvisable. Baritone is extremely good at correcting
     * itself and its paths as it learns new information, as new chunks load. There is no scenario in which having an
     * incorrect cache can cause Baritone to get stuck, take damage, or perform any action it wouldn't otherwise, everything
     * is rechecked once the real chunk is in range.
     * <p>
     * Having a robust cache greatly improves long distance pathfinding, as it's able to go around large scale obstacles
     * before they're in render distance. In fact, when the chunkCaching setting is disabled and Baritone starts anew
     * every time, or when you enter a completely new and very complicated area, it backtracks far more often because it
     * has to build up that cache from scratch. But after it's gone through an area just once, the next time will have zero
     * backtracking, since the entire area is now known and cached.
     */
    public final Setting<Long> cachedChunksExpirySeconds = new Setting<>(-1L);

    /**
     * The function that is called when Baritone will log to chat. This function can be added to
     * via {@link Consumer#andThen(Consumer)} or it can completely be overriden via setting
     * {@link Setting#value};
     */
    @JavaOnly
    public final Setting<Consumer<Component>> logger = new Setting<>((msg) -> {
        try {
            final GuiMessageTag tag = useMessageTag.value ? Helper.MESSAGE_TAG : null;
            Minecraft.getInstance().gui.hud.getChat().addPlayerMessage(msg, null, tag);
        } catch (Throwable t) {
            LOGGER.warn("Failed to log message to chat: " + msg.getString(), t);
        }
    });

    /**
     * The function that is called when Baritone will send a desktop notification. This function can be added to
     * via {@link Consumer#andThen(Consumer)} or it can completely be overriden via setting
     * {@link Setting#value};
     */
    @JavaOnly
    public final Setting<BiConsumer<String, Boolean>> notifier = new Setting<>(NotificationHelper::notify);

    /**
     * The function that is called when Baritone will show a toast. This function can be added to
     * via {@link Consumer#andThen(Consumer)} or it can completely be overriden via setting
     * {@link Setting#value};
     */
    @JavaOnly
    public final Setting<BiConsumer<Component, Component>> toaster = new Setting<>(BaritoneToast::addOrUpdate);

    /**
     * Print out ALL command exceptions as a stack trace to stdout, even simple syntax errors
     */
    public final Setting<Boolean> verboseCommandExceptions = new Setting<>(false);

    /**
     * The size of the box that is rendered when the current goal is a GoalYLevel
     */
    public final Setting<Double> yLevelBoxSize = new Setting<>(15D);

    /**
     * The color of the current path
     */
    public final Setting<Color> colorCurrentPath = new Setting<>(new Color(0xD9182B));

    /**
     * The color of the next path
     */
    public final Setting<Color> colorNextPath = new Setting<>(new Color(0xC77DFF));

    /**
     * The color of the parts of a path that will be crossed by boat (see {@link #allowBoats}). Water that
     * will be swum or waded stays in the normal path color.
     */
    public final Setting<Color> colorBoatPath = new Setting<>(Color.GREEN);

    /**
     * The color of the blocks to break
     */
    public final Setting<Color> colorBlocksToBreak = new Setting<>(new Color(0xE0303C));

    /**
     * The color of the blocks to place
     */
    public final Setting<Color> colorBlocksToPlace = new Setting<>(new Color(0x5CFF9D));

    /**
     * The color of the blocks to walk into
     */
    public final Setting<Color> colorBlocksToWalkInto = new Setting<>(new Color(0xC77DFF));

    /**
     * The color of the best path so far
     */
    public final Setting<Color> colorBestPathSoFar = new Setting<>(new Color(0x4DA3FF));

    /**
     * The color of the path to the most recent considered node
     */
    public final Setting<Color> colorMostRecentConsidered = new Setting<>(new Color(0xA8D4FF));

    /**
     * The color of the goal box
     */
    public final Setting<Color> colorGoalBox = new Setting<>(new Color(0x5CFF9D));

    /**
     * The color of the goal box when it's inverted
     */
    public final Setting<Color> colorInvertedGoalBox = new Setting<>(new Color(0xE0303C));

    /**
     * The color of all selections
     */
    public final Setting<Color> colorSelection = new Setting<>(Color.CYAN);

    /**
     * The color of the selection pos 1
     */
    public final Setting<Color> colorSelectionPos1 = new Setting<>(Color.BLACK);

    /**
     * The color of the selection pos 2
     */
    public final Setting<Color> colorSelectionPos2 = new Setting<>(Color.ORANGE);

    /**
     * The opacity of the selection. 0 is completely transparent, 1 is completely opaque
     */
    public final Setting<Float> selectionOpacity = new Setting<>(.5f);

    /**
     * Line width of the goal when rendered, in pixels
     */
    public final Setting<Float> selectionLineWidth = new Setting<>(2F);

    /**
     * Render selections
     */
    public final Setting<Boolean> renderSelection = new Setting<>(true);

    /**
     * Ignore depth when rendering selections
     */
    public final Setting<Boolean> renderSelectionIgnoreDepth = new Setting<>(true);

    /**
     * Render selection corners
     */
    public final Setting<Boolean> renderSelectionCorners = new Setting<>(true);

    /**
     * Use sword to mine. Off by default: a sword loses two durability per block it breaks, a pickaxe, axe or shovel loses one.
     * Swords still get picked for blocks only they can break, like cobwebs, when this is on.
     */
    public final Setting<Boolean> useSwordToMine = new Setting<>(false);

    /**
     * Wear tools out from the worst to the best. Among the tools that are the right kind and tier for a block, use the
     * one made of the cheapest material first (stone before copper before iron before diamond before netherite, wood and gold
     * last) and the one with the least durability left within a material, so a spare stone pickaxe dies before the iron one is touched.
     * <p>
     * Off means the fastest right tool wins, as before.
     */
    public final Setting<Boolean> toolWearChain = new Setting<>(true);

    /**
     * Desktop notifications
     */
    public final Setting<Boolean> desktopNotifications = new Setting<>(false);

    /**
     * Desktop notification on path complete
     */
    public final Setting<Boolean> notificationOnPathComplete = new Setting<>(true);

    /**
     * Desktop notification on farm fail
     */
    public final Setting<Boolean> notificationOnFarmFail = new Setting<>(true);

    /**
     * Desktop notification on build finished
     */
    public final Setting<Boolean> notificationOnBuildFinished = new Setting<>(true);

    /**
     * Desktop notification on explore finished
     */
    public final Setting<Boolean> notificationOnExploreFinished = new Setting<>(true);

    /**
     * Desktop notification on mine fail
     */
    public final Setting<Boolean> notificationOnMineFail = new Setting<>(true);

    /**
     * The number of ticks of elytra movement to simulate while firework boost is not active. Higher values are
     * computationally more expensive.
     */
    public final Setting<Integer> elytraSimulationTicks = new Setting<>(20);

    /**
     * The maximum allowed deviation in pitch from a direct line-of-sight to the flight target. Higher values are
     * computationally more expensive.
     */
    public final Setting<Integer> elytraPitchRange = new Setting<>(25);

    /**
     * The minimum speed that the player can drop to (in blocks/tick) before a firework is automatically deployed.
     */
    public final Setting<Double> elytraFireworkSpeed = new Setting<>(1.2);

    /**
     * The delay after the player's position is set-back by the server that a firework may be automatically deployed.
     * Value is in ticks.
     */
    public final Setting<Integer> elytraFireworkSetbackUseDelay = new Setting<>(15);

    /**
     * The minimum padding value that is added to the player's hitbox when considering which point to fly to on the
     * path. High values can result in points not being considered which are otherwise safe to fly to. Low values can
     * result in flight paths which are extremely tight, and there's the possibility of crashing due to getting too low
     * to the ground.
     */
    public final Setting<Double> elytraMinimumAvoidance = new Setting<>(0.2);

    /**
     * If enabled, avoids using fireworks when descending along the flight path.
     */
    public final Setting<Boolean> elytraConserveFireworks = new Setting<>(false);

    /**
     * Renders the raytraces that are performed by the elytra fly calculation.
     */
    public final Setting<Boolean> elytraRenderRaytraces = new Setting<>(false);

    /**
     * Renders the raytraces that are used in the hitbox part of the elytra fly calculation.
     * Requires {@link #elytraRenderRaytraces}.
     */
    public final Setting<Boolean> elytraRenderHitboxRaytraces = new Setting<>(false);

    /**
     * Renders the best elytra flight path that was simulated each tick.
     */
    public final Setting<Boolean> elytraRenderSimulation = new Setting<>(true);

    /**
     * Automatically path to and jump off of ledges to initiate elytra flight when grounded.
     */
    public final Setting<Boolean> elytraAutoJump = new Setting<>(false);

    /**
     * The seed used to generate chunks for long distance elytra path-finding in the nether.
     * Defaults to 2b2t's nether seed.
     */
    public final Setting<Long> elytraNetherSeed = new Setting<>(146008555100680L);

    /**
     * Whether nether-pathfinder should generate terrain based on {@link #elytraNetherSeed}.
     * If false all chunks that haven't been loaded are assumed to be air.
     */
    public final Setting<Boolean> elytraPredictTerrain = new Setting<>(false);

    /**
     * Automatically swap the current elytra with a new one when the durability gets too low
     */
    public final Setting<Boolean> elytraAutoSwap = new Setting<>(true);

    /**
     * The minimum durability an elytra can have before being swapped
     */
    public final Setting<Integer> elytraMinimumDurability = new Setting<>(5);

    /**
     * The minimum fireworks before landing early for safety
     */
    public final Setting<Integer> elytraMinFireworksBeforeLanding = new Setting<>(5);

    /**
     * Automatically land when elytra is almost out of durability, or almost out of fireworks
     */
    public final Setting<Boolean> elytraAllowEmergencyLand = new Setting<>(true);

    /**
     * Time between culling far away chunks from the nether pathfinder chunk cache
     */
    public final Setting<Long> elytraTimeBetweenCacheCullSecs = new Setting<>(TimeUnit.MINUTES.toSeconds(3));

    /**
     * Maximum distance chunks can be before being culled from the nether pathfinder chunk cache
     */
    public final Setting<Integer> elytraCacheCullDistance = new Setting<>(5000);

    /**
     * Should elytra consider nether brick a valid landing block
     */
    public final Setting<Boolean> elytraAllowLandOnNetherFortress = new Setting<>(false);

    /**
     * Has the user read and understood the elytra terms and conditions
     */
    public final Setting<Boolean> elytraTermsAccepted = new Setting<>(false);

    /**
     * Verbose chat logging in elytra mode
     */
    public final Setting<Boolean> elytraChatSpam = new Setting<>(false);


    /**
     * Allow the pathfinder to attempt flight in tighter spaces, useful in caves but can be dangerous.
     */
    public final Setting<Boolean> elytraAllowTightSpaces = new Setting<>(false);

    /**
     * Allow the pathfinder to fly above y 128 in the nether.
     */
    public final Setting<Boolean> elytraAllowAboveRoof = new Setting<>(false);

    /**
     * Allow the pathfinder to access the baritone cache to improve pathing
     */
    public final Setting<Boolean> elytraUseCache = new Setting<>(true);

    /**
     * Allow the pathfinder to fly above the build limit in the overworld and end.
     */
    public final Setting<Boolean> elytraAllowAboveBuildLimit = new Setting<>(true);

    /**
     * Minimum distance in blocks of an elytra trip before the pathfinder will try to fly above build limit. (Minimum: 32). Requires {@link #elytraAllowAboveBuildLimit} to be enabled.
     */
    public final Setting<Integer> elytraLongDistanceThreshold = new Setting<>(500);

    /**
     * Sneak when magma blocks are under feet
     */
    public final Setting<Boolean> allowWalkOnMagmaBlocks = new Setting<>(false);

    /**
     * Count the bot's work as player activity for vanilla's AFK frame limiter. Since 1.21.2 the game drops to 30 fps
     * after a minute without keyboard or mouse input (and 10 fps after ten minutes) when the inactivity limit option
     * is on AFK, and the bot never touches the real keyboard or mouse, so the frame rate would tank while you watch it
     * work. With this on, any tick where baritone is pathing, a process is in control, or AltoClef is running a task
     * counts as input. Does nothing while the bot is idle, so the AFK limiter still kicks in then, and your options
     * are never changed. Takes effect right away.
     */
    public final Setting<Boolean> keepFpsWhileBotting = new Setting<>(true);

    /**
     * Whether the gui key opens the click gui. The {@code #gui} command always works.
     */
    public final Setting<Boolean> guiKeyEnabled = new Setting<>(true);

    /**
     * GLFW key code that opens the click gui. Defaults to right shift (344). Set to -1 to unbind so only {@code #gui}
     * opens it. The gui's own Settings tab has a key capture control for this.
     */
    public final Setting<Integer> guiKey = new Setting<>(344);

    /**
     * Use the dark appearance in the click gui. The sidebar's Light and Dark buttons save this preference.
     */
    public final Setting<Boolean> guiDarkMode = new Setting<>(true);

    /**
     * Animate click gui transitions and scrolling. Disable to reduce motion and make changes immediate.
     */
    public final Setting<Boolean> guiAnimations = new Setting<>(true);

    /**
     * Draw Soprano's own boot screen and title screen instead of the vanilla ones. Turn this off if another mod
     * restyles those screens, or to get the vanilla look back. The switch in the bottom right corner of the title
     * screen flips this right away, changed anywhere else the title screen switches the next time it opens.
     */
    public final Setting<Boolean> guiMenus = new Setting<>(true);

    /**
     * Play Soprano's own background music on the title screen and the other menus instead of the vanilla menu music.
     * The speaker button in the top right corner of the title screen turns this on and off. Only does anything while
     * {@link #guiMenus} is on, in game the vanilla music plays as usual.
     */
    public final Setting<Boolean> guiMenuMusic = new Setting<>(true);

    /**
     * Ask GitHub once per session, when the title screen first opens, whether a newer Soprano release exists for the
     * Minecraft version and release channel you are on, so the title screen can offer to update. Nothing is
     * downloaded unless you click update.
     * <p>
     * Turning this off stops the check, but an update that was already downloaded still gets finished.
     */
    public final Setting<Boolean> checkForUpdates = new Setting<>(true);

    // everything below belongs to the built in AltoClef, see adris.altoclef. all of it is prefixed alto so that
    // #set alto<tab> finds it (same trick as the elytra settings). it only does anything while AltoClef has a task

    /**
     * Keep AltoClef's survival chains (eating, mob defense, bucket saves...) running even when no task was started,
     * and run {@link #altoIdleCommand} when it has nothing to do. With this off AltoClef does nothing at all until you
     * start a task, and goes back to doing nothing when the task ends. Takes effect right away.
     */
    public final Setting<Boolean> altoRunsWhenIdle = new Setting<>(false);

    /**
     * Let other players whisper AltoClef commands to your player (<code>/msg yourbot get cobblestone 4</code>). Only
     * players in the whitelist file baritone/altoclef/altoclef_butler_whitelist.txt (one name per line, case does not
     * matter) may, and an empty or missing whitelist means nobody can, so this does nothing until you add someone with
     * the whitelist command. The blacklist file next to it is applied on top and wins. Whispers can only run
     * AltoClef's commands, not Baritone's own, and never punk, gamma or whitelist. A whispered come walks to the player
     * who sent it. Strangers get no answer unless replyToUnauthorized is on in butler.json. On by default.
     * <p>
     * Whisper parsing is set up in baritone/altoclef/configs/butler.json.
     */
    public final Setting<Boolean> altoButler = new Setting<>(true);

    /**
     * Show the task list of AltoClef in the top left corner while it is running.
     */
    public final Setting<Boolean> altoShowTaskChains = new Setting<>(true);

    /**
     * Size of the task list on top of the game's gui scale. 1 is the normal size, 0.75 is a nice small one. Values
     * outside 0.5 to 2 are clamped. Needs {@link #altoShowTaskChains}.
     */
    public final Setting<Float> altoHudScale = new Setting<>(1.0f);

    /**
     * Show how long the current task has been running in the task list. Needs {@link #altoShowTaskChains}.
     */
    public final Setting<Boolean> altoShowTimer = new Setting<>(false);

    /**
     * Show the task list the way the developers see it: class names, item id lists and the raw task states, the same
     * text that goes into the log. Off, the list is written in plain words. Turn it on when you are putting together
     * a bug report. Needs {@link #altoShowTaskChains}.
     */
    public final Setting<Boolean> altoHudDetailed = new Setting<>(false);

    /**
     * The #gamer card on the right of the screen while a run is going: the phase and its clock against the budget,
     * what the bot is doing, the kit it is collecting with the game's own item icons, the furnace batches, and hp and
     * food. Sized by {@link #altoHudScale} like the task list. The task list keeps the phase as its one line.
     */
    public final Setting<Boolean> altoGamerHud = new Setting<>(true);

    /**
     * Hide all of AltoClef's warning logs. Not recommended, it makes debugging harder, but if you know what you are
     * doing go nuts.
     */
    public final Setting<Boolean> altoHideAllWarningLogs = new Setting<>(false);

    /**
     * The delay in seconds between moving items for crafting, furnaces and any other kind of inventory movement.
     */
    public final Setting<Float> altoContainerItemMoveDelay = new Setting<>(0.2f);

    /**
     * If a dropped resource item is further than this from the player, don't pick it up. Less than 0 disables the
     * limit.
     */
    public final Setting<Float> altoResourcePickupDropRange = new Setting<>(-1f);

    /**
     * Go after dropped items that are lying in water. Off by default: the bot ignores items that are in water unless
     * it can grab them from dry land (or from a puddle one block deep), because swimming down after a drop is a good
     * way to spend a whole run bobbing in a lake. Turn this on for the old behaviour. The watchdog that gives up on a
     * drop when the bot is in water and not getting any closer stays on either way.
     */
    public final Setting<Boolean> altoPickupItemsInWater = new Setting<>(false);

    /**
     * Minimum amount of food (in food points) to keep in the inventory. Below this the bot goes and gets more, up to
     * {@link #altoFoodUnitsToCollect}. 0 means it never goes looking for food on its own.
     */
    public final Setting<Integer> altoMinimumFoodAllowed = new Setting<>(0);

    /**
     * How much food (in food points) to collect when there is less than {@link #altoMinimumFoodAllowed} in the
     * inventory.
     */
    public final Setting<Integer> altoFoodUnitsToCollect = new Setting<>(0);

    /**
     * Chests are remembered along with what is in them. If the bot is collecting a resource and there is a chest with
     * it within this many blocks, it takes it from the chest. 0 disables chest pickups. Don't set this too high, the
     * bot will go for a chest even when the resource is lying right there.
     */
    public final Setting<Float> altoResourceChestLocateRange = new Setting<>(500f);

    /**
     * Some block resources are normally made rather than mined (a crafting table comes from planks), but if one is
     * found within this many blocks the bot may mine it instead. 0 disables that, -1 always mines a catalogued block
     * (not recommended: it will walk 10000 blocks to a crafting table it saw once, with a forest next to it).
     */
    public final Setting<Float> altoResourceMineRange = new Setting<>(100f);

    /**
     * When going to the nearest chest to store items the bot would dig up dungeons all day. With this on it searches
     * around each chest first to make sure it is not in one.
     */
    public final Setting<Boolean> altoAvoidSearchingDungeonChests = new Setting<>(true);

    /**
     * Ignore mining and interacting with blocks below an ocean (in an ocean biome and below y 64). AltoClef does not
     * know what to do with oceans.
     */
    public final Setting<Boolean> altoAvoidOceanBlocks = new Setting<>(true);

    /**
     * How close we must be to attack or interact with an entity. See {@link #blockReachDistance} for blocks.
     * <p>
     * Melee swings are aimed and never reach further than the player's own entity interaction range (3 for a normal
     * player), so for fighting this can only make the reach shorter. Trading, shearing and other interactions still use
     * it as is.
     */
    public final Setting<Float> altoEntityReachRange = new Setting<>(4f);

    /**
     * Before grabbing anything, get a pickaxe. Helps with navigation because dropped items are sometimes underground,
     * but it only makes sense in regular worlds.
     */
    public final Setting<Boolean> altoCollectPickaxeFirst = new Setting<>(true);

    /**
     * Run away from hostile mobs when health is low, from creepers that are about to blow up and from very dangerous
     * mobs like the warden, and use the force field ({@link #altoForceFieldStrategy}) to push mobs away.
     */
    public final Setting<Boolean> altoMobDefense = new Setting<>(true);

    /**
     * How the force field behaves while {@link #altoMobDefense} is on. It is there to push mobs away, not to kill
     * them.
     * <p>
     * FASTEST attacks every hostile at every possible moment, DELAY attacks the closest one when the attack is charged
     * up, SMART attacks the closest one at most every 0.2 seconds, OFF does nothing.
     */
    public final Setting<ForceFieldStrategy> altoForceFieldStrategy = new Setting<>(ForceFieldStrategy.SMART);

    /**
     * Dodge incoming projectiles. Needs {@link #altoMobDefense}.
     */
    public final Setting<Boolean> altoDodgeProjectiles = new Setting<>(true);

    /**
     * Skeletons and big groups of mobs are a pain. With this on the bot may fight or run from mobs that really need
     * dealing with, see {@link #altoCommitCombat} for what that means. Off, the bot never commits to a fight or a run,
     * mobs are scenery (the safety stays: fire, falls, the arrow shield and the force field swinging at what is hitting
     * it). Needs {@link #altoMobDefense}.
     */
    public final Setting<Boolean> altoKillOrAvoidAnnoyingHostiles = new Setting<>(true);

    /**
     * Mobs are ignored by default, in every dimension: the bot keeps walking its path past a crowd of zombies, a lit
     * creeper, or a skeleton shooting at it from across the field (walking spoils the aim). It only does something about
     * a mob that hit it while in contact (a fight), or real danger: 8 hp or less with an angry mob within 8 blocks, 10 hp
     * or less with a heavy hitter like a hoglin or vindicator that hit it or is in contact (one just
     * standing around is walked past), or the warden or the wither within 8 (or a sonic boom from up to 15) (a run). A fight
     * holds until its target is dead or has been more than 6 blocks away and quiet for three seconds, and turns into a
     * run on the danger rules. A run holds until the bot is 24 blocks from where it started with nothing hostile within
     * 12, or 25 seconds, whichever comes first. A run that gets nowhere (three seconds with a mob on top of the bot, six
     * with one within eight blocks) becomes a fight, and one that gets nowhere with nothing near is over after five. A few
     * things are left to the tasks that own them: ghasts, the dragon, blazes while the rod task is working them, a golem
     * being fought from its pillar. Off, the bot never commits to anything, which is the same as turning off
     * {@link #altoKillOrAvoidAnnoyingHostiles}. Needs {@link #altoKillOrAvoidAnnoyingHostiles}.
     */
    public final Setting<Boolean> altoCommitCombat = new Setting<>(true);

    /**
     * {@code #gamer} only, the IRON phase. One chooser decides what the bot is doing (a furnace trip, a station pickup, a
     * village chest, a coal detour, the climb out of the mine, the kit) and an activity that started holds until it is
     * done, instead of the side jobs and the furnace trips each cutting in on every tick. Turn it off to get the older
     * fixed order back for comparison. Every change of activity goes to the log as an {@code activity:} line.
     */
    public final Setting<Boolean> altoIronArbiter = new Setting<>(true);

    /**
     * {@code #gamer} only, the Nether pearl hunt. On, the hunt digs a nook into a wall and waits for endermen to come to it. Off (the default),
     * it goes straight for the endermen and fights each one in the open. The nook tends to end in a standoff with the enderman
     * out of reach, so it is off until that is sorted out.
     */
    public final Setting<Boolean> altoPearlNook = new Setting<>(false);

    /**
     * {@code #gamer} and survival schematic builds. When night falls and the bot has a bed to use (one in the bag, the wool and planks to craft one, or
     * one standing close by) it sleeps through the night instead of working on and fighting mobs until morning. Minecraft
     * refuses to let anyone sleep with monsters near the bed, so when there are some the bot does not fight them: it
     * pillars up out of their range, puts the bed on top and sleeps there. A bed the bot placed itself is picked up again
     * afterwards. Never used in the Nether or the End.
     * <p>
     * It tries to have the bed ready before dusk: in the last couple of minutes of the day it crafts one from wool and
     * planks it already holds, and in the last four minutes, with sheep close by and no wool, it hunts three wool of one
     * colour first (150 seconds at most, then it leaves it for the evening). With no bed and no wool it works the night as
     * before.
     * <p>
     * A schematic build stops the builder for the night (and for the evening's wool or craft), then walks back and counts
     * again in the morning before building on. The pillar only uses blocks held above what the rest of the build needs.
     */
    public final Setting<Boolean> altoSleepAtNight = new Setting<>(true);

    /**
     * Avoid going underwater when pathing is not giving the bot movement instructions. Turn it off if you want the bot
     * to be able to sink.
     */
    public final Setting<Boolean> altoAvoidDrowning = new Setting<>(true);

    /**
     * Close the open screen (furnace, crafting table, chest...) when the look direction changes or the bot is mining
     * something. Stops the bot from getting stuck in a container screen.
     */
    public final Setting<Boolean> altoAutoCloseScreenWhenLookingOrMining = new Setting<>(true);

    /**
     * Put ourselves out with water when we are on fire (and not immune to it).
     */
    public final Setting<Boolean> altoExtinguishSelfWithWater = new Setting<>(true);

    /**
     * Eat when hungry or in danger.
     */
    public final Setting<Boolean> altoAutoEat = new Setting<>(true);

    /**
     * Do a no fall bucket (MLG) when knocked off course and falling.
     */
    public final Setting<Boolean> altoAutoMLGBucket = new Setting<>(true);

    /**
     * Reconnect to the last server automatically when disconnected. Off, and the bot stops running when you get
     * disconnected.
     */
    public final Setting<Boolean> altoAutoReconnect = new Setting<>(true);

    /**
     * Respawn right away when you die. Off, and the bot stops running when you die.
     */
    public final Setting<Boolean> altoAutoRespawn = new Setting<>(true);

    /**
     * What to do when it needs the nether but there is no portal in sight. BUILD_PORTAL_VANILLA builds one,
     * GO_TO_HOME_BASE walks to {@link #altoHomeBasePosition} and assumes there is a portal there.
     */
    public final Setting<OverworldToNetherBehaviour> altoOverworldToNetherBehaviour = new Setting<>(OverworldToNetherBehaviour.BUILD_PORTAL_VANILLA);

    /**
     * When fast traveling through the nether, walk to the destination if we somehow end up closer than this many blocks
     * in the overworld. Normal travel gets well within this (to about 100 blocks), so keep it decently large.
     */
    public final Setting<Integer> altoNetherFastTravelWalkingRange = new Setting<>(600);

    /**
     * A command to run when nothing else is going on, for example {@code idle} to keep surviving, {@code follow
     * <your name>} or {@code goto <home base coordinates>}. Only used with {@link #altoRunsWhenIdle}. Empty does
     * nothing.
     */
    public final Setting<String> altoIdleCommand = new Setting<>("");

    /**
     * What to do after dying, once we are back. {@code {deathmessage}} is replaced by the death message. Starting with
     * {@code #} (or the prefix) it is a command, starting with {@code /} it goes to the server as a command, anything
     * else is sent as chat. Several can be chained with {@code " & "}, for example
     * {@code /home & i died with message: {deathmessage} & #get diamond}. Empty does nothing.
     */
    public final Setting<String> altoDeathCommand = new Setting<>("");

    /**
     * If we need to throw something away, throw these first. It is also what the bot may place as building blocks, and
     * it is added to {@link #acceptableThrowawayItems} while a task runs.
     */
    public final Setting<List<Item>> altoThrowawayItems = new Setting<>(List.of(
            // overworld junk
            Items.DRIPSTONE_BLOCK, Items.ROOTED_DIRT, Items.GRAVEL, Items.SAND, Items.DIORITE, Items.ANDESITE,
            Items.GRANITE, Items.TUFF, Items.COBBLESTONE, Items.DIRT, Items.COBBLED_DEEPSLATE,
            Items.ACACIA_LEAVES, Items.BIRCH_LEAVES, Items.DARK_OAK_LEAVES, Items.OAK_LEAVES, Items.JUNGLE_LEAVES, Items.SPRUCE_LEAVES,
            // nether junk, mostly tuned for the beat the game task
            Items.NETHERRACK, Items.MAGMA_BLOCK, Items.SOUL_SOIL, Items.SOUL_SAND, Items.NETHER_BRICKS, Items.NETHER_BRICK,
            Items.BASALT, Items.BLACKSTONE, Items.END_STONE, Items.SANDSTONE, Items.STONE_BRICKS
    ));

    /**
     * How many throwaway blocks to keep around as building blocks.
     */
    public final Setting<Integer> altoReservedBuildingBlockCount = new Setting<>(64);

    /**
     * Never throw away items that have a custom name.
     */
    public final Setting<Boolean> altoDontThrowAwayCustomNameItems = new Setting<>(true);

    /**
     * Never throw away enchanted items.
     */
    public final Setting<Boolean> altoDontThrowAwayEnchantedItems = new Setting<>(true);

    /**
     * If we need to throw something away and have no {@link #altoThrowawayItems}, throw away any item that is not
     * needed by the current task. Careful: with this on anything not in {@link #altoImportantItems} can go.
     */
    public final Setting<Boolean> altoThrowAwayUnusedItems = new Setting<>(true);

    /**
     * How much food to hold on to when making room. Counted in hunger points times how many you carry, with raw meat at its
     * cooked value, so a stack of 8 steak is 64. Food is only ever thrown away when the rest of the bag still covers this,
     * and then only the smallest stack that is pure surplus, after everything else that can go. Under it, food is never
     * thrown away to make room. 0 turns this off. The gamer raises it while it runs.
     */
    public final Setting<Integer> altoFoodKeepUnits = new Setting<>(40);

    /**
     * Items that are never thrown away, even when {@link #altoThrowAwayUnusedItems} is on and the task does not use
     * them.
     */
    public final Setting<List<Item>> altoImportantItems = new Setting<>(List.of(
            Items.TOTEM_OF_UNDYING, Items.ENCHANTED_GOLDEN_APPLE, Items.ENDER_EYE, Items.TRIDENT, Items.DIAMOND,
            Items.DIAMOND_BLOCK, Items.NETHERITE_SCRAP, Items.NETHERITE_INGOT, Items.NETHERITE_BLOCK,
            Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_HELMET, Items.DIAMOND_BOOTS,
            Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_HELMET, Items.NETHERITE_BOOTS,
            Items.DIAMOND_PICKAXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.DIAMOND_HOE,
            Items.NETHERITE_PICKAXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_SWORD, Items.NETHERITE_AXE, Items.NETHERITE_HOE,
            // losing a shulker box with its stuff in it would be pretty bad lol (the undyed one is missing, it always was)
            // (26.3 keeps the dyed ones in a ColorCollection instead of sixteen fields)
            Items.DYED_SHULKER_BOX.white(), Items.DYED_SHULKER_BOX.black(), Items.DYED_SHULKER_BOX.blue(),
            Items.DYED_SHULKER_BOX.brown(), Items.DYED_SHULKER_BOX.cyan(), Items.DYED_SHULKER_BOX.gray(), Items.DYED_SHULKER_BOX.green(),
            Items.DYED_SHULKER_BOX.lightBlue(), Items.DYED_SHULKER_BOX.lightGray(), Items.DYED_SHULKER_BOX.lime(),
            Items.DYED_SHULKER_BOX.magenta(), Items.DYED_SHULKER_BOX.orange(), Items.DYED_SHULKER_BOX.pink(), Items.DYED_SHULKER_BOX.purple(),
            Items.DYED_SHULKER_BOX.red(), Items.DYED_SHULKER_BOX.yellow()
    ));

    /**
     * Craft or place a blast furnace of our own for smelting ores when that is worth it (five or more to smelt, plus the
     * five iron and the smooth stone it costs). This is only about making one: a blast furnace that is already standing
     * nearby is {@link #altoUseNearbyBlastFurnace}'s business, and that works with this off. #gamer turns this off for
     * the run because the five iron is better spent on armor.
     */
    public final Setting<Boolean> altoUseBlastFurnace = new Setting<>(true);

    /**
     * Smelt raw iron and gold in a blast furnace that is already standing nearby (a village armorer has one) instead of
     * a plain furnace, since a blast furnace smelts ores twice as fast. It never crafts or places one, that is
     * {@link #altoUseBlastFurnace}. Nearby means within {@link #altoNearbyBlastFurnaceRange} blocks of us when we start
     * smelting a batch, and it sticks with whatever it picked until that batch is done.
     */
    public final Setting<Boolean> altoUseNearbyBlastFurnace = new Setting<>(true);

    /**
     * How far away, in blocks, a blast furnace can be and still count as nearby for
     * {@link #altoUseNearbyBlastFurnace}.
     */
    public final Setting<Double> altoNearbyBlastFurnaceRange = new Setting<>(48.0);

    /**
     * When smelting iron ingots, put everything in the furnace, close it and finish instead of standing there for the
     * whole cook. The smelt is then recorded as a furnace job that something else has to come back for, so this is off
     * for a plain {@code @get iron_ingot} (nobody would collect) and #gamer turns it on only while its iron phase can.
     */
    public final Setting<Boolean> altoAsyncSmelting = new Setting<>(false);

    /**
     * Same as {@link #altoAsyncSmelting} but for cooking food in a smoker or furnace. Only does anything while
     * {@link #altoAsyncSmelting} is on as well (#gamer turns that on for the phases that can come back for the food), so
     * this is the switch for "iron cooks in the background but I want the bot to stand there for the meat".
     */
    public final Setting<Boolean> altoAsyncCooking = new Setting<>(true);

    /**
     * #gamer's iron phase makes its iron pickaxe, iron chestplate, shield and shears (whichever the kit still owes, 14 raw
     * iron for all four) as soon as it holds the ore for them, instead of mining the whole kit's worth first with a stone
     * pick and no armor: that ore is smelted where the bot stands, it keeps mining nearby while it cooks, then comes back
     * and crafts them. it fires again if a death loses any of them. needs {@link #altoAsyncSmelting} for the "mine while it
     * cooks" part, without it the ore is smelted standing at the furnace and the crafts come right after.
     */
    public final Setting<Boolean> altoEarlyIronPick = new Setting<>(true);

    /**
     * Only use the items in {@link #altoSupportedFuels} as smelting fuel. Careful with turning this off: every burnable
     * item that is not protected (blaze rods, beds, wooden tools, crafting tables...) can get burned.
     */
    public final Setting<Boolean> altoLimitFuelsToSupportedFuels = new Setting<>(true);

    /**
     * The only things used as smelting fuel while {@link #altoLimitFuelsToSupportedFuels} is on.
     */
    public final Setting<List<Item>> altoSupportedFuels = new Setting<>(List.of(Items.COAL, Items.CHARCOAL));

    /**
     * Where the "home base" is. Some tasks (like {@link #altoOverworldToNetherBehaviour}) use it when told to, don't
     * bother with it unless you need it.
     */
    public final Setting<BlockPos> altoHomeBasePosition = new Setting<>(new BlockPos(0, 64, 0));

    /**
     * Areas AltoClef will not break or place blocks in, for spawn protection or against griefing. Each one is
     * {@code x1/y1/z1->x2/y2/z2} (corners in any order, both inclusive) with {@code @nether} or {@code @end} on the end
     * for other dimensions, and they are separated by commas, for example
     * {@code -10/0/-10->10/255/10,1000/50/2000->1200/255/2100@nether}.
     */
    public final Setting<List<BlockRange>> altoAreasToProtect = new Setting<>(List.of());

    /**
     * Build the #gamer nether portal in a one deep lava pool the way the speedrun does it, with water and a few cobble
     * blocks, instead of casting obsidian one block at a time. Needs a four long lava row with shore right behind it and
     * more lava to scoop from. When it cannot find one, or anything goes wrong with it, the gamer falls back to the old
     * per block cast, so this only ever makes the portal faster. Turn it off to always cast.
     */
    public final Setting<Boolean> altoLavaPoolPortal = new Setting<>(true);

    /**
     * Jump for critical hits in melee. When a mob is in reach and the weapon is a few ticks from full strength the bot
     * hops in place, drops sprint, and swings once it is coming down, which is a crit for 1.5x damage. It never hops
     * with a shield up (kiting and standing your ground), while eating, in water or lava, on a ladder, with a ceiling
     * right over its head, next to a ledge or lava, while baritone is walking a path, or when a plain hit already kills.
     * Off means every swing is a plain one the moment the cooldown is full.
     */
    public final Setting<Boolean> altoJumpCrits = new Setting<>(true);

    /**
     * A map of lowercase setting field names to their respective setting
     */
    public final Map<String, Setting<?>> byLowerName;

    /**
     * A list of all settings
     */
    public final List<Setting<?>> allSettings;

    // old lowercase name -> the lowercase name it became. only findByLowerName reads this, so the old names stay out of
    // byLowerName and allSettings, which means tab complete, #set list and the settings file never show them
    private static final Map<String, String> RENAMED = Map.of(
            "experimentalmovement", "fastmode",
            "experimentalminhealth", "fastmodeminhealth"
    );

    /**
     * Looks a setting up by its lowercase name. Unlike {@link #byLowerName} this also accepts the old name of a setting
     * that was renamed, so settings files and commands from before the rename keep working.
     *
     * @param lowerName the lowercase name of a setting, current or old
     * @return the setting, or {@code null} if there is no setting by that name
     */
    public Setting<?> findByLowerName(String lowerName) {
        Setting<?> setting = byLowerName.get(lowerName);
        if (setting == null) {
            String renamed = RENAMED.get(lowerName);
            setting = renamed == null ? null : byLowerName.get(renamed);
        }
        return setting;
    }

    public final Map<Setting<?>, Type> settingTypes;

    public final class Setting<T> {

        public T value;
        public final T defaultValue;
        private String name;
        private boolean javaOnly;

        @SuppressWarnings("unchecked")
        private Setting(T value) {
            if (value == null) {
                throw new IllegalArgumentException("Cannot determine value type class from null");
            }
            this.value = value;
            this.defaultValue = value;
            this.javaOnly = false;
        }

        /**
         * Deprecated! Please use .value directly instead
         *
         * @return the current setting value
         */
        @Deprecated
        public final T get() {
            return value;
        }

        public final String getName() {
            return name;
        }

        public Class<T> getValueClass() {
            // noinspection unchecked
            return (Class<T>) TypeUtils.resolveBaseClass(getType());
        }

        @Override
        public String toString() {
            return SettingsUtil.settingToString(this);
        }

        /**
         * Reset this setting to its default value
         */
        public void reset() {
            value = defaultValue;
        }

        public final Type getType() {
            return settingTypes.get(this);
        }

        /**
         * This should always be the same as whether the setting can be parsed from or serialized to a string; in other
         * words, the only way to modify it is by writing to {@link #value} programatically.
         *
         * @return {@code true} if the setting can not be set or read by the user
         */
        public boolean isJavaOnly() {
            return javaOnly;
        }
    }

    /**
     * Marks a {@link Setting} field as being {@link Setting#isJavaOnly() Java-only}
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    private @interface JavaOnly {}

    // here be dragons

    Settings() {
        Field[] temp = getClass().getFields();

        Map<String, Setting<?>> tmpByName = new HashMap<>();
        List<Setting<?>> tmpAll = new ArrayList<>();
        Map<Setting<?>, Type> tmpSettingTypes = new HashMap<>();

        try {
            for (Field field : temp) {
                if (field.getType().equals(Setting.class)) {
                    Setting<?> setting = (Setting<?>) field.get(this);
                    String name = field.getName();
                    setting.name = name;
                    setting.javaOnly = field.isAnnotationPresent(JavaOnly.class);
                    // not the default locale, turkish makes a dotless i out of the I in buildIgnoreBlocks and nothing finds it
                    name = name.toLowerCase(Locale.ROOT);
                    if (tmpByName.containsKey(name)) {
                        throw new IllegalStateException("Duplicate setting name");
                    }
                    tmpByName.put(name, setting);
                    tmpAll.add(setting);
                    tmpSettingTypes.put(setting, ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0]);
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        byLowerName = Collections.unmodifiableMap(tmpByName);
        allSettings = Collections.unmodifiableList(tmpAll);
        settingTypes = Collections.unmodifiableMap(tmpSettingTypes);
    }

    @SuppressWarnings("unchecked")
    public <T> List<Setting<T>> getAllValuesByType(Class<T> cla$$) {
        List<Setting<T>> result = new ArrayList<>();
        for (Setting<?> setting : allSettings) {
            if (setting.getValueClass().equals(cla$$)) {
                result.add((Setting<T>) setting);
            }
        }
        return result;
    }
}
