package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.StatsAccess;
import adris.altoclef.ui.gui.kit.Safe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// the vanilla statistics, read straight off the client's own mirror of them. the same numbers the pause screen shows,
// so "blocks mined" here is exactly what the game has been counting. distances come in centimetres and get turned
// into the biggest unit that still means something
public final class McStats implements StatsAccess {

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    @Override
    public List<Stat> snapshot() {
        List<Stat> out = new ArrayList<>();
        try {
            LocalPlayer p = mc().player;
            if (p == null) {
                return out;
            }
            StatsCounter s = p.getStats();
            distance(out, "Distance walked", s, Stats.WALK_ONE_CM, Stats.SPRINT_ONE_CM, Stats.CROUCH_ONE_CM);
            distance(out, "By boat", s, Stats.BOAT_ONE_CM);
            distance(out, "By minecart", s, Stats.MINECART_ONE_CM);
            distance(out, "By elytra", s, Stats.AVIATE_ONE_CM);
            distance(out, "By horse", s, Stats.HORSE_ONE_CM);
            distance(out, "Fallen", s, Stats.FALL_ONE_CM);
            out.add(new Stat("Blocks mined", count(blocksMined(s))));
            out.add(new Stat("Items crafted", count(itemsSum(s, Stats.ITEM_CRAFTED))));
            out.add(new Stat("Items used", count(itemsSum(s, Stats.ITEM_USED))));
            out.add(new Stat("Mobs killed", count(s.getValue(Stats.CUSTOM, Stats.MOB_KILLS))));
            out.add(new Stat("Players killed", count(s.getValue(Stats.CUSTOM, Stats.PLAYER_KILLS))));
            out.add(new Stat("Deaths", count(s.getValue(Stats.CUSTOM, Stats.DEATHS))));
            out.add(new Stat("Jumps", count(s.getValue(Stats.CUSTOM, Stats.JUMP))));
            out.add(new Stat("Fish caught", count(s.getValue(Stats.CUSTOM, Stats.FISH_CAUGHT))));
            out.add(new Stat("Damage dealt", count(s.getValue(Stats.CUSTOM, Stats.DAMAGE_DEALT))));
            out.add(new Stat("Time played", time(s.getValue(Stats.CUSTOM, Stats.TOTAL_WORLD_TIME))));
        } catch (Throwable t) {
            Safe.report("stats snapshot", t);
        }
        return out;
    }

    private void distance(List<Stat> out, String label, StatsCounter s, net.minecraft.resources.Identifier... keys) {
        long cm = 0;
        for (net.minecraft.resources.Identifier k : keys) {
            cm += s.getValue(Stats.CUSTOM, k);
        }
        out.add(new Stat(label, formatDistance(cm)));
    }

    private static long blocksMined(StatsCounter s) {
        long total = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            total += s.getValue(Stats.BLOCK_MINED, block);
        }
        return total;
    }

    private static long itemsSum(StatsCounter s, net.minecraft.stats.StatType<net.minecraft.world.item.Item> type) {
        long total = 0;
        for (net.minecraft.stats.Stat<net.minecraft.world.item.Item> stat : type) {
            total += s.getValue(stat);
        }
        return total;
    }

    // cm -> m, then km once it gets silly. 10 000 cm = 100 m, one decimal is plenty for a party trick number
    private static String formatDistance(long cm) {
        if (cm <= 0) {
            return count(0);
        }
        double m = cm / 100.0;
        if (m >= 1000) {
            return String.format(Locale.ROOT, "%.1f km", m / 1000.0);
        }
        if (m >= 10) {
            return String.format(Locale.ROOT, "%.0f m", m);
        }
        return String.format(Locale.ROOT, "%.1f m", m);
    }

    // ticks -> a rough "12 h 34 m" or "5 m 12 s", whatever is not zero
    private static String time(int ticks) {
        long seconds = ticks / 20;
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    private static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
