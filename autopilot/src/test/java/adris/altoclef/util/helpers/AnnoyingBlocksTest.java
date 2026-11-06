/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.util.helpers;

import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnnoyingBlocksTest {

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    @Test
    public void everyEntryOfTheListCounts() {
        // isAnnoying used to return from inside the loop, so only the first entry (vine) was ever tested
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.VINE));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.NETHER_SPROUTS));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.CAVE_VINES));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.CAVE_VINES_PLANT));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.TWISTING_VINES));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.TWISTING_VINES_PLANT));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.WEEPING_VINES_PLANT));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.LADDER));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.BIG_DRIPLEAF));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.BIG_DRIPLEAF_STEM));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.SMALL_DRIPLEAF));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.TALL_GRASS));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.SHORT_GRASS));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.SWEET_BERRY_BUSH));
    }

    @Test
    public void doorsFencesAndFlowersCount() {
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.OAK_DOOR));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.IRON_DOOR));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.OAK_FENCE));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.NETHER_BRICK_FENCE));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.OAK_FENCE_GATE));
        assertTrue(AnnoyingBlocks.isAnnoying(Blocks.DANDELION));
    }

    @Test
    public void solidGroundIsNotAnnoying() {
        // the port had mapped the old GRASS (plant) to GRASS_BLOCK, which would flag every hillside
        assertFalse(AnnoyingBlocks.isAnnoying(Blocks.GRASS_BLOCK));
        assertFalse(AnnoyingBlocks.isAnnoying(Blocks.STONE));
        assertFalse(AnnoyingBlocks.isAnnoying(Blocks.AIR));
        assertFalse(AnnoyingBlocks.isAnnoying(Blocks.DIRT));
    }
}
