package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.helpers.ThrowAim;
import adris.altoclef.util.slots.Slot;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

public class EnsureFreeInventorySlotTask extends Task {

    // armor we would wear is coming in: with no garbage the lowest stack we do not hold dear goes (StorageHelper.getLastResortSlot)
    private final boolean _lastResort;

    public EnsureFreeInventorySlotTask() {
        this(false);
    }

    // a full bag making room for a drop that StorageHelper.GarbageView.room already picked the stack for: throw exactly that one
    // and nothing else. checked every tick (the slot must still hold that stack), and no canDropStack veto, the room decision
    // was the veto
    private final Slot _evict;
    private final ItemStack _victim;

    public EnsureFreeInventorySlotTask(boolean lastResort) {
        _lastResort = lastResort;
        _evict = null;
        _victim = null;
    }

    public EnsureFreeInventorySlotTask(Slot evict, ItemStack victim) {
        _lastResort = false;
        _evict = evict;
        _victim = victim.copy();
    }

    private boolean _stuck = false;
    // where the next throw points, picked once so a tunnel's two equal ends can't flip us back and forth. cleared only once
    // the cursor is really empty (a click can be refused during the slot handler's move delay)
    private boolean _hasAim = false;
    private float _aimYaw;
    private int _aimSince;

    @Override
    protected void onStart(AltoClef mod) {
        _stuck = false;
        _hasAim = false;
    }

    private void pickAim(AltoClef mod) {
        if (_hasAim) return;
        _hasAim = true;
        _aimYaw = ThrowAim.pickYaw(mod, ThreadLocalRandom.current());
        _aimSince = WorldHelper.getTicks();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (_evict != null && cursorStack.isEmpty()) {
            return throwVictim(mod);
        }
        // something already on the cursor (or plain mode): the old way, no veto skipped for anyone
        Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
        if (garbage.isEmpty() && _lastResort) {
            garbage = StorageHelper.getLastResortSlot(mod);
        }
        boolean cursorThrowable = ItemHelper.canDropStack(mod, cursorStack);
        // only plain storage takes things back. a furnace input or a crafting grid would eat the stack (or hand it back
        // on close into a bag with no room), which is the loss this is here to stop
        boolean storageOpen = ContainerType.screenHandlerMatches(ContainerType.CHEST) || ContainerType.screenHandlerMatches(ContainerType.SHULKER);
        Optional<Slot> containerSlot = cursorStack.isEmpty() || !storageOpen ? Optional.empty()
                : mod.getItemStorage().getSlotThatCanFitInOpenContainer(cursorStack, false);
        FreeSlotPlan.Action action = FreeSlotPlan.plan(cursorStack.isEmpty(), cursorThrowable, garbage.isPresent(), containerSlot.isPresent());
        _stuck = action == FreeSlotPlan.Action.STUCK;
        // an empty cursor means whatever we threw is gone (a refused click leaves the stack in hand, so no re-pick there)
        if (cursorStack.isEmpty()) {
            _hasAim = false;
        }
        switch (action) {
            case CLICK_GARBAGE -> {
                setDebugState("Picking up garbage");
                pickAim(mod);
                mod.getSlotHandler().clickSlot(garbage.get(), 0, ContainerInput.PICKUP);
            }
            case THROW_CURSOR -> {
                setDebugState("Throwing the held stack");
                // a 1x1 pocket has no run to throw along, so the stack still comes back there. the bucket task has a
                // backstop for that
                pickAim(mod);
                if (aimed(mod)) {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                }
            }
            case PUT_BACK -> {
                setDebugState("Putting the held stack back");
                mod.getSlotHandler().clickSlot(containerSlot.get(), 0, ContainerInput.PICKUP);
            }
            default -> setDebugState("All items are protected.");
        }
        // held every tick, something else (baritone's look) may have turned us since
        if (_hasAim) {
            mod.getInputControls().forceLook(_aimYaw, ThrowAim.PITCH);
        }
        return null;
    }

    // what the server last heard from us, not the camera: the click goes out ahead of this tick's rotation packet,
    // and baritone's free look can send one angle and put another on the camera
    private boolean aimed(AltoClef mod) {
        var player = mod.getPlayer();
        var sent = mod.getClientBaritone().getLookBehavior().serverRotation();
        float sentYaw = sent.map(r -> r.getYaw()).orElse(player.getYRot());
        float sentPitch = sent.map(r -> r.getPitch()).orElse(player.getXRot());
        return ThrowAim.shouldThrow(ThrowAim.isAligned(sentYaw, sentPitch, _aimYaw, ThrowAim.PITCH), WorldHelper.getTicks() - _aimSince);
    }

    // the evict mode throws straight out of the slot (ctrl+Q), so the victim never sits on the cursor where a parent that
    // already sees the free slot could walk off and leave it there. a slot that no longer holds that exact stack ends us,
    // the pickup asks for room again with the bag as it is now
    private Task throwVictim(AltoClef mod) {
        if (!ItemStack.isSameItemSameComponents(StorageHelper.getItemStackInSlot(_evict), _victim) || StorageHelper.reservedForTheEnd(mod, _evict)) {
            _stuck = true;
            _hasAim = false;
            return null;
        }
        _stuck = false;
        setDebugState("Throwing out " + ItemHelper.stripItemName(_victim.getItem()) + " to make room");
        pickAim(mod);
        if (aimed(mod)) {
            mod.getSlotHandler().clickSlot(_evict, 1, ContainerInput.THROW);
        }
        mod.getInputControls().forceLook(_aimYaw, ThrowAim.PITCH);
        return null;
    }

    // nothing to throw and nowhere to put the held stack. a parent that asks isFinished (PickupFromContainerTask) moves
    // on, the rest were already going to wait on their own watchdogs, only now we stop clicking at nothing
    @Override
    public boolean isFinished(AltoClef mod) {
        return _stuck;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof EnsureFreeInventorySlotTask t && t._lastResort == _lastResort && Objects.equals(t._evict, _evict)
                && (t._victim == null ? _victim == null : _victim != null && ItemStack.isSameItemSameComponents(t._victim, _victim));
    }

    @Override
    protected String toDebugString() {
        return "Ensuring inventory is free";
    }

    @Override
    protected String toHudString() {
        return "Making room in the inventory";
    }

    @Override
    protected boolean isHudPlumbing() {
        return true;
    }
}
