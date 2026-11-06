package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

// where a table, furnace or smoker comes from, in one place. the container tasks used to answer this four different ways (a walk
// priced with baritone's heuristic against 0 / 10 / 100 / infinity, a stretched budget for ours, a 75 for smokers, a 6 block
// "we are standing at the one we just placed") and the answers disagreed. now it is one list and one distance:
//   a station we are in the middle of using (our ore in it)  >  ours standing within NEAR  >  a world one within NEAR
//   (a village's, a ruined portal's: never ours, never picked up)  >  the one in the bag  >  craft a new one
// a world one within NEAR goes ahead of the bag on purpose: it costs nothing and leaves nothing standing to take back. NEAR is
// WalkCost.STATION_NEAR, a straight line with the height counted. pure so the rules are tested without a game, the container
// task feeds it what the tracker and the registry (StationHook) can see
public final class StationChoice {
    // the one we are walking to keeps its place this far past the line, or a station sitting right on it trades places every time
    // a step moves us across
    public static final double HOLD = 2.0;
    // a table, furnace or smoker of ours this close (straight line) is walked back to even when the bag could make another. making a
    // furnace is 8 cobble and a table and a craft, and either way the old one sits out there until something walks back for it anyway, which is the
    // same walk plus a pickup. past this the new one wins if the bag has what it takes
    public static final double WALK_BACK = 48.0;

    // strongest first
    public enum Role {
        // holds our items (a half loaded furnace) or is the one we were sent to: used from anywhere, never left for a new one
        PINNED,
        // the run's registry (or the block our own placer just put down) says it is ours
        OURS,
        // standing in the world and nobody's we know of
        WORLD
    }

    public enum Use {
        // walk to the pick
        OURS,
        WORLD,
        // put the one in the bag down
        BAG,
        // get one (craft it) and put it down
        MAKE,
        // nothing to use and nothing we may make
        NONE
    }

    // `key` is whatever the caller names a station by (a BlockPos), `distance` the straight line from us to its middle
    public record Candidate<T>(T key, double distance, Role role) {
    }

    public record Pick<T>(Use use, T key) {
        public boolean walks() {
            return use == Use.OURS || use == Use.WORLD;
        }
    }

    private StationChoice() {
    }

    public static <T> Pick<T> decide(Collection<Candidate<T>> seen, T previous, boolean inBag, boolean mayMake, double worldReach) {
        return decide(seen, previous, inBag, mayMake, true, worldReach);
    }

    // `previous` is the station we were heading for last tick (null for none), `inBag` we hold the item, `mayMake` the task is
    // allowed to place or craft one at all (a blast furnace somebody sent us to is not), `canMakeNow` the bag holds what making one
    // takes (canMakeFrom). `worldReach`: how far a world one is worth the walk, NEAR for a table or furnace, more for a station that
    // costs a pile of iron to make
    public static <T> Pick<T> decide(Collection<Candidate<T>> seen, T previous, boolean inBag, boolean mayMake, boolean canMakeNow,
                                     double worldReach) {
        return decide(seen, previous, inBag, mayMake, canMakeNow, worldReach, WalkCost.STATION_NEAR);
    }

    // `walkBack`: how far ours is worth walking to when the bag could make another one (walkBackReach). with nothing to make one
    // from it is the forget line whatever this says
    public static <T> Pick<T> decide(Collection<Candidate<T>> seen, T previous, boolean inBag, boolean mayMake, boolean canMakeNow,
                                     double worldReach, double walkBack) {
        List<Candidate<T>> merged = merge(seen);
        Candidate<T> best = nearest(merged, Role.PINNED, previous, Double.POSITIVE_INFINITY);
        if (best != null) {
            return new Pick<>(Use.OURS, best.key());
        }
        best = nearest(merged, Role.OURS, previous, WalkCost.STATION_NEAR);
        if (best != null) {
            return new Pick<>(Use.OURS, best.key());
        }
        best = nearest(merged, Role.WORLD, previous, worldReach);
        if (best != null) {
            return new Pick<>(Use.WORLD, best.key());
        }
        if (!mayMake) {
            return new Pick<>(Use.NONE, null);
        }
        // the one in the bag goes down right here, cheaper than any walk
        if (!inBag) {
            // making one means mining 8 cobble (and logs, for a smoker) first, which is a trip of its own and usually a longer one
            // than walking back to a station that still stands. ours first, then anybody's, out to the forget line. with the
            // stuff in the bag ours still gets its walkBack
            best = nearest(merged, Role.OURS, previous, oursReach(canMakeNow, walkBack));
            if (best != null) {
                return new Pick<>(Use.OURS, best.key());
            }
            if (!canMakeNow) {
                best = nearest(merged, Role.WORLD, previous, WalkCost.STATION_FORGET);
                if (best != null) {
                    return new Pick<>(Use.WORLD, best.key());
                }
            }
        }
        return new Pick<>(inBag ? Use.BAG : Use.MAKE, null);
    }

    // how far ours is worth walking to when the bag could make another: WALK_BACK for all three, NEAR (no extra walk) for the things
    // the registry does not keep. a table is only 4 planks, but a second one means the first sits out there until something walks
    // back to pick it up, and the planner budgeting those planks with ours 22 blocks off made the log need come and go with every step
    public static double walkBackReach(StationHook.Kind kind) {
        return kind == null ? WalkCost.STATION_NEAR : WALK_BACK;
    }

    // the whole walk-back line in one number, for decide and for the planner (which has to count the same far station as held):
    // walkBack when the bag could make one, the forget line when it can't
    public static double oursReach(boolean canMakeNow, double walkBack) {
        return canMakeNow ? walkBack : WalkCost.STATION_FORGET;
    }

    public static double oursReach(StationHook.Kind kind, boolean canMakeNow) {
        return oursReach(canMakeNow, walkBackReach(kind));
    }

    // the bag holds what one more of this station takes (a table to craft it at aside, that is cheap): a table is 4 planks or a log,
    // a furnace 8 cobble, a smoker a furnace (or the 8 cobble for it) and 4 logs
    public static boolean canMakeFrom(StationHook.Kind kind, int cobble, int logs, int planks, int furnaces) {
        if (kind == null) {
            return true;
        }
        return switch (kind) {
            case TABLE -> planks >= 4 || logs >= 1;
            case FURNACE -> cobble >= 8;
            case SMOKER -> logs >= 4 && (furnaces >= 1 || cobble >= 8);
        };
    }

    // the same block can come in twice (the registry and the tracker both know it), the stronger role is the one that counts
    private static <T> List<Candidate<T>> merge(Collection<Candidate<T>> seen) {
        List<Candidate<T>> out = new ArrayList<>();
        outer:
        for (Candidate<T> c : seen) {
            for (int i = 0; i < out.size(); i++) {
                if (Objects.equals(out.get(i).key(), c.key())) {
                    if (c.role().ordinal() < out.get(i).role().ordinal()) {
                        out.set(i, c);
                    }
                    continue outer;
                }
            }
            out.add(c);
        }
        return out;
    }

    // the closest of this role inside the line, the one we were heading for staying put unless another is clearly closer
    // (twice as close, same rule as MineStick.clearlyCloser)
    private static <T> Candidate<T> nearest(List<Candidate<T>> merged, Role role, T previous, double limit) {
        Candidate<T> best = null;
        Candidate<T> kept = null;
        for (Candidate<T> c : merged) {
            if (c.role() != role) {
                continue;
            }
            boolean isPrevious = previous != null && previous.equals(c.key());
            if (c.distance() > (isPrevious ? limit + HOLD : limit)) {
                continue;
            }
            if (isPrevious) {
                kept = c;
            }
            if (best == null || c.distance() < best.distance()) {
                best = c;
            }
        }
        if (best == null || kept == null || best == kept) {
            return best;
        }
        return MineStick.clearlyCloser(best.distance() * best.distance(), kept.distance() * kept.distance()) ? best : kept;
    }
}
