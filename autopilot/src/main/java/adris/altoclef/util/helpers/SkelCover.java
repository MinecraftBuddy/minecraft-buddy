package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// (the buddy's fork, Tuning skel_cover and skel_close_rule, fixes of 13 Nov 2026, on) the overworld's skeletons, the rules with
// no minecraft in them: SkelCoverTask asks the world and places or digs, CombatBrain holds the cover (Hold), CombatCommit asks
// the close rule before a walk up to one. test heel's first 18 runs: skeletons killed 5, each time with 44 to 400 cobblestone
// in the bag. the buddy stood in a skeleton's line after a run ended clear, while healing or with nothing running, and walked
// up to skeletons hurt, without armour or with others in sight. the nether keeps its own rules: nothing here is asked there
public final class SkelCover {
    private SkelCover() {
    }

    // under this hp a skeleton with a line on the buddy is met from cover; the hold lets go at it too
    public static final float HP_LINE = 18;
    // a skeleton this close with a line counts (its bow reaches 15 and it walks in)
    public static final double RANGE = 16;
    // a walk up to a skeleton only from this hp
    public static final float CLOSE_HP = 16;
    // out of cover once no skeleton of the kinds is within LEAVE_RANGE for LEAVE_TICKS (5 s)
    public static final double LEAVE_RANGE = 20;
    public static final long LEAVE_TICKS = 100;
    // no hp back for this long (a meal under way counts as progress): regen is not happening, out of cover (60 s), and no
    // cover again for RETRY_STALL_TICKS (30 s); a cover that could not be had is not tried again for RETRY_FAILED_TICKS (10 s)
    public static final long STALL_TICKS = 1200;
    public static final long RETRY_STALL_TICKS = 600;
    public static final long RETRY_FAILED_TICKS = 200;

    public static boolean coverOn() {
        return Tuning.get("skel_cover", 0) > 0;
    }

    public static boolean closeOn() {
        return Tuning.get("skel_close_rule", 0) > 0;
    }

    public static float hpLine() {
        return (float) Tuning.get("skel_cover_hp", HP_LINE);
    }

    public static double range() {
        return Tuning.get("skel_cover_range", RANGE);
    }

    public static float closeHp() {
        return (float) Tuning.get("skel_close_hp", CLOSE_HP);
    }

    // the kinds that shoot arrows from where they stand: the skeleton, the stray, the bogged and the parched. not the wither
    // skeleton (a melee mob), not the blaze
    public static boolean kind(String entityType) {
        return switch (entityType) {
            case "skeleton", "stray", "bogged", "parched" -> true;
            default -> false;
        };
    }

    // the cover is wanted: the overworld, alive and under the hp line, and at least one skeleton with a line within range
    public static boolean wanted(boolean overworld, float hp, int lines, float hpLine) {
        return overworld && hp > 0 && hp < hpLine && lines > 0;
    }

    // why the buddy does not walk up to the skeleton, null when it may: hp at closeHp or more, a sword, at least one armour piece
    // worn and no other skeleton with a line within range (by sight, hit yet or not)
    public static String closeRefusal(float hp, boolean sword, int armour, int otherLines, float closeHp, double range) {
        if (hp < closeHp) return String.format(Locale.ROOT, "hp %.1f, under %.0f", hp, closeHp);
        if (!sword) return "no sword";
        if (armour <= 0) return "no armour on";
        if (otherLines > 0) return otherLines + (otherLines == 1 ? " more skeleton" : " more skeletons") + " with a line within " + Math.round(range);
        return null;
    }

    // the block cells the line from (x0, y0, z0) to (x1, y1, z1) passes through, in order, as {x, y, z}, the cell it starts in
    // left out, up to maxDist along the line: the cells a block can take the line away in (the skeleton's sight is the ray from
    // its eye to ours, LivingEntity.hasLineOfSight)
    public static List<int[]> rayCells(double x0, double y0, double z0, double x1, double y1, double z1, double maxDist) {
        List<int[]> out = new ArrayList<>();
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-9) return out;
        dx /= length;
        dy /= length;
        dz /= length;
        double limit = Math.min(length, maxDist);
        int x = (int) Math.floor(x0), y = (int) Math.floor(y0), z = (int) Math.floor(z0);
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int sy = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tx = boundary(x0, x, sx, dx), ty = boundary(y0, y, sy, dy), tz = boundary(z0, z, sz, dz);
        double ux = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double uy = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double uz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        for (int i = 0; i < 64; i++) {
            double t;
            if (tx <= ty && tx <= tz) {
                t = tx;
                x += sx;
                tx += ux;
            } else if (ty <= tz) {
                t = ty;
                y += sy;
                ty += uy;
            } else {
                t = tz;
                z += sz;
                tz += uz;
            }
            // (a face at the limit itself is the far side of the last cell, not a cell of its own)
            if (t >= limit) break;
            out.add(new int[]{x, y, z});
        }
        return out;
    }

    // how far along the line the first cell face on this axis is
    private static double boundary(double start, int cell, int step, double dir) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        double next = step > 0 ? cell + 1 : cell;
        return (next - start) / dir;
    }

    // the cover as it holds, once a tick (CombatBrain): taken when it is wanted and on offer (a full block in the bag, or ground
    // to dig), left when the hp is back to the line, when no skeleton of the kinds is within LEAVE_RANGE for LEAVE_TICKS, when
    // regen does not happen (STALL_TICKS), or when the cover task could not get it up. what it said goes out through takeNote
    public static final class Hold {
        private boolean on;
        private long gainAt;
        private float lastHp;
        private long farSince = -1;
        private long blockedUntil = Long.MIN_VALUE / 2;
        private long blockedFrom = Long.MIN_VALUE / 2;
        private String note;

        public boolean on() {
            return on;
        }

        public String takeNote() {
            String said = note;
            note = null;
            return said;
        }

        // a stall or a cover that could not be had waits its retry (a clock that went back, a new world, lets it go)
        private boolean blocked(long now) {
            return now >= blockedFrom && now < blockedUntil;
        }

        // the cover takes the buddy this tick: in it already, or wanted with cover on offer and no retry to wait for
        public boolean takes(long now, boolean wanted, boolean offered) {
            return on || (wanted && offered && !blocked(now));
        }

        // with nothing committed (no run, no fight). lines and lineDist: the skeletons with a line within range and the nearest
        // of them; nearest: the nearest skeleton of the kinds, seen or not (infinity for none); eating: a meal is under way;
        // failed: why the cover task gave up, null while it has not
        public void step(long now, boolean wanted, boolean offered, float hp, float hpLine, int lines, double lineDist,
                         double nearest, boolean eating, String failed) {
            if (!on) {
                if (!wanted || !offered || blocked(now)) return;
                on = true;
                gainAt = now;
                lastHp = hp;
                farSince = -1;
                note = String.format(Locale.ROOT, "skeleton cover: %d %s with a line within %.1f at %.1f hp, taking cover", lines,
                        lines == 1 ? "skeleton" : "skeletons", lineDist, hp);
                return;
            }
            if (hp > lastHp || eating || now < gainAt) gainAt = now;
            lastHp = hp;
            if (hp >= hpLine) {
                leave(now, String.format(Locale.ROOT, "hp %.1f", hp), 0);
                return;
            }
            if (failed != null) {
                leave(now, "no cover here (" + failed + ")", RETRY_FAILED_TICKS);
                return;
            }
            if (nearest > LEAVE_RANGE) {
                if (farSince < 0 || now < farSince) farSince = now;
                if (now - farSince >= LEAVE_TICKS) {
                    leave(now, "no skeleton within " + Math.round(LEAVE_RANGE) + " for " + LEAVE_TICKS / 20 + " s", 0);
                    return;
                }
            } else {
                farSince = -1;
            }
            if (now - gainAt >= STALL_TICKS) leave(now, "no hp back in " + STALL_TICKS / 20 + " s", RETRY_STALL_TICKS);
        }

        // a run or a fight took the wheel: out of cover, the next stretch with nothing committed asks again
        public void interrupt(long now, String why) {
            if (on) leave(now, why, 0);
        }

        public void reset() {
            on = false;
            farSince = -1;
            blockedUntil = Long.MIN_VALUE / 2;
            blockedFrom = Long.MIN_VALUE / 2;
            note = null;
        }

        private void leave(long now, String why, long retry) {
            on = false;
            farSince = -1;
            if (retry > 0) {
                blockedFrom = now;
                blockedUntil = now + retry;
            }
            note = "skeleton cover: out of cover: " + why;
        }
    }
}
