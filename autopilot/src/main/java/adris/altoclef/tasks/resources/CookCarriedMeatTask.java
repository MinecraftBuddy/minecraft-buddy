package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The buddy's fork (Tuning cook_raw_m2, 25 Nov 2026): the raw meat the bag carries, cooked in the bag's furnace before the
 * route goes deep (the buddy's piece "cook raw meat" at the end of M2.2). Raw meat from kills on the way stays raw: the food
 * run cooks only its own catch, and the surface phase counts raw meat as food. In heel's runs 9 of 29 went down to the
 * diamonds with 9 or more raw pieces, some with none cooked, and 5 of 16 obsidian deaths had run out of food: a raw beef heals
 * about 3 hp, a cooked one about 14 (nutrition 3 against 8, saturation 1.8 against 12.8; FoodData, 1 hp a 1.5 food points).
 * <p>
 * Only whole kinds that the bag's usable fuel covers (StorageHelper.calculateInventoryFuelCount, the count the furnace task
 * itself goes by), at most MAX_ITEMS: a kind the fuel does not cover is left raw, so the furnace task never goes for fuel. The
 * kinds are cooked one after the other, each by its own furnace task (the multi-target one does only its first), and the plan
 * is made once at the start, so the task is never made again with a new plan.
 */
public class CookCarriedMeatTask extends Task {

    // raw and cooked, the most food a fuel first: beef and pork 3 to 8, mutton, chicken and salmon 2 to 6, rabbit 3 to 5, cod 2 to 5
    private static final Item[][] KINDS = {
            {Items.BEEF, Items.COOKED_BEEF}, {Items.PORKCHOP, Items.COOKED_PORKCHOP}, {Items.MUTTON, Items.COOKED_MUTTON},
            {Items.CHICKEN, Items.COOKED_CHICKEN}, {Items.SALMON, Items.COOKED_SALMON}, {Items.RABBIT, Items.COOKED_RABBIT},
            {Items.COD, Items.COOKED_COD}};

    /** Fewer raw items than this are not worth placing the furnace for. */
    public static final int MIN_ITEMS = 4;

    /** At most this many items a cook: 200 s in a furnace. */
    public static final int MAX_ITEMS = 20;

    private final List<Item[]> _plan = new ArrayList<>();
    private int _index;
    private Task _current;
    private int _planned;
    private long _startMs;
    private boolean _started;

    // a cook under way: the buddy's piece stays wanted while it runs (the meat in the furnace is no longer in the bag, and the
    // route went on to the diamonds 1 s into the first fixed smoke's cook, which stopped it)
    private static volatile boolean active;

    /** Whether a cook is under way now. */
    public static boolean active() {
        return active;
    }

    /** The raw items this would cook now (whole kinds the bag's fuel covers, at most MAX_ITEMS); 0 without a furnace in the bag. */
    public static int cookable(AltoClef mod) {
        return choose(mod, null);
    }

    private static int choose(AltoClef mod, List<Item[]> out) {
        if (mod == null || mod.getPlayer() == null || mod.getItemStorage().getItemCount(Items.FURNACE) < 1) return 0;
        // (one smelt's margin: a part-burnt plank in the slot is not in the bag's count)
        double fuel = StorageHelper.calculateInventoryFuelCount(mod) - 1;
        int n = 0;
        for (Item[] k : KINDS) {
            int raw = mod.getItemStorage().getItemCount(k[0]);
            if (raw <= 0 || n + raw > fuel || n + raw > MAX_ITEMS) continue;
            n += raw;
            if (out != null) out.add(k);
        }
        return n;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _plan.clear();
        _planned = choose(mod, _plan);
        _started = true;
        active = true;
        _index = 0;
        _current = null;
        _startMs = System.currentTimeMillis();
        StringBuilder what = new StringBuilder();
        for (Item[] k : _plan) {
            what.append(what.length() == 0 ? "" : ", ").append(mod.getItemStorage().getItemCount(k[0])).append(' ').append(name(k[0]));
        }
        Debug.logInternal("food: cooking the raw meat carried before the deep: " + _planned + " items (" + what + "), usable fuel "
                + Math.round(StorageHelper.calculateInventoryFuelCount(mod)) + " smelts (cook_raw_m2)");
    }

    @Override
    protected Task onTick(AltoClef mod) {
        while (_index < _plan.size()) {
            Item[] k = _plan.get(_index);
            if (_current != null && _current.isFinished(mod)) {
                _index++;
                _current = null;
                continue;
            }
            if (_current == null) {
                int raw = mod.getItemStorage().getItemCount(k[0]);
                if (raw <= 0) {
                    _index++;
                    continue;
                }
                // (as the food run's own cook, CollectFoodTask.cook: the whole kind, what is cooked already counted in the target)
                SmeltInFurnaceTask t = new SmeltInFurnaceTask(new SmeltTarget(new ItemTarget(k[1], raw + mod.getItemStorage().getItemCount(k[1])),
                        new ItemTarget(k[0], raw)));
                t.ignoreMaterials();
                _current = t;
            }
            setDebugState("Cooking the raw " + name(k[0]) + " carried");
            return _current;
        }
        return null;
    }

    // (the plan is made in onStart: before it the empty plan read as done, and the first smoke's cook ended at once with 18 raw
    // pieces and the fuel for them in the bag, 25 Nov 2026)
    @Override
    public boolean isFinished(AltoClef mod) {
        return _started && _index >= _plan.size();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        active = false;
        int left = 0;
        for (Item[] k : KINDS) left += mod.getItemStorage().getItemCount(k[0]);
        Debug.logInternal("food: the raw meat cook " + (isFinished(mod) ? "done" : "stopped") + " after "
                + Math.round((System.currentTimeMillis() - _startMs) / 1000.0) + " s: " + _planned + " planned, " + left
                + " raw left in the bag (cook_raw_m2)");
    }

    private static String name(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CookCarriedMeatTask;
    }

    @Override
    protected String toDebugString() {
        return "Cooking the raw meat carried";
    }
}
