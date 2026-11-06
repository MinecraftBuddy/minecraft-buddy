package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.container.SmeltInSmokerTask;
import adris.altoclef.tasks.speedrun.gamer.CookGate;
import adris.altoclef.tasks.speedrun.gamer.CookTrip;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.FoodHelper;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.world.item.Item;

// cooks the raw meat in the bag, one kind at a time (the input slot only holds one). this is the gamer's own cook: CollectFoodTask
// only cooks inside its own need, and that need is met the moment raw meat counts at its cooked value. with async smelting on it
// loads and walks away, the phases come back for the output like any other furnace job
public class CookRawFoodTask extends Task {
    // still being ticked this long after the cook finished with no child: say so in the log once
    private static final long IDLE_LOG_TICKS = 5 * 20;

    private final boolean smoker;
    private Task smelt;
    private Item smelting;
    private int lastRaw;
    private int lastCooked;
    private int lastFuel;
    private long lastChange;
    private boolean gaveUp;
    // game tick we started handing back no child, -1 while there is one
    private long idleSince = -1;
    private boolean idleLogged;

    public CookRawFoodTask(boolean smoker) {
        this.smoker = smoker;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // a fight is not the end of the cook. with the whole pile in the slot the bag reads no raw meat, so a fresh look says done and
        // walks off without it. anything else gets a new smelt: its target counts the bag as it is now, a bite on the walk over
        // leaves the old one short for ever
        if (!keepSmelt(smelt instanceof SmeltInSmokerTask task && task.holdsMeat(), handedOff(),
                smelting == null ? 0 : mod.getItemStorage().getItemCount(smelting))) {
            smelt = null;
            smelting = null;
        }
        lastRaw = -1;
        lastCooked = -1;
        lastFuel = -1;
        gaveUp = false;
        idleSince = -1;
        idleLogged = false;
        lastChange = mod.getWorld().getGameTime();
    }

    // the kind with the most pieces, one trip cooks the biggest pile
    private static Item pickRaw(AltoClef mod) {
        Item best = null;
        int bestCount = 0;
        for (Item raw : CookGate.RAW_MEAT) {
            int n = mod.getItemStorage().getItemCount(raw);
            if (n > bestCount) {
                best = raw;
                bestCount = n;
            }
        }
        return best;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (gaveUp) {
            CookTrip.release();
            return idle(now, "Gave up on the cook");
        }
        if (handedOff()) {
            // loaded and walked away: the job lands in the gamer's list next tick and the cook need goes with it
            CookTrip.release();
            return idle(now, "Loaded, the smoker has it");
        }
        CookTrip.commit(smoker, now);
        if (smelt != null && smelt.isFinished(mod)) {
            // finished but not loaded: cooked in place (async cooking off), or the bag met the smelt's target by itself. that second
            // one is the cooked batch we collect from our own smoker before loading the next: three cooked beef in the bag satisfied
            // "have three cooked beef" with the three raw ones still sitting next to them. this used to count as a load, hand back
            // no child and idle for 35 s with the meat in the bag (live log 23:19:39). on to the next pile with a target that
            // counts what is there now
            smelt = null;
            lastRaw = -1;
            lastCooked = -1;
            lastFuel = -1;
            lastChange = now;
        }
        if (smelt == null) {
            smelting = pickRaw(mod);
            if (smelting == null) {
                // nothing left to cook and nothing loading: let go of the station, or a sync cook (async cooking off) kept the
                // cook need alive through the stamp until the watchdog
                CookTrip.release();
                return idle(now, "Nothing raw left to cook");
            }
            smelt = make(mod, smelting);
        }
        idleSince = -1;
        idleLogged = false;
        // a sync cook empties the bag as it goes and fills it with the cooked kind, either is the bag moving
        int raw = mod.getItemStorage().getItemCount(smelting);
        int cooked = mod.getItemStorage().getItemCount(FoodHelper.cookedForm(smelting));
        // the fuel trip comes first now (before the walk and the load) and it moves neither of those: coal coming into the bag is
        // the cook getting somewhere, or a long coal trip ran into the give up with the iron locked out the whole time
        int fuel = (int) (StorageHelper.calculateInventoryFuelCount(mod) * 10);
        if (raw != lastRaw || cooked != lastCooked || fuel != lastFuel) {
            lastRaw = raw;
            lastCooked = cooked;
            lastFuel = fuel;
            lastChange = now;
        }
        if (FurnacePlan.cookGaveUp(lastChange, now) || smelt instanceof SmeltInSmokerTask task && task.outOfFuel()) {
            Debug.logMessage("Cooking the " + smelting.getDescriptionId() + " is going nowhere, leaving it raw for a while.");
            CookTrip.suspend(now);
            gaveUp = true;
            // a long coal trip with the meat already in the station: it is not in the bag and not a job, so without this it sits
            // there unlit for the rest of the run and the cook need never comes back for it (raw reads 0)
            recordLeftBehind(mod);
            smelt = null;
            return null;
        }
        setDebugState("Cooking " + smelting.getDescriptionId());
        return smelt;
    }

    // the cook is being dropped with the current smelt part way through: if it already put meat in a station, leave a job for it
    // (once, the smelt forgets it recorded). FurnaceWatch asks this when it gives up on a cook, before it breaks the furnace
    public boolean recordLeftBehind(AltoClef mod) {
        if (smelt instanceof AsyncSmelting.Handoff handoff && handoff.recordLeftBehind(mod)) {
            smelt = null;
            return true;
        }
        return false;
    }

    // true once the smelt loaded the station and let go of it (see AsyncSmelting.Handoff)
    private boolean handedOff() {
        return isHandoff(smelt);
    }

    static boolean isHandoff(Object smelt) {
        return smelt instanceof AsyncSmelting.Handoff handoff && handoff.handedOff();
    }

    // coming back to a smoker we saw our meat in with none of this kind left in the bag: the rest of it is in that smoker
    static boolean keepSmelt(boolean holdsMeat, boolean handedOff, int rawOfKindInBag) {
        return holdsMeat && !handedOff && rawOfKindInBag == 0;
    }

    // every road that hands back no child ends here, so the hud says why instead of sitting silent (the 35 s freeze left nothing
    // in the log at all). all three are finishes: the planner drops the need once the job lands or the station is let go of. if
    // it is still asking for us seconds later something upstream is holding a finished task, and the log says which kind
    private Task idle(long now, String why) {
        setDebugState(why);
        if (idleSince < 0) {
            idleSince = now;
        } else if (!idleLogged && now - idleSince >= IDLE_LOG_TICKS) {
            idleLogged = true;
            Debug.logInternal("cook: still asked for " + IDLE_LOG_TICKS / 20 + " s after finishing (" + why + ")");
        }
        return null;
    }

    private Task make(AltoClef mod, Item raw) {
        int rawCount = mod.getItemStorage().getItemCount(raw);
        Item cooked = FoodHelper.cookedForm(raw);
        // total of the cooked kind we want to end up holding, same sum CollectFoodTask uses
        int toSmelt = rawCount + mod.getItemStorage().getItemCount(cooked);
        SmeltTarget target = new SmeltTarget(new ItemTarget(cooked, toSmelt), new ItemTarget(raw, rawCount));
        if (smoker) {
            SmeltInSmokerTask task = new SmeltInSmokerTask(target);
            task.ignoreMaterials();
            task.keepToTheBag();
            return task;
        }
        SmeltInFurnaceTask task = new SmeltInFurnaceTask(target);
        task.ignoreMaterials();
        return task;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // the smelt task closes its own screen. a chain taking over for a bit is not the end of the cook, the station stays picked
        // (the pin stays), but the meat in it gets its job now either way: a fight that ends in a death never comes back to this
        // task, and meat in a station with no job is meat nobody knows about (also the planner moving on mid fuel trip). the load
        // finishing later records over it, FurnaceJobs.record keeps one job per spot
        recordLeftBehind(mod);
        if (!isInterrupting()) {
            CookTrip.release();
        }
    }

    @Override
    protected void onStopWhilePaused(AltoClef mod) {
        // paused under a chain long enough for the pin to go stale and then dropped: same meat-in-the-station case as onStop
        recordLeftBehind(mod);
        CookTrip.release();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // only reads state, FurnaceWatch asks this between ticks
        if (gaveUp) {
            return true;
        }
        // loaded and left: "finished" means the one input slot is full and the next kind waits for this batch to come out
        if (handedOff()) {
            return true;
        }
        // nothing raw left and nothing half cooked in front of us
        return isActive() && pickRaw(mod) == null && (smelt == null || smelt.isFinished(mod));
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CookRawFoodTask task && task.smoker == smoker;
    }

    @Override
    protected String toDebugString() {
        return "Cook the raw meat in a " + (smoker ? "smoker" : "furnace");
    }

    @Override
    protected String toHudString() {
        return "Cooking the raw meat";
    }
}
