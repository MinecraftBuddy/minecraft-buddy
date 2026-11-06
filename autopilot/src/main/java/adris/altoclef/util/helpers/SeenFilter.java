package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.utils.Dimension;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// "has the player actually seen this block": line of sight from the eyes within a distance, remembered. the gamer uses
// it for its own structure discovery so it does not x-ray through rock. a block only counts as seen once, then it stays
// seen (until the world or the dimension changes)
public final class SeenFilter {
    // about 160 blocks of nether are loaded and a fortress is big enough to recognise from way out, so the sweep legs
    // (14 chunks apart) can lean on this much instead of needing a leg every 9 chunks
    static final double NETHER_RANGE = 128;
    static final double OTHER_RANGE = 128;
    // about a minute of walking past a fortress; the set is cleared when it fills, seen blocks just get seen again
    static final int MEMO_CAP = 50_000;
    // raycasts that may be spent on blocks we do not know yet, per game tick
    static final int RAYS_PER_TICK = 8;
    // a block we could not see is not looked at again for this long (1 s). we move, so it is worth asking again, just not every tick
    static final int MISS_RETRY_TICKS = 20;
    static final int MISS_CAP = 4_000;

    private static final Memo MEMO = new Memo(MEMO_CAP);
    private static final Budget BUDGET = new Budget(RAYS_PER_TICK);
    private static final MissMemo MISSES = new MissMemo(MISS_CAP, MISS_RETRY_TICKS);
    // the level the memo was built in: dimension changes make a new level object and the coordinates mean something else
    private static ClientLevel memoLevel;

    // true once the block was in line of sight within range at some point. unknown = false, ask again next tick
    public static boolean isSeen(AltoClef mod, BlockPos pos) {
        ClientLevel level = mod.getWorld();
        LocalPlayer player = mod.getPlayer();
        if (level == null || player == null) {
            return false;
        }
        if (level != memoLevel) {
            MEMO.clear();
            MISSES.clear();
            memoLevel = level;
        }
        long key = pos.asLong();
        if (MEMO.contains(key)) {
            return true;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 target = Vec3.atCenterOf(pos);
        double range = WorldHelper.getCurrentDimension() == Dimension.NETHER ? NETHER_RANGE : OTHER_RANGE;
        if (eye.distanceToSqr(target) > range * range) {
            return false;
        }
        long now = Minecraft.getInstance().gui.hud.getGuiTicks();
        // asked and missed a moment ago: do not spend a raycast on it again. without this the same eight blocks that
        // are in range but behind rock eat the whole budget every tick and a visible one further down the list starves
        if (MISSES.recentlyMissed(key, now)) {
            return false;
        }
        if (!BUDGET.tryUse(now)) {
            return false;
        }
        if (!clearView(level, player, eye, target, pos)) {
            MISSES.miss(key, now);
            return false;
        }
        MEMO.add(key);
        return true;
    }

    // the clip stops at the first thing that has a collision box. getting to the block itself, to a block touching it
    // (thin things like fences and chests leave room around the centre), or all the way to the centre (blocks with no
    // collision, a portal) is a look at it
    private static boolean clearView(ClientLevel level, LocalPlayer player, Vec3 eye, Vec3 target, BlockPos pos) {
        BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        return isSameOrTouching(hit.getBlockPos(), pos);
    }

    // one block of x-ray leaks through here on purpose: a block directly behind a single other block (a frame under the
    // platform seen through the one stone brick in front of it) counts as seen. requiring an exact hit would fail every
    // fence, chest and portal frame whose centre sits a hair inside the box the clip stops at
    static boolean isSameOrTouching(BlockPos hit, BlockPos pos) {
        int d = Math.abs(hit.getX() - pos.getX()) + Math.abs(hit.getY() - pos.getY()) + Math.abs(hit.getZ() - pos.getZ());
        return d <= 1;
    }

    // forget everything (world leave)
    public static void reset() {
        MEMO.clear();
        MISSES.clear();
        BUDGET.clear();
        memoLevel = null;
    }

    private SeenFilter() {
    }

    // a set of block positions that never holds more than cap of them: when it is full it is emptied, no eviction order to
    // maintain. losing the memo costs a few raycasts, a smarter structure would cost a tick every tick
    static final class Memo {
        private final LongOpenHashSet set = new LongOpenHashSet();
        private final int cap;

        Memo(int cap) {
            this.cap = cap;
        }

        boolean contains(long key) {
            return set.contains(key);
        }

        void add(long key) {
            if (set.size() >= cap) {
                set.clear();
            }
            set.add(key);
        }

        int size() {
            return set.size();
        }

        void clear() {
            set.clear();
        }
    }

    // positions that failed the line of sight test and when. bounded the same way as Memo: emptied when full
    static final class MissMemo {
        private final Long2LongOpenHashMap lastMiss = new Long2LongOpenHashMap();
        private final int cap;
        private final int retryTicks;

        MissMemo(int cap, int retryTicks) {
            this.cap = cap;
            this.retryTicks = retryTicks;
        }

        boolean recentlyMissed(long key, long nowTick) {
            long at = lastMiss.getOrDefault(key, Long.MIN_VALUE);
            return at != Long.MIN_VALUE && nowTick - at < retryTicks;
        }

        void miss(long key, long nowTick) {
            if (lastMiss.size() >= cap) {
                lastMiss.clear();
            }
            lastMiss.put(key, nowTick);
        }

        int size() {
            return lastMiss.size();
        }

        void clear() {
            lastMiss.clear();
        }
    }

    // n uses per tick. the tick number is whatever the caller counts in, a new number refills it
    static final class Budget {
        private final int perTick;
        private long tick = Long.MIN_VALUE;
        private int used;

        Budget(int perTick) {
            this.perTick = perTick;
        }

        boolean tryUse(long nowTick) {
            if (nowTick != tick) {
                tick = nowTick;
                used = 0;
            }
            if (used >= perTick) {
                return false;
            }
            used++;
            return true;
        }

        void clear() {
            tick = Long.MIN_VALUE;
            used = 0;
        }
    }
}
