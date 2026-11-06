package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// the buddy's fork: the rods that lay on the ground and were gone without coming into the bag (4 Nov 2026: in corner14 the
// drops stayed on the ground and could despawn in the end). each tick the rods lying where the client
// sees them are counted; what left the ground since the last tick (new drops in, the ground's count out) and did not come into
// the bag is lost: despawned by its age, burned in lava or fire, else gone. one line a rod, which the drills count
// (drillrun.py, rods_lost). a stack that merges into another leaves no gap in the count, and a rod out of view is no loss
final class RodWatch {

    // the server takes an item at 6000 ticks; the client counts from when it first saw it, so a little short of that
    static final int DESPAWN_AGE = 5800;
    // a rod that vanished this far from us went out of view, it is not lost
    static final double VIEW = 48;

    private record Seen(int count, BlockPos at, int age, double distance) {
    }

    private Map<Integer, Seen> lying = new HashMap<>();
    private int lastHeld = -1;
    private int lost;

    // rods that left the ground and did not come into the bag: the new drops' rods plus what lay before, less what lies now and
    // what the bag gained. never below 0 (a rod picked up and dropped in one tick, or one given by a command)
    static int lostRods(int before, int fresh, int now, int bagGained) {
        return Math.max(0, before + fresh - now - Math.max(0, bagGained));
    }

    void tick(AltoClef mod) {
        var player = mod.getPlayer();
        if (player == null || mod.getWorld() == null) return;
        // (dead: the bag's rods fall out and are no loss of the fight's; the count starts over after the respawn)
        if (!player.isAlive()) {
            lastHeld = -1;
            lying = new HashMap<>();
            return;
        }
        int held = mod.getItemStorage().getItemCount(Items.BLAZE_ROD);
        Map<Integer, Seen> now = new HashMap<>();
        for (Entity e : mod.getWorld().entitiesForRendering()) {
            if (e instanceof ItemEntity item && item.isAlive() && item.getItem().is(Items.BLAZE_ROD)) {
                now.put(item.getId(), new Seen(item.getItem().getCount(), item.blockPosition(), item.getAge(), item.distanceTo(player)));
            }
        }
        if (lastHeld >= 0) {
            int before = 0, fresh = 0, ground = 0, outOfView = 0;
            List<Seen> vanished = new ArrayList<>();
            for (var e : lying.entrySet()) {
                before += e.getValue().count();
                if (!now.containsKey(e.getKey())) {
                    if (e.getValue().distance() > VIEW) outOfView += e.getValue().count();
                    else vanished.add(e.getValue());
                }
            }
            for (var e : now.entrySet()) {
                ground += e.getValue().count();
                if (!lying.containsKey(e.getKey())) fresh += e.getValue().count();
            }
            int n = lostRods(before - outOfView, fresh, ground, held - lastHeld);
            // (the causes from the stacks that vanished, the clearest first: their age, then where they lay)
            vanished.sort((a, b) -> Integer.compare(rank(mod, b), rank(mod, a)));
            for (int i = 0; i < n; i++) {
                Seen s = i < vanished.size() ? vanished.get(i) : null;
                lost++;
                Debug.logInternal("rods: a rod lost (" + (s == null ? "gone" : cause(mod, s)) + ")" + (s == null ? "" : " at " + s.at().toShortString()
                        + ", age " + s.age()) + ", " + lost + " so far");
            }
        }
        lying = now;
        lastHeld = held;
    }

    private static int rank(AltoClef mod, Seen s) {
        return switch (cause(mod, s)) {
            case "despawned" -> 2;
            case "burned" -> 1;
            default -> 0;
        };
    }

    private static String cause(AltoClef mod, Seen s) {
        if (s.age() >= DESPAWN_AGE) return "despawned";
        BlockState b = mod.getWorld().getBlockState(s.at());
        if (b.is(Blocks.LAVA) || b.is(Blocks.FIRE) || b.is(Blocks.SOUL_FIRE) || !b.getFluidState().isEmpty()) return "burned";
        return "gone";
    }
}
