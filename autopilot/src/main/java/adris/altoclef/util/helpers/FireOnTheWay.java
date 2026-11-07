package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasksystem.Task;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

// the buddy's fork: a fire between us and a blaze fight's corner, put out before the walk (the pathfinder never walks through
// fire, and a fireball's fire in the door left the run to the corner without a path, corner17 run 4). one fire gets
// GIVE_UP_TICKS, then it is left alone for BAN_TICKS: a put out that did not take kept the buddy 120 ticks at the door in the
// blazes' fire (corner17 run 7). one per user, the task object kept while it is the same fire
public final class FireOnTheWay {
    static final long GIVE_UP_TICKS = 40;
    static final long BAN_TICKS = 200;
    // fr_skip_fires: under fire resistance with this many ticks left no fire is put out. blazes light fires as fast as they go
    // out, so a walk could stall for minutes on fires that do no harm while the potion holds; with less left, the fire would
    // still burn when the potion is gone, so it is put out as before
    static final int SKIP_MIN_TICKS = 200;
    static final long SKIP_LOG_TICKS = 200;

    private final Map<BlockPos, Long> banned = new HashMap<>();
    private BlockPos at;
    private long since;
    private Task task;
    private long skipLoggedAt = Long.MIN_VALUE / 2;

    // whether to leave the fires alone: the switch on and the potion with at least SKIP_MIN_TICKS left
    static boolean skipFires(double skipSwitch, int fireResTicksLeft) {
        return skipSwitch > 0 && fireResTicksLeft >= SKIP_MIN_TICKS;
    }

    // the ticks left of fire resistance, 0 without it (or with ignore_fire_resistance, FireRes.has), MAX_VALUE for an endless one
    private static int fireResTicksLeft(AltoClef mod) {
        if (!FireRes.has(mod.getPlayer())) return 0;
        MobEffectInstance fx = mod.getPlayer().getEffect(MobEffects.FIRE_RESISTANCE);
        if (fx == null) return 0;
        return fx.isInfiniteDuration() ? Integer.MAX_VALUE : fx.getDuration();
    }

    public Task step(AltoClef mod, BlockPos to) {
        long now = mod.getWorld().getGameTime();
        banned.values().removeIf(t -> now < t || now - t > BAN_TICKS);
        BlockPos f = BlazeShelter.fireAhead(mod.getWorld(), mod.getPlayer().position(), to, banned.keySet());
        if (f == null) {
            at = null;
            task = null;
            return null;
        }
        double skipSwitch = Tuning.get("fr_skip_fires", 0);
        if (skipSwitch > 0) {
            int left = fireResTicksLeft(mod);
            if (skipFires(skipSwitch, left)) {
                // walked on as with no fire ahead: the caller's path goes on, and a fire met after the potion starts afresh
                if (now - skipLoggedAt >= SKIP_LOG_TICKS || now < skipLoggedAt) {
                    skipLoggedAt = now;
                    String s = left == Integer.MAX_VALUE ? "endless" : (left / 20) + " s left";
                    Debug.logInternal("fire on the way: skipped, fire resistance " + s);
                }
                at = null;
                task = null;
                return null;
            }
        }
        if (!f.equals(at)) {
            at = f;
            since = now;
            task = new PutOutFireTask(f);
            Debug.logInternal("rods: a fire on the way to the corner at " + f.toShortString() + ", out first");
        }
        if (now - since > GIVE_UP_TICKS) {
            banned.put(f, now);
            Debug.logInternal("rods: the fire at " + f.toShortString() + " did not go out in " + GIVE_UP_TICKS + " ticks, left alone");
            at = null;
            task = null;
            return null;
        }
        return task;
    }
}
