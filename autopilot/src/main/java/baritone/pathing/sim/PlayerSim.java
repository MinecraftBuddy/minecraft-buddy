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

package baritone.pathing.sim;

import net.minecraft.util.Mth;

import java.util.Arrays;

// one player on foot, one game tick at a time, the way 26.3 does it. the method names in the comments are the vanilla ones this
// was ported from (LocalPlayer.aiStep, LivingEntity.aiStep / travelInAir / jumpFromGround, Entity.move / collide), floats where
// vanilla has floats since the last digit of a sprint jump decides whether a step is cleared.
// not modelled and bailed on: fluids, climbables, ice / slime / honey / soul sand / webs / powder snow, potions that touch
// jump or gravity, sneaking, passengers, elytra. blocks only: other entities (boats, minecarts, shulkers) and the world border
// are walked straight through. a tick() that returns false because it ended somewhere odd has still moved the player
public final class PlayerSim {

    // LivingEntity.SPEED_MODIFIER_SPRINTING, 0.3F as an ADD_MULTIPLIED_TOTAL. the amount is a double so the float widens
    public static final double SPRINT_MODIFIER = 0.3F;
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    // Direction.axisStepOrder, as indexes into x y z
    private static final int[] Y_X_Z = {1, 0, 2}, Y_Z_X = {1, 2, 0};

    // what the player is made of: attribute values, minus the sprint modifier, which the sim applies itself the way setSprinting does
    public record Params(float width, float height, float stepHeight, double gravity, double jumpStrength, double walkSpeed, boolean sprintAllowed) {
        public static Params vanilla() {
            return new Params(0.6F, 1.8F, 0.6F, 0.08, 0.42F, 0.1F, true);
        }
    }

    // one tick of keys. forward and left are -1, 0 or 1 (left is A). moveYaw is the yaw moveRelative and jumpFromGround see, which
    // on Baritone is the spoofed one (MixinEntity, MixinLivingEntity). viewYaw is the real getYRot() that the minor collision
    // test reads, because PlayerUpdate PRE only turns the head after aiStep
    public record Controls(int forward, int left, boolean jump, boolean sprint, float moveYaw, float viewYaw) {
        public Controls(int forward, int left, boolean jump, boolean sprint, float yaw) {
            this(forward, left, jump, sprint, yaw, yaw);
        }

        public Controls withJump(boolean jump) {
            return new Controls(forward, left, jump, sprint, moveYaw, viewYaw);
        }
    }

    private final Params params;
    private final CollisionProvider world;
    private final float halfWidth;

    // every tick() ever run, so whoever asks the sim a question can say what it cost (AscendHop's telemetry). the client thread only
    public static long ticksRun;

    public double x, y, z, vx, vy, vz;
    public boolean onGround, sprinting, horizontalCollision, minorHorizontalCollision, verticalCollision;
    // LivingEntity.noJumpDelay, the ticks left before holding space jumps again
    public int noJumpDelay;
    // set by the last tick(): did it leave the ground, and why it gave up if it did
    public boolean jumped;
    private String failure;

    // scratch, so a hundred ticks of sim is not a hundred thousand allocations
    private final BoxList boxes = new BoxList(), stepBoxes = new BoxList(), cell = new BoxList();
    private final double[] box = new double[6], groundedBox = new double[6], swept = new double[6], query = new double[6];
    private final double[] step = new double[3], stepped = new double[3], want = new double[3], out = new double[3];
    private float[] candidates = new float[8];
    private float xxa, zza;

    public PlayerSim(Params params, CollisionProvider world) {
        this.params = params;
        this.world = world;
        this.halfWidth = params.width() / 2.0F;
    }

    public PlayerSim copy() {
        PlayerSim c = new PlayerSim(params, world);
        c.x = x;
        c.y = y;
        c.z = z;
        c.vx = vx;
        c.vy = vy;
        c.vz = vz;
        c.onGround = onGround;
        c.sprinting = sprinting;
        c.horizontalCollision = horizontalCollision;
        c.minorHorizontalCollision = minorHorizontalCollision;
        c.verticalCollision = verticalCollision;
        c.noJumpDelay = noJumpDelay;
        return c;
    }

    public Params params() {
        return params;
    }

    // why the last tick() returned false
    public String failure() {
        return failure;
    }

    // the speed attribute as setSprinting leaves it
    private double walkSpeedAttribute(boolean sprinting) {
        return sprinting ? params.walkSpeed() * (1.0 + SPRINT_MODIFIER) : params.walkSpeed();
    }

    // run one tick. false if the player is somewhere the sim does not do the maths for, with nothing changed when that is where
    // the tick started, and the tick done anyway when it ended there
    public boolean tick(Controls c) {
        ticksRun++;
        jumped = false;
        String bad = unsupported();
        if (bad != null) {
            failure = bad;
            return false;
        }
        failure = null;

        // KeyboardInput / PlayerMovementInput: moveVector = new Vec2(left, forward).normalized()
        float lx = c.left(), lz = c.forward();
        float len = Mth.sqrt(lx * lx + lz * lz);
        float mvx = 0.0F, mvz = 0.0F;
        if (!(len < 1.0E-4F)) {
            mvx = lx / len;
            mvz = lz / len;
        }
        boolean hasForwardImpulse = mvz > 1.0E-5F;

        // LocalPlayer.aiStep. canStartSprinting, then shouldStopRunSprinting off last tick's collision flags
        if (!sprinting && hasForwardImpulse && params.sprintAllowed() && c.sprint()) {
            sprinting = true;
        }
        if (sprinting && (!params.sprintAllowed() || !hasForwardImpulse || horizontalCollision && !minorHorizontalCollision)) {
            sprinting = false;
        }

        // LivingEntity.aiStep
        if (noJumpDelay > 0) {
            noJumpDelay--;
        }
        double dx = vx, dy = vy, dz = vz;
        if (vx * vx + vz * vz < 9.0E-6) {
            dx = 0.0;
            dz = 0.0;
        }
        if (Math.abs(vy) < 0.003) {
            dy = 0.0;
        }

        // LocalPlayer.applyInput / modifyInput: 0.98 and then squared off so a diagonal is not slower than it should be
        xxa = mvx;
        zza = mvz;
        if (mvx * mvx + mvz * mvz != 0.0F) {
            float nx = mvx * 0.98F, nz = mvz * 0.98F;
            float length = Mth.sqrt(nx * nx + nz * nz);
            if (length > 0.0F) {
                float inv = 1.0F / length;
                float ux = nx * inv, uz = nz * inv;
                float tan = Math.abs(uz) > Math.abs(ux) ? Math.abs(ux) / Math.abs(uz) : Math.abs(uz) / Math.abs(ux);
                float modified = Math.min(length * Mth.sqrt(1.0F + tan * tan), 1.0F);
                xxa = ux * modified;
                zza = uz * modified;
            } else {
                xxa = nx;
                zza = nz;
            }
        }

        // the jump block: onGround and no delay, or the delay resets the moment the key is up
        if (c.jump()) {
            if (onGround && noJumpDelay == 0) {
                float power = (float) params.jumpStrength() * 1.0F * 1.0F + 0.0F; // LivingEntity.getJumpPower, plain block and no potion
                if (!(power <= 1.0E-5F)) {
                    dy = Math.max((double) power, dy);
                    if (sprinting) { // jumpFromGround, and MixinLivingEntity hands it the same yaw moveRelative gets
                        float angle = c.moveYaw() * DEG_TO_RAD;
                        dx += -Mth.sin(angle) * 0.2;
                        dz += Mth.cos(angle) * 0.2;
                    }
                    jumped = true;
                }
                noJumpDelay = 10;
            }
        } else {
            noJumpDelay = 0;
        }

        // LivingEntity.travelInAir
        float blockFriction = onGround ? modifiedFriction(0.6F) : 1.0F;
        float accel;
        if (onGround) { // getFrictionInfluencedSpeed. Player.getSpeed() is the attribute, so the sprint flag counts the tick it flips
            float speed = (float) walkSpeedAttribute(sprinting);
            accel = blockFriction > 0.6 ? speed * (0.21600002F / (blockFriction * blockFriction * blockFriction)) : speed;
        } else { // Player.getFlyingSpeed
            accel = sprinting ? 0.025999999F : 0.02F;
        }
        // Entity.moveRelative / getInputVector
        double inLen = (double) xxa * xxa + (double) zza * zza;
        if (!(inLen < 1.0E-7)) {
            double ix = xxa, iz = zza;
            if (inLen > 1.0) {
                double norm = Math.sqrt(inLen);
                ix /= norm;
                iz /= norm;
            }
            ix *= accel;
            iz *= accel;
            float rad = c.moveYaw() * DEG_TO_RAD;
            float sin = Mth.sin(rad), cos = Mth.cos(rad);
            dx += ix * cos - iz * sin;
            dz += iz * cos + ix * sin;
        }

        vx = dx;
        vy = dy;
        vz = dz;
        move(c.viewYaw());
        // what is left of the velocity after move (collisions zero the axis they hit) is what travelInAir reads back
        double movementY = vy - params.gravity();
        float airDrag = modifiedFriction(0.91F);
        float friction = blockFriction * airDrag;
        float verticalFriction = modifiedFriction(0.98F);
        vx = vx * friction;
        vy = movementY * verticalFriction;
        vz = vz * friction;

        // Entity.move multiplies x and z by the speed factor of the block we ended in or on. we only know the start, so
        // a tick that ends on soul sand or honey is not ours to answer for
        String ended = endedSomewhereOdd();
        if (ended != null) {
            failure = ended;
            return false;
        }
        return true;
    }

    // LivingEntity.computeModifiedFriction with the default modifier of 1
    private static float modifiedFriction(float friction) {
        return Mth.clamp(1.0F - (1.0F - friction) * 1.0F, 0.0F, 1.0F);
    }

    // anything about where we are standing that the maths above does not cover
    private String unsupported() {
        float w = halfWidth;
        int x0 = Mth.floor(x - w - 1.0E-3), x1 = Mth.floor(x + w + 1.0E-3);
        int z0 = Mth.floor(z - w - 1.0E-3), z1 = Mth.floor(z + w + 1.0E-3);
        // the block friction comes from getOnPos(0.500001F), speed and jump factors from the cell we are in, and
        // Entity.checkInsideBlocks from every cell the box touches
        int y0 = Math.min(Mth.floor(y - 0.500001), Mth.floor(y));
        int y1 = Mth.floor(y + params.height() + 1.0E-3);
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    if (!world.plain(bx, by, bz)) {
                        return "not plain at " + bx + " " + by + " " + bz;
                    }
                }
            }
        }
        return null;
    }

    // getBlockSpeedFactor looks at the cell we are in and the one getOnPos(0.500001F) gives. that one is the supporting block
    // when grounded, which can be under a corner of the box instead of the middle, so this only checks the middle and a tick
    // that lands on the edge of soul sand is caught by the next tick's unsupported() instead
    private String endedSomewhereOdd() {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (!world.plain(bx, Mth.floor(y), bz) || !world.plain(bx, Mth.floor(y - 0.500001), bz)) {
            return "ended on something not plain";
        }
        return null;
    }

    // Entity.move, off the velocity that tick() just set
    private void move(float viewYaw) {
        double dx = vx, dy = vy, dz = vz;
        collide(dx, dy, dz);
        double cx = out[0], cy = out[1], cz = out[2];
        double movementLength = cx * cx + cy * cy + cz * cz;
        if (movementLength > 1.0E-7 || dx * dx + dy * dy + dz * dz - movementLength < 1.0E-7) {
            x += cx;
            y += cy;
            z += cz;
        }
        boolean xCollision = !(Math.abs(cx - dx) < 1.0E-5F);
        boolean zCollision = !(Math.abs(cz - dz) < 1.0E-5F);
        horizontalCollision = xCollision || zCollision;
        verticalCollision = dy != cy;
        // setOnGroundWithMovement(verticalCollisionBelow, ...). the local player always takes this branch
        onGround = verticalCollision && dy < 0.0;
        minorHorizontalCollision = horizontalCollision && horizontalCollisionMinor(cx, cz, viewYaw);
        // restituteMovementAfterCollisions with a bounciness of 0: whatever was hit stops dead
        if (xCollision) {
            vx = 0.0;
        }
        if (zCollision) {
            vz = 0.0;
        }
        if (verticalCollision) {
            vy = 0.0;
        }
    }

    // LocalPlayer.isHorizontalCollisionMinor: close to where the keys wanted to go (under 8 degrees) is a graze, not a wall
    private boolean horizontalCollisionMinor(double mx, double mz, float viewYaw) {
        float rad = viewYaw * DEG_TO_RAD;
        double sin = Mth.sin(rad), cos = Mth.cos(rad);
        double globalXA = xxa * cos - zza * sin;
        double globalZA = zza * cos + xxa * sin;
        double aLengthSquared = globalXA * globalXA + globalZA * globalZA;
        double movementLengthSquared = mx * mx + mz * mz;
        if (!(aLengthSquared < 1.0E-5F) && !(movementLengthSquared < 1.0E-5F)) {
            double dot = globalXA * mx + globalZA * mz;
            double angle = Math.acos(dot / Math.sqrt(aLengthSquared * movementLengthSquared));
            return angle < 0.13962634F;
        }
        return false;
    }

    // Entity.collide: y first, then the longer of x and z, and a step up if the first try ran into something while on the ground
    private void collide(double dx, double dy, double dz) {
        out[0] = dx;
        out[1] = dy;
        out[2] = dz;
        if (dx * dx + dy * dy + dz * dz == 0.0) {
            return;
        }
        float stepHeight = params.stepHeight();
        box[0] = x - halfWidth;
        box[1] = y;
        box[2] = z - halfWidth;
        box[3] = x + halfWidth;
        box[4] = y + params.height();
        box[5] = z + halfWidth;
        expand(box, dx, dy, dz, query);
        gather(query, boxes);
        collideWithShapes(dx, dy, dz, box, boxes, step);
        boolean xCollision = dx != step[0], yCollision = dy != step[1], zCollision = dz != step[2];
        boolean onGroundAfterCollision = yCollision && dy < 0.0;
        if (stepHeight > 0.0F && (onGroundAfterCollision || onGround) && (xCollision || zCollision)) {
            System.arraycopy(box, 0, groundedBox, 0, 6);
            if (onGroundAfterCollision) {
                groundedBox[1] += step[1];
                groundedBox[4] += step[1];
            }
            expand(groundedBox, dx, stepHeight, dz, query);
            if (!onGroundAfterCollision) {
                query[1] += -1.0E-5F;
            }
            gather(query, stepBoxes);
            int n = candidateHeights(groundedBox[1], stepBoxes, stepHeight, (float) step[1]);
            double distanceToGround = box[1] - groundedBox[1];
            for (int i = 0; i < n; i++) {
                collideWithShapes(dx, candidates[i], dz, groundedBox, stepBoxes, stepped);
                if (stepped[0] * stepped[0] + stepped[2] * stepped[2] > step[0] * step[0] + step[2] * step[2]) {
                    out[0] = stepped[0];
                    out[1] = stepped[1] - distanceToGround;
                    out[2] = stepped[2];
                    return;
                }
            }
        }
        out[0] = step[0];
        out[1] = step[1];
        out[2] = step[2];
    }

    // Entity.collectCandidateStepUpHeights: every y coordinate of every box that is within step height above our feet, sorted
    private int candidateHeights(double minY, BoxList colliders, float maxStepHeight, float stepHeightToSkip) {
        int n = 0;
        for (int i = 0; i < colliders.size(); i++) {
            for (int k = 1; k < 6; k += 3) { // the min y then the max y, which is the order VoxelShape.getCoords gives them
                float relative = (float) (colliders.get(i, k) - minY);
                if (!(relative < 0.0F) && relative != stepHeightToSkip) {
                    if (relative > maxStepHeight) {
                        break;
                    }
                    boolean known = false;
                    for (int j = 0; j < n && !known; j++) {
                        known = candidates[j] == relative;
                    }
                    if (!known) {
                        if (n == candidates.length) {
                            candidates = Arrays.copyOf(candidates, n * 2);
                        }
                        candidates[n++] = relative;
                    }
                }
            }
        }
        Arrays.sort(candidates, 0, n);
        return n;
    }

    // Entity.collideWithShapes: y first, then the longer of x and z, each one swept from where the last one left the box
    private void collideWithShapes(double mx, double my, double mz, double[] from, BoxList shapes, double[] result) {
        if (shapes.size() == 0) {
            result[0] = mx;
            result[1] = my;
            result[2] = mz;
            return;
        }
        result[0] = 0.0;
        result[1] = 0.0;
        result[2] = 0.0;
        want[0] = mx;
        want[1] = my;
        want[2] = mz;
        int[] order = Math.abs(mx) < Math.abs(mz) ? Y_Z_X : Y_X_Z;
        for (int axis : order) {
            if (want[axis] != 0.0) {
                for (int i = 0; i < 3; i++) {
                    swept[i] = from[i] + result[i];
                    swept[i + 3] = from[i + 3] + result[i];
                }
                result[axis] = clip(axis, swept, shapes, want[axis]);
            }
        }
    }

    // Shapes.collide / VoxelShape.collideX for a one box shape. the 1e-7s are vanilla's, a sweep stops at the first face it meets
    private static double clip(int a, double[] mv, BoxList shapes, double distance) {
        int b = (a + 1) % 3, c = (a + 2) % 3;
        double[] d = shapes.data;
        for (int i = 0; i < shapes.size(); i++) {
            if (Math.abs(distance) < 1.0E-7) {
                return 0.0;
            }
            int o = i * 6;
            if (!(mv[b] + 1.0E-7 < d[o + 3 + b] && mv[b + 3] - 1.0E-7 >= d[o + b])
                    || !(mv[c] + 1.0E-7 < d[o + 3 + c] && mv[c + 3] - 1.0E-7 >= d[o + c])) {
                continue;
            }
            if (distance > 0.0) {
                if (mv[a + 3] - 1.0E-7 < d[o + a]) {
                    distance = Math.min(distance, d[o + a] - mv[a + 3]);
                }
            } else if (mv[a] + 1.0E-7 >= d[o + 3 + a]) {
                distance = Math.max(distance, d[o + 3 + a] - mv[a]);
            }
        }
        return distance;
    }

    // AABB.expandTowards
    private static void expand(double[] src, double dx, double dy, double dz, double[] dst) {
        System.arraycopy(src, 0, dst, 0, 6);
        if (dx < 0.0) {
            dst[0] += dx;
        } else {
            dst[3] += dx;
        }
        if (dy < 0.0) {
            dst[1] += dy;
        } else {
            dst[4] += dy;
        }
        if (dz < 0.0) {
            dst[2] += dz;
        } else {
            dst[5] += dz;
        }
    }

    // Level.getBlockCollisions: every cell the box touches plus a ring of one (fences stand 1.5 tall), and only the boxes that
    // really overlap it
    private void gather(double[] q, BoxList into) {
        into.clear();
        int x0 = Mth.floor(q[0] - 1.0E-7) - 1, x1 = Mth.floor(q[3] + 1.0E-7) + 1;
        int y0 = Mth.floor(q[1] - 1.0E-7) - 1, y1 = Mth.floor(q[4] + 1.0E-7) + 1;
        int z0 = Mth.floor(q[2] - 1.0E-7) - 1, z1 = Mth.floor(q[5] + 1.0E-7) + 1;
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    cell.clear();
                    world.boxes(bx, by, bz, cell);
                    for (int i = 0; i < cell.size(); i++) {
                        if (cell.get(i, 0) < q[3] && cell.get(i, 3) > q[0] && cell.get(i, 1) < q[4] && cell.get(i, 4) > q[1]
                                && cell.get(i, 2) < q[5] && cell.get(i, 5) > q[2]) {
                            into.add(cell.get(i, 0), cell.get(i, 1), cell.get(i, 2), cell.get(i, 3), cell.get(i, 4), cell.get(i, 5));
                        }
                    }
                }
            }
        }
    }
}
