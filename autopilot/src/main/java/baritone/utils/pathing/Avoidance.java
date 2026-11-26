/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils.pathing;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Avoidance {

    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final double coefficient;
    private final int radius;
    private final int radiusSq;

    public Avoidance(BlockPos center, double coefficient, int radius) {
        this(center.getX(), center.getY(), center.getZ(), coefficient, radius);
    }

    public Avoidance(int centerX, int centerY, int centerZ, double coefficient, int radius) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.coefficient = coefficient;
        this.radius = radius;
        this.radiusSq = radius * radius;
    }

    public double coefficient(int x, int y, int z) {
        int xDiff = x - centerX;
        int yDiff = y - centerY;
        int zDiff = z - centerZ;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff <= radiusSq ? coefficient : 1.0D;
    }

    // (avoid_crowd_cap) the most a crowd's avoidance multiplies a block's cost by: ten mobs' worth at the coefficient 2
    static final double CROWD_CAP = 1024;

    static boolean crowdCapOn() {
        return adris.altoclef.util.helpers.Tuning.get("avoid_crowd_cap", 0) > 0;
    }

    // (Tuning fireproof_blaze_avoid, off) whether the blazes are left out of the avoidance: the switch on, fire resistance on
    // the buddy and more than its last 30 s of it left (FireRes.endingSoon), so the avoidance is back before the effect ends
    static boolean blazesFree(boolean on, boolean fireRes, boolean endingSoon) {
        return on && fireRes && !endingSoon;
    }

    // a line when the blazes are left out, at most one in 30 s (a search builds the avoidance anew each time)
    private static long blazesFreeNoted = Long.MIN_VALUE;

    private static void noteBlazesFree(IPlayerContext ctx) {
        long now = System.currentTimeMillis();
        if (blazesFreeNoted != Long.MIN_VALUE && now - blazesFreeNoted < 30_000) {
            return;
        }
        blazesFreeNoted = now;
        long n = ctx.entitiesStream().filter(e -> e instanceof net.minecraft.world.entity.monster.Blaze).count();
        adris.altoclef.Debug.logInternal("avoidance: fire resistance holds, the blazes left out of the path's avoidance (" + n
                + " about) (fireproof_blaze_avoid)");
    }

    public static List<Avoidance> create(IPlayerContext ctx) {
        if (!Baritone.settings().avoidance.value) {
            return Collections.emptyList();
        }
        List<Avoidance> res = new ArrayList<>();
        double mobSpawnerCoeff = Baritone.settings().mobSpawnerAvoidanceCoefficient.value;
        double mobCoeff = Baritone.settings().mobAvoidanceCoefficient.value;
        if (mobSpawnerCoeff != 1.0D) {
            // (the cache keys a block by its registry path, BlockUtils.blockToString: "spawner" since 1.13, so the old
            // "mob_spawner" never found one and the avoidance never saw a spawner)
            ctx.worldData().getCachedWorld().getLocationsOf(baritone.api.utils.BlockUtils.blockToString(net.minecraft.world.level.block.Blocks.SPAWNER), 1, ctx.playerFeet().x, ctx.playerFeet().z, 2)
                    .forEach(mobspawner -> res.add(new Avoidance(mobspawner, mobSpawnerCoeff, Baritone.settings().mobSpawnerAvoidanceRadius.value)));
        }
        if (mobCoeff != 1.0D) {
            // (Tuning fireproof_blaze_avoid, off) under fire resistance the blazes are not avoided: see Tuning
            boolean blazesFree = blazesFree(adris.altoclef.util.helpers.Tuning.get("fireproof_blaze_avoid", 0) > 0,
                    adris.altoclef.util.helpers.FireRes.has(ctx.player()), adris.altoclef.util.helpers.FireRes.endingSoon(ctx.player()));
            if (blazesFree) {
                noteBlazesFree(ctx);
            }
            ctx.entitiesStream()
                    .filter(entity -> entity instanceof Mob)
                    .filter(entity -> (!(entity instanceof Spider)) || ctx.player().getLightLevelDependentMagicValue() < 0.5)
                    .filter(entity -> !(entity instanceof ZombifiedPiglin) || ((ZombifiedPiglin) entity).getLastHurtByMob() != null)
                    .filter(entity -> !(entity instanceof Enderman) || ((Enderman) entity).isCreepy())
                    // (Tuning avoid_crowd_cap, a fix of 22 Nov 2026, on) a slime of the smallest size does no harm: not avoided
                    .filter(entity -> !crowdCapOn() || !(entity instanceof net.minecraft.world.entity.monster.cubemob.Slime slime) || slime.getSize() > 1)
                    .filter(entity -> !blazesFree || !(entity instanceof net.minecraft.world.entity.monster.Blaze))
                    .forEach(entity -> res.add(new Avoidance(entity.blockPosition(), mobCoeff, Baritone.settings().mobAvoidanceRadius.value)));
        }
        return res;
    }

    public void applySpherical(Long2DoubleOpenHashMap map) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z <= radius * radius) {
                        long hash = BetterBlockPos.longHash(centerX + x, centerY + y, centerZ + z);
                        // (Tuning avoid_crowd_cap, a fix of 22 Nov 2026, on) the factors of a crowd multiply, so 19 tiny slimes made
                        // every block round the buddy cost 2^19 times a step, past COST_INF: no search got past its first node
                        // and the buddy stood 23 minutes in a trial chamber (box 1 heel-203-mc1 r4, look 222). at most CROWD_CAP
                        double f = map.get(hash) * coefficient;
                        map.put(hash, crowdCapOn() ? Math.min(f, CROWD_CAP) : f);
                    }
                }
            }
        }
    }
}
