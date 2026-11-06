package baritone.utils;

import adris.altoclef.tasks.speedrun.gamer.GolemRules;
import adris.altoclef.tasks.speedrun.gamer.NightSleep;
import adris.altoclef.util.helpers.ItemFixture;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

// numbers the code carries around that came from vanilla, held against the vanilla they came from. when a port to a new
// version moves one of them this is the test that says so, instead of the bot being a little wrong for a month
public class VanillaConstantsTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static JsonObject data(String path) throws Exception {
        try (Reader reader = new InputStreamReader(EntityType.class.getResourceAsStream("/data/minecraft/" + path), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    // fall damage is floor(fall + 1e-6 - safe) * multiplier with the landing move counted in the fall, so whole blocks
    // are free up to 3 and the first hurting fall is 4. ExperimentalMovement.fallDamage prices whole block drops with this
    @Test
    public void safeFallDistanceIsStillThree() {
        assertEquals(3.0, Attributes.SAFE_FALL_DISTANCE.value().getDefaultValue(), 0);
        assertEquals(1.0, Attributes.FALL_DAMAGE_MULTIPLIER.value().getDefaultValue(), 0);
        assertEquals(3, ExperimentalMovement.SAFE_FALL);
        assertEquals(0, ExperimentalMovement.fallDamage(3));
        assertEquals(1, ExperimentalMovement.fallDamage(4));
        // 23 blocks is exactly the 20 hp of a full bar
        assertEquals(20, ExperimentalMovement.fallDamage(ExperimentalMovement.MAX_HURT_FALL));
    }

    @Test
    public void reachAndEyeHeights() {
        assertEquals(4.5, Attributes.BLOCK_INTERACTION_RANGE.value().getDefaultValue(), 0);
        assertEquals(3.0, Attributes.ENTITY_INTERACTION_RANGE.value().getDefaultValue(), 0);
        assertEquals(GolemRules.INTERACT_RANGE, Attributes.ENTITY_INTERACTION_RANGE.value().getDefaultValue(), 0);
        assertEquals(1.62, EntityTypes.PLAYER.getDimensions().eyeHeight(), 1e-6);
        assertEquals(GolemRules.EYE_HEIGHT, EntityTypes.PLAYER.getDimensions().eyeHeight(), 1e-6);
        assertEquals(GolemRules.GOLEM_HEIGHT, EntityTypes.IRON_GOLEM.getDimensions().height(), 1e-6);
    }

    @Test
    public void sneakSpeedAndSoulSand() {
        assertEquals(0.3, Attributes.SNEAKING_SPEED.value().getDefaultValue(), 1e-9);
        assertEquals(0.4f, Blocks.SOUL_SAND.getSpeedFactor(), 0);
    }

    @Test
    public void miningFatigueIsAPower() {
        assertEquals(0.3, ToolSet.fatigueFactor(0), 1e-9);
        assertEquals(0.09, ToolSet.fatigueFactor(1), 1e-9);
        assertEquals(0.027, ToolSet.fatigueFactor(2), 1e-9);
        assertEquals(0.0081, ToolSet.fatigueFactor(3), 1e-9);
    }

    // NightSleep leans on the overworld clock being the 24000 one, and on nether and end never letting you sleep
    @Test
    public void overworldClockPeriodAndBedRules() throws Exception {
        assertEquals(NightSleep.DAY_LENGTH, data("timeline/day.json").get("period_ticks").getAsLong());
        // the bed rule is an environment attribute on the dimension type; the code decides by dimension type instead
        // because the client doesn't get that attribute synced, so make sure the data still says what that assumes
        assertEquals("never", bedRule("the_nether").get("can_sleep").getAsString());
        assertEquals("never", bedRule("the_end").get("can_sleep").getAsString());
        assertEquals("when_dark", bedRule("overworld").get("can_sleep").getAsString());
    }

    private static JsonObject bedRule(String dimension) throws Exception {
        JsonObject attributes = data("dimension_type/" + dimension + ".json").getAsJsonObject("attributes");
        return attributes.getAsJsonObject("minecraft:gameplay/bed_rule");
    }

    // water_evaporates is the attribute WorldHelper reads in place of ultraWarm, and the nether is the only one with it
    @Test
    public void onlyTheNetherEvaporatesWater() throws Exception {
        assertEquals(true, data("dimension_type/the_nether.json").getAsJsonObject("attributes").get("minecraft:gameplay/water_evaporates").getAsBoolean());
        for (String dimension : new String[]{"overworld", "the_end"}) {
            assertEquals(false, data("dimension_type/" + dimension + ".json").getAsJsonObject("attributes").has("minecraft:gameplay/water_evaporates"));
        }
    }
}
