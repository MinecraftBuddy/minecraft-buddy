package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.Locale;

// small pure questions about the End that both handlers and EndGear ask, all over RunState
public final class EndRules {
    // end stone for the stand's floor comes from around here, the bot isn't leaving the fountain for it
    public static final int END_STONE_RANGE = 12;
    public static final int END_STONE_DROP = 6;
    public static final int END_STONE_RISE = 3;

    private EndRules() {
    }

    // offsets from our feet
    public static boolean endStoneClose(int dx, int dy, int dz) {
        return Math.abs(dx) <= END_STONE_RANGE && Math.abs(dz) <= END_STONE_RANGE && dy >= -END_STONE_DROP && dy <= END_STONE_RISE;
    }

    // RunState.Death.dimension is whatever the engine wrote ("END", "the_end", "minecraft:the_end"), none of the others has "end" in it
    public static boolean isEnd(String dimension) {
        return dimension != null && dimension.toUpperCase(Locale.ROOT).contains("END");
    }

    // counts every End death the run ever had: a #gamer resume after "the dragon killed us N times" goes straight back to
    // STUCK from the overworld, `#gamer phase end_prep` after clearing deaths (or `#gamer reset`) is the way out
    public static int endDeaths(RunState state) {
        int deaths = 0;
        for (RunState.Death death : state.deaths) {
            if (isEnd(death.dimension)) {
                deaths++;
            }
        }
        return deaths;
    }

    // null when we never died there
    public static RunState.Death lastEndDeath(RunState state) {
        RunState.Death last = null;
        for (RunState.Death death : state.deaths) {
            if (isEnd(death.dimension) && (last == null || death.gameTime >= last.gameTime)) {
                last = death;
            }
        }
        return last;
    }

    public static boolean attemptsLeft(RunState state, EndConfig cfg) {
        return endDeaths(state) < cfg.attempts;
    }

    // gear lying in the End only counts while it has not despawned. no death there = nothing is lying there,
    // whatever the cache says (it is only ever filled from the End, but a stale file should not talk us out of crafting)
    public static boolean dropsStillThere(RunState state, long gameTime, EndConfig cfg) {
        RunState.Death death = lastEndDeath(state);
        return death != null && gameTime - death.gameTime < cfg.dropLifetimeTicks;
    }

    public static String key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    public static int dropped(RunState state, long gameTime, EndConfig cfg, Item... items) {
        if (!dropsStillThere(state, gameTime, cfg)) {
            return 0;
        }
        int total = 0;
        for (Item item : items) {
            total += state.endDrops.getOrDefault(key(item), 0);
        }
        return total;
    }
}
