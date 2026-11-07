package adris.altoclef.util.helpers;

// the buddy's fork: two caps on waits at the blaze spawner that had no end, each behind its own Tuning value (seconds, 0 = off,
// the default), without a world.
// - spawner_wait_cap (row L12): "wither skeletons at the spawner, waiting" holds while three or more are within 12 of the
//   spawner, and in a fortress they keep spawning: realfort-pair-trade A:7 stood twelve game minutes at 20 hp below its
//   spawner, 11 blazes within 16, till the stall. SpawnerWait counts the ticks waited at one spawner; once they reach the
//   cap the wait is over there for good, and the camp goes on as with no pack (the fight's own back off from a pack within
//   eight stays).
// - lure_cap (row L15): the looks round a corner (BlazeLureTask) never end by themselves, and the crowd's hiding place ends
//   only when the danger about is down to 2, every blaze within 20 counted, seen or not: in a closed pocket with two blazes
//   idling by the spawner it never is. A:15, A:68 and A:79 sat 7 to 11 game minutes hiding, 123 to 272 looks, no blaze came,
//   no "out of hiding". LureWatch counts the ticks one lure (or the walk into its hiding place) had the wheel at one cell
//   since a blaze last came within reach (the fight going after one); at the cap the hide ends and its pocket is barred, or
//   the camp's corner (or standing pillbox) is dropped and the camp picked again without it. since 7 Nov 2026 the cap bars
//   the whole nook and holds off a new hide for a while (Nooks, HideHold, below).
public final class BlazeCapRules {
    private BlazeCapRules() {
    }

    public static long spawnerWaitCapTicks() {
        return ticks(Tuning.get("spawner_wait_cap", 0));
    }

    public static long lureCapTicks() {
        return ticks(Tuning.get("lure_cap", 0));
    }

    // seconds of a Tuning value as game ticks, 0 for none or less
    static long ticks(double seconds) {
        return seconds > 0 ? Math.round(seconds * 20) : 0;
    }

    public enum Wait { HOLD, CAP, PAST }

    // the wait for a wither skeleton pack at one spawner: every tick the rods task would wait counts, in all (the count crosses
    // three and back by the second, and the walk to the camp takes the ticks between, so a clock reset by each gap never
    // reached anything). a new spawner counts afresh
    public static final class SpawnerWait {
        private long spawner = Long.MIN_VALUE;
        private long ticks;
        private long last = Long.MIN_VALUE;
        private boolean past;
        private boolean said;

        // one tick the pack holds the camp at this spawner (its key). HOLD: wait on; CAP: the cap is reached this tick, the
        // wait is over at this spawner (said once); PAST: over earlier, go on. capTicks 0: HOLD always (the switch off)
        public Wait tick(long spawnerKey, long now, long capTicks) {
            if (spawnerKey != spawner) {
                spawner = spawnerKey;
                ticks = 0;
                last = Long.MIN_VALUE;
                past = false;
                said = false;
            }
            if (past) return Wait.PAST;
            if (now != last) {
                ticks++;
                last = now;
            }
            if (capTicks > 0 && ticks >= capTicks) {
                past = true;
                return Wait.CAP;
            }
            return Wait.HOLD;
        }

        // ticks waited at the spawner (the key) now; 0 for another one
        public long ticks(long spawnerKey) {
            return spawnerKey == spawner ? ticks : 0;
        }

        // true once per spawner, the first time it is asked after a tick there (for the wait's one opening line)
        public boolean firstLine(long spawnerKey) {
            if (spawnerKey != spawner || said || ticks == 0) return false;
            said = true;
            return true;
        }
    }

    // the ticks one lure had the wheel at one cell (its key) since a blaze last came within reach. a new cell counts afresh; a
    // blaze in reach (drawnAt, the game time the fight last went after one) at or after the last counted tick starts it again
    public static final class LureWatch {
        private long cell = Long.MIN_VALUE;
        private long ticks;
        private long last = Long.MIN_VALUE;

        // one tick the lure at this cell had the wheel; true once capTicks such ticks passed with no blaze in reach (capTicks
        // 0: never, the switch off). the owner acts on true and moves on, reset() or another cell
        public boolean tick(long cellKey, long now, long drawnAt, long capTicks) {
            if (cellKey != cell) {
                cell = cellKey;
                ticks = 0;
                last = Long.MIN_VALUE;
            }
            if (drawnAt != Long.MIN_VALUE && last != Long.MIN_VALUE && drawnAt >= last) ticks = 0;
            if (now != last) {
                ticks++;
                last = now;
            }
            return capTicks > 0 && ticks >= capTicks;
        }

        public long ticks() {
            return ticks;
        }

        public void reset() {
            cell = Long.MIN_VALUE;
            ticks = 0;
            last = Long.MIN_VALUE;
        }
    }

    // ---- after a lure cap (the same switch). its first firings on the rig (realfort-pair-camp2 B:7, 7 Nov 2026) fired on time,
    // three times, and each time a danger of 4.5 to 6 hid the buddy again within 2 to 6 s one block over in the same nook, at
    // 20 hp, none of the 11 to 15 blazes within 20 coming within 4: one barred cell did nothing. so a cap bars the nook, every
    // cell within NOOK_RADIUS of the barred one, as a hiding place and as the camp's corner for the rest of the task, and holds
    // off a new hide for HOLD_OFF_TICKS unless the danger is twice the hide's (BlazeFight.HIDE_SCORE 4), the hp is down to the
    // run's (BlazeFightRules.RUN_HEALTH) or a mob's hit landed since the cap (that ends the hold-off). after it a hide is
    // allowed again, never in a barred nook
    public static final double NOOK_RADIUS = 4;
    public static final long HOLD_OFF_TICKS = 60 * 20;
    public static final double HOLD_OFF_DANGER = 8;
    public static final float HOLD_OFF_HEALTH = BlazeFightRules.RUN_HEALTH;
    // the hold-off's line at most this often
    public static final long HOLD_LINE_TICKS = 5 * 20;

    // a new hide held off now: within holdTicks of the cap (capAt, Long.MIN_VALUE for none), no hit since, the danger below
    // HOLD_OFF_DANGER and the hp above HOLD_OFF_HEALTH
    public static boolean holdOff(long now, long capAt, long holdTicks, boolean hitSince, double danger, float health) {
        if (capAt == Long.MIN_VALUE || hitSince || now - capAt >= holdTicks) return false;
        return danger < HOLD_OFF_DANGER && health > HOLD_OFF_HEALTH;
    }

    // what let a hide through within the hold-off's time: "a hit", "danger", "hp", or null (the hold-off holds, or is over)
    public static String holdBroken(long now, long capAt, long holdTicks, boolean hitSince, double danger, float health) {
        if (capAt == Long.MIN_VALUE || now - capAt >= holdTicks) return null;
        if (hitSince) return "a hit";
        if (danger >= HOLD_OFF_DANGER) return "danger";
        if (health <= HOLD_OFF_HEALTH) return "hp";
        return null;
    }

    // the hold-off after the last cap: when it fired, whether a mob's hit landed since, and its line's clock
    public static final class HideHold {
        private long capAt = Long.MIN_VALUE;
        private boolean hit;
        private long saidAt = Long.MIN_VALUE;

        // a cap fired now: the hold-off starts (again)
        public void capped(long now) {
            capAt = now;
            hit = false;
            saidAt = Long.MIN_VALUE;
        }

        // a mob's hit landed: the hold-off is over (no matter before a cap)
        public void hit() {
            if (capAt != Long.MIN_VALUE) hit = true;
        }

        public boolean holds(long now, double danger, float health) {
            return holdOff(now, capAt, HOLD_OFF_TICKS, hit, danger, health);
        }

        // why a hide is let through within the hold-off's time, null for none (see holdBroken)
        public String broken(long now, double danger, float health) {
            return holdBroken(now, capAt, HOLD_OFF_TICKS, hit, danger, health);
        }

        // the hold-off's line is due (at most once in HOLD_LINE_TICKS); true marks it said
        public boolean line(long now) {
            if (saidAt != Long.MIN_VALUE && now - saidAt < HOLD_LINE_TICKS) return false;
            saidAt = now;
            return true;
        }

        // whole seconds of the hold-off left (rounded up), 0 when over
        public long secondsLeft(long now) {
            if (capAt == Long.MIN_VALUE) return 0;
            long left = HOLD_OFF_TICKS - (now - capAt);
            return left <= 0 ? 0 : (left + 19) / 20;
        }
    }

    // the nooks barred by a cap: the cells within NOOK_RADIUS of each barred cell (straight line, in three dimensions)
    public static final class Nooks {
        private final java.util.List<int[]> centres = new java.util.ArrayList<>();
        private java.util.Set<Long> keys;

        public void bar(int x, int y, int z) {
            for (int[] c : centres) if (c[0] == x && c[1] == y && c[2] == z) return;
            centres.add(new int[]{x, y, z});
            keys = null;
        }

        public boolean barred(int x, int y, int z) {
            for (int[] c : centres) {
                double dx = x - c[0], dy = y - c[1], dz = z - c[2];
                if (dx * dx + dy * dy + dz * dz <= NOOK_RADIUS * NOOK_RADIUS) return true;
            }
            return false;
        }

        public boolean isEmpty() {
            return centres.isEmpty();
        }

        // every barred cell as BlazeCover.key, for a pick's banned set
        public java.util.Set<Long> keys() {
            if (keys != null) return keys;
            java.util.Set<Long> out = new java.util.HashSet<>();
            int r = (int) Math.floor(NOOK_RADIUS);
            for (int[] c : centres) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (dx * dx + dy * dy + dz * dz <= NOOK_RADIUS * NOOK_RADIUS) out.add(BlazeCover.key(c[0] + dx, c[1] + dy, c[2] + dz));
                        }
                    }
                }
            }
            keys = java.util.Collections.unmodifiableSet(out);
            return keys;
        }
    }

    // the pack line (BlazeFight.notePack) is said again only for a pack of another size or verdict, or seen more than radius
    // from where the last line was said: the check trips each time the count crosses three, 98 lines in twelve minutes from
    // one spot in A:7. lastSize below 0: no line yet
    public static boolean packLine(int size, boolean close, double fromLast, int lastSize, boolean lastClose, double radius) {
        return lastSize < 0 || size != lastSize || close != lastClose || fromLast > radius;
    }
}
