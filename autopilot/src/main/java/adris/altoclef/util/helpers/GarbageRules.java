package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// who gets thrown when the bag is full, as plain numbers so it can be tested without a game
public final class GarbageRules {

    // one throwable stack. keepUnits is what it counts for toward the food floor (0 for anything that is not plain food),
    // ownUnits is what it gives as it is, so raw meat goes before the cooked stack worth the same. spared is food we would
    // rather not throw (plain food, gapples, chorus fruit) even when it does not count toward the floor: rotten flesh is not.
    // reserved is the gapple: it is the thing that saves a run, so it never goes, floor or no floor
    public record Cand(boolean tool, int toolUses, int damage, int keepUnits, int ownUnits, int count, boolean spared, boolean reserved) {
    }

    private GarbageRules() {
    }

    // the worse of two tools of the same family, as an index into items (null = a slot we may not throw). first pair found
    // wins, same order the old slot loop went in. families are ItemHelper.toolKind, a pickaxe never loses to a sword
    public static int worseTool(List<Item> items) {
        java.util.Map<String, Integer> bestLevel = new java.util.HashMap<>();
        java.util.Map<String, Integer> bestAt = new java.util.HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            String kind = item == null ? null : ItemHelper.toolKind(item);
            if (kind == null) continue;
            int level = ItemHelper.getToolDurability(item);
            int prevBest = bestLevel.getOrDefault(kind, 0);
            if (level > prevBest) {
                // the one we saw before is worse
                if (bestAt.containsKey(kind)) {
                    return bestAt.get(kind);
                }
                bestLevel.put(kind, level);
                bestAt.put(kind, i);
            } else if (level < prevBest) {
                return i;
            }
        }
        return -1;
    }

    public static Cand cand(ItemStack stack) {
        Item item = stack.getItem();
        boolean tool = ItemHelper.isTool(item);
        FoodHelper.Kind kind = FoodHelper.kindOf(item);
        boolean spared = kind == FoodHelper.Kind.NORMAL || kind == FoodHelper.Kind.RESERVED || kind == FoodHelper.Kind.CHORUS_FRUIT;
        return new Cand(tool, tool ? ItemHelper.getToolDurability(item) : 0, stack.getDamageValue(), keepUnits(stack),
                FoodHelper.ownNutrition(item) * stack.getCount(), stack.getCount(), spared, kind == FoodHelper.Kind.RESERVED);
    }

    // same measure as GamerFacts.foodUnits: normal food only, raw meat at its cooked value, nutrition times count. the old sum
    // here added nutrition once per stack, so one stack of steak looked like 8 and a bag of 64 looked the same as a bag of 1
    public static int keepUnits(ItemStack stack) {
        if (stack.isEmpty() || FoodHelper.kindOf(stack.getItem()) != FoodHelper.Kind.NORMAL) {
            return 0;
        }
        return FoodHelper.plannedNutrition(stack.getItem()) * stack.getCount();
    }

    // food is only garbage when what is left without it still clears the floor. so a bag under the floor throws no food at all,
    // and over it only whole stacks that are pure surplus. a floor of 0 turns this off
    public static boolean keepsFood(int bagUnits, int stackUnits, int floor) {
        return stackUnits > 0 && bagUnits - stackUnits < floor;
    }

    // index of the stack to throw, -1 for none. non-food first (junk, then tools, worst first), food only after every one of
    // those and only when it is surplus, lowest units first
    public static int pick(List<Cand> cands, int bagUnits, int floor) {
        int best = -1;
        for (int i = 0; i < cands.size(); i++) {
            Cand c = cands.get(i);
            if (c.reserved() || keepsFood(bagUnits, c.keepUnits(), floor)) {
                continue;
            }
            if (best < 0 || compare(c, cands.get(best)) < 0) {
                best = i;
            }
        }
        return best;
    }

    // the window slots of the 36 real bag slots (hotbar and main) in whatever menu is open. the menu's own slot list also has the
    // craft result and the 2x2 grid with no screen up: the result clicked with something else on the cursor does nothing (the
    // cursor task clicked it forever), and a grid slot takes the stack only for the grid clearer to hand it right back.
    // isSlotInPlayerInventory says yes to all of those, and the inventory index mapping guesses wrong under any menu Slot has no
    // type for (a shulker box), so ask each slot whose it is
    public static List<Integer> bagWindowSlots(AbstractContainerMenu menu, Container inventory) {
        List<Integer> out = new ArrayList<>(Inventory.INVENTORY_SIZE);
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inventory && slot.getContainerSlot() < Inventory.INVENTORY_SIZE) {
                out.add(i);
            }
        }
        return out;
    }

    // one armor piece in the bag: its equip slot (-1 for anything that is not armor), what it gives, and wouldWear's say
    public record Armor(int equipSlot, double points, double toughness, boolean wouldWear) {
        public static final Armor NONE = new Armor(-1, 0, 0, false);
    }

    // of the pieces wouldWear likes, only the best one per equip slot is kept (first wins a tie). two iron helmets and no helmet
    // on: one goes on, the other is a spare like any other
    public static boolean[] keptArmor(List<Armor> pieces) {
        boolean[] kept = new boolean[pieces.size()];
        java.util.Map<Integer, Integer> best = new java.util.HashMap<>();
        for (int i = 0; i < pieces.size(); i++) {
            Armor a = pieces.get(i);
            if (a.equipSlot() < 0 || !a.wouldWear()) {
                continue;
            }
            Integer at = best.get(a.equipSlot());
            if (at == null || beats(a, pieces.get(at))) {
                best.put(a.equipSlot(), i);
            }
        }
        for (int i : best.values()) {
            kept[i] = true;
        }
        return kept;
    }

    private static boolean beats(Armor l, Armor r) {
        return l.points() != r.points() ? l.points() > r.points() : l.toughness() > r.toughness();
    }

    // one bag slot as the garbage pick sees it. mayGo is everything the game side asks first (canDropStack, not armor we would wear),
    // throwRank is where it sits on altoThrowawayItems (-1 = not on it). the list order is the junk order: gravel and sand sit
    // ahead of cobble so the blocks that fall on our head go first
    public record Entry(ItemStack stack, boolean mayGo, int throwRank) {
        public boolean throwaway() {
            return throwRank >= 0;
        }
    }

    // item -> first spot on the throwaway list, what Entry.throwRank reads from
    public static java.util.Map<Item, Integer> throwRanks(Item[] throwaways) {
        java.util.Map<Item, Integer> out = new java.util.HashMap<>();
        for (int i = 0; i < throwaways.length; i++) {
            out.putIfAbsent(throwaways[i], i);
        }
        return out;
    }

    // the whole garbage pick in one place, index into bag or -1: junk (a throwaway that is not a block, else the first throwaway
    // block once they are past the building reserve), then the worse of two tools of a family, then with throwUnused anything by
    // pick's order. StorageHelper used to do this over live slots, here it can also be asked about a bag that does not exist yet
    public static int nextPick(List<Entry> bag, int reserve, boolean throwUnused, int foodUnits, int foodFloor) {
        return nextPick(bag, reserve, throwUnused, foodUnits, foodFloor, item -> -1);
    }

    // (Tuning bag_junk_first, a fix of 13 Nov 2026, on) the same with a tier ahead of all the rest: what the deep mine fills the
    // bag with and no order of the buddy's wants (junkFirst's rank, -1 for anything else), whatever the building reserve says.
    // with the old order a full bag threw the bed, then the furnace (one of each, the smallest stacks of the "anything" tier)
    // while thirty raw copper and a stack of cinnabar stayed: M3.3's smoke 5, run 3
    public static int nextPick(List<Entry> bag, int reserve, boolean throwUnused, int foodUnits, int foodFloor,
                               java.util.function.ToIntFunction<Item> junkFirst) {
        return nextPick(bag, reserve, throwUnused, foodUnits, foodFloor, junkFirst, null);
    }

    // (Tuning bag_kit_last, a fix of 14 Nov 2026, on) the same with the kit kept last: `kit` names what the buddy's kit is made
    // of (kitItem), null for the old order. no kit stack goes while anything else may: the throwaway blocks past the reserve
    // without it, then the rest that is no kit, then the worse of two tools (never a pickaxe above wood, the kit's), and only
    // then anything by pick's order. a full bag in the deep mine threw the glass, then the empty bucket, with moss, basalt and
    // calcite in it, and the bottles of M3.4 could not be made (test heel, look 89)
    public static int nextPick(List<Entry> bag, int reserve, boolean throwUnused, int foodUnits, int foodFloor,
                               java.util.function.ToIntFunction<Item> junkFirst, java.util.function.Predicate<Item> kit) {
        int first = junkFirstPick(bag, junkFirst);
        if (first >= 0) {
            return first;
        }
        int junk = junkPick(bag, reserve, kit);
        if (junk >= 0) {
            return junk;
        }
        if (kit != null) {
            if (throwUnused) {
                int rest = restPick(bag, foodUnits, foodFloor, kit.negate());
                if (rest >= 0) {
                    return rest;
                }
            }
            int tool = worseToolKept(bag);
            if (tool >= 0 || !throwUnused) {
                return tool;
            }
            return restPick(bag, foodUnits, foodFloor, null);
        }
        int tool = toolPick(bag);
        if (tool >= 0 || !throwUnused) {
            return tool;
        }
        return restPick(bag, foodUnits, foodFloor, null);
    }

    // the junk-first tier: the stack of the lowest rank that may go, bag order breaking a tie; -1 for none
    static int junkFirstPick(List<Entry> bag, java.util.function.ToIntFunction<Item> junkFirst) {
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < bag.size(); i++) {
            Entry e = bag.get(i);
            if (!e.mayGo() || e.stack().isEmpty()) {
                continue;
            }
            int rank = junkFirst.applyAsInt(e.stack().getItem());
            if (rank >= 0 && rank < bestRank) {
                best = i;
                bestRank = rank;
            }
        }
        return best;
    }

    // (bag_junk_first) the deep mine's leftovers, in the order they go: the two stones of the deep that are no building block
    // here (cinnabar and sulfur), clay, bone, and raw copper (the copper armour is made before the deep, M2.1). -1 for anything else
    public static int junkFirstRank(Item item) {
        if (item == net.minecraft.world.item.Items.CINNABAR) return 0;
        if (item == net.minecraft.world.item.Items.SULFUR) return 1;
        if (item == net.minecraft.world.item.Items.CLAY_BALL) return 2;
        if (item == net.minecraft.world.item.Items.BONE) return 3;
        if (item == net.minecraft.world.item.Items.RAW_COPPER) return 4;
        return -1;
    }

    // the junk-first tier the game side goes by: junkFirstRank with bag_junk_first on, junkWideRank after it with bag_kit_last on,
    // nothing with both off
    public static java.util.function.ToIntFunction<Item> junkFirstNow() {
        boolean first = Tuning.get("bag_junk_first", 0) > 0;
        boolean wide = kitLastOn();
        if (first && wide) return GarbageRules::junkAllRank;
        if (first) return GarbageRules::junkFirstRank;
        if (wide) return GarbageRules::junkWideRank;
        return item -> -1;
    }

    // (Tuning bag_kit_last, a fix of 14 Nov 2026, on) the natural junk a mine or a walk fills the bag with and no piece of the
    // route asks for, ranked after junkFirstRank's: plants and carpets, seeds and bits, moss, then the stones that are no core
    // building block (cobblestone, cobbled deepslate, dirt and netherrack keep the building reserve's rules, junkPick). -1 for
    // anything else
    public static int junkWideRank(Item item) {
        Integer rank = WIDE_JUNK.get(item);
        return rank == null ? -1 : rank;
    }

    // junkFirstRank, then junkWideRank
    public static int junkAllRank(Item item) {
        int first = junkFirstRank(item);
        return first >= 0 ? first : junkWideRank(item);
    }

    private static final java.util.Map<Item, Integer> WIDE_JUNK = wideJunk();

    private static java.util.Map<Item, Integer> wideJunk() {
        java.util.Map<Item, Integer> out = new java.util.HashMap<>();
        Item[][] tiers = {
                // plants, carpets and what grows on stone
                {Items.MOSS_CARPET, Items.PALE_MOSS_CARPET, Items.DANDELION, Items.POPPY, Items.BLUE_ORCHID, Items.ALLIUM,
                        Items.AZURE_BLUET, Items.RED_TULIP, Items.ORANGE_TULIP, Items.WHITE_TULIP, Items.PINK_TULIP, Items.OXEYE_DAISY,
                        Items.CORNFLOWER, Items.LILY_OF_THE_VALLEY, Items.TORCHFLOWER, Items.OPEN_EYEBLOSSOM, Items.CLOSED_EYEBLOSSOM,
                        Items.WILDFLOWERS, Items.LEAF_LITTER, Items.SHORT_GRASS, Items.FERN, Items.DEAD_BUSH, Items.BUSH, Items.FIREFLY_BUSH,
                        Items.AZALEA, Items.FLOWERING_AZALEA, Items.SMALL_DRIPLEAF, Items.BIG_DRIPLEAF, Items.HANGING_ROOTS,
                        Items.SPORE_BLOSSOM, Items.GLOW_LICHEN, Items.SCULK_VEIN, Items.VINE, Items.LILY_PAD, Items.SEAGRASS,
                        Items.PALE_HANGING_MOSS, Items.COBWEB},
                // seeds and small bits
                {Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.PUMPKIN_SEEDS, Items.MELON_SEEDS, Items.TORCHFLOWER_SEEDS,
                        Items.PITCHER_POD, Items.POINTED_DRIPSTONE, Items.AMETHYST_SHARD},
                {Items.MOSS_BLOCK, Items.PALE_MOSS_BLOCK},
                {Items.CALCITE},
                {Items.BASALT, Items.SMOOTH_BASALT},
                {Items.TUFF},
                {Items.DIORITE, Items.ANDESITE, Items.GRANITE},
                {Items.DRIPSTONE_BLOCK, Items.SCULK},
                {Items.ROOTED_DIRT, Items.COARSE_DIRT, Items.MUD, Items.CLAY},
                {Items.GRAVEL}};
        // after junkFirstRank's five (0 to 4)
        for (int t = 0; t < tiers.length; t++) {
            for (Item item : tiers[t]) {
                out.putIfAbsent(item, 5 + t);
            }
        }
        return out;
    }

    public static boolean kitLastOn() {
        return Tuning.get("bag_kit_last", 0) > 0;
    }

    // the kit predicate the game side goes by: kitItem with bag_kit_last on, null (the old order) with it off
    public static java.util.function.Predicate<Item> kitNow() {
        return kitLastOn() ? GarbageRules::kitItem : null;
    }

    // (bag_kit_last) what the buddy's kit is made of: tools and armour, the bucket, glass and bottles, the bed and the stations,
    // wood (logs, planks, sticks), food it would eat, fuel and torches, iron, gold, obsidian, diamonds, flint, sand for the glass,
    // the brew's makings (redstone, a slime ball, wart, cream, sugar, rods, powder) and the end's pearls and eyes
    public static boolean kitItem(Item item) {
        if (item == null || item == Items.AIR) {
            return false;
        }
        if (KIT.contains(item) || DropRoom.isBed(item) || in(item, ItemHelper.LOG) || in(item, ItemHelper.PLANKS)) {
            return true;
        }
        if (ItemHelper.isTool(item) || ItemHelper.isArmor(item)) {
            return true;
        }
        FoodHelper.Kind food = FoodHelper.kindOf(item);
        return food == FoodHelper.Kind.NORMAL || food == FoodHelper.Kind.RESERVED;
    }

    private static final java.util.Set<Item> KIT = java.util.Set.of(Items.BUCKET, Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.GLASS,
            Items.GLASS_BOTTLE, Items.POTION, Items.SPLASH_POTION, Items.CRAFTING_TABLE, Items.FURNACE, Items.SMOKER, Items.STICK,
            Items.FLINT, Items.FLINT_AND_STEEL, Items.SHIELD, Items.BOW, Items.CROSSBOW, Items.ARROW, Items.SHEARS, Items.TORCH,
            Items.COAL, Items.CHARCOAL, Items.IRON_INGOT, Items.RAW_IRON, Items.IRON_NUGGET, Items.GOLD_INGOT, Items.RAW_GOLD,
            Items.GOLD_NUGGET, Items.GOLD_BLOCK, Items.RAW_GOLD_BLOCK, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.OBSIDIAN,
            Items.DIAMOND, Items.REDSTONE, Items.SLIME_BALL, Items.BLAZE_ROD, Items.BLAZE_POWDER, Items.NETHER_WART, Items.MAGMA_CREAM,
            Items.SUGAR, Items.BREWING_STAND, Items.CAULDRON, Items.ENDER_PEARL, Items.ENDER_EYE, Items.SAND, Items.OAK_BOAT);

    private static boolean in(Item item, Item[] group) {
        for (Item i : group) {
            if (i == item) return true;
        }
        return false;
    }

    // a pickaxe better than a wooden one: the kit's (two of iron or better and the diamond one) or the stone ones the deep
    // mine makes for its rock. never the worse of two tools with bag_kit_last on (worseToolKept)
    static boolean aboveWoodPick(Item item) {
        return "pickaxe".equals(ItemHelper.toolKind(item)) && item != Items.WOODEN_PICKAXE;
    }

    // (Tuning bag_keep_stations, a fix of 13 Nov 2026, on) what a full bag never throws: a bed (the night's sleep) and the stations
    // the deep stretch smelts and crafts with. one of each was the smallest stack of the "anything" tier, so they went first:
    // the bed, then 15 minutes in a hole for the night; the furnace, then no gold smelted (M3.3's smoke 5)
    public static boolean station(Item item) {
        return item == net.minecraft.world.item.Items.FURNACE || item == net.minecraft.world.item.Items.SMOKER
                || item == net.minecraft.world.item.Items.CRAFTING_TABLE || DropRoom.isBed(item);
    }

    public static boolean keepStationsOn() {
        return Tuning.get("bag_keep_stations", 0) > 0;
    }

    // by throwaway list order, bag order only breaks a tie. the old getJunkSlot walked the list item by item, and going by bag
    // order instead threw the cobble in the hotbar while the gravel sat in the main bag
    public static int junkPick(List<Entry> bag, int reserve) {
        return junkPick(bag, reserve, null);
    }

    // (bag_kit_last) a kit item on the throwaway list (sand, the bottles' glass) is no junk here: kit null for the old pick
    public static int junkPick(List<Entry> bag, int reserve, java.util.function.Predicate<Item> kit) {
        int item = -1;
        int block = -1;
        int blocks = 0;
        for (int i = 0; i < bag.size(); i++) {
            Entry e = bag.get(i);
            if (!e.throwaway() || !e.mayGo() || (kit != null && kit.test(e.stack().getItem()))) {
                continue;
            }
            if (e.stack().getItem() instanceof BlockItem) {
                blocks += e.stack().getCount();
                block = earlierRank(bag, block, i);
            } else {
                // not a block, so it is no use for building either, it goes no matter the reserve
                item = earlierRank(bag, item, i);
            }
        }
        if (item >= 0) {
            return item;
        }
        return blocks > reserve ? block : -1;
    }

    // a core build block is not garbage while the bag is at or under the reserve, the fragments go first. the stack-level
    // twin of what junkPick does with the block total
    public static boolean protectsCore(boolean core, int buildingBlocks, int reserve) {
        return core && buildingBlocks <= reserve;
    }

    private static int earlierRank(List<Entry> bag, int best, int i) {
        return best < 0 || bag.get(i).throwRank() < bag.get(best).throwRank() ? i : best;
    }

    private static int toolPick(List<Entry> bag) {
        List<Integer> at = new ArrayList<>();
        List<Item> tools = new ArrayList<>();
        for (int i = 0; i < bag.size(); i++) {
            Entry e = bag.get(i);
            if (e.mayGo() && ItemHelper.isTool(e.stack().getItem())) {
                at.add(i);
                tools.add(e.stack().getItem());
            }
        }
        int worse = worseTool(tools);
        return worse < 0 ? -1 : at.get(worse);
    }

    // (bag_kit_last) the worse of two tools of a family, every tool that may go weighed for the family's best, but never a pickaxe
    // above wood as the one that goes; the worst first, bag order breaking a tie. -1 for none
    static int worseToolKept(List<Entry> bag) {
        java.util.Map<String, Integer> best = new java.util.HashMap<>();
        for (Entry e : bag) {
            Item item = e.stack().getItem();
            String kind = e.mayGo() && !e.stack().isEmpty() ? ItemHelper.toolKind(item) : null;
            if (kind != null) best.merge(kind, ItemHelper.getToolDurability(item), Math::max);
        }
        int pick = -1;
        int pickLevel = Integer.MAX_VALUE;
        for (int i = 0; i < bag.size(); i++) {
            Entry e = bag.get(i);
            Item item = e.stack().getItem();
            String kind = e.mayGo() && !e.stack().isEmpty() ? ItemHelper.toolKind(item) : null;
            if (kind == null || aboveWoodPick(item)) continue;
            int level = ItemHelper.getToolDurability(item);
            if (level < best.get(kind) && level < pickLevel) {
                pick = i;
                pickLevel = level;
            }
        }
        return pick;
    }

    private static int restPick(List<Entry> bag, int foodUnits, int foodFloor, java.util.function.Predicate<Item> may) {
        List<Integer> at = new ArrayList<>();
        List<Cand> cands = new ArrayList<>();
        for (int i = 0; i < bag.size(); i++) {
            Entry e = bag.get(i);
            // (may null: every stack that may go, the old pick; with a filter an empty slot is none)
            if (e.mayGo() && (may == null || !e.stack().isEmpty() && may.test(e.stack().getItem()))) {
                at.add(i);
                cands.add(cand(e.stack()));
            }
        }
        int pick = pick(cands, foodUnits, foodFloor);
        return pick < 0 ? -1 : at.get(pick);
    }

    // a full bag, and the pick at `pick` gets thrown so `drop` can come in. would the drop be the next thing out? it would if the
    // same pick lands on it, or on the same item somewhere else (another netherrack stack). this is the whole pick run again with
    // the drop in the pick's slot, so a stone sword in the bag makes a wooden sword drop the next garbage even while the current
    // pick is a string in the "anything" tier. checking tiers one at a time missed that and the two took turns on the floor
    public static boolean dropIsNextPick(List<Entry> bag, int pick, Entry drop, int reserve, boolean throwUnused, int foodUnits, int foodFloor) {
        return dropIsNextPick(bag, pick, drop, reserve, throwUnused, foodUnits, foodFloor, item -> -1);
    }

    // the same with the junk-first tier (bag_junk_first)
    public static boolean dropIsNextPick(List<Entry> bag, int pick, Entry drop, int reserve, boolean throwUnused, int foodUnits, int foodFloor,
                                         java.util.function.ToIntFunction<Item> junkFirst) {
        return dropIsNextPick(bag, pick, drop, reserve, throwUnused, foodUnits, foodFloor, junkFirst, null);
    }

    // the same with the kit kept last (bag_kit_last)
    public static boolean dropIsNextPick(List<Entry> bag, int pick, Entry drop, int reserve, boolean throwUnused, int foodUnits, int foodFloor,
                                         java.util.function.ToIntFunction<Item> junkFirst, java.util.function.Predicate<Item> kit) {
        if (pick < 0) {
            return false;
        }
        List<Entry> after = new ArrayList<>(bag);
        after.set(pick, drop);
        int next = nextPick(after, reserve, throwUnused, foodUnits, foodFloor, junkFirst, kit);
        return next == pick || (next >= 0 && after.get(next).stack().getItem() == drop.stack().getItem());
    }

    // the food total once `thrown` is on the floor and `drop` is in. what dropIsNextPick wants for foodUnits: the bag total
    // as it is still counts the stack that is about to go, and 2 bread off the pile looked like food we had to keep
    public static int unitsAfterSwap(int foodUnits, ItemStack thrown, ItemStack drop) {
        return foodUnits - keepUnits(thrown) + keepUnits(drop);
    }

    // a bag piece we would put on is not garbage: its slot is bare, or it beats what is on it (points first, toughness breaks a
    // tie). an unworn single piece always won "smallest count" below, so the iron chestplate fresh off the pile was the first
    // thing out the door. a spare (what is worn is as good or better) can still go
    public static boolean wouldWear(boolean slotBare, double bagPoints, double bagToughness, double wornPoints, double wornToughness) {
        if (slotBare) {
            return true;
        }
        if (bagPoints != wornPoints) {
            return bagPoints > wornPoints;
        }
        return bagToughness > wornToughness;
    }

    // the cursor holds something we keep, nothing can be thrown and nowhere takes it. the stack that goes in its place is the
    // lowest one by the usual order, only now the food floor does not get a say (the cands are already free of the important
    // list). this one is the nothing-guarded view, the protect list gets its say in the overload below. -1 = nothing to swap
    // with, throw the cursor, it is that or click forever
    public static int lastResort(List<Cand> cands) {
        return lastResort(cands, new boolean[cands.size()]);
    }

    // guarded[i] = that stack is one the bot is holding on to right now (protect list, enchanted, torches with autoTorch).
    // a water bucket or a crafting table is one item, so it always won "smallest count" and the one thing we were saving
    // went over the edge. an unguarded stack goes first, the guarded ones only when the bag is nothing but
    public static int lastResort(List<Cand> cands, boolean[] guarded) {
        int best = lastResort(cands, guarded, true);
        return best >= 0 ? best : lastResort(cands, guarded, false);
    }

    private static int lastResort(List<Cand> cands, boolean[] guarded, boolean skipGuarded) {
        int best = -1;
        for (int i = 0; i < cands.size(); i++) {
            Cand c = cands.get(i);
            if (c.reserved() || (skipGuarded && guarded[i])) {
                continue;
            }
            if (best < 0 || compare(c, cands.get(best)) < 0) {
                best = i;
            }
        }
        return best;
    }

    private static int rank(Cand c) {
        if (c.spared()) {
            return 2;
        }
        return c.tool() ? 1 : 0;
    }

    private static int compare(Cand l, Cand r) {
        int byRank = Integer.compare(rank(l), rank(r));
        if (byRank != 0) {
            return byRank;
        }
        if (l.tool()) {
            // material first, then durability
            if (l.toolUses() != r.toolUses()) {
                return l.toolUses() - r.toolUses();
            }
            return l.damage() - r.damage();
        }
        if (l.spared()) {
            // the floor measure first, so 8 raw beef (64 planned) does not go before 4 cooked (32). what it gives as it is only
            // breaks a tie, which is where raw goes before the cooked stack worth the same. a gapple has no floor units, its own
            // hunger stands in so it is not thrown ahead of every steak
            int lv = Math.max(l.keepUnits(), l.ownUnits());
            int rv = Math.max(r.keepUnits(), r.ownUnits());
            if (lv != rv) {
                return lv - rv;
            }
            return l.ownUnits() - r.ownUnits();
        }
        // just discard the one with the smallest quantity, but this doesn't really matter
        return l.count() - r.count();
    }
}
