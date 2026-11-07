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
public record MobThreat(String kind, double width, double height, double eye, double speed, boolean flies, double follow, boolean neutral,
                        double melee, double reach, boolean sphere, double shot, double range, double aim, double burn,
                        double hit, double push, boolean pushRound) {

    // a hostile kind with no row of its own: a plain melee mob
    static final MobThreat ANY = new MobThreat("hostile", 0.6, 1.95, 1.74, 3.0, false, 16, false,
            2.0, 1.43, false, 0, 0, 0, 0, 2, SafeStand.HIT, false);

    private static final Map<String, MobThreat> ROWS = Map.ofEntries(
            // a volley of three small fireballs (5 raw, 2.5 through iron) every 178 ticks (60 charged, three 6 apart, 100 cool:
            // BlazeAttackGoal), 0.84 a second; 5 s of fire per volley that lands, 0.56 a second; the spread is triangle(2.297 x
            // sqrt(distance) x 0.5) blocks to a side, so about 1 / sqrt(distance) of the balls land; it shoots while it sees us
            // within its follow range (48). within 2 it bites: 6 raw once a second. it hovers where it is while it sees us and
            // flies at us only for 5 ticks after losing sight, so it closes slowly (0.5 a second here: only one already close bites)
            Map.entry("blaze", new MobThreat("blaze", 0.6, 1.8, 1.53, 0.5, true, 48, false,
                    3.12, 2.0, true, 0.84, 48, 1, 0.56, 2.5, SafeStand.HIT, false)),
            // one large fireball every 3 s at most (chargeTime 20 with sight, then -40: GhastShootFireballGoal), 6 raw, 3.12
            // through iron (the rig measured 3.12), within 64 and with a line from its eye to ours; aimed at our middle with no
            // lead, it lands on a buddy standing still. its push, ball and blast together, carries 4.5 (SafeStand.GHAST)
            Map.entry("ghast", new MobThreat("ghast", 4.0, 4.0, 2.6, 0.5, true, 64, false,
                    0, 0, false, 1.04, 64, 16, 0, 3.12, SafeStand.GHAST, false)),
            // a stone sword, 8 raw (4.48 through iron, measured) once a second, and 4 hp of wither over the 8 s after the last
            // blow (about 0.5 a second); its box 0.7 x 2.4, so a roof two high keeps it out; chases at about 4 blocks a second
            Map.entry("wither_skeleton", new MobThreat("wither_skeleton", 0.7, 2.4, 2.1, 4.0, false, 16, false,
                    5.0, 0.35 + 0.83 + 0.3, false, 0, 0, 0, 0, 4.48, SafeStand.HIT, false)),
            // an arrow every 3 s on Normal (40 ticks between, a 20 tick draw: RangedBowAttackGoal), mostly 4 raw (1.92 through
            // iron, measured); lands 99% at 4, 62% at 8, 51% at 15 (aim 4); keeps 7.5 to 13 off, takes no target past 16
            Map.entry("skeleton", skeleton("skeleton", 0.64)),
            Map.entry("stray", skeleton("stray", 0.64)),
            // bogged and parched wait 70 ticks between arrows
            Map.entry("bogged", skeleton("bogged", 0.43)),
            Map.entry("parched", skeleton("parched", 0.43)),
            // a sword piglin: a golden sword, 8 raw (4.48 through iron, measured) once a second; neutral to a buddy in gold
            Map.entry("piglin", new MobThreat("piglin", 0.6, 1.95, 1.79, 3.5, false, 16, true,
                    4.48, 1.43, false, 0, 0, 0, 0, 4.48, SafeStand.HIT, false)),
            // a crossbow piglin: a bolt of 4 raw (1.92 through iron) every 2.3 to 3.3 s, only within 8 and in sight
            // (CrossbowAttack); never in melee
            Map.entry("piglin_crossbow", new MobThreat("piglin_crossbow", 0.6, 1.95, 1.79, 3.5, false, 16, true,
                    0, 0, false, 0.69, 8, 8, 0, 1.92, SafeStand.HIT, false)),
            // a golden axe, 13 raw (8.58 through iron, measured), once a second; never neutral
            Map.entry("piglin_brute", new MobThreat("piglin_brute", 0.6, 1.95, 1.79, 3.5, false, 16, false,
                    8.58, 1.43, false, 0, 0, 0, 0, 8.58, SafeStand.HIT, false)),
            // a golden sword, 8 raw (4.48 through iron, measured) once a second, 3.45 blocks a second angry; neutral
            Map.entry("zombified_piglin", new MobThreat("zombified_piglin", 0.6, 1.95, 1.79, 3.45, false, 35, true,
                    4.48, 1.43, false, 0, 0, 0, 0, 4.48, SafeStand.HIT, false)),
            // lights within 3 (feet to feet) and blasts 1.5 s later: at 2 to 3 blocks about 15 to 20 through iron (creeper.md's
            // table), so 13 a second within the fuse's reach as a rate; it needs to see us for the fuse to count. its blast
            // pushes further than a hit: 4.5 here, a guess from the blast's push (not measured)
            Map.entry("creeper", new MobThreat("creeper", 0.6, 1.7, 1.45, 2.75, false, 16, false,
                    13, 3.0, true, 0, 0, 0, 0, 18, SafeStand.GHAST, false)),
            // 6 raw (3.12 through iron) every 2 s, and the throw: up to 7 any way round as it turns (SafeStand.HOGLIN)
            Map.entry("hoglin", new MobThreat("hoglin", 1.4, 1.4, 1.2, 3.0, false, 16, false,
                    1.56, 0.7 + 0.83 + 0.3, false, 0, 0, 0, 0, 3.12, SafeStand.HOGLIN, true)),
            Map.entry("zoglin", new MobThreat("zoglin", 1.4, 1.4, 1.2, 3.0, false, 16, false,
                    1.56, 0.7 + 0.83 + 0.3, false, 0, 0, 0, 0, 3.12, SafeStand.HOGLIN, true)),
            // 3 raw (1.38 through iron) once a second, about 2.3 blocks a second
            Map.entry("zombie", zombie("zombie")),
            Map.entry("husk", zombie("husk")),
            Map.entry("drowned", zombie("drowned")),
            Map.entry("zombie_villager", zombie("zombie_villager")),
            // 7 raw (3.9 through iron) once a second, its box 2.9 high; neutral until looked at or hit
            Map.entry("enderman", new MobThreat("enderman", 0.6, 2.9, 2.55, 4.0, false, 64, true,
                    3.9, 1.43, false, 0, 0, 0, 0, 3.9, SafeStand.HIT, false))
    );

    private static MobThreat skeleton(String kind, double shot) {
        return new MobThreat(kind, 0.6, 1.99, 1.74, 3.0, false, 16, false, 0, 0, false, shot, 16, 4, 0, 1.92, SafeStand.HIT, false);
    }

    private static MobThreat zombie(String kind) {
        return new MobThreat(kind, 0.6, 1.95, 1.74, 2.3, false, 35, false, 1.38, 1.43, false, 0, 0, 0, 0, 1.38, SafeStand.HIT, false);
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
                2 * hit, w / 2 + 0.83 + 0.3, false, 0, 0, 0, 0, hit, SafeStand.HIT, false);
    }

    // its shots are fire damage alone, which fire resistance refuses before any knock: a blaze's small fireballs. a ghast's
    // ball is fire damage too but its blast is not, so a ghast stays
    boolean fireShot() {
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
