/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.*;
import baritone.altoclef.AltoClefSettings;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.utils.BlockStateInterface;
import baritone.utils.FallingColumn;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import baritone.utils.ExperimentalMovement;

public abstract class Movement implements IMovement, MovementHelper {

    public static final Direction[] HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    protected final IBaritone baritone;
    protected final IPlayerContext ctx;

    private MovementState currentState = new MovementState().setStatus(MovementStatus.PREPPING);

    protected final BetterBlockPos src;

    protected final BetterBlockPos dest;

    /**
     * The positions that need to be broken before this movement can ensue
     */
    protected final BetterBlockPos[] positionsToBreak;

    /**
     * The position where we need to place a block before this movement can ensue
     */
    protected final BetterBlockPos positionToPlace;

    private Double cost;

    public List<BlockPos> toBreakCached = null;
    public List<BlockPos> toPlaceCached = null;
    public List<BlockPos> toWalkIntoCached = null;

    private Set<BetterBlockPos> validPositionsCached = null;

    private Boolean calculatedWhileLoaded;

    private final SwimStall swimStall = new SwimStall();
    private SwimEscape swimEscape = new SwimEscape();
    private boolean standingUp;
    // this tick's answer for the player's column and dest's, see LowRoof
    private boolean lowRoofOwn;
    private boolean lowRoofDest;
    private LowRoof.Lip lowRoofLip = new LowRoof.Lip();
    // temporary (swimprobe)
    private boolean probeSprintBefore;

    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak, BetterBlockPos toPlace) {
        this.baritone = baritone;
        // null in the offline tests that build whole paths (the trip what-if ones), a movement there only gets priced
        this.ctx = baritone == null ? null : baritone.getPlayerContext();
        this.src = src;
        this.dest = dest;
        this.positionsToBreak = toBreak;
        this.positionToPlace = toPlace;
    }

    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak) {
        this(baritone, src, dest, toBreak, null);
    }

    public double getCost() throws NullPointerException {
        return cost;
    }

    public double getCost(CalculationContext context) {
        if (cost == null) {
            cost = calculateCost(context);
        }
        return cost;
    }

    public abstract double calculateCost(CalculationContext context);

    public double recalculateCost(CalculationContext context) {
        cost = null;
        return getCost(context);
    }

    public void override(double cost) {
        this.cost = cost;
    }

    protected abstract Set<BetterBlockPos> calculateValidPositions();

    public Set<BetterBlockPos> getValidPositions() {
        if (validPositionsCached == null) {
            validPositionsCached = calculateValidPositions();
            Objects.requireNonNull(validPositionsCached);
        }
        return validPositionsCached;
    }

    // a forced aim (breaking, placing, a door) means somebody needs the crosshair exactly where it is, smoothing keeps out
    public boolean isTargetingBlock() {
        return currentState.getTarget().hasToForceRotations();
    }

    protected boolean playerInValidPosition() {
        return getValidPositions().contains(ctx.playerFeet()) || getValidPositions().contains(((PathingBehavior) baritone.getPathingBehavior()).pathStart());
    }

    /**
     * Handles the execution of the latest Movement
     * State, and offers a Status to the calling class.
     *
     * @return Status
     */
    // read by the falltrace, which wants the status of the tick we fell on, not the one PathExecutor saw last
    public MovementStatus status() {
        return currentState.getStatus();
    }

    @Override
    public MovementStatus update() {
        ctx.player().getAbilities().flying = false;
        currentState = updateState(currentState);
        if (MovementHelper.isLiquid(ctx, ctx.playerFeet()) || ctx.player().isInWater()) {
            checkLowRoof();
            boolean sinking = sinkToEscape();
            // sinkToEscape starts every tick by clearing this. it's what stops PathExecutor arming sprint, and an armed sprint
            // is all MixinEntity needs to latch the swim state again
            standingUp |= lowRoof();
            if (sinking) {
                swimStall.reset();
            } else if (shouldSwim()) {
                // sprint is what enters and keeps the swim state. vanilla only STARTS sprinting from
                // the key while on the ground or eye-underwater, and actively cancels it at the water
                // surface (which is exactly where we used to bob), so arm the flag ourselves.
                // MixinEntity.allowSwimStart then latches the swim state this same tick instead of
                // waiting for gravity to pull the eye under
                currentState.setInput(Input.SPRINT, true);
                probeSprintBefore = ctx.player().isSprinting();
                ctx.player().setSprinting(true);
                boolean floating = ctx.player().isSwimming() && !ctx.player().onGround();
                if (currentState.getStatus() == MovementStatus.RUNNING) {
                    watchSwimStall(floating);
                } else {
                    swimStall.reset();
                }
                if (currentState.getStatus() == MovementStatus.RUNNING && floating) {
                    holdWaterline();
                    if (!swimStall.diving()) {
                        preTurnIntoTheBend();
                    }
                } else if (!ctx.player().isSwimming() && ctx.player().onGround() && ctx.player().position().y < dest.y + BOB_CLEARANCE) {
                    // swim state hasn't latched yet and we're on the bottom: the old bob, for one tick
                    currentState.setInput(Input.JUMP, true);
                }
            } else {
                swimStall.reset();
                // (a movement that shut the swim gate lets go of sprint even before the swim state latches, or the mixin
                // would latch it this very tick)
                if (ExperimentalMovement.allowSwimming() && (ctx.player().isSwimming() || !swimAllowed() || lowRoof())) {
                    // standing up to breathe (see shouldSwim). sprintInWater is on by default and traverse
                    // requests sprint every tick, which would keep us crawling along the bottom with our
                    // head under, so drop it explicitly
                    currentState.setInput(Input.SPRINT, false);
                    ctx.player().setSprinting(false);
                }
                // the dest.y rule is the old bob. deep under (a clutch into a pool, a dive gone long) dest can be level with
                // us or below and that never lets go of the bottom, so running low on air goes up no matter where dest is
                if (lowRoof()) {
                    obeyLowRoof();
                } else if (ctx.player().position().y < dest.y + BOB_CLEARANCE || Surfacing.wantsAir(ctx.player())) {
                    currentState.setInput(Input.JUMP, true);
                }
            }
        } else {
            swimStall.reset();
            // nothing else clears it once we're out of the water, and it would hold sprint off for the rest of the movement
            standingUp = false;
        }
        if (ctx.player().isInWall()) {
            ctx.getSelectedBlock().ifPresent(pos -> MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, pos)));
            currentState.setInput(Input.CLICK_LEFT, true);
        }

        // If the movement target has to force the new rotations, or we aren't using silent move, then force the rotations
        currentState.getTarget().getRotation().ifPresent(rotation ->
                baritone.getLookBehavior().updateTarget(
                        rotation,
                        currentState.getTarget().hasToForceRotations()));
        baritone.getInputOverrideHandler().clearAllKeys();
        currentState.getInputStates().forEach((input, forced) -> {
            baritone.getInputOverrideHandler().setInputForceState(input, forced);
        });
        currentState.getInputStates().clear();

        // If the current status indicates a completed movement
        if (currentState.getStatus().isComplete()) {
            baritone.getInputOverrideHandler().clearAllKeys();
        }

        return currentState.getStatus();
    }

    /**
     * Where the feet sit below the water surface while swimming along it. The swim pose puts the eye 0.4
     * above the feet and vanilla checks the eye 1/9 below where it really is, so the eye is out of the water
     * (air refills) at any depth below 0.289; the body stays in water (swim state and sprint speed hold)
     * at any depth above 0. This is the middle of that band: head out, shoulders in, as much margin as
     * possible each way
     */
    static final double WATERLINE_DEPTH = 0.15;

    /**
     * How far above a destination's block the feet aim when that would be higher than the waterline, i.e.
     * climbing out onto land. Below the waterline (a destination on the water itself, or a shore at the
     * same level that step assist handles) the waterline wins
     */
    static final double DEST_CLEARANCE = 0.3;

    // the old bob holds jump until the feet are this far above dest's block
    static final double BOB_CLEARANCE = 0.6;

    /**
     * Air ticks (of the 300 max) at which a swimmer crawling along the bottom of shallow water stands up to
     * breathe: 5 seconds of margin before drowning damage starts
     */
    static final int SURFACE_AT_AIR = 100;

    // vanilla swim physics per tick, from LivingEntity.jumpInLiquid, Player.travel and LivingEntity.travel:
    // holding jump adds SWIM_JUMP_BOOST, then vertical speed is pulled toward the look vector's y by
    // SWIM_PULL (SWIM_PULL_STEEP when looking down steeper than SWIM_STEEP_LOOK), then it's scaled by
    // WATER_DRAG_Y. sprinting in water has no gravity at all, which is why swimmers float
    private static final double SWIM_JUMP_BOOST = 0.04;
    private static final double SWIM_PULL = 0.06;
    private static final double SWIM_PULL_STEEP = 0.085;
    private static final double SWIM_STEEP_LOOK = -0.2;
    private static final double WATER_DRAG_Y = 0.8;

    /**
     * Wanted vertical speed per block of height error, and per unit of current vertical speed (damping).
     * Tuned against the vanilla numbers above: no overshoot from any depth, settles within a second, and a
     * couple of degrees of random look offset moves the waterline by about a hundredth of a block
     */
    private static final double WATERLINE_GAIN = 0.3;
    private static final double WATERLINE_DAMPING = 0.9;

    // coast distance in blocks, per block per tick of speed, at water drag 0.8: 1 / (1 - 0.8).
    // the early turn starts when the corner is closer than this
    private static final double WATER_COAST_TICKS = 5;

    // where the feet go during a dive, relative to dest's block. the 0.6 tall box tops out 0.38 under anything at head
    // height. not any deeper: playerFeet rounds into the block below past -0.125, and traverse stops pressing W the
    // moment it thinks we're on the wrong y
    private static final double DIVE_FEET = 0.02;

    // half the swim box plus a hair, so a block the box is pressed flat against counts as in the way
    private static final double SWIM_HALF_WIDTH = 0.31;

    // a movement that needs the player upright in water (crouching to place a block) says no here: the swim pose beats the
    // crouch pose, and sprint is what holds the swim pose. only MovementPillar says no, see its placing
    protected boolean swimAllowed() {
        return true;
    }

    /**
     * Holding sprint is what keeps the vanilla swim state alive, so swimming is only on the table when
     * we're actually in water, sprinting is allowed, and we have the hunger to sprint
     */
    private boolean shouldSwim() {
        if (!swimAllowed()
                || !ExperimentalMovement.allowSwimming()
                || !ctx.player().isInWater()
                || !Baritone.settings().allowSprint.value
                || ctx.player().getFoodData().getFoodLevel() <= 6) {
            return false;
        }
        if (lowRoof()) {
            return false;
        }
        if (currentState.getStatus() == MovementStatus.PREPPING) {
            // something's in the way and prepared() is mining it. the swim pose is the worst place to do
            // that from: vanilla mines 5x slower off the ground and 5x slower again with the eye under,
            // and prepared() owns the pitch to aim at the block so holdWaterline can't keep the eye out.
            // stand up instead: eye out bobbing at the surface, full speed on the bottom of the shallows
            return false;
        }
        if (!ctx.player().onGround()) {
            // floating: holdWaterline keeps the eye out, so air is never a problem out here
            return true;
        }
        // shallow water: the swim pose crawls along the bottom with the eye under, so air is a clock.
        // crawl until it runs low, then stand up (which is what surfaces us here, pitch can't) and stay
        // standing until it's actually full, otherwise we'd get one tick of air and go right back under.
        // a swimmer just brushing the bottom at the shore keeps swimming, its eye is already out
        int air = ctx.player().getAirSupply();
        if (ctx.player().isUnderWater()) {
            return air >= SURFACE_AT_AIR;
        }
        return ctx.player().isSwimming() || air >= ctx.player().getMaxAirSupply();
    }

    /**
     * Sit exactly on the waterline while swimming: head in the air so we never drown, body in the water
     * so we keep the swim state and its sprint speed.
     * <p>
     * The trick is that with jump held, vanilla's vertical speed settles at a value set purely by the
     * look pitch (about 25 degrees down hovers, level rises, steeper down sinks), so pitch is a smooth
     * throttle instead of the on/off jump key. Cutting the jump key at a depth band is what used to
     * launch us out of the water and bob us back in: the momentum after a jump assisted rise carries four
     * times the last tick's speed
     */
    private void holdWaterline() {
        double feetY = ctx.player().position().y;
        double surfaceY = feetY + ctx.player().getFluidHeight(FluidTags.WATER);
        // the waterline, unless the movement is taking us higher (climbing out onto land)
        double targetY = LowRoof.waterline(surfaceY, dest.y);
        if (swimStall.diving()) {
            // down from the waterline, where the box pokes into head height, to just above dest's floor
            targetY = dest.y + DIVE_FEET;
        }
        double wanted = WATERLINE_GAIN * (targetY - feetY) - WATERLINE_DAMPING * ctx.player().getDeltaMovement().y;
        float pitch = pitchForSwimVelocity(wanted);
        if (pitch == ctx.playerRotations().getPitch()) {
            // same convention as RotationUtils.reachable: equal to the current pitch means "don't care"
            pitch += 0.0001F;
        }
        final float swimPitch = pitch;
        currentState.getTarget().getRotation().ifPresent(rotation ->
                currentState.setTarget(new MovementState.MovementTarget(rotation.withPitch(swimPitch), false))
        );
        currentState.setInput(Input.JUMP, true);
    }

    /**
     * Water momentum coasts half a block past a bend, taking the 0.6 wide hitbox with it. braking
     * would be the honest fix but vanilla cancels sprint swim the moment W stops counting as forward
     * (LocalPlayer.aiStep), which flickers the whole swim state. so turn early instead: aim at the
     * block after the corner once the coast would reach it, and the momentum is the entry angle
     */
    private void preTurnIntoTheBend() {
        if (!MovementHelper.isWater(BlockStateInterface.get(ctx, dest))) {
            // climbing out onto land wants the speed
            return;
        }
        if (dest.x == src.x && dest.z == src.z) {
            // vertical (pillar, downward), no corner in the horizontal plane
            return;
        }
        IPathExecutor current = baritone.getPathingBehavior().getCurrent();
        if (current == null) {
            return;
        }
        List<BetterBlockPos> positions = current.getPath().positions();
        int pos = current.getPosition();
        if (pos + 2 >= positions.size()) {
            return; // path is over, nothing to aim the early turn at
        }
        BetterBlockPos next = positions.get(pos + 2);
        double ax = dest.x - src.x;
        double az = dest.z - src.z;
        double bx = next.x - dest.x;
        double bz = next.z - dest.z;
        if (bx == 0 && bz == 0) {
            return; // next leg is vertical (a descend), no corner to cut either
        }
        if (ax * bx + az * bz >= 0.99 * Math.hypot(ax, az) * Math.hypot(bx, bz)) {
            return; // straight chains share their heading exactly, anything else is a corner
        }
        double speed = Math.hypot(ctx.player().getDeltaMovement().x, ctx.player().getDeltaMovement().z);
        double dist = Math.hypot(dest.x + 0.5 - ctx.player().position().x, dest.z + 0.5 - ctx.player().position().z);
        if (dist < speed * WATER_COAST_TICKS) {
            BlockStateInterface bsi = new BlockStateInterface(ctx);
            double nx = next.x + 0.5;
            double nz = next.z + 0.5;
            if (!swimSweepClear(bsi, nx, nz, dest.y, next) || !swimSweepClear(bsi, nx, nz, dest.y + 1, next)) {
                // cutting the bend drags the box across the inside corner, and that one's a block. go the long way
                return;
            }
            float yaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(next), ctx.playerRotations()).getYaw();
            currentState.getTarget().getRotation().ifPresent(rotation ->
                    currentState.setTarget(new MovementState.MovementTarget(new Rotation(yaw, rotation.getPitch()), false))
            );
        }
    }

    private boolean lowRoof() {
        return lowRoofOwn || lowRoofDest;
    }

    // once a tick, before anybody asks. a movement that says no to the swim (pillar) keeps its own keys, that one has to climb
    private void checkLowRoof() {
        lowRoofOwn = false;
        lowRoofDest = false;
        if (!swimAllowed() || !ExperimentalMovement.allowSwimming() || !ctx.player().isInWater()) {
            return;
        }
        int feet = Mth.floor(ctx.player().getY());
        lowRoofOwn = LowRoof.tooLow(roofColumn(ctx.player().getX(), ctx.player().getZ(), feet), dest.y);
        lowRoofDest = LowRoof.tooLow(roofColumn(dest.x + 0.5, dest.z + 0.5, feet), dest.y);
    }

    private LowRoof.Column roofColumn(double x, double z, int feet) {
        double top = LowRoof.waterTop(ctx.world(), Mth.floor(x), feet, Mth.floor(z));
        return new LowRoof.Column() {
            @Override
            public double waterTop() {
                return top;
            }

            @Override
            public boolean standingFits(double feetY) {
                return standingBoxFits(x, feetY, z);
            }

            @Override
            public double roofOver(double feetY) {
                double roof = Double.POSITIVE_INFINITY;
                for (VoxelShape shape : ctx.world().getBlockCollisions(ctx.player(), standingBox(x, feetY, z))) {
                    roof = Math.min(roof, shape.min(Direction.Axis.Y));
                }
                return roof;
            }
        };
    }

    // deflated like the server's own check, or a box touching the roof exactly at the cap would count as stuck in it
    private AABB standingBox(double x, double feetY, double z) {
        return ctx.player().getDimensions(Pose.STANDING).makeBoundingBox(x, feetY, z).deflate(1.0E-5);
    }

    private boolean standingBoxFits(double x, double feetY, double z) {
        return ctx.world().noBlockCollision(ctx.player(), standingBox(x, feetY, z));
    }

    // these go over whatever the movement asked for: traverse's wrong y branch presses jump all by itself
    private void obeyLowRoof() {
        boolean running = currentState.getStatus() == MovementStatus.RUNNING;
        double feetY = ctx.player().position().y;
        boolean jump = LowRoof.jump(lowRoofOwn, ctx.player().getPose() == Pose.STANDING, feetY, dest.y);
        // still prepping in open water: nobody's pressed against the lip, and prepared() wants the eye out to mine from, so the old bob stays
        boolean bob = !lowRoofOwn && !running && feetY < dest.y + BOB_CLEARANCE;
        currentState.setInput(Input.JUMP, jump || bob || Surfacing.wantsAir(ctx.player()));
        boolean fits = !lowRoofOwn && lowRoofDest && standingBoxFits(dest.x + 0.5, feetY, dest.z + 0.5);
        LowRoof.Forward forward = lowRoofLip.tick(lowRoofOwn, lowRoofDest, fits, running);
        if (LowRoof.leanIn(forward, running, currentState.getTarget().hasToForceRotations(), ctx.playerFeet().y, src.y, dest.y)) {
            MovementHelper.moveTowards(ctx, currentState, dest);
        }
        if (forward == LowRoof.Forward.GO) {
            return;
        }
        currentState.setInput(Input.MOVE_FORWARD, false);
        currentState.setInput(Input.MOVE_BACK, false);
        currentState.setInput(Input.MOVE_LEFT, false);
        currentState.setInput(Input.MOVE_RIGHT, false);
        if (forward == LowRoof.Forward.GIVE_UP) {
            logDebug("swim: never sank low enough to get under the roof at " + dest + ", replanning");
            currentState.setStatus(MovementStatus.FAILED);
        }
    }

    // the cell keeps failing whichever way out we pick, so stop swimming and sink out of it, see SwimEscape
    private boolean sinkToEscape() {
        standingUp = false;
        if (currentState.getStatus() != MovementStatus.RUNNING || !ctx.player().isInWater()) {
            return false;
        }
        PathingBehavior pb = (PathingBehavior) baritone.getPathingBehavior();
        int exits = pb.failedMoves().exitsFrom(src, ctx.world(), System.currentTimeMillis());
        // needsAir, not wantsAir: low on air under a flooded roof isn't the moment to sit still either. the normal swim
        // takes over from here and it's the one that goes up when up is open
        boolean needsAir = Surfacing.needsAir(ctx.player().getAirSupply(), ctx.player().isUnderWater());
        // both ends: a move that goes down wants us under src, that's just the move working
        boolean belowMove = ctx.playerFeet().y < Math.min(src.y, dest.y);
        switch (swimEscape.tick(exits, needsAir, ctx.player().onGround(), belowMove)) {
            case NONE -> {
                return false;
            }
            case GIVE_BACK -> {
                // floating back up for another go is the loop we came here to break, the replan gets to start from down here
                logDebug("swim: sank out of " + src + ", replanning from here");
                currentState.setStatus(MovementStatus.FAILED);
                return true;
            }
            case SINK -> {
                if (swimEscape.changed()) {
                    logDirect("swim: " + exits + " exits from " + src + " failed, sinking to get out");
                }
                currentState.setInput(Input.MOVE_FORWARD, false);
                currentState.setInput(Input.MOVE_BACK, false);
                currentState.setInput(Input.MOVE_LEFT, false);
                currentState.setInput(Input.MOVE_RIGHT, false);
                currentState.setInput(Input.JUMP, false);
            }
            case WALK -> {
                if (swimEscape.changed()) {
                    logDebug("swim: on the bottom at " + src + ", walking out");
                }
                // a climb still needs the bob, anything else would only lift us back off the floor
                currentState.setInput(Input.JUMP, dest.y > src.y);
            }
        }
        currentState.setInput(Input.SPRINT, false);
        ctx.player().setSprinting(false);
        standingUp = true;
        return true;
    }

    // feeds SwimStall. horizontal moves only, a pillar or a downward in water has no distance to dest worth watching
    private void watchSwimStall(boolean floating) {
        if (dest.x == src.x && dest.z == src.z) {
            return;
        }
        double feetY = ctx.player().position().y;
        double flat = Math.hypot(dest.x + 0.5 - ctx.player().position().x, dest.z + 0.5 - ctx.player().position().z);
        // climbing out onto a bank is mostly going up while pressed against it, that counts as getting somewhere.
        // nothing else gets the height, or a dive would look like progress all by itself
        double up = dest.y > src.y ? Math.max(0, dest.y - feetY) : 0;
        if (flat < 0.3 || (feetY < dest.y - 0.5 && !swimStall.diving())) {
            // right over dest, or sunk under it after a fall into the water. the distance can't get any better here,
            // floating back up is what finishes the movement, so there's no stall to see
            swimStall.reset();
            return;
        }
        boolean wasDiving = swimStall.diving();
        switch (swimStall.tick(Math.hypot(flat, up), floating && canDive())) {
            case DIVE -> {
                if (!wasDiving) {
                    logDebug("swimming into something at head height, diving under it");
                }
            }
            case GIVE_UP -> {
                logDebug("stuck while swimming, replanning around it");
                swimProbe(feetY, flat, up, wasDiving, floating);
                swimStall.reset();
                ((PathingBehavior) baritone.getPathingBehavior()).swimStalls().record(src, dest, ctx.world(), System.currentTimeMillis());
                currentState.setStatus(MovementStatus.FAILED);
            }
            default -> {
            }
        }
    }

    // temporary: one line per new swim stall pair, to find out why swimming into a 2-high gap pins us. strip with the swimprobe tag
    private void swimProbe(double feetY, double flat, double up, boolean wasDiving, boolean floating) {
        PathingBehavior pb = (PathingBehavior) baritone.getPathingBehavior();
        if (pb.swimStalls().snapshot(ctx.world(), System.currentTimeMillis()).penalty(src.x, src.y, src.z, dest.x, dest.y, dest.z) != 0) {
            return;
        }
        AABB box = ctx.player().getBoundingBox();
        System.out.println("[swimprobe] " + src + " -> " + dest + " feetY=" + feetY + " flat=" + flat + " up=" + up + " wasDiving=" + wasDiving
                + " floating=" + floating + " pose=" + ctx.player().getPose() + " swimming=" + ctx.player().isSwimming()
                + " inWater=" + ctx.player().isInWater() + " underWater=" + ctx.player().isUnderWater() + " onGround=" + ctx.player().onGround()
                + " box=" + box.minY + ".." + box.maxY + " src+1=" + blockName(src.above(1)) + " dest+1=" + blockName(dest.above(1)) + " dest+2=" + blockName(dest.above(2))
                + " src+2=" + blockName(src.above(2)) + " sprinting=" + ctx.player().isSprinting() + " move=" + getClass().getSimpleName() + " branch=" + swimStall.lastGiveUp
                + " delta=" + ctx.player().getDeltaMovement()
                // all three as of last tick, this tick's aren't decided yet (holdWaterline asks for jump after the stall fires).
                // sprint is the flag from before we armed it, sprinting= above is always true here and the key is always cleared
                + " forward=" + baritone.getInputOverrideHandler().isInputForcedDown(Input.MOVE_FORWARD)
                + " jump=" + baritone.getInputOverrideHandler().isInputForcedDown(Input.JUMP)
                + " sprintBefore=" + probeSprintBefore);
    }

    private String blockName(BlockPos pos) {
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(ctx.world().getBlockState(pos).getBlock()));
    }

    // a dive only gets under something at head height, with water at our level the whole way and more under it (so the
    // feet hovering just over dest's floor never touch a bottom and lose the swim), and never on borrowed air:
    // surfacing to breathe beats everything
    private boolean canDive() {
        if (ctx.player().getAirSupply() < SURFACE_AT_AIR || dest.y > src.y) {
            return false;
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        double tx = dest.x + 0.5;
        double tz = dest.z + 0.5;
        return !swimSweepClear(bsi, tx, tz, dest.y + 1, null)
                && swimSweep(bsi, tx, tz, dest.y, null, true)
                && swimSweep(bsi, tx, tz, dest.y - 1, null, true);
    }

    private boolean swimSweepClear(BlockStateInterface bsi, double tx, double tz, int y, BetterBlockPos next) {
        return swimSweep(bsi, tx, tz, y, next, false);
    }

    // drags the swim box in a straight line from where we are to (tx, tz) at level y and asks if every block it
    // touches lets a swimmer through (or is plain water, for waterOnly). with next set the path's own columns get a
    // pass, the planner already checked those, we're after the corners in between
    private boolean swimSweep(BlockStateInterface bsi, double tx, double tz, int y, BetterBlockPos next, boolean waterOnly) {
        double px = ctx.player().position().x;
        double pz = ctx.player().position().z;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(tx - px, tz - pz) / 0.25));
        for (int i = 0; i <= steps; i++) {
            double cx = px + (tx - px) * i / steps;
            double cz = pz + (tz - pz) * i / steps;
            for (int corner = 0; corner < 4; corner++) {
                int bx = Mth.floor(cx + ((corner & 1) == 0 ? -SWIM_HALF_WIDTH : SWIM_HALF_WIDTH));
                int bz = Mth.floor(cz + ((corner & 2) == 0 ? -SWIM_HALF_WIDTH : SWIM_HALF_WIDTH));
                if (next != null && ((bx == src.x && bz == src.z) || (bx == dest.x && bz == dest.z) || (bx == next.x && bz == next.z))) {
                    continue;
                }
                BlockState state = bsi.get0(bx, y, bz);
                if (waterOnly ? !state.is(Blocks.WATER) : !MovementHelper.swimmerFits(bsi, bx, y, bz, state)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The pitch that, with jump held, makes vanilla settle at the given vertical speed. Clamped to
     * straight up / straight down, which is about +0.31 / -0.14 blocks per tick
     */
    private static float pitchForSwimVelocity(double velocity) {
        double look = lookForSwimVelocity(velocity, SWIM_PULL);
        if (look < SWIM_STEEP_LOOK) {
            // the stronger pull kicks in past this look, so the map is piecewise (and has a small gap
            // right at the threshold, which just pins to the threshold)
            look = Math.min(SWIM_STEEP_LOOK, lookForSwimVelocity(velocity, SWIM_PULL_STEEP));
        }
        look = Math.max(-1, Math.min(1, look));
        // look vector y is -sin(pitch)
        return (float) -Math.toDegrees(Math.asin(look));
    }

    private static double lookForSwimVelocity(double velocity, double pull) {
        // steady state of v = drag * ((1 - pull) * (v + boost) + pull * look), solved for look
        double carry = WATER_DRAG_Y * (1 - pull);
        return (velocity * (1 - carry) - carry * SWIM_JUMP_BOOST) / (WATER_DRAG_Y * pull);
    }

    // how far above a cell we still count a falling block as "on its way down into it". a stack drops all at once, so
    // the top of a tall one is still way up there when the bottom one has landed
    private static final double FALL_LOOKAHEAD = 6;
    // ticks we'll stand around for a column to land before deciding it was floating gravel that is never going to move
    private static final int FALL_WAIT_MAX = 60;
    private static final int FALL_PENDING_MAX = 10;

    private int fallWaited;
    private int fallPending;
    // set by prepared() while it is waiting on a column, so a movement that walks while breaking doesn't walk into where it lands
    protected boolean waitingOnFall;
    // sticks once the hold above has stalled this movement, the timing recorder doesn't want those ticks in a sample
    private boolean heldForFall;

    public boolean wasHeldForFall() {
        return heldForFall;
    }

    private FallingColumn.Cells fallCells() {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        return FallingColumn.over((x, y, z) -> ctx.world().getBlockState(at.set(x, y, z)));
    }

    // the stack over this cell would end up in the cells we are standing in
    private boolean fallsOnUs(BlockPos pos) {
        AABB box = ctx.player().getBoundingBox();
        return FallingColumn.landsOnBox(fallCells(), pos.getX(), pos.getY(), pos.getZ(), box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    // the cell is open and a falling block sits on it with nothing under that one. it lets go in two ticks, and until
    // then there is no entity for the check above to see, which is the exact gap we'd walk into
    private boolean aboutToDrop(BlockPos pos) {
        FallingColumn.Cells w = fallCells();
        return !w.stops(pos.getX(), pos.getY(), pos.getZ()) && w.falls(pos.getX(), pos.getY() + 1, pos.getZ());
    }

    // true while a column is coming down into one of the cells we break, or is about to. the counters give up on a column
    // that never lands (sand held up by something we can't see, a dupe machine) because it isn't worth standing here for
    private boolean holdForFalling() {
        if (!Baritone.settings().pauseMiningForFallingBlocks.value) {
            return false;
        }
        boolean inFlight = false;
        boolean pending = false;
        for (BetterBlockPos blockPos : positionsToBreak) {
            if (!ctx.world().getEntitiesOfClass(FallingBlockEntity.class, new AABB(0, 0, 0, 1, 1.1 + FALL_LOOKAHEAD, 1).move(blockPos)).isEmpty()) {
                inFlight = true;
            } else if (aboutToDrop(blockPos)) {
                pending = true;
            }
        }
        fallWaited = inFlight ? fallWaited + 1 : 0;
        fallPending = pending ? fallPending + 1 : 0;
        return inFlight && fallWaited <= FALL_WAIT_MAX || pending && fallPending <= FALL_PENDING_MAX;
    }

    // what prepared() is stuck on, for the stand line: PREPPING with no keys down says nothing about which cell or why. null when it let go
    private String preparedHold;

    public String preparedHold() {
        return preparedHold;
    }

    private String breakHold(BetterBlockPos pos, boolean reachable) {
        return "break " + pos.x + "," + pos.y + "," + pos.z + " " + BlockStateInterface.get(ctx, pos).getBlock()
                + " avoid=" + AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(pos.x, pos.y, pos.z)
                + " reach=" + reachable + " look=" + ctx.isLookingAt(pos);
    }

    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        boolean somethingInTheWay = false;
        waitingOnFall = holdForFalling();
        if (waitingOnFall) {
            heldForFall = true;
            preparedHold = "falling";
            return false;
        }
        for (BetterBlockPos blockPos : positionsToBreak) {
            if (!MovementHelper.canWalkThrough(ctx, blockPos) && fallsOnUs(blockPos)) {
                BetterBlockPos feet = ctx.playerFeet();
                if (feet.x != blockPos.x || feet.z != blockPos.z) {
                    // only an edge of us hangs into that column (walkWhileBreaking momentum, mostly). failing the movement
                    // here would just get the same movement planned again, so back off into src and break it from there
                    MovementHelper.moveTowards(ctx, state, src);
                    preparedHold = "column " + blockPos.x + "," + blockPos.y + "," + blockPos.z;
                    return false;
                }
                // we really are under it. the plan was made before whatever is stacked up there got there (the planner
                // refuses this for ascend and pillar), and the alternative is to dig it out from under the column
                state.setStatus(MovementStatus.UNREACHABLE);
                return true;
            }
            if (!MovementHelper.canWalkThrough(ctx, blockPos)) { // can't break air, so don't try
                somethingInTheWay = true;
                MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, blockPos));
                Optional<Rotation> reachable = RotationUtils.reachable(ctx, blockPos, ctx.playerController().getBlockReachDistance());
                if (reachable.isPresent()) {
                    Rotation rotTowardsBlock = reachable.get();
                    state.setTarget(new MovementState.MovementTarget(rotTowardsBlock, true));
                    if (ctx.isLookingAt(blockPos) || ctx.playerRotations().isReallyCloseTo(rotTowardsBlock)) {
                        state.setInput(Input.CLICK_LEFT, true);
                    }
                    preparedHold = breakHold(blockPos, true);
                    return false;
                }
                //get rekt minecraft
                //i'm doing it anyway
                //i dont care if theres snow in the way!!!!!!!
                //you dont own me!!!!
                state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                        VecUtils.getBlockPosCenter(blockPos), ctx.playerRotations()), true)
                );
                // don't check selectedblock on this one, this is a fallback when we can't see any face directly, it's intended to be breaking the "incorrect" block
                state.setInput(Input.CLICK_LEFT, true);
                preparedHold = breakHold(blockPos, false);
                return false;
            }
        }
        if (somethingInTheWay) {
            // There's a block or blocks that we can't walk through, but we have no target rotation to reach any
            // So don't return true, actually set state to unreachable
            state.setStatus(MovementStatus.UNREACHABLE);
            return true;
        }
        return true;
    }

    @Override
    public boolean safeToCancel() {
        return safeToCancel(currentState);
    }

    protected boolean safeToCancel(MovementState currentState) {
        return true;
    }

    @Override
    public BetterBlockPos getSrc() {
        return src;
    }

    @Override
    public BetterBlockPos getDest() {
        return dest;
    }

    @Override
    public void reset() {
        currentState = new MovementState().setStatus(MovementStatus.PREPPING);
        fallWaited = 0;
        fallPending = 0;
        waitingOnFall = false;
        heldForFall = false;
        swimStall.reset();
        swimEscape = new SwimEscape();
        standingUp = false;
        lowRoofOwn = false;
        lowRoofDest = false;
        lowRoofLip = new LowRoof.Lip();
    }

    // true on ticks the swim escape has the keys or a low roof is around, PathExecutor must not turn sprint back on over it
    public boolean standingUp() {
        return standingUp;
    }

    /**
     * Calculate latest movement state. Gets called once a tick.
     *
     * @param state The current state
     * @return The new state
     */
    public MovementState updateState(MovementState state) {
        preparedHold = null;
        if (!prepared(state)) {
            // (the subclasses with their own prepared don't all say, the move= next to it names the class)
            if (preparedHold == null) preparedHold = "own";
            return state.setStatus(MovementStatus.PREPPING);
        } else if (state.getStatus() == MovementStatus.PREPPING) {
            state.setStatus(MovementStatus.WAITING);
        }

        if (state.getStatus() == MovementStatus.WAITING) {
            state.setStatus(MovementStatus.RUNNING);
        }

        return state;
    }

    @Override
    public BlockPos getDirection() {
        return getDest().subtract(getSrc());
    }

    public void checkLoadedChunk(CalculationContext context) {
        calculatedWhileLoaded = context.bsi.worldContainsLoadedChunk(dest.x, dest.z);
    }

    @Override
    public boolean calculatedWhileLoaded() {
        return calculatedWhileLoaded;
    }

    @Override
    public void resetBlockCache() {
        toBreakCached = null;
        toPlaceCached = null;
        toWalkIntoCached = null;
    }

    public List<BlockPos> toBreak(BlockStateInterface bsi) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (BetterBlockPos positionToBreak : positionsToBreak) {
            if (!MovementHelper.canWalkThrough(bsi, positionToBreak.x, positionToBreak.y, positionToBreak.z)) {
                result.add(positionToBreak);
            }
        }
        toBreakCached = result;
        return result;
    }

    public List<BlockPos> toPlace(BlockStateInterface bsi) {
        if (toPlaceCached != null) {
            return toPlaceCached;
        }
        List<BlockPos> result = new ArrayList<>();
        if (positionToPlace != null && !MovementHelper.canWalkOn(bsi, positionToPlace.x, positionToPlace.y, positionToPlace.z)) {
            result.add(positionToPlace);
        }
        toPlaceCached = result;
        return result;
    }

    public List<BlockPos> toWalkInto(BlockStateInterface bsi) { // overridden by movementdiagonal
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        return toWalkIntoCached;
    }

    // null for the movements that never place
    public BetterBlockPos getPositionToPlace() {
        return positionToPlace;
    }

    public BlockPos[] toBreakAll() {
        return positionsToBreak;
    }
}
