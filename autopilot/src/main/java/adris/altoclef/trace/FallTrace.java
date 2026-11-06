package adris.altoclef.trace;

import java.util.Locale;
import java.util.function.Function;

// the last second or three of motion, kept so a long fall into the nether sea can be read back tick by tick. parallel
// primitive arrays that wrap, the object refs (block, movement, goal) are only turned into text when a dump is asked for.
// pure: Monitors reads the player and hands the numbers in
public final class FallTrace {

    public static final int SIZE = 60;
    // a fall is this many blocks before it is worth a dump (the little hops of a path never get there)
    public static final float FALL_TRIGGER = 4;

    // the ring ends at the lava entry and the escape is the other half of the story, this many more ticks print one at a time
    public static final int AFTER_LAVA = 40;

    public static final byte FORWARD = 1, BACK = 2, LEFT = 4, RIGHT = 8, SPRINT = 16, JUMP = 32, SNEAK = 64;

    private final double[] x = new double[SIZE], y = new double[SIZE], z = new double[SIZE];
    private final double[] dx = new double[SIZE], dy = new double[SIZE], dz = new double[SIZE];
    private final float[] fall = new float[SIZE];
    private final boolean[] ground = new boolean[SIZE], hurt = new boolean[SIZE];
    private final byte[] keys = new byte[SIZE];
    private final int[] attacker = new int[SIZE];
    private final double[] ax = new double[SIZE], ay = new double[SIZE], az = new double[SIZE];
    private final Object[] under = new Object[SIZE], move = new Object[SIZE], goal = new Object[SIZE];
    // the movement's status and its last bridge PlaceResult that tick, both enums, so still no strings until a dump
    private final Object[] status = new Object[SIZE], place = new Object[SIZE];
    // where the head pointed and whether the last move bumped something up or down. a bridge that never clicks is either
    // looking the wrong way or standing on nothing, these say which
    private final float[] yaw = new float[SIZE], pitch = new float[SIZE];
    // whose input object was on the player. the keys alone can't tell a forced W from one of ours
    private final Class<?>[] inputClass = new Class<?>[SIZE];
    private final boolean[] vColl = new boolean[SIZE], vCollBelow = new boolean[SIZE];
    // a raised shield eats the knock, so whether it was up says if a hit could have thrown us at all
    private final boolean[] blocking = new boolean[SIZE];
    private int next;
    private int size;
    private int carryLeft;
    private int carried;
    // 5 ticks on the ground and the next fall counts again
    private final EntryLatch fallLatch = new EntryLatch(5);

    // the fall half of the trigger: the tick fallDistance crosses the line in the nether, once per fall. the caller ORs in the
    // lava entry. the latch is fed every tick or it never re-arms
    public boolean falling(boolean nether, float fallDistance) {
        return fallLatch.tick(nether && fallDistance > FALL_TRIGGER);
    }

    // attackerId < 0 means nobody (no hit this tick)
    public void push(double px, double py, double pz, double vx, double vy, double vz, boolean onGround, float fallDistance, byte keyBits,
                     float yawNow, float pitchNow, boolean verticalCollision, boolean verticalCollisionBelow, Object blockUnder, Object movement, Object moveStatus, Object placeResult, Object goalNow, Class<?> inputNow, boolean blockingNow, boolean hit, int attackerId,
                     double hx, double hy, double hz) {
        int i = next;
        x[i] = px;
        y[i] = py;
        z[i] = pz;
        dx[i] = vx;
        dy[i] = vy;
        dz[i] = vz;
        ground[i] = onGround;
        fall[i] = fallDistance;
        keys[i] = keyBits;
        yaw[i] = yawNow;
        pitch[i] = pitchNow;
        vColl[i] = verticalCollision;
        vCollBelow[i] = verticalCollisionBelow;
        under[i] = blockUnder;
        move[i] = movement;
        status[i] = moveStatus;
        place[i] = placeResult;
        goal[i] = goalNow;
        inputClass[i] = inputNow;
        blocking[i] = blockingNow;
        hurt[i] = hit;
        attacker[i] = attackerId;
        ax[i] = hx;
        ay[i] = hy;
        az[i] = hz;
        next = (i + 1) % SIZE;
        if (size < SIZE) {
            size++;
        }
    }

    public int size() {
        return size;
    }

    // oldest first, one line per tick. i = 0 is the oldest tick still kept. movement names come from the caller, this class
    // does not know what a movement is
    public String line(int i, Function<Object, String> moveName) {
        return format((next - size + i + SIZE) % SIZE, "t-" + (size - 1 - i), moveName);
    }

    // start printing the ticks after this one, see AFTER_LAVA
    public void carryOn() {
        carryLeft = AFTER_LAVA;
        carried = 0;
    }

    // the newest tick as a line while a carry is on, null when none is. once per push or it prints the same tick twice
    public String carriedLine(Function<Object, String> moveName) {
        if (carryLeft <= 0) {
            return null;
        }
        carryLeft--;
        return format((next - 1 + SIZE) % SIZE, "t+" + ++carried, moveName);
    }

    private String format(int at, String when, Function<Object, String> moveName) {
        String line = String.format(Locale.ROOT, "[falltrace] %s pos %.2f %.2f %.2f vel %.3f %.3f %.3f ground %b vcoll %s fall %.1f keys %s look %.2f/%.2f under %s move %s status %s place %s goal %s input %s",
                when, x[at], y[at], z[at], dx[at], dy[at], dz[at], ground[at], collWord(vColl[at], vCollBelow[at]), fall[at], keyWord(keys[at]),
                yaw[at], pitch[at], under[at], move[at] == null ? "none" : moveName.apply(move[at]),
                orDash(status[at]), orDash(place[at]), shorten(goal[at]), inputClass[at] == null ? "-" : inputClass[at].getSimpleName());
        if (hurt[at]) {
            line += String.format(Locale.ROOT, " HURT by #%d at %.2f %.2f %.2f", attacker[at], ax[at], ay[at], az[at]);
        }
        return line + " blocking " + blocking[at];
    }

    // below implies a vertical collision, so three words cover it
    static String collWord(boolean vertical, boolean below) {
        return !vertical ? "-" : below ? "below" : "above";
    }

    static String keyWord(byte bits) {
        if (bits == 0) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        if ((bits & FORWARD) != 0) out.append('W');
        if ((bits & BACK) != 0) out.append('S');
        if ((bits & LEFT) != 0) out.append('A');
        if ((bits & RIGHT) != 0) out.append('D');
        if ((bits & SPRINT) != 0) out.append('R');
        if ((bits & JUMP) != 0) out.append('J');
        // C for crouch, S was taken by back
        if ((bits & SNEAK) != 0) out.append('C');
        return out.toString();
    }

    private static String orDash(Object o) {
        return o == null ? "-" : o.toString();
    }

    // goals print their whole life story, the first bit says which one it is
    private static String shorten(Object o) {
        if (o == null) {
            return "none";
        }
        String s = o.toString();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }
}
