package adris.altoclef.util.helpers;

import adris.altoclef.Debug;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// the buddy's fork: whether the decisions take fire resistance into account. with ignore_fire_resistance they act as without
// it (the douse, the run when burning, the lava escape, the gapple), so a drill with the effect given by the rig measures the
// same tactic with fire's damage taken away (steering 37: with the effect nothing doused the fires in the pillbox, Baritone
// walks into no fire, and the buddy stood locked out of its box for twelve game minutes, realfort-a-fireproof run 2)
public final class FireRes {
    private FireRes() {
    }

    public static boolean has(LivingEntity e) {
        return e.hasEffect(MobEffects.FIRE_RESISTANCE) && Tuning.get("ignore_fire_resistance", 0) <= 0;
    }

    // in lava under the potion the lava escape waits till this many ticks are left of it: deep in a shaft dug down past the lava
    // sea's level (the walk to the spawner, realfort-ab-fpreach-1 run 8 and realfort-ab-fpcamp-1 run 2) the escape never started
    // while the potion held, and with it gone the lava killed the buddy in two seconds
    public static final int LAVA_MARGIN_TICKS = 600;

    public static boolean endingSoon(LivingEntity e) {
        var fx = e.getEffect(MobEffects.FIRE_RESISTANCE);
        return fx != null && !fx.isInfiniteDuration() && fx.getDuration() <= LAVA_MARGIN_TICKS;
    }

    // a drink asked for by a task, the last game tick it was (the brew box drinks its potion inside before it opens, with no
    // blaze near or fire on the buddy, BrewBoxTask): the food chain drinks as if on fire while it is fresh
    static final long ASK_FRESH = 5;
    private static volatile long askedAt = Long.MIN_VALUE / 2;

    public static void ask(long now) {
        askedAt = now;
    }

    public static boolean asked(long now) {
        return now >= askedAt && now - askedAt <= ASK_FRESH;
    }

    // Tuning fr_trade_hold, off by default: the traded potion kept for the blazes (FireResTradeRules.held). FireResTrade says
    // every tick it runs that it ended with its potion, and by which spawner; the splash throw and the food chain's drink ask
    private static volatile long tradedAt = Long.MIN_VALUE / 2;
    private static volatile BlockPos tradedSpawner;
    private static volatile long heldAt = Long.MIN_VALUE / 2;

    public static void traded(long now, BlockPos spawner) {
        tradedAt = now;
        tradedSpawner = spawner;
    }

    // Tuning box_home (BoxHomeRules.holdPotion), off by default: any fire resistance potion, traded or brewed, waits for the first
    // blaze while the buddy is in the standing box (BlazeShelter.inStandingBox); asked for by a task, in lava or on fire at
    // fr_trade_hold_hp or less it goes down. a line per held episode and one when it goes down in the box
    private static volatile long boxHeldAt = Long.MIN_VALUE / 2;
    private static volatile long boxDrunkAt = Long.MIN_VALUE / 2;
    // a drink in the box is said again only after this long (ticks): the drink's own 32 ticks ask every tick
    static final long BOX_DRINK_GAP = 200;

    public static boolean holdsForBox(LivingEntity e, double blaze, boolean asked, long now) {
        if (!BoxHomeRules.on()) return false;
        boolean inLava = e.isInLava() || e.isEyeInFluid(FluidTags.LAVA);
        double hp = Tuning.get("fr_trade_hold_hp", FireResTradeRules.HOLD_HP);
        boolean inBox = BlazeShelter.inStandingBox(e.blockPosition());
        boolean near = blaze <= FireResRules.BLAZE_RANGE;
        if (BoxHomeRules.holdPotion(asked, inLava, e.isOnFire(), e.getHealth(), hp, inBox, near)) {
            if (FireResTradeRules.newEpisode(boxHeldAt, now)) {
                Debug.logInternal(String.format(Locale.ROOT, "box home: fire resistance held for the box (%s, %.1f hp, nearest blaze %s, %s)",
                        e.isOnFire() ? "on fire" : "a blaze near", e.getHealth(), FireResTradeRules.distance(blaze), inBox ? "in the box" : "out of it"));
            }
            boxHeldAt = now;
            return true;
        }
        if (inBox && near && !asked && (now < boxDrunkAt || now - boxDrunkAt > BOX_DRINK_GAP)) {
            Debug.logInternal(String.format(Locale.ROOT, "box home: fire resistance drunk in the box, the first blaze %s", FireResTradeRules.distance(blaze)));
            boxDrunkAt = now;
        }
        return false;
    }

    // Tuning fr_hold (FightOpenRules.holdPotion), off by default: any fire resistance potion, the kit's among them, waits for the
    // blazes as fr_trade_hold's traded one does. fr_trade_hold holds only a potion FireResTrade says it traded, so a kit's
    // potion went down at the first fire on the way. "at the fortress" is within FORTRESS_NEAR of the spawner the rods task
    // last said (spawnerSeen, fresh for HOLD_FRESH)
    private static volatile long spawnerAt = Long.MIN_VALUE / 2;
    private static volatile BlockPos spawnerSeen;
    private static volatile long anyHeldAt = Long.MIN_VALUE / 2;

    public static void spawnerSeen(long now, BlockPos spawner) {
        spawnerAt = now;
        spawnerSeen = spawner;
    }

    public static boolean holdsAny(LivingEntity e, double blaze, boolean asked, long now) {
        if (!FightOpenRules.holdOn()) return false;
        BlockPos spawner = FireResTradeRules.tradedFresh(spawnerAt, now) ? spawnerSeen : null;
        boolean atFortress = spawner != null && e.position().distanceTo(Vec3.atCenterOf(spawner)) <= FireResTradeRules.FORTRESS_NEAR;
        boolean inLava = e.isInLava() || e.isEyeInFluid(FluidTags.LAVA);
        double hp = Tuning.get("fr_trade_hold_hp", FireResTradeRules.HOLD_HP);
        if (!FightOpenRules.holdPotion(true, blaze <= FireResRules.BLAZE_RANGE, atFortress, asked, inLava, e.isOnFire(), e.getHealth(), hp)) {
            return false;
        }
        if (FireResTradeRules.newEpisode(anyHeldAt, now)) {
            Debug.logInternal(String.format(Locale.ROOT, "fr hold: the potion held for the blazes (%s, %.1f hp, nearest blaze %s)",
                    e.isOnFire() ? "on fire" : "not on fire", e.getHealth(), FireResTradeRules.distance(blaze)));
        }
        anyHeldAt = now;
        return true;
    }

    // Tuning route_hold, on by default (the route, steering 118: the first two rods under the traded potion): while the route's
    // phase 3 runs (the trade over, the cream and the wart on their errand; the rods task says so every tick it gets to the
    // errand) any fire resistance potion waits, by the spawner too, where fr_trade_hold's and fr_hold's waits end and where
    // the errand waits for a cube: only a blaze in drinking range, a task's ask, lava, or fire at fr_trade_hold_hp or less
    // let it go down. the say counts ROUTE_FRESH ticks, so a fight that takes the tick ends the wait soon after
    static final long ROUTE_FRESH = 40;
    private static volatile long routeAt = Long.MIN_VALUE / 2;
    private static volatile long routeHeldAt = Long.MIN_VALUE / 2;
    // (Tuning route_hold_fight, on by default, route jar 2: RouteRules.holdPotion) the rods task's say whether the spawner fight
    // has begun (phase 4 and the buddy at the spawner); the burn's best health and the tick it was last looked at (a look
    // BURN_GAP after the last is a new burn); the last let-go said
    private static volatile boolean routeFight;
    private static double burnBest = Double.NaN;
    private static long burnAt = Long.MIN_VALUE / 2;
    static final long BURN_GAP = 10;
    private static volatile long routeLetAt = Long.MIN_VALUE / 2;

    public static void routeHolds(long now) {
        routeHolds(now, false);
    }

    // said every tick of phases 3 and 4 (route_hold_fight) or of the errand (route jar 1); fightBegun: RouteRules.fightBegun
    public static void routeHolds(long now, boolean fightBegun) {
        routeAt = now;
        routeFight = fightBegun;
    }

    // (Tuning route_hold_way, on by default, route jar 3: RouteRules.wayLetGo) on the way the say lasts WAY_FRESH: the mob
    // defense's run took the tick for more than ROUTE_FRESH and let the potion go at any burn; and how long the buddy has
    // been in this lava (a look LAVA_GAP after the last is new lava)
    static final long WAY_FRESH = 200;
    static final long LAVA_GAP = 10;
    private static long lavaSince = Long.MIN_VALUE / 2;
    private static long lavaAt = Long.MIN_VALUE / 2;

    static synchronized long lavaTicks(boolean inLava, long now) {
        if (!inLava) return -1;
        if (now < lavaAt || now - lavaAt > LAVA_GAP) lavaSince = now;
        lavaAt = now;
        return now - lavaSince;
    }

    public static boolean holdsRoute(LivingEntity e, double blaze, boolean asked, long now) {
        boolean way = RouteRules.holdFightOn() && RouteRules.holdWayOn() && !routeFight;
        if (Tuning.get("route_hold", 0) <= 0 || now < routeAt || now - routeAt > (way ? WAY_FRESH : ROUTE_FRESH)) return false;
        boolean inLava = e.isInLava() || e.isEyeInFluid(FluidTags.LAVA);
        double hp = Tuning.get("fr_trade_hold_hp", FireResTradeRules.HOLD_HP);
        if (way) {
            long lava = lavaTicks(inLava, now);
            String why = RouteRules.wayLetGo(asked, inLava, lava, Math.round(RouteRules.WAY_LAVA_S * 20), e.isOnFire(), e.getHealth(),
                    Tuning.get("route_hold_lava_hp", RouteRules.WAY_LAVA_HP), Tuning.get("route_hold_burn_hp", RouteRules.WAY_BURN_HP));
            if (why != null) {
                if (FireResTradeRules.newEpisode(routeLetAt, now)) {
                    Debug.logInternal(String.format(Locale.ROOT, "route hold: the potion goes down on the way, %s (%.1f hp, nearest blaze %s)",
                            why, e.getHealth(), FireResTradeRules.distance(blaze)));
                }
                routeLetAt = now;
                return false;
            }
        } else if (RouteRules.holdFightOn()) {
            boolean onFire = e.isOnFire();
            synchronized (FireRes.class) {
                if (!onFire || Double.isNaN(burnBest) || now < burnAt || now - burnAt > BURN_GAP) burnBest = onFire ? e.getHealth() : Double.NaN;
                else burnBest = Math.max(burnBest, e.getHealth());
                burnAt = now;
            }
            boolean falling = RouteRules.healthFalling(onFire, burnBest, e.getHealth());
            String why = RouteRules.holdLetGo(asked, inLava, onFire, falling, e.getHealth(), hp, routeFight, blaze <= FireResRules.BLAZE_RANGE);
            if (why != null) {
                if (FireResTradeRules.newEpisode(routeLetAt, now)) {
                    Debug.logInternal(String.format(Locale.ROOT, "route hold: the potion goes down, %s (%.1f hp%s, nearest blaze %s)", why,
                            e.getHealth(), falling ? String.format(Locale.ROOT, ", %.1f at the burn's start", burnBest) : "", FireResTradeRules.distance(blaze)));
                }
                routeLetAt = now;
                return false;
            }
        } else if (!FireResTradeRules.held(true, blaze <= FireResRules.BLAZE_RANGE, false, asked, inLava, e.isOnFire(), e.getHealth(), hp)) {
            return false;
        }
        if (FireResTradeRules.newEpisode(routeHeldAt, now)) {
            Debug.logInternal(String.format(Locale.ROOT, "route hold: the potion kept for the blazes (%s, %.1f hp, nearest blaze %s)",
                    e.isOnFire() ? "on fire" : "not on fire", e.getHealth(), FireResTradeRules.distance(blaze)));
        }
        routeHeldAt = now;
        return true;
    }

    // (Tuning route_way_throw, route jar 7) the way's rule lets the kept potion go now: the route's say fresh, on the way
    // (before the spawner fight), and RouteRules.wayLetGo said why (its line said by holdsRoute). false off the way
    public static boolean wayLets(LivingEntity e, double blaze, boolean asked, long now) {
        boolean way = RouteRules.holdFightOn() && RouteRules.holdWayOn() && !routeFight;
        if (!way || Tuning.get("route_hold", 0) <= 0 || now < routeAt || now - routeAt > WAY_FRESH) return false;
        return !holdsRoute(e, blaze, asked, now);
    }

    // whether a fire resistance potion that would go down now (FireResRules.wantsToDrink said yes) waits for the blazes, with
    // a line once per fire episode. always false with the switch off. blaze: the nearest one, infinite for none
    public static boolean holdsTraded(LivingEntity e, double blaze, boolean asked, long now) {
        if (Tuning.get("fr_trade_hold", 0) <= 0) return false;
        if (!FireResTradeRules.tradedFresh(tradedAt, now)) return false;
        BlockPos spawner = tradedSpawner;
        boolean atFortress = spawner != null && e.position().distanceTo(Vec3.atCenterOf(spawner)) <= FireResTradeRules.FORTRESS_NEAR;
        boolean inLava = e.isInLava() || e.isEyeInFluid(FluidTags.LAVA);
        double hp = Tuning.get("fr_trade_hold_hp", FireResTradeRules.HOLD_HP);
        if (!FireResTradeRules.held(true, blaze <= FireResRules.BLAZE_RANGE, atFortress, asked, inLava, e.isOnFire(), e.getHealth(), hp)) {
            return false;
        }
        if (FireResTradeRules.newEpisode(heldAt, now)) {
            Debug.logInternal(String.format(Locale.ROOT, "strategy trade: the potion held for the blazes (on fire, %.1f hp, nearest blaze %s)",
                    e.getHealth(), FireResTradeRules.distance(blaze)));
        }
        heldAt = now;
        return true;
    }
}
