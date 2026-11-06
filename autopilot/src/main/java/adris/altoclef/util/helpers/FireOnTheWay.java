package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasksystem.Task;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

// the buddy's fork: a fire between us and a blaze fight's corner, put out before the walk (the pathfinder never walks through
// fire, and a fireball's fire in the door left the run to the corner without a path, corner17 run 4). one fire gets
// GIVE_UP_TICKS, then it is left alone for BAN_TICKS: a put out that did not take kept the buddy 120 ticks at the door in the
// blazes' fire (corner17 run 7). one per user, the task object kept while it is the same fire
public final class FireOnTheWay {
    static final long GIVE_UP_TICKS = 40;
    static final long BAN_TICKS = 200;

    private final Map<BlockPos, Long> banned = new HashMap<>();
    private BlockPos at;
    private long since;
    private Task task;

    public Task step(AltoClef mod, BlockPos to) {
        long now = mod.getWorld().getGameTime();
        banned.values().removeIf(t -> now < t || now - t > BAN_TICKS);
        BlockPos f = BlazeShelter.fireAhead(mod.getWorld(), mod.getPlayer().position(), to, banned.keySet());
        if (f == null) {
            at = null;
            task = null;
            return null;
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
