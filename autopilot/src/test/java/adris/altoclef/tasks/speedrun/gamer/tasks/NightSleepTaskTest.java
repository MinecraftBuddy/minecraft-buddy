package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the bits of the bed placement that do not need a world: what has to be cleared, where we have to stand, what chat says
public class NightSleepTaskTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static boolean inTheWay(BlockState s) {
        return NightSleepTask.inTheWay(s, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    // anything the click's ray can stop on comes out, collision or not
    @Test
    public void plantsAndThinStuffGetCleared() {
        assertTrue(inTheWay(Blocks.SHORT_GRASS.defaultBlockState()));
        assertTrue(inTheWay(Blocks.TALL_GRASS.defaultBlockState()));
        assertTrue(inTheWay(Blocks.POPPY.defaultBlockState()));
        assertTrue(inTheWay(Blocks.MOSS_CARPET.defaultBlockState()));
        // one snow layer has no collision at all, it still has an outline
        assertTrue(Blocks.SNOW.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty());
        assertTrue(inTheWay(Blocks.SNOW.defaultBlockState()));
        assertTrue(inTheWay(Blocks.STONE.defaultBlockState()));
    }

    @Test
    public void airAndWaterAreLeftAlone() {
        assertFalse(inTheWay(Blocks.AIR.defaultBlockState()));
        assertFalse(inTheWay(Blocks.WATER.defaultBlockState()));
        assertFalse(inTheWay(Blocks.LAVA.defaultBlockState()));
    }

    // the stand cell only loses what we cannot be standing on
    @Test
    public void onlyPlantsComeOutOfTheStandCell() {
        BlockPos at = BlockPos.ZERO;
        assertTrue(NightSleepTask.plantInTheWay(Blocks.SHORT_GRASS.defaultBlockState(), EmptyBlockGetter.INSTANCE, at));
        assertTrue(NightSleepTask.plantInTheWay(Blocks.TALL_GRASS.defaultBlockState(), EmptyBlockGetter.INSTANCE, at));
        assertTrue(NightSleepTask.plantInTheWay(Blocks.COBWEB.defaultBlockState(), EmptyBlockGetter.INSTANCE, at));
        assertTrue(NightSleepTask.plantInTheWay(Blocks.SNOW.defaultBlockState(), EmptyBlockGetter.INSTANCE, at));
        assertFalse(NightSleepTask.plantInTheWay(Blocks.MOSS_CARPET.defaultBlockState(), EmptyBlockGetter.INSTANCE, at));
        assertFalse(NightSleepTask.plantInTheWay(Blocks.STONE_SLAB.defaultBlockState(), EmptyBlockGetter.INSTANCE, at));
        assertFalse(NightSleepTask.plantInTheWay(Blocks.STONE.defaultBlockState(), EmptyBlockGetter.INSTANCE, at));
        assertFalse(NightSleepTask.plantInTheWay(Blocks.WATER.defaultBlockState(), EmptyBlockGetter.INSTANCE, at));
        assertFalse(NightSleepTask.plantInTheWay(Blocks.AIR.defaultBlockState(), EmptyBlockGetter.INSTANCE, at));
    }

    @Test
    public void theClickOnlyHappensFromTheStandCell() {
        assertEquals(NightSleepTask.Spot.WALK, NightSleepTask.spot(false, false, true));
        assertEquals(NightSleepTask.Spot.WALK, NightSleepTask.spot(false, true, false));
        assertEquals(NightSleepTask.Spot.STEP_BACK, NightSleepTask.spot(true, true, true));
        assertEquals(NightSleepTask.Spot.STEP_BACK, NightSleepTask.spot(true, false, false));
        assertEquals(NightSleepTask.Spot.CLICK, NightSleepTask.spot(true, false, true));
    }

    // anywhere in the stand cell without a toe over the foot cell (0.3 is half a player), aim at the middle of the support top
    // one block over: the bucket is always facing. with the toe over, it can tie, which is why that is a step back
    @Test
    public void fromTheStandCellTheHeadPointsTheRightWay() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (int i = 0; i < 100; i++) {
                for (int j = 0; j < 100; j++) {
                    double x = i / 100.0;
                    double z = j / 100.0;
                    // how far the middle of us is from the face the foot cell is on
                    double along = facing.getStepX() != 0 ? (facing.getStepX() > 0 ? 1 - x : x) : (facing.getStepZ() > 0 ? 1 - z : z);
                    if (along < 0.3) {
                        continue;
                    }
                    double aimX = facing.getStepX() + 0.5;
                    double aimZ = facing.getStepZ() + 0.5;
                    assertEquals(facing, NightSleepTask.towards(aimX - x, aimZ - z));
                }
            }
        }
    }

    @Test
    public void towardsUsesTheGamesCompass() {
        assertEquals(Direction.EAST, NightSleepTask.towards(1, 0.2));
        assertEquals(Direction.WEST, NightSleepTask.towards(-1, -0.2));
        assertEquals(Direction.SOUTH, NightSleepTask.towards(0.2, 1));
        assertEquals(Direction.NORTH, NightSleepTask.towards(-0.2, -1));
    }

    @Test
    public void aRetryNeedsABedTheNightAndBudget() {
        assertTrue(NightSleepTask.canRetry(true, true, false));
        assertFalse(NightSleepTask.canRetry(false, true, false));
        assertFalse(NightSleepTask.canRetry(true, false, false));
        assertFalse(NightSleepTask.canRetry(true, true, true));
    }

    @Test
    public void chatOnlySaysNotTonightWhenItMeansIt() {
        assertEquals("Couldn't get the bed down, trying again in a bit", NightSleepTask.failLine(NightSleepTask.BED_WOULD_NOT_GO_DOWN, true));
        assertEquals("Not sleeping tonight: " + NightSleepTask.BED_WOULD_NOT_GO_DOWN, NightSleepTask.failLine(NightSleepTask.BED_WOULD_NOT_GO_DOWN, false));
        assertEquals("Couldn't sleep yet (monsters by the bed), trying again in a bit", NightSleepTask.failLine("monsters by the bed", true));
        assertEquals("Not sleeping tonight: out of time for tonight", NightSleepTask.failLine("out of time for tonight", false));
    }

    private static BlockState bed(BedPart part, Direction facing) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState().setValue(BedBlock.PART, part).setValue(BedBlock.FACING, facing);
    }

    private static List<BlockPos> cellsAround(BlockPos footCell) {
        return List.of(footCell, footCell.north(), footCell.south(), footCell.east(), footCell.west());
    }

    @Test
    public void eitherHalfWalksBackToTheFoot() {
        BlockPos at = new BlockPos(5, 64, 5);
        assertEquals(at, NightSleepTask.footOf(at, bed(BedPart.FOOT, Direction.WEST)));
        assertEquals(at.east(), NightSleepTask.footOf(at, bed(BedPart.HEAD, Direction.WEST)));
        assertNull(NightSleepTask.footOf(at, Blocks.STONE.defaultBlockState()));
    }

    // the bed landed one cell off the aimed foot cell, head pointing west: still found, still walked back to its foot
    @Test
    public void aBedOneCellOffIsStillOurs() {
        BlockPos aimed = new BlockPos(10, 66, 8);
        BlockPos landedFoot = aimed.south();
        Map<BlockPos, BlockState> world = new HashMap<>();
        world.put(landedFoot, bed(BedPart.FOOT, Direction.WEST));
        world.put(landedFoot.west(), bed(BedPart.HEAD, Direction.WEST));
        assertEquals(landedFoot, NightSleepTask.appearedFoot(cellsAround(aimed), Set.of(), p -> world.getOrDefault(p, Blocks.AIR.defaultBlockState())));
    }

    // a bed that stood there before the click is somebody's, a new one beside it is ours
    @Test
    public void aBedThatWasAlreadyThereIsNeverOurs() {
        BlockPos aimed = new BlockPos(10, 66, 8);
        Map<BlockPos, BlockState> world = new HashMap<>();
        world.put(aimed.north(), bed(BedPart.FOOT, Direction.NORTH));
        world.put(aimed.north().north(), bed(BedPart.HEAD, Direction.NORTH));
        Set<BlockPos> before = Set.of(aimed.north());
        assertNull(NightSleepTask.appearedFoot(cellsAround(aimed), before, p -> world.getOrDefault(p, Blocks.AIR.defaultBlockState())));
        world.put(aimed.east(), bed(BedPart.HEAD, Direction.EAST));
        world.put(aimed, bed(BedPart.FOOT, Direction.EAST));
        assertEquals(aimed, NightSleepTask.appearedFoot(cellsAround(aimed), before, p -> world.getOrDefault(p, Blocks.AIR.defaultBlockState())));
    }

    // ---- bed sites next to a portal

    private static final BlockPos FOOT = new BlockPos(10, 64, 10);
    private static final BlockPos HEAD = new BlockPos(11, 64, 10);

    private static boolean portalSite(Map<BlockPos, net.minecraft.world.level.block.Block> world) {
        return NightSleepTask.portalSite(FOOT, HEAD, p -> world.getOrDefault(p, Blocks.AIR));
    }

    @Test
    public void openGroundIsNotAPortalSite() {
        assertFalse(portalSite(new HashMap<>()));
        assertFalse(portalSite(Map.of(FOOT.below(), Blocks.STONE, HEAD.below(), Blocks.STONE)));
    }

    @Test
    public void portalAndObsidianCellsOfTheBedAreNoSite() {
        assertTrue(portalSite(Map.of(FOOT, Blocks.NETHER_PORTAL)));
        assertTrue(portalSite(Map.of(HEAD.above(), Blocks.NETHER_PORTAL)));
        // the frame is never dug up for a bed, and never stood a bed on either
        assertTrue(portalSite(Map.of(HEAD, Blocks.OBSIDIAN)));
        assertTrue(portalSite(Map.of(FOOT.below(), Blocks.OBSIDIAN)));
    }

    @Test
    public void aPortalTouchingTheSiteIsNoSite() {
        assertTrue(portalSite(Map.of(FOOT.west(), Blocks.NETHER_PORTAL)));
        assertTrue(portalSite(Map.of(HEAD.east().above(), Blocks.NETHER_PORTAL)));
        assertTrue(portalSite(Map.of(HEAD.below().south(), Blocks.NETHER_PORTAL)));
        // two cells off is far enough
        assertFalse(portalSite(Map.of(FOOT.west(2), Blocks.NETHER_PORTAL)));
        // obsidian only counts when the bed itself would use it
        assertFalse(portalSite(Map.of(FOOT.west(), Blocks.OBSIDIAN)));
    }
}
