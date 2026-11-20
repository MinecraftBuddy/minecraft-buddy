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
            // (13 Nov 2026, off: with 2 or 3 wither skeletons near at 14 hp or more, the fight from a cell they cannot enter, a
            // 2-high gap found or dug, instead of the run; its knobs wither_gap_hp, wither_gap_leave_hp, wither_gap_search and
            // the rest have their fallbacks in WitherGapRules)
            java.util.Map.entry("wither_gap", 0.0),
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
            // (fixes of 13 Nov 2026, test heel's five skeleton deaths in the overworld, a block between in the bag every time: under
            // skel_cover_hp (18) with a skeleton, stray, bogged or parched in sight within skel_cover_range (16), a block between or
            // two down, the meal and the regen there, and no run from their arrows; a walk up to one only at skel_close_hp (16) or
            // more, with a sword, armour on and no second one in sight (SkelCover, SkelCoverTask, CombatCommit); the nether's rules
            // unchanged)
            java.util.Map.entry("skel_cover", 1.0),
            java.util.Map.entry("skel_close_rule", 1.0),
            // (fixes of 14 Nov 2026, test heel's 23 skeleton deaths since the cover, mostly early with no armour at night: the cover
            // digs in first, two down with the top shut, a pillbox from the bag where the ground will not dig, a hole with no top
            // failed, an arrow in cover the next block or the dig; out of cover at full hp, or at the line with no line for 5 s,
            // the stall only into no line; off the ground a second (in water) no cover; the dug-in cover in place of the low hp,
            // crowd and unarmed runs with melee mobs about too, and a run under the line that takes an arrow digs in where it
            // stands (SkelCover, SkelCoverTask, CornerWallTask, CombatCommit, CombatBrain))
            java.util.Map.entry("skel_cover_dig_first", 1.0),
            java.util.Map.entry("skel_cover_hold", 1.0),
            java.util.Map.entry("skel_cover_wet_fail", 1.0),
            java.util.Map.entry("skel_cover_over_runs", 1.0),
            // (fixes of 13 Nov 2026, test heel's night deaths, two stalls of five minutes at full hp with blocks in the bag and one
            // bare-handed fight: the night shelter's dig with no block dug in 15 s shuts the buddy in on the spot from the bag,
            // the breaker's clock kept for the spot (the buddy's ObtainSafe); while a meal is under way the buddy's own steps leave
            // the hotbar alone, and a bite cut off five times in 10 s backs off 3 s (the buddy's ObtainSteps and Work, FoodChain);
            // unarmed, the chased-too-long turn puts up the cornered run's wall instead of the fight (CombatCommit))
            java.util.Map.entry("shelter_dig_clock", 1.0),
            java.util.Map.entry("meal_hand_hold", 1.0),
            java.util.Map.entry("unarmed_no_chase_fight", 1.0),
            // (fixes of 13 Nov 2026, test heel's whole-game run, 45 minutes in a flooded cave at y 54 to 62 for copper at 48: in
            // water under a roof a branch mine first gets out of the water to the nearest dry cell, as in open water, and the
            // place watch fired three times in ten game minutes at one spot gives up the depth there, BranchMineTask; a route
            // piece that failed waits ten minutes while the phase's next piece goes on, the buddy's ObtainRoute)
            java.util.Map.entry("mine_water_roof", 1.0),
            java.util.Map.entry("kit_failed_skip", 1.0),
            // (fixes of 13 Nov 2026, test heel's night shelter, three stalls and a death, read for the buddy's ObtainSafe: a dig that
            // stops short on open ground (snow and no shovel) shuts the buddy in on the spot, walls first, past a cell that will not
            // shut; with the walls up and no top to be had, a block on a wall from the top of a jump and the top against it; the
            // placer clicks on a one-layer snow or short grass in the cell, and gives a cell up after 5 s aimed with no block in it;
            // a furnace or a chest is no face to shut the top against, for the shelter as for the placer, and no dig down after all
            // where the dig cannot run (five silent minutes beside a furnace))
            java.util.Map.entry("shelter_refused_walls", 1.0),
            java.util.Map.entry("shelter_jump_top", 1.0),
            java.util.Map.entry("place_into_replaceable", 1.0),
            java.util.Map.entry("place_click_clock", 1.0),
            java.util.Map.entry("shelter_place_face", 1.0),
            // (fixes of 13 Nov 2026, the test heel's armour and gold read: logs and planks in the bag burn in the furnace past the
            // wood the task under way still crafts from, and a fuel trip a few smelts short or with no coal ore near fetches logs,
            // not a coal mine (WoodFuel, CollectFuelTask); the wooden pickaxe a task wants is a stone one with three stone in the bag
            // (SatisfyMiningRequirementTask); a branch mine that leaves its band twice at one place keeps a tunnel of its own there,
            // and the place watch's second fire gives the depth up; a mine below 0 starts wherever the buddy is between y -48 and 0
            // after a drift or a fall (BranchMineTask); a creeper step with no safe heading puts a block between or digs one down,
            // and the aura hits a mob in reach while the buddy stands (CreeperStepTask, CornerWallTask, MobDefenseChain))
            java.util.Map.entry("wood_fuel", 1.0),
            java.util.Map.entry("stone_pick_first", 1.0),
            java.util.Map.entry("mine_own_pit", 1.0),
            java.util.Map.entry("gold_wide_band", 1.0),
            java.util.Map.entry("creeper_no_heading_block", 1.0),
            // (a fix of 13 Nov 2026, test heel's flooded cave, a kit piece's mine stalled on a ledge for each piece and try: a
            // branch mine that starts or runs within 24 blocks of where a mine gave its depth up in the last 30 game minutes first
            // leaves it 48 blocks by baritone's goal path, three ways at most; the way down through the open that gets nowhere
            // three times within ten blocks counts as a fire of the place watch; a fire leaves a walk along under way going,
            // BranchMineTask)
            java.util.Map.entry("mine_leave_given_up", 1.0),
            // (a fix of 14 Nov 2026, a live look at the test heel's branch mine for iron at y 16, an underground pool at y 22 inside
            // its band: the walk out of water under a roof and the staircase's step back into it took turns about once a second,
            // in laps of 60 to 75 s that no watch saw: a walk-out that starts where the last one began or ended, or by the last
            // dry cell, is counted by place, kept over a task made anew, and the third within ten blocks counts as a fire of the
            // place watch, so the second such fire gives the depth up and the mine leaves 48 blocks; the staircase's and the
            // tunnel's step keeps a block from the water cell walked out of and takes a way into the open before one into water;
            // a dry cell not reached in time is passed over for a game minute, BranchMineTask, MineLeave)
            java.util.Map.entry("mine_water_streak", 1.0),
            // (his word, 14 Nov 2026, the test heel's gold: the gold mine (and the slime dig at its height) digs poke holes off its
            // tunnel, a 1x1 hole 4 deep each side at head height every 4 steps, dug standing, legs 12 apart, out of caves; for gold
            // the band it stays in is -28 to -4; its rock goes to an iron pickaxe the kit can spare, BranchMineTask, PokeMineTask,
            // ToolPick)
            java.util.Map.entry("gold_poke_shafts", 1.0),
            java.util.Map.entry("gold_band_core", 1.0),
            java.util.Map.entry("gold_rock_iron", 1.0),
            // (fixes of 13 Nov 2026, test heel's night start with an empty bag on grass over stone, read for the buddy's ObtainSafe:
            // with fewer than 5 blocks to build with, no shooter close and no dig here, the night shelter first breaks up to 6
            // surface cells of grass or dirt within 3 blocks by hand and picks the dirt up; ground counts as dug for the shelter only
            // with a tool that takes it out (stone only with a pickaxe), so no site of stone is walked to with an empty hand; with
            // an empty bag and a cell that will not shut, a cell within 16 where two of ground come out by hand, and the buddy
            // digs in there and shuts the top with the dug block)
            java.util.Map.entry("shelter_blocks_first", 1.0),
            java.util.Map.entry("shelter_tool_dig", 1.0),
            java.util.Map.entry("shelter_hand_dig", 1.0),
            // (a fix of 14 Nov 2026, a live look at the test heel's night shelter, a cell that would not shut with 12 logs and an
            // axe and a wait half shut for good: once the three tries elsewhere are spent the dig-in by hand is the way out with
            // blocks in the bag too, and a try elsewhere that leaves the feet where they were keeps the shut-in on the spot, so the
            // other walls and the top still go up round the cell that will not shut, the buddy's ObtainSafe and DigIn)
            java.util.Map.entry("shelter_stuck_way_out", 1.0),
            // (a fix of 14 Nov 2026, a live look at the test heel's night shelter on a bare start in a forest's canopy, oak leaves
            // at y 75 and the floor 5 to 9 below, "shut but for 8 cells" with no block in the bag till a phantom and spiders drove
            // the buddy down: on leaves or a log, or more than four over the ground under the feet, the night shelter walks down to
            // the nearest ground first; its sites are measured from the ground under the feet, and with no block in the bag and
            // nothing under the feet to dig there is no shut-in on the spot, the buddy's ObtainSafe and DigIn)
            java.util.Map.entry("shelter_down_from_tree", 1.0),
            // (fixes of 14 Nov 2026, two live looks at whole runs from a bare start, the test heel: M3's kit lost on the way and no
            // sticks deep down)
            // standing in water with no empty bottle (or bucket) in the bag, the fill's own branch is skipped, so the bottles'
            // table walk and craft run to their end: it flipped every 3 s between "in it" and "getting bottle", 102 table walks
            // begun in 6 minutes, CollectBucketLiquidTask
            java.util.Map.entry("bottle_in_water_fix", 1.0),
            // a crafting table a craft put down goes back in the bag when the craft is cut (a fight, the chain took over, the
            // parent moved on), not only when it is done; while a fight holds the craft the table waits, and one that cannot be
            // broken at once is fetched within 32 blocks for five game minutes when the bag has none, CraftInTableTask, StationsBack
            java.util.Map.entry("table_back_on_cut", 1.0),
            // a full bag throws natural junk first (moss, calcite, basalt, tuff, diorite, flowers, seeds...) and no kit item
            // (tools, the bucket, glass, bottles, wood, sticks, food, gold, obsidian, the brew's makings) while anything else
            // may go; an iron or better pickaxe is never the worse of two tools. it threw the glass and the bucket with moss and
            // basalt in the bag, GarbageRules, StorageHelper (bag_junk_first, of 13 Nov, keeps its own tier ahead of these)
            java.util.Map.entry("bag_kit_last", 1.0),
            // the furnace burns no wood under a reserve of 3 planks (a log) and 2 sticks' worth; a branch mine makes sticks while
            // the bag has the wood for them past that reserve, and the next pickaxe (iron from 3 ingots, else stone) while the
            // last one above wood is still good for the last of its uses, WoodFuel, StickReserve, BranchMineTask
            java.util.Map.entry("stick_reserve", 1.0),
            // under a roof with the log more than 16 above the feet, a timed-out search to it strikes no tree: up to the surface
            // first (baritone's goal of the sky's height over the column, which takes the way it came, digs or pillars), and
            // three trees given up in a row with no log mined is "logs out of reach from here" and the same climb; a tree's ban
            // that ends is one line, MineAndCollectTask, LogReach, Bans
            java.util.Map.entry("log_far_surface", 1.0),
            // the buddy's route (ObtainRoute): before the portal a crafting table while none is in the bag and less than 4 planks
            // of wood, and the M3 kit's missing pieces asked again, each with its three tries (his word: M4 begins with the
            // whole kit)
            java.util.Map.entry("m34_kit_again", 1.0),
            // (a fix of 14 Nov 2026, a live look at the test heel's flooded start, look 92) the leave from a spot a mine gave up ends
            // only on dry ground 48 from every fresh spot given up, its goal moved on along the way while it does not, the next way
            // past 120; the land walked to from open water is one 48 from every such spot when one lies within 64; the place watch
            // and its fires are kept over a task made anew, BranchMineTask, MineLeave, LandFinder
            java.util.Map.entry("mine_leave_dry", 1.0),
            // (fixes of 14 Nov 2026, a live look at the test heel's night start with an empty bag on bare stone, a stony shore rim:
            // no ground the hand digs, no hole, three tries elsewhere at the same feet and then a wait in the open till morning; a
            // second night on a snowy slope, a wall cell over a drop that would not shut, the hand dig elsewhere 2 min 20 s after
            // the third try; the buddy's ObtainSafe and DigIn)
            // with fewer than 5 blocks in the bag and no hole to dig: logs within 24 punched by hand, up to 3, made into planks in
            // the 2x2 grid when no monster is about, then the shelter again where the buddy stands; at blocks first when the ground
            // the hand breaks gave too few, and in place of the open wait once no try elsewhere and no dig-in by hand is left
            java.util.Map.entry("shelter_logs_first", 1.0),
            // with fewer than 5 blocks in the bag and no site to dig within 16, no tries elsewhere: they kept the same feet
            java.util.Map.entry("shelter_skip_tries", 1.0),
            // the tries elsewhere spent and no cell to dig in by hand: with blocks enough for the walls and the top, a walk to the
            // nearest open cell within 24 whose four walls stand on ground, and a shut-in on the spot there
            java.util.Map.entry("shelter_flat_walk", 1.0),
            // (a fix of 14 Nov 2026, a live look at the test heel's night start on frozen-ocean ice: an empty bag, no ground the hand
            // breaks, the nearest log about 70 blocks off, "cannot shut, blocks 0" every 30 s for 6 minutes in the open, a creeper,
            // and after its run of 24 blocks no new search) with no way to shelter left (too few blocks, the tries spent, no walk by
            // hand or to flat ground, logs first used up or none in reach) the night's hole of the whole order gives up for now and
            // the order's step runs; a new hole looks again once the bag holds 5 or more blocks or logs, or 16 or more blocks from
            // where it last searched, 30 s after at the soonest (the buddy's ObtainSafe, Obtain, DigIn)
            java.util.Map.entry("shelter_none_go_on", 1.0),
            // (a fix of 14 Nov 2026, a live look at the test heel's flint loop: a gravel column at a pond's edge, the flint rolled
            // into the water, each detour out of time, its blocks banned one by one, and the gravel that fell in from above
            // started the next detour at the same place for 3 to 5 minutes, 159 ban lines) a detour for a falling kind (gravel,
            // sand) that ends out of time, led too far, or capped with nothing got bans the columns of its box as one area for the
            // ban's time; gravel or sand with water beside it or beside the cell under it is not started on, TakeAlongChain,
            // FallBans
            java.util.Map.entry("take_along_falling_ban", 1.0),
            // (fixes of 14 Nov 2026, a live look at the test heel's gold mine at y -17: the ore's worn iron pickaxe and the rock's
            // worn stone one took turns in hotbar slot 5, about four swaps a second for 18 minutes, each a pause of the pathing, so
            // no path search ran; gold_rock_iron, which would have given the rock the iron one too, never ran: its once-a-second
            // clock began at Long.MIN_VALUE and the overflow said "looked a moment ago" for ever, a bug fixed with no key, Recent)
            // a hotbar slot a swap of HotbarTools filled is not emptied for another tool for 5 s, and the same pair of slots
            // swapped three times within a minute is refused for a minute, HotbarTools, SwapGuard
            java.util.Map.entry("hotbar_swap_guard", 1.0),
            // a run of the inventory pauser's pauses holds the pathing about 2 s at most, then 3 s with no pause, PauseCap
            java.util.Map.entry("inv_pause_cap", 1.0),
            // (a fix of 14 Nov 2026, a live look at the test heel's tunnel flip: a tunnel of its own at y 16 met water two cells ahead,
            // turned half round into the tunnel just dug, and from there the only closed way was back: 1042 half turns between two
            // cells in 322 s, each step reached at once, so no watch fired but the place watch, whose remedy was the same tunnel)
            // a step that turns half round against water keeps the step it refused for a game minute, and a way whose step comes
            // within a block of it is taken only when every way is like that; the tunnel's step back into the cell just left, the
            // step before back too (A to B to A), is a fire of the place watch, so the second gives the depth up; the place watch
            // fired in the tunnel of its own (mine_own_pit) gives the depth up; every turn of the step is logged, BranchMineTask,
            // TunnelFlip
            java.util.Map.entry("tunnel_flip_guard", 1.0),
            // (a fix of 14 Nov 2026, a live look at the test heel's flooded start, look 97: the leave from the flooded cave walked 120
            // blocks with no dry ground and ended on a ledge at the sea's height, dry for one tick of onGround; then at sea by a
            // one-block islet, the land picked, the walk to it and the staircase's step back into the sea took turns every tick for
            // 11 minutes, and the walk-outs counted only under a roof) the walk to land from open water is held until the buddy
            // stands on dry ground: a cell to stand in with no fluid in its four neighbours or in those of the block under it; no
            // staircase starts until 10 s after the last tick in open water, and the tunnel's hold on water (ice_start_tunnel_hold)
            // counts those 10 s as water; only such dry ground is land for that walk (looked for out to 96 blocks, as off the ice)
            // and ends the leave from a spot given up; a walk-out of open water counts with those under a roof (mine_water_streak),
            // BranchMineTask, LandFinder
            java.util.Map.entry("open_water_land_hold", 1.0),
            // (fixes of 14 Nov 2026, a read of 8 of test heel's 14 witch deaths since 13 Nov, 11 at the gold mine: the poison first,
            // then harming every 3 s within 10 with a line, 15 to 30 s to death; the run from the witch measured from the buddy's
            // own spot ended clear at 12 with the witch following, no bite while it had a line, no swing at 1 m with the run stuck)
            // a witch's hit, or a poison tick with a witch within 16, is the skeleton cover in place of the run: two down with the
            // top shut, the pillbox from the bag where the ground will not dig, the meal inside, out once the poison is over, the hp
            // is back to the cover's rule and no witch has had a line for 5 s; in water no cover (WitchRules, SkelCover,
            // SkelCoverTask, CombatCommit, CombatBrain)
            java.util.Map.entry("witch_cover", 1.0),
            // a witch within reach is a melee foe with a sword or an axe at hand and no other shooter with a line within 16, and in
            // any hand once the run is cornered or the cover is hit through (CombatCommit, CombatBrain)
            java.util.Map.entry("witch_melee_reach", 1.0),
            // poisoned, a witch 5 or more off or with no line keeps no bite shut, and a golden apple goes down under 10 hp
            // (CombatBrain, FoodChain)
            java.util.Map.entry("poison_eat", 1.0),
            // (fixes of 14 Nov 2026, a read of early deaths in the test heel, whole runs from an empty bag at 1x) a melee mob on a
            // mount (a zombie on a zombie horse with a spear, a chicken jockey) is never run from on foot: a mount outruns the
            // buddy, and a night start's run of 93 blocks uphill took four spear hits from 3 blocks off ('speared by Zombie' 15
            // times in the records since 13 Nov); with a sword or an axe at 14 hp or more it is fought, else the dug-in cover
            // takes the buddy where it holds (two down with the top shut, or a shut pillbox), held while a mounted mob is within
            // 16 and 5 s after; a run under way ends there, and a fight's bail goes into the cover; with no cover to be had the
            // run as before (MountRules, CombatCommit, CombatBrain, SkelCover)
            java.util.Map.entry("mounted_no_run", 1.0),
            // the skeleton cover's pillbox on flat bare ground gets its top: the top cell over the head has no face to go against
            // there (1 of 5 cells shut and no top, then a knock off the spot, the run and 7 arrows), so a block goes on a wall
            // cell's top first, clicked at the top of a jump (the wall's top face is in sight only with the eye over it), and the
            // top against its side; before a side goes in the buddy steps to the middle of the cell, so the body no longer stands
            // in the cells toward the skeleton (SkelCover, SkelCoverTask)
            java.util.Map.entry("pillbox_top_support", 1.0),
            // inside a shut cover (the four cells at the eye's height and the one over the head solid) a melee mob at the walls
            // is no reason to leave it: no fight or run is started on it while the cover holds, and the aura swings at one that
            // hits in reach with a line, under the flee line too; a shut pillbox was left at once for a fight with a spider and
            // skeletons and a zombie killed the buddy outside it (SkelCover, CombatCommit, CombatBrain, MobDefenseChain)
            java.util.Map.entry("pillbox_hold_melee", 1.0),
            // (fixes of 14 Nov 2026, a look at the test heel's table and fuel loop: the furnace for the chestplate's 8 ingots put the
            // crafting table down, the table out of the bag held one more log back from the fuel, 9.0 smelts became 7.5, the smelt
            // left the furnace craft for a fuel trip, the cut table went back in the bag before the trip reached its log 9 blocks
            // off, 9.0 again, the furnace craft again, the table down again: 25 minutes in the open, three hits, the night's shelter
            // held back) a crafting table standing within the bench's reach (the craft's own, put down or going down, or any within
            // WalkCost.STATION_NEAR) counts as one in the bag for the wood the furnace keeps, so putting it down never changes the
            // fuel count, WoodFuel, CraftInTableTask
            java.util.Map.entry("fuel_table_standing", 1.0),
            // a cut craft's table is not taken back while the task that ticked the craft still runs (not the run's wrapper) and
            // the craft's item is not in the bag, three game minutes from the cut at most, StationsBack
            java.util.Map.entry("cut_parent_hold", 1.0),
            // at most two take-backs of a cut craft's table at one spot (4 blocks across) in a game minute; past that the table
            // is left standing, with a line, StationsBack
            java.util.Map.entry("stations_back_cap", 1.0),
            // (fixes of 14 Nov 2026, look 98, test heel's worn pickaxes: the gold mine ran two and a half hours and ended short at
            // 75 of 96 ingots; the kit's diamond pickaxe dug the rock in every gap with no stone one in the bag, 1561 uses to 0,
            // the batch of stone pickaxes spent the last planks, and from then on there was no pickaxe at all for half an hour)
            // the kit's diamond pickaxe stays off a block a stone pickaxe takes while another pickaxe digs it, and with none the
            // mine makes a stone one first, the keep's sticks too; with nothing to make one from, a line, and the diamond one
            // digs (ToolPick, HotbarTools, BranchMineTask)
            java.util.Map.entry("kit_diamond_off_rock", 1.0),
            // the mine's batch of stone pickaxes for the rock counts only the sticks and planks past StickReserve's keep
            // (BranchMineTask.cheapPick)
            java.util.Map.entry("cheap_pick_keep", 1.0),
            // the next pickaxe: a diamond one from three diamonds when no iron or stone one can be made, and up to the surface for
            // three logs when the wood is what the bag lacks, the log climb of log_far_surface (StickReserve, BranchMineTask)
            java.util.Map.entry("next_pick_more", 1.0),
            // a branch mine for an ore stone cannot take (gold, the slime dig's, diamonds) neither starts nor goes on with no
            // pickaxe above wood in the bag: the next pickaxe first, from the bag, by the trip up for logs, else the catalogue's
            // stone pickaxe (BranchMineTask)
            java.util.Map.entry("slime_dig_needs_pick", 1.0),
            // (a fix of 13 Nov 2026, test heel's first stone under the rigs' anti-xray, where every buried block below y 64 reaches
            // the client as an ore: with legitMine on in the overworld and the best stone more than 8 blocks below the feet, or
            // none known, a pit four deep at the feet first, so the real stone shows and the look round the feet takes it,
            // MineAndCollectTask)
            java.util.Map.entry("stone_pit_first", 1.0),
            // (a fix of 14 Nov 2026, test heel's first stone: the pit's searches through the fake ores found no path, "no stone in
            // 30 s" with no block dug, twice, then 8 minutes of no-path bans) the pit is dug by breaking the block under the feet
            // one at a time, standing on it, the world read again after each break; a pit that dug nothing does not count
            // against the two a task, and the pit's column is refused where Baritone would refuse a block of it (water beside,
            // MineAndCollectTask, StoneDigRank)
            java.util.Map.entry("stone_pit_direct", 1.0),
            // (fixes of 14 Nov 2026, a read of 22 test heel runs that reached the gold and lost it) the gold mine and the slime dig go
            // down from the surface with two pickaxes above wood besides the kit's diamond one and the wood for one more, fetched up
            // there (BranchMineTask, StickReserve.surfaceReady): three runs stalled 35 to 45 minutes in a cave, the logs on the
            // surface out of reach
            java.util.Map.entry("mine_kit_surface", 1.0),
            // with no pickaxe above wood, the log climb walks back up the way the buddy came down (DownTrail, TrailBackTask) before
            // the climb by hand, and the next pickaxe is made at a crafting table standing within the bench's reach when the table
            // is what the wood lacks (MineAndCollectTask, BranchMineTask): no pickaxe at y 10 to 26 in a dripstone cave, every
            // climb by hand "no way up"
            java.util.Map.entry("pickless_way_out", 1.0),
            // (fixes of 14 Nov 2026, two test heel runs) under the overworld's anti-xray the tunnel's column scan ends at a buried
            // cell that is no stone and no plain ground (it may be a fake), a band under the stone the pick had is not latched, and
            // a band whose picks end no path three in a row is let go, so the pit may start (MineAndCollectTask, StoneDigRank): a
            // band latched on a cave's ceiling 16 under the feet, past the fake ores, took 45 minutes and 777 no-path bans with no
            // cobblestone and no pit
            java.util.Map.entry("tunnel_no_fakes", 1.0),
            // with no pickaxe above wood the food climb walks back up the way the buddy came down first (DownTrail, TrailBackTask;
            // it needs pickless_way_out, which keeps the trail), the staircase by hand only from where that walk ends short
            // (SurfaceFoodTask): with no pickaxe at y 13 to 46 in a cave, 360 "No path found" in 32 minutes climbing by hand
            java.util.Map.entry("food_climb_trail", 1.0),
            // a mob that strikes out again on "couldn't reach": 60 s, 120 s, 240 s, then for the run or until it hits us (Bans,
            // BanPolicy): one zombie banned 25 times in 40 minutes, 60 s each time
            java.util.Map.entry("entity_ban_grow", 1.0),
            // a spot a branch mine left, or gave up leaving, is not left again by a later mine of the order (MineLeave, BranchMineTask):
            // the route's switch between the helmet and the gold made a new task that set out from the same spot again
            java.util.Map.entry("mine_leave_once", 1.0),
            // the poke holes take the wanted ores they showed, in reach, with no hostile mob near (ShownOres, PokeMineTask): before
            // the golden helmet 3 of 18 shown ores were mined
            java.util.Map.entry("poke_take_shown", 1.0),
            // (the buddy's route, Alto.Run, ObtainRoute.goldClock) the gold piece of M3.3 runs on past its 40 minutes while the gold
            // in the bag rose within the last 20, all its tries together six game hours at most: 5 of the 22 runs gave the gold up
            // while it still rose, one at 90 of 101
            java.util.Map.entry("gold_runs_on", 1.0),
            // at night the skeleton cover's far exit (no skeleton within 20 for 5 s) waits for full hp (SkelCover.Hold, CombatBrain):
            // early deaths since heel-17 ran at 6 to 7 hp in the dark after the cover was left
            java.util.Map.entry("cover_night_hold", 1.0),
            // (fixes of 14 Nov 2026, the first run from a bare start that met the whole kit: 10 rods, dead on the way out) phase 7
            // goes out by the portal the buddy came in by first, and a frame passed over stays out of every pick, a live portal's
            // cells too (RouteRules.exitOwnFirst, EnterNetherPortalTask.standableCell): the exit picked a portal across the lava
            // sea twice in the tick it passed it over
            java.util.Map.entry("exit_own_portal", 1.0),
            // the exit's walk with no block it may place builds with the bag's cobblestone above the stand's keep (CollectBlazeRodsTask,
            // AltoClefSettings.capReserve): the buddy stood two minutes on a pillar in the lava sea with 37 cobblestone counted as
            // spoken for by the levels of user tasks finished long before
            java.util.Map.entry("exit_free_stone", 1.0),
            // a user task done with the crafting table its last craft put down still standing hands it to the next task's way back
            // (StationsBack.rootDone): a finished root is dropped without a stop, so the table stayed and was nobody's
            java.util.Map.entry("root_done_table", 1.0),
            // a table, furnace or smoker whose walk got "0.0 blocks" from where the buddy stands is banned at once
            // (DoStuffInContainerTask, PathingBehavior.noStepHere): two minutes of such searches before the fifth miss banned it
            java.util.Map.entry("station_no_step_ban", 1.0),
            // a branch mine made anew within 48 blocks of the tunnel of its own an earlier mine began at its height goes on with
            // that tunnel, and the band exits that lead to one count over mines made anew (OwnTunnel, BranchMineTask): a pickaxe
            // craft made the mine anew twice and each forgot its tunnel, 27 minutes of the iron leggings at 6 of 7 raw iron
            java.util.Map.entry("mine_own_keep", 1.0),
            // baritone's mine stopping for no path twice within ten game minutes and 100 blocks turns the branch mine to a tunnel
            // of its own, a quarter turned; a second such turn within 100 blocks in 30 game minutes gives the depth up
            // (OwnTunnel, BranchMineTask, MineProcess.noPathStops): it shuttled 88 blocks through an old tunnel, 43 such stops
            java.util.Map.entry("mine_no_path_tunnel", 1.0),
            // at 8 hp or less the skeleton cover's give-up (an arrow in the shut hole, arrows in the pillbox, no block of it in)
            // digs two down and shuts the top instead, once a cover (SkelCover.lowDig, SkelCoverTask): since heel-17 the cover
            // gave up in 4 of 23 runs and the buddy ran in the open at 6 to 7 hp and died
            java.util.Map.entry("cover_low_dig", 1.0),
            // (the buddy's route, Alto.Run, ObtainRoute.goldOver) a gold try stopped from outside banks its time on the game clock,
            // not the wall clock, and M3.4's gold runs on M3.3's clock and budget, not mined again once its six game hours are
            // spent or its last try ended with no rise: the six hours fired a median 52 minutes into M3.3, and M3.4 then asked
            // the gold as three plain 40-minute tries, 31 of the 65 runs lost in M3.4
            java.util.Map.entry("gold_clock_fix", 1.0),
            // the buddy's gold try (M3.3's or M3.4's gold piece) in a gold mine with no unworn pickaxe above wood left and none of
            // iron or diamond to make from the bag ends as "pickaxes worn", the kit's worn iron and diamond pickaxes kept
            // (BranchMineTask.picksWorn): a stone one was made and the kit's wore through on the ore, then M3.4 asked them again
            // under the ground with no wood, 6 stalls of heel-27/28
            java.util.Map.entry("gold_keep_picks", 1.0),
            // a frame cell that will not go in drops the portal's site after 3 clicks given up on it or 2 minutes of placing with no
            // frame block put in; a new site at least 8 blocks from every dropped one, which stays out for the run
            // (ConstructNetherPortalObsidianTask.siteGivenUp): the click banned its block, wandered and came back to the same cell
            // 47 to 84 times over 30 to 74 minutes, 7 stalls of heel-27/28
            java.util.Map.entry("frame_new_site", 1.0),
            // (off; an A/B's B arm turns it on, test nachtgat) the night hole when the bed fails: tonight's bed spent, or slept in
            // for nothing ("the night did not pass", "woken before the night passed"), or no bed at all calls the hole as a death
            // did (NightShelter.bedless, GamerTask.shelterTick), and a cover taken on the surface at night is held until dawn
            // (SkelCover.Hold.tillDawn): about 51 of 132 deaths before the iron tools in whole runs were on the surface at night
            // with no shelter, the bed slept 6 of 146 nights on one lane of the shared servers, and the hole was never dug
            java.util.Map.entry("night_hole_bedless", 0.0),
            // (off; an A/B's B arm turns the three on together, test vlucht, 17 Nov 2026) the overworld's flee: 126 of 476 whole runs
            // from a bare start died before the iron tools, about 110 of 340 overworld deaths during a run.
            // one_melee_fight: exactly one plain melee mob on the buddy, at most one skeleton with a line, no creeper within 8, a
            // sword or an axe in the bag, on the ground, hp at max(4, 2.5 x its worst hit) or more: its fight in place of the low hp
            // run, the fight's bail, the run's no-turn when hurt and the cap's new run (CombatCommit.meleeHold)
            java.util.Map.entry("one_melee_fight", 0.0),
            // cover_keep: the skeleton cover under way is not left for a low hp, shot or crowd run while the threat is arrows alone
            // (no melee mob within 4 or on the buddy, no creeper within 6): 30 of 98 deaths in a run ran straight out of an
            // unfinished or broken cover (CombatCommit.coverKept)
            java.util.Map.entry("cover_keep", 0.0),
            // flee_arrow_dig: a run under the cover's hp line that took a skeleton's arrow digs in where the ground digs, with the
            // threat arrows alone, the cover taken whatever its retry waits for, the arrow counted for 1 s (its knock lifts the
            // buddy off the ground the 5 ticks the old rule gave it): arrows killed about 45 of 98 that died in a run
            // (CombatCommit.arrowEndsRun, SkelCover.Hold.force)
            java.util.Map.entry("flee_arrow_dig", 0.0),
            // (off; an A/B's B arm turns the three on together, test staven, 17 Nov 2026) the route's phase 6, the other rods under
            // the brewed potions: whole runs passed it 39 of 62 times, the drill with the same fortresses 56 of 63.
            // fireproof_food_reserve (read by the buddy, Alto.wantsHome): with fire resistance on or a fire resistance potion in the
            // bag, an order of blaze rods in the nether goes home for food under 12 points, not 36; under 12 the hungry rule still
            // sends it. 10 of 59 whole runs went home for food in the middle of the rods (9 lost), the drill's 48 steaks never did
            java.util.Map.entry("fireproof_food_reserve", 0.0),
            // box_crowd_hold: the brew box stays shut under fire resistance while 3 or more blazes are within 4 of the buddy, at
            // most a minute (BoxCrowd): 5 of 23 phase-6 losses opened the box into a crowd and died to bites within 1.5 minutes
            java.util.Map.entry("box_crowd_hold", 0.0),
            // camp_step_in: three game minutes at the fire-proof camp of the pillbox's open side with no rod and no blaze gone
            // after, a stand within 4 of the spawner (CampStepIn): 2 of 23 losses waited there 15 minutes with no blaze in reach
            java.util.Map.entry("camp_step_in", 0.0),
            // (on since 19 Nov 2026: test obsidiaan confirmed the four together, alive with the ten obsidian A 66, B 77 of 90 pairs,
            // z 2.12 against 2.00, deaths 24 against 12; before, off and turned on by its B arm) the route's M3.2, ten obsidian cast
            // with lava and a water bucket: of 369 whole runs that began it 66 were lost there, 61 of them dead.
            // cast_band_here: the obsidian task's lava fetch keeps to the band around the buddy's own height for its first minute,
            // not around max(y, 60) (LavaHunt.inBand, PlaceObsidianBucketTask.bandHere); deep down every pool near the cast was
            // out of the band and the fetch wandered, "25 known, 0 passed" 528 times in 150 game logs. the bucket portal keeps its band
            java.util.Map.entry("cast_band_here", 1.0),
            // deep_food_late (read by the buddy, Alto.wantsFood): in M3.2 the deep stretch's daytime floor for a food trip to the
            // surface is 16 points, not 40; hungry now and under 12 still send it. 17 of 28 logged losses had a trip in M3.2, of
            // 10 to 17 game minutes, nearly always into the night, and the cast was built again where the trip ended
            java.util.Map.entry("deep_food_late", 1.0),
            // dig_lava_check: in the overworld no block is broken that has lava beside, over or under it, or an open cell beside
            // it with lava beside or over that cell (LavaBreak, a break avoider of AltoClef's; GetDownColumnTask holds its dig):
            // the path broke the block beside a pool just scooped, and a dug-in cover dug down into lava
            java.util.Map.entry("dig_lava_check", 1.0),
            // spare_shield_deep (read by the buddy, Alto.spareShield): on the route's deep stretch the spare shield is made only
            // from the bag's makings, as outside the overworld: no climb to the surface for wood
            java.util.Map.entry("spare_shield_deep", 1.0),
            // (fixes of 16 Nov 2026, a frozen-ocean start: 31 minutes of loops in M3.3's gold, no gold) the open water's land
            // search rules ice out as the ice rule's does and both ask the same search, the sea ice no dry ground for the walk out
            // of the water (BranchMineTask, ice_same_land); the ice rule picks its land again only in the land's column or after
            // half a minute, not one step short (ice_pick_at_column); an iceberg's top is no land, its column looked down to the
            // sea's top block for ice (LandFinder.icyColumn, ice_berg_not_land)
            java.util.Map.entry("ice_same_land", 1.0),
            java.util.Map.entry("ice_pick_at_column", 1.0),
            java.util.Map.entry("ice_berg_not_land", 1.0),
            // (a fix of 16 Nov 2026, box 1 lane t1: the brew's pillbox planned for 15 blocks with 9 in the bag starved, 15 s a wall
            // block, till the potion ran out and the blazes killed the buddy) the brew's box by the spawner is refused when it needs
            // more blocks than the bag holds, the box of its own then (CollectBlazeRodsTask.routeBrewBox); and a pillbox with no
            // block in the bag a click may place gives up at once (BlazePillboxTask)
            java.util.Map.entry("brew_box_bag_check", 1.0),
            // (a fix of 16 Nov 2026, the same run: 113 netherrack thrown in the piglin trade, 25 basalt after it) in the nether
            // netherrack, blackstone and basalt are the building blocks to keep, 96 of them, netherrack first: never junk there,
            // and the barter's junk (nether brick, soul sand, gravel) goes first (GarbageRules.netherKept, junkNether; the buddy's
            // own junk rule, ObtainSafe.junkSlot, the same)
            java.util.Map.entry("nether_keep_rack", 1.0),
            // (a fix of 16 Nov 2026, box 3 lane t4: the brew errand flipped at the wart reach's edge, each pick restarting its
            // give-up, one wart held 10 minutes) the wart held stays in reach to the reach + 16, and the same wart picked again
            // keeps its clocks (RouteRules.wartHeldInReach, CollectBlazeRodsTask.wartFetch)
            java.util.Map.entry("wart_hold_reach", 1.0),
            // (a fix of 16 Nov 2026, cascade t1: a tunnel of its own shuttled 3 minutes between two waters 13 cells apart, one
            // half turn each 2.7 s, which the A to B to A check never sees) a half turn against water facing back at another cell
            // within a game minute of the last is a fire of the place watch; in the tunnel of its own the depth is given up
            // (TunnelFlip.refuse, BranchMineTask)
            java.util.Map.entry("tunnel_shuttle_guard", 1.0),
            // (off; an A/B's B arm turns it on, test grotdek) the cover in a cave: where the floor will not dig, a cell 1 wide and 2
            // high dug into the nearest cave wall within 3, stepped into and its opening shut with a block at the head's height
            // (the feet's too when needed), instead of the pillbox; a notch on offer counts as ground that digs for the cover in
            // place of a run (CaveNotch, SkelCoverTask, CombatBrain.notchHere): 38 of 132 deaths before the iron tools were
            // underground in M2.1, the pillbox "no ground to dig here" in 6 of 11 read, a low-hp run kept and cornered in 8 of 11
            java.util.Map.entry("cave_notch_cover", 0.0),
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
            java.util.Map.entry("spawner_wait_cap", 90.0),
            // (fixes of 18 Nov 2026, three live looks at the newest build: stalls of 11 to 40 minutes at full health)
            // the fill where the buddy stands only in a source: in a cave stream the click every 3 s filled nothing and cut the
            // dig of the lid over the source below, CollectBucketLiquidTask (look 121)
            java.util.Map.entry("bucket_in_source_only", 1.0),
            // in water the sky is read over the top of the water column, not over the head: in a 1x1 pit "under a roof" and
            // "open water" took turns every second, BranchMineTask.overWater (look 120)
            java.util.Map.entry("water_roof_column", 1.0),
            // the obsidian cast's frame and pour read only from loaded chunks, else the walk to its stand: from an unloaded
            // chunk the frame read air, and build and walk took turns on a chunk line, PlaceObsidianBucketTask (look 122)
            java.util.Map.entry("cast_chunk_wait", 1.0),
            // the branch mine's way down through the open, once "nothing under any step" chose it, is kept till it ends, the
            // feet are two lower or 30 s have passed: on a rim it and the staircase took turns every second for minutes,
            // BranchMineTask (looks 117, 119)
            java.util.Map.entry("down_way_keep", 1.0),
            // a take-along of blocks that ends "done" with nothing got bans what is left, as a timeout does: a cluster seen
            // through water began again every 7 s with no pickup, TakeAlongChain (look 115)
            java.util.Map.entry("take_along_done_ban", 1.0),
            // for gold, with a diamond pickaxe's diamonds in the bag, the next pickaxe is a diamond one (the bag's stone makes
            // none: a stone one takes no gold ore); with no wood in the bag the try ends as before (no trip up from the deep
            // with a worn pickaxe, look 127):
            // at 71 give-ups both iron pickaxes were broken, 29 held 3+ diamonds, 64 lacked the wood, BranchMineTask
            java.util.Map.entry("gold_pick_from_bag", 1.0),
            // the slime hunt keeps one branch mine for the whole hunt: a new one after each chase forgot the tunnel it was
            // in and began a new mine in the same cave about once a second, KillAndLootTask (look 118)
            java.util.Map.entry("slime_dig_keep", 1.0),
            // a ghast's ball that no tick of its way brings into the punch's window gets the shield while the shield still
            // has time to come up, not two ticks from the hit; a meal waits for a ball coming at the buddy: eight balls took
            // a run from 20 hp to 0 with the shield never in time, MobDefenseChain, GhastBallPlan (look 123)
            java.util.Map.entry("ghast_ball_plan", 1.0),
            // no take-along from the bed, and a night trip stopped with the buddy in bed gets up: a take-along a second after
            // "Sleeping." left the buddy in bed, every move cancelled, for five minutes till dawn, TakeAlongChain,
            // NightSleepTask (look 124)
            java.util.Map.entry("sleep_guard", 1.0),
            // a branch mine made one or two off the poke holes' fresh run goes on at that run's height: one made on the old
            // legs' head row after a pickup's climb turned in its own dug cells for 14 game minutes, BranchMineTask,
            // PokeMineTask (look 125)
            java.util.Map.entry("poke_level_keep", 1.0),
            // a take-along starts only on a block the miner would go for (below 15 armour an ore within 5): on raw iron farther
            // off it found no target, branch mined down and ended with nothing, TakeAlongChain (look 128)
            java.util.Map.entry("take_along_miner_takes", 1.0),
            // the fight due on a creeper after a run from it got nowhere sees the creeper: a swelling creeper is off the
            // hostile list, so the fallback never fired and the same run began 18 times in 7 minutes, CombatBrain (look 129)
            java.util.Map.entry("creeper_fight_sees", 1.0),
            // the gold mine's kit takes this much more wood, in planks, than one more pickaxe and the reserve (a stone pickaxe is a
            // plank's two sticks, about 130 blocks of rock): every gold try of heel-34 ended with no wood for sticks, 2 of 2 (64
            // of 71 give-ups before), the bag full of stone and diamonds, BranchMineTask, StickReserve
            java.util.Map.entry("gold_wood_planks", 24.0),
            // that wood fetched also when the gold phase begins underground (a cave at y -1 in heel-34 run 3, so no surface kit
            // at all), at most SURFACE_CAP and once an order as on the surface, BranchMineTask
            java.util.Map.entry("gold_wood_deep", 1.0),
            // a movement whose block still breaks more each tick is not timed out (600 ticks more at most): in water off the
            // ground a break takes 25 times the estimate and every cancel began it afresh, 132 cancels in 11 minutes in a water
            // pocket, PathExecutor (look 130, S171.9)
            java.util.Map.entry("dig_timeout_progress", 1.0),
            // obsidian found in the last 10 s still counts as found: a far natural block came and went about once a second and
            // each change switched between walking to it and a cast with no lava, six minutes, CollectObsidianTask (look 130)
            java.util.Map.entry("obsidian_seen_hold", 1.0),
            // a plant or snow layer in a portal frame cell is broken by hand before the click: the ray stopped on its outline and
            // the click never went in; 5 of 15 dropped sites in one run's 34 minutes on a forest floor, ConstructNetherPortalObsidianTask (look 131)
            java.util.Map.entry("portal_frame_clear", 1.0),
            // a block on the ground beside a portal column before its third block, the stand a step up onto it: baritone's pillar
            // there never came and 9 of 15 sites were dropped in 34 minutes, ConstructNetherPortalObsidianTask (look 131)
            java.util.Map.entry("portal_frame_step", 1.0),
            // a deep wood fetch begun and cut off (a pickup, a fight) goes on in the next gold mine within SURFACE_CAP of its
            // begin: a fetch begun counted as done, so a pickup two seconds in left the gold mine without wood for 30 game
            // minutes, twice in one run, and the kit's diamond pickaxe dug the rock until it broke, BranchMineTask (look 132)
            java.util.Map.entry("gold_wood_resume", 1.0),
            // a poke mine's fallback tunnel within FALLBACK_SPELL of the last one keeps that one's heading: each fallback turned a
            // quarter the same way, so seven in a row walked a closed square in the same cave ground, 7 game minutes with no
            // gold, BranchMineTask (look 132)
            java.util.Map.entry("poke_fallback_straight", 1.0),
            // glow berries only on a vine within 8 blocks of the feet' height: the food step took an unarmoured buddy from y 60
            // to y -28 for them, two heel deaths in one evening, CollectFoodTask (read of 18 Nov 2026)
            java.util.Map.entry("glow_berries_level", 1.0),
            // a bed in the world taken only within 8 blocks of the feet' height, else one is crafted: a bed at y -16 had the
            // buddy dig down from y 77 into a cave, CollectBedTask (read of 18 Nov 2026)
            java.util.Map.entry("world_bed_level", 1.0),
            // a search that starts with the feet in a current may walk through the water within 5 blocks for 30 s, and the
            // executor wades through water instead of clicking at it: in a current with water on every side the search had no
            // first move, 14 minutes beside lava on an obsidian floor and the run stalled, FlowEscape (look 135)
            java.util.Map.entry("flow_escape", 1.0),
            // (the buddy's route, ObtainRoute's M3.4 piece "raw gold") once no more gold is mined, the raw gold in the bag is
            // smelted before the portal: a piglin trades only ingots, and a run went through with 27 ingots and 12 raw gold
            // (goudsmoke3 run 4, 19 Nov 2026)
            java.util.Map.entry("gold_raw_smelt", 1.0),
            // the sea over us, once seen, holds within 16 blocks for 5 game minutes: the water was counted over the feet's column
            // alone and the frame's choice flipped once a second between a climb and the rock, 5 minutes, DefaultGoToDimensionTask (look 136)
            java.util.Map.entry("portal_sea_hold", 1.0),
            // a snow layer or a plant in the portal step's cell cleared by hand first: the click's ray stopped on the snow and two
            // snowy sites were dropped with 5 of 10 placed, ConstructNetherPortalObsidianTask (look 136)
            java.util.Map.entry("portal_step_clear", 1.0),
            // a climb for food that got no higher in 3 game minutes is given up and the order goes on with the bag's food; the deep
            // food rule waits 15 game minutes: one climb went back down its shaft ten times and into a cave, dead with 38 food
            // points in the bag, SurfaceFoodTask (look 137)
            java.util.Map.entry("food_climb_stuck", 1.0),
            // a step up out of water goes on while the feet bob one block under its start, jump held: each bob ended the one way out
            // of a four-deep pool under a stone roof, 12.6 game minutes at full health, MovementAscend (look 138)
            java.util.Map.entry("ascend_from_water", 1.0),
            // the branch mine's way out of water under a roof never picks the feet's own cell, and a dry cell reached while still in
            // the water is passed over: the feet's cell was picked every tick, 7118 times in 14 minutes, BranchMineTask (19 Nov 2026)
            java.util.Map.entry("mine_dry_not_feet", 1.0),
            // (his word, steering 200 of 19 Nov 2026) the trade's gold dug in the nether from nether gold ore, out of every
            // piglin's anger box, before the trade begins; the buddy's route then asks only the golden helmets' gold in the
            // overworld, where the mine took 10 to 20 gold a game hour, TradeGold
            java.util.Map.entry("nether_gold", 1.0),
            // the obsidian cast's spot looked for within 8 blocks when the cell underfoot fails, and walked to: the old way
            // wandered blind, 5.5 game minutes stood still in a cave under the sea with 9 of 10 obsidian, CollectObsidianTask (look 139)
            java.util.Map.entry("cast_spot_scan", 1.0),
            // sea ice over a frozen ocean is open water to the wanders' steer to land: four whole runs began on such a coast with
            // no tree in reach and walked out over the ice to drowned, guardians and a polar bear, LandFinder (19 Nov 2026)
            java.util.Map.entry("sea_ice_steer", 1.0),
            // a guardian is a shooter and a run at any hp: its beam was no projectile, so five beams in 10 s met no reaction,
            // MobReachability.isRanged, CombatBrain.tridentRun (19 Nov 2026)
            java.util.Map.entry("guardian_run", 1.0),
            // a creeper within 8 blocks is never one the stall rule leaves out: one round a cave corner was "stalled" a second
            // before it lit, MobReachability.stepsFor (19 Nov 2026)
            java.util.Map.entry("creeper_near_kept", 1.0),
            // a station's stand (the "better patch" walked to) never within 2 blocks of lava at its level or one below: a furnace
            // was put at a lava pool's edge and the first fight there knocked the buddy in, StationSpots.standpoint (19 Nov 2026)
            java.util.Map.entry("station_stand_lava", 1.0),
            // an arrow in the skeleton cover's shut hole: its open side cells walled from the bag (the pillbox's walls) before
            // any give-up; the hole was taken with its top alone checked, SkelCoverTask (19 Nov 2026)
            java.util.Map.entry("cover_side_wall", 1.0),
            // the food climb's clock counts only the time the climb runs: five minutes in a skeleton's cover gave it up on its
            // first tick back, with an empty bag, SurfaceFoodTask (look 140, 19 Nov 2026)
            java.util.Map.entry("food_climb_pause", 1.0),
            // with legitMine an ore's exposure is tested inside the block tracker's scan, before each type's budget of 25: under an
            // anti-xray in engine mode 2 every hidden block is sent as a random fake ore, so the 25 were fakes in the walls round
            // the feet and real ore with an open face a few blocks off was never known, BlockTracker (E1119-0905.7, 19 Nov 2026)
            java.util.Map.entry("ore_scan_exposed", 1.0),
            // a run's shield (a drawn bow or a raised trident while the run stands) never pauses the path, and none goes up while a
            // path is under way: the pause held the run still for two minutes under a drowned's tridents, MobDefenseChain (19 Nov 2026)
            java.util.Map.entry("run_shield_no_pause", 1.0),
            // a dug-in cover holds only with the feet in its column: knocked 7 blocks off, the cave roof over the new cell read as the
            // hole's top and the cover stood without a swing under two zombies, CornerWallTask (19 Nov 2026)
            java.util.Map.entry("cover_in_column", 1.0),
            // a run from a crowd with a trident drowned, under the open sky, goes inland along the driest ray: the rerun from the
            // same shore origin went back along the water, CommittedRunTask (19 Nov 2026, F3)
            java.util.Map.entry("trident_run_inland", 1.0),
            // the trade's gold step keeps its ore while the buddy is more than 24 blocks from it, whatever piglins wander near it
            // meanwhile: asked from afar, the target changed every few seconds and no trip arrived, TradeGold (19 Nov 2026)
            java.util.Map.entry("gold_target_keep", 1.0),
            // the witch cover is held while the reason it was taken for (a witch within 16 that hit lately, or the poison) holds:
            // taken with no witch's line, its exit held at once and the cover flapped 57 times in 5 s, SkelCover (19 Nov 2026)
            java.util.Map.entry("witch_cover_hold_wanted", 1.0),
            // the trade's gold step ends with the pickaxes of iron or better down to 300 uses left in all: its trips through the
            // netherrack wore out every pickaxe, diamond included, and the run went on with none, TradeGold (19 Nov 2026)
            java.util.Map.entry("gold_need_pick", 1.0),
            // the trade's gold step: a hostile within 12 counts for 10 s after it was seen, so the fold and the dig no longer flip
            // every second as a mob comes and goes at the box's edge, TradeGold (19 Nov 2026)
            java.util.Map.entry("gold_danger_hold", 1.0),
            // the trade's gold step digs no ore lower than 10 blocks over the lava sea's top (4 before): a fall from a ledge by an
            // ore 6 over the sea ended in it, TradeGoldRules.bandLow (19 Nov 2026)
            java.util.Map.entry("gold_sea_margin", 10.0),
            // baritone's legit mine begun again runs away from the last few mines' branch points within 160 blocks, not only from
            // where the feet stand: it went back and forth through its own tunnel for 78 game minutes, MineProcess (19 Nov 2026)
            java.util.Map.entry("mine_branch_memory", 1.0),
            // a staircase step not reached in 10 s is refused for a game minute before the turn: the way pick turned straight back
            // onto it, 22 times in 4.6 minutes, BranchMineTask (19 Nov 2026)
            java.util.Map.entry("stair_stuck_refuse", 1.0),
            // three minutes at a portal frame clearing its inside with no end drop the site: water left in a frame after a douse was
            // aimed at as a flowing cell the bucket never clicks, 20 minutes, ConstructNetherPortalObsidianTask (19 Nov 2026)
            java.util.Map.entry("portal_clear_limit", 1.0),
            // a portal is never lit with a frame cell gone: lit so, it made fire in the frame, the douse filled it with water,
            // ConstructNetherPortalObsidianTask (19 Nov 2026)
            java.util.Map.entry("portal_light_whole", 1.0),
            // the branch mine's walk along turns a quarter when a step is not reached, at most three times between steps, before it
            // ends: it ended at its first step against water and the staircase went back to the same water, BranchMineTask (19 Nov 2026)
            java.util.Map.entry("away_step_turn", 1.0),
            // a second 'got nowhere' from one spot takes the vein's known cells out for five minutes: a pond's sand under water
            // came round one cell a minute for the whole five-minute limit, MineAndCollectTask (19 Nov 2026)
            java.util.Map.entry("stall_vein_still", 1.0),
            // the trade's gold step leaves an ore whose straight way is open to a fall in more than 2 columns: it went across the
            // lava sea on its own bridge and along basalt rims, and a ghast or a magma cube knocked the buddy off, TradeGold (19 Nov 2026)
            java.util.Map.entry("gold_way_open", 2.0),
            // a failed search for a way to the ore gives it up at once, not after its minute on the tip of a bridge, TradeGold
            // (19 Nov 2026)
            java.util.Map.entry("gold_path_fail", 1.0),
            // the ore's minute starts again on arrival within 6 blocks: it ran out at the end of a long walk, TradeGold (19 Nov 2026)
            java.util.Map.entry("gold_clock_near", 1.0),
            // low on air in water: the swim to a near air cell first and kept, the break of the block overhead only with none:
            // the two took turns every tick as the feet bobbed, WorldSurvivalChain (19 Nov 2026)
            java.util.Map.entry("drown_escape_keep", 1.0),
            // a pillbox side left open is tried again every second, and with the rounds spent and a witch's line in, the dig:
            // a witch's splashes came through a side the box had given up on, SkelCoverTask (19 Nov 2026)
            java.util.Map.entry("pillbox_open_retry", 1.0),
            // a witch's line into the shut hole walls its open sides, as an arrow does, SkelCoverTask (19 Nov 2026)
            java.util.Map.entry("witch_side_wall", 1.0),
            // a fight's bail with a witch that hit lately takes the cover where it holds, and a witch's splash ends a run into
            // it as an arrow does, CombatCommit (19 Nov 2026)
            java.util.Map.entry("witch_cover_bail", 1.0),
            // the server's time packet never sets the client's game time back; the count holds still instead: a step back of a
            // few ticks began every "since" timer again, 20000 new poke mine runs in a day, ClockStep (19 Nov 2026)
            java.util.Map.entry("clock_no_step_back", 1.0),
            // a block the ocean rule turned down with its chunk in view stays out five minutes with the chunk out of view: a log
            // under the sea flipped in and out over a chunk border, the miner and the wander took turns, OceanRule (19 Nov 2026)
            java.util.Map.entry("ocean_rule_keep", 1.0),
            // the branch mine's staircase up with no floor under any way's step takes baritone's own way up and keeps it, as the
            // way down does: in a trial chamber each step was a goal over air, 32 game minutes for 23 blocks, BranchMineTask (19 Nov 2026)
            java.util.Map.entry("up_way_keep", 1.0),
            // the buddy's M3.4 counts the obsidian standing in its own frames nearby with the bag's: after a food run it mined its
            // own frame of 8 back out for want of 10 in the bag, the buddy's ObtainRoute (20 Nov 2026)
            java.util.Map.entry("frame_obsidian_counts", 1.0),
            // a climb to the surface for the portal frame holds off the next within 16 blocks for five game minutes, unless the feet
            // are more than 6 under the ground's top: the site search and the climb took turns every 4 s for 43 minutes,
            // DefaultGoToDimensionTask (20 Nov 2026)
            java.util.Map.entry("portal_climb_hold", 1.0),
            // the obsidian cast's walk back to its stand, once begun, goes on till within 24 blocks: the side of a chunk line with the
            // cast's chunks in built from 142 blocks off, and the walk and the build took turns, PlaceObsidianBucketTask (20 Nov 2026)
            java.util.Map.entry("cast_walk_latch", 1.0),
            // the buddy's portal phase gathers food by points too, under 120 up to 160: eight carrots met its count of eight food,
            // and the nether sent the buddy home under 36 points on arrival, the buddy's ObtainRoute (20 Nov 2026)
            java.util.Map.entry("m34_food_points", 1.0),
            // the lava fetch's open source outranks the lids 6 blocks further once it has: at 32 to the block the pick turned with each
            // first step, the lid and a pool taking turns for 20 minutes, CollectBucketLiquidTask (20 Nov 2026)
            java.util.Map.entry("bucket_outrank_hold", 1.0),
            // a gold step that ended short, or its gold all handed with no potion, digs again for the rest of the 96, up to 4
            // rounds in all, before the blazes without a potion: 13 of 26 failed nether runs went to the blazes without one, 9
            // after a step that ended with 0 to 28 gold, FireResTrade (20 Nov 2026)
            java.util.Map.entry("gold_rounds", 4.0),
            // the gold step under the pickaxe reserve digs on with a stone pickaxe, made from the bag's cobblestone when there is
            // none and no danger near, before it ends on "the pickaxes kept": 7 of 8 steps since gold_rounds ended there at 0 to
            // 54 gold, so no other round and no potion, TradeGold (20 Nov 2026)
            java.util.Map.entry("gold_stone_dig", 1.0),
            // a perch not yet on top never eats (the stance stays FIGHT): a bite pauses the clicks and the pillar goes nowhere;
            // 29 of 70 perch starts had a bite within a second, 18 of those went nowhere and 4 died on the floor, CombatBrain
            // (20 Nov 2026)
            java.util.Map.entry("perch_climb_no_bite", 1.0),
            // the slime chase measures the distance across, a slime more than 40 above or below left out: the client gets a slime
            // by its distance across, and one 45 across and 35 above (58 in 3D) was past the 48, KillAndLootTask (20 Nov 2026)
            java.util.Map.entry("slime_chase_flat", 1.0),
            // a sulfur cube is neutral (NeutralMobs): it attacks nobody, and one holding a block takes no harm from a blow; a
            // fight with one "in our way" ran almost 6 minutes and ended at 1.3 hp from arrows (20 Nov 2026)
            java.util.Map.entry("sulfur_cube_neutral", 1.0),
            // the shimmy out of a block under a goal walk (GetToYTask and the like) for 10 s at most, as InteractWithBlockTask's:
            // held by a drop beside the feet it stood 10.8 minutes on the way up to the portal, CustomBaritoneGoalTask (20 Nov 2026)
            java.util.Map.entry("goal_shimmy_limit", 1.0),
            // in the nether with no usable pickaxe left, the blaze task makes a stone one from the bag's cobblestone past the
            // stand's, with no danger near: the errand's walks wore out every pickaxe and the buddy stalled 32 minutes with 18
            // cobblestone in the bag, CollectBlazeRodsTask (20 Nov 2026)
            java.util.Map.entry("nether_pick_make", 1.0),
            // a branch mine whose driest way from the feet is wet gives the depth up at once: baritone breaks nothing beside water,
            // and the place watch's second fire came 2.2 to 3 minutes later, BranchMineTask (20 Nov 2026)
            java.util.Map.entry("mine_all_wet_leave", 1.0),
            // the branch mine's ice rule steps aside while the surface kit's walk for wood runs: the two took turns every tick on a
            // frozen lake's edge for 3.7 minutes, BranchMineTask (20 Nov 2026)
            java.util.Map.entry("ice_kit_first", 1.0),
            // the old placer's click that placed nothing counts as placing for 40 ticks in a row at most, then its progress check
            // and the spot's strike get the tick: it held a table in hand 23.5 minutes, PlaceBlockNearbyTask (20 Nov 2026)
            java.util.Map.entry("place_click_limit", 1.0),
            // a run from a creeper that kept coming back ended clear: the logs and ores by that creeper, or whose straight way from
            // here passes it, are out for a minute and a half. the task walked back past the same unlit creeper to the same oak
            // log every ~17 s for 4.8 minutes, CombatBrain (20 Nov 2026)
            java.util.Map.entry("creeper_way_ban", 1.0),
            // the nether pickaxe's craft aims at one more than the bag holds of that tool: a worn one in the bag made the craft
            // "done" before it began, and the gold step ended on the pickaxes kept in 6 of 13 nether runs, NetherPickTask (20 Nov 2026)
            java.util.Map.entry("pick_count_held", 1.0),
            // a magma cube let go by lava that has hit us since is not let go by lava again for 30 s: the fight goes on and the kill
            // task's shore walk takes the buddy off the rim; let go again and again, the route walked on beside it while it hit on,
            // 3 of 15 nether deaths, CombatCommit (20 Nov 2026)
            java.util.Map.entry("cube_let_go_once", 1.0),
            // a magma cube counts as able to reach us from 4.5 above or below (it jumps about 4.1), not 3 like a walker, MobReachRules
            // (20 Nov 2026)
            java.util.Map.entry("cube_vertical_reach", 1.0),
            // in the skeleton cover but not shut in (a hole with no top, or still digging it), the aura's hands answer a melee mob in
            // contact that hit us: a zombie bit 5 to 8 s with no swing back in 3 of 16 early deaths, CombatBrain (20 Nov 2026)
            java.util.Map.entry("cover_contact_hands", 1.0),
            // the fight's own target counts for the mid-fight bite whether or not the foe list holds it this tick: a cornered fight on a
            // spider read 'no melee mob within 4' and the buddy ate at hp 5.3 and 2.7 with the spider on it, CombatBrain (20 Nov 2026)
            java.util.Map.entry("fight_target_bite", 1.0),
            // a failed nether pickaxe craft is equal to no other: the fresh one after a failure was kept out as "equal" to the failed
            // child, which returned null every tick, and the gold step stood 19 minutes, NetherPickTask (20 Nov 2026)
            java.util.Map.entry("pick_fail_replace", 1.0),
            // the bucket's scoop keeps its source for 2 s while the eye is in reach, and 20 swaps with the walk to it give the source
            // up: the two took turns every tick for 6.4 minutes beside one lava source, CollectBucketLiquidTask (20 Nov 2026)
            java.util.Map.entry("scoop_hold", 1.0),
            // the heal hold ends once regen cannot happen (the bar under the regen line, nothing decent to eat): one stood 40 s at 3.2 hp
            // and hunger 16, RecoverHold (20 Nov 2026)
            java.util.Map.entry("hold_regen_check", 1.0),
            // a wart seen later in a room the brew errand gave up is out too: two in the same room were walked at again, 60 s each,
            // from a rim 40 above the fortress, CollectBlazeRodsTask (20 Nov 2026)
            java.util.Map.entry("wart_room_seen", 1.0),
            // the blaze corner picks only a blaze its leash keeps once the buddy steps out (the bite, or lure_keep_reach): one 3.4 to 4.5
            // off was picked and let go every 2 s for 6 minutes with no rod, BlazeFight (20 Nov 2026)
            java.util.Map.entry("corner_pick_leash", 1.0),
            // the buddy's night shelter gives up at sea with no land in its search's reach: a fresh 'to land first' failed every tick
            // for 17.5 minutes afloat, ObtainSafe (the buddy, 20 Nov 2026)
            java.util.Map.entry("shelter_sea_give_up", 1.0),
            // baritone walks on mud (14/16 high like soul sand, which it already took): a start on a mangrove swamp's mud had no move
            // at all for 14 minutes, MovementHelper (20 Nov 2026)
            java.util.Map.entry("walk_on_mud", 1.0),
            // in the lava the rim's shield and sneak are off: knocked into a pool, the run's guard held the feet still for 3 s while
            // forward, sprint and jump were held, CombatBrain (20 Nov 2026)
            java.util.Map.entry("rim_guard_lava_off", 1.0),
            // the skeleton pillbox gives up when no block went in within 3 s of building or the buddy was knocked off its spot twice
            // with none in: begun with skeletons in its cells, six arrows came in over 9 s, SkelCoverTask (20 Nov 2026)
            java.util.Map.entry("pillbox_no_block_give_up", 1.0),
            // with its rounds spent, a skeleton's line into the pillbox's open side digs in or gives up, as a witch's does: the
            // buddy stood 26 s at 1.5 hp in that line, SkelCoverTask (20 Nov 2026)
            java.util.Map.entry("pillbox_line_give_up", 1.0),
            // a dig straight down breaks the block under the feet only with the feet on its cell: a descend that landed 0.68 past
            // its cell left the buddy over a shaft, and the break dropped it 28 blocks, MovementDownward (20 Nov 2026)
            java.util.Map.entry("downward_on_cell", 1.0),
            // the skeleton pillbox's side over a drop gets a corner block beside it first, against the side wall already up, so it
            // has a face: the side toward a lower skeleton stayed open through both retry rounds, SkelCoverTask (20 Nov 2026)
            java.util.Map.entry("pillbox_corner_face", 1.0),
            // no brewing stand and fewer than its 3 stone in the bag: no brew (the route's fallback, as without the cream), never
            // the stand's craft out of the shut box: 3 of 3 such brews died in the open, CollectBlazeRodsTask (20 Nov 2026)
            java.util.Map.entry("brew_stand_stone_gate", 1.0),
            // the route's no-brew line said in a run holds past a new order of rods in that run: the order set again began the
            // cream errand anew, 9 minutes round the fortress over the spawner, RouteRules (20 Nov 2026)
            java.util.Map.entry("route_nobrew_keep", 1.0),
            // the slime hunt chases a slime within 48 blocks and keeps it 5 s past the last sight; a farther or banned one leaves
            // the mine digging: one hopping across the edge of the client's list flipped the chase and the mine every 1 to 4 s,
            // KillAndLootTask (20 Nov 2026)
            java.util.Map.entry("slime_chase_near", 48.0),
            // a block's break, once begun, lets a step that is mid way finish to its first safe tick before the path goes: a
            // diagonal round a corner over the lava sea, cut short, slid the buddy off a rim, DestroyBlockTask (20 Nov 2026)
            java.util.Map.entry("destroy_start_safe", 1.0),
            // a pillbox roof-row block on a wall's top is left to the jump that places it: the step to the front cell's middle
            // cut every jump, and the support block on a bent box's back wall never went in under blazes, BlazePillboxTask
            // (20 Nov 2026)
            java.util.Map.entry("roof_jump_keep", 1.0),
            // the pillbox's step to a cell's middle walks uncrouched with the feet a block or more over the cell, so it drops in:
            // crouched it held the buddy on its own wall's edge in the blazes' fire, BlazePillboxTask (20 Nov 2026)
            java.util.Map.entry("centre_drop_in", 1.0),
            // the obsidian cast's frame with no block it may place frees the cobblestone above 16 and the deepslate from the
            // reserve the kit's finished tasks left behind: "every throwaway we have is protected" with 56 cobblestone held, six
            // casts failed in 5.6 minutes, PlaceObsidianBucketTask (20 Nov 2026)
            java.util.Map.entry("cast_free_stone", 1.0),
            // a user task finished on its chain's tick pops the behaviour levels its tasks left, down to the runner's own: their
            // reserves added up over the kit's tasks and locked the stone, UserTaskChain (20 Nov 2026). On since its two smokes
            // (7 runs, one through the nether and out with 9 rods): no 'every throwaway we have is protected' (in 21 of 209
            // game logs of 20 Nov without it), only surplus thrown
            java.util.Map.entry("root_done_unwind", 1.0),
            // three poke-mine segments in a row with nothing dug start a new run into rock not dug yet: a cave on both sides
            // turned the run back onto its own tunnels, 188 legs in 12 minutes with no block dug, PokeMineTask (20 Nov 2026)
            java.util.Map.entry("poke_empty_new_run", 1.0),
            // no climb to the surface for the portal's frame while the portal task fetches obsidian: the fetch went down to y -55 and
            // the climb took the buddy back up every 4 s, 306 times in 46 minutes, DefaultGoToDimensionTask (20 Nov 2026)
            java.util.Map.entry("portal_fetch_no_climb", 1.0),
            // the obsidian cast keeps its frame's cells from breaking only once its buckets are in the bag: in a pocket one wide
            // the fetch for the lava bucket could neither place nor dig its way out, 45 minutes, PlaceObsidianBucketTask (20 Nov 2026)
            java.util.Map.entry("cast_frame_fetch_free", 1.0),
            // the drown escape takes an air cell over the feet only with an open column under it: the cells on top of the ice had
            // no path, each was banned in turn and the break overhead never ran, WorldSurvivalChain (20 Nov 2026)
            java.util.Map.entry("drown_open_column", 1.0),
            // a block's place resets its progress check after a failure, so the try from above gets more than one tick: the buddy
            // went back to the same stand with no face to click for 4.3 minutes, PlaceBlockTask (20 Nov 2026)
            java.util.Map.entry("place_fail_reset", 1.0));

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
