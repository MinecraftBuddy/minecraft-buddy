package adris.altoclef.control;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CursorSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.EmptyMapItem;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FoodOnAStickItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SpawnEggItem;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;


public class SlotHandler {

    private final AltoClef _mod;

    private final TimerGame _slotActionTimer = new TimerGame(0);
    private boolean _overrideTimerOnce = false;

    public SlotHandler(AltoClef mod) {
        _mod = mod;
    }

    private void forceAllowNextSlotAction() {
        _overrideTimerOnce = true;
    }

    public boolean canDoSlotAction() {
        if (_overrideTimerOnce) {
            _overrideTimerOnce = false;
            return true;
        }
        _slotActionTimer.setInterval(Baritone.settings().altoContainerItemMoveDelay.value);
        return _slotActionTimer.elapsed();
    }

    public void registerSlotAction() {
        _mod.getItemStorage().registerSlotAction();
        _slotActionTimer.reset();
    }


    // true when a click really went out. the cooldown eats clicks silently, and a caller that wants to know whether
    // its click landed (ReceiveCraftingOutputSlotTask) has to be told
    public boolean clickSlot(Slot slot, int mouseButton, ContainerInput type) {
        if (!canDoSlotAction()) return false;

        if (slot.getWindowSlot() == -1) {
            return clickSlot(PlayerSlot.UNDEFINED, 0, ContainerInput.PICKUP);
        }
        // NOT THE CASE! We may have something in the cursor slot to place.
        //if (getItemStackInSlot(slot).isEmpty()) return getItemStackInSlot(slot);

        return clickWindowSlot(slot.getWindowSlot(), mouseButton, type);
    }

    private void clickSlotForce(Slot slot, int mouseButton, ContainerInput type) {
        forceAllowNextSlotAction();
        clickSlot(slot, mouseButton, type);
    }

    private boolean clickWindowSlot(int windowSlot, int mouseButton, ContainerInput type) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        registerSlotAction();
        return sendClick(player, player.containerMenu.containerId, windowSlot, mouseButton, type);
    }

    // the packet part of a click with no cooldown bookkeeping, so a burst can send a few under one slot action.
    // handleContainerInput runs the click on our own menu first (the client predicts it) and then sends it, so
    // back to back clicks see each other and the server replays the same thing
    private boolean sendClick(LocalPlayer player, int syncId, int windowSlot, int mouseButton, ContainerInput type) {
        noteBedDrop(player, windowSlot, type);
        try {
            _mod.getController().handleContainerInput(syncId, windowSlot, mouseButton, type, player);
        } catch (Exception e) {
            Debug.logWarning("Slot Click Error (ignored)");
            e.printStackTrace();
            return false;
        }
        return true;
    }

    // a bed is a whole night, and most of the garbage paths drop things without a word. every drop click comes through
    // here, so one line here says who threw it
    private void noteBedDrop(LocalPlayer player, int windowSlot, ContainerInput type) {
        ItemStack gone = ItemStack.EMPTY;
        if (windowSlot == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE && type == ContainerInput.PICKUP) {
            gone = player.containerMenu.getCarried();
        } else if (type == ContainerInput.THROW && windowSlot >= 0 && windowSlot < player.containerMenu.slots.size()) {
            gone = player.containerMenu.getSlot(windowSlot).getItem();
        }
        if (gone.isEmpty() || !Arrays.asList(ItemHelper.BED).contains(gone.getItem())) {
            return;
        }
        // (a null owner is a chain, the fix chains and the garbage sweeps click from outside any task)
        Task owner = Task.currentOwner();
        Debug.logInternal("dropping a bed (" + gone.getCount() + "x " + ItemHelper.stripItemName(gone.getItem()) + ") from window slot "
                + windowSlot + ", clicked by " + (owner == null ? "a chain" : owner.toString()));
    }

    // the same click a few times in one slot action (one cooldown). right clicking a held stack into a slot puts one
    // down per click, this is how "place 12" costs one action instead of twelve
    public boolean clickSlotBurst(Slot slot, int mouseButton, ContainerInput type, int times) {
        if (!canDoSlotAction()) return false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || slot.getWindowSlot() == -1) return false;
        registerSlotAction();
        int syncId = player.containerMenu.containerId;
        for (int i = 0; i < times; ++i) {
            if (!sendClick(player, syncId, slot.getWindowSlot(), mouseButton, type)) return false;
        }
        return true;
    }

    // vanilla drag clicking over some slots, as ONE slot action. a left drag (evenSplit) splits the cursor evenly,
    // floor(count / slots) each, and keeps the remainder. a right drag puts exactly one in each slot, and rounds is
    // how many of those to do back to back. returnFirst right clicks the cursor into returnTo before that, which is
    // how a stack gets trimmed so the even split lands on an exact number.
    // one drag is slots + 2 packets: start on slot -999, one per slot, end on slot -999
    public boolean dragSplit(List<Slot> slots, boolean evenSplit, int rounds, Slot returnTo, int returnFirst) {
        if (slots.size() < 2 || !canDoSlotAction()) return false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        registerSlotAction();
        int syncId = player.containerMenu.containerId;
        int type = evenSplit ? AbstractContainerMenu.QUICKCRAFT_TYPE_CHARITABLE : AbstractContainerMenu.QUICKCRAFT_TYPE_GREEDY;
        int outside = AbstractContainerMenu.SLOT_CLICKED_OUTSIDE;
        if (returnTo != null) {
            for (int i = 0; i < returnFirst; ++i) {
                if (!sendClick(player, syncId, returnTo.getWindowSlot(), 1, ContainerInput.PICKUP)) return false;
            }
        }
        int start = AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_START, type);
        int add = AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_CONTINUE, type);
        int end = AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_END, type);
        for (int round = 0; round < rounds; ++round) {
            if (!sendClick(player, syncId, outside, start, ContainerInput.QUICK_CRAFT)) return false;
            for (Slot slot : slots) {
                if (!sendClick(player, syncId, slot.getWindowSlot(), add, ContainerInput.QUICK_CRAFT)) return false;
            }
            if (!sendClick(player, syncId, outside, end, ContainerInput.QUICK_CRAFT)) return false;
        }
        return true;
    }

    public void forceEquipItemToOffhand(Item toEquip) {
        if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() == toEquip) {
            return;
        }
        List<Slot> currentItemSlot = _mod.getItemStorage().getSlotsWithItemPlayerInventory(false,
                toEquip);
        for (Slot CurrentItemSlot : currentItemSlot) {
            if (!Slot.isCursor(CurrentItemSlot)) {
                _mod.getSlotHandler().clickSlot(CurrentItemSlot, 0, ContainerInput.PICKUP);
            } else {
                _mod.getSlotHandler().clickSlot(PlayerSlot.OFFHAND_SLOT, 0, ContainerInput.PICKUP);
            }
        }
    }

    public boolean forceEquipItem(Item toEquip) {

        // Already equipped
        if (StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem() == toEquip) return true;

        // (the buddy's fork: an item in the hotbar is held by selecting its slot, as a player does with the wheel or keys 1 to 9.
        // this always selected slot 1 and swapped the item there, so a sword in slot 4 went to slot 1 at every equip and what was
        // in slot 1 went to slot 4 or the bag, and the inventory churned all day; anti-cheats look at clicks while moving too
        // (4 Nov 2026: the right hotbar item is used where it is, never swapped in))
        var inventory = _mod.getPlayer().getInventory();
        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).getItem() == toEquip) {
                inventory.setSelectedSlot(i);
                return holds(toEquip);
            }
        }
        // not in the hotbar: in, once, to a free slot (first + last is occupied by baritone, 7 is its throwaway blocks)
        // (selected only when something comes in: with the item nowhere the hand stays as it was)
        int hotbar = arrivalSlot(inventory);

        // If our item is in our cursor, simply move it to the hotbar.
        boolean inCursor = StorageHelper.getItemStackInSlot(CursorSlot.SLOT).getItem() == toEquip;

        if (inCursor) {
            inventory.setSelectedSlot(hotbar);
            // the cursor is listed first in the slot list and its window index is -1, which clicks as "outside the
            // window". that threw the stack on the floor instead of equipping it. drop it onto the hotbar slot
            // like the offhand version does
            clickSlotForce(PlayerSlot.getEquipSlot(), 0, ContainerInput.PICKUP);
            return holds(toEquip);
        }

        List<Slot> itemSlots = _mod.getItemStorage().getSlotsWithItemScreen(toEquip);
        if (itemSlots.size() != 0) {
            // one swap is the whole job. this used to swap EVERY stack of the item into the hotbar slot, so with
            // two stacks the second swap just undid the first and the item bounced in and out forever
            Slot from = Objects.requireNonNull(itemSlots.get(0));
            inventory.setSelectedSlot(hotbar);
            clickSlotForce(from, hotbar, ContainerInput.SWAP);
            noteSwap(toEquip, hotbar);
            return holds(toEquip);
        }
        return false;
    }

    // the click is predicted on our own menu before it goes out, so the hand already shows what it will hold. "true" used
    // to mean "we clicked", and DestroyBlockTask trusted it for 26 s of swapping at a carrot
    private static boolean holds(Item item) {
        return StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem() == item;
    }

    public boolean forceDeequipHitTool() {
        return forceDeequip(stack -> ItemHelper.isTool(stack.getItem()));
    }

    public void forceDeequipRightClickableItem() {
        forceDeequip(stack -> {
                    Item item = stack.getItem();
                    return item instanceof BucketItem // water,lava,milk,fishes
                            || item instanceof EnderEyeItem
                            || item == Items.BOW
                            || item == Items.CROSSBOW
                            || item == Items.FLINT_AND_STEEL || item == Items.FIRE_CHARGE
                            || item == Items.ENDER_PEARL
                            || item instanceof FireworkRocketItem
                            || item instanceof SpawnEggItem
                            || item == Items.END_CRYSTAL
                            || item == Items.EXPERIENCE_BOTTLE
                            || item instanceof PotionItem // also includes splash/lingering
                            || item == Items.TRIDENT
                            || item == Items.WRITABLE_BOOK
                            || item == Items.WRITTEN_BOOK
                            || item instanceof FishingRodItem
                            || item instanceof FoodOnAStickItem
                            || item == Items.COMPASS
                            || item instanceof EmptyMapItem
                            || ItemHelper.isEquippable(item)
                            || item == Items.LEAD
                            || item == Items.SHIELD;
                }
        );
    }

    /**
     * Tries to de-equip any item that we don't want equipped.
     *
     * @param isBad: Whether an item is bad/shouldn't be equipped
     * @return Whether we successfully de-equipped, or if we didn't have the item equipped at all.
     */
    public boolean forceDeequip(Predicate<ItemStack> isBad) {
        ItemStack equip = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
        ItemStack cursor = StorageHelper.getItemStackInSlot(CursorSlot.SLOT);
        if (isBad.test(cursor)) {
            // Throw away cursor slot OR move
            Optional<Slot> fittableSlots = _mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
            if (fittableSlots.isEmpty()) {
                // Try to swap items with the first non-bad slot.
                for (Slot slot : Slot.getCurrentScreenSlots()) {
                    if (!isBad.test(StorageHelper.getItemStackInSlot(slot))) {
                        clickSlotForce(slot, 0, ContainerInput.PICKUP);
                        return false;
                    }
                }
                if (ItemHelper.canDropStack(_mod, cursor)) {
                    clickSlotForce(PlayerSlot.UNDEFINED, 0, ContainerInput.PICKUP);
                    return true;
                }
                // Can't throw :(
                return false;
            } else {
                // Put in the empty/available slot.
                clickSlotForce(fittableSlots.get(), 0, ContainerInput.PICKUP);
                return true;
            }
        } else if (isBad.test(equip)) {
            // Pick up the item
            clickSlotForce(PlayerSlot.getEquipSlot(), 0, ContainerInput.PICKUP);
            return false;
        } else if (equip.isEmpty() && !cursor.isEmpty()) {
            // cursor is good and equip is empty, so finish filling it in.
            clickSlotForce(PlayerSlot.getEquipSlot(), 0, ContainerInput.PICKUP);
            return true;
        }
        // We're already de-equipped
        return true;
    }

    public void forceEquipSlot(Slot slot) {
        var inventory = _mod.getPlayer().getInventory();
        // (the buddy's fork: a hotbar slot of the player's own is selected, not swapped; see forceEquipItem)
        if (slot instanceof PlayerSlot && slot.getInventorySlot() >= 0 && slot.getInventorySlot() < 9) {
            inventory.setSelectedSlot(slot.getInventorySlot());
            return;
        }
        int hotbar = arrivalSlot(inventory);
        inventory.setSelectedSlot(hotbar);
        clickSlotForce(slot, hotbar, ContainerInput.SWAP);
        noteSwap(StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem(), hotbar);
    }

    // the hotbar slot an item from the bag comes into: an empty one of 1 to 6 (0 and 8 are baritone's tools, 7 its throwaway
    // blocks), else the least useful of them (see keep): with the hotbar full the sword and the food both came into slot 1 and
    // pushed each other into the bag, a swap every few seconds (corner17, 4 Nov 2026)
    static int arrivalSlot(net.minecraft.world.entity.player.Inventory inventory) {
        for (int i = 1; i <= 6; i++) {
            if (inventory.getItem(i).isEmpty()) return i;
        }
        int best = 1, bestKeep = Integer.MAX_VALUE;
        for (int i = 1; i <= 6; i++) {
            int k = keep(inventory, i);
            if (k < bestKeep) {
                bestKeep = k;
                best = i;
            }
        }
        return best;
    }

    // how much a hotbar slot's item is worth keeping there, lower goes first: a tool with one as good or better of its kind
    // elsewhere in the hotbar 0, a table, planks or sticks 1, another block (not a throwaway) 2, the rest 5; the sword, food,
    // a shield and the throwaway blocks never (100)
    static int keep(net.minecraft.world.entity.player.Inventory inventory, int slot) {
        ItemStack stack = inventory.getItem(slot);
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (stack.getItem().components().has(net.minecraft.core.component.DataComponents.FOOD) || id.endsWith("_sword")
                || stack.getItem() == Items.SHIELD || id.equals("cobblestone") || id.equals("netherrack") || id.equals("dirt")
                || id.equals("cobbled_deepslate")) return 100;
        String kind = id.contains("_") ? id.substring(id.lastIndexOf('_')) : "";
        if (kind.equals("_pickaxe") || kind.equals("_axe") || kind.equals("_shovel") || kind.equals("_hoe")) {
            for (int i = 0; i < 9; i++) {
                if (i == slot) continue;
                String other = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(inventory.getItem(i).getItem()).getPath();
                if (other.endsWith(kind) && tier(other) >= tier(id)) return 0;
            }
            return 5;
        }
        if (id.equals("crafting_table") || id.endsWith("_planks") || id.equals("stick")) return 1;
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) return 2;
        return 5;
    }

    private static int tier(String id) {
        if (id.startsWith("netherite")) return 6;
        if (id.startsWith("diamond")) return 5;
        if (id.startsWith("iron")) return 4;
        if (id.startsWith("stone")) return 3;
        if (id.startsWith("golden")) return 2;
        return 1;
    }

    // a hotbar slot to hold while the shield is up, when the hand has food (a right click with food in hand eats, with the
    // shield it blocks): a sword, else a pickaxe, else an empty slot; -1 with none
    public static int nonFoodSlot(net.minecraft.world.entity.player.Inventory inventory) {
        for (String want : new String[]{"_sword", "_pickaxe"}) {
            for (int i = 0; i < 9; i++) {
                if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(inventory.getItem(i).getItem()).getPath().endsWith(want)) return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    // every swap into the hand, one line each: the drills count them a run (drillrun.py), so the number can be seen going down
    private static void noteSwap(Item item, int hotbar) {
        Debug.logInternal("slot: swapped " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath() + " into hotbar " + hotbar);
    }

    public boolean forceEquipItem(Item[] matches, boolean unInterruptable) {
        return forceEquipItem(new ItemTarget(matches, 1), unInterruptable);
    }

    public boolean forceEquipItem(ItemTarget toEquip, boolean unInterruptable) {
        if (toEquip == null) return false;

        //If the bot try to eat
        if (_mod.getFoodChain().needsToEat() && !unInterruptable) { //unless we really need to force equip the item
            return false; //don't equip the item for now
        }

        Slot target = PlayerSlot.getEquipSlot();
        // Already equipped
        if (toEquip.matches(StorageHelper.getItemStackInSlot(target).getItem())) return true;

        for (Item item : toEquip.getMatches()) {
            if (_mod.getItemStorage().hasItem(item)) {
                if (forceEquipItem(item)) return true;
            }
        }
        return false;
    }

    // By default, don't force equip if the bot is eating.
    public boolean forceEquipItem(Item... toEquip) {
        return forceEquipItem(toEquip, false);
    }

    public void refreshInventory() {
        if (Minecraft.getInstance().player == null)
            return;
        for (int i = 0; i < Minecraft.getInstance().player.getInventory().getNonEquipmentItems().size(); ++i) {
            Slot slot = Slot.getFromCurrentScreenInventory(i);
            clickSlotForce(slot, 0, ContainerInput.PICKUP);
            clickSlotForce(slot, 0, ContainerInput.PICKUP);
        }
    }
}
