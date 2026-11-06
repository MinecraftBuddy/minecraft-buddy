package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.slot.EnsureFreeCursorSlotTask;
import adris.altoclef.tasks.slot.EnsureFreePlayerCraftingGridTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WalkCost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

// a bed out of the wool and planks we already hold, at a table. the catalogue's bed task (CollectBedTask) is not this: it mines
// the nearest bed it knows of before it ever looks at the bag, which is how a night trip could walk off to break somebody
// else's bed. this one only crafts, and says so when it cannot. shared by the night trip and the evening prep
public final class BedCraft {
    // a table costs four planks, and the bag's table or one standing close saves them
    private static final int TABLE_PLANKS = 4;

    private ItemHelper.ColorfulItems colour;
    private CraftInTableTask craft;

    // the most wool of any one colour, which is what a bed can use
    public static int bestWoolStack(AltoClef mod) {
        int best = 0;
        for (Item wool : ItemHelper.WOOL) {
            best = Math.max(best, mod.getItemStorage().getItemCount(wool));
        }
        return best;
    }

    public static int planks(AltoClef mod) {
        return mod.getItemStorage().getItemCount(ItemHelper.PLANKS);
    }

    // planks, plus four a log: what a bed (and a table, if there is none) can be made of without going to find any more
    public static int wood(AltoClef mod) {
        return planks(mod) + 4 * mod.getItemStorage().getItemCount(ItemHelper.LOG);
    }

    // the table task only walks to a world table this close (StationChoice's near line) and one the tracker gave up on does not
    // count, past that it makes its own and the four planks that costs come out of the bed's
    public static boolean tableReady(AltoClef mod) {
        if (mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
            return true;
        }
        var tracker = mod.getBlockTracker();
        if (!tracker.isTracking(Blocks.CRAFTING_TABLE)) {
            // the night check asks every tick of a build, nobody tracks tables there, and the warning flooded the trace
            return false;
        }
        return tracker.getNearestTracking(p -> !tracker.unreachable(p), Blocks.CRAFTING_TABLE)
                .map(p -> p.closerToCenterThan(mod.getPlayer().position(), WalkCost.STATION_NEAR)).orElse(false);
    }

    // wood the bed itself may count on: if there is no table, making one eats four planks first
    public static int usableWood(AltoClef mod) {
        return wood(mod) - (tableReady(mod) ? 0 : TABLE_PLANKS);
    }

    private Task planksTask;
    private int planksFor = -1;

    // the planks for the bed (and the table) crafted out of logs when the bag holds the wood but not as planks, or null when
    // there is nothing to do. the bed craft itself does not turn logs into planks
    public Task planksFirst(AltoClef mod) {
        int need = woodNeeded(mod);
        if (planks(mod) >= need || wood(mod) < need) {
            planksTask = null;
            planksFor = -1;
            return null;
        }
        if (planksTask == null || planksFor != need) {
            planksFor = need;
            planksTask = TaskCatalogue.getItemTask("planks", need);
        }
        return planksTask;
    }

    // the planks wood has to be worth, table or not (the prep rule's number)
    public static int woodNeeded(AltoClef mod) {
        return 3 + (tableReady(mod) ? 0 : TABLE_PLANKS);
    }

    // the task that crafts the bed, or null when the bag does not have three of a colour and three planks (nothing to hunt for
    // here, that is the caller's business). the same task comes back every tick: it keeps its own state
    public Task next(AltoClef mod) {
        ItemHelper.ColorfulItems best = null;
        int most = 0;
        for (ItemHelper.ColorfulItems c : ItemHelper.getColorfulItems()) {
            int n = mod.getItemStorage().getItemCount(c.wool);
            if (n > most) {
                most = n;
                best = c;
            }
        }
        if (best == null || most < 3 || planks(mod) < 3) {
            return null;
        }
        if (craft == null || colour != best) {
            colour = best;
            ItemTarget wool = new ItemTarget(best.wool, 1);
            ItemTarget plank = TaskCatalogue.getItemTarget("planks", 1);
            CraftingRecipe recipe = CraftingRecipe.newShapedRecipe(new ItemTarget[]{wool, wool, wool, plank, plank, plank, null, null, null}, 1);
            // collect=false: it crafts what is in the bag and does not go looking for more
            craft = new CraftInTableTask(new RecipeTarget(best.bed, 1, recipe), false, true);
        }
        return craft;
    }

    // wool or planks that look gone may be sitting somewhere the count does not see: on the cursor, in the 2x2 grid while another
    // screen is the open one, or behind a screen that is up (closing it hands the grid back). this puts them back, one thing a
    // tick, and returns the task that does it, or null when nothing is lying about
    public static Task stray(AltoClef mod) {
        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return new EnsureFreeCursorSlotTask();
        }
        AbstractContainerMenu menu = mod.getPlayer().containerMenu;
        boolean inventory = menu instanceof InventoryMenu;
        if (inventory && gridHolds()) {
            return new EnsureFreePlayerCraftingGridTask();
        }
        var screen = Minecraft.getInstance().gui.screen();
        if (screen != null && !(screen instanceof InBedChatScreen) && !(menu instanceof InventoryMenu)) {
            // a table, a chest or a furnace somebody left up. shutting it gives its grid back to the bag
            StorageHelper.closeScreen();
        }
        return null;
    }

    private static boolean gridHolds() {
        for (var slot : StorageHelper.openCraftGridInputs(false)) {
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (!stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
