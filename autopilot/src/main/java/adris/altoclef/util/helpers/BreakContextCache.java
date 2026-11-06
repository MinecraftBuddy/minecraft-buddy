package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.altoclef.AltoClefSettings;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

// canBreak gets asked about every cached ore position every tick and a CalculationContext is a tool set, a bsi, a pile of
// settings reads and three inventory scans. 25 to 300 of those per tick was not a good time. one per tick is plenty
final class BreakContextCache {
    private static CalculationContext context;
    private static int tick = Integer.MIN_VALUE;
    private static Level world;
    private static Player player;
    // the rules snapshot gets replaced whenever somebody changes an avoid/force rule, so a stale one means rebuild
    private static AltoClefSettings.Snapshot rules;

    private BreakContextCache() {
    }

    // main thread only, like everything else that calls canBreak
    static CalculationContext get(AltoClef mod) {
        int now = WorldHelper.getTicks();
        Level w = mod.getWorld();
        Player p = mod.getPlayer();
        AltoClefSettings alto = mod.getExtraBaritoneSettings();
        AltoClefSettings.Snapshot r = alto.snapshot();
        if (context != null && tick == now && world == w && player == p && rules == r) {
            return context;
        }
        // JANK: build it with interactions unpaused, same as canBreak always did. the constructor reads the pause flag
        // for hasThrowaway (placing, not breaking) but we want the answer we always got, so same toggle, same answer
        boolean prevInteractionPaused = alto.isInteractionPaused();
        alto.setInteractionPaused(false);
        try {
            context = new CalculationContext(mod.getClientBaritone());
        } finally {
            alto.setInteractionPaused(prevInteractionPaused);
        }
        tick = now;
        world = w;
        player = p;
        rules = r;
        return context;
    }
}
