package adris.altoclef.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// test only: vanilla's concentric ring placement for strongholds (ChunkGeneratorStructureState.generateRingPositions,
// 1.21.4) without the biome shift. java.util.Random is bit for bit the same LCG as LegacyRandomSource for setSeed,
// nextDouble and nextLong, so this reproduces the real unshifted ring points for a seed
final class StrongholdRings {
    record Stronghold(int ring, int chunkX, int chunkZ) {
        // the corner the eye of ender points at
        int cornerX() {
            return chunkX * 16;
        }

        int cornerZ() {
            return chunkZ * 16;
        }
    }

    private static final int DISTANCE = 32;
    private static final int COUNT = 128;
    private static final int SPREAD = 3;

    static List<Stronghold> generate(long seed) {
        Random random = new Random(seed);
        double angle = random.nextDouble() * Math.PI * 2.0;
        int posInCircle = 0;
        int circle = 0;
        int spread = SPREAD;
        List<Stronghold> out = new ArrayList<>(COUNT);
        for (int k = 0; k < COUNT; k++) {
            double dist = (4 * DISTANCE + DISTANCE * circle * 6) + (random.nextDouble() - 0.5) * DISTANCE * 2.5;
            int x = (int) Math.round(Math.cos(angle) * dist);
            int z = (int) Math.round(Math.sin(angle) * dist);
            // the fork eats one nextLong, the biome shift would use it
            random.nextLong();
            out.add(new Stronghold(circle, x, z));
            angle += 2 * Math.PI / spread;
            if (++posInCircle == spread) {
                circle++;
                posInCircle = 0;
                spread += 2 * spread / (circle + 1);
                spread = Math.min(spread, COUNT - k);
                angle += random.nextDouble() * Math.PI * 2.0;
            }
        }
        return out;
    }

    // nearest stronghold start corner to a point, the same pick the eye makes
    static Stronghold nearest(List<Stronghold> all, double x, double z) {
        Stronghold best = null;
        double bestD = Double.MAX_VALUE;
        for (Stronghold s : all) {
            double dx = s.cornerX() - x;
            double dz = s.cornerZ() - z;
            double d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    private StrongholdRings() {
    }
}
