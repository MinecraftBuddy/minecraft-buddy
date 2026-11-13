package adris.altoclef.util.helpers;

import baritone.Baritone;
import adris.altoclef.AltoSettings;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import baritone.utils.accessor.IAbstractFurnaceMenu;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.speedrun.gamer.end.EndGear;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.CursorSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import baritone.utils.ToolSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.ArrayUtils;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Helper functions for interpreting containers/slots/windows/inventory
 */
@SuppressWarnings({"ConstantConditions", "rawtypes"})
public class StorageHelper {

    public static List<PlayerSlot> INACCESSIBLE_PLAYER_SLOTS = Stream.concat(Stream.of(PlayerSlot.CRAFT_INPUT_SLOTS), Stream.of(PlayerSlot.ARMOR_SLOTS)).toList();
    private static final List<PlayerSlot> WORN_SLOTS = List.of(PlayerSlot.ARMOR_SLOTS);

    public static void closeScreen() {
        if (Minecraft.getInstance().player == null)
            return;
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen == null) {
            closeStaleMenu(Minecraft.getInstance().player);
            return;
        }
        if (
                !(screen instanceof PauseScreen) &&
                        !(screen instanceof OptionsSubScreen) &&
                        !(screen instanceof ChatScreen)) {
            // Close the screen if we're in-game
            Minecraft.getInstance().player.closeContainer();
        }
    }

    private static long _lastStaleMenuLog;

    // a menu with no screen over it. swapping the screen out (GuiClick, the click gui, MoveScreen, the death menu) never
    // closes the menu, so it sticks around holding whatever was in its slots, and every "is a table open" check reads
    // containerMenu, not the screen. closing used to wait for a screen that was never coming back, forever.
    // closeContainer sends the close packet (the server hands the grid back to the bag) and resets containerMenu to the
    // inventory, its setScreen(null) with nothing up is harmless. not while dead, setScreen(null) would open the death screen
    private static void closeStaleMenu(LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu || Minecraft.getInstance().level == null || player.isDeadOrDying())
            return;
        long now = System.currentTimeMillis();
        if (now - _lastStaleMenuLog > 5000) {
            _lastStaleMenuLog = now;
            Debug.logInternal("closing stale menu " + menu.getClass().getSimpleName() + " id " + menu.containerId);
        }
        player.closeContainer();
    }

    public static ItemStack getItemStackInSlot(Slot slot) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null)
            return ItemStack.EMPTY;
        // Cursor slot
        if (Slot.isCursor(slot)) {
            return StorageHelper.getItemStackInCursorSlot();
        }
        // Inventory slot when inventory is NOT open
        Inventory inv = player.getInventory();
        if (inv != null) {
            if (slot.equals(PlayerSlot.OFFHAND_SLOT))
                return player.getItemBySlot(EquipmentSlot.OFFHAND);
            if (slot.equals(PlayerSlot.ARMOR_HELMET_SLOT))
                return player.getItemBySlot(EquipmentSlot.HEAD);
            if (slot.equals(PlayerSlot.ARMOR_CHESTPLATE_SLOT))
                return player.getItemBySlot(EquipmentSlot.CHEST);
            if (slot.equals(PlayerSlot.ARMOR_LEGGINGS_SLOT))
                return player.getItemBySlot(EquipmentSlot.LEGS);
            if (slot.equals(PlayerSlot.ARMOR_BOOTS_SLOT))
                return player.getItemBySlot(EquipmentSlot.FEET);
        }
        try {
            // We might have messed up and opened the wrong slot.
            net.minecraft.world.inventory.Slot mcSlot = player.containerMenu.getSlot(slot.getWindowSlot());
            return (mcSlot != null) ? mcSlot.getItem() : ItemStack.EMPTY;
        } catch (Exception e) {
            Debug.logWarning("Screen Slot Error (ignored)");
            e.printStackTrace();
            return ItemStack.EMPTY;
        }
    }

    public static MiningRequirement getCurrentMiningRequirement(AltoClef mod) {
        MiningRequirement[] order = new MiningRequirement[]{
                MiningRequirement.DIAMOND, MiningRequirement.IRON, MiningRequirement.STONE, MiningRequirement.WOOD
        };
        for (MiningRequirement check : order) {
            if (miningRequirementMet(mod, check)) {
                return check;
            }
        }
        return MiningRequirement.HAND;
    }

    private static boolean h(AltoClef mod, boolean inventoryOnly, Item... items) {
        if (inventoryOnly) {
            return mod.getItemStorage().hasItemInventoryOnly(items);
        }
        return mod.getItemStorage().hasItem(items);
    }

    private static boolean miningRequirementMetInner(AltoClef mod, boolean inventoryOnly, MiningRequirement requirement) {
        switch (requirement) {
            case HAND:
                return true;
            // the buddy's fork: a copper pickaxe is of the stone tier in 26.x, and a golden one of the wooden tier only (it mines no
            // iron ore, it was counted for the stone and the iron tier)
            case WOOD:
                return h(mod, inventoryOnly, Items.WOODEN_PICKAXE) || h(mod, inventoryOnly, Items.STONE_PICKAXE) || h(mod, inventoryOnly, Items.COPPER_PICKAXE) || h(mod, inventoryOnly, Items.IRON_PICKAXE) || h(mod, inventoryOnly, Items.GOLDEN_PICKAXE) || h(mod, inventoryOnly, Items.DIAMOND_PICKAXE) || h(mod, inventoryOnly, Items.NETHERITE_PICKAXE);
            case STONE:
                return h(mod, inventoryOnly, Items.STONE_PICKAXE) || h(mod, inventoryOnly, Items.COPPER_PICKAXE) || h(mod, inventoryOnly, Items.IRON_PICKAXE) || h(mod, inventoryOnly, Items.DIAMOND_PICKAXE) || h(mod, inventoryOnly, Items.NETHERITE_PICKAXE);
            case IRON:
                return h(mod, inventoryOnly, Items.IRON_PICKAXE) || h(mod, inventoryOnly, Items.DIAMOND_PICKAXE) || h(mod, inventoryOnly, Items.NETHERITE_PICKAXE);
            case DIAMOND:
                return h(mod, inventoryOnly, Items.DIAMOND_PICKAXE) || h(mod, inventoryOnly, Items.NETHERITE_PICKAXE);
            default:
                Debug.logError("You missed a spot");
                return false;
        }
    }

    public static boolean miningRequirementMet(AltoClef mod, MiningRequirement requirement) {
        return miningRequirementMetInner(mod, false, requirement);
    }

    public static boolean miningRequirementMetInventory(AltoClef mod, MiningRequirement requirement) {
        return miningRequirementMetInner(mod, true, requirement);
    }

    public static Optional<Slot> getBestToolSlot(AltoClef mod, BlockState state) {
        // TODO: mod.configState.silkTouchOverrideMode {
        //      DONT_CARE (Default)
        //      PREFER (Always use silk touch if we have)
        //      AVOID  (Don't use silk touch if we can)
        //  }
        if (Slot.getCurrentScreenSlots() == null) {
            return Optional.empty();
        }
        // the choosing is ToolPick's (pure, tested), this is only the slot bookkeeping
        List<Slot> slots = new ArrayList<>();
        List<ItemStack> stacks = new ArrayList<>();
        for (Slot slot : Slot.getCurrentScreenSlots()) {
            if (!slot.isSlotInPlayerInventory())
                continue;
            slots.add(slot);
            stacks.add(getItemStackInSlot(slot));
        }
        int best = ToolPick.best(stacks, state);
        return best < 0 ? Optional.empty() : Optional.of(slots.get(best));
    }

    // food we hold, counted the way the gamer counts it (GarbageRules.keepUnits): the bag, the offhand and the cursor. a held stack
    // is not in the bag, so it has to be added or the stack on the cursor would never count toward its own floor
    public static int bagFoodUnits(AltoClef mod) {
        if (mod.getPlayer() == null) {
            return 0;
        }
        Inventory inv = mod.getPlayer().getInventory();
        int sum = GarbageRules.keepUnits(getItemStackInCursorSlot());
        for (int i = 0; i < inv.getContainerSize(); i++) {
            sum += GarbageRules.keepUnits(inv.getItem(i));
        }
        return sum;
    }

    // Gets a slot with an item we can throw away. the order lives in GarbageRules.nextPick so a test can lean on it
    public static Optional<Slot> getGarbageSlot(AltoClef mod) {
        Bag bag = bag(mod);
        int units = bagFoodUnits(mod);
        return at(bag, GarbageRules.nextPick(entries(mod, bag, units), reserve(), Baritone.settings().altoThrowAwayUnusedItems.value,
                units, Baritone.settings().altoFoodKeepUnits.value, GarbageRules.junkFirstNow()));
    }

    // one look at the bag: its slots, what is in them (read off the menu slot itself: under a menu Slot has no type for, window 45
    // is a bag slot and getItemStackInSlot would hand back the offhand), and which armor pieces are the ones we would put on
    private record Bag(List<Slot> slots, List<ItemStack> stacks, boolean[] wear) {
    }

    private static Bag bag(AltoClef mod) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return new Bag(List.of(), List.of(), new boolean[0]);
        }
        AbstractContainerMenu menu = player.containerMenu;
        List<Slot> slots = new ArrayList<>();
        List<ItemStack> stacks = new ArrayList<>();
        List<GarbageRules.Armor> armor = new ArrayList<>();
        for (int window : GarbageRules.bagWindowSlots(menu, player.getInventory())) {
            Slot slot = Slot.getFromCurrentScreen(window);
            ItemStack stack = menu.getSlot(window).getItem();
            slots.add(slot);
            stacks.add(stack);
            armor.add(armorOf(mod, stack));
        }
        return new Bag(slots, stacks, GarbageRules.keptArmor(armor));
    }

    private static GarbageRules.Armor armorOf(AltoClef mod, ItemStack stack) {
        if (stack.isEmpty() || !ItemHelper.isArmor(stack.getItem())) {
            return GarbageRules.Armor.NONE;
        }
        EquipmentSlot slot = ItemHelper.getEquipSlot(stack.getItem());
        if (slot == null) {
            return GarbageRules.Armor.NONE;
        }
        return new GarbageRules.Armor(slot.ordinal(), ItemHelper.getArmorPoints(stack.getItem()), ItemHelper.getArmorToughness(stack.getItem()),
                wouldWear(mod, stack));
    }

    // armor we would put on (bare slot, or it beats what is worn), the rule is GarbageRules.wouldWear. this one asks about a single
    // stack (the cursor, a drop), the garbage tiers go through bag() so a second identical piece still counts as a spare
    public static boolean wouldWear(AltoClef mod, ItemStack stack) {
        if (stack.isEmpty() || mod.getPlayer() == null || !ItemHelper.isArmor(stack.getItem())) {
            return false;
        }
        EquipmentSlot slot = ItemHelper.getEquipSlot(stack.getItem());
        if (slot == null) {
            return false;
        }
        ItemStack worn = mod.getPlayer().getItemBySlot(slot);
        return GarbageRules.wouldWear(worn.isEmpty(), ItemHelper.getArmorPoints(stack.getItem()), ItemHelper.getArmorToughness(stack.getItem()),
                ItemHelper.getArmorPoints(worn.getItem()), ItemHelper.getArmorToughness(worn.getItem()));
    }

    // what every garbage tier asks of a bag stack. foodUnits is the bag total the food floor is held against
    private static boolean mayGo(AltoClef mod, Bag bag, int i, int foodUnits) {
        return !bag.wear[i] && ItemHelper.canDropStack(mod, bag.stacks.get(i), foodUnits);
    }

    private static List<GarbageRules.Entry> entries(AltoClef mod, Bag bag, int foodUnits) {
        return entries(mod, bag, foodUnits, GarbageRules.throwRanks(AltoSettings.getThrowawayItems(mod)));
    }

    private static List<GarbageRules.Entry> entries(AltoClef mod, Bag bag, int foodUnits, Map<Item, Integer> ranks) {
        List<GarbageRules.Entry> out = new ArrayList<>(bag.slots.size());
        for (int i = 0; i < bag.slots.size(); i++) {
            ItemStack stack = bag.stacks.get(i);
            out.add(new GarbageRules.Entry(stack, mayGo(mod, bag, i, foodUnits), ranks.getOrDefault(stack.getItem(), -1)));
        }
        return out;
    }

    private static int reserve() {
        return Baritone.settings().altoReservedBuildingBlockCount.value;
    }

    private static Optional<Slot> at(Bag bag, int i) {
        return i < 0 ? Optional.empty() : Optional.of(bag.slots.get(i));
    }

    // the cheap half of getGarbageSlot: throwaway items (dirt, cobble past the building reserve, rotten flesh). what comes after is
    // worse tools and, with altoThrowAwayUnusedItems, anything the task is not using, which is not room anybody should plan on
    public static Optional<Slot> getJunkSlot(AltoClef mod) {
        Bag bag = bag(mod);
        List<GarbageRules.Entry> entries = entries(mod, bag, bagFoodUnits(mod));
        // (bag_junk_first: the deep mine's leftovers are junk too, ahead of the rest)
        int first = GarbageRules.junkFirstPick(entries, GarbageRules.junkFirstNow());
        return at(bag, first >= 0 ? first : GarbageRules.junkPick(entries, reserve()));
    }

    // the End's beds and wool in this slot (DropRoom.reserved). the victim throw's last look, the bag may have changed since the room decision
    public static boolean reservedForTheEnd(AltoClef mod, Slot slot) {
        Bag bag = bag(mod);
        boolean[] forTheEnd = DropRoom.reserved(bag.stacks, EndGear.reserveBeds());
        for (int i = 0; i < forTheEnd.length; i++) {
            if (forTheEnd[i] && bag.slots.get(i).equals(slot)) {
                return true;
            }
        }
        return false;
    }

    // empty bag slots, the end prep's gate logs it
    public static int freeBagSlots(AltoClef mod) {
        int free = 0;
        for (ItemStack stack : bag(mod).stacks) {
            if (stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    // one look at the bag and its garbage pick, held for a tick of pile drops so each drop does not read the bag again
    public static GarbageView garbageView(AltoClef mod) {
        return new GarbageView(mod);
    }

    public static final class GarbageView {
        private final AltoClef _mod;
        private final Bag _bag;
        private final Map<Item, Integer> _ranks;
        private final List<GarbageRules.Entry> _entries;
        private final int _foodUnits;
        private final int _floor;
        private final boolean _throwUnused;
        private final int _reserve;
        private final int _pick;
        private final java.util.function.ToIntFunction<Item> _junkFirst;

        private GarbageView(AltoClef mod) {
            _mod = mod;
            _bag = bag(mod);
            _ranks = GarbageRules.throwRanks(AltoSettings.getThrowawayItems(mod));
            _foodUnits = bagFoodUnits(mod);
            _entries = entries(mod, _bag, _foodUnits, _ranks);
            _floor = Baritone.settings().altoFoodKeepUnits.value;
            _throwUnused = Baritone.settings().altoThrowAwayUnusedItems.value;
            _reserve = reserve();
            _junkFirst = GarbageRules.junkFirstNow();
            _pick = GarbageRules.nextPick(_entries, _reserve, _throwUnused, _foodUnits, _floor, _junkFirst);
        }

        public boolean isThrowaway(Item item) {
            return _ranks.containsKey(item);
        }

        // the throwaway blocks junkPick weighs against the building reserve
        public int throwawayBlocksHeld() {
            int total = 0;
            for (GarbageRules.Entry e : _entries) {
                if (e.throwaway() && e.mayGo() && e.stack().getItem() instanceof BlockItem) {
                    total += e.stack().getCount();
                }
            }
            return total;
        }

        // a full bag at a death pile: making room for this drop would throw the current pick, and would the drop then be the next
        // thing thrown? RecoverItemsTask leaves those, see GarbageRules.dropIsNextPick. the food floor is asked of the bag after
        // the swap: the old total still counts the stack that is about to hit the floor
        public boolean dropIsNext(ItemStack drop) {
            if (_pick < 0) {
                return false;
            }
            int after = GarbageRules.unitsAfterSwap(_foodUnits, _bag.stacks.get(_pick), drop);
            GarbageRules.Entry in = new GarbageRules.Entry(drop, ItemHelper.canDropStack(_mod, drop, after) && !wouldWear(_mod, drop),
                    _ranks.getOrDefault(drop.getItem(), -1));
            return GarbageRules.dropIsNextPick(entriesAt(after), _pick, in, _reserve, _throwUnused, after, _floor, _junkFirst);
        }

        // room for a drop with the bag as it is: FIT, EVICT a slot, or NONE (see DropRoom). the death pile's filter, the
        // pickup and the throw all go by this one answer
        public Room room(ItemStack drop) {
            if (hasRoomFor(_mod, drop)) {
                return new Room(DropRoom.Decision.FIT, Optional.empty(), null);
            }
            Optional<Slot> lastResort = Optional.empty();
            if (wouldWear(_mod, drop)) {
                // the last resort stack has to be one the free slot task may actually throw, else it swaps protected stacks round for ever
                lastResort = getLastResortSlot(_mod).filter(slot -> ItemHelper.canDropStack(_mod, getItemStackInSlot(slot)));
            }
            // (the buddy's fork, Tuning potion_room, on, a fix: a fire resistance potion is a core drop, it pushes a stack worth
            // less out of a full bag. the trade's barter fills the bag, and the potions a piglin threw at ingots 98 and 154
            // were left lying, "nothing in the bag may go" (box 2's drankjes smoke, 10 Nov 2026: 192 ingots, one potion kept))
            boolean core = ItemValue.isCore(drop.getItem())
                    || (Tuning.get("potion_room", 1) > 0 && FireResTradeRules.form(drop) != FireResTradeRules.Form.NONE);
            DropRoom.Decision d = DropRoom.decide(false, _pick, _pick >= 0 && dropIsNext(drop), lastResort.isPresent(), core,
                    core ? victims() : List.of());
            if (d.kind() != DropRoom.Kind.EVICT) {
                return new Room(d, Optional.empty(), null);
            }
            Optional<Slot> slot = d.source() == DropRoom.Source.WEAR ? lastResort : Optional.of(_bag.slots.get(d.slot()));
            return new Room(d, slot, getItemStackInSlot(slot.get()).copy());
        }

        private List<DropRoom.Stack> victims() {
            List<DropRoom.Stack> out = new ArrayList<>(_bag.stacks.size());
            boolean[] forTheEnd = DropRoom.reserved(_bag.stacks, EndGear.reserveBeds());
            for (int i = 0; i < _bag.stacks.size(); i++) {
                ItemStack stack = _bag.stacks.get(i);
                Item item = stack.getItem();
                int units = GarbageRules.keepUnits(stack);
                // wouldWear too: wear[] only keeps the best piece per slot, a second helmet for a bare head still counts
                // (Tuning potion_room: a protected stack is never a victim, the trade's gold while it runs; a core drop could take
                // a second stack of gold for a surplus before)
                // (bag_keep_stations: a bed and the stations are never a victim either)
                boolean keep = forTheEnd[i] || _bag.wear[i] || wouldWear(_mod, stack) || AltoSettings.isImportant(item)
                        || (GarbageRules.keepStationsOn() && GarbageRules.station(item))
                        || (Tuning.get("potion_room", 1) > 0 && _mod.getBehaviour().isProtected(item))
                        || (stack.isEnchanted() && Baritone.settings().altoDontThrowAwayEnchantedItems.value)
                        || (stack.has(DataComponents.CUSTOM_NAME) && Baritone.settings().altoDontThrowAwayCustomNameItems.value)
                        || (units > 0 && GarbageRules.keepsFood(_foodUnits, units, _floor));
                boolean build = item instanceof BlockItem && (isThrowaway(item) || ItemHelper.isCoreBuildBlock(item));
                out.add(new DropRoom.Stack(stack, stack.isEmpty() ? ItemValue.REST : ItemValue.tierOf(_mod, stack, false), keep, build));
            }
            return out;
        }

        // the bag in one line for the death pile's log: free slots and every stack
        public String dump() {
            int free = 0;
            StringBuilder sb = new StringBuilder();
            for (ItemStack stack : _bag.stacks) {
                if (stack.isEmpty()) {
                    free++;
                    continue;
                }
                if (sb.length() > 0) sb.append(", ");
                sb.append(ItemHelper.stripItemName(stack.getItem())).append(" x").append(stack.getCount());
            }
            return free + " free: " + sb;
        }

        // only food stacks answer mayGo differently for another total, so the rest is reused as is
        private List<GarbageRules.Entry> entriesAt(int units) {
            if (units == _foodUnits) {
                return _entries;
            }
            List<GarbageRules.Entry> out = new ArrayList<>(_entries);
            for (int i = 0; i < out.size(); i++) {
                GarbageRules.Entry e = out.get(i);
                if (GarbageRules.keepUnits(e.stack()) > 0) {
                    out.set(i, new GarbageRules.Entry(e.stack(), mayGo(_mod, _bag, i, units), e.throwRank()));
                }
            }
            return out;
        }
    }

    // what GarbageView.room says about a drop. slot and victim are set for an EVICT: the slot to throw and what has to still
    // be in it when the throw happens
    public record Room(DropRoom.Decision decision, Optional<Slot> slot, ItemStack victim) {
        public DropRoom.Kind kind() {
            return decision.kind();
        }
    }

    // an empty slot, or a stack of the same thing with space left, takes at least some of a drop (ItemPickupRules.hasRoom)
    public static boolean hasRoomFor(AltoClef mod, ItemStack drop) {
        List<ItemStack> slots = mod.getPlayer().getInventory().getNonEquipmentItems();
        int[] counts = new int[slots.size()];
        boolean[] sameKind = new boolean[slots.size()];
        for (int i = 0; i < counts.length; i++) {
            ItemStack there = slots.get(i);
            counts[i] = there.isEmpty() ? 0 : there.getCount();
            sameKind[i] = ItemStack.isSameItemSameComponents(there, drop);
        }
        return ItemPickupRules.hasRoom(counts, sameKind, drop.getMaxStackSize());
    }

    // (the buddy's fork, Tuning bag_keep_stations) a stack a task's stop must not throw off the cursor: a bed or a station
    public static boolean keptOnCursor(ItemStack stack) {
        return !stack.isEmpty() && GarbageRules.keepStationsOn() && GarbageRules.station(stack.getItem());
    }

    // EnsureFreeCursorSlotTask's way out when it holds something we keep and getGarbageSlot has nothing: the lowest bag stack that
    // is not on the important list (or armor we would wear, or the End's, DropRoom.reserved). the food floor and the protect list are
    // soft, a deadlock is not
    public static Optional<Slot> getLastResortSlot(AltoClef mod) {
        Bag bag = bag(mod);
        boolean[] forTheEnd = DropRoom.reserved(bag.stacks, EndGear.reserveBeds());
        final List<Slot> slots = new ArrayList<>();
        final List<GarbageRules.Cand> cands = new ArrayList<>();
        final boolean[] guarded = new boolean[bag.slots.size()];
        for (int i = 0; i < bag.slots.size(); i++) {
            ItemStack stack = bag.stacks.get(i);
            if (stack.isEmpty() || forTheEnd[i] || bag.wear[i] || AltoSettings.isImportant(stack.getItem()))
                continue;
            // (Tuning bag_keep_stations: a bed, furnace, smoker or crafting table is not even the last resort; one of each was the
            // smallest stack in the bag, so it was always the first to go)
            if (GarbageRules.keepStationsOn() && GarbageRules.station(stack.getItem()))
                continue;
            guarded[cands.size()] = ItemHelper.isStackProtected(mod, stack);
            slots.add(bag.slots.get(i));
            cands.add(GarbageRules.cand(stack));
        }
        int pick = GarbageRules.lastResort(cands, guarded);
        return pick < 0 ? Optional.empty() : Optional.of(slots.get(pick));
    }

    /**
     * @return whether EVERY item target in {@code targetsToMeet} is met in our inventory or conversion slots.
     */
    public static boolean itemTargetsMet(AltoClef mod, ItemTarget... targetsToMeet) {
        return Arrays.stream(targetsToMeet).allMatch(target -> mod.getItemStorage().getItemCount(target.getMatches()) >= target.getTargetCount());
    }

    /**
     * AVOID using this unless it's the end goal to keep an item in our inventory.
     *
     * @return whether EVERY item target in {@code targetsToMeet} is strictly in our inventory.
     */
    public static boolean itemTargetsMetInventory(AltoClef mod, ItemTarget... targetsToMeet) {
        return Arrays.stream(targetsToMeet).allMatch(target -> mod.getItemStorage().getItemCountInventoryOnly(target.getMatches()) >= target.getTargetCount());
    }

    /**
     * Same as {@code itemTargetsMetInventory} but it ignores the cursor slot.
     */
    public static boolean itemTargetsMetInventoryNoCursor(AltoClef mod, ItemTarget... targetsToMeet) {
        ItemStack cursorStack = getItemStackInCursorSlot();
        return Arrays.stream(targetsToMeet).allMatch(target -> {
            int count = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
            if (target.matches(cursorStack.getItem()))
                count -= cursorStack.getCount();
            return count >= target.getTargetCount();
        });
    }

    public static boolean isArmorEquipped(AltoClef mod, Item... any) {
        for (Item item : any) {
            if (ItemHelper.isArmor(item)) {
                ItemStack equippedStack = mod.getPlayer().getItemBySlot(ItemHelper.getEquipSlot(item));
                if (equippedStack.getItem().equals(item))
                    return true;
            }
            if (item instanceof ShieldItem shield) {
                ItemStack equippedStack = mod.getPlayer().getInventory().getItem(Inventory.SLOT_OFFHAND);
                if (equippedStack.getItem().equals(shield))
                    return true;
            }
        }
        return false;
    }

    // the blocks that count as building materials: throwaway blocks baritone can place (AltoSettings.isBuildBlock). the count and the collect
    // target both come from here, or a bag of gravel finishes the collect task and still reads as no blocks at all
    public static Item[] getBuildingMaterials(AltoClef mod) {
        return Arrays.stream(AltoSettings.getThrowawayItems(mod, true)).filter(AltoSettings::isBuildBlock).toArray(Item[]::new);
    }

    public static int getBuildingMaterialCount(AltoClef mod) {
        return mod.getItemStorage().getItemCount(getBuildingMaterials(mod));
    }

    private static boolean isScreenOpenInner(Predicate<AbstractContainerMenu> pNotNull) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null)
            return pNotNull.test(player.containerMenu);
        return false;
    }

    public static boolean isBigCraftingOpen() {
        return isScreenOpenInner(screen -> screen instanceof CraftingMenu);
    }

    public static boolean isPlayerInventoryOpen() {
        return isScreenOpenInner(screen -> screen instanceof InventoryMenu);
    }

    public static boolean isFurnaceOpen() {
        return isScreenOpenInner(screen -> screen instanceof FurnaceMenu);
    }

    public static boolean isSmokerOpen() {
        return isScreenOpenInner(screen -> screen instanceof SmokerMenu);
    }

    public static boolean isBlastFurnaceOpen() {
        return isScreenOpenInner(screen -> screen instanceof BlastFurnaceMenu);
    }

    public static boolean isArmorEquippedAll(AltoClef mod, Item... items) {
        return Arrays.stream(items).allMatch(item -> isArmorEquipped(mod, item));
    }

    public static boolean isEquipped(AltoClef mod, Item... items) {
        return ArrayUtils.contains(items, StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem());
    }

    public static int calculateInventoryFoodScore(AltoClef mod) {
        int result = 0;
        if (!mod.getItemStorage().getItemStacksPlayerInventory(true).isEmpty()) {
            for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
                if (stack.getItem().components().has(DataComponents.FOOD))
                    result += Objects.requireNonNull(stack.getItem().components().get(DataComponents.FOOD)).nutrition() * stack.getCount();
            }
        }
        return result;
    }

    public static double calculateInventoryFuelCount(AltoClef mod) {
        // FuelPolicy leaves out wood somebody has plans for, so "do we have the fuel" and "what goes in the slot" agree
        return FuelPolicy.usableFuel(mod.getItemStorage().getItemStacksPlayerInventory(true), AltoSettings::isSupportedFuel,
                ItemHelper::getFuelAmount);
    }

    /**
     * Returns whether we have the items in our inventory (or currently crafting)
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean hasRecipeMaterialsOrTarget(AltoClef mod, RecipeTarget... targets) {
        HashMap<Integer, Integer> slotUsedCounts = new HashMap<>();
        for (RecipeTarget target : targets) {
            CraftingRecipe recipe = target.getRecipe();
            int need = 0;
            if (target.getOutputItem() != null) {
                need = target.getTargetCount();
                // craftUsableCount, not getItemCount: what the craft itself sees, see there
                need -= craftUsableCount(mod, target.getOutputItem());
            }
            // need holds how many items we need to CRAFT
            // However, a crafting recipe can output more than 1 of an item.
            int materialsPerSlotNeeded = (int) Math.ceil((float) need / target.getRecipe().outputCount());
            for (int i = 0; i < materialsPerSlotNeeded; ++i) {
                for (int slot = 0; slot < recipe.getSlotCount(); ++slot) {
                    ItemTarget needs = recipe.getSlot(slot);

                    // Satisfied by default.
                    if (needs == null || needs.isEmpty()) continue;

                    // do NOT include craft or armor slots. This would include the OUTPUT (which we DO NOT want)
                    List<Slot> slotsWithItem = mod.getItemStorage().getSlotsWithItemPlayerInventory(false, needs.getMatches());

                    // Other slots may have our crafting supplies.
                    AbstractContainerMenu screen = mod.getPlayer().containerMenu;
                    if (screen instanceof InventoryMenu || screen instanceof CraftingMenu) {
                        // Check crafting slots
                        boolean bigCrafting = (screen instanceof CraftingMenu);
                        boolean bigRecipe = recipe.isBig();
                        // a small recipe has 4 slots even in the table. counting to 9 walked off the grid into the player inventory
                        for (int craftSlotIndex = 0; craftSlotIndex < (bigCrafting && bigRecipe ? 9 : 4); ++craftSlotIndex) {
                            Slot craftSlot = bigCrafting ? CraftingTableSlot.getInputSlot(craftSlotIndex, bigRecipe) : PlayerSlot.getCraftInputSlot(craftSlotIndex);
                            ItemStack stack = StorageHelper.getItemStackInSlot(craftSlot);
                            if (needs.matches(stack.getItem())) {
                                slotsWithItem.add(craftSlot);
                            }
                        }
                    }

                    // Try to satisfy THIS slot.
                    boolean satisfied = false;
                    if (!slotsWithItem.isEmpty()) {
                        for (Slot checkSlot : slotsWithItem) {
                            int windowSlot = checkSlot.getWindowSlot();
                            if (!slotUsedCounts.containsKey(windowSlot)) {
                                slotUsedCounts.put(windowSlot, 0);
                            }
                            int usedFromSlot = slotUsedCounts.get(windowSlot);
                            ItemStack stack = StorageHelper.getItemStackInSlot(checkSlot);

                            if (usedFromSlot < stack.getCount()) {
                                slotUsedCounts.put(windowSlot, slotUsedCounts.get(windowSlot) + 1);
                                //Debug.logMessage("Satisfied " + slot + " with " + checkInvSlot);
                                satisfied = true;
                                break;
                            }
                        }
                    }

                    if (!satisfied) {
                        //Debug.logMessage("FAILED TO SATISFY " + slot + " : needs " + needs);
                        // We couldn't satisfy this slot in either the inventory or crafting output.
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // the grid we are standing at, as the slots recipe items could be sitting in. goes by the open menu, not the screen,
    // and with no other menu open that is the inventory's own 2x2
    public static List<Slot> openCraftGridInputs(boolean bigRecipe) {
        AbstractContainerMenu screen = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.containerMenu : null;
        List<Slot> grid = new ArrayList<>();
        if (screen instanceof CraftingMenu) {
            for (int i = 0; i < (bigRecipe ? 9 : 4); ++i) {
                grid.add(CraftingTableSlot.getInputSlot(i, bigRecipe));
            }
        } else if (screen instanceof InventoryMenu) {
            for (int i = 0; i < 4; ++i) {
                grid.add(PlayerSlot.getCraftInputSlot(i));
            }
        }
        return grid;
    }

    /**
     * How many full runs of this recipe our inventory, cursor and the open grid can feed right now. Grid contents
     * count on purpose: the answer must not shrink just because the crafting task already moved the ingredients in.
     */
    public static int craftsPossible(AltoClef mod, CraftingRecipe recipe, int cap) {
        Map<Item, Integer> inGrid = gridContents(openCraftGridInputs(recipe.isBig()));
        return CraftMath.maxCrafts(recipeOptions(recipe), item -> mod.getItemStorage().getItemCountInventoryOnly(item) + inGrid.getOrDefault(item, 0), cap);
    }

    // the same count as craftsPossible, so the answer doesn't move as the grid fills
    public static int perSlotToFill(AltoClef mod, CraftingRecipe recipe, int perSlot) {
        Map<Item, Integer> inGrid = gridContents(openCraftGridInputs(recipe.isBig()));
        return CraftMath.perSlotToFill(recipeOptions(recipe), item -> mod.getItemStorage().getItemCountInventoryOnly(item) + inGrid.getOrDefault(item, 0), perSlot);
    }

    private static List<List<Item>> recipeOptions(CraftingRecipe recipe) {
        List<List<Item>> slots = new ArrayList<>();
        for (int i = 0; i < recipe.getSlotCount(); ++i) {
            ItemTarget needs = recipe.getSlot(i);
            if (needs == null || needs.isEmpty()) continue;
            slots.add(Arrays.asList(needs.getMatches()));
        }
        return slots;
    }

    // the one count for "what can a craft use": the bag (cursor included) plus whatever sits in the open grid, the same rule
    // craftsPossible goes by. getItemCount also counts a furnace's fuel and input, which no craft can touch, so a planks
    // task could see logs the craft could not find and both stood still. the whole grid here (9 at a table), so a 3x3
    // that is half filled doesn't suddenly read as "go collect". a table menu left open with no screen still counts,
    // closeScreen is what deals with that one (closeStaleMenu hands the grid back to the bag)
    public static int craftUsableCount(AltoClef mod, Item... items) {
        Map<Item, Integer> inGrid = gridContents(openCraftGridInputs(true));
        return CraftMath.usableCount(Arrays.asList(items), item -> mod.getItemStorage().getItemCountInventoryOnly(item),
                item -> inGrid.getOrDefault(item, 0));
    }

    private static Map<Item, Integer> gridContents(List<Slot> grid) {
        Map<Item, Integer> inGrid = new HashMap<>();
        for (Slot gridSlot : grid) {
            ItemStack stack = getItemStackInSlot(gridSlot);
            if (!stack.isEmpty()) inGrid.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return inGrid;
    }

    // true when the open screen's craft output slot holds something we have not picked up yet. false when there is no
    // crafting screen, the output slot of a screen that isn't up is garbage
    public static boolean craftOutputWaiting() {
        AbstractContainerMenu screen = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.containerMenu : null;
        if (screen instanceof CraftingMenu) {
            return !getItemStackInSlot(CraftingTableSlot.OUTPUT_SLOT).isEmpty();
        }
        if (screen instanceof InventoryMenu) {
            return !getItemStackInSlot(PlayerSlot.CRAFT_OUTPUT_SLOT).isEmpty();
        }
        return false;
    }

    public static boolean hasCataloguedItem(AltoClef mod, String cataloguedName) {
        return mod.getItemStorage().hasItem(TaskCatalogue.getItemMatches(cataloguedName));
    }

    /**
     * There are slots in our inventory that can't be accessed by containers
     * <p>
     * Mainly the crafting + armor + shield slot.
     *
     * @return A slot of {@code withItem} that is inaccessible to open containers, or {@code Optional.empty} if there
     * are none.
     */
    public static Optional<Slot> getFilledInventorySlotInaccessibleToContainer(AltoClef mod, ItemTarget withItem) {
        // First check if we have anything within our regular inventory.
        if (!StorageHelper.isPlayerInventoryOpen() || withItem.isEmpty() || itemTargetsMetInventory(mod, withItem)) {
            return Optional.empty();
        }
        // Then check our "invalid" slots for our item.
        for (Slot slot : INACCESSIBLE_PLAYER_SLOTS) {
            // (not what is worn: armour is on for a reason. a bill for a spare golden helmet took the one on the head off
            // to count it, and the buddy went on in iron, among piglins. the bill makes a new one instead)
            if (WORN_SLOTS.contains(slot)) continue;
            if (withItem.matches(getItemStackInSlot(slot).getItem())) {
                return Optional.of(slot);
            }
        }
        // Consider Cursor slot only if we have our player inventory open AND we're not crafting it...
        if (StorageHelper.isPlayerInventoryOpen() && withItem.matches(getItemStackInCursorSlot().getItem())) {
            if (!mod.getUserTaskChain().getCurrentTask().thisOrChildSatisfies(task -> {
                if (task instanceof CraftInInventoryTask invCraft) {
                    return withItem.matches(invCraft.getRecipeTarget().getOutputItem());
                }
                return false;
            })) {
                return Optional.of(CursorSlot.SLOT);
            }
        }
        return Optional.empty();
    }

    public static boolean isItemInaccessibleToContainer(AltoClef mod, ItemTarget item) {
        return getFilledInventorySlotInaccessibleToContainer(mod, item).isPresent();
    }

    public static ItemStack getItemStackInCursorSlot() {
        if (Minecraft.getInstance().player != null) {
            if (Minecraft.getInstance().player.containerMenu != null) {
                return Minecraft.getInstance().player.containerMenu.getCarried();
            }
        }
        return ItemStack.EMPTY;
    }

    public static int getBrewingStandFuel() {
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof BrewingStandMenu stand)
            return getBrewingStandFuel(stand);
        return -1;
    }

    public static int getBrewingStandFuel(BrewingStandMenu handler) {
        return handler.getFuel();
    }

    private static final int FURNACE_COOK_TICKS = 200;
    private static final int FAST_COOK_TICKS = 100;

    // lit time left comes in ticks, and the unit everywhere else is items. an item takes 200 ticks in a furnace but 100 in a
    // smoker or blast furnace. a coal is 8 items in all three (the fast ones burn it twice as fast, 800 ticks), so only the lit
    // part of the reading was off: /200 gave half the items a lit smoker could still cook, and it looked nearly dry
    public static double litItems(int litTicks, int ticksPerItem) {
        return (double) litTicks / ticksPerItem;
    }

    public static double getFurnaceFuel(AbstractFurnaceMenu handler) {
        ContainerData d = ((IAbstractFurnaceMenu) handler).getPropertyDelegate();
        return litItems(d.get(0), FURNACE_COOK_TICKS);
    }

    public static double getSmokerFuel(AbstractFurnaceMenu handler) {
        ContainerData d = ((IAbstractFurnaceMenu) handler).getPropertyDelegate();
        return litItems(d.get(0), FAST_COOK_TICKS);
    }

    public static double getBlastFurnaceFuel(AbstractFurnaceMenu handler) {
        ContainerData d = ((IAbstractFurnaceMenu) handler).getPropertyDelegate();
        return litItems(d.get(0), FAST_COOK_TICKS);
    }

    public static double getFurnaceFuel() {
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof AbstractFurnaceMenu furnace)
            return getFurnaceFuel(furnace);
        return -1;
    }

    public static double getSmokerFuel() {
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof AbstractFurnaceMenu smoker)
            return getSmokerFuel(smoker);
        return -1;
    }

    public static double getBlastFurnaceFuel() {
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof AbstractFurnaceMenu blastFurnace)
            return getBlastFurnaceFuel(blastFurnace);
        return -1;
    }

    // getBurnProgress is a 0-1 float now. it used to be an int out of 24 (the arrow's pixel width), and the /24
    // outlived it, so cooking progress read as 4% of what it was
    public static double getFurnaceCookPercent(AbstractFurnaceMenu handler) {
        return (double) handler.getBurnProgress();
    }

    public static double getSmokerCookPercent(AbstractFurnaceMenu handler) {
        return (double) handler.getBurnProgress();
    }

    public static double getBlastFurnaceCookPercent(AbstractFurnaceMenu handler) {
        return (double) handler.getBurnProgress();
    }

    public static double getFurnaceCookPercent() {
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof AbstractFurnaceMenu furnace)
            return getFurnaceCookPercent(furnace);
        return -1;
    }

    public static double getSmokerCookPercent() {
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof AbstractFurnaceMenu smoker)
            return getSmokerCookPercent(smoker);
        return -1;
    }

    public static double getBlastFurnaceCookPercent() {
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof AbstractFurnaceMenu blastFurnace)
            return getBlastFurnaceCookPercent(blastFurnace);
        return -1;
    }

    public static ItemTarget[] getAllInventoryItemsAsTargets(Predicate<Slot> accept) {
        HashMap<Item, Integer> counts = new HashMap<>();
        if (Slot.getCurrentScreenSlots() != null) {
            for (Slot slot : Slot.getCurrentScreenSlots()) {
                if (slot.isSlotInPlayerInventory() && accept.test(slot)) {
                    ItemStack stack = getItemStackInSlot(slot);
                    if (!stack.isEmpty()) {
                        counts.put(stack.getItem(), counts.getOrDefault(stack.getItem(), 0) + stack.getCount());
                    }
                }
            }
        }
        ItemTarget[] results = new ItemTarget[counts.size()];
        int i = 0;
        if (!counts.keySet().isEmpty()) {
            for (Item item : counts.keySet()) {
                results[i++] = new ItemTarget(item, counts.get(item));
            }
        }
        return results;
    }
}
