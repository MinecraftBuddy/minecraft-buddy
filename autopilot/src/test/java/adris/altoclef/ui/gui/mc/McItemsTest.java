package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.CommandsAccess;
import adris.altoclef.ui.gui.facade.ItemsAccess.BasketLine;
import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.Result;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class McItemsTest {

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final class Recorder implements CommandsAccess {
        final List<String> lines = new ArrayList<>();
        Function<String, Result> answer = l -> Result.ok("Ran " + l, l);

        @Override
        public List<CommandInfo> commands() {
            return List.of();
        }

        @Override
        public Result run(String line) {
            lines.add(line);
            return answer.apply(line);
        }
    }

    private static List<BasketLine> basket(Object... pairs) {
        List<BasketLine> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.add(new BasketLine((String) pairs[i], (Integer) pairs[i + 1]));
        }
        return out;
    }

    @Test
    public void browsingCategoriesWorkBeforeOpeningCreativeInventory() {
        assertEquals("Blocks", McItems.categoryOf("stone"));
        assertEquals("Blocks", McItems.categoryOf("planks"));
        assertEquals("Tools", McItems.categoryOf("iron_pickaxe"));
        assertEquals("Tools", McItems.categoryOf("water_bucket"));
        assertEquals("Combat", McItems.categoryOf("iron_sword"));
        assertEquals("Combat", McItems.categoryOf("iron_chestplate"));
        assertEquals("Food", McItems.categoryOf("bread"));
        assertEquals("Materials", McItems.categoryOf("iron_ingot"));
        assertEquals("Redstone", McItems.categoryOf("repeater"));
        assertEquals("Other", McItems.categoryOf("soprano_unknown_item"));
    }

    @Test
    public void getLineIsTheChatSyntax() {
        assertEquals("get oak_log 64 iron_ingot 3", McItems.getLine(basket("oak_log", 64, "iron_ingot", 3)));
        assertEquals("get log 1", McItems.getLine(basket(" Log ", 1)));
    }

    @Test
    public void getLineParsesBackWithTheRealItemGrammar() throws Exception {
        String line = McItems.getLine(basket("oak_log", 64, "iron_ingot", 3, "oak_log", 6));
        var parsed = adris.altoclef.commands.ItemArgs.parse(line.substring("get ".length()));
        assertEquals(2, parsed.size());
        assertEquals("oak_log", parsed.get(0).name());
        assertEquals(70, parsed.get(0).count());
        assertEquals(3, parsed.get(1).count());
    }

    @Test
    public void mineLineUsesTheFirstCountAndEveryName() {
        assertEquals("mine 64 iron_ore coal_ore", McItems.mineLine(basket("iron_ore", 64, "coal_ore", 10)));
        assertEquals("mine iron_ore coal_ore", McItems.mineLine(basket("iron_ore", 0, "coal_ore", 10)));
        assertEquals("mine 5 stone", McItems.mineLine(basket("stone", 5, "stone", 9)));
    }

    @Test
    public void getRunsTheLineAndSaysWhatItIsDoing() {
        Recorder r = new Recorder();
        Result res = new McItems(r).get(basket("oak_log", 64, "iron_ingot", 3));
        assertTrue(res.ok());
        assertEquals(List.of("get oak_log 64 iron_ingot 3"), r.lines);
        assertEquals("get oak_log 64 iron_ingot 3", res.command());
        assertEquals("Getting 64 oak_log and 3 iron_ingot", res.message());
    }

    @Test
    public void mineRunsTheLineAndSaysWhatItIsDoing() {
        Recorder r = new Recorder();
        Result res = new McItems(r).mine(basket("iron_ore", 16, "coal_ore", 0));
        assertTrue(res.ok());
        assertEquals(List.of("mine 16 iron_ore coal_ore"), r.lines);
        assertEquals("Mining iron_ore, coal_ore until you have 16 in total", res.message());
        assertEquals("Mining stone", new McItems(new Recorder()).mine(basket("stone", 0)).message());
    }

    @Test
    public void aRefusedCommandComesBackAsASentenceWithTheLine() {
        Recorder r = new Recorder();
        r.answer = l -> Result.fail("\"foo\" is not a catalogued resource", l);
        Result res = new McItems(r).get(basket("foo", 1));
        assertFalse(res.ok());
        assertEquals("\"foo\" is not a catalogued resource", res.message());
        assertEquals("get foo 1", res.command());
        r.answer = l -> Result.fail("altoclef is not up yet, give it a second", l);
        assertEquals("Soprano is not up yet, give it a second", new McItems(r).get(basket("log", 1)).message());
    }

    @Test
    public void badBasketsNeverReachTheRunner() {
        Recorder r = new Recorder();
        McItems items = new McItems(r);
        assertFalse(items.get(List.of()).ok());
        assertFalse(items.get(null).ok());
        assertFalse(items.get(basket("log", 0)).ok());
        assertFalse(items.get(basket("two words", 2)).ok());
        assertFalse(items.get(basket("", 2)).ok());
        assertFalse(items.mine(List.of()).ok());
        assertFalse(items.mine(basket("a,b", 1)).ok());
        assertTrue(r.lines.isEmpty());
    }

    @Test
    public void aThrowingRunnerIsAFailureNotAnException() {
        Recorder r = new Recorder();
        r.answer = l -> {
            throw new IllegalStateException("boom");
        };
        Result res = new McItems(r).get(basket("log", 1));
        assertFalse(res.ok());
        assertNull(res.command());
    }

    @Test
    public void describeStaysShort() {
        assertEquals("1 log", McItems.describe(basket("log", 1)));
        assertEquals("1 a, 2 b and 1 more", McItems.describe(basket("a", 1, "b", 2, "c", 3)));
        assertEquals("3 a, 2 b and 2 more", McItems.describe(basket("a", 1, "b", 2, "c", 3, "d", 4, "a", 2)));
        assertEquals("6 a", McItems.describe(basket("a", 1, "a", 5)));
    }

    @Test
    public void gettableIsSortedWithIconsAndStackSizes() {
        List<ItemEntry> list = new McItems(new Recorder()).gettable();
        assertTrue(list.size() > 200);
        for (int i = 1; i < list.size(); i++) {
            assertTrue(list.get(i - 1).name() + " before " + list.get(i).name(),
                    list.get(i - 1).name().compareTo(list.get(i).name()) < 0);
        }
        for (ItemEntry e : list) {
            assertTrue(e.name(), e.iconItemId().startsWith("minecraft:"));
            assertFalse(e.name() + " has no icon", e.iconItemId().equals("minecraft:barrier"));
            assertTrue(e.name(), e.maxStack() >= 1 && e.maxStack() <= 64);
        }
        ItemEntry sword = list.stream().filter(e -> e.name().equals("diamond_sword")).findFirst().orElseThrow();
        assertEquals(1, sword.maxStack());
        ItemEntry log = list.stream().filter(e -> e.name().equals("oak_log")).findFirst().orElseThrow();
        assertEquals(64, log.maxStack());
        assertEquals("minecraft:oak_log", log.iconItemId());
    }

    @Test
    public void everyEntryHasADisplayNameAndNeverSaysAltoclef() {
        McItems items = new McItems(new Recorder());
        for (ItemEntry e : items.gettable()) {
            assertFalse(e.name(), e.displayName() == null || e.displayName().isBlank());
            assertFalse(e.name(), e.displayName().toLowerCase().contains("altoclef"));
        }
        for (ItemEntry e : items.mineable()) {
            assertFalse(e.name(), e.displayName() == null || e.displayName().isBlank());
        }
        // a group has no single item to speak for it
        ItemEntry planks = items.gettable().stream().filter(e -> e.name().equals("planks")).findFirst().orElseThrow();
        assertEquals("Planks", planks.displayName());
    }

    @Test
    public void aliasesNeverGetTheirOwnTile() {
        List<ItemEntry> list = new McItems(new Recorder()).gettable();
        List<String> names = list.stream().map(ItemEntry::name).toList();
        java.util.Map<String, String> aliases = adris.altoclef.TaskCatalogue.aliases();
        assertTrue(aliases.containsKey("netherite_pick"));
        for (java.util.Map.Entry<String, String> a : aliases.entrySet()) {
            assertFalse(a.getKey() + " is an alias", names.contains(a.getKey()));
            // the thing it stands for still has to be clickable
            assertTrue(a.getKey() + " -> " + a.getValue(), names.contains(a.getValue()));
        }
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        for (ItemEntry e : list) {
            String before = seen.put(e.displayName(), e.name());
            assertNull(e.displayName() + " is on both " + before + " and " + e.name(), before);
        }
    }

    @Test
    public void gettableIsBuiltOnce() {
        McItems items = new McItems(new Recorder());
        assertTrue(items.gettable() == items.gettable());
        assertTrue(items.mineable() == items.mineable());
    }

    @Test
    public void mineableHasOresAndNothingUnbreakable() {
        List<ItemEntry> list = new McItems(new Recorder()).mineable();
        List<String> names = list.stream().map(ItemEntry::name).toList();
        for (String want : List.of("iron_ore", "deepslate_diamond_ore", "stone", "oak_log", "ancient_debris", "obsidian")) {
            assertTrue(want, names.contains(want));
        }
        for (String nope : List.of("bedrock", "barrier", "air", "water", "lava", "fire", "command_block", "light",
                "end_portal_frame", "nether_portal", "moving_piston", "jigsaw", "structure_block", "reinforced_deepslate")) {
            assertFalse(nope, names.contains(nope));
        }
        for (int i = 1; i < names.size(); i++) {
            assertTrue(names.get(i - 1).compareTo(names.get(i)) < 0);
        }
        assertTrue(list.stream().allMatch(e -> e.maxStack() >= 1 && e.maxStack() <= 64));
    }
}
