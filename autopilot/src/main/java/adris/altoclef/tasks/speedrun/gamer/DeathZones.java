package adris.altoclef.tasks.speedrun.gamer;

import java.util.ArrayList;
import java.util.List;

// where we died in the nether lately and something walked us into it, as spots for the spawner walk to keep off. pure
// numbers over the death book so a test needs no minecraft
public final class DeathZones {

    // game ticks, fifteen minutes. longer than a run zone: nobody cleared the room, we just died in it
    public static final long MAX_AGE_TICKS = 15 * 60 * 20L;
    public static final double RADIUS = 12;

    private DeathZones() {
    }

    // {x, z} of every death in the nether that was not lava or the void (those are about the spot's floor, not what lives
    // there) and is younger than MAX_AGE_TICKS. a null cause is an old save, which is just "unknown", so it counts
    public static List<double[]> spots(List<RunState.Death> deaths, long now) {
        List<double[]> out = new ArrayList<>();
        for (RunState.Death death : deaths) {
            if (!"NETHER".equals(death.dimension)) continue;
            if ("lava".equals(death.cause) || "void".equals(death.cause)) continue;
            // (a death from the future is another world's clock)
            if (death.gameTime > now || now - death.gameTime >= MAX_AGE_TICKS) continue;
            out.add(new double[]{death.x, death.z});
        }
        return out;
    }
}
