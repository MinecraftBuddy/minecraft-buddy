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
