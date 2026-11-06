package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// put on the armor the pile gave back, only what is on us this very tick. EquipArmorTask goes off to gather any piece it is
// handed that we don't hold, and a pile pickup has no business mining iron. every piece here passes the same "do we hold it"
// test EquipArmorTask asks first, so its gather branch never fires
public final class WearFromBag {

    private WearFromBag() {
    }

    // the pieces of toEquip (KitPlanner.toEquip, rebuilt by the caller every tick) in the bag or on the cursor
    public static List<Item> held(AltoClef mod, List<Item> toEquip) {
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        List<Item> out = new ArrayList<>();
        for (Item item : toEquip) {
            if (mod.getItemStorage().getItemCountInventoryOnly(item) > 0 || cursor.getItem() == item) {
                out.add(item);
            }
        }
        return out;
    }

    // null when there is nothing to put on
    public static Task task(AltoClef mod, List<Item> toEquip) {
        List<Item> held = held(mod, toEquip);
        return held.isEmpty() ? null : new EquipArmorTask(held.toArray(new Item[0]));
    }
}
