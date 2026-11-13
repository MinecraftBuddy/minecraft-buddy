package adris.altoclef.util.helpers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// the buddy's fork: numbers the drills set per block, a block per value (steering entry 13, 4 Nov 2026: the strategies work
// better, their numbers still want tuning). the rig's drillrun.py writes the queue line's TUNE_* values into
// tuning.properties in the game directory before each run ("buddy_leash=5"); read again when the file changes, looked at
// once a second. no file, or no such key: DEFAULTS, then the caller's fallback, as on every real buddy
public final class Tuning {
    // the code defaults: what every control arm of the A/B blocks carried on 7 Nov 2026 (the brief's repair 2), so a queue
    // line names only what an arm changes and a real buddy plays what the drills measured. a key changes here only when its
    // A/B block confirms it (or at the maintainer's word)
    public static final java.util.Map<String, Double> DEFAULTS = java.util.Map.ofEntries(
            java.util.Map.entry("brew_box", 2.0),
            // (the route through the nether, the maintainer's word, 8 Nov 2026, steering 118 to 120: an order of blaze rods plays
            // it by default, RouteRules. the trade first, then the cream and the wart on their errand, then the blazes)
            java.util.Map.entry("brew_errand", 1.0),
            java.util.Map.entry("calm_reach", 4.5),
            java.util.Map.entry("cream_down", 6.0),
            java.util.Map.entry("cream_up", 12.0),
            java.util.Map.entry("cream_wild", 1.0),
            java.util.Map.entry("fireproof_camp", 1.0),
            java.util.Map.entry("fireproof_side", 1.0),
            // (on by his word, 9 Nov 2026: a restart of the chunk search keeps what it explored through a fight; the A/B block
            // realfort-pair-keep ended inconclusive on the spawner seen, its side numbers all the same way. ChunkCommitRules)
            java.util.Map.entry("chunk_search_keep", 1.0),
            // (the route, steering 118: the fire resistance trade before the fortress)
            java.util.Map.entry("fr_trade", 1.0),
            // (confirmed 8 Nov 2026, realfort-pair-tsure2 at look 2: a potion 17 against 30 of 59 pairs, z 2.84)
            java.util.Map.entry("fr_trade_biome", 1.0),
            // (the maintainer's word, 8 Nov 2026: by one piglin about 6 s an ingot, so the 64 gold of the trade fit its cap)
            java.util.Map.entry("fr_trade_stay", 1.0),
            // (route jar 5, 9 Nov 2026: with a ghast in sight, or seen in the last 3 s, no standing on a bridge or rim over a
            // deadly drop: back to solid ground with room, and a crossing over more than ghast_deep_drop blocks of air waits as
            // one over the sea, GhastShelterChain; 0 the old shelter)
            java.util.Map.entry("ghast_bridge", 1.0),
            java.util.Map.entry("ghast_deep_drop", 10.0),
            // (route jar 2, 8 Nov 2026: a ghast's ball punched back only from this far
            // (blocks, our copy's gap); the punches that burst on the buddy came at 2.6 to 3.1, none of those at 3.3 or more)
            java.util.Map.entry("ghast_punch_min", 3.3),
            // (route jar 2: the heal in cover builds the pillbox only within its leash; out of it the build's walk and the run took
            // turns about 40 times in 3.7 minutes with no rod, route jar 1 run 2)
            java.util.Map.entry("heal_build_near", 1.0),
            java.util.Map.entry("lure_cap", 120.0),
            // (route jar 2: a lure ends after four looks in a row that no blaze saw; a blaze within 3.3 with its body in view is
            // fought, never let go by the buddy's leash)
            java.util.Map.entry("lure_blind", 4.0),
            java.util.Map.entry("lure_keep_reach", 3.3),
            // (route jar 3, 8 Nov 2026, each a switch back to route jar 2's way: the nether's path prices the nodes beside a
            // lava pool; a pillbox block with nothing to go against is left out at once; no pillbox whose build nearly every
            // spawn eye sees (the far box, else the open camp); a run starts no bite with a wither skeleton within 14, turns to
            // fight when it doubles back 8 with a melee foe on its heels, and passes a corner with a wither skeleton by it)
            java.util.Map.entry("nether_lava_edges", 1.0),
            // (route jar 5: the no-face check only at the block's stand with its chunks loaded, and the box's clock from its
            // cell; 0 route jar 4's way, the check before the walk and the clock from the plan)
            java.util.Map.entry("pillbox_at_cell", 1.0),
            java.util.Map.entry("pillbox_no_face", 1.0),
            java.util.Map.entry("pillbox_open_skip", 1.0),
            java.util.Map.entry("piglin_grudge", 1.0),
            // (the route's own pieces, steering 118 to 120, each a switch an A/B arm may turn off for one phase: the brew shut
            // in the fight's pillbox, a magma cube taken along on the trade's search, the way out through a portal after the
            // rods, any fire resistance potion kept for the blazes while the cream and the wart are fetched, and the trade
            // until the gold is gone or a dead end)
            java.util.Map.entry("route_brew_pillbox", 1.0),
            java.util.Map.entry("route_cream_trade", 1.0),
            // (route jar 2, each a switch back to route jar 1's way: the errand fetching the wart and the cream both and looking
            // for them till a dead end; the potion kept through phase 4 for the spawner fight, gone at once in lava or burning
            // with the health falling; the fire-proof camp in phase 4 while the potion is kept)
            java.util.Map.entry("route_errand", 1.0),
            // (route jar 6, 9 Nov 2026: the errand's dead end counts only time within 80 of the spawner, 0 from the spawner
            // known anywhere; the errand walks to the wart it knows and breaks it itself, given up only when its stand is not
            // reached, 0 the mining task and the 90 s clock; the cube held is hunted by a kill task of its own id, 0 one rule
            // kept across cubes. RouteRules)
            java.util.Map.entry("route_errand_near", 80.0),
            java.util.Map.entry("route_wart_walk", 1.0),
            java.util.Map.entry("route_cube_id", 1.0),
            // (route jar 7, 9 Nov 2026: the errand's cube hunt takes a cube of size 2 alone within 6 with the buddy at 14 hp
            // or more, 0 any cube; the trade walks off a magma cube within 6, 0 it stands; the last 3 of the stand's stone
            // never go into a box while no stand is made, 0 cobblestone first; the hiding place goes in and out by one
            // range, 20, 0 in by 32; a potion thrown by lava is fetched from a cell with no lava beside it, 0 given up; the
            // splash potion thrown over the mob defense when the way's rule lets it go burning, 0 by the rods task alone.
            // RouteRules, BlazeFightRules, ItemPickupRules, FireResTrade.burnThrow)
            java.util.Map.entry("route_cube_alone", 6.0),
            java.util.Map.entry("route_trade_cube", 6.0),
            // (11 Nov 2026, a fix: a piglin with a magma cube within the cube distance and its clear margin is passed over, as one
            // by a hoglin; the walk to it and the walk off the cubes took turns 146 times in seven minutes, FireResTrade.cubeNear)
            java.util.Map.entry("route_trade_cube_pick", 1.0),
            java.util.Map.entry("brew_keep_stone", 3.0),
            java.util.Map.entry("hide_one_range", 1.0),
            java.util.Map.entry("route_potion_edge", 1.0),
            java.util.Map.entry("route_way_throw", 1.0),
            // (route jar 8, 9 Nov 2026: the errand takes a wart within 150 of the spawner (of the buddy with none known), 0 at
            // any distance; the errand's dead end counted in game time each tick while it is the order, whoever has the tick,
            // 0 by its own ticks; the rings round the spawner from 32 out, 16 apart, 0 from 20, 20 apart; in phase 3 no camp,
            // hiding place or chase at the spawner, a blaze with a line on the buddy walked away from and one within 3.3 hit,
            // 0 the fight as at the camp; a ghast that sends the buddy into cover again within 30 s of a release doubles the
            // wait out of its sight, 0 ten seconds each time; a cube hunted only with a walk to its stand, 0 any. RouteRules,
            // GhastCover, CubeWay)
            java.util.Map.entry("route_wart_near", 150.0),
            java.util.Map.entry("route_errand_clock", 1.0),
            java.util.Map.entry("route_ring_out", 32.0),
            java.util.Map.entry("route_errand_away", 1.0),
            // (route jar 12, row L37: the ring points in a zone walked away from are passed over, RouteRules.noteAway)
            java.util.Map.entry("route_away_zone", 1.0),
            // (10 Nov 2026, a fix: a roof row block whose click has no face in sight steps to its cell's middle first,
            // BlazePillboxTask; the brew box's roof over the stand's cell)
            java.util.Map.entry("roof_face_step", 1.0),
            // (10 Nov 2026, a fix: a brew box in the fight's pillbox opens its own look out again and the buddy stays in the
            // pillbox, BrewBoxTask; it broke another wall and the lure aimed at the walled look out, look 40)
            java.util.Map.entry("brew_open_lookout", 1.0),
            // (10 Nov 2026, a fix: before the stand's screen opens, the brew's things leave the bag's slots 9 to 12, which
            // that screen reads as armour, BrewRules.hiddenMove)
            java.util.Map.entry("brew_hidden_out", 1.0),
            // (10 Nov 2026, a fix: the standing pillbox's stamp kept fresh by a fight while the buddy stands in it, CombatBrain)
            java.util.Map.entry("box_stamp_fight", 1.0),
            // (10 Nov 2026, a fix: a chunk search kept across an interruption tries its queued chunks again, ChunkSearchTask)
            java.util.Map.entry("chunk_search_requeue", 1.0),
            // (11 Nov 2026, test slijm confirmed on cascade: a slime ball in the bag stands for the brew's cream, phase 3 fetches
            // the wart alone, SlimeCream; phase 3 passed with the ball 40 of 60 pairs, without 11)
            java.util.Map.entry("route_slime_cream", 1.0),
            // (11 Nov 2026, a fix: past phase 3 a burn with a blaze within 24 lets the kept potion go off the spawner's ten
            // blocks too, RouteRules.wayBlazeLetGo)
            java.util.Map.entry("route_way_blaze", 1.0),
            // (11 Nov 2026, a fix: the brew's wart, cream, slime ball and redstone are never thrown out to make room,
            // CollectBlazeRodsTask; potion_room let a potion push them out of a full bag after the trade)
            java.util.Map.entry("brew_keep", 1.0),
            // (route jar 13, 9 Nov 2026: chunks the explore's rings failed to reach twice are passed over, ExploreProcess.Unreachable)
            java.util.Map.entry("explore_ban", 1.0),
            java.util.Map.entry("ghast_wait_double", 1.0),
            java.util.Map.entry("route_cube_way", 1.0),
            java.util.Map.entry("route_exit", 1.0),
            // (11 Nov 2026, a fix: with the order's rods in the bag the exit comes before the camp, the fight and the pickups,
            // its cap counts its own ticks, a walk with no progress tries the portal's surroundings and then the next portal, and
            // a give-up stops the walk; phase 7 lost 10 of 30 runs at the spawner, RouteRules.exitFirstOn)
            java.util.Map.entry("route_exit_first", 1.0),
            // (11 Nov 2026, a fix: two rods under the potion with the brew's kit, the brew comes before the hiding place and the
            // chase; 4 of phase 4's 30 deaths had two rods or more under the potion and no brew begun, RouteRules.brewFirstOn)
            java.util.Map.entry("route_brew_first", 1.0),
            // (11 Nov 2026, a fix: a walk onto the spawner's top that comes no closer in 20 s walks to within the camp's radius
            // of it, RouteRules.campNearOn; look 51 and H-nopath)
            java.util.Map.entry("camp_near", 1.0),
            // (a fix, 12 Nov 2026: a corner camp the walk comes no closer to in 30 s is dropped and shut out, look 65)
            java.util.Map.entry("camp_corner_giveup", 1.0),
            // (a fix, 12 Nov 2026: the walk to a wart has more time the farther it starts, RouteRules.wartWalkCapS; look 66)
            java.util.Map.entry("wart_walk_scale", 1.0),
            // (12 Nov 2026, from the wartver block, phase 3 passed A 34, B 44 of 60 pairs: a dead end with the wart still wanted
            // first begins the far pass, RouteRules.wartFarOn)
            java.util.Map.entry("route_wart_far", 1.0),
            // (fixes of 12 Nov 2026: the brew's cream lost only once it stays lost 5 s, the trade's search goes on out past a
            // biome with no piglins and gives a far point its time, a hoglin bars a piglin only by its distance to the piglin)
            java.util.Map.entry("brew_cream_grace", 1.0),
            java.util.Map.entry("trade_search_out", 1.0),
            java.util.Map.entry("trade_search_wastes", 1.0),
            // (a fix of 12 Nov 2026: the brew box lets the fight in for a living thing inside, not an item lying there)
            java.util.Map.entry("box_living_only", 1.0),
            // (a fix of 13 Nov 2026: the ghast shelter takes no cover within 3 blocks of the one held, look 73)
            java.util.Map.entry("ghast_cover_near_stay", 1.0),
            // (fixes of 13 Nov 2026, route-58's phase 2: no punch of a ghast's ball with a zombified piglin by its way back; the
            // trade's search rings start on the side away from the start, and wastes are looked for 16 and 32 above and below too)
            java.util.Map.entry("ghast_punch_zp_guard", 1.0),
            java.util.Map.entry("trade_search_onward", 1.0),
            java.util.Map.entry("trade_wastes_heights", 1.0),
            // (13 Nov 2026, off: in the fire-proof camp one wither skeleton by a blaze or by the buddy keeps the chase off,
            // BlazeFight.scan; the A/B block witherwacht)
            java.util.Map.entry("fireproof_wither_one", 0.0),
            // (13 Nov 2026, on: a line only, whether a plain walk reaches a stand under a blaze the chase found no path to)
            java.util.Map.entry("blaze_way_log", 1.0),
            // (a fix of 13 Nov 2026: a branch mine's baritone mine takes only ores in its band, BranchMineTask)
            java.util.Map.entry("mine_band_ores", 1.0),
            // (a fix of 13 Nov 2026: the gold batch's furnace goes back in the bag, CollectGoldIngotTask)
            java.util.Map.entry("gold_furnace_back", 1.0),
            // (13 Nov 2026, off: a blaze walked to by a stand on the ground under it, not the blaze in the air; GetToEntityTask,
            // the A/B block standplek)
            java.util.Map.entry("blaze_stand_goal", 0.0),
            // (a fix of 13 Nov 2026: the food run's climb reads the height again where it stands, SurfaceFoodTask)
            java.util.Map.entry("food_climb_again", 1.0),
            // (fixes of 13 Nov 2026, the read of M3.3's smoke 5: one food trip for the rest of the phase, cooked in one batch, and
            // the night in the mine instead of a hole at the surface (the buddy's Alto and Obtain); a full bag throws cinnabar,
            // sulfur, clay, bone and raw copper first (GarbageRules.junkFirst); a bed, furnace, smoker or crafting table is never
            // thrown, the furnace, smoker or table a smelt or craft put down goes back in the bag (StationsBack), and the food is
            // cooked in the bag's furnace rather than a smoker made of it; a witch is no melee fight, a run out of its range
            // (CombatCommit), and the poison still to come counts with the burn (DangerShadow); the tunnel of its own keeps the
            // tick on ice or open water for its whole time (BranchMineTask))
            java.util.Map.entry("food_one_trip", 1.0),
            java.util.Map.entry("bag_junk_first", 1.0),
            java.util.Map.entry("bag_keep_stations", 1.0),
            java.util.Map.entry("witch_no_melee", 1.0),
            java.util.Map.entry("ice_start_tunnel_hold", 1.0),
            // (fixes of 13 Nov 2026, route-60 and route-61's phase 2: with no fire resistance the trade walks off the blazes and
            // never into the blaze camp, TradeBlazeZones; wastes come into view give the search 120 s more before the bound; no
            // punch of a ghast's ball with a zombified piglin within 6 of the buddy or the ball)
            java.util.Map.entry("trade_blaze_off", 1.0),
            java.util.Map.entry("trade_wastes_grace", 120.0),
            java.util.Map.entry("ghast_punch_zp_near", 6.0),
            // (fixes of 13 Nov 2026, phase 7's six stalls with 9 rods: the way into a portal the tracker knows gets the kept
            // walk's stall rule, its frame passed over; the exit's cap only once no walk came closer in 120 s, twice the cap at most)
            java.util.Map.entry("exit_tracked_stall", 1.0),
            java.util.Map.entry("exit_cap_progress", 1.0),
            // (fixes of 13 Nov 2026, test heel's first six deaths in the overworld, all within 30 game minutes of an empty bag: the
            // buddy's night shelter digs in two deep where the buddy stands, every full block in the bag counted for the walls and
            // the top (the buddy's ObtainSafe); unarmed, a run with no room puts a block between the buddy and the mob or digs
            // down, and is a fight only with nothing to do that with (CombatCommit, CornerWallTask))
            java.util.Map.entry("shelter_dig_here", 1.0),
            java.util.Map.entry("unarmed_no_corner_fight", 1.0),
            // (his word, steering 182, 13 Nov 2026: the trade goes on to a second fire resistance potion before the fortress)
            java.util.Map.entry("route_trade_potions", 2.0),
            java.util.Map.entry("route_trade_hoglin_buddy", 0.0),
            // (11 Nov 2026, a fix: the pillbox the route's brew was made in stayed the fight's camp and corner under the brewed
            // potion, as the brewed one followed the traded one with no gap; once the brew is through and the potion on, the camp
            // is picked again as the fire-proof one, at the box's open side; CollectBlazeRodsTask, E1111-1500.1)
            java.util.Map.entry("brew_camp_repick", 1.0),
            // (11 Nov 2026, a fix: once the batch's potions are out of the stand the brew is done; the bag's spare water bottle
            // started a second batch and the brew was given up for no cream with its potions in the bag, BrewFireResistanceTask)
            java.util.Map.entry("brew_once", 1.0),
            // (confirmed 12 Nov 2026, realfort-doorlopen-mc1 at look 3: phase 3 passed with the wart 57 against 70 of 90 pairs,
            // z 2.41, fewer deaths a blaze: in phase 3 no walk-away from a blaze's sight, BlazeFight)
            java.util.Map.entry("route_errand_noaway", 1.0),
            // (12 Nov 2026, a fix of the slime ball's fetch, his "test it" of steering 168: with no slime in sight in the
            // overworld, a branch mine at y 8 instead of a walk over the ground; slimes live below y 40, KillAndLootTask)
            java.util.Map.entry("slime_dig", 1.0),
            // (12 Nov 2026, a fix: the route brews one batch; past it no brew box is planned again, CollectBlazeRodsTask)
            java.util.Map.entry("route_brew_one", 1.0),
            // (12 Nov 2026, a fix: at a camp spot only a fire within a block of its radius is put out, CollectBlazeRodsTask)
            java.util.Map.entry("fire_at_spot", 1.0),
            // (12 Nov 2026, a fix, look 62: no box by the spawner that needs more blocks than the bag holds, CollectBlazeRodsTask)
            java.util.Map.entry("pillbox_bag_check", 1.0),
            // (12 Nov 2026, a fix of M3.3: raw gold smelted in batches of 16, not all at the end, CollectGoldIngotTask)
            java.util.Map.entry("gold_batch", 1.0),
            // (12 Nov 2026, a fix, look 63: under the lava the kept potion goes down at once, RouteRules.wayLetGo)
            java.util.Map.entry("route_hold_under", 1.0),
            // (12 Nov 2026, steering 170: far cave gold with 15 armour only at y 0 or above and not beside lava, MineAndCollectTask)
            java.util.Map.entry("gold_cave_safe", 1.0),
            // (12 Nov 2026, a fix: with a calm neutral by the target and no axe, the pickaxe, which never sweeps, WeaponPick)
            java.util.Map.entry("neutral_pick", 1.0),
            // (12 Nov 2026, a fix: under fire resistance no shield for a blaze's small fireball, MobDefenseChain)
            java.util.Map.entry("fr_no_fireball_shield", 1.0),
            // (12 Nov 2026, a fix meant on since 6ac7c955 but left out of this list, so off: under fire resistance a hiding place
            // from before the potion is left; one kept leashed the fight to its pocket through a whole traded potion, 75 blazes
            // let go and no kill, route-51 on box 2)
            java.util.Map.entry("fr_hide_drop", 1.0),
            // (12 Nov 2026, his yes to steering 170's plan: the gold mine at feet -17, the sculk walk-away doubling capped at 96
            // blocks, BranchMineTask)
            java.util.Map.entry("gold_mine_y", -17.0),
            java.util.Map.entry("sculk_away_cap", 1.0),
            java.util.Map.entry("route_hold", 1.0),
            java.util.Map.entry("route_hold_fight", 1.0),
            // (route jar 3: on the way, before the spawner fight, the potion goes down only in lava for 2 s or at 12 hp, or
            // burning at 10 hp, RouteRules.wayLetGo)
            java.util.Map.entry("route_hold_way", 1.0),
            java.util.Map.entry("route_rods2_camp", 1.0),
            java.util.Map.entry("route_trade", 1.0),
            // (route jar 4, 9 Nov 2026: a piglin is passed over only while a hoglin is within 12 of it or of the buddy, 0 the
            // old 64 from the piglin; the trade's dead end counts only time with no piglin known, 0 the old clock of no ingot
            // taken, FireResTradeRules.decideRoute)
            java.util.Map.entry("route_trade_hoglin", 12.0),
            java.util.Map.entry("route_trade_known", 1.0),
            java.util.Map.entry("run_bite_wither", 14.0),
            java.util.Map.entry("run_shelter_wither", 1.0),
            java.util.Map.entry("run_turn_fight", 1.0),
            java.util.Map.entry("spawner_wait_cap", 90.0));

    private static Path file;
    private static Properties props = new Properties();
    private static long fileTime = Long.MIN_VALUE;
    private static long lookedAt;

    private Tuning() {
    }

    public static synchronized double get(String key, double fallback) {
        long now = System.currentTimeMillis();
        if (now - lookedAt > 1000) {
            lookedAt = now;
            Path f = file();
            long t;
            try {
                t = f != null && Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
            } catch (Exception e) {
                t = 0;
            }
            if (t != fileTime) {
                fileTime = t;
                Properties p = new Properties();
                if (t != 0) {
                    try (var in = Files.newInputStream(f)) {
                        p.load(in);
                    } catch (Exception ignored) {
                        // a file being written: the next look reads it
                        fileTime = Long.MIN_VALUE;
                    }
                }
                props = p;
                if (!p.isEmpty()) adris.altoclef.Debug.logInternal("tuning: " + p);
            }
        }
        String v = props.getProperty(key);
        double base = DEFAULTS.getOrDefault(key, fallback);
        if (v == null) return base;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return base;
        }
    }

    // a key read from the file as it is now, not as it was up to a second ago: for what is read once at an order's start
    // (fresh_run, RunMemory), where the rig writes the file just before the order. the rig replaces the file whole, so no
    // half-written file is read
    public static synchronized double now(String key, double fallback) {
        lookedAt = 0;
        fileTime = Long.MIN_VALUE;
        return get(key, fallback);
    }

    // tuning.properties in the game directory (the system property buddy.tuning first, for tests); null before the game is up
    private static Path file() {
        String prop = System.getProperty("buddy.tuning");
        // (followed when a test points it at another file: each test of a class may have its own)
        if (prop != null && (file == null || !file.toString().equals(prop))) return file = Path.of(prop);
        if (file != null) return file;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) file = mc.gameDirectory.toPath().resolve("tuning.properties");
        } catch (Throwable ignored) {
            // no game (a unit test): the defaults
        }
        return file;
    }

    // every key in force now, the defaults and what the file sets, sorted (the snapshots' header, Snapshots)
    public static synchronized java.util.Map<String, String> all() {
        get("", 0);
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        for (var e : DEFAULTS.entrySet()) out.put(e.getKey(), String.valueOf(e.getValue()));
        for (String k : props.stringPropertyNames()) out.put(k, props.getProperty(k));
        return out;
    }
}
