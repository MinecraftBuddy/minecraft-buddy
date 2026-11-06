package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import baritone.utils.FallingColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Put a crafting table, furnace, smoker or blast furnace down the way a player does: look at the floor next to you and
 * right click it. StationSpots picks where, StationAttempt keeps the score.
 */
public class PlaceStationTask extends Task {

    private static final Set<Block> STATIONS = Set.of(Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.SMOKER, Blocks.BLAST_FURNACE);
    // a walk to a better patch of floor gets this long, then we look again from wherever we are
    private static final double WALK_SECONDS = 12;
    // the swim back to dry land can be a couple hundred blocks of water, swimming is slow
    private static final double DRY_LAND_SECONDS = 90;
    // stone by hand is a couple of seconds, deepslate and the like more. this is for a pick that never lands
    private static final double CARVE_SECONDS = 10;
    // softer than this and we'll mine it for room. stone is 1.5, deepslate 3, obsidian 50 (not today)
    private static final float CARVE_MAX_HARDNESS = 3.5f;

    private final Block[] _toPlace;
    // the old way, for when the player way has been out of ideas twice
    private final PlaceBlockNearbyTask _fallback;
    private final StationSpots.Bans _bans = new StationSpots.Bans();
    private final TimerGame _walkTimer = new TimerGame(WALK_SECONDS);
    private final TimerGame _dryLandTimer = new TimerGame(DRY_LAND_SECONDS);
    // whichever of the two the walk in flight runs on
    private TimerGame _walkClock = _walkTimer;
    // the swim back to dry land is once per attempt, see StationAttempt.relocateMove
    private boolean _dryLandUsed;
    // a wall that fights back for this long isn't getting carved today
    private final TimerGame _carveTimer = new TimerGame(CARVE_SECONDS);
    // carve targets that didn't open up. separate from _bans, those are for spots that missed
    private final StationSpots.Bans _carveBans = new StationSpots.Bans();
    private StationAttempt _attempt = new StationAttempt();
    private StationSpots.Spot _spot;
    private Task _walk;
    // the block we are mining out for room, and the last one we did, which gets to skip the way-out rule
    private Task _carveTask;
    private BlockPos _carving;
    private StationSpots.Cell _carved;
    private boolean _fallbackLogged;
    private boolean _placedLogged;

    public PlaceStationTask(Block... toPlace) {
        _toPlace = toPlace;
        _fallback = new PlaceBlockNearbyTask(toPlace);
    }

    // only the four stations. an anvil or a chest keeps the old placer, the spot rules here are about tables and furnaces
    public static boolean isStation(Block... blocks) {
        if (blocks == null || blocks.length == 0) {
            return false;
        }
        for (Block block : blocks) {
            if (!STATIONS.contains(block)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _attempt = new StationAttempt();
        _bans.clear();
        _carveBans.clear();
        _spot = null;
        _walk = null;
        _walkClock = _walkTimer;
        _dryLandUsed = false;
        _carveTask = null;
        _carving = null;
        _carved = null;
        _fallbackLogged = false;
        _placedLogged = false;
        // whatever walked us here may still be pathing, and a builder that wakes up is the thing this task replaces
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        mod.getClientBaritone().getBuilderProcess().onLostControl();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // in a boat nothing is put down: ashore first. a crossing cut short by a craft left it in the boat at sea, looking
        // for a spot for a table for ten game minutes (the runner, 3 Nov 2026, attempt 105). a path from a boat rows to its
        // end and gets out there (BoatTrip.plan), so a way to the nearest land is all it takes
        if (mod.getPlayer().getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat) {
            BlockPos land = adris.altoclef.util.helpers.LandFinder.nearestLand(mod, mod.getPlayer().blockPosition(), 64);
            if (land != null) {
                setDebugState("In a boat: ashore first, to " + land.toShortString());
                return new GetToBlockTask(land);
            }
        }
        _attempt.tick();
        return switch (_attempt.phase()) {
            case PICK -> pick(mod);
            case CARVE -> carve(mod);
            case AIM -> aim(mod);
            case VERIFY -> verify(mod);
            case RELOCATE -> relocate(mod);
            case FALLBACK -> {
                mod.getInputControls().release(Input.SNEAK);
                if (!_fallbackLogged) {
                    _fallbackLogged = true;
                    // the builder pillars and bridges with throwaway blocks. a warning so a play log shows whether it still runs
                    Debug.logWarning("station fallback: builder placing " + kindWord() + " after " + _attempt.totalTicks() + " ticks, " + _attempt.fallbackWhy());
                }
                setDebugState("Out of ideas, letting the old placer try.");
                yield _fallback;
            }
            case DONE -> null;
        };
    }

    private Task pick(AltoClef mod) {
        if (!cursorClear(mod)) {
            return null;
        }
        setDebugState("Looking for a spot.");
        StationSpots.Refusals why = new StationSpots.Refusals();
        StationSpots.Stance stance = stance(mod);
        Optional<StationSpots.Spot> spot = StationSpots.best(new World(mod), stance, _bans, _carved, why);
        if (spot.isEmpty()) {
            // onGround is never true in deep water, a steady swim has to count or the ladder never starts
            _attempt.noSpot(mod.getPlayer().onGround() || mod.getPlayer().isInWater());
            // once per streak, the picks that follow ask the same question
            if (_attempt.noSpotTicks() == 1 || _attempt.phase() != StationAttempt.Phase.PICK) {
                Debug.logInternal("station spots: no spot around " + mod.getPlayer().blockPosition().toShortString() + ": " + why.summary());
            }
            return null;
        }
        _spot = spot.get();
        Debug.logInternal("station spot: " + kindWord() + " at " + describe(_spot) + ", dy=" + (_spot.y() - stance.feetY())
                + ", stand at " + new BlockPos(stance.feetX(), stance.feetY(), stance.feetZ()).toShortString());
        _attempt.picked();
        return null;
    }

    // nothing to click, so make something: knock a block out of the wall beside us and the hole is a normal candidate.
    // underground in a 1x1 shaft that is the only thing a player can do, walking off to find a floor isn't on the menu
    private Task carve(AltoClef mod) {
        mod.getInputControls().release(Input.SNEAK);
        if (_carving == null) {
            List<StationSpots.Cell> options = StationSpots.carveCandidates(new World(mod), stance(mod), _carveBans);
            if (options.isEmpty()) {
                Debug.logInternal("station: nothing safe to carve around " + mod.getPlayer().blockPosition().toShortString());
                _attempt.noCarve();
                return null;
            }
            StationSpots.Cell cell = options.get(0);
            _carving = new BlockPos(cell.x(), cell.y(), cell.z());
            _carveTask = new DestroyBlockTask(_carving);
            _carveTimer.reset();
            Debug.logInternal("station: carving " + _carving.toShortString() + " for room");
        }
        if (_carveTask.isFinished(mod)) {
            _carved = new StationSpots.Cell(_carving.getX(), _carving.getY(), _carving.getZ());
            finishCarve();
            return null;
        }
        if (_carveTimer.elapsed()) {
            Debug.logInternal("station: carving " + _carving.toShortString() + " is taking forever, trying another wall");
            _carveBans.ban(_carving.getX(), _carving.getY(), _carving.getZ());
            finishCarve();
            return null;
        }
        setDebugState("Mining out some room for the station.");
        return _carveTask;
    }

    private void finishCarve() {
        _carving = null;
        _carveTask = null;
        _attempt.carveDone();
    }

    private Task aim(AltoClef mod) {
        if (_attempt.aimTimedOut()) {
            lose(mod, "never got the crosshair on it");
            return null;
        }
        if (!cursorClear(mod) || mod.getExtraBaritoneSettings().isInteractionPaused()) {
            return null;
        }
        LocalPlayer player = mod.getPlayer();
        World world = new World(mod);
        if (!world.placeable(_spot.x(), _spot.y(), _spot.z())) {
            lose(mod, "something is in the cell now");
            return null;
        }
        // sneaking keeps us on a fence or an edge, and a click on a chest or a door places instead of opening it
        mod.getInputControls().hold(Input.SNEAK);
        setDebugState("Aiming at " + describe(_spot));
        Item[] items = stationItems();
        if (!StorageHelper.isEquipped(mod, items)) {
            mod.getSlotHandler().forceEquipItem(items);
            return null;
        }
        Vec3 face = faceCentre(_spot);
        LookHelper.lookAt(mod, face);
        // our own raycast along the look we just set. the crosshair (Minecraft.hitResult) is a frame behind it
        Rotation look = LookHelper.getLookRotation(player);
        HitResult hit = RayTraceUtils.rayTraceTowards(player, look, StationSpots.REACH + 0.5);
        if (!(hit instanceof BlockHitResult bhit) || !hitsSpot(world.level, bhit, _spot)) {
            return null;
        }
        assert Minecraft.getInstance().gameMode != null;
        InteractionResult result = Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhit);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            _attempt.clicked();
        } else {
            lose(mod, "the click was refused");
        }
        return null;
    }

    private Task verify(AltoClef mod) {
        mod.getInputControls().hold(Input.SNEAK);
        setDebugState("Waiting for the block to show up.");
        BlockState there = mod.getWorld().getBlockState(spotCell(_spot));
        _attempt.verify(isOurs(there.getBlock()));
        StationAttempt.Phase after = _attempt.phase();
        if (after == StationAttempt.Phase.DONE) {
            mod.getInputControls().release(Input.SNEAK);
            logPlaced(spotCell(_spot));
        } else if (after != StationAttempt.Phase.VERIFY) {
            // verify() lost the try
            Debug.logInternal("station spot: no block at " + describe(_spot) + " after the click, banning it");
            ban(mod);
        }
        return null;
    }

    private Task relocate(AltoClef mod) {
        mod.getInputControls().release(Input.SNEAK);
        if (_walk == null) {
            World world = new World(mod);
            LocalPlayer player = mod.getPlayer();
            // afloat, no floor to walk from: swim to the nearest bank first, the pick runs again from there
            boolean swimming = player.isInWater() && !player.onGround();
            Optional<StationSpots.Stand> shore = swimming ? StationSpots.nearestShore(world, stance(mod), StationSpots.SHORE_RADIUS) : Optional.empty();
            Optional<StationSpots.Stand> stand = shore.isPresent() ? shore
                    : StationSpots.standpoint(world, stance(mod), _bans, sneakEyeHeight(mod));
            // plain isInWater: a bot sunk to the sea floor reads onGround, and it is just as far from a floor it can use
            Optional<BlockPos> dry = stand.isPresent() ? Optional.empty() : mod.getMiscBlockTracker().getLastDryLand(WorldHelper.getCurrentDimension());
            double dryDistance = dry.map(p -> Math.sqrt(player.blockPosition().distSqr(p))).orElse(-1.0);
            StationAttempt.Move move = StationAttempt.relocateMove(shore.isPresent(), stand.isPresent(), player.isInWater(), dryDistance, _dryLandUsed);
            if (move == StationAttempt.Move.NONE) {
                _attempt.noStandpoint();
                return null;
            }
            BlockPos at = move == StationAttempt.Move.DRY_LAND ? dry.get() : new BlockPos(stand.get().x(), stand.get().y(), stand.get().z());
            Debug.logInternal(switch (move) {
                case SHORE -> "station: in water, swimming to shore at " + at.toShortString();
                case DRY_LAND -> "station: in water, no shore or floor around, swimming back to dry land at " + at.toShortString()
                        + " (" + (int) dryDistance + " blocks)";
                default -> "station: walking to a better patch at " + at.toShortString() + " (move " + _attempt.relocations() + ")";
            });
            _dryLandUsed |= move == StationAttempt.Move.DRY_LAND;
            _carved = null;
            _walk = new GetToBlockTask(at);
            _walkClock = move == StationAttempt.Move.DRY_LAND ? _dryLandTimer : _walkTimer;
            _walkClock.reset();
        }
        if (_walk.isFinished(mod) || _walkClock.elapsed()) {
            _walk = null;
            _attempt.arrived();
            return null;
        }
        setDebugState("Moving to a better patch of floor.");
        return _walk;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().release(Input.SNEAK);
        // the old placer finishes inside its own task, nothing in our tick sees it land. the block tells us when it is there
        // (the container task swaps us out the tick after, so an interrupt here is the normal way to be done)
        if (_attempt.phase() == StationAttempt.Phase.FALLBACK && _fallback.isFinished(mod)) {
            logPlaced(_fallback.getPlaced());
        }
    }

    // once per attempt: how long it took and which road got the block down
    private void logPlaced(BlockPos at) {
        if (_placedLogged) {
            return;
        }
        _placedLogged = true;
        Debug.logInternal("station placed: " + kindWord() + " at " + (at == null ? "?" : at.toShortString()) + " in " + _attempt.totalTicks()
                + " ticks via " + _attempt.how());
    }

    // "crafting table", or "furnace/smoker" when more than one will do
    private String kindWord() {
        StringBuilder out = new StringBuilder();
        for (Block block : _toPlace) {
            out.append(out.length() == 0 ? "" : "/").append(BuiltInRegistries.BLOCK.getKey(block).getPath().replace('_', ' '));
        }
        return out.toString();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PlaceStationTask task && Arrays.equals(task._toPlace, _toPlace);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        StationAttempt.Phase phase = _attempt.phase();
        return phase == StationAttempt.Phase.DONE || (phase == StationAttempt.Phase.FALLBACK && _fallback.isFinished(mod));
    }

    // clicked, waiting for the block to show up
    public boolean isVerifying() {
        return _attempt.phase() == StationAttempt.Phase.VERIFY;
    }

    // where the block went (or is going), for DoStuffInContainerTask to walk to. nothing until the click is out
    public BlockPos getPlaced() {
        return switch (_attempt.phase()) {
            case VERIFY, DONE -> spotCell(_spot);
            case FALLBACK -> _fallback.getPlaced();
            default -> null;
        };
    }

    @Override
    protected String toHudString() {
        if (_toPlace.length == 1) {
            return "Placing " + HudText.one(HudText.block(_toPlace[0]));
        }
        return "Placing " + HudText.blocks(_toPlace);
    }

    @Override
    protected String toDebugString() {
        return "Place station " + Arrays.toString(_toPlace);
    }

    private Item[] stationItems() {
        return ItemHelper.blocksToItems(_toPlace);
    }

    private boolean isOurs(Block block) {
        for (Block want : _toPlace) {
            if (want == block) {
                return true;
            }
        }
        return false;
    }

    // this try is lost: the spot and its column are out, and the attempt decides whether that was the last straw
    private void lose(AltoClef mod, String why) {
        Debug.logInternal("station spot: " + describe(_spot) + " lost, " + why);
        ban(mod);
        _attempt.failed();
    }

    private void ban(AltoClef mod) {
        if (_spot != null) {
            _bans.ban(_spot.x(), _spot.y(), _spot.z());
        }
        mod.getInputControls().release(Input.SNEAK);
    }

    private static BlockPos spotCell(StationSpots.Spot spot) {
        return spot == null ? null : new BlockPos(spot.x(), spot.y(), spot.z());
    }

    private static String describe(StationSpots.Spot spot) {
        return spotCell(spot).toShortString() + (spot.isTopFace() ? " (floor)" : " (wall)");
    }

    private static Vec3 faceCentre(StationSpots.Spot s) {
        return new Vec3(s.sx() + 0.5 + s.faceX() * 0.5, s.sy() + 0.5 + s.faceY() * 0.5, s.sz() + 0.5 + s.faceZ() * 0.5);
    }

    private static Direction face(int fx, int fy, int fz) {
        for (Direction d : Direction.values()) {
            if (d.getUnitVec3i().getX() == fx && d.getUnitVec3i().getY() == fy && d.getUnitVec3i().getZ() == fz) {
                return d;
            }
        }
        throw new IllegalArgumentException("not a face: " + fx + " " + fy + " " + fz);
    }

    // the crosshair is on the face we picked. a replaceable plant standing in the cell counts too: the click replaces it,
    // so the block still lands in the cell. the ray stops at the plant and never reaches the floor under it
    private static boolean hitsSpot(ClientLevel level, BlockHitResult hit, StationSpots.Spot spot) {
        BlockPos clicked = hit.getBlockPos();
        if (clicked.equals(new BlockPos(spot.sx(), spot.sy(), spot.sz()))
                && hit.getDirection() == face(spot.faceX(), spot.faceY(), spot.faceZ())) {
            return true;
        }
        BlockState state = level.getBlockState(clicked);
        return clicked.equals(spotCell(spot)) && state.canBeReplaced();
    }

    // every ore is *_ore, and ancient debris is far too hard to get here anyway
    private static boolean isOre(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith("_ore");
    }

    private static double sneakEyeHeight(AltoClef mod) {
        return mod.getPlayer().getEyeHeight(Pose.CROUCHING);
    }

    // where we stand and where the eye will be once we are sneaking (we sneak the whole time we place)
    // the nearest dry stand when we are afloat, for anything else that has to get out of the water (the combat run). empty on
    // land or when the water goes on past the shore radius
    public static java.util.Optional<BlockPos> shoreNear(AltoClef mod) {
        return StationSpots.nearestShore(new World(mod), stance(mod), StationSpots.SHORE_RADIUS)
                .map(stand -> new BlockPos(stand.x(), stand.y(), stand.z()));
    }

    private static StationSpots.Stance stance(AltoClef mod) {
        LocalPlayer p = mod.getPlayer();
        BlockPos feet = p.blockPosition();
        return new StationSpots.Stance(feet.getX(), feet.getY(), feet.getZ(), p.getX(), p.getY() + sneakEyeHeight(mod), p.getZ());
    }

    // the cursor has a stack on it or a screen is open: sort that first, a click on the world does nothing with either
    private boolean cursorClear(AltoClef mod) {
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (cursor.isEmpty()) {
            StorageHelper.closeScreen();
            return true;
        }
        Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
        if (moveTo.isPresent()) {
            mod.getSlotHandler().clickSlot(moveTo.get(), 0, ContainerInput.PICKUP);
        } else if (ItemHelper.canDropStack(mod, cursor)) {
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
        } else {
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            mod.getSlotHandler().clickSlot(garbage.orElse(Slot.UNDEFINED), 0, ContainerInput.PICKUP);
        }
        return false;
    }

    // the world, as StationSpots asks about it
    private static final class World implements StationSpots.Cells {
        private final AltoClef mod;
        private final ClientLevel level;
        private final Entity me;
        // every station is a full cube, so any of them stands in for "the thing we are about to place" in the entity check
        private final BlockState placing;

        World(AltoClef mod) {
            this.mod = mod;
            this.level = mod.getWorld();
            this.me = mod.getPlayer();
            this.placing = Blocks.CRAFTING_TABLE.defaultBlockState();
        }

        @Override
        public boolean placeable(int x, int y, int z) {
            BlockPos p = new BlockPos(x, y, z);
            if (!mod.getChunkTracker().isChunkLoaded(p)) {
                return false;
            }
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty() || !(s.isAir() || s.canBeReplaced())) {
                return false;
            }
            // same question BlockItem asks: does a mob, an arrow-less entity or us stand in the way. the box around our standing
            // stations is not this question's business, StationSpots keeps each of them reachable on its own
            boolean avoided = StationBox.asPicker(() -> mod.getExtraBaritoneSettings().shouldAvoidPlacingAt(p));
            return !avoided && level.isUnobstructed(placing, p, CollisionContext.of(me));
        }

        @Override
        public boolean faceSturdy(int x, int y, int z, int fx, int fy, int fz) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            return s.getFluidState().isEmpty() && s.isFaceSturdy(level, p, face(fx, fy, fz));
        }

        @Override
        public boolean passable(int x, int y, int z) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            return s.getFluidState().isEmpty() && s.getCollisionShape(level, p).isEmpty();
        }

        @Override
        public boolean standable(int x, int y, int z) {
            BlockPos floor = new BlockPos(x, y - 1, z);
            if (!mod.getChunkTracker().isChunkLoaded(floor)) {
                return false;
            }
            BlockState s = level.getBlockState(floor);
            return s.getFluidState().isEmpty() && !s.is(Blocks.MAGMA_BLOCK) && s.isFaceSturdy(level, floor, Direction.UP)
                    && s.isCollisionShapeFullBlock(level, floor) && passable(x, y, z) && passable(x, y + 1, z);
        }

        @Override
        public boolean carvable(int x, int y, int z) {
            BlockPos p = new BlockPos(x, y, z);
            if (!mod.getChunkTracker().isChunkLoaded(p)) {
                return false;
            }
            BlockState s = level.getBlockState(p);
            // the cheap nos first. air and plants are the spot search's business, not a wall. a block entity is a chest,
            // a furnace, a spawner, a sign: somebody's. ore is what we are down here for. bedrock reads as -1
            float hardness = s.getDestroySpeed(level, p);
            if (s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced() || s.hasBlockEntity()
                    || hardness < 0 || hardness > CARVE_MAX_HARDNESS || isOre(s.getBlock())) {
                return false;
            }
            return s.isCollisionShapeFullBlock(level, p) && WorldHelper.canBreak(mod, p);
        }

        @Override
        public boolean keep(int x, int y, int z) {
            BlockPos p = new BlockPos(x, y, z);
            return StationHook.keepStanding(p, level.getBlockState(p).getBlock());
        }

        @Override
        public boolean floods(int x, int y, int z) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            return !s.getFluidState().isEmpty() || FallingColumn.isGravity(s.getBlock());
        }

        @Override
        public boolean visible(double ex, double ey, double ez, int sx, int sy, int sz, int fx, int fy, int fz, int cx, int cy, int cz) {
            Vec3 eye = new Vec3(ex, ey, ez);
            Vec3 face = new Vec3(sx + 0.5 + fx * 0.5, sy + 0.5 + fy * 0.5, sz + 0.5 + fz * 0.5);
            Vec3 way = face.subtract(eye);
            if (way.lengthSqr() < 1.0E-6) {
                return false;
            }
            // through the face and a bit past it, a ray that ends exactly on the surface can stop a hair short
            Vec3 end = face.add(way.normalize().scale(0.3));
            BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, me));
            return hit.getType() == HitResult.Type.BLOCK
                    && hitsSpot(level, hit, new StationSpots.Spot(cx, cy, cz, sx, sy, sz));
        }
    }
}
