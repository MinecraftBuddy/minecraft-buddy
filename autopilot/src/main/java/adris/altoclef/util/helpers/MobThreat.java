package adris.altoclef.util.helpers;

import java.util.Map;

// the buddy's fork: what one kind of mob can do to the buddy, one row a kind, for StandModel. the numbers are 26.3's own (the
// mob's goals, its attributes and EntityTypes), on Normal and through the kit's full iron (armour 15:
// CombatRules.getDamageAfterAbsorb, 8 raw is 4.48, 6 is 3.12, 5 is 2.5, 4 is 1.92); fire and wither go through armour. rates
// are hp a second while the mob is at it; a row says nothing about whether it is at it (StandModel weighs that per cell)
//
// width, height, eye: its box and its eyes over its feet (EntityTypes)
// speed: blocks a second chasing; flies: no path needed, the straight line
// follow: past this it takes no target and drops the one it has (its follow range)
// neutral: only a danger once angry (the caller's aware flag)
// melee: hp a second in reach; reach: its attack box (its box widened 0.83 sideways, Mob.getAttackBoundingBox) against ours,
//   centre to centre on each axis, with the boxes overlapping in height; sphere: the reach is a straight distance instead
//   (a blaze's bite, a creeper's fuse)
// shot: hp a second from its projectiles with every one landing, line of sight from its eyes to ours needed; range: how far
//   it shoots; aim: out to this every shot lands, past it the share is sqrt(aim / distance) (the spread grows with distance)
// burn: hp a second of fire from its shots landing (a blaze's balls set us alight for 5 s), none with fire resistance
// hit: one hit's damage, for counting hits (a knock comes with each); push: how far a hit carries the feet (SafeStand), away
//   from the mob, or any way round (pushRound, a hoglin's throw)
// hp: its full health (the danger meter's time to kill it; a live mob's own health wins where the client has it)
// chase: for a mob that holds its place (holds()), how far past its reach it follows us; 0, it bites only where it reaches
public record MobThreat(String kind, double width, double height, double eye, double speed, boolean flies, double follow, boolean neutral,
                        double melee, double reach, boolean sphere, double shot, double range, double aim, double burn,
                        double hit, double push, boolean pushRound, double hp, double chase) {

    public MobThreat(String kind, double width, double height, double eye, double speed, boolean flies, double follow, boolean neutral,
                     double melee, double reach, boolean sphere, double shot, double range, double aim, double burn,
                     double hit, double push, boolean pushRound, double hp) {
        this(kind, width, height, eye, speed, flies, follow, neutral, melee, reach, sphere, shot, range, aim, burn, hit, push, pushRound, hp, 0);
    }

    // a hostile kind with no row of its own: a plain melee mob
    static final MobThreat ANY = new MobThreat("hostile", 0.6, 1.95, 1.74, 3.0, false, 16, false,
            2.0, 1.43, false, 0, 0, 0, 0, 2, SafeStand.HIT, false, 20);

    private static final Map<String, MobThreat> ROWS = Map.ofEntries(
            // a volley of three small fireballs (5 raw, 2.5 through iron) every 178 ticks (60 charged, three 6 apart, 100 cool:
            // BlazeAttackGoal), 0.84 a second; 5 s of fire per volley that lands, 0.56 a second; the spread is triangle(2.297 x
            // sqrt(distance) x 0.5) blocks to a side, so about 1 / sqrt(distance) of the balls land; it shoots while it sees us
            // within its follow range (48). within 2 it bites: 6 raw once a second. it hovers where it is while it sees us and
            // flies at us only for 5 ticks after losing sight: it holds its place (holds()), and only one already close bites
            Map.entry("blaze", new MobThreat("blaze", 0.6, 1.8, 1.53, 0.5, true, 48, false,
                    3.12, 2.0, true, 0.84, 48, 1, 0.56, 2.5, SafeStand.HIT, false, 20)),
            // one large fireball every 3 s at most (chargeTime 20 with sight, then -40: GhastShootFireballGoal), 6 raw, 3.12
            // through iron (the rig measured 3.12), within 64 and with a line from its eye to ours; aimed at our middle with no
            // lead, it lands on a buddy standing still. its push, ball and blast together, carries 4.5 (SafeStand.GHAST)
            Map.entry("ghast", new MobThreat("ghast", 4.0, 4.0, 2.6, 0.5, true, 64, false,
                    0, 0, false, 1.04, 64, 16, 0, 3.12, SafeStand.GHAST, false, 10)),
            // a stone sword, 8 raw (4.48 through iron, measured) once a second, and 4 hp of wither over the 8 s after the last
            // blow (about 0.5 a second); its box 0.7 x 2.4, so a roof two high keeps it out; chases at about 4 blocks a second
            Map.entry("wither_skeleton", new MobThreat("wither_skeleton", 0.7, 2.4, 2.1, 4.0, false, 16, false,
                    5.0, 0.35 + 0.83 + 0.3, false, 0, 0, 0, 0, 4.48, SafeStand.HIT, false, 20)),
            // an arrow every 3 s on Normal (40 ticks between, a 20 tick draw: RangedBowAttackGoal), mostly 4 raw (1.92 through
            // iron, measured); lands 99% at 4, 62% at 8, 51% at 15 (aim 4); keeps 7.5 to 13 off, takes no target past 16
            Map.entry("skeleton", skeleton("skeleton", 0.64)),
            Map.entry("stray", skeleton("stray", 0.64)),
            // bogged and parched wait 70 ticks between arrows
            Map.entry("bogged", skeleton("bogged", 0.43)),
            Map.entry("parched", skeleton("parched", 0.43)),
            // a sword piglin: a golden sword, 8 raw (4.48 through iron, measured) once a second; neutral to a buddy in gold
            Map.entry("piglin", new MobThreat("piglin", 0.6, 1.95, 1.79, 3.5, false, 16, true,
                    4.48, 1.43, false, 0, 0, 0, 0, 4.48, SafeStand.HIT, false, 16)),
            // a crossbow piglin: a bolt of 4 raw (1.92 through iron) every 2.3 to 3.3 s, only within 8 and in sight
            // (CrossbowAttack); never in melee
            Map.entry("piglin_crossbow", new MobThreat("piglin_crossbow", 0.6, 1.95, 1.79, 3.5, false, 16, true,
                    0, 0, false, 0.69, 8, 8, 0, 1.92, SafeStand.HIT, false, 16)),
            // a golden axe, 13 raw (8.58 through iron, measured), once a second; never neutral
            Map.entry("piglin_brute", new MobThreat("piglin_brute", 0.6, 1.95, 1.79, 3.5, false, 16, false,
                    8.58, 1.43, false, 0, 0, 0, 0, 8.58, SafeStand.HIT, false, 50)),
            // a golden sword, 8 raw (4.48 through iron, measured) once a second, 3.45 blocks a second angry; neutral
            Map.entry("zombified_piglin", new MobThreat("zombified_piglin", 0.6, 1.95, 1.79, 3.45, false, 35, true,
                    4.48, 1.43, false, 0, 0, 0, 0, 4.48, SafeStand.HIT, false, 20)),
            // lights within 3 (feet to feet) and blasts 1.5 s later: at 2 to 3 blocks about 15 to 20 through iron (creeper.md's
            // table), so 13 a second within the fuse's reach as a rate; it needs to see us for the fuse to count. its blast
            // pushes further than a hit: 4.5 here, a guess from the blast's push (not measured)
            Map.entry("creeper", new MobThreat("creeper", 0.6, 1.7, 1.45, 2.75, false, 16, false,
                    13, 3.0, true, 0, 0, 0, 0, 18, SafeStand.GHAST, false, 20)),
            // 6 raw (3.12 through iron) every 2 s, and the throw: up to 7 any way round as it turns (SafeStand.HOGLIN)
            Map.entry("hoglin", new MobThreat("hoglin", 1.4, 1.4, 1.2, 3.0, false, 16, false,
                    1.56, 0.7 + 0.83 + 0.3, false, 0, 0, 0, 0, 3.12, SafeStand.HOGLIN, true, 40)),
            Map.entry("zoglin", new MobThreat("zoglin", 1.4, 1.4, 1.2, 3.0, false, 16, false,
                    1.56, 0.7 + 0.83 + 0.3, false, 0, 0, 0, 0, 3.12, SafeStand.HOGLIN, true, 40)),
            // 3 raw (1.38 through iron) once a second, about 2.3 blocks a second
            Map.entry("zombie", zombie("zombie")),
            Map.entry("husk", zombie("husk")),
            Map.entry("drowned", zombie("drowned")),
            Map.entry("zombie_villager", zombie("zombie_villager")),
            // 7 raw (3.9 through iron) once a second, its box 2.9 high; neutral until looked at or hit
            Map.entry("enderman", new MobThreat("enderman", 0.6, 2.9, 2.55, 4.0, false, 64, true,
                    3.9, 1.43, false, 0, 0, 0, 0, 3.9, SafeStand.HIT, false, 40))
    );

    private static MobThreat skeleton(String kind, double shot) {
        return new MobThreat(kind, 0.6, 1.99, 1.74, 3.0, false, 16, false, 0, 0, false, shot, 16, 4, 0, 1.92, SafeStand.HIT, false, 20);
    }

    private static MobThreat zombie(String kind) {
        return new MobThreat(kind, 0.6, 1.95, 1.74, 2.3, false, 35, false, 1.38, 1.43, false, 0, 0, 0, 0, 1.38, SafeStand.HIT, false, 20);
    }

    // the danger meter's extra rows: the hostile kinds StandModel has no row for yet, the neutral kinds (a danger once angry),
    // and the passive ones, which hurt nobody but can push the buddy into a hazard. kept apart from ROWS so that of() and
    // known(), and with them StandModel and StandPick, read exactly what they read before. through iron (armour 15) on Normal
    // like the rows above: 2 raw is 0.88, 3 is 1.38, 4 is 1.92, 6 is 3.12; a passive body nudges the feet about a block
    private static final Map<String, MobThreat> MORE = Map.ofEntries(
            // 2 raw once a second; climbs, so a wall is no way out (MobReachability lets spiders up walls too)
            Map.entry("spider", new MobThreat("spider", 1.4, 0.9, 0.65, 3.0, false, 16, false,
                    0.88, 0.7 + 0.83 + 0.3, false, 0, 0, 0, 0, 0.88, SafeStand.HIT, false, 16)),
            // (a record, 14 Nov 2026: test heel's 14 witch deaths read "a body that hurts nobody", the level calm) a potion every
            // 3 s within 10 with a line (RangedAttackGoal 60 ticks, 10 blocks): slowness first from 8 out, poison while the hp is
            // 8 or more, else harming, 6 a throw (magic, through armour); the poison counts as the buddy's burn (DangerShadow).
            // never in melee; 26 hp, walks at about 2.5 blocks a second
            Map.entry("witch", new MobThreat("witch", 0.6, 1.95, 1.62, 2.5, false, 16, false,
                    0, 0, false, 2.0, 10, 10, 0, 6.0, SafeStand.HIT, false, 26)),
            // 2 raw and 7 s of poison on Normal (about 0.8 a second on top)
            Map.entry("cave_spider", new MobThreat("cave_spider", 0.7, 0.5, 0.45, 3.0, false, 16, false,
                    1.7, 0.35 + 0.83 + 0.3, false, 0, 0, 0, 0, 0.88, SafeStand.HIT, false, 12)),
            Map.entry("wolf", new MobThreat("wolf", 0.6, 0.85, 0.68, 3.5, false, 16, true,
                    1.92, 1.43, false, 0, 0, 0, 0, 1.92, SafeStand.HIT, false, 8)),
            // one sting (2 raw and poison), then it is done
            Map.entry("bee", new MobThreat("bee", 0.7, 0.6, 0.3, 3.0, true, 16, true,
                    1.5, 1.48, false, 0, 0, 0, 0, 0.88, SafeStand.HIT, false, 10)),
            // 7.5 to 21.5 raw (10 through iron for the middle one) every 1.25 s, and a throw upwards
            Map.entry("iron_golem", new MobThreat("iron_golem", 1.4, 2.7, 2.3, 2.5, false, 16, true,
                    8.0, 0.7 + 0.83 + 0.3, false, 0, 0, 0, 0, 10.0, SafeStand.GHAST, false, 100)),
            Map.entry("polar_bear", new MobThreat("polar_bear", 1.4, 1.4, 1.2, 2.5, false, 20, true,
                    3.12, 0.7 + 0.83 + 0.3, false, 0, 0, 0, 0, 3.12, SafeStand.HIT, false, 30)),
            Map.entry("panda", new MobThreat("panda", 1.3, 1.25, 1.0, 2.0, false, 16, true,
                    3.12, 0.65 + 0.83 + 0.3, false, 0, 0, 0, 0, 3.12, SafeStand.HIT, false, 20)),
            // a spit of 1 raw every 2 s or so within 8
            Map.entry("llama", new MobThreat("llama", 0.9, 1.87, 1.6, 2.0, false, 40, true,
                    0, 0, false, 0.2, 8, 8, 0, 0.4, 1.0, false, 22)),
            Map.entry("trader_llama", new MobThreat("trader_llama", 0.9, 1.87, 1.6, 2.0, false, 40, true,
                    0, 0, false, 0.2, 8, 8, 0, 0.4, 1.0, false, 22)),
            // a ram now and then (2 raw) with a big knock
            Map.entry("goat", new MobThreat("goat", 0.9, 1.3, 1.1, 2.5, false, 16, true,
                    0.1, 0.45 + 0.83 + 0.3, false, 0, 0, 0, 0, 0.88, SafeStand.GHAST, false, 10)),
            Map.entry("dolphin", new MobThreat("dolphin", 0.9, 0.6, 0.3, 4.0, false, 16, true,
                    1.38, 0.45 + 0.83 + 0.3, false, 0, 0, 0, 0, 1.38, SafeStand.HIT, false, 10)),
            Map.entry("cow", passive("cow", 0.9, 1.4, 10)),
            Map.entry("mooshroom", passive("mooshroom", 0.9, 1.4, 10)),
            Map.entry("pig", passive("pig", 0.9, 0.9, 10)),
            Map.entry("sheep", passive("sheep", 0.9, 1.3, 8)),
            Map.entry("chicken", passive("chicken", 0.4, 0.7, 4)),
            Map.entry("rabbit", passive("rabbit", 0.4, 0.5, 3)),
            Map.entry("horse", passive("horse", 1.4, 1.6, 22)),
            Map.entry("donkey", passive("donkey", 1.4, 1.5, 22)),
            Map.entry("mule", passive("mule", 1.4, 1.6, 22)),
            Map.entry("camel", passive("camel", 1.7, 2.4, 32)),
            Map.entry("fox", passive("fox", 0.6, 0.7, 10)),
            Map.entry("cat", passive("cat", 0.6, 0.7, 10)),
            Map.entry("ocelot", passive("ocelot", 0.6, 0.7, 10)),
            Map.entry("turtle", passive("turtle", 1.2, 0.4, 30)),
            Map.entry("armadillo", passive("armadillo", 0.7, 0.65, 12)),
            Map.entry("sniffer", passive("sniffer", 1.9, 1.75, 14)),
            Map.entry("villager", passive("villager", 0.6, 1.95, 20)),
            Map.entry("wandering_trader", passive("wandering_trader", 0.6, 1.95, 20)),
            Map.entry("snow_golem", passive("snow_golem", 0.7, 1.9, 4)),
            // the nether's lava walker: harmless, and always beside lava
            Map.entry("strider", passive("strider", 0.9, 1.7, 20))
    );

    private static MobThreat passive(String kind, double width, double height, double hp) {
        return new MobThreat(kind, width, height, height * 0.85, 2.0, false, 0, false, 0, 0, false, 0, 0, 0, 0, 0, 1.0, false, hp);
    }

    // the danger meter's row of a kind: ROWS first, then MORE; null for a kind in neither (the meter treats it as a body)
    public static MobThreat forMeter(String kind, int size) {
        MobThreat row = of(kind, size);
        return row != null ? row : MORE.get(kind);
    }

    // (S137, the repair after smoke9) a blaze in its bite: within its reach with a line to us it moves at our feet on every
    // tick it bites (BlazeAttackGoal), at its walking pace (its movement speed is a zombie's, 0.23: about 2.3 blocks a second
    // on the ground, less in the air), so a step or two away does not leave it behind; once we are out of its reach it stops
    // and shoots again. smoke9 r4: a run fled 1 to 2 blocks to cells priced 0, two blazes followed and bit 3.1 a second, 13.9
    // hp to 0 in 4 s. its row then chases at that pace, no farther than BITE_CHASE past its reach
    static final double BITE_SPEED = 2.3;
    static final double BITE_CHASE = 1.0;
    private static final MobThreat BLAZE = ROWS.get("blaze");
    private static final MobThreat BLAZE_BITING = new MobThreat("blaze", BLAZE.width, BLAZE.height, BLAZE.eye, BITE_SPEED, BLAZE.flies,
            BLAZE.follow, BLAZE.neutral, BLAZE.melee, BLAZE.reach, BLAZE.sphere, BLAZE.shot, BLAZE.range, BLAZE.aim, BLAZE.burn,
            BLAZE.hit, BLAZE.push, BLAZE.pushRound, BLAZE.hp, BITE_CHASE);

    // the row of a mob in its bite (one row, so the field sees the same object while it stays so); the row itself for any
    // other
    static MobThreat biting(MobThreat row) {
        return row == BLAZE ? BLAZE_BITING : row;
    }

    // seconds until its melee reaches a point d blocks off (feet to feet for the meter, to a cell's centre for the field);
    // infinity where it never comes: a mob that holds its place bites only within its reach, or its chase past it
    double toReach(double d) {
        if (d <= reach) return 0;
        if (holds() && d > reach + chase) return Double.POSITIVE_INFINITY;
        return (d - reach) / Math.max(0.05, speed);
    }

    // the row of a kind (the entity type's path, "blaze"), sized for a magma cube or slime (size 1, 2 or 4; others ignore it);
    // null for a kind that is no danger in itself (animals, villagers, items)
    public static MobThreat of(String kind, int size) {
        if (kind.equals("magma_cube")) return magmaCube(Math.max(1, size));
        MobThreat row = ROWS.get(kind);
        return row;
    }

    // whether a kind has a row (the game side lists the mobs about by it)
    public static boolean known(String kind) {
        return kind.equals("magma_cube") || ROWS.containsKey(kind);
    }

    // a magma cube by its size: box 0.52 x size, a contact hit of size + 2 raw twice a second (the hurt cooldown: small 3, medium
    // 4, big 6 raw; 1.38, 1.92, 3.12 through iron), its reach half its width + 0.83 + 0.3; it hops, slowly (big about 0.85 a
    // second, medium 0.4, small 0.2: slime.md)
    static MobThreat magmaCube(int size) {
        double w = 0.52 * size;
        double hit = size >= 4 ? 3.12 : size >= 2 ? 1.92 : 1.38;
        double speed = size >= 4 ? 0.85 : size >= 2 ? 0.4 : 0.2;
        return new MobThreat("magma_cube", w, w, 0.325 * size, speed, false, 16, false,
                2 * hit, w / 2 + 0.83 + 0.3, false, 0, 0, 0, 0, hit, SafeStand.HIT, false, size * size);
    }

    // its shots are fire damage alone, which fire resistance refuses before any knock: a blaze's small fireballs. a ghast's
    // ball is fire damage too but its blast is not, so a ghast stays
    boolean fireShot() {
        return kind.equals("blaze");
    }

    // it comes no closer than it is: a blaze hovers and shoots while it sees us, and flies at us only for the five ticks after
    // it loses sight (BlazeAttackGoal). its bite counts where it already reaches, never as a mob on its way: the meter read a
    // blaze 2.4 to 2.9 blocks off as biting within the second, and under fire resistance, where its fireballs do nothing,
    // that alone set flee eight times with no hp lost (the flee search's smoke, 11 Nov 2026)
    boolean holds() {
        return kind.equals("blaze");
    }

    // the share of its shots that land at this distance (eyes to eyes)
    double aimShare(double distance) {
        if (aim <= 0) return 0;
        return distance <= aim ? 1 : Math.sqrt(aim / distance);
    }

    // how many cells high its box is, for the room it needs to walk
    int cellsHigh() {
        return Math.max(1, (int) Math.ceil(height - 1e-6));
    }

    // its melee reach as whole cells to a side (centres at integer offsets, each axis below reach)
    int reachCells() {
        return Math.max(0, (int) Math.ceil(reach - 1e-6) - 1);
    }
}
