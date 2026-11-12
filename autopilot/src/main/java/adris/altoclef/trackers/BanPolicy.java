package adris.altoclef.trackers;

import adris.altoclef.trackers.Bans.Key;
import adris.altoclef.trackers.Bans.Until;
import baritone.api.utils.Dimension;

import java.util.function.Predicate;

// how long each kind of ban lasts, in one place so "why is it still skipping that" has one file to read. every number is
// game ticks. pure, the callers hand in what they know
public final class BanPolicy {
    // the n-failures rule for blocks (DestroyBlockTask, GetToBlockTask, beds, hay...). 5 minutes is a lot of other blocks,
    // and coming back with a better pick or from far enough away that the chunk reloaded is a new try anyway
    public static final long BLOCK_STRIKES = 6000;
    // the same for mobs and drops. they move, a minute later the river may be between us and somewhere else
    public static final long ENTITY_STRIKES = 1200;
    // the books tasks used to keep for themselves (mining got nowhere, a pickup got nowhere, a bucket's lid would not
    // break). those lived as long as the task object, which is whenever its parent rebuilt it. a minute is about that
    public static final long TASK_GAVE_UP = 1200;
    // a tree we stalled on twice (MineAndCollectTask). five minutes like the block strikes, so the canopy is not back before
    // the trees around it are used up
    public static final long TREE_GAVE_UP = 6000;
    // a liquid pool we missed twice (CollectBucketLiquidTask). five minutes like a tree, so the pool is not back before the
    // other pools around have had their turn
    public static final long BUCKET_POOL_GAVE_UP = 6000;
    // a drop we waited on and never got (DropPatience, a death pile, one stuck in water). items despawn at 5 minutes,
    // so this is "for as long as it exists" without keeping dead ids forever
    public static final long DROP_GAVE_UP = 6000;
    // a drop we could not make room for. a minute later the bag may have changed
    public static final long NO_ROOM = 1200;
    // two path searches for a mob came back empty (CalcFailureWatch). it ends early when the mob hits us
    public static final long NO_PATH = 2400;
    // a foe we stopped waiting on because of lava by it (LavaShore). a minute: it wanders off the shore, the pool does not move
    public static final long LAVA_SHORE = 1200;
    // the neighbours of a block a search proved unreachable (MineAndCollectTask)
    public static final long NO_PATH_VEIN = 600;
    // a coal detour that ran out of time or wandered off. 5 minutes, then the cluster gets one more 30 s try
    public static final long COAL = 6000;

    public static final String COAL_REASON = "coal detour gave up on it";
    // the coal bans are the detour's business only (Bans.banFor): the fuel task, or coal as the head need, still mines them
    public static final String COAL_SCOPE = "the coal detour";
    // same deal for the gravel detour, same 5 minutes. its own scope, a gravel patch we gave up on says nothing about coal.
    // and the flint task (kit head "flint") still digs them
    public static final long GRAVEL = 6000;
    public static final String GRAVEL_REASON = "gravel detour gave up on it";
    public static final String GRAVEL_SCOPE = "the gravel detour";
    // and the diamond detour, the same five minutes. a vein we gave up on says nothing about coal, and the kit's own diamond
    // task (the head need "diamond") still mines it
    public static final long DIAMOND = 6000;
    public static final String DIAMOND_REASON = "diamond detour gave up on it";
    public static final String DIAMOND_SCOPE = "the diamond detour";
    // and the gold detour, same five minutes, its own scope
    public static final long GOLD = 6000;
    public static final String GOLD_REASON = "gold detour gave up on it";
    public static final String GOLD_SCOPE = "the gold detour";
    // and taking things along on the way (TakeAlongChain), any raw item, the same five minutes by default. its own scope: the
    // user task that needs the ore still gets offered it
    public static final long TAKE_ALONG = 6000;
    public static final String TAKE_ALONG_REASON = "the take-along gave up on it";
    public static final String TAKE_ALONG_SCOPE = "the take-along";
    public static final String OUTPOST_REASON = "near a pillager outpost";
    public static final String DEEP_DARK_REASON = "down low or in the deep dark (ancient city / warden)";

    private BanPolicy() {
    }

    // requestBlockUnreachable's rule
    public static boolean blockStrike(Bans bans, Dimension dim, int x, int y, int z, int allowed, double distSq, String reason) {
        return bans.strike(Key.block(dim, x, y, z), reason, allowed, distSq, BLOCK_STRIKES, Until.BETTER_TOOL, Until.CHUNK_RELOAD);
    }

    // requestEntityUnreachable's rule. the better pick part is the old blacklist's, it never made much sense for a cow but
    // it costs nothing either
    public static boolean entityStrike(Bans bans, int id, int allowed, double distSq, String reason) {
        return bans.strike(Key.entity(id), reason, allowed, distSq, ENTITY_STRIKES, Until.BETTER_TOOL, Until.HIT_US);
    }

    // a mob by the lava we will not walk at: banned outright, not a strike (a stall never adds one while we wait)
    public static boolean lavaShore(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "lava by it: " + what, LAVA_SHORE, Until.HIT_US);
    }

    // ---- entities. `what` is EntityTracker.describe, it goes in the line

    // DropPatience ran out: walked at it for 5 s without getting closer, or 30 s in all
    public static boolean dropGaveUp(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "drop never came: " + what, DROP_GAVE_UP);
    }

    // in water and not getting closer (WaterPickupWatchdog)
    public static boolean wetDrop(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "drop in water, not getting closer: " + what, DROP_GAVE_UP);
    }

    // a death pile item RecoverRules.Chase gave up on
    public static boolean recoverGaveUp(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "can't get at death pile item: " + what, DROP_GAVE_UP);
    }

    // the recover's pinned pickup won't touch it (next to lava, out in water), so chasing it is the whole chase for nothing
    public static boolean recoverRefused(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "death pile item the pickup refuses: " + what, DROP_GAVE_UP);
    }

    // touching it and the bag is full of things we keep
    public static boolean noRoom(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "no room for: " + what, NO_ROOM);
    }

    // the pickup task's progress checker ran out on it
    public static boolean pickupStalled(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "pickup got nowhere: " + what, TASK_GAVE_UP);
    }

    // GetToEntityTask: a chase that never left a two block circle (ChaseStallWatch). a minute, then the hunter may try again
    public static boolean chaseStalled(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "chase got nowhere: " + what, TASK_GAVE_UP);
    }

    // CalcFailureWatch: the second empty search. a hit from it proves there is a way
    public static boolean noPath(Bans bans, int id, String what) {
        long keep = "blaze".equals(what) ? blazeKeepTicks() : 0;
        // (Tuning blaze_stand_ban: the stand it was banned from counts too, StandBan)
        if ("blaze".equals(what) && adris.altoclef.util.helpers.StandBan.on()) {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && mc.level != null) {
                adris.altoclef.util.helpers.StandBan.noted(mc.level.getGameTime(), mc.player.getX(), mc.player.getY(), mc.player.getZ());
            }
        }
        return bans.ban(Key.entity(id), "no path to " + what, keep > 0 ? keep : NO_PATH, Until.HIT_US);
    }

    // (Tuning blaze_nopath_keep, seconds, 0 off: a blaze with no path to it stays let go this long, not NO_PATH's 2 min, and
    // its fireball does not lift that (EntityTracker.noteHitUs); one that comes within a bite does (BlazeFight). a blaze
    // hovers where it was, over the lava or past a wall: of 15162 such bans on 11 Nov 13125 ran out and the same blaze was
    // chased again, 8281 times, while the potion minutes went to blazes with no path, E1111-2100.2)
    public static long blazeKeepTicks() {
        return Math.round(adris.altoclef.util.helpers.Tuning.get("blaze_nopath_keep", 0) * 20);
    }

    // ---- blocks

    // MineAndCollectTask's progress checker ran out mid-break or on the way
    public static boolean miningStalled(Bans bans, Dimension dim, int x, int y, int z) {
        return bans.ban(Key.block(dim, x, y, z), "mining got nowhere", TASK_GAVE_UP);
    }

    // MineAndCollectTask: baritone's search for the block came back empty (a minute, same as stalling on it)
    public static boolean noPathBlock(Bans bans, Dimension dim, int x, int y, int z) {
        return bans.ban(Key.block(dim, x, y, z), "no path to it", TASK_GAVE_UP);
    }

    // and the search had looked at everything, so the rest of its vein goes too, but only for half a minute: a door opening or
    // a block placed somewhere is a different world and the vein deserves another look
    public static int noPathVein(Bans bans, Iterable<Key> vein, int x, int y, int z) {
        return bans.banAll(vein, "no path to the vein", NO_PATH_VEIN, "ores of the vein near " + x + " " + y + " " + z);
    }

    // MineAndCollectTask stalled twice on one tree: all its logs are out for five minutes, not the one minute a single log gets.
    // the same canopy came back every minute otherwise. one line for the lot like the outpost does
    public static int treeGaveUp(Bans bans, Iterable<Key> logs, int x, int y, int z) {
        return bans.banAll(logs, "tree stalled twice", TREE_GAVE_UP, "logs of the tree near " + x + " " + y + " " + z);
    }

    // CollectBucketLiquidTask got nowhere near two sources of one pool: every source of it is out. the per source strike
    // needs five on one column and the next pick is always the column next door, so it never landed. one line for the lot
    public static int bucketPoolGaveUp(Bans bans, Iterable<Key> sources, int x, int y, int z) {
        return bans.banAll(sources, "missed the pool twice", BUCKET_POOL_GAVE_UP, "sources of the pool near " + x + " " + y + " " + z);
    }

    // CollectBucketLiquidTask could not break the block over a source
    public static boolean bucketLidStuck(Bans bans, Dimension dim, int x, int y, int z) {
        return bans.ban(Key.block(dim, x, y, z), "couldn't break the block over this source", TASK_GAVE_UP);
    }

    public static boolean coal(Bans bans, int x, int y, int z) {
        return bans.banFor(COAL_SCOPE, Key.block(Dimension.OVERWORLD, x, y, z), COAL_REASON, COAL);
    }

    // what the detour asks: the everybody bans plus its own
    public static boolean coalBanned(Bans bans, int x, int y, int z) {
        return bans.banned(Key.block(Dimension.OVERWORLD, x, y, z), COAL_SCOPE);
    }

    // overworld only like coal, so no dimension to take
    public static boolean diamond(Bans bans, int x, int y, int z) {
        return bans.banFor(DIAMOND_SCOPE, Key.block(Dimension.OVERWORLD, x, y, z), DIAMOND_REASON, DIAMOND);
    }

    public static boolean diamondBanned(Bans bans, int x, int y, int z) {
        return bans.banned(Key.block(Dimension.OVERWORLD, x, y, z), DIAMOND_SCOPE);
    }

    public static boolean gold(Bans bans, int x, int y, int z) {
        return bans.banFor(GOLD_SCOPE, Key.block(Dimension.OVERWORLD, x, y, z), GOLD_REASON, GOLD);
    }

    public static boolean goldBanned(Bans bans, int x, int y, int z) {
        return bans.banned(Key.block(Dimension.OVERWORLD, x, y, z), GOLD_SCOPE);
    }

    public static boolean takeAlong(Bans bans, Dimension dim, int x, int y, int z, long ticks) {
        return bans.banFor(TAKE_ALONG_SCOPE, Key.block(dim, x, y, z), TAKE_ALONG_REASON, ticks);
    }

    public static boolean takeAlongBanned(Bans bans, Dimension dim, int x, int y, int z) {
        return bans.banned(Key.block(dim, x, y, z), TAKE_ALONG_SCOPE);
    }

    // a drop or an animal the take-along gave up on (out of time, led too far, the pickup got nowhere), in the same scope: the
    // user task's own pickup or hunt may still go for it
    public static boolean takeAlongEntity(Bans bans, int id, String what, long ticks) {
        return bans.banFor(TAKE_ALONG_SCOPE, Key.entity(id), TAKE_ALONG_REASON + ": " + what, ticks);
    }

    public static boolean takeAlongEntityBanned(Bans bans, int id) {
        return bans.banned(Key.entity(id), TAKE_ALONG_SCOPE);
    }

    // gravel comes in the nether too (soul sand valleys), so this one takes the dimension
    public static boolean gravel(Bans bans, Dimension dim, int x, int y, int z) {
        return bans.banFor(GRAVEL_SCOPE, Key.block(dim, x, y, z), GRAVEL_REASON, GRAVEL);
    }

    public static boolean gravelBanned(Bans bans, Dimension dim, int x, int y, int z) {
        return bans.banned(Key.block(dim, x, y, z), GRAVEL_SCOPE);
    }

    // DangerFilter: trial chamber furniture, witch hut and outpost tables. they do not stop being traps, so the run
    public static boolean trap(Bans bans, Dimension dim, int x, int y, int z, String what) {
        return bans.ban(Key.block(dim, x, y, z), what, Bans.RUN);
    }

    // DangerFilter: logs and wool by the outpost at (x, z), one pass. for the run, but lifted the moment the outpost goes quiet
    // (liftOutpost). one line for the pass, the already banned ones don't count
    public static int outpost(Bans bans, Iterable<Key> blocks, double x, double z) {
        return bans.banAll(blocks, OUTPOST_REASON, Bans.RUN, "logs/wool near the pillager outpost at " + (int) x + " " + (int) z);
    }

    // DeepDarkRules: wool down low or in the deep dark, one pass, one line. never lifted, the warden is not going anywhere
    public static int deepDarkWool(Bans bans, Iterable<Key> blocks, long x, long z) {
        return bans.banAll(blocks, DEEP_DARK_REASON, Bans.RUN, "wool down low or in the deep dark near " + x + " " + z);
    }

    // an outpost at (x, z) went quiet. its bans go unless `stillCovered` says another live outpost reaches the block.
    // only the outpost bans: a log that was unreachable for its own reasons stays that way
    public static int liftOutpost(Bans bans, double x, double z, double radius, Predicate<Key> stillCovered) {
        return bans.liftAll(OUTPOST_REASON, key -> key.kind() == Bans.Kind.BLOCK && key.dim() == Dimension.OVERWORLD
                && Math.hypot(key.x() - x, key.z() - z) <= radius && !stillCovered.test(key),
                "logs/wool near the pillager outpost at " + (int) x + " " + (int) z + ", it went quiet");
    }
}
