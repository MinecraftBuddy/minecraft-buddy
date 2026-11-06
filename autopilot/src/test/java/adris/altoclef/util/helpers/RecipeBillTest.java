package adris.altoclef.util.helpers;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.util.ItemTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the bill over the real catalogue recipes with a pretend bag. every number here was worked out by hand from the recipes
// in TaskCatalogue, so if one of these moves, check the recipe before you "fix" the test
public class RecipeBillTest {

    private static final RecipeBill.World NOTHING_NEAR = new RecipeBill.World(false, false, 0, n -> false);
    private static final RecipeBill.World ALL_NEAR = new RecipeBill.World(true, true, 0, n -> false);

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static CatalogueBook bag(Object... pairs) {
        Map<Item, Integer> held = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) held.put((Item) pairs[i], (Integer) pairs[i + 1]);
        return new CatalogueBook(matches -> {
            int sum = 0;
            for (Item item : matches) sum += held.getOrDefault(item, 0);
            return sum;
        });
    }

    private static Map<String, Integer> want(Object... pairs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], (Integer) pairs[i + 1]);
        return out;
    }

    private static List<String> names(List<RecipeBill.Node> nodes) {
        List<String> out = new ArrayList<>();
        for (RecipeBill.Node n : nodes) out.add(n.name());
        return out;
    }

    @Test
    public void lanternChainAndBucketPoolTheirIron() {
        RecipeBill.Bill bill = RecipeBill.of(want("lantern", 1, "iron_chain", 1, "bucket", 1), bag(), NOTHING_NEAR);
        // nuggets: 8 for the lantern + 2 for the chain = 10, two crafts of 9. ingots: 2 for those + 1 chain + 3 bucket = 6
        assertEquals(10, bill.need("iron_nugget"));
        assertEquals(6, bill.need("iron_ingot"));
        assertEquals(6, bill.need("raw_iron"));
        assertEquals(6, bill.smelts);
        // the torch: a coal and a stick. a stick is half of 2 planks, a crafting table is 4 more, 6 planks is 2 logs
        assertEquals(1, bill.need("crafting_table"));
        assertEquals(6, bill.need("planks"));
        assertEquals(2, bill.need("log"));
        // one furnace (8 of cobble, cobbled deepslate or blackstone), and 6 smelts is one coal on top of the torch's
        assertEquals(1, bill.need("furnace"));
        assertEquals(8, bill.need("stone_material"));
        assertEquals(2, bill.need("coal"));
        assertEquals(1, bill.fuelCoal);
        assertTrue(bill.table);
        assertTrue(bill.furnace);
        assertFalse(bill.blast);
        // fetched lowest pickaxe first
        assertEquals(List.of("log", "coal", "stone_material", "raw_iron"), names(bill.leaves));
        assertTrue(bill.deferred.isEmpty());
    }

    @Test
    public void heldIngotsAndNuggetsComeOffOnce() {
        RecipeBill.Bill bill = RecipeBill.of(want("lantern", 1, "iron_chain", 1, "bucket", 1),
                bag(Items.IRON_INGOT, 3, Items.IRON_NUGGET, 4), NOTHING_NEAR);
        // 10 nuggets wanted, 4 held: one craft, one ingot. ingots 1 + 1 + 3 = 5, 3 held: 2 raw iron
        assertEquals(1, bill.node("iron_nugget").runs());
        assertEquals(5, bill.need("iron_ingot"));
        assertEquals(2, bill.need("raw_iron"));
        assertEquals(2, bill.smelts);
    }

    @Test
    public void sixtyFourTorches() {
        RecipeBill.Bill bill = RecipeBill.of(want("torch", 64), bag(), ALL_NEAR);
        assertEquals(16, bill.need("coal"));
        assertEquals(16, bill.need("stick"));
        assertEquals(8, bill.need("planks"));
        assertEquals(2, bill.need("log"));
        // torches, sticks and planks all fit the 2x2, so no table for this one
        assertFalse(bill.table);
        assertEquals(0, bill.need("crafting_table"));
        // and no table is no table even when there's none around
        assertEquals(0, RecipeBill.of(want("torch", 64), bag(), NOTHING_NEAR).need("crafting_table"));
    }

    @Test
    public void aTableCraftWithNoTableAroundBringsOne() {
        RecipeBill.Bill bill = RecipeBill.of(want("chest", 1), bag(), NOTHING_NEAR);
        // 8 planks for the chest, 4 for the table, 12 planks is 3 logs
        assertEquals(1, bill.need("crafting_table"));
        assertEquals(12, bill.need("planks"));
        assertEquals(3, bill.need("log"));
        // with one standing nearby it's 2 logs
        assertEquals(2, RecipeBill.of(want("chest", 1), bag(), ALL_NEAR).need("log"));
        // one in the bag counts as the station
        assertEquals(2, RecipeBill.of(want("chest", 1), bag(Items.CRAFTING_TABLE, 1), NOTHING_NEAR).need("log"));
    }

    @Test
    public void stationsComeBeforeWhatNeedsThem() {
        RecipeBill.Bill bill = RecipeBill.of(want("bucket", 1), bag(), NOTHING_NEAR);
        assertTrue(bill.node("crafting_table").level() < bill.node("furnace").level());
        assertTrue(bill.node("furnace").level() < bill.node("iron_ingot").level());
        assertTrue(bill.node("iron_ingot").level() < bill.node("bucket").level());
    }

    @Test
    public void blastFurnacePartsOnlyWhenTheIronRunWouldBuildOne() {
        RecipeBill.World blasts = new RecipeBill.World(false, false, 0, n -> n >= 5);
        RecipeBill.Bill bill = RecipeBill.of(want("lantern", 1, "iron_chain", 1, "bucket", 1), bag(), blasts);
        assertTrue(bill.blast);
        // the ingot target stays 6, the blast eats 5 more on the side
        assertEquals(6, bill.need("iron_ingot"));
        assertEquals(11, bill.need("raw_iron"));
        assertEquals(3, bill.need("smooth_stone"));
        assertEquals(3, bill.need("stone"));
        // a furnace to smelt in and one that goes into the blast furnace: 16 of the furnaces' stone, and 3 cobble for the stone
        assertEquals(2, bill.need("furnace"));
        assertEquals(16, bill.need("stone_material"));
        assertEquals(3, bill.need("cobblestone"));
        // smelts: 6 iron + 5 for the blast + 3 stone + 3 smooth stone = 17, three coal, plus the torch's
        assertEquals(17, bill.smelts);
        assertEquals(4, bill.need("coal"));
        // the smooth stone and the furnace are there before the iron goes in
        assertTrue(bill.node("smooth_stone").level() < bill.node("iron_ingot").level());
        assertTrue(bill.node("furnace").level() < bill.node("iron_ingot").level());

        // 4 ingots isn't a blast furnace's worth
        RecipeBill.Bill few = RecipeBill.of(want("iron_ingot", 4), bag(), blasts);
        assertFalse(few.blast);
        assertEquals(4, few.need("raw_iron"));
        assertEquals(0, few.need("smooth_stone"));
        // and nothing to smelt is nothing to build, even for a big target
        RecipeBill.Bill held = RecipeBill.of(want("iron_ingot", 9), bag(Items.IRON_INGOT, 9), blasts);
        assertFalse(held.blast);
    }

    @Test
    public void fuelMath() {
        assertEquals(0, RecipeBill.fuelCoal(0, 0));
        assertEquals(1, RecipeBill.fuelCoal(1, 0));
        assertEquals(1, RecipeBill.fuelCoal(8, 0));
        assertEquals(2, RecipeBill.fuelCoal(9, 0));
        assertEquals(3, RecipeBill.fuelCoal(17, 0));
        // charcoal or whatever else is in the bag covers its share first
        assertEquals(0, RecipeBill.fuelCoal(8, 8));
        assertEquals(1, RecipeBill.fuelCoal(9, 8));
        // coal already in the bag nets off the coal node like any other leaf
        RecipeBill.Bill bill = RecipeBill.of(want("iron_ingot", 16), bag(Items.COAL, 1), ALL_NEAR);
        assertEquals(2, bill.need("coal"));
        assertEquals(1, bill.net("coal"));
    }

    @Test
    public void mobDropsAndNetherStuffAreNotFetchedUpFront() {
        RecipeBill.Bill bill = RecipeBill.of(want("blaze_powder", 2, "book", 1), bag(), ALL_NEAR);
        assertTrue(names(bill.deferred).contains("blaze_rod"));
        assertTrue(names(bill.deferred).contains("leather"));
        assertFalse(names(bill.leaves).contains("blaze_rod"));
        assertFalse(names(bill.leaves).contains("leather"));
        // the paper's sugar cane is a plain mine, that one is fine
        assertEquals(List.of("sugar_cane"), names(bill.leaves));
        assertEquals(3, bill.need("sugar_cane"));
        // quartz is a plain mine too, but it's in the nether
        assertEquals(-1, new CatalogueBook(m -> 0).gatherTier("quartz"));
        assertEquals(-1, new CatalogueBook(m -> 0).gatherTier("blaze_rod"));
        assertEquals(2, new CatalogueBook(m -> 0).gatherTier("raw_iron"));
    }

    @Test
    public void nothingNetherIsFetchedUpFront() {
        CatalogueBook book = new CatalogueBook(m -> 0);
        assertTrue(TaskCatalogue.needsNether(Items.NETHERRACK));
        assertEquals(-1, book.gatherTier("netherrack"));
        // today every nether mine() also forces the dimension, so gatherRequirement says no first. this sweep is for the
        // day somebody adds a mine() that needsNether catches and forcesNether doesn't
        int checked = 0;
        for (String name : TaskCatalogue.resourceNames()) {
            Item[] matches = TaskCatalogue.getItemMatches(name);
            if (matches.length != 1 || !TaskCatalogue.needsNether(matches[0])) continue;
            checked++;
            assertEquals(name, -1, book.gatherTier(name));
        }
        assertTrue(checked > 10);
    }

    @Test
    public void aliasesFindTheRealRecipe() {
        assertEquals("iron_chain", CatalogueBook.canonical(new ItemTarget("chain", 1)));
        assertEquals("wooden_pickaxe", CatalogueBook.canonical(new ItemTarget("wooden_pick", 1)));
        assertEquals("planks", CatalogueBook.canonical(new ItemTarget("planks", 1)));
        assertEquals("iron_ingot", CatalogueBook.canonical(new ItemTarget(Items.IRON_INGOT, 1)));
        assertNull(CatalogueBook.canonical(new ItemTarget("not_a_thing_at_all", 1)));
    }

    @Test
    public void aLoopEnds() {
        RecipeBill.Book loop = new RecipeBill.Book() {
            @Override
            public RecipeBill.Way wayOf(String name) {
                return switch (name) {
                    case "nugget" -> new RecipeBill.Way(RecipeBill.Kind.CRAFT, Map.of("ingot", 1), 9);
                    case "ingot" -> new RecipeBill.Way(RecipeBill.Kind.CRAFT, Map.of("block", 1), 9);
                    case "block" -> new RecipeBill.Way(RecipeBill.Kind.CRAFT, Map.of("nugget", 81), 1);
                    default -> null;
                };
            }

            @Override
            public int gatherTier(String name) {
                return 0;
            }

            @Override
            public int held(String name) {
                return 0;
            }
        };
        RecipeBill.Bill bill = RecipeBill.of(want("nugget", 10), loop, ALL_NEAR);
        assertEquals(10, bill.need("nugget"));
        assertEquals(2, bill.need("ingot"));
        // the one that would close the loop is a leaf now
        assertNotNull(bill.node("block"));
        assertNull(bill.node("block").kind());
    }

    private static RecipeBill.Node leaf(String name, int gross, int held, int tier) {
        return new RecipeBill.Node(name, null, gross, held, 0, 0, tier);
    }

    private static RecipeBill.ToolBag tools(int pick, int axe, int shovel) {
        return new RecipeBill.ToolBag() {
            public int pickTier() {
                return pick;
            }

            public int axeTier() {
                return axe;
            }

            public int shovelTier() {
                return shovel;
            }
        };
    }

    private static RecipeBill.Node deferredLeaf(String name, int gross) {
        return new RecipeBill.Node(name, null, gross, 0, 0, 0, -1);
    }

    @Test
    public void toolsTheJobCallsFor() {
        RecipeBill.ToolBag none = tools(-1, -1, -1);
        List<RecipeBill.Node> nothing = List.of();
        assertEquals(List.of("wooden_axe"), RecipeBill.tools(List.of(leaf("log", 20, 0, 0)), nothing, none));
        // wood first even for a big pile, the stone one waits for a stone pick
        assertEquals(List.of("wooden_axe"), RecipeBill.tools(List.of(leaf("log", 40, 0, 0)), nothing, none));
        // 20 in the bill but 15 already held is 5 to chop, a fist does that
        assertEquals(List.of(), RecipeBill.tools(List.of(leaf("log", 20, 15, 0)), nothing, none));
        assertEquals(List.of(), RecipeBill.tools(List.of(leaf("sand", 10, 0, 0)), nothing, none));
        assertEquals(List.of("wooden_shovel"), RecipeBill.tools(List.of(leaf("dirt", 40, 0, 0)), nothing, none));
        // iron ore wants a stone pick, but the wooden one and the wooden axe come first, then all the logs
        assertEquals(List.of("wooden_pickaxe", "wooden_axe"),
                RecipeBill.tools(List.of(leaf("log", 20, 0, 0), leaf("raw_iron", 6, 0, 2)), nothing, none));
        // with those made (and the logs still out there) the stone pick is next, the wooden axe does
        assertEquals(List.of("stone_pickaxe"),
                RecipeBill.tools(List.of(leaf("log", 20, 0, 0), leaf("raw_iron", 6, 0, 2)), nothing, tools(1, 1, -1)));
        // a stone pick in the bag and logs left to chop: stone axe, even over a wooden one
        assertEquals(List.of("stone_axe"), RecipeBill.tools(List.of(leaf("log", 10, 0, 0)), nothing, tools(2, -1, -1)));
        assertEquals(List.of("stone_axe"), RecipeBill.tools(List.of(leaf("log", 10, 0, 0)), nothing, tools(2, 1, -1)));
        // and one that's good enough is left alone
        assertEquals(List.of(), RecipeBill.tools(List.of(leaf("log", 10, 0, 0), leaf("raw_iron", 6, 0, 2)), nothing, tools(3, 2, -1)));
        // cobble is a wooden pick and nothing else
        assertEquals(List.of("wooden_pickaxe"), RecipeBill.tools(List.of(leaf("cobblestone", 8, 0, 1)), nothing, none));
        // stripped logs are a tree job too, even though they aren't fetched up front
        assertEquals(List.of("wooden_axe"), RecipeBill.tools(nothing, List.of(deferredLeaf("spruce_stripped_logs", 57)), none));
        // the axe broke: the bag says none again and the same job asks for a new one
        assertEquals(List.of(), RecipeBill.tools(List.of(leaf("log", 40, 0, 0)), nothing, tools(0, 1, -1)));
        assertEquals(List.of("wooden_axe"), RecipeBill.tools(List.of(leaf("log", 40, 0, 0)), nothing, tools(0, -1, -1)));
    }

    @Test
    public void picksGoOneTierAtATime() {
        List<RecipeBill.Node> iron = List.of(leaf("log", 20, 0, 0), leaf("raw_iron", 6, 0, 2));
        assertEquals("wooden_pickaxe", RecipeBill.nextPick(iron, tools(-1, -1, -1)));
        assertEquals("stone_pickaxe", RecipeBill.nextPick(iron, tools(1, -1, -1)));
        assertNull(RecipeBill.nextPick(iron, tools(2, -1, -1)));
        // the ones after the wooden pick are made out of the fetched stuff, so they go on the bill
        assertEquals(List.of("stone_pickaxe"), RecipeBill.laterTools(iron, tools(-1, -1, -1)));
        assertEquals(List.of("stone_pickaxe"), RecipeBill.laterTools(iron, tools(1, -1, -1)));
        assertEquals(List.of(), RecipeBill.laterTools(iron, tools(2, -1, -1)));
        assertEquals(List.of("stone_pickaxe", "iron_pickaxe"), RecipeBill.laterTools(List.of(leaf("diamond", 3, 0, 3)), tools(-1, -1, -1)));
    }

    @Test
    public void aWornToolCountsAsGone() {
        // a wooden axe has 59 uses, ToolWear calls it gone at 85%
        assertEquals(-1, RecipeBill.usableTier(1, 55, 59));
        assertEquals(1, RecipeBill.usableTier(1, 10, 59));
        // something that doesn't wear never wears out
        assertEquals(2, RecipeBill.usableTier(2, 0, 0));
    }

    // a two-wood build with a bit of everything. ALL_NEAR so the counts are just the blocks
    private static Map<String, Integer> cabin() {
        return want("spruce_planks", 165, "birch_planks", 112, "spruce_stairs", 57, "spruce_stripped_logs", 57,
                "birch_stairs", 43, "birch_slab", 16, "glass_pane", 16, "grass", 12, "spruce_fence", 11, "spruce_trapdoor", 10,
                "spruce_slab", 6, "ladder", 4, "lantern", 4, "blast_furnace", 1, "crafting_table", 1, "furnace", 1,
                "smoker", 1, "spruce_door", 1);
    }

    @Test
    public void theCabinGetsAllItsLogsInOneGo() {
        RecipeBill.Bill bill = RecipeBill.of(cabin(), bag(), ALL_NEAR);
        // spruce planks: 165 + stairs 15 runs * 6 = 90 + slab 3 + fence 4 runs * 4 = 16 + trapdoor 5 runs * 6 = 30 + door 6 = 310.
        // the generic share lands on spruce: sticks 8 (fence) + 14 (2 ladder runs) + 1 (torch) = 23, 6 runs = 12 planks, and the
        // table is 4. 326 planks is 82 logs, plus the smoker's 4
        assertEquals(326, bill.need("spruce_planks"));
        assertEquals(86, bill.need("spruce_log"));
        // birch: 112 + stairs 11 runs * 6 = 66 + slab 3 runs * 3 = 9 = 187, 47 logs
        assertEquals(187, bill.need("birch_planks"));
        assertEquals(47, bill.need("birch_log"));
        // and nothing is left on the any-wood nodes for a craft to grab birch with
        assertEquals(0, bill.need("planks"));
        assertEquals(0, bill.need("log"));
        assertTrue(names(bill.leaves).contains("spruce_log"));
        assertTrue(names(bill.deferred).contains("spruce_stripped_logs"));
        // shears stuff stays the plain task's, it doesn't stop the rest from being billed
        assertTrue(names(bill.deferred).contains("grass"));
        // the schematic hands these over as items
        assertEquals("grass", CatalogueBook.canonical(new ItemTarget(Items.SHORT_GRASS, 12)));
        assertEquals("spruce_stripped_logs", CatalogueBook.canonical(new ItemTarget(Items.STRIPPED_SPRUCE_LOG, 57)));
        assertEquals("spruce", bill.mainWood());
        // the wood blocks have a way now, they used to be leaves nobody fetched for
        assertNotNull(bill.way("spruce_stairs"));
        assertNotNull(bill.way("spruce_trapdoor"));
    }

    @Test
    public void heldStairsComeOffThePlanksAndLogs() {
        RecipeBill.Bill bill = RecipeBill.of(cabin(), bag(Items.SPRUCE_STAIRS, 20), ALL_NEAR);
        // 37 stairs still to make is 10 runs, 60 planks instead of 90
        assertEquals(296, bill.need("spruce_planks"));
        assertEquals(78, bill.need("spruce_log"));
    }

    @Test
    public void theGenericShareGoesToTheMainWood() {
        // 8 oak stairs (12 planks) and 4 sticks (2 any planks): all oak, 14 planks is 4 logs
        RecipeBill.Bill bill = RecipeBill.of(want("oak_stairs", 8, "stick", 4), bag(), ALL_NEAR);
        assertEquals(14, bill.need("oak_planks"));
        assertEquals(4, bill.need("oak_log"));
        assertEquals(0, bill.need("planks"));
        // held oak planks net off the generic share too, there's just one node now
        assertEquals(1, RecipeBill.of(want("oak_stairs", 8, "stick", 4), bag(Items.OAK_PLANKS, 10), ALL_NEAR).need("oak_log"));
        // the most planks wins: 8 birch stairs beat 4 oak stairs
        RecipeBill.Bill two = RecipeBill.of(want("oak_stairs", 4, "birch_stairs", 8, "stick", 4), bag(), ALL_NEAR);
        assertEquals(14, two.need("birch_planks"));
        assertEquals(6, two.need("oak_planks"));
        // nether stems are a nether trip, the generic share doesn't go there
        RecipeBill.Bill nether = RecipeBill.of(want("crimson_stairs", 8, "stick", 4), bag(), ALL_NEAR);
        assertEquals(2, nether.need("planks"));
        // no wood named at all: generic like before
        assertEquals(2, RecipeBill.of(want("stick", 4), bag(), ALL_NEAR).need("planks"));
    }

    @Test
    public void aLatchedWoodKeepsTheGenericShare() {
        // birch is one plank ahead, so a fresh bill hands it the sticks
        Map<String, Integer> close = want("oak_planks", 12, "birch_planks", 13, "stick", 4);
        assertEquals("birch", RecipeBill.of(close, bag(), ALL_NEAR).mainWood());
        // but the job started on oak, and oak is still on the list: it stays oak
        RecipeBill.Bill latched = RecipeBill.of(close, bag(), ALL_NEAR, "oak");
        assertEquals("oak", latched.mainWood());
        assertEquals(14, latched.need("oak_planks"));
        assertEquals(13, latched.need("birch_planks"));
        assertEquals(0, latched.need("planks"));
        // oak stairs already crafted: the oak planks ask goes to 0 but the job still wants oak stairs, so oak keeps it
        Map<String, Integer> stairs = want("oak_stairs", 8, "birch_stairs", 9, "stick", 4);
        assertEquals("birch", RecipeBill.of(stairs, bag(Items.OAK_STAIRS, 8), ALL_NEAR).mainWood());
        assertEquals("oak", RecipeBill.of(stairs, bag(Items.OAK_STAIRS, 8), ALL_NEAR, "oak").mainWood());
        // a latch on a wood the job doesn't want anymore is dropped, the pick goes back to the biggest
        assertEquals("birch", RecipeBill.of(close, bag(), ALL_NEAR, "spruce").mainWood());
        // and a nether stem never takes it, latched or not
        assertEquals("birch", RecipeBill.of(want("crimson_planks", 20, "birch_planks", 13, "stick", 4), bag(), ALL_NEAR, "crimson").mainWood());
    }

    @Test
    public void consumersGoFirst() {
        RecipeBill.Bill bill = RecipeBill.of(cabin(), bag(), ALL_NEAR);
        List<String> order = RecipeBill.consumersFirst(bill, List.of("spruce_planks", "spruce_stairs", "spruce_fence", "crafting_table"));
        assertTrue(order.indexOf("spruce_stairs") < order.indexOf("spruce_planks"));
        // the fence eats sticks, which eat planks, so it's the furthest up
        assertEquals("spruce_fence", order.get(0));
        assertEquals("spruce_planks", order.get(order.size() - 1));
    }

    @Test
    public void theOtherWoodsPlanksWaitForTheAnyPlanksCrafts() {
        RecipeBill.Bill bill = RecipeBill.of(cabin(), bag(), ALL_NEAR);
        Set<String> made = new HashSet<>();
        // first the main wood's planks, birch waits: the sticks and the table would grab a full birch stack otherwise
        List<String> first = RecipeBill.nextBatch(bill, made);
        assertTrue(first.contains("spruce_planks"));
        assertFalse(first.contains("birch_planks"));
        made.addAll(first);
        List<String> second = RecipeBill.nextBatch(bill, made);
        assertTrue(second.contains("stick"));
        assertTrue(second.contains("crafting_table"));
        assertTrue(second.contains("birch_stairs"));
        made.addAll(second);
        // the sticks are made, now the birch planks go, and the smoker's any-log waits for them
        List<String> third = RecipeBill.nextBatch(bill, made);
        assertEquals(List.of("birch_planks"), third);
        made.addAll(third);
        boolean smoker = false;
        for (List<String> next = RecipeBill.nextBatch(bill, made); !next.isEmpty(); next = RecipeBill.nextBatch(bill, made)) {
            smoker |= next.contains("smoker");
            made.addAll(next);
        }
        assertTrue(smoker);
        // nothing redirected, nothing waits
        RecipeBill.Bill plain = RecipeBill.of(want("stick", 4, "crafting_table", 1), bag(), ALL_NEAR);
        assertTrue(RecipeBill.nextBatch(plain, Set.of()).contains("planks"));
    }

    @Test
    public void theBagKeepsRoomForTheFinalsAndThePlanks() {
        // 64 planks: 16 logs are a stack and so are the planks they turn into, nothing extra
        RecipeBill.Bill small = RecipeBill.of(want("spruce_planks", 64), bag(), ALL_NEAR);
        assertEquals(0, RecipeBill.reservedSlots(small, want("spruce_planks", 64), n -> 64));
        // 256: a stack of logs becomes four stacks of planks, 3 more slots
        RecipeBill.Bill big = RecipeBill.of(want("spruce_planks", 256), bag(), ALL_NEAR);
        assertEquals(3, RecipeBill.reservedSlots(big, want("spruce_planks", 256), n -> 64));
        // the cabin: 19 stacks of finals out of 8 of leaves is 11, more than the 6 the planks grow by (the three furnaces'
        // stone is a leaf of its own, the blast furnace's smooth stone is cobble)
        RecipeBill.Bill cabin = RecipeBill.of(cabin(), bag(), ALL_NEAR);
        int reserved = RecipeBill.reservedSlots(cabin, cabin(), n -> 64);
        assertEquals(11, reserved);
        // and a full bag of leaves still fits next to that in 36 slots
        assertTrue(RecipeBill.fitBag(cabin.leaves, 34 - reserved, n -> 64).size() == cabin.leaves.size());
    }

    @Test
    public void theBagCapCutsInLeafOrder() {
        List<RecipeBill.Node> leaves = List.of(leaf("log", 2, 0, 0), leaf("cobblestone", 200, 0, 1), leaf("raw_iron", 6, 0, 2));
        List<RecipeBill.Fetch> all = RecipeBill.fitBag(leaves, 10, n -> 64);
        assertEquals(3, all.size());
        assertEquals(200, all.get(1).target());
        // 3 slots: a log stack, then two stacks of cobble out of the four it wanted, and the iron waits
        List<RecipeBill.Fetch> cut = RecipeBill.fitBag(leaves, 3, n -> 64);
        assertEquals(2, cut.size());
        assertEquals("log", cut.get(0).name());
        assertEquals("cobblestone", cut.get(1).name());
        assertEquals(128, cut.get(1).target());
        // a half stack already held tops up into its own stack for free
        List<RecipeBill.Fetch> topUp = RecipeBill.fitBag(List.of(leaf("cobblestone", 60, 10, 1)), 0, n -> 64);
        assertEquals(60, topUp.get(0).target());
        assertTrue(RecipeBill.fitBag(leaves, 0, n -> 64).isEmpty());
    }

    @Test
    public void everyEdgeInputIsACatalogueName() {
        // an edge pointing at a name nobody registered would quietly turn into a deferred leaf
        for (String name : TaskCatalogue.resourceNames()) {
            TaskCatalogue.BillEdge edge = TaskCatalogue.billEdge(name);
            if (edge != null) {
                assertTrue(name + " <- " + edge.input(), TaskCatalogue.taskExists(edge.input()));
            }
        }
    }
}
