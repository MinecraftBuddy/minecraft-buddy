package adris.altoclef.util.helpers;

import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CropRulesTest {
    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    // wheat, carrots and potatoes grow to 7, beetroots only to 3
    private static void onlyTheLastAgeIsRipe(Block block, int maxAge) {
        CropBlock crop = (CropBlock) block;
        assertEquals(block + " max age", maxAge, crop.getMaxAge());
        for (int age = 0; age <= maxAge; age++) {
            BlockState state = crop.getStateForAge(age);
            assertEquals(block + " at age " + age, age == maxAge, CropRules.ripe(state));
        }
    }

    @Test
    public void wheatIsRipeOnlyWhenGrown() {
        onlyTheLastAgeIsRipe(Blocks.WHEAT, 7);
    }

    @Test
    public void carrotsAreRipeOnlyWhenGrown() {
        // the one the bot kept breaking as seedlings
        onlyTheLastAgeIsRipe(Blocks.CARROTS, 7);
    }

    @Test
    public void potatoesAreRipeOnlyWhenGrown() {
        onlyTheLastAgeIsRipe(Blocks.POTATOES, 7);
    }

    @Test
    public void beetrootsAreRipeAtTheirOwnLastAge() {
        onlyTheLastAgeIsRipe(Blocks.BEETROOTS, 3);
    }

    @Test
    public void somethingThatIsNotACropIsNotRipe() {
        assertFalse(CropRules.ripe(Blocks.STONE.defaultBlockState()));
        assertFalse(CropRules.ripe(Blocks.AIR.defaultBlockState()));
        assertFalse(CropRules.ripe(Blocks.FARMLAND.defaultBlockState()));
        assertTrue(CropRules.ripe(((CropBlock) Blocks.CARROTS).getStateForAge(7)));
    }
}
