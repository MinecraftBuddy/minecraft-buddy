package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.BrewRules;
import adris.altoclef.util.helpers.PotionHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.BrewingStandSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import baritone.Baritone;
import baritone.api.utils.Dimension;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;

// fire resistance from the water bottles in the bag: a brewing stand (a rod and three stone), a blaze powder for its fuel, a
// nether wart (water to awkward), a magma cream (awkward to fire resistance) and, if the bag has one, a redstone dust (three
// minutes to eight). each step is twenty seconds, the three bottles brew at once. the stand brews on while the screen is shut,
// so a fight in between costs nothing but the walk back; the stand with our bottles in it is remembered across instances (an
// order stopped and given again is a new task)
public class BrewFireResistanceTask extends ResourceTask {

    private final int _count;
    private final BrewInStand _inner = new BrewInStand();

    // the stand our bottles stand in, what they were when last seen and its fuel then (-1: not seen)
    private static BlockPos _stand;
    private static Dimension _standDim;
    private static BrewRules.Stage _standStage = BrewRules.Stage.NONE;
    private static int _standFuel = -1;
    // this batch is out of the stand and in the bag
    private boolean _done;

    public BrewFireResistanceTask(int count) {
        super(Items.POTION, Math.min(count, BrewRules.BATCH));
        _count = Math.min(count, BrewRules.BATCH);
    }

    public static int fireRes(AltoClef mod) {
        return PotionHelper.count(mod, Potions.FIRE_RESISTANCE) + PotionHelper.count(mod, Potions.LONG_FIRE_RESISTANCE);
    }

    // bottles that a brew makes fire resistance of: water and awkward
    public static int bases(AltoClef mod) {
        return PotionHelper.count(mod, Potions.WATER) + PotionHelper.count(mod, Potions.AWKWARD);
    }

    // our bottles stand in a stand in this dimension
    public static boolean pending() {
        return _stand != null && _standDim == WorldHelper.getCurrentDimension();
    }

    public static BlockPos pendingStand() {
        return pending() ? _stand : null;
    }

    // the buddy's fork: the stand the brew box put in its front cell (BrewBoxTask, Tuning brew_box), used before any other stand
    // about until our bottles are in it; null with none (always with the tuning off)
    private static BlockPos _boxStand;

    public static void pinBoxStand(BlockPos at) {
        _boxStand = at;
    }

    private static void forget(String why) {
        if (_stand != null) Debug.logInternal("brew: stand at " + _stand.toShortString() + " forgotten, " + why);
        _stand = null;
        _standDim = null;
        _standStage = BrewRules.Stage.NONE;
        _standFuel = -1;
    }

    private static BrewRules.Stage bagStage(AltoClef mod) {
        return BrewRules.stage(PotionHelper.count(mod, Potions.WATER), PotionHelper.count(mod, Potions.AWKWARD), 0, 0);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return !pending() && (_done || fireRes(mod) >= _count);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    // a brew that wants a magma cream with none in the bag. the catalogue's fetch for it searches the basalt deltas chunk by
    // chunk and walks hundreds of blocks from the fortress (live watch, 7 Nov 2026: 400 blocks in three minutes with the
    // bottles left in the stand), so the caller gives the brew up instead of letting this fetch run
    public static boolean wantsCream(AltoClef mod) {
        BrewRules.Stage stage = pending() ? _standStage : bagStage(mod);
        return BrewRules.needsCream(stage) && !mod.getItemStorage().hasItem(Items.MAGMA_CREAM);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (mod.getPlayer().containerMenu instanceof BrewingStandMenu) {
            setDebugState("At the brewing stand");
            return _inner;
        }
        // broken while we were away (a ghast, a creeper): what was in it lies about or burnt
        if (pending() && mod.getChunkTracker().isChunkLoaded(_stand) && !mod.getWorld().getBlockState(_stand).is(Blocks.BREWING_STAND)) {
            forget("the block is gone");
        }
        BrewRules.Stage stage = pending() ? _standStage : bagStage(mod);
        if (stage == BrewRules.Stage.NONE && !pending()) {
            if (fireRes(mod) > 0) {
                // nothing left to brew from and something brewed: that is the batch
                _done = true;
                return null;
            }
            setDebugState("Getting water bottles");
            return TaskCatalogue.getItemTask("water_bottle", _count);
        }
        if (stage == BrewRules.Stage.WATER && !mod.getItemStorage().hasItem(Items.NETHER_WART)) {
            setDebugState("Getting a nether wart");
            return TaskCatalogue.getItemTask("nether_wart", 1);
        }
        if (BrewRules.needsCream(stage) && !mod.getItemStorage().hasItem(Items.MAGMA_CREAM)) {
            setDebugState("Getting a magma cream");
            return TaskCatalogue.getItemTask("magma_cream", 1);
        }
        if (BrewRules.needsFuel(pending() ? _standFuel : -1, stage, mod.getItemStorage().hasItem(Items.REDSTONE)) && !mod.getItemStorage().hasItem(Items.BLAZE_POWDER)) {
            setDebugState("Getting blaze powder for the stand");
            return TaskCatalogue.getItemTask("blaze_powder", 1);
        }
        setDebugState(pending() ? "Back to the brewing stand" : "Brewing fire resistance");
        return _inner;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        // a fight or a bite took over: the stand brews on without us, the screen is no place to fight from
        if (mod.getPlayer() != null && mod.getPlayer().containerMenu instanceof BrewingStandMenu) {
            StorageHelper.closeScreen();
        }
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof BrewFireResistanceTask task && task._count == _count;
    }

    @Override
    protected String toHudString() {
        return "Brewing fire resistance";
    }

    @Override
    protected String toDebugStringName() {
        return "Brew " + _count + " fire resistance";
    }

    private class BrewInStand extends DoStuffInContainerTask {

        private final TimerGame _moveTimer = new TimerGame(0);
        // an ingredient that sits in its slot with nothing brewing: it fits no bottle (or the fuel is out), so it comes back out
        private int _idleSince = -1;

        BrewInStand() {
            super(Blocks.BREWING_STAND, new ItemTarget("brewing_stand"));
        }

        @Override
        protected BlockPos pinnedStation(AltoClef mod) {
            BlockPos pending = pendingStand();
            return pending != null ? pending : _boxStand;
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            return true;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return mod.getPlayer().containerMenu instanceof BrewingStandMenu;
        }

        @Override
        protected String toHudString() {
            return BrewFireResistanceTask.this.toHudString();
        }

        // it costs a blaze rod: one nobody else placed is worth a longer walk than a table
        @Override
        protected double worldReach() {
            return 48;
        }

        @Override
        protected Task containerSubTask(AltoClef mod) {
            if (!(mod.getPlayer().containerMenu instanceof BrewingStandMenu menu)) return null;
            // a move under way is seen through every tick: handed back only now and then, it lost its cursor stack to the tidy
            // up below and started over, forever. the fuel is in once the stand has burnt it (it takes the powder the next tick)
            if (_move != null) {
                boolean fuelled = _moveIsFuel && (menu.getFuel() > 0 || !StorageHelper.getItemStackInSlot(BrewingStandSlot.FUEL).isEmpty());
                if (!fuelled && !_move.isFinished(mod) && _moveTicks++ < MOVE_TICKS) return _move;
                _move = null;
                _moveTimer.reset();
            }
            _moveTimer.setInterval(Baritone.settings().altoContainerItemMoveDelay.value);
            if (!_moveTimer.elapsed() || !mod.getSlotHandler().canDoSlotAction()) return null;

            // whatever a move left on the cursor goes back to the bag
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            if (!cursor.isEmpty()) {
                Optional<Slot> to = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
                if (to.isPresent()) {
                    click(mod, to.get(), ContainerInput.PICKUP);
                    return null;
                }
            }

            ItemStack[] bottles = {
                    StorageHelper.getItemStackInSlot(BrewingStandSlot.LEFT_POTION),
                    StorageHelper.getItemStackInSlot(BrewingStandSlot.MIDDLE_POTION),
                    StorageHelper.getItemStackInSlot(BrewingStandSlot.RIGHT_POTION)};
            ItemStack ingredient = StorageHelper.getItemStackInSlot(BrewingStandSlot.INGREDIENT);
            ItemStack fuelStack = StorageHelper.getItemStackInSlot(BrewingStandSlot.FUEL);
            int water = 0, awkward = 0, fire = 0, longFire = 0, ours = 0;
            for (ItemStack b : bottles) {
                if (PotionHelper.is(b, Potions.WATER)) water++;
                else if (PotionHelper.is(b, Potions.AWKWARD)) awkward++;
                else if (PotionHelper.is(b, Potions.FIRE_RESISTANCE)) fire++;
                else if (PotionHelper.is(b, Potions.LONG_FIRE_RESISTANCE)) longFire++;
                else continue;
                ours++;
            }
            int fuel = menu.getFuel();
            int brewing = menu.getBrewingTicks();
            BlockPos at = getTargetContainerPosition();
            if (ours > 0 && at != null) {
                _stand = at;
                _standDim = WorldHelper.getCurrentDimension();
            }
            // (the bottles as they will be once the ingredient in its slot has brewed: BrewRules.after)
            BrewRules.Ingredient inSlot = ingredient.is(Items.NETHER_WART) ? BrewRules.Ingredient.WART
                    : ingredient.is(Items.MAGMA_CREAM) ? BrewRules.Ingredient.CREAM
                    : ingredient.is(Items.REDSTONE) ? BrewRules.Ingredient.REDSTONE : null;
            _standStage = BrewRules.after(BrewRules.stage(water, awkward, fire, longFire), inSlot);
            _standFuel = fuel + (fuelStack.is(Items.BLAZE_POWDER) ? BrewRules.BREWS_PER_POWDER * fuelStack.getCount() : 0);

            // the fuel: one powder is twenty brews
            if (fuel <= 0 && fuelStack.isEmpty()) {
                if (mod.getItemStorage().hasItem(Items.BLAZE_POWDER)) {
                    setDebugState("Fuelling the stand");
                    return move(new MoveItemToSlotFromInventoryTask(new ItemTarget(Items.BLAZE_POWDER, 1), BrewingStandSlot.FUEL), true);
                }
                setDebugState("No blaze powder for the stand");
                StorageHelper.closeScreen();
                return null;
            }
            if (brewing > 0) {
                _idleSince = -1;
                setDebugState("Brewing, " + (brewing + 19) / 20 + " s");
                return null;
            }
            // the bottles: water first, then awkward, into the empty places, as many as the batch. a shift click, which the stand
            // sends to the first free bottle place: a bottle on the cursor is one of the task's own potions to ResourceTask, which
            // put it back in the bag before the second click could set it down
            // (and only into a stand with nothing brewed in it: taking the finished ones out one by one left a place free, and
            // the bag's other water bottles went in for a second batch)
            if (!ingredientIn(ingredient) && ours < _count && awkward + fire + longFire == 0 && emptyBottleSlot(bottles) != null) {
                Slot from = bagBottle(mod);
                if (from != null) {
                    setDebugState("Putting a bottle in");
                    click(mod, from, ContainerInput.QUICK_MOVE);
                    return null;
                }
            }
            if (ingredientIn(ingredient)) {
                // put in a moment ago and the stand has not started yet, or it fits nothing: give it two seconds
                int now = WorldHelper.getTicks();
                if (_idleSince < 0) _idleSince = now;
                if (now - _idleSince < BrewRules.IDLE_INGREDIENT_TICKS) {
                    setDebugState("Waiting for the stand to start");
                    return null;
                }
                _idleSince = -1;
                setDebugState("Taking back an ingredient that brews nothing");
                click(mod, BrewingStandSlot.INGREDIENT, ContainerInput.QUICK_MOVE);
                return null;
            }
            _idleSince = -1;
            BrewRules.Ingredient step = BrewRules.next(water, awkward, fire, mod.getItemStorage().hasItem(Items.REDSTONE));
            if (step != null) {
                Item next = switch (step) {
                    case WART -> Items.NETHER_WART;
                    case CREAM -> Items.MAGMA_CREAM;
                    case REDSTONE -> Items.REDSTONE;
                };
                if (mod.getItemStorage().hasItem(next)) {
                    // (the stand's stage as it will be with this in its slot: the screen shut a tick after the wart went in, by
                    // a bite, and the stage read before the click still wanted a wart. the brew walked for the fortress's wart
                    // from inside its shut box, no path, seven minutes, piece2in-1 run 8. a click that failed is read again
                    // from the slots at the next visit)
                    _standStage = BrewRules.after(BrewRules.stage(water, awkward, fire, longFire), step);
                    setDebugState("Adding " + next.getDescriptionId());
                    return move(new MoveItemToSlotFromInventoryTask(new ItemTarget(next, 1), BrewingStandSlot.INGREDIENT), false);
                }
                // the outer task fetches it (the wart, the cream). the bottles wait in the stand
                setDebugState("Missing " + next.getDescriptionId());
                StorageHelper.closeScreen();
                return null;
            }
            // brewed as far as it goes: the potions into the bag
            for (Slot s : new Slot[]{BrewingStandSlot.LEFT_POTION, BrewingStandSlot.MIDDLE_POTION, BrewingStandSlot.RIGHT_POTION}) {
                ItemStack b = StorageHelper.getItemStackInSlot(s);
                if (PotionHelper.is(b, Potions.FIRE_RESISTANCE) || PotionHelper.is(b, Potions.LONG_FIRE_RESISTANCE)) {
                    setDebugState("Taking the potions");
                    click(mod, s, ContainerInput.QUICK_MOVE);
                    return null;
                }
            }
            Debug.logInternal("brew: done, " + fireRes(mod) + " fire resistance in the bag");
            forget("brewed");
            _done = true;
            StorageHelper.closeScreen();
            return null;
        }

        private Task _move;
        private boolean _moveIsFuel;
        private int _moveTicks;
        // a move that has not landed in this long is let go and looked at again
        private static final int MOVE_TICKS = 100;

        private Task move(Task move, boolean fuel) {
            _move = move;
            _moveIsFuel = fuel;
            _moveTicks = 0;
            return move;
        }

        private void click(AltoClef mod, Slot slot, ContainerInput type) {
            mod.getSlotHandler().clickSlot(slot, 0, type);
            _moveTimer.reset();
        }

        private boolean ingredientIn(ItemStack ingredient) {
            return !ingredient.isEmpty();
        }

        private Slot emptyBottleSlot(ItemStack[] bottles) {
            Slot[] slots = {BrewingStandSlot.LEFT_POTION, BrewingStandSlot.MIDDLE_POTION, BrewingStandSlot.RIGHT_POTION};
            for (int i = 0; i < 3; i++) if (bottles[i].isEmpty()) return slots[i];
            return null;
        }

        // a bag slot with a water bottle, else one with an awkward potion
        private Slot bagBottle(AltoClef mod) {
            List<Slot> slots = mod.getItemStorage().getSlotsWithItemPlayerInventory(false, Items.POTION);
            for (Slot s : slots) if (PotionHelper.is(StorageHelper.getItemStackInSlot(s), Potions.WATER)) return s;
            for (Slot s : slots) if (PotionHelper.is(StorageHelper.getItemStackInSlot(s), Potions.AWKWARD)) return s;
            return null;
        }
    }
}
