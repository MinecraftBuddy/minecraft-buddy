package adris.altoclef.chains;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

public class PlayerInteractionFixChain extends TaskChain {
    // a slot task holds the cursor on purpose (crafting, container shuffles, drag splits) and a laggy server or a busy
    // cooldown easily makes that 1s+. at 1s this yanked the stack out from under whatever was working with it. raising
    // the number is the simple fix, skipping while a slot task is active would need a list of every task that holds it
    private static final double STACK_HELD_SECONDS = 3;
    private final TimerGame _stackHeldTimeout = new TimerGame(STACK_HELD_SECONDS);
    private final TimerGame _generalDuctTapeSwapTimeout = new TimerGame(30);
    private final TimerGame _shiftDepressTimeout = new TimerGame(10);
    private final TimerGame _betterToolTimer = new TimerGame(0);
    private final TimerGame _mouseMovingButScreenOpenTimeout = new TimerGame(1);
    private final KeptStackWatch _keptStackWatch = new KeptStackWatch();
    private ItemStack _lastHandStack = null;

    private Screen _lastScreen;
    private Rotation _lastLookRotation;

    public PlayerInteractionFixChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onStop(AltoClef mod) {

    }

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {

    }

    @Override
    protected void onTick(AltoClef mod) {
    }

    @Override
    public float getPriority(AltoClef mod) {

        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

//        if (mod.getUserTaskChain().isActive() && _betterToolTimer.elapsed()) {
//            // Equip the right tool for the job if we're not using one.
//            _betterToolTimer.reset();
//            if (mod.getControllerExtras().isBreakingBlock()) {
//                BlockState state = mod.getWorld().getBlockState(mod.getControllerExtras().getBreakingBlockPos());
//                Optional<Slot> bestToolSlot = StorageHelper.getBestToolSlot(mod, state);
//                Slot currentEquipped = PlayerSlot.getEquipSlot();
//
//                // if baritone is running, only accept tools OUTSIDE OF HOTBAR!
//                // Baritone will take care of tools inside the hotbar.
//                if (bestToolSlot.isPresent() && !bestToolSlot.get().equals(currentEquipped)) {
//                    // ONLY equip if the item class is STRICTLY different (otherwise we swap around a lot)
//                    if (StorageHelper.getItemStackInSlot(currentEquipped).getItem() != StorageHelper.getItemStackInSlot(bestToolSlot.get()).getItem()) {
//                        boolean isAllowedToManage = (!mod.getClientBaritone().getPathingBehavior().isPathing() ||
//                                bestToolSlot.get().getInventorySlot() >= 9) && !mod.getFoodChain().isTryingToEat();
//                        if (isAllowedToManage) {
//                            Debug.logMessage("Found better tool in inventory, equipping.");
//                            ItemStack bestToolItemStack = StorageHelper.getItemStackInSlot(bestToolSlot.get());
//                            Item bestToolItem = bestToolItemStack.getItem();
//                            mod.getSlotHandler().forceEquipItem(bestToolItem);
//                        }
//                    }
//                }
//            }
//        }

        // Unpress shift (it gets stuck for some reason???)
        if (mod.getInputControls().isHeldDown(Input.SNEAK)) {
            if (mod.getInputControls().isHeldBy(Input.SNEAK, WorldSurvivalChain.MAGMA_SNEAK)) {
                // the magma sneak is held on purpose for as long as the floor hurts, one tick of this letting go is a burn
                _shiftDepressTimeout.reset();
            } else if (_shiftDepressTimeout.elapsed()) {
                mod.getInputControls().release(Input.SNEAK);
            }
        } else {
            _shiftDepressTimeout.reset();
        }

        // Refresh inventory
        if (_generalDuctTapeSwapTimeout.elapsed()) {
            // not with a container open: a screen Slot doesn't know (hopper, brewing stand, a villager) maps our slots as if it
            // were the inventory and the 72 clicks land in the container, and on the ones it does know they reset the slot
            // cooldown under whatever task is working the screen. the timer stays elapsed, it goes the tick the screen shuts
            // the clicks go to containerMenu, so a menu still open behind a closed screen counts too
            boolean containerOpen = Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>
                    || mod.getPlayer().containerMenu != mod.getPlayer().inventoryMenu;
            // and not mid bite: 72 slot clicks cut the use short
            boolean eating = mod.getFoodChain().isTryingToEat() || mod.getPlayer().isUsingItem();
            if (!mod.getControllerExtras().isBreakingBlock() && !containerOpen && !eating) {
                // this fires every 30s for as long as altoclef runs, chat is no place for it
                Debug.logInternal("Refreshed inventory...");
                mod.getSlotHandler().refreshInventory();
                _generalDuctTapeSwapTimeout.reset();
                return Float.NEGATIVE_INFINITY;
            }
        }

        ItemStack currentStack = StorageHelper.getItemStackInCursorSlot();

        if (currentStack != null && !currentStack.isEmpty()) {
            //noinspection PointlessNullCheck
            if (_lastHandStack == null || !ItemStack.matches(currentStack, _lastHandStack)) {
                // We're holding a new item in our stack!
                _stackHeldTimeout.reset();
                _keptStackWatch.reset();
                _lastHandStack = currentStack.copy();
            }
        } else {
            _stackHeldTimeout.reset();
            _keptStackWatch.reset();
            _lastHandStack = null;
        }

        // If we have something in our hand for a period of time...
        if (_lastHandStack != null && _stackHeldTimeout.elapsed()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(_lastHandStack, false);
            if (moveTo.isPresent()) {
                mod.getSlotHandler().clickSlot(moveTo.get(), 0, ContainerInput.PICKUP);
                return Float.NEGATIVE_INFINITY;
            }
            if (ItemHelper.canDropStack(mod, StorageHelper.getItemStackInCursorSlot())) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                return Float.NEGATIVE_INFINITY;
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            if (garbage.isPresent()) {
                mod.getSlotHandler().clickSlot(garbage.get(), 0, ContainerInput.PICKUP);
                return Float.NEGATIVE_INFINITY;
            }
            // a kept stack with a full bag and nothing to swap it with stays in hand for a while. throwing it was the old
            // last resort and it threw iron and food away. whoever is working with it can deal, or the bag frees up.
            // but a screen that never closes freezes the bot, so after 10s vanilla gets to drop it
            if (_keptStackWatch.tick()) {
                ItemStack kept = StorageHelper.getItemStackInCursorSlot();
                Debug.logMessage("Bag is full and nothing to swap, dropping the held " + kept.getCount() + "x " + kept.getItem().getDescriptionId());
                if (Minecraft.getInstance().gui.screen() != null) {
                    StorageHelper.closeScreen();
                } else {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                }
            }
            return Float.NEGATIVE_INFINITY;
        }

        if (shouldCloseOpenScreen(mod)) {
            //Debug.logMessage("Closed screen since we changed our look.");
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (!cursorStack.isEmpty()) {
                Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
                if (moveTo.isPresent()) {
                    mod.getSlotHandler().clickSlot(moveTo.get(), 0, ContainerInput.PICKUP);
                    return Float.NEGATIVE_INFINITY;
                }
                if (ItemHelper.canDropStack(mod, cursorStack)) {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                    return Float.NEGATIVE_INFINITY;
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                // Try throwing away cursor slot if it's garbage
                if (garbage.isPresent()) {
                    mod.getSlotHandler().clickSlot(garbage.get(), 0, ContainerInput.PICKUP);
                    return Float.NEGATIVE_INFINITY;
                }
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
            } else {
                StorageHelper.closeScreen();
            }
            return Float.NEGATIVE_INFINITY;
        }

        return Float.NEGATIVE_INFINITY;
    }

    private boolean shouldCloseOpenScreen(AltoClef mod) {
        if (!Baritone.settings().altoAutoCloseScreenWhenLookingOrMining.value)
            return false;
        // Only check look if we've had the same screen open for a while
        Screen openScreen = Minecraft.getInstance().gui.screen();
        if (openScreen != _lastScreen) {
            _mouseMovingButScreenOpenTimeout.reset();
        }
        // We're in the player screen/a screen we DON'T want to cancel out of
        if (openScreen == null || openScreen instanceof ChatScreen || openScreen instanceof PauseScreen || openScreen instanceof DeathScreen) {
            _mouseMovingButScreenOpenTimeout.reset();
            return false;
        }
        // Check for rotation change
        Rotation look = LookHelper.getLookRotation();
        if (_lastLookRotation != null && _mouseMovingButScreenOpenTimeout.elapsed()) {
            Rotation delta = look.subtract(_lastLookRotation);
            if (Math.abs(delta.getYaw()) > 0.1f || Math.abs(delta.getPitch()) > 0.1f) {
                _lastLookRotation = look;
                return true;
            }
            // do NOT update our last look rotation, just because we want to measure long term rotation.
        } else {
            _lastLookRotation = look;
        }
        _lastScreen = openScreen;
        return false;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getName() {
        return "Hand Stack Fix Chain";
    }

    @Override
    public String getHudName() {
        return "Tidying up";
    }
}
