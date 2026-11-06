package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.speedrun.gamer.end.DragonDeadLatch;
import adris.altoclef.tasks.speedrun.gamer.end.LandingLog;
import adris.altoclef.tasks.speedrun.gamer.end.PillarBed;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// the bed fight, the way altoclef always had it: wait at the bedrock pillar, put a bed on top of it as the dragon comes in to land
// and click it while the head is close. a few clicks in one landing does it and the crystals never get a say (the nearest one heals
// 1 hp every 10 ticks, the beds just outrun that). where and when is PillarBed's call
public class KillEnderDragonWithBedsTask extends Task {
    private static final double DRAGON_GONE_SECONDS = 5;
    // the exit portal is a 3x3 ring around the fountain at (0,0), a tracked END_PORTAL block this close to it is the real one
    private static final int EXIT_PORTAL_RADIUS = 8;
    // an enderman this close is on us, so mob defense may deal with it even mid bed
    private static final double HOLD_STAND_MELEE = 3;
    // a cloud this close while landing is what the landing gets blamed on
    private static final double CLOUD_BLAME_RANGE = 8;
    // something this slow is fine a couple of times a second
    private static final int CLOUD_CELL_TICKS = 10;
    // a landing is 10 s of everything happening at once, this is how we read it afterwards
    private static final int TRACE_TICKS = 5;

    private final Task _whenNotPerchingTask;
    // instance state: this used to be two statics shared by every instance (and Playground), reset only in onStart
    private final DragonDeadLatch _deadLatch = new DragonDeadLatch(DRAGON_GONE_SECONDS);
    private final LandingLog _landing = new LandingLog();
    // the level we pushed, for the hand over on a pause
    private BotBehaviour.State _level;

    private BlockPos _endPortalTop;
    private boolean _perching;
    // landing: mob defense does not pull us off for an enderman then
    private volatile boolean _holdStand;
    private AltoClef _mod;

    private Task _standWalk;
    private long _lastClick = Long.MIN_VALUE / 2;
    private String _placeLog;
    // a copy the path thread can read while we rebuild it
    private volatile Set<BlockPos> _cloudCells = Set.of();

    public KillEnderDragonWithBedsTask(IDragonWaiter notPerchingOverride) {
        _whenNotPerchingTask = (Task) notPerchingOverride;
    }

    private static BlockPos locateExitPortalTop(AltoClef mod) {
        if (!mod.getChunkTracker().isChunkLoaded(new BlockPos(0, 64, 0))) return null;
        int height = WorldHelper.getGroundHeight(mod, 0, 0, Blocks.BEDROCK);
        if (height != -1) return new BlockPos(0, height, 0);
        return null;
    }

    // a tracked END_PORTAL next to (0,0). the tracker could still hold the stronghold portal from the overworld
    public static boolean exitPortalExists(AltoClef mod) {
        Optional<BlockPos> portal = mod.getBlockTracker().getNearestTracking(Blocks.END_PORTAL);
        return portal.isPresent() && Math.abs(portal.get().getX()) <= EXIT_PORTAL_RADIUS && Math.abs(portal.get().getZ()) <= EXIT_PORTAL_RADIUS;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _deadLatch.reset();
        _perching = false;
        _mod = mod;
        _holdStand = false;
        mod.getBlockTracker().trackBlock(Blocks.END_PORTAL);
        // the sword task keeps mob defense out of the dragon fight and so does this one: endermen and the dragon are not a
        // fight or a run while the beds go off
        mod.getBehaviour().releasePaused(this);
        _level = mod.getBehaviour().push();
        mod.getBehaviour().addMobDefenseExclusion(this::ownedOrHeld);
        // and off the aura too, like the sword task: an excluded mob is the aura's otherwise, and a swing turns the head in the
        // middle of aiming a bed
        mod.getBehaviour().addForceFieldExclusion(KillEnderDragonTask::calmDragonFightEntity);
        // paths go around the clouds. through them, the dodge just yanks us straight back out
        mod.getBehaviour().avoidWalkingThrough(p -> _cloudCells.contains(p));
    }

    // no beds left in the inventory and none on the pillar. the caller swaps to the sword when this holds and the dragon is not
    // coming down (see isFinished), this task alone would wait forever
    public boolean outOfBeds(AltoClef mod) {
        return bedsLeft(mod) == 0;
    }

    // beds in the bag plus the one on the pillar waiting for its click: what the strat choice has to count, or a placed bed
    // reads as none left and the phase drops to the sword
    public int bedsLeft(AltoClef mod) {
        // this gets asked before the task ever ran, and a bed on the pillar is worth starting it for
        BlockPos top = _endPortalTop != null ? _endPortalTop : locateExitPortalTop(mod);
        return countBeds(mod.getItemStorage().getItemCount(ItemHelper.BED), top != null && isBed(mod, PillarBed.bed(top)));
    }

    static int countBeds(int inBag, boolean placed) {
        return inBag + (placed ? 1 : 0);
    }

    public boolean isDragonDead() {
        return _deadLatch.isDead();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        Optional<Entity> dragon = mod.getEntityTracker().getClosestEntity(EnderDragon.class);
        _deadLatch.update(exitPortalExists(mod), dragon.isPresent(),
                mod.getChunkTracker().isChunkLoaded(new BlockPos(0, 64, 0)), mod.getWorld().getGameTime() / 20.0);
        _perching = false;
        _holdStand = false;
        if (!_deadLatch.hasSeenDragon() && !_deadLatch.isDead()) {
            setDebugState("Waiting for dragon to spawn.", "Waiting for the dragon");
            return null;
        }
        if (_endPortalTop == null) {
            _endPortalTop = locateExitPortalTop(mod);
            if (_endPortalTop != null) {
                ((IDragonWaiter) _whenNotPerchingTask).setExitPortalTop(_endPortalTop);
            }
        }

        if (_endPortalTop == null) {
            setDebugState("Searching for end portal top.");
            return new GetToXZTask(0, 0);
        }

        if (_deadLatch.isDead()) {
            setDebugState("Waiting for overworld portal to spawn.");
            if (mod.getPlayer().getXRot() != -90) {
                mod.getPlayer().setXRot(-90);
            }
            return null;
        }

        if (dragon.isEmpty()) {
            setDebugState("No dragon found.");
            // not in the latch until it has been gone a while: walk to the middle meanwhile, that is where it ends up anyway
            if (!WorldHelper.inRangeXZ(mod.getPlayer(), _endPortalTop, 0.25)) {
                setDebugState("Going to end portal top at " + _endPortalTop.toString() + ".");
                return new GetToXZTask(_endPortalTop.getX(), _endPortalTop.getZ());
            }
            mod.getFoodChain().shouldStop(false);
            setDebugState("Waiting for the dragon to perch", "Waiting for the dragon");
            return _whenNotPerchingTask;
        }
        return pillarTick(mod, (EnderDragon) dragon.get());
    }

    private Task pillarTick(AltoClef mod, EnderDragon dragon) {
        DragonPhaseInstance now = dragon.getPhaseManager().getCurrentPhase();
        updateCloudCells(mod);
        boolean coming = now.getPhase() == EnderDragonPhase.LANDING || now.getPhase() == EnderDragonPhase.LANDING_APPROACH;
        boolean sat = PillarBed.sat(now.isSitting(), dragon.getY(), _endPortalTop.getY());
        boolean landing = coming && !sat;
        ((IDragonWaiter) _whenNotPerchingTask).setPerchState(landing);
        _perching = landing;
        _holdStand = landing;
        mod.getFoodChain().shouldStop(landing);

        LocalPlayer player = mod.getPlayer();
        BlockPos stand = PillarBed.stand(_endPortalTop);
        BlockPos bed = PillarBed.bed(_endPortalTop);
        boolean atStand = PillarBed.atStand(stand, player.getX(), player.getZ(), player.getBlockY());
        boolean bedDown = isBed(mod, bed);
        AABB box = player.getBoundingBox();
        double seen = landing ? PillarBed.seen(_endPortalTop, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ) : 1;
        boolean covered = landing && PillarBed.covered(player.onGround(),
                PillarBed.reachGap(_endPortalTop, player.getX(), player.getEyeY(), player.getZ()), seen);
        boolean fightHere = PillarBed.fightHere(covered, bedDown, PillarBed.looksEast(_endPortalTop, player.getX(), player.getZ()));
        logLanding(_landing.tick(mod.getWorld().getGameTime(), landing, sat, atStand || covered, bedDown,
                mod.getEntityTracker().entityFound(e -> e.closerThan(player, CLOUD_BLAME_RANGE), AreaEffectCloud.class),
                dragon.getHealth(), player.getHealth() + player.getAbsorptionAmount()));

        if (landing && mod.getWorld().getGameTime() % TRACE_TICKS == 0) {
            traceLanding(dragon, player, bedDown, atStand, covered, seen);
        }
        boolean walking = _standWalk != null && _standWalk.isActive() && !_standWalk.isFinished(mod);
        if (fightHere && walking) {
            Debug.logMessage("pillar bed: covered at " + player.blockPosition().toShortString() + ", dropping the walk to the stand");
            _standWalk = null;
            walking = false;
        }
        // a hop on the stand isn't worth a walk, the click still waits for covered
        switch (PillarBed.step(sat, landing, fightHere || atStand && !walking, bedDown)) {
            case RING -> {
                setDebugState("The dragon is sitting, keeping clear");
                return _whenNotPerchingTask;
            }
            case STAND -> {
                if (!walking) {
                    _standWalk = new GetToBlockTask(stand);
                    Debug.logMessage("pillar bed: going to the stand " + stand.toShortString() + ", phase " + now.getPhase());
                }
                setDebugState("Going to the pillar");
                return _standWalk;
            }
            case WAIT -> setDebugState("Waiting at the pillar");
            case PLACE -> {
                setDebugState("Placing the bed");
                if (mod.getSlotHandler().forceEquipItem(ItemHelper.BED, true)) {
                    LookHelper.lookAt(mod, bed.below(), Direction.UP, true);
                    placeOnPillar(mod, bed.below(), now.getPhase());
                }
            }
            case CLICK -> {
                setDebugState("Wait for it...");
                BlockState placed = mod.getWorld().getBlockState(bed);
                Direction facing = placed.getValue(BedBlock.FACING);
                // a bed the wrong way round goes off on our side of the pillar and there's no click worth that. no cap on the
                // retries, the beds are the cap and the sword is what's after them
                if (!PillarBed.rightWay(facing, placed.getValue(BedBlock.PART) == BedPart.FOOT)) {
                    logPlace("pillar bed: wrong bed, facing " + facing.name() + ", breaking it");
                    return new DestroyBlockTask(bed);
                }
                // keep facing east so the next bed goes straight down
                LookHelper.lookAt(mod, bed.below(), Direction.UP, true);
                clickWhenClose(mod, dragon, bed, facing, covered);
            }
        }
        return null;
    }

    // from the stand the crosshair only grazes the pillar's top face, so an aimed click needs a jump and a lucky frame. sent
    // directly instead
    private void placeOnPillar(AltoClef mod, BlockPos top, EnderDragonPhase<?> phase) {
        // the use packet carries no rotation, the server aims the bed with the last yaw it heard and this tick's look goes out
        // after us. the camera's no help, it says east the moment lookAt runs while the server still has the walk's last step.
        // same race as EnsureFreeInventorySlotTask.aimed, but no orElse: nothing heard yet is no bed, a guess here is a blast
        // in the face
        Direction facing = mod.getClientBaritone().getLookBehavior().serverRotation().map(r -> Direction.fromYRot(r.getYaw())).orElse(null);
        if (facing != Direction.EAST) {
            logPlace("pillar bed: server still has " + (facing == null ? "nothing" : facing.name()) + ", turning east first");
        }
        if (!PillarBed.placeNow(facing, mod.getWorld().getGameTime())) {
            return;
        }
        InteractionResult used = useOn(mod, new Vec3(top.getX() + 0.5, top.getY() + 1, top.getZ() + 0.5), top);
        logPlace(String.format("pillar bed: place on %s, phase %s, %s", top.toShortString(), phase, used));
    }

    private void logPlace(String line) {
        if (!line.equals(_placeLog)) {
            _placeLog = line;
            Debug.logMessage(line);
        }
    }

    // direct as well: the walk and re-aim of an interact task cost seconds per bed, and the landing doesn't wait
    private void clickWhenClose(AltoClef mod, EnderDragon dragon, BlockPos bed, Direction facing, boolean covered) {
        BlockPos foot = WorldHelper.getBedFoot(mod, bed);
        if (foot == null) {
            return;
        }
        double dist = dragon.head.getBoundingBox().getCenter().distanceTo(WorldHelper.toVec3d(foot));
        long tick = mod.getWorld().getGameTime();
        double body = partToBlast(dragon, "body");
        boolean finishing = PillarBed.finish(dragon.getHealth(), bedsLeft(mod), body);
        if (PillarBed.clickNow(covered, dist, finishing, tick, _lastClick)) {
            _lastClick = tick;
            _landing.clicked();
            Debug.logMessage(String.format("pillar bed: click, facing %s, head %.1f, body %.1f, finishing %b, dragon hp %.0f, us hp %.1f", facing.name(), dist, body, finishing, dragon.getHealth(), mod.getPlayer().getHealth()));
            useOn(mod, new Vec3(bed.getX() + 0.5, bed.getY() + 0.5, bed.getZ() + 0.5), bed);
        }
    }

    // from the part's position, not its middle: that's the distance ServerExplosion does the damage by
    private double partToBlast(EnderDragon dragon, String name) {
        for (EnderDragonPart part : dragon.getSubEntities()) {
            if (part.name.equals(name)) {
                return part.position().distanceTo(PillarBed.blast(_endPortalTop));
            }
        }
        return Double.MAX_VALUE;
    }

    private void traceLanding(EnderDragon dragon, LocalPlayer player, boolean bedDown, boolean atStand, boolean covered, double seen) {
        Debug.logInternal("pillar bed: trace head %.1f neck %.1f body %.1f, dragon %.1f %.1f %.1f hp %.1f, bedDown %b, atStand %b, covered %b, seen %.2f, ground %b, us %.1f %.1f %.1f",
                partToBlast(dragon, "head"), partToBlast(dragon, "neck"), partToBlast(dragon, "body"),
                dragon.getX(), dragon.getY(), dragon.getZ(), dragon.getHealth(), bedDown, atStand, covered, seen,
                player.onGround(), player.getX(), player.getY(), player.getZ());
    }

    private static InteractionResult useOn(AltoClef mod, Vec3 at, BlockPos block) {
        BlockHitResult hit = new BlockHitResult(at, Direction.UP, block, false);
        InteractionResult used = Minecraft.getInstance().gameMode.useItemOn(mod.getPlayer(), InteractionHand.MAIN_HAND, hit);
        if (used.consumesAction()) {
            mod.getPlayer().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        }
        return used;
    }

    private void updateCloudCells(AltoClef mod) {
        if (mod.getWorld().getGameTime() % CLOUD_CELL_TICKS != 0) {
            return;
        }
        Set<BlockPos> cells = new HashSet<>();
        for (AreaEffectCloud cloud : mod.getEntityTracker().getTrackedEntities(AreaEffectCloud.class)) {
            for (BlockPos cell : WorldHelper.getBlocksTouchingBox(mod, cloud.getBoundingBox())) {
                cells.add(cell.immutable());
            }
        }
        _cloudCells = cells;
    }

    private static void logLanding(String line) {
        if (line != null) {
            Debug.logMessage(line);
        }
    }

    private static boolean isBed(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).getBlock() instanceof BedBlock;
    }

    // mob defense skips what the fight owns, and an angry enderman too while we go for a bed (unless it is already on us,
    // then it is a fight like any other)
    private boolean ownedOrHeld(Entity entity) {
        if (KillEnderDragonTask.ownedByTheDragonFight(entity)) return true;
        if (!_holdStand || !(entity instanceof Enderman)) return false;
        return _mod == null || _mod.getPlayer() == null || entity.distanceTo(_mod.getPlayer()) > HOLD_STAND_MELEE;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getFoodChain().shouldStop(false);
        mod.getBlockTracker().stopTracking(Blocks.END_PORTAL);
        // same hand over as the sword task
        if (isInterrupting()) {
            mod.getBehaviour().holdWhilePaused(this, chain(), _level);
        } else {
            mod.getBehaviour().releasePaused(this);
            // an interrupt comes back to the same landing, the log notices the gap by itself
            logLanding(_landing.finish());
        }
        _level = null;
        mod.getBehaviour().pop();
    }

    @Override
    protected void onStopWhilePaused(AltoClef mod) {
        mod.getBehaviour().releasePaused(this);
        logLanding(_landing.finish());
    }

    // finished = out of beds and the dragon is not coming down, the phase swaps to KillEnderDragonTask then. while it is we stay:
    // the last bed may be on the pillar waiting for its click
    @Override
    public boolean isFinished(AltoClef mod) {
        return isActive() && !_perching && outOfBeds(mod);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof KillEnderDragonWithBedsTask;
    }

    @Override
    protected String toHudString() {
        return "Fighting the Ender Dragon with beds";
    }

    @Override
    protected String toDebugString() {
        return "Bedding the Ender Dragon";
    }
}
