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

package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.event.events.RenderEvent;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.calc.IPathFinder;
import baritone.api.pathing.goals.*;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.CurvedMovement;
import baritone.pathing.path.BoatTrip;
import baritone.pathing.path.PathExecutor;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * @author Brady
 * @since 8/9/2018
 */
public final class PathRenderer implements IRenderer {

    private PathRenderer() {}

    private static final float GOAL_BEACON_INNER_RADIUS = 0.2F;
    private static final float GOAL_BEACON_GLOW_RADIUS = 0.25F;
    private static final int GOAL_BEACON_GLOW_ALPHA = 32;

    private static final float BOX_FILL_ALPHA = 0.13F;
    private static final float GOAL_FILL_ALPHA = 0.32F;
    private static final float OUTLINE_ALPHA = 0.7F;

    // it has to remember last frame to be smooth about this one, and every bot has its own search going
    private static final Map<PathingBehavior, SearchGlow> SEARCH_GLOW = new WeakHashMap<>();

    // 1 / tan(fov / 2), what the ribbon needs to turn pixels into blocks. 1.5 is a 67 degree fov, for the frame before the first event
    private static volatile float projectionM11 = 1.5F;

    // the event handler passes every render event through here, so that the ribbon doesn't depend on who is drawn first
    public static void setProjection(Matrix4f projection) {
        projectionM11 = projection.m11();
    }

    static float projectionM11() {
        return projectionM11;
    }

    public static double posX() {
        return renderManager.renderPosX();
    }

    public static double posY() {
        return renderManager.renderPosY();
    }

    public static double posZ() {
        return renderManager.renderPosZ();
    }

    public static void render(RenderEvent event, PathingBehavior behavior) {
        final IPlayerContext ctx = behavior.ctx;
        if (ctx.world() == null) {
            return;
        }
        if (ctx.minecraft().gui.screen() instanceof GuiClick) {
            ((GuiClick) ctx.minecraft().gui.screen()).onRender(event.getModelViewStack(), event.getProjectionMatrix());
        }

        final float partialTicks = event.getPartialTicks();
        final Goal goal = behavior.getGoal();

        final DimensionType thisPlayerDimension = ctx.world().dimensionType();
        final DimensionType currentRenderViewDimension = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().world().dimensionType();

        if (thisPlayerDimension != currentRenderViewDimension) {
            // this is a path for a bot in a different dimension, don't render it
            return;
        }

        if (goal != null && settings.renderGoal.value) {
            drawGoal(event.getModelViewStack(), ctx, goal, partialTicks, settings.colorGoalBox.value);
        }

        if (!settings.renderPath.value) {
            return;
        }

        PathExecutor current = behavior.getCurrent(); // this should prevent most race conditions?
        PathExecutor next = behavior.getNext(); // like, now it's not possible for current!=null to be true, then suddenly false because of another thread
        if (current != null && settings.renderSelectionBoxes.value) {
            drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toBreak(), settings.colorBlocksToBreak.value);
            drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toPlace(), settings.colorBlocksToPlace.value);
            drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toWalkInto(), settings.colorBlocksToWalkInto.value);
        }

        //drawManySelectionBoxes(player, Collections.singletonList(behavior.pathStart()), partialTicks, Color.WHITE);

        // Render the current path, if there is one
        double currentLength = 0;
        if (current != null && current.getPath() != null) {
            int renderBegin = Math.max(current.getPosition() - 3, 0);
            IPath path = current.getPath();
            drawPathWithBoats(event.getModelViewStack(), current, renderBegin, settings.colorCurrentPath.value, 0, true);
            currentLength = PathRibbon.length(path.positions(), movementsOf(path), 0, path.positions().size() - 1, true);
        }

        if (next != null && next.getPath() != null) {
            // next starts where current ends, so it picks up the shimmer right where current drops it
            drawPathWithBoats(event.getModelViewStack(), next, 0, settings.colorNextPath.value, currentLength, true);
        }

        // If there is a path calculation currently running, render the path calculation process
        // the classic one flickers like crazy, which is how you know it's thinking. some people like that
        if (settings.renderPathRibbon.value && settings.renderSearchSmooth.value) {
            // every frame, search or no search, because it has things to fade out after the search is over
            SEARCH_GLOW.computeIfAbsent(behavior, b -> new SearchGlow()).render(event.getModelViewStack(), behavior.getInProgress());
        } else {
            behavior.getInProgress().ifPresent(currentlyRunning -> drawSearchClassic(event.getModelViewStack(), ctx, currentlyRunning));
        }
    }

    private static void drawSearchClassic(PoseStack stack, IPlayerContext ctx, IPathFinder currentlyRunning) {
        currentlyRunning.bestPathSoFar().ifPresent(p -> {
            drawPath(stack, p, 0, settings.colorBestPathSoFar.value, 0, false);
        });

        currentlyRunning.pathToMostRecentNodeConsidered().ifPresent(mr -> {
            drawPath(stack, mr, 0, settings.colorMostRecentConsidered.value, 0, false);
            drawManySelectionBoxes(stack, ctx.player(), Collections.singletonList(mr.getDest()), settings.colorMostRecentConsidered.value);
        });
    }

    public static void drawPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0) {
        drawPath(stack, positions, startIndex, color, fadeOut, fadeStart0, fadeEnd0, 0.5D);
    }

    // the path in its own color, except the stretches that'll be rowed, which get colorBoatPath. drawn as a
    // handful of sublists rather than one overlay so the two colors never fight over the same line.
    // the fade indices shift with each piece so the fade still lands on the same nodes it would have
    private static void drawPathWithBoats(PoseStack stack, PathExecutor executor, int startIndex, Color color, double arcOffset, boolean animated) {
        IPath path = executor.getPath();
        List<BetterBlockPos> positions = path.positions();
        List<int[]> boatRuns = executor.boatRuns();
        if (boatRuns.isEmpty()) {
            drawPath(stack, path, startIndex, color, arcOffset, animated);
            return;
        }
        // the pieces keep the movements too, so a neo before the water still bends round its wall
        List<IMovement> movements = movementsOf(path);
        int at = startIndex;
        for (int[] run : boatRuns) {
            int runStart = run[0];
            int runEnd = run[1];
            if (runEnd <= at) {
                continue; // already rowed that one
            }
            if (runStart > at) {
                drawPiece(stack, positions.subList(0, runStart + 1), movements == null ? null : movements.subList(0, runStart), at, color, 10 + startIndex - at, 20 + startIndex - at, arcOffset, animated);
            }
            drawLane(stack, executor.boatLane(run), settings.colorBoatPath.value);
            at = runEnd;
        }
        if (at < positions.size() - 1) {
            drawPiece(stack, positions, movements, at, color, 10 + startIndex - at, 20 + startIndex - at, arcOffset, animated);
        }
    }

    private static void drawPiece(PoseStack stack, List<BetterBlockPos> positions, @Nullable List<IMovement> movements, int startIndex, Color color, int fadeStart, int fadeEnd, double arcOffset, boolean animated) {
        if (settings.renderPathRibbon.value) {
            PathRibbon.draw(stack, positions, movements, startIndex, color, settings.fadePath.value, fadeStart, fadeEnd, 0.5D, arcOffset, 1.0F, 1.0F, animated, true, true);
        } else {
            drawPathLines(stack, positions, movements, startIndex, color, settings.fadePath.value, fadeStart, fadeEnd, 0.5D);
        }
    }

    // a boat run is drawn as a lane, two lines either side of a rounded centerline. no fade on these
    private static void drawLane(PoseStack stack, List<Vec3> lane, Color color) {
        BufferBuilder bufferBuilder = IRenderer.startLines(color);
        for (int side = -1; side <= 1; side += 2) {
            Vec3 prev = null;
            for (int i = 0; i < lane.size(); i++) {
                // normal from the neighbours either side, not per segment, so the edge is one connected line
                // instead of a pile of little planks that gap on the outside of a turn
                Vec3 a = lane.get(Math.max(i - 1, 0));
                Vec3 b = lane.get(Math.min(i + 1, lane.size() - 1));
                double dx = b.x - a.x;
                double dz = b.z - a.z;
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len == 0) {
                    continue;
                }
                Vec3 c = lane.get(i);
                double x = c.x - dz / len * BoatTrip.LANE_HALF * side;
                double z = c.z + dx / len * BoatTrip.LANE_HALF * side;
                if (laneEdgeFolds(lane, i, x, z)) {
                    continue;
                }
                Vec3 cur = new Vec3(x, c.y, z);
                if (prev != null) {
                    // the lane points are already block centers, so only the y needs the half block lift
                    emitPathLine(bufferBuilder, stack, prev.x - 0.5, prev.y, prev.z - 0.5, cur.x - 0.5, cur.y, cur.z - 0.5, 0.5D);
                }
                prev = cur;
            }
        }
        IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
    }

    // on the inside of a turn tighter than the lane is wide the offset edge folds back over itself and
    // draws a little bow tie. any edge point that ended up closer to the centerline than it started is
    // part of the fold, skip it and the line just cuts the corner
    private static boolean laneEdgeFolds(List<Vec3> lane, int i, double x, double z) {
        double min = (BoatTrip.LANE_HALF - 0.1) * (BoatTrip.LANE_HALF - 0.1);
        // four lane points to a block, so this looks about five blocks either way. plenty for a 1.75 offset
        for (int j = Math.max(i - 20, 0); j < Math.min(i + 21, lane.size()); j++) {
            Vec3 o = lane.get(j);
            if ((o.x - x) * (o.x - x) + (o.z - z) * (o.z - z) < min) {
                return true;
            }
        }
        return false;
    }

    public static void drawPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0, double offset) {
        if (settings.renderPathRibbon.value) {
            // whoever is calling this from outside might not be drawing something that walks (elytra isn't), so no steps
            PathRibbon.draw(stack, positions, null, startIndex, color, fadeOut, fadeStart0, fadeEnd0, offset, 0, 1.0F, 1.0F, true, false, true);
        } else {
            drawPathLines(stack, positions, null, startIndex, color, fadeOut, fadeStart0, fadeEnd0, offset);
        }
    }

    // the path as the executor sees it. movements that know how they're shaped (neos swing round their wall) get drawn
    // that way instead of as a straight line, in both the ribbon and the plain lines
    private static void drawPath(PoseStack stack, IPath path, int startIndex, Color color, double arcOffset, boolean animated) {
        List<BetterBlockPos> positions = path.positions();
        List<IMovement> movements = movementsOf(path);
        if (settings.renderPathRibbon.value) {
            PathRibbon.draw(stack, positions, movements, startIndex, color, settings.fadePath.value, 10, 20, 0.5D, arcOffset, 1.0F, 1.0F, animated, true, true);
        } else {
            drawPathLines(stack, positions, movements, startIndex, color, settings.fadePath.value, 10, 20, 0.5D);
        }
    }

    // only a verified path has movements: the ones still being searched throw, and those stay straight
    @Nullable
    private static List<IMovement> movementsOf(IPath path) {
        try {
            List<IMovement> movements = path.movements();
            // movement i goes from positions i to i + 1, but only believe that if the path says so
            return movements.size() == path.positions().size() - 1 ? movements : null;
        } catch (IllegalStateException e) {
            return null; // not verified
        }
    }

    private static void drawPathLines(PoseStack stack, List<BetterBlockPos> positions, @Nullable List<IMovement> movements, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0, double offset) {
        BufferBuilder bufferBuilder = IRenderer.startLines(color);

        int fadeStart = fadeStart0 + startIndex;
        int fadeEnd = fadeEnd0 + startIndex;

        for (int i = startIndex, next; i < positions.size() - 1; i = next) {
            BetterBlockPos start = positions.get(i);
            BetterBlockPos end = positions.get(next = i + 1);

            int dirX = end.x - start.x;
            int dirY = end.y - start.y;
            int dirZ = end.z - start.z;

            // a curve is its own segment, and nothing gets merged into it either way
            Vec3[] curve = CurvedMovement.of(movements, i);
            while (curve == null && next + 1 < positions.size() && (!fadeOut || next + 1 < fadeStart) &&
                    !CurvedMovement.isCurved(movements, next) &&
                    (dirX == positions.get(next + 1).x - end.x &&
                            dirY == positions.get(next + 1).y - end.y &&
                            dirZ == positions.get(next + 1).z - end.z)) {
                end = positions.get(++next);
            }

            if (fadeOut) {
                float alpha;

                if (i <= fadeStart) {
                    alpha = 0.4F;
                } else {
                    if (i > fadeEnd) {
                        break;
                    }
                    alpha = 0.4F * (1.0F - (float) (i - fadeStart) / (float) (fadeEnd - fadeStart));
                }
                IRenderer.glColor(color, alpha);
            }

            if (curve != null) {
                // same line as everywhere else, just bent
                for (int k = 0; k < curve.length - 1; k++) {
                    emitPathLine(bufferBuilder, stack, curve[k].x, curve[k].y, curve[k].z, curve[k + 1].x, curve[k + 1].y, curve[k + 1].z, offset);
                }
            } else {
                emitPathLine(bufferBuilder, stack, start.x, start.y, start.z, end.x, end.y, end.z, offset);
            }
        }

        IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
    }

    private static void emitPathLine(BufferBuilder bufferBuilder, PoseStack stack, double x1, double y1, double z1, double x2, double y2, double z2, double offset) {
        final double extraOffset = offset + 0.03D;

        double vpX = posX();
        double vpY = posY();
        double vpZ = posZ();
        boolean renderPathAsFrickinThingy = !settings.renderPathAsLine.value;

        IRenderer.emitLine(bufferBuilder, stack,
                x1 + offset - vpX, y1 + offset - vpY, z1 + offset - vpZ,
                x2 + offset - vpX, y2 + offset - vpY, z2 + offset - vpZ,
                settings.pathRenderLineWidthPixels.value
        );
        if (renderPathAsFrickinThingy) {
            IRenderer.emitLine(bufferBuilder, stack,
                    x2 + offset - vpX, y2 + offset - vpY, z2 + offset - vpZ,
                    x2 + offset - vpX, y2 + extraOffset - vpY, z2 + offset - vpZ,
                    settings.pathRenderLineWidthPixels.value
            );
            IRenderer.emitLine(bufferBuilder, stack,
                    x2 + offset - vpX, y2 + extraOffset - vpY, z2 + offset - vpZ,
                    x1 + offset - vpX, y1 + extraOffset - vpY, z1 + offset - vpZ,
                    settings.pathRenderLineWidthPixels.value
            );
            IRenderer.emitLine(bufferBuilder, stack,
                    x1 + offset - vpX, y1 + extraOffset - vpY, z1 + offset - vpZ,
                    x1 + offset - vpX, y1 + offset - vpY, z1 + offset - vpZ,
                    settings.pathRenderLineWidthPixels.value
            );
        }
    }

    public static void drawManySelectionBoxes(PoseStack stack, Entity player, Collection<BlockPos> positions, Color color) {
        if (positions.isEmpty()) {
            return;
        }
        boolean ignoreDepth = settings.renderSelectionBoxesIgnoreDepth.value;

        //BlockPos blockpos = movingObjectPositionIn.getBlockPos();
        BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext()); // TODO this assumes same dimension between primary baritone and render view? is this safe?

        List<AABB> boxes = new ArrayList<>(positions.size());
        positions.forEach(pos -> {
            BlockState state = bsi.get0(pos);
            VoxelShape shape = state.getShape(player.level(), pos);
            AABB toDraw = shape.isEmpty() ? Shapes.block().bounds() : shape.bounds();
            boxes.add(toDraw.move(pos).inflate(.002D));
        });

        // two passes because there's only the one draw open at a time, quads and lines can't be going at once
        if (settings.renderBoxFill.value) {
            BufferBuilder quads = IRenderer.startQuads();
            IRenderer.glColor(color, BOX_FILL_ALPHA);
            boxes.forEach(box -> IRenderer.emitFilledAABB(quads, stack, box, BOX_FILL_ALPHA));
            IRenderer.endQuads(quads, ignoreDepth);
        }

        BufferBuilder bufferBuilder = IRenderer.startLines(color, outlineAlpha());
        float lineWidth = boxLineWidth();
        boxes.forEach(box -> IRenderer.emitAABB(bufferBuilder, stack, box, lineWidth));
        IRenderer.endLines(bufferBuilder, ignoreDepth);
    }

    // fat wireframes are most of why this looked like 2014. once there's a fill to carry the box the outline can calm down
    private static float boxLineWidth() {
        float width = settings.pathRenderLineWidthPixels.value;
        return settings.renderBoxFill.value ? width / 2 : width;
    }

    // without the fill it's the classic look, and the classic look has see through outlines
    private static float outlineAlpha() {
        return settings.renderBoxFill.value ? OUTLINE_ALPHA : 0.4F;
    }

    public static void drawGoal(PoseStack stack, IPlayerContext ctx, Goal goal, float partialTicks, Color color) {
        // figure out every box first and draw after. composites can nest and mix goal types,
        // and this way they all end up in one batch of fills and one batch of lines no matter what's in there
        List<GoalBox> boxes = new ArrayList<>();
        List<GoalBeacon> beacons = new ArrayList<>();
        collectGoalBoxes(boxes, beacons, ctx, goal, color);
        boolean ignoreDepth = settings.renderGoalIgnoreDepth.value;

        if (!boxes.isEmpty()) {
            if (settings.renderBoxFill.value) {
                BufferBuilder quads = IRenderer.startQuads();
                boxes.forEach(box -> box.fill(quads, stack));
                IRenderer.endQuads(quads, ignoreDepth);
            }

            BufferBuilder bufferBuilder = IRenderer.startLines(color, outlineAlpha());
            boxes.forEach(box -> box.outline(bufferBuilder, stack));
            IRenderer.endLines(bufferBuilder, ignoreDepth);
        }

        // the beams have their own draws, so they wait until the boxes are done
        for (GoalBeacon beacon : beacons) {
            drawGoalXZBeacon(stack, ctx, beacon.goal, beacon.minY, beacon.maxY, partialTicks, beacon.color);
        }
    }

    // -1 to 1 and back every two seconds. the rings ride it, and the fill breathes along with it
    private static double goalWave() {
        return Mth.cos((float) (((float) ((System.nanoTime() / 100000L) % 20000L)) / 20000F * Math.PI * 2));
    }

    private static void collectGoalBoxes(List<GoalBox> boxes, List<GoalBeacon> beacons, IPlayerContext ctx, Goal goal, Color color) {
        double renderPosX = posX();
        double renderPosY = posY();
        double renderPosZ = posZ();
        double minX, maxX;
        double minZ, maxZ;
        double minY, maxY;
        double y, y1, y2;
        float breath = 1;
        if (!settings.renderGoalAnimated.value) {
            // y = 1 causes rendering issues when the player is at the same y as the top of a block for some reason
            y = 0.999F;
        } else {
            y = goalWave();
            breath = 0.75F + 0.25F * (float) y;
        }
        if (goal instanceof IGoalRenderPos) {
            BlockPos goalPos = ((IGoalRenderPos) goal).getGoalPos();
            minX = goalPos.getX() + 0.002 - renderPosX;
            maxX = goalPos.getX() + 1 - 0.002 - renderPosX;
            minZ = goalPos.getZ() + 0.002 - renderPosZ;
            maxZ = goalPos.getZ() + 1 - 0.002 - renderPosZ;
            if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
                y /= 2;
            }
            y1 = 1 + y + goalPos.getY() - renderPosY;
            y2 = 1 - y + goalPos.getY() - renderPosY;
            minY = goalPos.getY() - renderPosY;
            maxY = minY + 2;
            if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
                y1 -= 0.5;
                y2 -= 0.5;
                maxY--;
            }
            // bright at the floor and gone by the top, like the block is glowing upwards
            boxes.add(new GoalBox(color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, GOAL_FILL_ALPHA * breath, 0));
        } else if (goal instanceof GoalXZ) {
            GoalXZ goalPos = (GoalXZ) goal;
            minY = ctx.world().getMinY();
            maxY = ctx.world().getMaxY();

            minX = goalPos.getX() + 0.002 - renderPosX;
            maxX = goalPos.getX() + 1 - 0.002 - renderPosX;
            minZ = goalPos.getZ() + 0.002 - renderPosZ;
            maxZ = goalPos.getZ() + 1 - 0.002 - renderPosZ;

            y1 = 0;
            y2 = 0;
            minY -= renderPosY;
            maxY -= renderPosY;
            // a gradient over 384 blocks is just a flat color with extra steps
            float alpha = GOAL_FILL_ALPHA / 2 * breath;
            boxes.add(new GoalBox(color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, alpha, alpha));
            beacons.add(new GoalBeacon((GoalXZ) goal, minY, maxY, color));
        } else if (goal instanceof GoalComposite) {
            for (Goal g : ((GoalComposite) goal).goals()) {
                collectGoalBoxes(boxes, beacons, ctx, g, color);
            }
        } else if (goal instanceof GoalInverted) {
            collectGoalBoxes(boxes, beacons, ctx, ((GoalInverted) goal).origin, settings.colorInvertedGoalBox.value);
        } else if (goal instanceof GoalYLevel) {
            GoalYLevel goalpos = (GoalYLevel) goal;
            minX = ctx.player().position().x - settings.yLevelBoxSize.value - renderPosX;
            minZ = ctx.player().position().z - settings.yLevelBoxSize.value - renderPosZ;
            maxX = ctx.player().position().x + settings.yLevelBoxSize.value - renderPosX;
            maxZ = ctx.player().position().z + settings.yLevelBoxSize.value - renderPosZ;
            minY = ((GoalYLevel) goal).level - renderPosY;
            maxY = minY + 2;
            y1 = 1 + y + goalpos.level - renderPosY;
            y2 = 1 - y + goalpos.level - renderPosY;
            // this one is thirty blocks wide and you're usually standing in it, so go easy
            boxes.add(new GoalBox(color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, GOAL_FILL_ALPHA / 3 * breath, 0));
        }
    }

    private static void renderHorizontalQuad(BufferBuilder bufferBuilder, PoseStack stack, double minX, double maxX, double minZ, double maxZ, double y, float lineWidth) {
        if (y != 0) {
            IRenderer.emitLine(bufferBuilder, stack, minX, y, minZ, maxX, y, minZ, 1.0, 0.0, 0.0, lineWidth);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, minZ, maxX, y, maxZ, 0.0, 0.0, 1.0, lineWidth);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, maxZ, minX, y, maxZ, -1.0, 0.0, 0.0, lineWidth);
            IRenderer.emitLine(bufferBuilder, stack, minX, y, maxZ, minX, y, minZ, 0.0, 0.0, -1.0, lineWidth);
        }
    }

    private static void drawGoalXZBeacon(PoseStack stack, IPlayerContext ctx, GoalXZ goal, double minY, double maxY, float partialTicks, Color color) {
        float time = settings.renderGoalAnimated.value ? (float) ctx.world().getGameTime() + partialTicks : 0.0F;
        int glowColor = (color.getRGB() & 0x00FFFFFF) | GOAL_BEACON_GLOW_ALPHA << 24;
        double height = maxY - minY;

        stack.pushPose();
        stack.translate(goal.getX() - posX(), minY - posY(), goal.getZ() - posZ());
        renderGoalXZBeaconLayer(stack, height, time, color.getRGB(), GOAL_BEACON_INNER_RADIUS, false);
        renderGoalXZBeaconLayer(stack, height, time, glowColor, GOAL_BEACON_GLOW_RADIUS, true);
        stack.popPose();
    }

    private static void renderGoalXZBeaconLayer(PoseStack stack, double height, float time, int color, float radius, boolean translucent) {
        RenderType renderType = IRenderer.beaconBeam(BeaconRenderer.BEAM_LOCATION, translucent, settings.renderGoalIgnoreDepth.value);
        BufferBuilder bufferBuilder = IRenderer.startBlockQuads(renderType);
        float scroll = Mth.frac(-time * 0.2F - Mth.floor(-time * 0.1F));

        stack.pushPose();
        stack.translate(0.5D, 0.0D, 0.5D);
        if (!translucent) {
            stack.pushPose();
            stack.rotateDegrees(Axis.YP, time * 2.25F - 45.0F);
        }

        float v0 = -1.0F + scroll;
        float v1 = (float) (translucent ? height + v0 : height * (0.5F / radius) + v0);
        PoseStack.Pose pose = stack.last();
        if (translucent) {
            emitBeaconShell(bufferBuilder, pose, color, 0.0F, (float) height, -radius, -radius, radius, -radius, -radius, radius, radius, radius, v0, v1);
        } else {
            emitBeaconShell(bufferBuilder, pose, color, 0.0F, (float) height, 0.0F, radius, radius, 0.0F, -radius, 0.0F, 0.0F, -radius, v0, v1);
        }

        if (!translucent) {
            stack.popPose();
        }
        stack.popPose();

        IRenderer.endBuffer(bufferBuilder, renderType);
    }

    private static void emitBeaconShell(BufferBuilder bufferBuilder, PoseStack.Pose pose, int color, float minY, float maxY,
                                        float x1, float z1, float x2, float z2, float x3, float z3, float x4, float z4,
                                        float v0, float v1) {
        emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x1, z1, x2, z2, 0.0F, 1.0F, v0, v1);
        emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x4, z4, x3, z3, 0.0F, 1.0F, v0, v1);
        emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x2, z2, x4, z4, 0.0F, 1.0F, v0, v1);
        emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x3, z3, x1, z1, 0.0F, 1.0F, v0, v1);
    }

    private static void emitBeaconFace(BufferBuilder bufferBuilder, PoseStack.Pose pose, int color, float minY, float maxY,
                                       float x1, float z1, float x2, float z2, float u0, float u1, float v0, float v1) {
        float nx = z2 - z1;
        float nz = x1 - x2;
        float length = Mth.sqrt(nx * nx + nz * nz);
        if (length != 0.0F) {
            nx /= length;
            nz /= length;
        }

        IRenderer.emitTexturedVertex(bufferBuilder, pose, x1, maxY, z1, color, u1, v0, nx, 0.0F, nz);
        IRenderer.emitTexturedVertex(bufferBuilder, pose, x1, minY, z1, color, u1, v1, nx, 0.0F, nz);
        IRenderer.emitTexturedVertex(bufferBuilder, pose, x2, minY, z2, color, u0, v1, nx, 0.0F, nz);
        IRenderer.emitTexturedVertex(bufferBuilder, pose, x2, maxY, z2, color, u0, v0, nx, 0.0F, nz);
    }

    private record GoalBeacon(GoalXZ goal, double minY, double maxY, Color color) {}

    // one dank lit goal box, camera relative, remembered for long enough to draw it twice
    // y1 and y2 are the two rings, zero means no ring
    private record GoalBox(Color color, double minX, double maxX, double minZ, double maxZ, double minY, double maxY, double y1, double y2, float bottomAlpha, float topAlpha) {

        private void fill(BufferBuilder quads, PoseStack stack) {
            IRenderer.glColor(color, bottomAlpha);
            IRenderer.emitWalls(quads, stack, minX, minY, minZ, maxX, maxY, maxZ, bottomAlpha, topAlpha);
            if (y1 == 0 && y2 == 0) {
                // the xz column. its floor is at bedrock, nobody is going to miss it
                return;
            }
            IRenderer.emitHorizontalQuad(quads, stack, minX, minZ, maxX, maxZ, minY, bottomAlpha);
            // the rings get a faint pane each, so they look like they're scanning the box and not just floating in it
            IRenderer.emitHorizontalQuad(quads, stack, minX, minZ, maxX, maxZ, y1, bottomAlpha / 2);
            IRenderer.emitHorizontalQuad(quads, stack, minX, minZ, maxX, maxZ, y2, bottomAlpha / 2);
        }

        private void outline(BufferBuilder bufferBuilder, PoseStack stack) {
            float lineWidth = settings.goalRenderLineWidthPixels.value;
            IRenderer.glColor(color, outlineAlpha());
            renderHorizontalQuad(bufferBuilder, stack, minX, maxX, minZ, maxZ, y1, lineWidth);
            renderHorizontalQuad(bufferBuilder, stack, minX, maxX, minZ, maxZ, y2, lineWidth);

            for (double y = minY; y < maxY; y += 16) {
                double max = Math.min(maxY, y + 16);
                IRenderer.emitLine(bufferBuilder, stack, minX, y, minZ, minX, max, minZ, 0.0, 1.0, 0.0, lineWidth);
                IRenderer.emitLine(bufferBuilder, stack, maxX, y, minZ, maxX, max, minZ, 0.0, 1.0, 0.0, lineWidth);
                IRenderer.emitLine(bufferBuilder, stack, maxX, y, maxZ, maxX, max, maxZ, 0.0, 1.0, 0.0, lineWidth);
                IRenderer.emitLine(bufferBuilder, stack, minX, y, maxZ, minX, max, maxZ, 0.0, 1.0, 0.0, lineWidth);
            }
        }
    }
}
