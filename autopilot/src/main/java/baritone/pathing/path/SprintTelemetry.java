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

package baritone.pathing.path;

import baritone.Baritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.behavior.LookBehavior;
import baritone.pathing.sim.AscendGuard;
import baritone.pathing.sim.AscendHop;
import baritone.pathing.sim.LiveSim;
import baritone.pathing.sim.PlayerSim;
import baritone.pathing.sim.WorldCollision;
import baritone.utils.ExperimentalMovement;
import baritone.utils.InputOverrideHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

// two questions about sprint, answered with lines of text (sprintTelemetry): when did it drop and who did it, and does PlayerSim
// predict what the game really did. all of it is read-only and none of it runs with the setting off.
//
// the order inside one game tick, which is what decides what "before" and "after" mean here:
//   Minecraft.tick, early (MixinMinecraft.runTick) -> behaviors' onTick in registration order, PathingBehavior's among them:
//   begin() -> the executor sets keys, sprint flag and look target -> end() -> LocalPlayer.aiStep (input.tick reads those keys,
//   vanilla starts or stops the sprint, jump, travel) -> end of tick -> next game tick -> begin()
// so begin() of tick N+1 sees the player as aiStep N left it plus whatever other behaviors that tick ran before us (altoclef's
// listener goes first), and end() of tick N sees the state aiStep N starts from together with the inputs it will be given, unless
// a behavior registered after PathingBehavior changes keys later in the same event. the drift check takes the snapshot in end(),
// replays aiStep N on it with the yaw moveRelative really used (LookBehavior.lastMoveYaw), and compares in begin()
public final class SprintTelemetry implements Helper {

    private static final int REPORT_EVERY = 1000;
    // a systematically wrong sim, or a flag that flips every other tick, would otherwise write a line per tick
    private static final int LINES_PER_REPORT = 25;
    private static final double DRIFT = 0.01;
    // a wall drop this soon after a hop took off is the hop clipping the step
    private static final int CLIP_WINDOW = 20;
    private static final String[] BUCKETS = {"1", "2", "3-5", "6-10", "11-20", "21+"};
    private static final String PAST_THE_END = "past the end";

    private final Baritone baritone;
    private final IPlayerContext ctx;

    private int pathTicks, sprintTicks, drops, totalDrops, totalPathTicks, endDrops;
    private int checks, mismatches, driftLogged, dropsLogged, unsupported;

    // begin() of the tick that just ended: the sprint flag and the collision flags aiStep of the next one decides from
    private int lastTick = -1;
    private boolean lastSprint, lastHc, lastMinor;

    // end() of the tick that just ended: what the executor wanted and did
    private boolean lastPathing, lastWant, lastJump, lastFinished;
    private int lastIndex, lastLength;
    private String lastMovement = "", lastWhy = "", lastReleased = "", lastSteering = "none";
    private double lastShortcutDist = Double.NaN;
    private int lastShortcutEnd = -1;
    private Vec3 lastShortcutTarget, lastShortcutStart, lastPos = Vec3.ZERO, lastVel = Vec3.ZERO;
    private List<? extends IMovement> lastMovements;

    // the ascend hop's side of the story, told by whoever asked the sim and whoever pressed space
    private AscendHop.Verdict lastVerdict;
    private int lastVerdictTick = -1000;
    private String lastBranch = "";
    private AscendHop.Verdict lastBranchVerdict;
    private int lastTakeoffTick = -1000, takeoffs, clipped, guardedForward, guardedStill;
    // brake plans picked and ticks spent braking since the last report, and the last tick a brake was counted on (two executors can tick in one)
    private int brakePlans, brakeTicks, lastBrakeTick = -1000;

    // the drop sprint has not come back from yet, and for how long the ones that did come back were gone
    private int openDropTick = -1;
    private String openDropCause = "", openDropMovement = "";
    private boolean openDropLogged, openDropCarried;
    private final int[] resumeHist = new int[BUCKETS.length];
    private int stillOff;
    // {drops, how many came back, ticks they were gone for}
    private final Map<String, int[]> byCause = new TreeMap<>(), byMovement = new TreeMap<>();

    // ground shortcuts that stood still or gave up, since the last report: W held back by a check (and which), then how each one
    // ended. row[0] is the count
    public enum ShortcutEnd {HANDOFF, RESUMED, REPLAN}

    private int shortcutHolds, shortcutLines;
    private final int[] shortcutEnds = new int[ShortcutEnd.values().length];
    private final Map<String, int[]> shortcutHoldsBy = new TreeMap<>(), shortcutEndsBy = new TreeMap<>();

    // the replay. the sim stands where the player did when the keys were set, the rest is the keys and the yaw they were set with
    private PlayerSim pending;
    private int pendingForward, pendingLeft, pendingMoveCount;
    private boolean pendingJump, pendingSprint;
    private float pendingViewYaw;

    public SprintTelemetry(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    // setting is off (or there is no player): forget everything so turning it on later does not compare against a tick from a
    // minute ago, and say what the walk so far came to
    public void off() {
        lastTick = -1;
        lastPathing = false;
        pending = null;
        // tickCount starts over in a new world, and "N ticks ago" from before that would be nonsense
        lastTakeoffTick = -1000;
        lastVerdictTick = -1000;
        if (pathTicks > 0) {
            report(true);
        }
    }

    /**
     * First thing in a game tick, before anything of ours has touched the keys or the sprint flag
     */
    public void begin(boolean pathing) {
        LocalPlayer player = ctx.player();
        if (player == null) {
            off();
            return;
        }
        int tick = player.tickCount;
        if (openDropTick >= 0 && player.isSprinting()) {
            resumed(tick);
        }
        if (lastTick >= 0 && tick == lastTick + 1 && lastPathing) {
            replay(player);
            if (lastSprint && !player.isSprinting()) {
                dropped(player, tick);
            }
        } else {
            pending = null;
        }
        if (pathing) {
            pathTicks++;
            if (player.isSprinting()) {
                sprintTicks++;
            }
            if (pathTicks >= REPORT_EVERY) {
                report(false);
            }
        } else if (pathTicks > 0) {
            report(true); // the path ended, was cancelled or stopped since last tick
        }
        lastTick = tick;
        lastSprint = player.isSprinting();
        lastHc = player.horizontalCollision;
        lastMinor = player.minorHorizontalCollision;
    }

    /**
     * After the executor and everything else of ours have had their say for this tick. The executor is null when there is no path
     */
    public void end(PathExecutor executor, boolean paused) {
        LocalPlayer player = ctx.player();
        pending = null;
        lastPathing = executor != null;
        if (player == null || executor == null) {
            return;
        }
        InputOverrideHandler keys = baritone.getInputOverrideHandler();
        List<? extends IMovement> movements = executor.getPath().movements();
        lastMovements = movements;
        lastIndex = executor.getPosition();
        lastLength = movements.size();
        lastMovement = lastIndex >= 0 && lastIndex < lastLength ? movements.get(lastIndex).getClass().getSimpleName() : PAST_THE_END;
        // a cancel also puts the position past the end, only a path that was walked to its last node is "the end"
        lastFinished = executor.finished() && !executor.failed();
        lastWant = executor.isSprinting();
        lastWhy = executor.sprintWhy();
        lastReleased = paused ? "behavior pause" : executor.releasedWhy();
        lastSteering = executor.steering();
        lastShortcutDist = executor.shortcutDistance();
        lastShortcutTarget = executor.shortcutTarget();
        lastShortcutEnd = executor.shortcutEndIndex();
        lastShortcutStart = executor.shortcutStart();
        lastPos = player.position();
        lastVel = player.getDeltaMovement();
        lastJump = keys.isInputForcedDown(Input.JUMP);
        if (paused || baritone.bsi == null || keys.isInputForcedDown(Input.SNEAK)) {
            return; // nothing of ours is moving the player, or it is a kind of moving the sim does not do
        }
        PlayerSim sim = LiveSim.of(player, new WorldCollision(baritone.bsi));
        if (sim == null) {
            unsupported++;
            return;
        }
        pending = sim;
        pendingForward = (keys.isInputForcedDown(Input.MOVE_FORWARD) ? 1 : 0) - (keys.isInputForcedDown(Input.MOVE_BACK) ? 1 : 0);
        pendingLeft = (keys.isInputForcedDown(Input.MOVE_LEFT) ? 1 : 0) - (keys.isInputForcedDown(Input.MOVE_RIGHT) ? 1 : 0);
        pendingJump = lastJump;
        pendingSprint = lastWant; // PathingBehavior.onPlayerSprintState hands this to vanilla as the sprint key
        pendingViewYaw = player.getYRot();
        pendingMoveCount = baritone.getLookBehavior().moveCount();
    }

    // what the sim said about a hop up a step, asked from the traverse before it or from the ascend itself
    public void verdict(AscendHop.Verdict verdict) {
        LocalPlayer player = ctx.player();
        if (player != null && ExperimentalMovement.sprintTelemetry()) {
            lastVerdict = verdict;
            lastVerdictTick = player.tickCount;
        }
    }

    // the same, with the brake in it: counts the brake ticks, and a line for every plan the sim picks
    public void verdict(AscendHop.Decision decision, BetterBlockPos src, BetterBlockPos dest) {
        LocalPlayer player = ctx.player();
        verdict(decision.verdict());
        if (player == null || !ExperimentalMovement.sprintTelemetry()) {
            return;
        }
        if (decision.verdict() == AscendHop.Verdict.BRAKE && player.tickCount != lastBrakeTick) {
            lastBrakeTick = player.tickCount;
            brakeTicks++;
        }
        AscendHop.BrakePlan plan = decision.fresh();
        if (plan != null) {
            brakePlans++;
            Vec3 pos = player.position(), vel = player.getDeltaMovement();
            emit(String.format(Locale.ROOT, "brake plan tick=%d (%d,%d,%d)->(%d,%d,%d) brake=%d push=%d yaw=%.1f, over the step in %d ticks, jumping now would take %d%s"
                            + " feet=(%.3f,%.3f) vel=(%.3f,%.3f) hc=%s minor=%s",
                    player.tickCount, src.x, src.y, src.z, dest.x, dest.y, dest.z, plan.brakeTicks, plan.pushTicks, plan.yaw, plan.finish,
                    plan.baselineFinish, plan.baselineClean ? "" : " and clips", pos.x, pos.z, vel.x, vel.z, player.horizontalCollision, player.minorHorizontalCollision));
        }
    }

    // space goes down for a hop up a step: which rule pressed it, what the sim had said (null when it was not asked) and where we stood
    public void takeoff(String branch, AscendHop.Verdict verdict, BetterBlockPos src, BetterBlockPos dest) {
        LocalPlayer player = ctx.player();
        // space stays down for a couple of ticks in the air, only the one on the ground is a takeoff
        if (player == null || !ExperimentalMovement.sprintTelemetry() || !player.onGround() || player.tickCount == lastTakeoffTick) {
            return;
        }
        lastTakeoffTick = player.tickCount;
        lastBranch = branch;
        lastBranchVerdict = verdict;
        takeoffs++;
        int dx = Integer.signum(dest.x - src.x), dz = Integer.signum(dest.z - src.z);
        String where = "face=diagonal";
        if ((dx != 0) != (dz != 0)) {
            Vec3 pos = player.position(), vel = player.getDeltaMovement();
            double toX = dest.x + 0.5 - pos.x, toZ = dest.z + 0.5 - pos.z;
            where = String.format(Locale.ROOT, "face=%.2f side=%.3f v=%.3f", toX * dx + toZ * dz - 0.5 - player.getBbWidth() / 2.0,
                    Math.abs(toX * dz) + Math.abs(toZ * dx), vel.x * dx + vel.z * dz);
        }
        emit(String.format(Locale.ROOT, "takeoff tick=%d (%d,%d,%d)->(%d,%d,%d) via=%s sim=%s sprinting=%s %s",
                player.tickCount, src.x, src.y, src.z, dest.x, dest.y, dest.z, branch, verdict == null ? "not asked" : verdict, player.isSprinting(), where));
    }

    // AscendGuard took keys away from a hop this tick. if that was space the takeoff logged this very tick never happened
    public void guarded(AscendGuard.Move move, BetterBlockPos src, BetterBlockPos dest) {
        LocalPlayer player = ctx.player();
        if (player == null || !ExperimentalMovement.sprintTelemetry()) {
            return;
        }
        if (move == AscendGuard.Move.NO_FORWARD) {
            guardedForward++;
            return;
        }
        guardedStill++;
        if (lastTakeoffTick == player.tickCount) {
            lastTakeoffTick = -1000;
            takeoffs--;
            emit("takeoff withdrawn, the ascend guard stood still (" + src.x + "," + src.y + "," + src.z + ")->(" + dest.x + "," + dest.y + "," + dest.z + ")");
        }
    }

    // a ground shortcut let go of W because a check said the way ahead is blocked (called on the tick that starts the hold, not
    // every tick of it). `where` is the cell and what is in it, speed is blocks a tick
    public void shortcutHold(String check, String where, double speed, double distToTarget) {
        if (!ExperimentalMovement.sprintTelemetry()) {
            return;
        }
        shortcutHolds++;
        count(shortcutHoldsBy, check);
        shortcutLine(String.format(Locale.ROOT, "shortcut holding W back: %s says no at %s, speed=%.3f toTarget=%.2f", check, where, speed, distToTarget));
    }

    // a ground shortcut is over without arriving: an early handoff to its end node, a stop that carried on with the movements it
    // skipped, or one that had to replan. `detail` says where the feet were
    public void shortcutEnded(ShortcutEnd how, String reason, String detail) {
        if (!ExperimentalMovement.sprintTelemetry()) {
            return;
        }
        shortcutEnds[how.ordinal()]++;
        // the numbers in a reason ("0.63 blocks off its line") would make a row each
        count(shortcutEndsBy, how.name().toLowerCase(Locale.ROOT) + ": " + reason.replaceAll("\\d+\\.\\d+", "N"));
        shortcutLine("shortcut " + how.name().toLowerCase(Locale.ROOT) + " (" + reason + ") " + detail);
    }

    private void shortcutLine(String line) {
        if (shortcutLines++ < LINES_PER_REPORT) {
            emit(line + (shortcutLines == LINES_PER_REPORT ? " (no more shortcut lines until the next report)" : ""));
        }
    }

    // run aiStep of the tick that just finished on the snapshot and hold the result against the player
    private void replay(LocalPlayer player) {
        PlayerSim sim = pending;
        pending = null;
        if (sim == null) {
            return;
        }
        LookBehavior look = baritone.getLookBehavior();
        if (look.moveCount() != pendingMoveCount + 1) {
            return; // moveRelative did not run exactly once, so whatever happened was not an ordinary tick
        }
        PlayerSim.Controls controls = new PlayerSim.Controls(pendingForward, pendingLeft, pendingJump, pendingSprint, look.lastMoveYaw(), pendingViewYaw);
        if (!sim.tick(controls)) {
            unsupported++;
            return;
        }
        checks++;
        double ex = player.getX() - sim.x, ey = player.getY() - sim.y, ez = player.getZ() - sim.z;
        double err = Math.sqrt(ex * ex + ey * ey + ez * ez);
        boolean flags = sim.onGround != player.onGround() || sim.sprinting != player.isSprinting()
                || sim.horizontalCollision != player.horizontalCollision || sim.minorHorizontalCollision != player.minorHorizontalCollision;
        if (err <= DRIFT && !flags) {
            return;
        }
        mismatches++;
        if (driftLogged++ < LINES_PER_REPORT) {
            emit(String.format(Locale.ROOT, "drift tick=%d %s err=%.4f (%.4f,%.4f,%.4f) predicted ground=%s sprint=%s hc=%s minor=%s, actual ground=%s sprint=%s hc=%s minor=%s"
                            + " keys fwd=%d left=%d jump=%s sprintKey=%s moveYaw=%.2f viewYaw=%.2f vel=(%.4f,%.4f,%.4f)",
                    player.tickCount - 1, lastMovement, err, ex, ey, ez,
                    sim.onGround, sim.sprinting, sim.horizontalCollision, sim.minorHorizontalCollision,
                    player.onGround(), player.isSprinting(), player.horizontalCollision, player.minorHorizontalCollision,
                    pendingForward, pendingLeft, pendingJump, pendingSprint, look.lastMoveYaw(), pendingViewYaw,
                    player.getDeltaMovement().x, player.getDeltaMovement().y, player.getDeltaMovement().z));
        }
    }

    // sprint was on at the start of the last tick and is off now. the one at the end of a path is expected (nobody sprints past
    // the goal), it gets its own count and its own line and stays out of the real drops
    private void dropped(LocalPlayer player, int tick) {
        String cause = causeOf(player);
        if (PAST_THE_END.equals(lastMovement) && lastFinished) {
            endDrops++;
            emit("end of path, " + describeDrop(player, cause));
            return;
        }
        drops++;
        count(byCause, cause);
        count(byMovement, lastMovement);
        if (cause.equals("horizontal collision") && tick - lastTakeoffTick <= CLIP_WINDOW) {
            clipped++;
        }
        openDropTick = tick;
        openDropCause = cause;
        openDropMovement = lastMovement;
        openDropCarried = false;
        openDropLogged = dropsLogged++ < LINES_PER_REPORT;
        if (openDropLogged) {
            emit(describeDrop(player, cause));
        }
    }

    // sprint is back after being off since openDropTick
    private void resumed(int tick) {
        int gone = tick - openDropTick;
        if (!openDropCarried) { // a drop from before the last report was counted there, its numbers are gone
            int bucket = gone <= 2 ? Math.max(gone, 1) - 1 : gone <= 5 ? 2 : gone <= 10 ? 3 : gone <= 20 ? 4 : 5;
            resumeHist[bucket]++;
            tally(byCause, openDropCause, gone);
            tally(byMovement, openDropMovement, gone);
        }
        if (openDropLogged) {
            emit(String.format(Locale.ROOT, "sprint back after %d ticks (drop at tick %d, %s)", gone, openDropTick - 1, openDropCause));
        }
        openDropTick = -1;
    }

    private static void count(Map<String, int[]> into, String key) {
        into.computeIfAbsent(key, k -> new int[3])[0]++;
    }

    private static void tally(Map<String, int[]> into, String key, int gone) {
        int[] row = into.computeIfAbsent(key, k -> new int[3]);
        row[1]++;
        row[2] += gone;
    }

    // the player was sprinting at the start of the last tick and is not now: the executor let go, or aiStep dropped it
    private String causeOf(LocalPlayer player) {
        float forward = player.input.getMoveVector().y;
        if (!lastReleased.isEmpty()) {
            return "keys released (" + lastReleased + ")";
        } else if (!lastWant) {
            return "executor said no (" + (lastWhy.isEmpty() ? "no reason given" : lastWhy) + ")";
        } else if (!(forward > 1.0E-5F)) {
            return "no forward impulse";
        } else if (lastHc && !lastMinor) {
            return "horizontal collision";
        } else if (player.getFoodData().getFoodLevel() <= 6) {
            return "food";
        } else if (player.hasEffect(MobEffects.BLINDNESS)) {
            return "blindness";
        }
        return "something else in aiStep";
    }

    private String describeDrop(LocalPlayer player, String cause) {
        Vec3 pos = player.position();
        BlockPos cell = player.blockPosition();
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT,
                "drop tick=%d path=%d/%d %s cause=%s feet=(%.3f,%.3f,%.3f) cell+off=(%d,%d,%d)+(%.3f,%.3f)"
                        + " ground=%s hc=%s minor=%s prevHc=%s prevMinor=%s forward=%.2f food=%d wantSprint=%s why=\"%s\" released=\"%s\" jump=%s"
                        + " prev=%s cur=%s next=%s steer=%s",
                player.tickCount - 1, lastIndex, lastLength, lastMovement, cause, pos.x, pos.y, pos.z,
                cell.getX(), cell.getY(), cell.getZ(), pos.x - cell.getX(), pos.z - cell.getZ(),
                player.onGround(), player.horizontalCollision, player.minorHorizontalCollision, lastHc, lastMinor,
                player.input.getMoveVector().y, player.getFoodData().getFoodLevel(),
                lastWant, lastWhy, lastReleased, lastJump,
                moveName(lastIndex - 1), moveName(lastIndex), moveName(lastIndex + 1), lastSteering));
        if (!Double.isNaN(lastShortcutDist) && lastShortcutTarget != null) {
            line.append(String.format(Locale.ROOT, " shortcutDist=%.2f shortcutTarget=(%.2f,%.2f)", lastShortcutDist, lastShortcutTarget.x, lastShortcutTarget.z));
            line.append(shortcutEndNote());
        }
        if (player.horizontalCollision || lastHc) {
            // where the tick started and how fast it was going, so the swing into the wall can be replayed by hand
            line.append(String.format(Locale.ROOT, " from=(%.3f,%.3f) vel=(%.3f,%.3f) collided=%s", lastPos.x, lastPos.z, lastVel.x, lastVel.z, collidedWith(player)));
        }
        if (player.tickCount - lastTakeoffTick <= 40) {
            line.append(String.format(Locale.ROOT, " lastTakeoff=%dt ago via=%s sim=%s", player.tickCount - 1 - lastTakeoffTick, lastBranch,
                    lastBranchVerdict == null ? "not asked" : lastBranchVerdict));
        }
        if (lastVerdict != null && player.tickCount - lastVerdictTick <= 40) {
            line.append(String.format(Locale.ROOT, " lastSimVerdict=%s %dt ago", lastVerdict, player.tickCount - 1 - lastVerdictTick));
        }
        return line.toString();
    }

    // what the shortcut is walking into and how sharp the bend there is: the movement that starts at its end node, against the
    // shortcut's own line (the player is a few tenths from the node when it brakes, and from there the heading to it is noise). only built when a drop line is written, which is the one place it is looked at
    private String shortcutEndNote() {
        if (lastShortcutEnd < 0 || lastMovements == null || lastShortcutEnd >= lastMovements.size()) {
            return " shortcutEnd=" + moveName(lastShortcutEnd);
        }
        BlockPos out = lastMovements.get(lastShortcutEnd).getDirection();
        Vec3 from = lastShortcutStart == null ? lastPos : lastShortcutStart;
        double ax = lastShortcutTarget.x - from.x, az = lastShortcutTarget.z - from.z;
        double la = Math.hypot(ax, az), lb = Math.hypot(out.getX(), out.getZ());
        if (la < 1.0E-6 || lb == 0) {
            return " shortcutEnd=" + moveName(lastShortcutEnd) + " endTurn=n/a";
        }
        double cos = (ax * out.getX() + az * out.getZ()) / (la * lb);
        return String.format(Locale.ROOT, " shortcutEnd=%s endTurn=%.0f", moveName(lastShortcutEnd), Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos)))));
    }

    private String moveName(int index) {
        if (lastMovements == null || index < 0 || index >= lastMovements.size()) {
            return "-";
        }
        IMovement m = lastMovements.get(index);
        BlockPos d = m.getDirection();
        return m.getClass().getSimpleName().replace("Movement", "") + "(" + d.getX() + "," + d.getY() + "," + d.getZ() + ")";
    }

    // every block whose collision shape touches the box widened by a hair, which at a wall is the wall. relative to the feet cell
    private String collidedWith(LocalPlayer player) {
        Level world = ctx.world();
        AABB box = player.getBoundingBox();
        double hair = 0.02;
        // the floor starts at minY, so lift the bottom a hair or the block we stand on is in every list
        double minX = box.minX - hair, maxX = box.maxX + hair, minY = box.minY + 0.01, maxY = box.maxY, minZ = box.minZ - hair, maxZ = box.maxZ + hair;
        BlockPos feet = player.blockPosition();
        StringBuilder out = new StringBuilder("[");
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX); x++) {
            for (int y = (int) Math.floor(minY); y <= (int) Math.floor(maxY); y++) {
                for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ); z++) {
                    BlockState state = world.getBlockState(pos.set(x, y, z));
                    if (state.isAir() || state.getCollisionShape(world, pos).isEmpty()) {
                        continue;
                    }
                    final int ox = x, oy = y, oz = z;
                    boolean[] touches = {false};
                    state.getCollisionShape(world, pos).forAllBoxes((x1, y1, z1, x2, y2, z2) ->
                            touches[0] |= x2 + ox > minX && x1 + ox < maxX && y2 + oy > minY && y1 + oy < maxY && z2 + oz > minZ && z1 + oz < maxZ);
                    if (touches[0]) {
                        String full = state.toString();
                        int props = full.indexOf('[');
                        out.append(out.length() > 1 ? " " : "").append(String.format(Locale.ROOT, "(%+d,%+d,%+d)%s%s", x - feet.getX(), y - feet.getY(), z - feet.getZ(),
                                BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), props >= 0 ? full.substring(props) : ""));
                    }
                }
            }
        }
        return out.append("]").toString();
    }

    // ending: the path is over, so a drop sprint has not come back from is not going to. the every-1000-ticks report keeps it open
    private void report(boolean ending) {
        totalDrops += drops;
        totalPathTicks += pathTicks;
        if (!ending && openDropTick >= 0) {
            openDropCarried = true;
        }
        if (ending && openDropTick >= 0) {
            stillOff++; // the path ended before sprint came back
            openDropTick = -1;
        }
        emit(String.format(Locale.ROOT, "%d sprint drops (and %d at the end of a path) in %d path ticks (%d of them sprinting), %.2f per 1000 path ticks, %.2f per 1000 sprinting ticks, %d over %d path ticks so far. sim: %d ticks checked, %d off, %d unsupported%s%s",
                drops, endDrops, pathTicks, sprintTicks, drops * 1000.0 / pathTicks, drops * 1000.0 / Math.max(1, sprintTicks), totalDrops, totalPathTicks,
                checks, mismatches, unsupported,
                driftLogged > LINES_PER_REPORT ? " (" + (driftLogged - LINES_PER_REPORT) + " drift lines not shown)" : "",
                dropsLogged > LINES_PER_REPORT ? " (" + (dropsLogged - LINES_PER_REPORT) + " drop lines not shown)" : ""));
        if (drops > 0) {
            StringBuilder hist = new StringBuilder("sprint was off for (ticks):");
            for (int i = 0; i < BUCKETS.length; i++) {
                hist.append(' ').append(BUCKETS[i]).append('=').append(resumeHist[i]);
            }
            emit(hist + ", not back before the path ended: " + stillOff + ". by cause: " + table(byCause) + ". by movement: " + table(byMovement));
        }
        if (takeoffs > 0 || guardedForward + guardedStill > 0) {
            emit(takeoffs + " ascend takeoffs, " + clipped + " of them clipped the step (a wall drop within " + CLIP_WINDOW + " ticks). ascend guard took W away on "
                    + guardedForward + " ticks and W and space on " + guardedStill);
        }
        if (brakePlans > 0 || brakeTicks > 0) {
            emit(brakePlans + " brake plans picked, " + brakeTicks + " ticks of braking");
        }
        if (shortcutHolds > 0 || shortcutEnds[0] + shortcutEnds[1] + shortcutEnds[2] > 0) {
            emit(String.format(Locale.ROOT, "ground shortcuts: %d holds of W (%s), %d early handoffs, %d abandons that carried on, %d abandons that replanned. by reason: %s",
                    shortcutHolds, table(shortcutHoldsBy), shortcutEnds[ShortcutEnd.HANDOFF.ordinal()], shortcutEnds[ShortcutEnd.RESUMED.ordinal()],
                    shortcutEnds[ShortcutEnd.REPLAN.ordinal()], table(shortcutEndsBy)));
        }
        if (AscendHop.decides > 0) {
            emit(String.format(Locale.ROOT, "ascend sim: %d asks, %.1f sim ticks per ask on average, %d at worst", AscendHop.decides,
                    AscendHop.simTicks / (double) AscendHop.decides, AscendHop.worstDecideTicks));
            AscendHop.decides = 0;
            AscendHop.simTicks = 0;
            AscendHop.worstDecideTicks = 0;
        }
        pathTicks = 0;
        sprintTicks = 0;
        drops = 0;
        endDrops = 0;
        dropsLogged = 0;
        checks = 0;
        mismatches = 0;
        driftLogged = 0;
        unsupported = 0;
        takeoffs = 0;
        clipped = 0;
        guardedForward = 0;
        guardedStill = 0;
        brakePlans = 0;
        brakeTicks = 0;
        stillOff = 0;
        shortcutHolds = 0;
        shortcutLines = 0;
        Arrays.fill(shortcutEnds, 0);
        shortcutHoldsBy.clear();
        shortcutEndsBy.clear();
        Arrays.fill(resumeHist, 0);
        byCause.clear();
        byMovement.clear();
    }

    // "name count (avg n ticks gone)" for each row
    private static String table(Map<String, int[]> rows) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, int[]> row : rows.entrySet()) {
            int[] v = row.getValue();
            if (v[0] == 0) {
                continue; // came back after the report that counted it
            }
            out.append(out.length() > 0 ? ", " : "").append(row.getKey()).append(' ').append(v[0]);
            if (v[1] > 0) {
                out.append(String.format(Locale.ROOT, " (avg %.1f)", v[2] / (double) v[1]));
            }
        }
        return out.toString();
    }

    // chat when chatDebug is on, and the game log either way so a whole walk can be grepped afterwards
    private void emit(String line) {
        String text = "[sprintTelemetry] " + line;
        logDebug(text);
        System.out.println(text);
    }
}
