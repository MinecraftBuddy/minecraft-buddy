package adris.altoclef.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

// the end portal ring: 12 frames around a 3x3 hole, corners empty. pure ints, no minecraft types.
// the ring is symmetric under a quarter turn so it does not matter which axis the room runs along
public final class FrameGeometry {
    public static final int FRAME_COUNT = 12;

    // x, z offsets from the centre of the hole (y is the same for all of them)
    private static final int[][] OFFSETS = {
            {2, 1}, {2, 0}, {2, -1},
            {-2, 1}, {-2, 0}, {-2, -1},
            {1, 2}, {0, 2}, {-1, 2},
            {1, -2}, {0, -2}, {-1, -2}
    };

    // a seen frame is {x, y, z} or {x, y, z, facingX, facingZ}. the facing is the horizontal direction the frame
    // looks, which for a generated room is toward the hole, and it settles the case a plain position cannot:
    // three frames on one side fit a ring on either side of them
    public static Optional<int[]> centreFromFrames(Collection<int[]> seenFrames) {
        if (seenFrames == null || seenFrames.size() < 3) {
            return Optional.empty();
        }
        int[] first = null;
        Set<String> distinct = new HashSet<>();
        for (int[] f : seenFrames) {
            if (f == null || (f.length != 3 && f.length != 5)) {
                return Optional.empty();
            }
            distinct.add(f[0] + "," + f[1] + "," + f[2]);
            if (first == null) {
                first = f;
            } else if (f[1] != first[1]) {
                // one ring is flat. different heights = not one portal
                return Optional.empty();
            }
        }
        if (distinct.size() < 3) {
            return Optional.empty();
        }
        int[] found = null;
        for (int[] o : OFFSETS) {
            int[] candidate = {first[0] - o[0], first[1], first[2] - o[1]};
            if (!fitsAll(candidate, seenFrames)) {
                continue;
            }
            if (found != null && !(found[0] == candidate[0] && found[2] == candidate[2])) {
                // two different rings explain the frames, ask again once more are seen
                return Optional.empty();
            }
            found = candidate;
        }
        return Optional.ofNullable(found);
    }

    private static boolean fitsAll(int[] centre, Collection<int[]> frames) {
        for (int[] f : frames) {
            if (!fits(centre, f)) {
                return false;
            }
        }
        return true;
    }

    private static boolean fits(int[] centre, int[] frame) {
        int ox = frame[0] - centre[0];
        int oz = frame[2] - centre[2];
        boolean onRing = (Math.abs(ox) == 2 && Math.abs(oz) <= 1) || (Math.abs(oz) == 2 && Math.abs(ox) <= 1);
        if (!onRing) {
            return false;
        }
        if (frame.length < 5) {
            return true;
        }
        // frames face the hole: a frame on the +x side looks along -x and so on
        int wantX = Math.abs(ox) == 2 ? -Integer.signum(ox) : 0;
        int wantZ = Math.abs(oz) == 2 ? -Integer.signum(oz) : 0;
        return frame[3] == wantX && frame[4] == wantZ;
    }

    public static List<int[]> positions(int[] centre) {
        List<int[]> out = new ArrayList<>(FRAME_COUNT);
        for (int[] o : OFFSETS) {
            out.add(new int[]{centre[0] + o[0], centre[1], centre[2] + o[1]});
        }
        return out;
    }

    // frames of this ring that hold an eye according to the lookup
    public static int filledCount(int[] centre, Predicate<int[]> hasEye) {
        int n = 0;
        for (int[] p : positions(centre)) {
            if (hasEye.test(p)) {
                n++;
            }
        }
        return n;
    }

    // frames of this ring we have actually seen
    public static int seenCount(int[] centre, Predicate<int[]> seen) {
        return filledCount(centre, seen);
    }

    public static boolean allSeen(int[] centre, Predicate<int[]> seen) {
        return seenCount(centre, seen) == FRAME_COUNT;
    }

    private FrameGeometry() {
    }
}
