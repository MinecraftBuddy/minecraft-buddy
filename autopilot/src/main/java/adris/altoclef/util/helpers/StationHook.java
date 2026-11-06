package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.function.Function;
import java.util.function.Predicate;

// what the container tasks (alto) can ask about the stations the gamer put down, without knowing there is a gamer. same pattern
// as AsyncSmelting.isOurFurnace: the run wires a Source in when it starts and pulls it when it ends. the container tasks feed what
// it says (and what the block tracker sees) to StationChoice, which is the one place that picks a station. a plain alto run has no
// source: nothing is ours, nothing is coming down, and the nearest reachable station within NEAR is used, else one is placed or crafted.
// plain alto does keep a small book of its own (StationBook) of what its placer reported, record only for now (see ownedWithin)
public final class StationHook {
    // the three workbenches the registry keeps. blast furnaces and the like are the village's, never ours to take back
    public enum Kind {
        TABLE, FURNACE, SMOKER;

        public String word() {
            return switch (this) {
                case TABLE -> "table";
                case FURNACE -> "furnace";
                case SMOKER -> "smoker";
            };
        }
    }

    public interface Source {
        // the closest station of ours of this kind that is standing in this world within `radius` (straight line, height counts) of
        // the point, null when there is none. a station being taken back is not standing any more
        BlockPos standingWithin(Kind kind, double x, double y, double z, double radius);

        // the usual question: within WalkCost.STATION_NEAR
        default BlockPos standingNear(Kind kind, double x, double y, double z) {
            return standingWithin(kind, x, y, z, WalkCost.STATION_NEAR);
        }

        // the closest one that `ok` likes (a split smelt wants the nearest idle furnace, not the nearest furnace). the default only
        // asks about the closest, a source with the whole list (Workbenches) looks further down it
        default BlockPos standingWithin(Kind kind, double x, double y, double z, double radius, Predicate<BlockPos> ok) {
            BlockPos best = standingWithin(kind, x, y, z, radius);
            return best != null && ok.test(best) ? best : null;
        }

        // how many of ours of this kind stand within `radius` that `ok` likes
        default int countWithin(Kind kind, double x, double y, double z, double radius, Predicate<BlockPos> ok) {
            return standingWithin(kind, x, y, z, radius, ok) == null ? 0 : 1;
        }

        // some station of this kind is being picked up right now, so nothing may place or craft another one
        boolean pickingUp(Kind kind);

        // this exact block is the one coming down, no container task may walk to it or open it
        boolean pickingUp(BlockPos pos);

        // the registry has this block, whatever state it is in and however far away. a table the run did not place (a village's)
        // is not ours, so a container task may use it when it is near and nothing ever picks it up
        boolean ours(BlockPos pos);

        // this block is ours and its job went stale and was given up on (Bench.givenUp): what the container tracker still remembers
        // in it is not a load to go back and finish
        boolean givenUp(BlockPos pos);

        // this block is ours and three pickups ran out on time at it (Bench.pickupFailed). the walk back skips it, the planner
        // never counted it either (Workbenches.walkBackTo)
        default boolean parked(BlockPos pos) {
            return false;
        }

        // cobble in the bag that is already owed to something else (the gamer's stone tools). "the bag can make a furnace" only
        // counts what is left over, or 8 cobble for a pick, an axe and a sword reads as a furnace and the far one gets abandoned
        default int cobbleOwed() {
            return 0;
        }

        // the container task that put this station down says so, instead of the book guessing from a block event and how far away we
        // stood. the same spot twice (reported and heard) is one entry, the book keys on kind + dimension + position
        default void placed(Kind kind, BlockPos pos, String dimension, long now) {
        }

        // a container task is working at or walking to exactly this station, said every tick it does (a short lease: the book treats
        // the station as in use for a little while after the last one, so a tick with an empty task tree does not read as "done")
        default void touch(Kind kind, BlockPos pos, String dimension, long now) {
        }
    }

    private static volatile Source source;

    private StationHook() {
    }

    public static void install(Source s) {
        source = s;
    }

    public static void clear() {
        source = null;
    }

    // which workbench a container task is for, null for the ones we do not keep track of (chests, anvils, a blast furnace)
    public static Kind kindOf(Block[] blocks) {
        if (blocks == null || blocks.length != 1) {
            return null;
        }
        Block block = blocks[0];
        if (block == Blocks.CRAFTING_TABLE) {
            return Kind.TABLE;
        }
        if (block == Blocks.FURNACE) {
            return Kind.FURNACE;
        }
        return block == Blocks.SMOKER ? Kind.SMOKER : null;
    }

    public static BlockPos standingNear(Kind kind, double x, double y, double z) {
        Source s = source;
        return s == null || kind == null ? null : s.standingNear(kind, x, y, z);
    }

    public static BlockPos standingWithin(Kind kind, double x, double y, double z, double radius) {
        Source s = source;
        return s == null || kind == null ? null : s.standingWithin(kind, x, y, z, radius);
    }

    public static BlockPos standingWithin(Kind kind, double x, double y, double z, double radius, Predicate<BlockPos> ok) {
        Source s = source;
        return s == null || kind == null ? null : s.standingWithin(kind, x, y, z, radius, ok);
    }

    public static int countWithin(Kind kind, double x, double y, double z, double radius, Predicate<BlockPos> ok) {
        Source s = source;
        return s == null || kind == null ? 0 : s.countWithin(kind, x, y, z, radius, ok);
    }

    // a run that keeps its stations here (the gamer). plain alto has none, and nothing is split for it (SmeltSplit)
    public static boolean installed() {
        return source != null;
    }

    public static boolean pickingUp(Kind kind) {
        Source s = source;
        return s != null && kind != null && s.pickingUp(kind);
    }

    public static boolean pickingUp(BlockPos pos) {
        Source s = source;
        return s != null && pos != null && s.pickingUp(pos);
    }

    // no source (a plain alto run) means nothing is ours: every table the tracker sees within NEAR is a world one, and the choice
    // (StationChoice) uses the nearest reachable one, else places or crafts
    public static boolean ours(BlockPos pos) {
        Source s = source;
        return s != null && pos != null && s.ours(pos);
    }

    public static boolean givenUp(BlockPos pos) {
        Source s = source;
        return s != null && pos != null && s.givenUp(pos);
    }

    public static boolean parked(BlockPos pos) {
        Source s = source;
        return s != null && pos != null && s.parked(pos);
    }

    // a block a station placer must never mine to make room: one of ours (the table the furnace was just crafted at is the usual
    // victim), or any table, furnace or smoker at all. a crafting table has no block entity, so the carve's "somebody's" test let
    // it through
    public static boolean keepStanding(BlockPos pos, Block block) {
        return ours(pos) || block == Blocks.CRAFTING_TABLE || block == Blocks.FURNACE || block == Blocks.SMOKER;
    }

    // plain alto's own small book (StationBook): what the placer reported and which station a task is working at. it never replaces the
    // run's registry, the source wins whenever one is installed. record only unless plainAnswers is turned on, see ownedWithin
    private static final StationBook plain = new StationBook();
    private static volatile boolean plainAnswers = false;

    // our placer finished a station and the block is in the world. with a run installed it goes to the registry, without one into
    // plain alto's book
    public static void reportPlaced(Kind kind, BlockPos pos, String dimension, long now) {
        if (kind == null || pos == null || dimension == null) {
            return;
        }
        Source s = source;
        if (s != null) {
            s.placed(kind, pos.immutable(), dimension, now);
        } else {
            plain.report(kind, pos, dimension, now);
            plain.touch(kind, pos, dimension, now);
        }
    }

    // a container task is working at / walking to this station right now, called every tick it is. a station the book does not know
    // (a village's) is ignored
    public static void touch(Kind kind, BlockPos pos, String dimension, long now) {
        if (kind == null || pos == null || dimension == null) {
            return;
        }
        Source s = source;
        if (s != null) {
            s.touch(kind, pos, dimension, now);
        } else {
            plain.touch(kind, pos, dimension, now);
        }
    }

    // "the closest station of ours of this kind within `radius`" for the container task's choice. with a run installed this is exactly
    // the registry's standingWithin. without one it asks plain alto's book, but only when plainAnswers is on (off: null, plain alto
    // behaves as it always did and the book is record only). what turning it on changes is listed in the commit that added the book:
    // StationChoice sees the bot's own tables as OURS, so they go ahead of a nearer village table within NEAR, and are walked back to
    // (to WALK_BACK, to the forget line with nothing to make one from) instead of placing or crafting another
    public static BlockPos ownedWithin(Kind kind, String dimension, double x, double y, double z, double radius, Predicate<BlockPos> ok,
                                       Function<BlockPos, StationBook.Standing> standing) {
        if (kind == null) {
            return null;
        }
        Source s = source;
        if (s != null) {
            return s.standingWithin(kind, x, y, z, radius, ok);
        }
        return plainAnswers ? plain.nearest(kind, dimension, x, y, z, radius, ok, standing) : null;
    }

    // plain alto's book, for the tests and for whoever flips plainAnswers
    static StationBook plainBook() {
        return plain;
    }

    // test hook, and the one switch for the plain book's answers
    static void plainAnswers(boolean on) {
        plainAnswers = on;
    }

    // the world was left: what the book remembers belongs to a world that is gone. (a proxy switch with no leave keeps the old
    // entries, harmless while the book only records)
    public static void clearPlain() {
        plain.clear();
    }

    // plain alto owes nothing, all of it can go into a furnace
    public static int cobbleOwed() {
        Source s = source;
        return s == null ? 0 : s.cobbleOwed();
    }
}
