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
        int junk = junkPick(bag, reserve);
        if (junk >= 0) {
            return junk;
        }
        int tool = toolPick(bag);
        if (tool >= 0 || !throwUnused) {
            return tool;
        }
        return restPick(bag, foodUnits, foodFloor);
    }

    // by throwaway list order, bag order only breaks a tie. the old getJunkSlot walked the list item by item, and going by bag
    // order instead threw the cobble in the hotbar while the gravel sat in the main bag
    public static int junkPick(List<Entry> bag, int reserve) {
        int item = -1;
        int block = -1;
        int blocks = 0;
        for (int i = 0; i < bag.size(); i++) {
            Entry e = bag.get(i);
            if (!e.throwaway() || !e.mayGo()) {
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

    private static int restPick(List<Entry> bag, int foodUnits, int foodFloor) {
        List<Integer> at = new ArrayList<>();
        List<Cand> cands = new ArrayList<>();
        for (int i = 0; i < bag.size(); i++) {
            Entry e = bag.get(i);
            if (e.mayGo()) {
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
        if (pick < 0) {
            return false;
        }
        List<Entry> after = new ArrayList<>(bag);
        after.set(pick, drop);
        int next = nextPick(after, reserve, throwUnused, foodUnits, foodFloor);
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
