package baritone.process;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.altoclef.AltoClefSettings;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.schematic.StaticSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// altoclef's portal frame goes in a cell at a time, a one block build each. the confirm for one cell shows up after the
// next cell's build owns the ledger, right next to the new box, and used to get booked as its scaffold. no game here,
// same unconstructed builder trick as BuilderPlacedBuildTest
public class BuilderLedgerStampTest {

    private static final BlockPos K = new BlockPos(0, 64, 0);
    // a method and not a constant: Blocks has to wait for ItemFixture.boot, or the whole registry goes down with it
    private static BlockState obsidian() {
        return Blocks.OBSIDIAN.defaultBlockState();
    }

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @After
    public void forget() {
        AltoClefSettings.getInstance().resetAll();
    }

    private static BuilderProcess builder() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (BuilderProcess) ((sun.misc.Unsafe) f.get(null)).allocateInstance(BuilderProcess.class);
    }

    private static ScaffoldLedger ledger(BuilderProcess b) throws Exception {
        Field f = BuilderProcess.class.getDeclaredField("ledger");
        f.setAccessible(true);
        return (ScaffoldLedger) f.get(b);
    }

    private static StaticSchematic oneObsidian() {
        return new StaticSchematic(new BlockState[][][]{{{obsidian()}}});
    }

    private static long cell(BlockPos p) {
        return BetterBlockPos.serializeToLong(p.getX(), p.getY(), p.getZ());
    }

    @Test
    public void aLateConfirmFromTheLastCellIsntTheNextCellsScaffold() throws Exception {
        BuilderProcess b = builder();
        b.build("portal", oneObsidian(), K);
        int stamp = b.ledgerGeneration();
        // the click for K went out, then the next cell up got its own build before the world showed K
        b.build("portal", oneObsidian(), K.above());
        b.placed(K, obsidian(), "builder", stamp);
        assertFalse(ledger(b).isScaffold(cell(K)));
        assertFalse(ledger(b).hasScaffold());
    }

    @Test
    public void aConfirmFromThisBuildStillBooks() throws Exception {
        BuilderProcess b = builder();
        b.build("portal", oneObsidian(), K.above());
        // a pillar block under the box, clicked and confirmed under the same build
        b.placed(K, obsidian(), "builder", b.ledgerGeneration());
        assertTrue(ledger(b).isScaffold(cell(K)));
    }

    @Test
    public void aResumeOfTheSameBuildKeepsItsStamp() throws Exception {
        BuilderProcess b = builder();
        b.build("house", oneObsidian(), K.above());
        int stamp = b.ledgerGeneration();
        // a stop and start of the same build in between, the click from before still counts
        b.build("house", oneObsidian(), K.above());
        b.placed(K, obsidian(), "someone", stamp);
        assertTrue(ledger(b).isScaffold(cell(K)));
    }

    @Test
    public void aBlockAltoclefWantsKeptNeverBooks() throws Exception {
        AltoClefSettings.getInstance().avoidBlockBreak(K);
        BuilderProcess b = builder();
        b.build("portal", oneObsidian(), K.above());
        b.placed(K, obsidian(), "builder", b.ledgerGeneration());
        assertFalse(ledger(b).isScaffold(cell(K)));
    }
}
