package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.ItemFixture;
import adris.altoclef.util.helpers.ItemHelper;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import org.junit.BeforeClass;
import org.junit.Test;

// the buried rule only runs for a miner that calls all its blocks logs (MineAnchor.logsOnly), so one odd block on MineLogsTask's
// list would quietly turn it off for #get log. the task itself can't be built here, its progress checkers want a running game,
// and the block tags aren't bound in a test, so this reads the same tag files a world load does
public class MineLogsTaskTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static void readTag(String path, Set<String> out) throws Exception {
        try (Reader reader = new InputStreamReader(Block.class.getResourceAsStream("/data/minecraft/tags/block/" + path + ".json"), StandardCharsets.UTF_8)) {
            for (JsonElement entry : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("values")) {
                String value = entry.getAsString();
                if (value.startsWith("#minecraft:")) {
                    readTag(value.substring("#minecraft:".length()), out);
                } else {
                    out.add(value);
                }
            }
        }
    }

    @Test
    public void everyBlockOnTheListCountsAsALog() throws Exception {
        Set<String> logs = new HashSet<>();
        readTag("logs", logs);
        Block[] list = ItemHelper.itemsToBlocks(ItemHelper.LOG);
        assertTrue(list.length > 0);
        for (Block block : list) {
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            assertTrue(id + " is not in the logs tag", logs.contains(id));
        }
    }
}
