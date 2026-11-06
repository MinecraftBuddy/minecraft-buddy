package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// where to point a thrown stack. a random look in a tunnel throws into the wall a block away, inside pickup range, and the
// pile at our feet refills every slot we free. pick the direction with the most open room instead
public final class ThrowAim {

    private ThrowAim() {
    }

    public static final int DIRECTIONS = 16;
    public static final int MAX_RUN = 4;
    private static final int SUBSTEPS = 4;
    // a little up so it carries, but not much: a 2 high tunnel has the item's top near 1.77 against a ceiling at 2.0
    public static final float PITCH = -10f;
    // how far off the aim still counts as lined up, and how long we wait for the look to land before throwing anyway
    // (baritone's look behavior may be eating the rotation every tick while it paths)
    public static final float ALIGN_DEGREES = 5f;
    public static final int GIVE_UP_TICKS = 5;

    // yaw of direction i. mc yaw: 0 = +z, 90 = -x, 180 = -z, 270 = +x
    public static float yawOf(int i) {
        return i * (360f / DIRECTIONS);
    }

    // runs[i] = clear blocks along yawOf(i). longest wins, ties go to whoever the random pick lands on
    public static float pick(int[] runs, RandomGenerator random) {
        int best = -1;
        int count = 0;
        for (int i = 0; i < DIRECTIONS; i++) {
            if (runs[i] > best) {
                best = runs[i];
                count = 1;
            } else if (runs[i] == best) {
                count++;
            }
        }
        int nth = random.nextInt(count);
        for (int i = 0; i < DIRECTIONS; i++) {
            if (runs[i] == best && nth-- == 0) {
                return yawOf(i);
            }
        }
        return 0f;
    }

    // aligned = throw now. not aligned: wait for the look to land, but only so long
    public static boolean shouldThrow(boolean aligned, int heldTicks) {
        return aligned || heldTicks >= GIVE_UP_TICKS;
    }

    public static boolean isAligned(float currentYaw, float currentPitch, float aimYaw, float aimPitch) {
        float dYaw = Math.abs(Mth.wrapDegrees(currentYaw - aimYaw));
        float dPitch = Math.abs(currentPitch - aimPitch);
        return dYaw <= ALIGN_DEGREES && dPitch <= ALIGN_DEGREES;
    }

    public static float pickYaw(AltoClef mod, RandomGenerator random) {
        Level level = mod.getWorld();
        BlockPos feet = mod.getPlayer().blockPosition();
        Vec3 p = mod.getPlayer().position();
        int[] runs = new int[DIRECTIONS];
        for (int i = 0; i < DIRECTIONS; i++) {
            double yaw = Math.toRadians(yawOf(i));
            double dx = -Math.sin(yaw);
            double dz = Math.cos(yaw);
            // quarter block steps so a diagonal can't hop over a cell it clips
            int steps = 0;
            while (steps < MAX_RUN * SUBSTEPS) {
                double d = (steps + 1) / (double) SUBSTEPS;
                int x = (int) Math.floor(p.x + dx * d);
                int z = (int) Math.floor(p.z + dz * d);
                if (!open(level, new BlockPos(x, feet.getY(), z)) || !open(level, new BlockPos(x, feet.getY() + 1, z))) {
                    break;
                }
                steps++;
            }
            int run = steps / SUBSTEPS;
            runs[i] = run;
        }
        return pick(runs, random);
    }

    private static boolean open(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty();
    }
}
