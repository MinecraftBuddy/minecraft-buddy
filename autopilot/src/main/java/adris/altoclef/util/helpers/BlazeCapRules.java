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
//   the camp's corner (or standing pillbox) is dropped and the camp picked again without it.
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

    // the pack line (BlazeFight.notePack) is said again only for a pack of another size or verdict, or seen more than radius
    // from where the last line was said: the check trips each time the count crosses three, 98 lines in twelve minutes from
    // one spot in A:7. lastSize below 0: no line yet
    public static boolean packLine(int size, boolean close, double fromLast, int lastSize, boolean lastClose, double radius) {
        return lastSize < 0 || size != lastSize || close != lastClose || fromLast > radius;
    }
}
