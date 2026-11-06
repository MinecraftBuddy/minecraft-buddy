package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasks.speedrun.gamer.FurnaceJobs;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan.Mode;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.FuelPolicy;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.FurnaceSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.Optional;

// walks to ONE specific furnace we loaded earlier (AsyncSmelting) and empties it. it never places or crafts a furnace and
// never picks a different one, that is the difference from the smelt tasks: a furnace that is gone is the caller's problem
// (FurnaceWatch checks before it hands this out). the furnace's own slots are the truth about what is left to cook, and what to do
// about them is FurnacePlan.atStation: this only reads the slots, clicks, and walks
public class CollectFromFurnaceTask extends Task {
    private final BlockPos pos;
    private final Block block;
    private final Mode mode;
    private final String kind;
    private final long waitTicks;
    private final long capTicks;
    private InteractWithBlockTask open;
    private boolean done;
    private int inputLeft;
    // ticks until that input is out, read off the cook arrow when we let go. the job gets re-stamped with it
    private long leftTicks;
    private boolean cappedOut;
    // the last look at the slots while the screen was open: input plus output still in the station (what is left to come back for),
    // and the ticks the input still needs. -1 = never had the screen open. FurnaceWatch keeps the job honest off it, so a visit that
    // gets cut off (a mob, a death) does not leave the job counting what is already in the bag
    private int seenLeft = -1;
    private long seenTicks;
    // the input came back out because the station was not cooking it (cold with no fuel, or it never finished): not a visit that
    // just chose to leave, FurnaceWatch backs the cook off after one of these or the same cook starts again at once
    private boolean tookBackStalled;
    // the fuel we are putting into a cold station instead of taking its input back out
    private Task feeding;
    private long waitingSince = -1;
    // WAIT_ALL stands with the screen closed until this game tick, -1 = not idling
    private long idleUntil = -1;
    // the fuel the visit picked to put in (FurnacePlan.atStation asks whether there is any)
    private FuelPolicy.Pick feedPick;
    // the call this visit is in, and the change nobody has read yet
    private FurnacePlan.Call visitCall;
    // the last act + fire reading we logged, one line per change (FurnacePlan.visitKey)
    private String visitKey;
    private Said said;

    // a change of call during the visit, for the log
    public record Said(FurnacePlan.Call call, String text) {
    }

    // waitTicks: "nearly done" for NORMAL and TAKE_ALL. capTicks: the most we stand there once we started waiting, a furnace
    // that never finishes (no fuel, a chunk that stopped ticking) must not hold the bot for ever
    public CollectFromFurnaceTask(BlockPos pos, Block block, String kind, Mode mode, long waitTicks, long capTicks) {
        this.pos = pos;
        this.block = block;
        this.kind = kind;
        this.mode = mode;
        this.waitTicks = waitTicks;
        this.capTicks = capTicks;
    }

    // standing at the station waiting for it (the screen open, or closed between looks), not walking to it and not done
    public boolean waiting() {
        return waitingSince >= 0 && !done;
    }

    // input still in the furnace when we let go of it, 0 = nothing left to come back for. only meaningful once finished
    public int inputLeft() {
        return inputLeft;
    }

    // what the last open screen showed still in the station (input + output), -1 when it was never open. see the field
    public int seenLeft() {
        return seenLeft;
    }

    // the ticks that input still needs, as of the same look
    public long seenTicks() {
        return seenTicks;
    }

    // how long the input we left behind still needs. only meaningful once finished with inputLeft above 0
    public long leftTicks() {
        return leftTicks;
    }

    // we stood there until the cap ran out and let go with input still in the slot (not a visit that chose to leave it). the
    // caller counts these, a furnace that does this twice is not cooking
    public boolean cappedOut() {
        return cappedOut;
    }

    // we took the input back out because the station was cold or never finished, see the field
    public boolean tookBackStalled() {
        return tookBackStalled;
    }

    // the latest change of call this visit made (waiting, refueling, taking it back...), handed over once. FurnaceWatch asks every
    // tick and puts it in the log next to the call the trip started with
    public Said said() {
        Said out = said;
        said = null;
        return out;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        // the furnace is the one thing here we must not mine, whatever path we take to it
        mod.getBehaviour().avoidBlockBreaking(pos);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (done) {
            return null;
        }
        if (mod.getPlayer().containerMenu instanceof AbstractFurnaceMenu) {
            return atFurnace(mod);
        }
        if (idleUntil >= 0) {
            if (mod.getWorld().getGameTime() < idleUntil) {
                setDebugState("Waiting by the furnace, screen closed");
                return null;
            }
            idleUntil = -1;
        }
        if (mod.getFoodChain().needsToEat()) {
            setDebugState("Eating first");
            return null;
        }
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty()) {
            Optional<Slot> fit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
            if (fit.isEmpty()) {
                return new EnsureFreeInventorySlotTask();
            }
            mod.getSlotHandler().clickSlot(fit.get(), 0, ContainerInput.PICKUP);
            return null;
        }
        setDebugState("Walking to the furnace");
        if (open == null) {
            open = new InteractWithBlockTask(pos);
        }
        return open;
    }

    // the slots in, FurnacePlan.atStation decides, this does it
    private Task atFurnace(AltoClef mod) {
        ItemStack output = StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT);
        ItemStack input = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_MATERIALS);
        ItemStack fuel = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_FUEL);
        boolean lit = StorageHelper.getFurnaceFuel() > 0;
        long now = mod.getWorld().getGameTime();
        boolean hasInput = !input.isEmpty();
        seenLeft = input.getCount() + output.getCount();
        seenTicks = hasInput ? remainingTicks(input) : 0;
        FurnacePlan.Look look = new FurnacePlan.Look(!output.isEmpty(), input.getCount(), lit, fuel.isEmpty(),
                !fuel.isEmpty() && AltoSettings.isSupportedFuel(fuel.getItem()), hasInput ? remainingTicks(input) : 0,
                hasInput ? untilDry(input, fuel) : Long.MAX_VALUE, waitingSince >= 0 ? now - waitingSince : -1);
        boolean feedingNow = feeding != null && fuel.isEmpty() && !lit && !feeding.isFinished(mod);
        FurnacePlan.Act act = FurnacePlan.atStation(look, mode, waitTicks, capTicks, feeding != null, feedingNow, () -> {
            // meat left cold, and the bag has the fuel for all of it by now (the coal trip that outlasted the cook): light it instead
            // of carrying the meat out and walking it back in. once per visit, a click that did not take is not retried
            feedPick = FuelPolicy.chooseCovering(mod.getItemStorage().getItemStacksPlayerInventory(true), input.getCount(),
                    AltoSettings::isSupportedFuel, ItemHelper::getFuelAmount);
            return feedPick != null;
        });
        String key = FurnacePlan.visitKey(act, look);
        if (!key.equals(visitKey)) {
            visitKey = key;
            Debug.logInternal("furnace visit at " + pos.toShortString() + ": " + FurnacePlan.visitState(act, look));
        }
        if (!act.quiet && act.call != visitCall) {
            visitCall = act.call;
            said = new Said(act.call, FurnacePlan.visitText(act, look));
        }
        switch (act) {
            case TAKE_OUTPUT:
                return takeOut(mod, FurnaceSlot.OUTPUT_SLOT, output, "Taking what is done");
            case WAIT:
                // (a food leave waits out the whole cook, that is minutes and not the few seconds the open screen is meant for)
                return waitHere(mod, mode == Mode.WAIT_ALL || waitTicks >= FurnacePlan.FOOD_LEAVE_TICKS);
            case LEAVE_CAPPED:
                inputLeft = input.getCount();
                leftTicks = FurnacePlan.leftTicks(act, look);
                cappedOut = true;
                done = true;
                return null;
            case KEEP_FEEDING:
                return feeding;
            case FEED:
                setDebugState("Putting the fuel in");
                feeding = new MoveItemToSlotFromInventoryTask(new ItemTarget(feedPick.stack().getItem(), feedPick.count()), FurnaceSlot.INPUT_SLOT_FUEL);
                return feeding;
            case TAKE_BACK_STALLED:
                // the failure: the caller backs the cook off (tookBackStalled)
                tookBackStalled = true;
                // the raw stuff goes back in the bag, the planner sees it there and smelts it again
                return takeOut(mod, FurnaceSlot.INPUT_SLOT_MATERIALS, input, "Taking the unfinished input back");
            case TAKE_BACK:
                // just us leaving
                return takeOut(mod, FurnaceSlot.INPUT_SLOT_MATERIALS, input, "Taking the unfinished input back");
            case LEAVE_COOKING:
                // not close to done (it may have sat in an unloaded chunk and barely cooked): leave it, the job gets the real time
                inputLeft = input.getCount();
                leftTicks = FurnacePlan.leftTicks(act, look);
                done = true;
                return null;
            case TAKE_SPARE_FUEL:
                return takeOut(mod, FurnaceSlot.INPUT_SLOT_FUEL, fuel, "Taking the spare fuel");
            default:
                inputLeft = 0;
                done = true;
                return null;
        }
    }

    // closed = let go of the screen and stand next to the furnace until the next output is due (or the reopen timer, whichever
    // is first). the gui open for 6 minutes was a bot that could not eat, could not see a creeper and looked afk to the
    // server. the short "nearly done" wait of the other modes keeps the screen, it is a few seconds
    private Task waitHere(AltoClef mod, boolean closed) {
        long now = mod.getWorld().getGameTime();
        if (waitingSince < 0) {
            waitingSince = now;
        }
        if (!closed) {
            setDebugState("Waiting for the furnace");
            return null;
        }
        // the arrow is already 0..1 (StorageHelper.getFurnaceCookPercent), dividing it by 24 read every item as just started
        double arrow = Math.min(1.0, Math.max(0, StorageHelper.getFurnaceCookPercent()));
        long nextOutput = Math.round(FurnaceJobs.ticksPerItem(kind) * (1.0 - arrow));
        idleUntil = now + FurnacePlan.idleTicks(nextOutput);
        StorageHelper.closeScreen();
        setDebugState("Waiting by the furnace, screen closed");
        return null;
    }

    // shift click, the furnace menu sends it to the inventory in one go. a full bag would make that a silent no-op for ever
    private Task takeOut(AltoClef mod, Slot slot, ItemStack stack, String why) {
        if (mod.getItemStorage().getSlotThatCanFitInPlayerInventory(stack, false).isEmpty()) {
            return new EnsureFreeInventorySlotTask();
        }
        setDebugState(why);
        mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.QUICK_MOVE);
        return null;
    }

    private long untilDry(ItemStack input, ItemStack fuel) {
        // the smoker and the blast furnace read their fire at their own scale (StorageHelper.litItems)
        boolean fast = FurnaceJobs.ticksPerItem(kind) == FurnaceJobs.FAST_TICKS;
        double lit = fast ? StorageHelper.getSmokerFuel() : StorageHelper.getFurnaceFuel();
        double slot = fuel.isEmpty() ? 0 : ItemHelper.getFuelAmount(fuel);
        return FurnacePlan.fuelTicks(kind, input.getCount(), StorageHelper.getFurnaceCookPercent(), lit, slot);
    }

    // cook progress of the item that is cooking right now, 0..1. it used to be 24 pixels and the /24 outlived that,
    // which made every wake up and re-stamp up to a whole item late (the ~5 s off on a smoker)
    private long remainingTicks(ItemStack input) {
        double arrow = Math.min(1.0, Math.max(0, StorageHelper.getFurnaceCookPercent()));
        return FurnaceJobs.remainingTicks(kind, input.getCount(), arrow);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        if (mod.getPlayer().containerMenu instanceof AbstractFurnaceMenu) {
            StorageHelper.closeScreen();
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return done;
    }

    @Override
    protected boolean isEqual(Task other) {
        // a finished visit is not "the same task" as a fresh one at the same furnace: Task keeps the equal child, and a done one
        // returns null every tick, so it would sit on top for ever
        return other instanceof CollectFromFurnaceTask task && !task.done && task.pos.equals(pos) && task.mode == mode && task.block == block;
    }

    @Override
    protected String toHudString() {
        return "Collecting from the furnace";
    }

    @Override
    protected String toDebugString() {
        return "Collecting from the furnace at " + pos.toShortString();
    }
}
