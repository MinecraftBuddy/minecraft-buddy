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

package baritone.cache;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class CachedSpecialBlocksTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    // the type only carries the shape now; which dimension it is lives in the ResourceKey the cache is handed
    private static DimensionType dim(int minY, int height) {
        return new DimensionType(false, true, false, false, 1.0, minY, height, height,
            HolderSet.empty(), 0.0f, new DimensionType.MonsterSettings(ConstantInt.of(0), 0),
            DimensionType.Skybox.OVERWORLD, CardinalLighting.Type.DEFAULT, EnvironmentAttributeMap.EMPTY,
            HolderSet.empty(), Optional.empty());
    }

    private static BlockState[] stoneOverview() {
        BlockState[] overview = new BlockState[256];
        Arrays.fill(overview, Blocks.STONE.defaultBlockState());
        return overview;
    }

    // one solid column at (x, z) from the floor of the dimension up to relTop, in dimension relative y
    private static BitSet solidColumn(int height, int x, int z, int relTop) {
        BitSet data = new BitSet(CachedChunk.size(height));
        for (int y = 0; y <= relTop; y++) {
            int i = CachedChunk.getPositionIndex(x, y, z);
            data.set(i);
            data.set(i + 1);
        }
        return data;
    }

    // what the packer hands over: real world y, not relative
    private static Map<String, List<BlockPos>> specials(int x, int z, int... realYs) {
        List<BlockPos> chests = new ArrayList<>();
        for (int y : realYs) {
            chests.add(new BlockPos(x, y, z));
        }
        Map<String, List<BlockPos>> map = new HashMap<>();
        map.put("chest", chests);
        return map;
    }

    @Test
    public void overworldSpecialsAreFoundAtTheirRealY() {
        DimensionType dim = dim(-64, 384);
        // surface well above both chests so neither is the overview block
        CachedChunk chunk = new CachedChunk(0, 0, 384, solidColumn(384, 3, 5, 200), stoneOverview(), specials(3, 5, 70, -24), 0L);
        assertEquals(Blocks.CHEST, chunk.getBlock(3, 70 + 64, 5, dim, Level.OVERWORLD).getBlock());
        assertEquals(Blocks.CHEST, chunk.getBlock(3, -24 + 64, 5, dim, Level.OVERWORLD).getBlock());
        // and a plain solid block next to them is still just stone
        assertEquals(Blocks.STONE, chunk.getBlock(3, 71 + 64, 5, dim, Level.OVERWORLD).getBlock());
        assertEquals(Blocks.STONE, chunk.getBlock(3, 69 + 64, 5, dim, Level.OVERWORLD).getBlock());
    }

    @Test
    public void netherSpecialsStillWork() {
        DimensionType dim = dim(0, 256);
        CachedChunk chunk = new CachedChunk(0, 0, 256, solidColumn(256, 3, 5, 200), stoneOverview(), specials(3, 5, 70), 0L);
        assertEquals(Blocks.CHEST, chunk.getBlock(3, 70, 5, dim, Level.NETHER).getBlock());
        // the low-solid obsidian guess is an overworld thing, the nether keeps netherrack down there like 1.21.4 did
        assertEquals(Blocks.NETHERRACK, chunk.getBlock(3, 2, 5, dim, Level.NETHER).getBlock());
    }

    @Test
    public void bedrockBandUsesRelativeY() {
        DimensionType dim = dim(-64, 384);
        CachedChunk chunk = new CachedChunk(0, 0, 384, solidColumn(384, 3, 5, 200), stoneOverview(), new HashMap<>(), 0L);
        // real y -64 to -60 is the bedrock band, so cached solids there are discouraged
        assertEquals(Blocks.OBSIDIAN, chunk.getBlock(3, 0, 5, dim, Level.OVERWORLD).getBlock());
        assertEquals(Blocks.OBSIDIAN, chunk.getBlock(3, 4, 5, dim, Level.OVERWORLD).getBlock());
        assertEquals(Blocks.STONE, chunk.getBlock(3, 5, 5, dim, Level.OVERWORLD).getBlock());
    }

    @Test
    public void specialsSurviveARegionSaveAndLoad() {
        DimensionType dim = dim(-64, 384);
        CachedChunk chunk = new CachedChunk(0, 0, 384, solidColumn(384, 3, 5, 200), stoneOverview(), specials(3, 5, 70, -24), System.currentTimeMillis());
        CachedRegion region = new CachedRegion(0, 0, dim, Level.OVERWORLD);
        region.updateCachedChunk(0, 0, chunk);
        String dir = folder.getRoot().toString();
        region.save(dir);

        CachedRegion loaded = new CachedRegion(0, 0, dim, Level.OVERWORLD);
        loaded.load(dir);
        assertEquals(Blocks.CHEST, loaded.getBlock(3, 70, 5).getBlock());
        assertEquals(Blocks.CHEST, loaded.getBlock(3, -24, 5).getBlock());
        assertEquals(Blocks.STONE, loaded.getBlock(3, 71, 5).getBlock());
        assertEquals(2, loaded.getLocationsOf("chest").size());
    }

    @Test
    public void unknownBlockNamesDontWipeTheRegion() throws Exception {
        DimensionType dim = dim(-64, 384);
        Path file = folder.getRoot().toPath().resolve("r.0.0.bcr");
        BitSet data = solidColumn(384, 3, 5, 200);
        byte[] raw = new byte[CachedChunk.sizeInBytes(CachedChunk.size(384))];
        byte[] packed = data.toByteArray();
        System.arraycopy(packed, 0, raw, 0, packed.length);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file.toFile()))))) {
            out.writeInt(CachedRegion.CACHED_REGION_MAGIC);
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    if (x == 0 && z == 0) {
                        out.write(1);
                        out.write(raw);
                    } else {
                        out.write(0);
                    }
                }
            }
            for (int i = 0; i < 256; i++) {
                out.writeUTF(i == 0 ? "definitely_not_a_block" : "stone");
            }
            out.writeShort(2);
            out.writeUTF("definitely_not_a_block");
            out.writeShort(1);
            out.writeByte(5 << 4 | 3);
            out.writeInt(134);
            out.writeUTF("chest");
            out.writeShort(1);
            out.writeByte(5 << 4 | 3);
            out.writeInt(134);
            out.writeLong(System.currentTimeMillis());
        }

        CachedRegion loaded = new CachedRegion(0, 0, dim, Level.OVERWORLD);
        loaded.load(folder.getRoot().toString());
        assertEquals(true, loaded.isCached(0, 0));
        // the unknown overview entry turns into stone instead of killing the load
        // (an all air column reports its overview block at the floor of the dimension)
        assertEquals(Blocks.STONE, loaded.getBlock(0, -64, 0).getBlock());
        // the known special survives, the unknown one is dropped
        assertEquals(Blocks.CHEST, loaded.getBlock(3, 70, 5).getBlock());
        assertEquals(1, loaded.getLocationsOf("chest").size());
        assertEquals(0, loaded.getLocationsOf("definitely_not_a_block").size());
    }

    @Test
    public void outOfWorldRegionsDontShadowTheOrigin() {
        CachedWorld world = new CachedWorld(folder.getRoot().toPath(), dim(0, 256), Level.NETHER);
        world.tryLoadFromDisk(0, 0);
        assertNotNull(world.getRegion(0, 0));
        assertNull(world.getRegion(1_000_000, 0));
        assertNull(world.getRegion(0, -1_000_000));
        world.tryLoadFromDisk(1_000_000, 0); // must not throw or create anything
        assertNull(world.getRegion(1_000_000, 0));
        assertEquals(false, world.isCached(1_000_000 << 9, 0));
    }
}
