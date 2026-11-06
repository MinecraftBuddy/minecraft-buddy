package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.PlaceWait;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Place a type of block nearby, anywhere.
 * <p>
 * Also known as the "bear strats" task.
 */
public class PlaceBlockNearbyTask extends Task {

    private final Block[] _toPlace;

    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final TimeoutWanderTask _wander = new TimeoutWanderTask(5);

    private final TimerGame _randomlookTimer = new TimerGame(0.25);
    private final Predicate<BlockPos> _canPlaceHere;
    private BlockPos _justPlaced; // Where we JUST placed a block.
    private BlockPos _tryPlace;   // Where we should TRY placing a block.
    // spots that let us down. the progress checker below sleeps while baritone paths, so on its own a bad spot was retried forever
    private final SpotFailures _failures = new SpotFailures();
    // so a spot that keeps us walking still runs out of time
    private final TimerGame _spotTimer = new TimerGame(SPOT_SECONDS);
    private static final double SPOT_SECONDS = 15;
    // the task working on _tryPlace, kept so we can ask it if it ran dry
    private PlaceBlockTask _placing;
    // ticks in a row something has stood in _tryPlace
    private int _blockedTicks;
    // Oof, necesarry for the onBlockPlaced action.
    private AltoClef _mod;
    private Subscription<BlockPlaceEvent> _onBlockPlaced;

    public PlaceBlockNearbyTask(Predicate<BlockPos> canPlaceHere, Block... toPlace) {
        _toPlace = toPlace;
        _canPlaceHere = canPlaceHere;
    }

    public PlaceBlockNearbyTask(Block... toPlace) {
        this(blockPos -> true, toPlace);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _progressChecker.reset();
        _failures.clear();
        _placing = null;
        // a restart is a new placement, the old one (still standing, probably) is not this run's doing
        _justPlaced = null;
        _tryPlace = null;
        _blockedTicks = 0;
        _spotTimer.reset();
        _mod = mod;
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);

        // Check for blocks being placed
        _onBlockPlaced = EventBus.subscribe(BlockPlaceEvent.class, evt -> {
            if (ArrayUtils.contains(_toPlace, evt.blockState.getBlock())) {
                stopPlacing(_mod);
            }
        });
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progressChecker.reset();
        }
        // Method:
        // - If looking at placable block
        //      Place immediately
        // Find a spot to place
        // - Prefer flat areas (open space, block below) closest to player
        // -

        // Close screen first
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            if (moveTo.isPresent()) {
                mod.getSlotHandler().clickSlot(moveTo.get(), 0, ContainerInput.PICKUP);
                return null;
            }
            if (ItemHelper.canDropStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                return null;
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            if (garbage.isPresent()) {
                mod.getSlotHandler().clickSlot(garbage.get(), 0, ContainerInput.PICKUP);
                return null;
            }
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
        } else {
            StorageHelper.closeScreen();
        }

        // Try placing where we're looking right now.
        BlockPos current = getCurrentlyLookingBlockPlace(mod);
        if (current != null && _canPlaceHere.test(current)) {
            setDebugState("Placing since we can...");
            if (mod.getSlotHandler().forceEquipItem(ItemHelper.blocksToItems(_toPlace))) {
                if (place(mod, current)) {
                    return null;
                }
            }
        }

        // Wander while we can.
        if (_wander.isActive() && !_wander.isFinished(mod)) {
            setDebugState("Wandering, will try to place again later.");
            _progressChecker.reset();
            return _wander;
        }
        // Fail check
        if (!_progressChecker.check(mod)) {
            Debug.logMessage("Failed placing, wandering and trying again.");
            LookHelper.randomOrientation(mod);
            failSpot(mod);
            return _wander;
        }

        // the checker above resets every tick baritone is pathing, so a spot that sends us walking in circles never trips
        // it. this clock doesn't care
        if (_tryPlace != null && _spotTimer.elapsed()) {
            Debug.logMessage("Placing at " + _tryPlace + " is taking forever, striking it.");
            failSpot(mod);
        }
        // the builder had nothing to place with here. dirt over the table spot was the old answer to this
        if (_placing != null && _placing.isStarved()) {
            failSpot(mod);
        }
        // a mob standing in the cell eats the click without a word. give it a moment to leave, then it's a bad spot like any
        // other (PlaceWait, 10 ticks)
        if (_tryPlace != null) {
            boolean blocked = !WorldHelper.entityFreeFor(mod.getWorld(), _tryPlace, placingState(), mod.getPlayer());
            _blockedTicks = PlaceWait.next(blocked, _blockedTicks);
            if (PlaceWait.judge(_blockedTicks, PlaceWait.TASK_PATIENCE) == PlaceWait.Verdict.GIVE_UP) {
                Debug.logMessage("Something is standing at " + _tryPlace + ", striking it.");
                _blockedTicks = 0;
                failSpot(mod);
            }
        } else {
            _blockedTicks = 0;
        }

        // Try to place at a particular spot.
        if (_tryPlace == null || !WorldHelper.canReach(mod, _tryPlace)) {
            _tryPlace = locateClosePlacePos(mod);
            _placing = null;
            _spotTimer.reset();
        }
        if (_tryPlace != null) {
            setDebugState("Trying to place at " + _tryPlace);
            // a table on a spot we picked needs no scaffolding. autocollect here sent us off to mine 32 dirt first
            if (_placing == null) {
                _placing = new PlaceBlockTask(_tryPlace, _toPlace, false, false);
            }
            return _placing;
        }

        // Look in random places to maybe get a random hit
        if (_randomlookTimer.elapsed()) {
            _randomlookTimer.reset();
            LookHelper.randomOrientation(mod);
        }

        setDebugState("Wandering until we randomly place or find a good place spot.");
        return new TimeoutWanderTask();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        stopPlacing(mod);
        EventBus.unsubscribe(_onBlockPlaced);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof PlaceBlockNearbyTask task) {
            return Arrays.equals(task._toPlace, _toPlace);
        }
        return false;
    }

    @Override
    protected String toHudString() {
        if (_toPlace != null && _toPlace.length == 1) {
            return "Placing " + HudText.one(HudText.block(_toPlace[0]));
        }
        return "Placing " + HudText.blocks(_toPlace);
    }

    @Override
    protected String toDebugString() {
        return "Place " + Arrays.toString(_toPlace) + " nearby";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // the spot we picked never held one of ours to begin with (see locateClosePlacePos), so once the builder says it
        // does, we put it there
        if (_placing != null && _tryPlace != null && _placing.isFinished(mod)) {
            _justPlaced = _tryPlace;
        }
        return _justPlaced != null && ArrayUtils.contains(_toPlace, mod.getWorld().getBlockState(_justPlaced).getBlock());
    }

    public BlockPos getPlaced() {
        return _justPlaced;
    }

    private BlockPos getCurrentlyLookingBlockPlace(AltoClef mod) {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit instanceof BlockHitResult bhit) {
            BlockPos bpos = bhit.getBlockPos();//.subtract(bhit.getSide().getVector());
            //Debug.logMessage("TEMP: A: " + bpos);
            IPlayerContext ctx = mod.getClientBaritone().getPlayerContext();
            if (MovementHelper.canPlaceAgainst(ctx, bpos)) {
                BlockPos placePos = bhit.getBlockPos().offset(bhit.getDirection().getUnitVec3i());
                // Don't place inside the player.
                if (WorldHelper.isInsidePlayer(mod, placePos)) {
                    return null;
                }
                //Debug.logMessage("TEMP: B (actual): " + placePos);
                if (WorldHelper.canPlaceBlock(mod, placePos, placingState())) {
                    return placePos;
                }
            }
        }
        return null;
    }

    private boolean blockEquipped(AltoClef mod) {
        return StorageHelper.isEquipped(mod, ItemHelper.blocksToItems(_toPlace));
    }

    private boolean place(AltoClef mod, BlockPos targetPlace) {
        if (!mod.getExtraBaritoneSettings().isInteractionPaused() && blockEquipped(mod)) {
            // Shift click just for 100% container security.
            mod.getInputControls().hold(Input.SNEAK);

            //mod.getInputControls().tryPress(Input.CLICK_RIGHT);
            // This appears to work on servers...
            // TODO: Helper lol
            HitResult mouseOver = Minecraft.getInstance().hitResult;
            if (mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
                return false;
            }
            InteractionHand hand = InteractionHand.MAIN_HAND;
            assert Minecraft.getInstance().gameMode != null;
            // a place that went in is a fresh Success with the shrunk stack attached, never == SUCCESS. same check as
            // BlockPlaceHelper
            if (Minecraft.getInstance().gameMode.useItemOn(mod.getPlayer(), hand, (BlockHitResult) mouseOver) instanceof InteractionResult.Success success &&
                    mod.getPlayer().isShiftKeyDown()) {
                if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
                    mod.getPlayer().swing(hand, SwingAnimation.DEFAULT, false);
                }
                _justPlaced = targetPlace;
                return true;
            }

            //mod.getControllerExtras().mouseClickOverride(1, true);
            //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            return true;
        }
        return false;
    }

    private void stopPlacing(AltoClef mod) {
        mod.getInputControls().release(Input.SNEAK);
        //mod.getControllerExtras().mouseClickOverride(1, false);
        // Oof, these sometimes cause issues so this is a bit of a duct tape fix.
        mod.getClientBaritone().getBuilderProcess().onLostControl();
    }

    // one strike against the spot we were working on. two and it never comes up again, however much we were pathing
    private void failSpot(AltoClef mod) {
        _progressChecker.reset();
        _placing = null;
        BlockPos spot = _tryPlace;
        _tryPlace = null;
        // no table in the bag is not the spot's fault, and blacklisting all of them for it leaves nowhere to go once we do have one
        if (spot == null || !mod.getItemStorage().hasItem(ItemHelper.blocksToItems(_toPlace))) {
            return;
        }
        // one strike takes the column with it (see SpotFailures), so the next pick can't be the cell above
        _failures.fail(spot.getX(), spot.getY(), spot.getZ());
        Debug.logMessage("Giving up on placing at " + spot + " and the column around it");
        mod.getBlockTracker().requestBlockUnreachable(spot);
    }

    // what the entity check measures the cell against. every block this task places is a full cube anyway
    private BlockState placingState() {
        return (_toPlace.length > 0 ? _toPlace[0] : Blocks.STONE).defaultBlockState();
    }

    // a throwaway above what a recipe is saving, the same answer the movements get
    private static boolean canScaffold(AltoClef mod) {
        return mod.getClientBaritoneSettings().allowPlace.value && mod.getClientBaritone().getInventoryBehavior().hasGenericThrowaway();
    }

    private BlockPos locateClosePlacePos(AltoClef mod) {
        int range = 7;
        // best is the new rank (a floor under it, or something to build one with, and close to our feet).
        // bestLoose is the old rank, for when the new one finds nothing at all
        BlockPos best = null;
        BlockPos bestLoose = null;
        double smallestScore = Double.POSITIVE_INFINITY;
        double smallestLoose = Double.POSITIVE_INFINITY;
        boolean scaffold = canScaffold(mod);
        BlockState placing = placingState();
        BlockPos feet = mod.getPlayer().blockPosition();
        int feetY = feet.getY();
        boolean onGround = mod.getPlayer().onGround();
        BlockPos start = mod.getPlayer().blockPosition().offset(-range, -range, -range);
        BlockPos end = mod.getPlayer().blockPosition().offset(range, range, range);
        for (BlockPos blockPos : WorldHelper.scanRegion(mod, start, end)) {
            // already one of ours (signs and chests don't count as solid, so nothing else keeps them out)
            if (ArrayUtils.contains(_toPlace, mod.getWorld().getBlockState(blockPos).getBlock())) {
                continue;
            }
            boolean solid = WorldHelper.isSolid(mod, blockPos);
            boolean inside = WorldHelper.isInsidePlayer(mod, blockPos);
            // We can't break this block.
            if (solid && !WorldHelper.canBreak(mod, blockPos)) {
                continue;
            }
            // nor a table, furnace or smoker, ours least of all: the builder breaks what stands in the cell
            if (solid && StationHook.keepStanding(blockPos, mod.getWorld().getBlockState(blockPos).getBlock())) {
                continue;
            }
            // We can't place here as defined by user.
            if (!_canPlaceHere.test(blockPos)) {
                continue;
            }
            // We can't place here.
            if (!WorldHelper.canReach(mod, blockPos) || !WorldHelper.canPlace(mod, blockPos)) {
                continue;
            }
            // a spot that already let us down twice
            if (_failures.isBad(blockPos.getX(), blockPos.getY(), blockPos.getZ())) {
                continue;
            }
            boolean hasBelow = WorldHelper.isSolid(mod, blockPos.below());
            // never our own cells, never a cell that has to be built up to. the builder pillars us up the shaft for those
            if (PlaceSpotRank.wouldPillar(blockPos.getX(), blockPos.getY(), blockPos.getZ(), feet.getX(), feetY, feet.getZ(), hasBelow)) {
                continue;
            }
            if (PlaceSpotRank.underFloatingFeet(blockPos.getX(), blockPos.getY(), blockPos.getZ(), feet.getX(), feetY, feet.getZ(), onGround)) {
                continue;
            }
            // somebody standing in the cell. last, it is the one check here that asks the world about entities
            if (!WorldHelper.entityFreeFor(mod.getWorld(), blockPos, placing, mod.getPlayer())) {
                continue;
            }
            double distSq = blockPos.distToCenterSqr(mod.getPlayer().position());

            double loose = PlaceSpotRank.oldScore(distSq, solid, hasBelow, inside);
            if (loose < smallestLoose) {
                bestLoose = blockPos;
                smallestLoose = loose;
            }
            double score = PlaceSpotRank.score(distSq, solid, hasBelow, inside, blockPos.getY() - feetY, scaffold);
            if (score < smallestScore) {
                best = blockPos;
                smallestScore = score;
            }
        }

        return best != null ? best : bestLoose;
    }
}
