package adris.altoclef.tasks.speedrun.gamer.tasks;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.DropRoom;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.ItemPickupRules;
import adris.altoclef.util.helpers.ItemValue;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

// after a death: walk back to where we died and pick up whatever is lying around there. bounded on purpose, the pile might
// be in lava or the void and a bot that stares at it for ten minutes helps nobody
public class RecoverItemsTask extends Task {
    // nothing to see for this long after we got there: it burned, it was eaten by a cactus, or somebody else got it
    private static final double EMPTY_SECONDS = 4;

    private final BlockPos _deathPos;
    private final double _budgetSeconds;
    private final TimerGame _empty = new TimerGame(EMPTY_SECONDS);
    private final Task _walk;
    // the armor we would put on (KitPlanner.toEquip), asked fresh every tick. empty for a caller that has no kit to go by
    private final Supplier<List<Item>> _toWear;
    private final RecoverRules.Gate _gate = new RecoverRules.Gate();
    private final RecoverRules.Chase _chase = new RecoverRules.Chase();
    private Task _standOff;
    private final RecoverRules.Pin<ItemEntity> _pin = new RecoverRules.Pin<>();
    private PickupThisDrop _pickup;
    private boolean _finished;
    private boolean _started;
    private boolean _bagLogged;
    // at least one drop of ours was the target, so an empty pile at the end means we got it
    private boolean _wentForADrop;
    // a deep pile and bare hands: a wooden pick first (see RecoverRules.pickFirst). latched so a flicker of the chunk does not
    // drop us out of it halfway
    private final Task _getPick = new SatisfyMiningRequirementTask(MiningRequirement.WOOD);
    private boolean _gettingPick;
    private double _pickSeconds;
    private double _lastPickNow = Double.NaN;
    private boolean _hadPick;
    private final RecoverRules.NoPaths _noPaths = new RecoverRules.NoPaths();

    public RecoverItemsTask(BlockPos deathPos, double budgetSeconds) {
        this(deathPos, budgetSeconds, List::of);
    }

    public RecoverItemsTask(BlockPos deathPos, double budgetSeconds, Supplier<List<Item>> toWear) {
        _deathPos = deathPos;
        _budgetSeconds = budgetSeconds;
        _toWear = toWear;
        _walk = new GetWithinRangeOfBlockTask(deathPos, (int) RecoverRules.ARRIVE_RANGE);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _empty.reset();
        // the crowd gate is NOT restarted here either: onStart runs again after every interrupt, and the 30 s are 30 in a
        // row, not 30 since the last time mob defense let go of us. the time on task clock never restarts on its own
        if (!_started) {
            _started = true;
            _gate.reset();
        }
        // the chase counts and write offs are NOT reset here: mob defense flickering at the pile would hand every stuck
        // drop a fresh 20 s each time
        _standOff = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        double budget = RecoverRules.stretched(_budgetSeconds, _pickSeconds);
        if (overTimeOnTask(budget)) {
            // armor we already hold goes on before we leave, a trip that timed out mid equip left it in the bag for good
            Task lateWear = WearFromBag.task(mod, _toWear.get());
            if (lateWear != null && RecoverRules.wearPastBudget(true, timeOnTaskSeconds(), budget)) {
                setDebugState("Putting on armor from the pile.", "Putting on armor");
                return lateWear;
            }
            setDebugState("Out of time.", "Giving up on our stuff");
            Debug.logMessage("death: out of time on our stuff, leaving it.");
            _finished = true;
            return null;
        }
        if (spotIsHopeless(mod)) {
            Debug.logMessage("Our stuff is in lava or the void, leaving it.");
            _finished = true;
            return null;
        }
        // whatever killed us is probably still standing on it. walking in again is how the same two zombies got us twice
        List<Entity> threats = threatsAtPile(mod);
        switch (_gate.update(mod.getWorld().getGameTime() / 20.0, threats.size())) {
            case GIVE_UP -> {
                Debug.logMessage("Still " + threats.size() + " hostile(s) on our stuff after " + (int) RecoverRules.WAIT_SECONDS
                        + " s, giving it up.");
                setDebugState("Crowded.", "Giving up on our stuff");
                _finished = true;
                return null;
            }
            case WAIT -> {
                return standOff(mod, threats);
            }
            default -> {
                _standOff = null;
            }
        }
        double fromPile = Math.sqrt(mod.getPlayer().blockPosition().distSqr(_deathPos));
        double dy = mod.getPlayer().getY() - _deathPos.getY();
        boolean holdsPick = StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.WOOD);
        if (holdsPick && !_hadPick) {
            // every no path so far was for bare hands
            _hadPick = true;
            _noPaths.clear();
        }
        Task pick = getPickFirst(mod, dy, holdsPick);
        if (pick != null) {
            _empty.reset();
            _chase.idle();
            return pick;
        }
        if (_finished) {
            return null;
        }
        ItemEntity pinned = _pin.drop();
        if (pinned != null && !pinned.isAlive()) {
            // it went into the bag (or burned, either way the way in exists)
            _noPaths.pickedUp();
        }
        if (_noPaths.noWay()) {
            setDebugState("No way to our stuff.", "Giving up on our stuff");
            _finished = true;
            return null;
        }
        ItemEntity drop = closestDrop(mod, holdsPick);
        RecoverRules.Step step = RecoverRules.step(fromPile, dy, drop == null ? 0 : mod.getPlayer().getY() - drop.getY(), drop != null);
        if (step == RecoverRules.Step.WALK) {
            _empty.reset();
            _chase.idle();
            setDebugState("Walking back to where we died.", "Going back for our stuff");
            return _walk;
        }
        if (!_bagLogged) {
            // what we walked in with. a full bag refusing pile drops is unreadable without it
            _bagLogged = true;
            Debug.logInternal("death: at the pile, pearls " + mod.getItemStorage().getItemCount(Items.ENDER_PEARL)
                    + " eyes " + mod.getItemStorage().getItemCount(Items.ENDER_EYE)
                    + " rods " + mod.getItemStorage().getItemCount(Items.BLAZE_ROD)
                    + " powder " + mod.getItemStorage().getItemCount(Items.BLAZE_POWDER)
                    + ", bag " + StorageHelper.garbageView(mod).dump());
        }
        // armor off the pile goes on before anything else. in the bag it is one more stack a full bag would rather throw, and the
        // slot it frees is room the next pickup does not have to make
        Task wear = WearFromBag.task(mod, _toWear.get());
        if (wear != null) {
            _empty.reset();
            _chase.idle();
            setDebugState("Putting on armor from the pile.", "Putting on armor");
            return wear;
        }
        if (step == RecoverRules.Step.LOOK) {
            _chase.idle();
            setDebugState("Nothing left here.", "Looking for our stuff");
            if (_empty.elapsed()) {
                // two ways to get here: we picked it all up, or there was never anything (despawned, burned, taken)
                Debug.logMessage(_wentForADrop ? "death: picked up what was at the pile." : "death: nothing at the pile, moving on.");
                _finished = true;
            }
            return null;
        }
        // every tick a drop is the target counts, so 20 s of chasing is 20 s and not 20 s of the ticks the walk left over
        if (_chase.update(drop.getId(), mod.getWorld().getGameTime() / 20.0)) {
            // visible, "reachable" and still not ours after all that: the pickup task's own retries reset every time we get a
            // block closer, so this is the only count that ever ends
            Debug.logMessage("Can't get at " + drop.getItem().getItem().getDescriptionId() + " after "
                    + (int) RecoverRules.CHASE_SECONDS + " s, leaving it.");
            BanPolicy.recoverGaveUp(mod.getBans(), drop.getId(), EntityTracker.describe(drop));
            _pickup = null;
            _pin.clear();
            return null;
        }
        _empty.reset();
        setDebugState("Picking up a drop.", "Picking up our stuff");
        if (PickupThisDrop.refuses(drop)) {
            // next to lava or out in water the pickup won't touch it. pinned to it, the pickup would wander off for the
            // whole chase instead, so it is banned right now (the shared book, the tracker stops offering it to anyone)
            BanPolicy.recoverRefused(mod.getBans(), drop.getId(), EntityTracker.describe(drop));
            _pickup = null;
            _pin.clear();
            return null;
        }
        // only a drop we actually set out to pick up counts, a written off one is not "got it"
        _wentForADrop = true;
        boolean moved = _pin.retarget(drop);
        if (_pickup == null || moved || _pickup.stalled(mod)) {
            // a stall only flags this pickup (see gaveUpOnDrop below), it bans nothing. a fresh one gets another go and the chase
            // clock above is the only judge (the tracker's strikes, the fourth bans, usually land around the same time)
            _pickup = new PickupThisDrop(drop, _noPaths);
        }
        return _pickup;
    }

    // the stock pickup goes for the nearest item of a kind anywhere, which is not always the one we picked and timed.
    // this one only ever sees our drop and keeps the rest (full bag, water watchdog, stuck shimmy) as is
    private static final class PickupThisDrop extends PickupDroppedItemTask {
        private final ItemEntity _drop;
        private final RecoverRules.NoPaths _noPaths;

        PickupThisDrop(ItemEntity drop, RecoverRules.NoPaths noPaths) {
            // "any amount" is only for the debug string, getClosestTo below never looks at it
            super(new ItemTarget(drop.getItem().getItem(), Integer.MAX_VALUE), true);
            _drop = drop;
            _noPaths = noPaths;
        }

        // the parent's isValid rules that no retry fixes, same checks in the same order. if isValid grows a new one and
        // this doesn't, the drop just gets rebuilt every tick until the chase writes it off
        static boolean refuses(ItemEntity drop) {
            return ItemPickupRules.lavaBlocksPickup(drop)
                    || (!Baritone.settings().altoPickupItemsInWater.value && !ItemPickupRules.worthSwimming(drop) && !ItemPickupRules.isPickupSafe(drop));
        }

        private boolean _stalled;

        // gave up on its own: a progress fail, or anything else that made it stop wanting the drop
        boolean stalled(AltoClef mod) {
            return _drop.isAlive() && (_stalled || !isValid(mod, _drop));
        }

        // the stock pickup bans a drop it got nowhere with for a minute. here that would hide the pile's drop from the recover
        // for most of its budget, and the chase clock is the one that is supposed to decide
        @Override
        protected void gaveUpOnDrop(AltoClef mod, ItemEntity drop) {
            _stalled = true;
        }

        // the stock ban hides the drop from everybody for two minutes after two empty searches. recovery keeps its own book
        // (RecoverRules.NoPaths) and moves on to the next drop
        @Override
        protected void noPathToDrop(AltoClef mod, ItemEntity drop) {
            _noPaths.record(drop.getId());
        }

        // the budget is short and the bag is empty, a stone pickaxe trip is the whole pile allowance and then some
        @Override
        protected boolean mayGetPickaxeFirst() {
            return false;
        }

        @Override
        protected Optional<ItemEntity> getClosestTo(AltoClef mod, Vec3 pos) {
            return isValid(mod, _drop) ? Optional.of(_drop) : Optional.empty();
        }

        // the parent's isEqual only compares item kinds, so two andesite drops would keep the old task (and its old target).
        // the object and not the id, see Pin
        @Override
        protected boolean isEqual(Task other) {
            return other instanceof PickupThisDrop task && task._drop == _drop;
        }
    }

    // the angry ones near the pile, if the world has loaded them (from far away it hasn't, they show up as we get closer
    // and the gate takes it from there). creepers count asleep too, but an idle one only when it is close or can see us (RecoverRules.threatensAt)
    private List<Entity> threatsAtPile(AltoClef mod) {
        List<Entity> out = new ArrayList<>();
        try {
            List<Entity> candidates = new ArrayList<>(mod.getEntityTracker().getHostiles());
            // getHostiles only holds the angry ones, so the quiet creepers come straight from the tracker
            for (Creeper creeper : mod.getEntityTracker().getTrackedEntities(Creeper.class)) {
                if (!candidates.contains(creeper)) candidates.add(creeper);
            }
            for (Entity entity : candidates) {
                if (!(entity instanceof Mob mob) || !mob.isAlive()) continue;
                if (!RecoverRules.isThreat(mob instanceof Creeper, EntityHelper.isAngryAtPlayer(mod, mob))) continue;
                boolean aggressive = EntityHelper.isAngryAtPlayer(mod, mob);
                if (RecoverRules.threatensAt(aggressive, mob.hasLineOfSight(mod.getPlayer()), mob.getX() - (_deathPos.getX() + 0.5),
                        mob.getY() - _deathPos.getY(), mob.getZ() - (_deathPos.getZ() + 0.5))) {
                    out.add(mob);
                }
            }
        } catch (ConcurrentModificationException | ArrayIndexOutOfBoundsException | NullPointerException ignored) {
            // the tracker rebuilds its lists on another thread, an empty look this tick just means one more tick of walking
        }
        return out;
    }

    // wait it out somewhere the crowd can't see us. already far enough means standing still (the wheel is mob defense's if
    // anything does come)
    private Task standOff(AltoClef mod, List<Entity> threats) {
        double nearest = Double.POSITIVE_INFINITY;
        for (Entity threat : threats) {
            nearest = Math.min(nearest, threat.distanceTo(mod.getPlayer()));
        }
        setDebugState("Something is standing on our stuff.", "Waiting for our stuff to clear");
        if (nearest >= RecoverRules.SAFE_DISTANCE) {
            _standOff = null;
            return null;
        }
        if (_standOff == null) {
            _standOff = new RunAwayFromHostilesTask(RecoverRules.SAFE_DISTANCE, false, () -> threatsAtPile(mod));
        }
        return _standOff;
    }

    private boolean spotIsHopeless(AltoClef mod) {
        Level level = mod.getWorld();
        // an unloaded chunk reads as air, so only judge it once we can see it
        boolean lava = level.isLoaded(_deathPos) && level.getFluidState(_deathPos).is(FluidTags.LAVA);
        return RecoverRules.hopeless(lava, _deathPos.getY(), level.getMinY());
    }

    // a wooden pick before going down for a deep pile, null when it is not the time. finishes the task when the trip ran out
    private Task getPickFirst(AltoClef mod, double dy, boolean holdsPick) {
        if (holdsPick) {
            _gettingPick = false;
        } else if (!_gettingPick) {
            int depth = pileDepth(mod);
            _gettingPick = RecoverRules.pickFirst(depth != Integer.MIN_VALUE, depth, dy, false);
        }
        if (!_gettingPick) {
            _lastPickNow = Double.NaN;
            return null;
        }
        double now = mod.getWorld().getGameTime() / 20.0;
        // a gap is not pick time, same idea as Chase
        _pickSeconds += Double.isNaN(_lastPickNow) ? 0 : Math.min(Math.max(0, now - _lastPickNow), 1.0);
        _lastPickNow = now;
        if (RecoverRules.pickTimedOut(_pickSeconds)) {
            setDebugState("No pickaxe.", "Giving up on our stuff");
            _finished = true;
            return null;
        }
        setDebugState("Getting a pickaxe to dig down to our stuff.", "Making a pickaxe for our stuff");
        return _getPick;
    }

    // blocks between the pile and the open sky, MIN_VALUE while its chunk is not loaded (from far away the heightmap is a guess)
    // and in the nether, where the roof is not the sky
    private int pileDepth(AltoClef mod) {
        Level level = mod.getWorld();
        if (!level.isLoaded(_deathPos) || level.dimensionType().hasCeiling()) {
            return Integer.MIN_VALUE;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, _deathPos.getX(), _deathPos.getZ()) - _deathPos.getY();
    }

    private ItemEntity closestDrop(AltoClef mod, boolean holdsPick) {
        List<ItemEntity> drops = mod.getEntityTracker().getDroppedItems();
        Level level = mod.getWorld();
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        int bestTier = Integer.MAX_VALUE;
        FullBag full = new FullBag(mod);
        for (ItemEntity e : drops) {
            // the death spot says what's on the pile, we say which of it is closest
            double d = e.position().distanceToSqr(_deathPos.getX() + 0.5, _deathPos.getY() + 0.5, _deathPos.getZ() + 0.5);
            if (d > RecoverRules.DROP_RADIUS * RecoverRules.DROP_RADIUS || _noPaths.skips(e.getId())) {
                continue;
            }
            int tier = ItemValue.tierOf(mod, e.getItem(), !holdsPick);
            double near = e.distanceToSqr(mod.getPlayer());
            if (best != null && !RecoverRules.better(tier, near, bestTier, bestDist)) {
                continue;
            }
            // a drop that slid into lava is a drop we are not going swimming for
            boolean lost = RecoverRules.hopeless(level.getFluidState(e.blockPosition()).is(FluidTags.LAVA), e.blockPosition().getY(), level.getMinY());
            // the bag question last, every drop in the world goes through this loop and most are not ours
            if (!lost && mod.getEntityTracker().isEntityReachable(e) && !full.skips(e.getItem())) {
                bestDist = near;
                bestTier = tier;
                best = e;
            }
        }
        return best;
    }

    // a full bag at the pile: a drop with no room (StorageHelper.GarbageView.room says NONE, the same answer the pickup acts on)
    // is skipped right here, no walk, no touch, no ban. same for junk once the block reserve is full (see RecoverRules.skipWhenFull).
    // the bag is only read once a pile drop does not fit, and then once per tick no matter how many drops ask
    private static final class FullBag {
        private final AltoClef _mod;
        private StorageHelper.GarbageView _view;
        private int _blocksHeld;

        FullBag(AltoClef mod) {
            _mod = mod;
        }

        boolean skips(ItemStack drop) {
            if (PickupDroppedItemTask.hasRoomFor(_mod, drop)) {
                return false;
            }
            if (_view == null) {
                _view = StorageHelper.garbageView(_mod);
                _blocksHeld = _view.throwawayBlocksHeld();
            }
            Item item = drop.getItem();
            // any throwaway that is not a block, or a block once the reserve is full. the pick alone would let two block kinds take
            // turns (cobble in pushes the first block stack out, which is the netherrack, and round again)
            boolean junk = _view.isThrowaway(item)
                    && (!(item instanceof BlockItem) || _blocksHeld >= Baritone.settings().altoReservedBuildingBlockCount.value);
            return RecoverRules.skipWhenFull(false, junk, !junk && _view.room(drop).kind() == DropRoom.Kind.NONE);
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof RecoverItemsTask task && task._deathPos.equals(_deathPos);
    }

    @Override
    protected String toDebugString() {
        return "Recover items near " + _deathPos.toShortString();
    }

    @Override
    protected String toHudString() {
        return "Getting our stuff back near " + HudText.pos(_deathPos);
    }
}
