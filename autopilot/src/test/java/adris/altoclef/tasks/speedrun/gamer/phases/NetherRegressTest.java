package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.tasks.speedrun.gamer.FakeFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// a death in the Nether respawns us at home with an empty bag: the nether phases must send us back to plan the kit
public class NetherRegressTest {
    private final GamerConfig cfg = new GamerConfig();
    private final RunState state = new RunState();
    private final NetherPhase nether = new NetherPhase();
    private final EyesPhase eyes = new EyesPhase();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static FakeFacts overworldWithKit() {
        FakeFacts f = new FakeFacts();
        f.dimension = Dimension.OVERWORLD;
        f.give(Items.IRON_PICKAXE, 1).give(Items.FLINT_AND_STEEL, 1).give(Items.BUCKET, 2);
        f.armorPoints = 15;
        return f;
    }

    @Test
    public void emptyBagAtHomeStartsOverWithGather() {
        FakeFacts f = new FakeFacts();
        f.dimension = Dimension.OVERWORLD;
        assertEquals(Optional.of(GamerPhase.GATHER), nether.regressTo(f, state, cfg));
        assertEquals(Optional.of(GamerPhase.GATHER), eyes.regressTo(f, state, cfg));
    }

    @Test
    public void stonePickaxeButNoEssentialsGoesToIron() {
        FakeFacts f = new FakeFacts();
        f.dimension = Dimension.OVERWORLD;
        f.give(Items.STONE_PICKAXE, 1);
        assertEquals(Optional.of(GamerPhase.IRON), nether.regressTo(f, state, cfg));
        assertEquals(Optional.of(GamerPhase.IRON), eyes.regressTo(f, state, cfg));
    }

    @Test
    public void armorUnderTheBedLineGoesToIron() {
        FakeFacts f = overworldWithKit();
        f.armorPoints = cfg.end.bedMinArmor - 1;
        assertEquals(Optional.of(GamerPhase.IRON), nether.regressTo(f, state, cfg));
        f.armorPoints = cfg.end.bedMinArmor;
        assertTrue(nether.regressTo(f, state, cfg).isEmpty());
    }

    @Test
    public void lighterArmorPlanHasNoNumberToHoldItTo() {
        cfg.overworld.armorPlan = OverworldConfig.ArmorPlan.CHEST_HELMET;
        FakeFacts f = overworldWithKit();
        f.armorPoints = 0;
        assertTrue(nether.regressTo(f, state, cfg).isEmpty());
    }

    @Test
    public void theKitIntactMeansWalkBackToThePortalNotARegress() {
        assertTrue(nether.regressTo(overworldWithKit(), state, cfg).isEmpty());
    }

    @Test
    public void nothingFiresInsideTheNether() {
        FakeFacts f = new FakeFacts();
        f.dimension = Dimension.NETHER;
        assertTrue(nether.regressTo(f, state, cfg).isEmpty());
    }

    @Test
    public void portalPhaseOnlyRegressesWhenThePickaxeIsGone() {
        PortalPhase portal = new PortalPhase();
        FakeFacts f = new FakeFacts();
        f.dimension = Dimension.OVERWORLD;
        assertEquals(Optional.of(GamerPhase.GATHER), portal.regressTo(f, state, cfg));
        f.give(Items.STONE_PICKAXE, 1);
        assertEquals(Optional.of(GamerPhase.IRON), portal.regressTo(f, state, cfg));
        // the rest of the kit is the gate's job: armor short and no flint left (used up lighting it) is not a regress
        FakeFacts ready = new FakeFacts();
        ready.dimension = Dimension.OVERWORLD;
        ready.give(Items.IRON_PICKAXE, 1);
        ready.armorPoints = 0;
        assertTrue(portal.regressTo(ready, state, cfg).isEmpty());
        FakeFacts below = new FakeFacts();
        below.dimension = Dimension.NETHER;
        assertTrue(portal.regressTo(below, state, cfg).isEmpty());
    }

    // the gear rule comes before the "not enough eyes" rule: kit first, then the trip
    @Test
    public void eyesPhaseAtHomeWithoutKitAndWithoutEyesPlansTheKitFirst() {
        FakeFacts f = new FakeFacts();
        f.dimension = Dimension.OVERWORLD;
        f.give(Items.STONE_PICKAXE, 1);
        assertEquals(Optional.of(GamerPhase.IRON), eyes.regressTo(f, state, cfg));
        // with the kit and no eyes it is the old rule
        assertEquals(Optional.of(GamerPhase.NETHER), eyes.regressTo(overworldWithKit(), state, cfg));
    }
}
