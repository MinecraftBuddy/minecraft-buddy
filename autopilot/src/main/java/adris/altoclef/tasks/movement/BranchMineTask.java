package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.HotbarTools;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.MineLeave;
import adris.altoclef.util.helpers.OwnTunnel;
import adris.altoclef.util.helpers.ToolPick;
import adris.altoclef.util.helpers.ToolWear;
import baritone.Baritone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Arrays;

// the buddy's fork: an ore nobody has seen, on a server that hides ores from the client (an anti-xray plugin, legitMine on).
// wandering over the ground never shows one, so down to the height the ore is most common at and a branch mine there with
// baritone's legit mine, which opens the rock and takes what it opens. baritone's legit mine never goes down by itself (its
// GoalYLevel step is commented out), so the way down (and up, after a fall) is a staircase of its own first
public class BranchMineTask extends Task {

    private final Block[] _blocks;
    private final int _y;
    // the way down is a staircase of its own, one block on and one down a step: GetToYTask let baritone take the cheapest
    // way down, which is a cave's, where nothing has to be dug, and the caves killed it on the way to iron for its first
    // shield (the runner, 1 Nov 2026). a step not reached in ten game seconds turns the stair a quarter (water, lava, bedrock)
    private int _dx, _dz;
    private BlockPos _step;
    private long _stepSince;
    private boolean _mining;
    // baritone's mine stopping as fast as it starts (no branch it can dig from here) is no loop to stay in: five starts in
    // thirty game seconds and it digs a tunnel of its own for a game minute, then tries again
    private final java.util.ArrayDeque<Long> _starts = new java.util.ArrayDeque<>();
    private long _tunnelUntil;
    // the height it mines at: the ore's best, or where it is when it is below that and still in the ore's band. a climb up
    // to the best height from below went through the caves, and a pillar up made the height flap round the edge of the band
    // (in and out of it at each jump), so baritone's mine was started and stopped five times in a second and the task
    // wandered off into a cave (the runner, 1 Nov 2026)
    private Integer _mineY;
    private long _sculkLook, _awaySince;
    private int _awaySteps;
    // the deepest the staircase down has got, and when (the loop breaker above)
    private int _deepest = Integer.MAX_VALUE;
    private long _deepestAt;
    // the same for a staircase up
    private int _highest = Integer.MIN_VALUE;
    private long _highestAt;
    private static final long STAIR_STUCK = 2400;
    // the last tick on the stairs, and the gap after which the stairs are a new stretch with a fresh clock
    private long _stairLast = Long.MIN_VALUE / 2;
    private static final long STAIR_GAP = 100;
    // why the level walk under way is walked, for its debug line (the sculk's and the stairs' share it)
    private String _awayWhy = "Away from the deep dark's sculk";
    private BlockPos _awayStep;
    private BlockPos _land;
    private long _landSince;
    // (Tuning open_water_land_hold, a fix of 14 Nov 2026, on) the walk to land from open water under way, held until the buddy
    // stands on dry ground (dryGround), the last tick in open water, and how long after it no staircase starts
    private boolean _landWalk;
    private long _openWetAt = Long.MIN_VALUE;
    static final long LAND_HOLD = 200;
    // land with no ice on top, for a branch mine begun on ice, and how far it is looked for
    private BlockPos _iceLand;
    private long _iceLandSince;
    static final int ICE_LAND_REACH = 96;
    // a tunnel of its own, straight on at that height, where baritone's mine will not start: the endless wander that stood
    // here led it into caves without armour
    private BlockPos _tunnel;
    private long _tunnelSince;
    // where baritone's mine was last seen moving on, and when (the check above)
    private BlockPos _minePos;
    private long _mineSince;
    private static final long MINE_STILL = 600;
    // (the buddy's fork, 10 Nov 2026, look 39: three game minutes within ten blocks of one spot, whatever the mode, is a loop.
    // for the iron helmet the buddy stayed 50 minutes in its own pit at y 16 to 25: baritone's mine routed back up the pit's
    // staircase, more than 8 over the band the mine stopped and the staircase down began again, every 25 s; the clocks above
    // each start again with every turn of that round, so none ever fired. then a tunnel of its own for two game minutes, a
    // quarter turned, out of the place; mc-test1 t10, realfort-ow-old-mc1 run 5)
    private final adris.altoclef.util.helpers.PlaceWatch _place = new adris.altoclef.util.helpers.PlaceWatch(10, 3600, 600);
    // (Tuning mine_water_roof, a fix of 13 Nov 2026, on: the place watch fired three times in ten game minutes within ten
    // blocks of one spot gives up the depth there. a flooded cave by the sea at y 54 to 62 for copper at 48 had the watch
    // hand the same tunnel back every three game minutes for 45 minutes, and the piece hit its limit and was asked again)
    // (Tuning mine_own_pit, a fix of 13 Nov 2026, on: the second fire gives the depth up, not the third, so a kit piece over a
    // mine that goes nowhere fails over after about six game minutes and not nine to thirteen)
    private final adris.altoclef.util.helpers.PlaceWatch.Fires _fires = new adris.altoclef.util.helpers.PlaceWatch.Fires(3, 12000, 10);
    // (Tuning mine_leave_dry, a fix of 14 Nov 2026, on) the place watch and its fires kept over a task made anew, per dimension, as
    // MineLeave.WALK_OUTS is: a flooded start made the mine anew eight times (the night's tries, take-alongs) and each began with
    // a fresh watch and no fires. the watch's own gap (30 s away) still starts it again
    private static final adris.altoclef.util.helpers.PlaceWatch SHARED_PLACE = new adris.altoclef.util.helpers.PlaceWatch(10, 3600, 600);
    private static final adris.altoclef.util.helpers.PlaceWatch.Fires SHARED_FIRES = new adris.altoclef.util.helpers.PlaceWatch.Fires(3, 12000, 10);
    private static String sharedDim;

    private adris.altoclef.util.helpers.PlaceWatch place() {
        if (!leaveDryOn()) return _place;
        shareDim();
        return SHARED_PLACE;
    }

    private adris.altoclef.util.helpers.PlaceWatch.Fires fires() {
        if (!leaveDryOn()) return _fires;
        shareDim();
        return SHARED_FIRES;
    }

    private static void shareDim() {
        String dim = dimension();
        if (!dim.equals(sharedDim)) {
            sharedDim = dim;
            SHARED_FIRES.clear();
        }
    }

    static boolean leaveDryOn() {
        return adris.altoclef.util.helpers.Tuning.get("mine_leave_dry", 0) > 0;
    }
    // (Tuning mine_own_pit, a fix of 13 Nov 2026, on) baritone's mine leaving its band, counted by place: the second time within
    // ten blocks in ten game minutes, the tunnel of its own keeps the tick for the rest of the task within OWN_TUNNEL_REACH of
    // there. baritone's mine walked up the buddy's own staircase, out of the band, the mine stopped and the staircase ran down
    // again, every 25 to 30 s for many minutes; each turn started the clocks above again (test heel, look 81)
    private final adris.altoclef.util.helpers.PlaceWatch.Fires _bandExits = new adris.altoclef.util.helpers.PlaceWatch.Fires(2, 12000, 10);
    private BlockPos _ownTunnelAt;
    static final int OWN_TUNNEL_REACH = 48;
    // (Tuning mine_no_path_tunnel, a fix of 14 Nov 2026, on) the count of baritone's mine's stops for no path when this mine last
    // started it (MineProcess.noPathStops, OwnTunnel)
    private int _noPathSeen;
    // (Tuning gold_wide_band, a fix of 13 Nov 2026, on) for a mine whose height lies below 0 and within WIDE_LO (gold's -17, a
    // slime dig's), the mine starts wherever the buddy is between WIDE_LO and WIDE_HI once it has stood at its height once: no
    // staircase back to the height after a drift or a fall. staircases took 35% of the gold's time in the test heel, 65 to
    // 242 of them a run, the tunnel at depth 23%. diamonds and redstone at -50 lie under the band and keep their height
    static final int WIDE_LO = -48, WIDE_HI = 0;
    // (Tuning gold_band_core, 14 Nov 2026, his word, on) for the gold mine the band it starts and stays in is GOLD_LO to GOLD_HI:
    // gold's main batch is a triangle from -64 to 32 that peaks at -16, so -28 to -4 keeps 75% of the peak and more, where -48
    // has a third and 0 two thirds. no staircase back while inside it; outside it, back toward the mine's height. the slime
    // dig, at the same height, keeps the wide band: its slimes are in any chunk below 40
    static final int GOLD_LO = -28, GOLD_HI = -4;
    private final boolean _slimeDig;
    private boolean _reached;
    // (Tuning gold_poke_shafts, 14 Nov 2026, his word, on) in the wide band's scope (gold and the slime dig) the mine digs its
    // own pattern of poke holes off a main tunnel (PokeMineTask) in place of baritone's legit mine
    private PokeMineTask _poke;
    // (Tuning gold_rock_iron, 14 Nov 2026, his word, on) in the same scope the rock goes to an iron pickaxe the kit can spare
    // (ToolPick.rockIron): the buddy's kit keeps KIT_PICKS unworn pickaxes of iron or better (the diamond one among them, which
    // never digs rock), so an iron one is free only past that; a worn one the kit no longer counts. _rockIron: one digs now
    static final int KIT_PICKS = 2;
    private long _rockLook = Long.MIN_VALUE;
    private int _rockFree = -1;
    private boolean _rockIron;
    private Boolean _rockSaid;
    // every give-up so far and the last one's line: whoever asked for the mine (the buddy's kit piece) compares the count
    // with the one at its start and fails over to its next piece
    public static volatile int gaveUps;
    public static volatile String gaveUpLine = "";
    // (Tuning mine_leave_given_up, a fix of 13 Nov 2026, on) a branch mine that starts or runs within MineLeave.NEAR blocks of a
    // spot where a mine gave its depth up in the last 30 game minutes first leaves it, MineLeave.FAR blocks off by baritone's
    // normal goal path, which digs and climbs; a way with no headway in LEAVE_STALL ticks is passed for another, and after
    // LEAVE_WAYS ways the mine goes on where it is. a kit piece whose mine gave up in a flooded cave failed over, and the next
    // piece's mine began on the same ledge with a fresh place watch and stalled the same way, for each piece and each of its
    // three tries (test heel's flooded cave)
    private MineLeave.Spot _leaveFrom;
    private Task _leaveTask;
    private int _leaveTried, _leaveWays, _leaveWay = -1;
    // (mine_leave_dry) the way's goal and how far from the spot it lies, and whether "far but not over" was said
    private int[] _leaveTarget;
    private int _leaveReach;
    private boolean _leaveFarSaid;
    private double _leaveBest;
    private long _leaveBestAt;
    // the spots this task has left, or gave up leaving: once each
    private final java.util.Set<MineLeave.Spot> _leaveDone = new java.util.HashSet<>();
    static final long LEAVE_STALL = 2400;
    static final int LEAVE_WAYS = 3;
    // (mine_leave_given_up) the way down through the open (nothing under any step) that gets nowhere, counted by place: three
    // in a row within ten blocks count as a fire of the place watch. baritone breaks nothing beside water, so in a flooded
    // cave its search found no path, the task wandered five blocks, the staircase stepped back onto a ledge and the search
    // failed again, every 7 s, and the place watch caught that only every three game minutes
    private final MineLeave.Streak _downFails = new MineLeave.Streak(3, 10, 1200);
    private boolean _downStall;
    // (mine_water_roof) out of water under a roof: the dry cell walked to, since when, the cells not reached in time, and the
    // last dry cell stood on (the way the buddy came)
    private BlockPos _dry, _lastDry;
    private long _drySince;
    // the cells not reached in time and when: with mine_water_streak on they are passed over for DRY_TRIED_KEEP ticks, where
    // every dry tick emptied the list, and each walk back into the water tried the same cell out of reach for 20 s again
    private final java.util.Map<BlockPos, Long> _dryTried = new java.util.HashMap<>();
    private static final long DRY_STEP = 400;
    static final long DRY_TRIED_KEEP = 1200;
    static final int DRY_REACH = 12, DRY_BACK = 48;
    // (mine_water_streak) in water under a roof since the last dry tick, so the next such tick is no new walk-out; a third
    // walk-out at one place for the place watch's block on the next tick, and where its row began
    private boolean _wetNow, _wetStall;
    // (open_water_land_hold) the third walk-out was one of open water
    private boolean _wetOpen;
    private int _wetX, _wetZ;
    // the cell the walk along (the sculk's, or the 32 blocks of a stuck staircase) came from: never stepped back onto
    private BlockPos _awayPrev;
    // (Tuning tunnel_flip_guard, a fix of 14 Nov 2026, on) the step refused against water and the tunnel's steps back (TunnelFlip);
    // a second step back in a row for the place watch's block on the next tick, and where
    private final TunnelFlip _flip = new TunnelFlip();
    private boolean _flipStall;
    private int _flipX, _flipZ;
    // the rock goes to a cheap pick and the iron one is kept for the ore. an iron pickaxe is through in 250 blocks and a staircase
    // to the diamonds takes three a step: the iron one wore out on the way down while a stone one lay in the bag, and it climbed
    // to its furnace at y 16 for the iron of a new one four times for two diamonds (the runner, 2 Nov 2026, attempt 80). with only
    // a pickaxe the ore needs left, two stone ones are made where it stands, from what it carries, and the table goes back in
    // the bag (it was left behind at every craft, and the next one had none)
    private Task _cheapCraft;
    private int _cheapWant;
    private long _cheapUntil, _cheapAfter;
    private java.util.Set<BlockPos> _tablesBefore;
    private int _tablesHad;
    private BlockPos _ourTable;
    private long _tableUntil;
    private static final CraftingRecipe STONE_PICK = CraftingRecipe.newShapedRecipe("stone_pickaxe", new ItemTarget[]{
            stoneTarget(), stoneTarget(), stoneTarget(), null, new ItemTarget("stick", 1), null, null, new ItemTarget("stick", 1), null}, 1);
    // (Tuning stick_reserve, a fix of 14 Nov 2026, on) the deep mine's own reserve for its next pickaxe (StickReserve): sticks made
    // from the bag's spare wood while it holds fewer than two, and the next pickaxe (iron from three ingots, else stone) made from
    // the bag once no pickaxe above wood in it is unworn, at the bag's table, which goes back in the bag after as the stone
    // pickaxes' does. look 88: the diamond pickaxe wore out on the gold mine's rock, a new iron one wanted sticks, the bag had one
    // plank, and the log for them was 50 blocks over the cave
    private Task _aheadCraft;
    private net.minecraft.world.item.Item _aheadItem;
    private int _aheadWant;
    private long _aheadUntil, _aheadAfter = Long.MIN_VALUE;
    private static final CraftingRecipe IRON_PICK = CraftingRecipe.newShapedRecipe("iron_pickaxe", new ItemTarget[]{
            new ItemTarget("iron_ingot", 1), new ItemTarget("iron_ingot", 1), new ItemTarget("iron_ingot", 1), null,
            new ItemTarget("stick", 1), null, null, new ItemTarget("stick", 1), null}, 1);
    // (Tuning next_pick_more, a fix of 14 Nov 2026, on) the next pickaxe from three diamonds when no iron or stone one can be
    // made, and the trip up for logs when the wood is what the bag lacks (StickReserve.nextPick): look 98's bag at the gold mine
    // held 7 diamonds, 1 stick and no wood, nextPick said NONE, and the mine went on with no pickaxe for half an hour
    private static final CraftingRecipe DIAMOND_PICK = CraftingRecipe.newShapedRecipe("diamond_pickaxe", new ItemTarget[]{
            new ItemTarget("diamond", 1), new ItemTarget("diamond", 1), new ItemTarget("diamond", 1), null,
            new ItemTarget("stick", 1), null, null, new ItemTarget("stick", 1), null}, 1);
    static final int WOOD_TRIP_LOGS = 3;
    private Task _woodTrip;
    private int _woodWant;
    private long _woodUntil, _woodAfter = Long.MIN_VALUE;
    // (Tuning kit_diamond_off_rock, a fix of 14 Nov 2026, on) the kit's diamond pickaxe off the rock (ToolPick.kitOffRock); the
    // line for a rock with no stone pickaxe to be made, said once until one is there again
    private boolean _kitSaid;
    // (Tuning slime_dig_needs_pick, a fix of 14 Nov 2026, on) no pickaxe above wood: the catalogue's stone pickaxe when the bag
    // gives none, and the line said once
    private Task _firstPick;
    private boolean _firstSaid;
    // (Tuning mine_kit_surface, a fix of 14 Nov 2026, on) the gold mine and the slime dig go down from the surface with two
    // pickaxes above wood besides the kit's diamond one and the wood for one more (StickReserve.surfaceReady): the fetch under
    // way and what it is for, since when the buddy works at it on the surface, and done for this task. a fetch that does not
    // make it in SURFACE_CAP leaves the mine to go down anyway, and no mine of the order tries again for SURFACE_OFF
    private Task _surfaceTask;
    private adris.altoclef.util.helpers.StickReserve.Ready _surfaceWhat;
    private long _surfaceSince = Long.MIN_VALUE;
    private boolean _surfaceDone;
    private static long surfaceOffUntil = Long.MIN_VALUE;
    static final long SURFACE_CAP = 12000;
    static final long SURFACE_OFF = 36000;
    // (Tuning pickless_way_out) a crafting table standing within the bench's reach, looked for at most every TABLE_LOOK ticks
    private long _tableLookAt = Long.MIN_VALUE;
    private boolean _tableNear;
    static final long TABLE_LOOK = 200;

    public BranchMineTask(Block[] blocks, int y) {
        this(blocks, y, false);
    }

    // slimeDig: the slime dig's mine (KillAndLootTask), which keeps the wide band
    public BranchMineTask(Block[] blocks, int y, boolean slimeDig) {
        _blocks = blocks;
        _y = y;
        _slimeDig = slimeDig;
    }

    // the height to branch mine for these blocks in the overworld of 26.x, null when none of them is an ore of the overworld.
    // five above the lava of the deepest caves (it lies at -55 and below) for diamonds and redstone
    public static Integer bestY(Block[] blocks) {
        Integer best = null;
        for (Block b : blocks) {
            String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
            // (an if chain: a ?: chain of ints that ends in null unboxes the null, and that threw for every block that is no
            // ore, logs among them, and ended the task it ran in)
            Integer y = null;
            // (-50 and not -53: at -53 the lava lakes' level, -55, lay at its feet, and in full iron it walked into lava four
            // times in a minute and died at the diamonds, the runner, 2 Nov 2026)
            if (id.contains("diamond_ore") || id.contains("redstone_ore")) y = -50;
            // (8 and not -16, its best: the deep dark lies under 0, and a branch mine for a golden helmet's gold at -16 called a
            // warden, which killed it in full iron, two blows; at 8 gold is half as common and the warden is not there, the
            // runner, 3 Nov 2026, attempt 114)
            // (12 Nov 2026, his yes to steering 170's plan, Tuning gold_mine_y: feet at -17, gold's peak; the sculk walk-away
            // and the target's distance from sculk guard against the warden instead of the height)
            else if (id.contains("gold_ore") && !id.contains("nether")) y = (int) Math.round(adris.altoclef.util.helpers.Tuning.get("gold_mine_y", 8));
            else if (id.contains("lapis_ore")) y = 0;
            else if (id.contains("iron_ore")) y = 16;
            else if (id.contains("copper_ore")) y = 48;
            else if (id.contains("coal_ore")) y = 45;
            if (y != null && (best == null || y < best)) {
                best = y;
            }
        }
        return best;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // a fresh clock for the staircase up after time away (food, a fight): what it climbed before is no measure now
        _highest = Integer.MIN_VALUE;
        // and a fresh clock for the leave's way: a fight or a meal is no lack of headway
        if (_leaveFrom != null) _leaveBestAt = mod.getWorld().getGameTime();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        int y = mod.getPlayer().getBlockY();
        if (_mineY == null) {
            // (twelve and not forty: with forty a fall into a cave at y -16 had it branch mine for iron down there in the
            // deepslate, without armour, and a zombie killed it, the runner, 2 Nov 2026)
            _mineY = y < _y && y >= _y - 12 ? y : _y;
            if (wideBand() && inBand(y)) _mineY = y;
            // (gold_band_core) a start a little under the mine's height but out of gold's band mines at the height
            else if (goldCore() && !inBand(_mineY)) _mineY = _y;
        }
        BlockPos feet = mod.getPlayer().blockPosition();
        // (mine_no_path_tunnel) baritone's mine stopped itself for no path since this mine started it
        if (_mining && noPathOn() && baritone.process.MineProcess.noPathStops != _noPathSeen) {
            _noPathSeen = baritone.process.MineProcess.noPathStops;
            if (noPathStop(mod, feet, y, now)) return null;
        }
        // (gold_rock_iron) which pick digs the rock, before the hotbar is filled for it
        rockPick(mod, now);
        // (kit_diamond_off_rock) the kit's diamond pickaxe off the rock while another pickaxe digs it, for the next 30 s
        if (kitOffOn()) ToolPick.kitOffRock(System.currentTimeMillis() + 30_000);
        // the cheap pick for the rock into the hotbar, where baritone's tools can see it (it looks at the hotbar only). first of
        // all: the walk away from sculk digs too, and the iron one went on it with a stone one in the bag (attempt 84)
        HotbarTools.forBlocks(mod, Blocks.STONE, Blocks.DEEPSLATE);
        // (slime_dig_needs_pick) no pickaxe above wood in the bag: no mine and no staircase, the pickaxe first. look 98: the slime
        // dig began the gold mine with none, "0 dug ... picks none (the way closed)" three times over, punching rock by hand
        if (needsPickOn()) {
            if (!anyAboveWood(mod)) {
                stopMine(mod);
                return pickFirst(mod, now);
            }
            _firstPick = null;
            _firstSaid = false;
        }
        if (leaveOn()) {
            Task leave = leaveGivenUp(mod, feet, now);
            if (leave != null) return leave;
        }
        if (Math.abs(y - _mineY) <= 3) _downFails.reset();
        boolean watch = place().tick(feet.getX(), feet.getZ(), now);
        boolean stall = _downStall;
        _downStall = false;
        // (mine_water_streak) a third walk-out of water under a roof at one place counts as a fire too
        boolean wetLoop = _wetStall;
        _wetStall = false;
        // (tunnel_flip_guard) the tunnel's step back into the cell just left twice in a row counts as a fire too
        boolean flipLoop = _flipStall;
        _flipStall = false;
        if (watch || stall || wetLoop || flipLoop) {
            int fx = watch ? place().x() : stall ? _downFails.x() : wetLoop ? _wetX : _flipX;
            int fz = watch ? place().z() : stall ? _downFails.z() : wetLoop ? _wetZ : _flipZ;
            stopMine(mod);
            // (gold_poke_shafts) the run is not resumed here after the tunnel of its own
            if (pokeOn()) PokeMineTask.dropRun(watch ? "the place watch fired" : stall ? "the way down got nowhere"
                    : wetLoop ? "the walk out of the water came back" : "the tunnel's step went to and fro");
            if (adris.altoclef.util.helpers.Tuning.get("mine_water_roof", 0) > 0) {
                fires().add(fx, fz, now);
                // (mine_water_streak) the walk-outs' own fires are kept over a task made anew: a take-along or a station between
                // two of them made a new task with no fires
                boolean wetGiveUp = wetLoop && MineLeave.WALK_OUTS.fires() >= firesToGiveUp();
                if (fires().last() >= firesToGiveUp() || wetGiveUp) {
                    return giveUpHere(mod, now, y, fx, fz, Math.max(fires().last(), wetGiveUp ? MineLeave.WALK_OUTS.fires() : 0),
                            watch ? "" : stall ? ", the last fire the way down's, mine_leave_given_up"
                                    : wetLoop ? ", the last fire the walk-outs', mine_water_streak" : ", the last fire the tunnel's step to and fro, tunnel_flip_guard");
                }
            }
            // (tunnel_flip_guard) the place watch fired in the tunnel of its own (mine_own_pit): its remedy, a tunnel of its own,
            // is what went nowhere here, so the depth is given up. its fire at 02:29 in the test heel's tunnel flip gave the same
            // tunnel two more game minutes of the loop
            if (flipGuardOn() && watch && _ownTunnelAt != null) {
                return giveUpHere(mod, now, y, fx, fz, Math.max(1, fires().last()), ", in the tunnel of its own, tunnel_flip_guard");
            }
            // (mine_leave_given_up) a walk along under way goes on, way and all: zeroing it here stopped one at 25 blocks to
            // go, and the mine started again in the place the walk was leaving
            if (_awaySteps == 0 || !leaveOn()) {
                int turn = _dx;
                _dx = -_dz;
                _dz = turn;
                _awaySteps = 0;
                _awayStep = null;
                _awayPrev = null;
            }
            _step = null;
            _deepest = Integer.MAX_VALUE;
            _deepestAt = now;
            _highest = Integer.MIN_VALUE;
            _highestAt = now;
            _minePos = null;
            _starts.clear();
            _tunnelUntil = now + 2400;
            _tunnel = null;
            adris.altoclef.Debug.logInternal("branch mine: " + (watch ? "three game minutes" : stall ? "the way down got nowhere three times"
                    : wetLoop ? (_wetOpen ? "three walk-outs of open water" : "three walk-outs of water under a roof") : "the tunnel's step to and fro")
                    + " within ten blocks of " + fx + ", " + fz + " at y " + y + ", a tunnel of its own at y " + _mineY + " for two game minutes"
                    + (watch ? "" : stall ? " (mine_leave_given_up)" : wetLoop ? (_wetOpen ? " (mine_water_streak, open_water_land_hold)" : " (mine_water_streak)")
                    : " (tunnel_flip_guard)"));
            setDebugState((watch ? "Three game minutes in one place" : stall ? "The way down got nowhere three times here"
                    : wetLoop ? "Three walk-outs of the water here" : "The tunnel's step went to and fro here") + ", a tunnel of its own");
            return null;
        }
        // (Tuning ice_start_tunnel_hold, a fix of 13 Nov 2026, on) the tunnel of its own keeps the tick for its whole time on ice or
        // in open water too, level at the feet's height, a quarter turned when a step is not reached in ten game seconds. the
        // two checks below took the tick back from it every tick: a start on a frozen ocean walked to a land it never reached,
        // the place watch gave the tunnel its two minutes every three and the tunnel never got one step, 35 minutes on one
        // spot (M3.3's smoke 5, run 2)
        // (Tuning mine_water_roof, a fix of 13 Nov 2026, on) in water under a roof (a flooded cave) as in open water: out of the
        // water first, to the nearest dry cell to stand in or back the way the buddy came, then the staircase or the tunnel
        // from there. the two water checks below need the sky, so in a flooded cave by the sea neither fired: baritone would
        // not dig by the water ("No path found", a wander of five blocks), the feet bobbed between y 56 and 57 and the tunnel
        // at y 48 and the staircase took turns for 45 minutes
        boolean water = mod.getPlayer().isInWater();
        boolean roofed = water && adris.altoclef.util.helpers.Tuning.get("mine_water_roof", 0) > 0 && !mod.getWorld().canSeeSky(feet.above());
        // (open_water_land_hold) in water with the sky over the feet, and out of it less than LAND_HOLD ago
        boolean open = water && mod.getWorld().canSeeSky(feet.above());
        boolean landHold = landHoldOn();
        if (open && landHold) _openWetAt = now;
        boolean openLately = landHold && adris.altoclef.util.helpers.Recent.within(now, _openWetAt, LAND_HOLD);
        if (!water && mod.getPlayer().onGround()) {
            _lastDry = feet;
            _dry = null;
            _wetNow = false;
            if (waterStreakOn()) _dryTried.values().removeIf(at -> now < at || now - at > DRY_TRIED_KEEP);
            else _dryTried.clear();
        }
        // (open_water_land_hold) a walk-out of open water counts with those under a roof: by a one-block islet the walk to it
        // and the staircase's step back into the sea took turns every tick for 11 minutes, and only the place watch's three
        // minutes counted it
        if (open && landHold && waterStreakOn()) walkOut(feet, now, false);
        if (roofed) {
            if (waterStreakOn()) walkOut(feet, now, true);
            Task out = outOfRoofedWater(mod, feet, now);
            if (out != null) return out;
        }
        if (now < _tunnelUntil && adris.altoclef.util.helpers.Tuning.get("ice_start_tunnel_hold", 0) > 0) {
            // (open_water_land_hold) out of open water less than LAND_HOLD ago counts as in it: a tick on a ledge gave the tick to
            // the staircase, whose step went back into the sea
            boolean wet = roofed || open || openLately;
            if (wet || adris.altoclef.util.helpers.LandFinder.ice(mod.getWorld().getBlockState(feet.below())) || iceColumn(mod, feet)) {
                return holdTunnel(mod, feet, now, wet);
            }
        }
        // in open water no staircase or tunnel starts: dry land first. it swam about the sea in steps of five blocks for
        // fifteen game minutes and never got its shield's iron (the runner, 2 Nov 2026)
        // (Tuning open_water_land_hold, a fix of 14 Nov 2026, on) the walk to land is held until the buddy stands on dry ground
        // (dryGround, the land picked the same way), and no staircase starts until LAND_HOLD after the last tick in open water.
        // at sea by a one-block islet, the land picked, every tick out of the water was dry land for a tick and the staircase's
        // step went back into the sea: the two took turns every tick for 11 minutes, "No path" 15 to 37 a minute (test heel's
        // flooded start, look 97)
        if (landHold) {
            boolean dryHere = !water && mod.getPlayer().onGround() && dryGround(mod.getWorld()::getBlockState, feet);
            boolean landed = dryHere || !water && mod.getPlayer().onGround() && _land != null && feet.closerThan(_land, 1.5);
            int step = landStep(open, openLately, _landWalk, landed);
            if (step == LAND_OFF && _landWalk) {
                _landWalk = false;
                adris.altoclef.Debug.logInternal("branch mine: on dry ground at " + feet.toShortString() + ", out of the open water "
                        + LAND_HOLD / 20 + " s, the mine goes on from here (open_water_land_hold)");
            }
            if (step == LAND_STAND) {
                stopMine(mod);
                setDebugState("On dry ground, out of the water: the staircase in " + (LAND_HOLD - (now - _openWetAt) + 19) / 20 + " s");
                return null;
            }
            if (step == LAND_WALK) {
                boolean look = _land == null ? adris.altoclef.util.helpers.Recent.due(now, _landSince, 20)
                        : now < _landSince || now - _landSince > 600 || feet.closerThan(_land, 1.5) && now - _landSince > 20;
                if (look) {
                    BlockPos was = _land;
                    _land = nearestLand(mod, feet, now);
                    _landSince = now;
                    if (_land != null && !_land.equals(was)) {
                        adris.altoclef.Debug.logInternal("branch mine: " + (open ? "in open water" : "out of the open water, not on dry ground,")
                                + " at " + feet.toShortString() + ", to dry land at " + _land.toShortString()
                                + ", held until it stands on dry ground (open_water_land_hold)");
                    }
                }
                if (_land != null) {
                    _landWalk = true;
                    stopMine(mod);
                    setDebugState("Out of the water first, to dry land at " + _land.toShortString());
                    return new GetToBlockTask(_land);
                }
                _landWalk = false;
                // no dry ground within reach: out of the water no staircase until LAND_HOLD after the last tick in it; in the
                // water on as before (the tunnel's hold, the walk-outs' fire, the place watch)
                if (open) stopMine(mod);
                if (!water && openLately) {
                    stopMine(mod);
                    setDebugState("Out of the open water, no dry ground in reach: the staircase in " + (LAND_HOLD - (now - _openWetAt) + 19) / 20 + " s");
                    return null;
                }
            }
        } else if (open) {
            stopMine(mod);
            if (_land == null || now - _landSince > 600 || feet.closerThan(_land, 1.5)) {
                _land = nearestLand(mod, feet, now);
                _landSince = now;
            }
            if (_land != null) {
                setDebugState("Out of the water first, to dry land at " + _land.toShortString());
                return new GetToBlockTask(_land);
            }
        }
        // on ice no staircase or tunnel starts either: baritone breaks no ice and no packed ice by water, and a staircase down
        // through an iceberg in a frozen ocean stood walled in at y 61, every step, the 32 blocks along and the wander "no
        // path", for thirty game minutes with no gold (the overworld route's gold, box 1, 10 Nov 2026). land with no ice or
        // water under its top first (none within reach: as before). ice anywhere in the few blocks under the feet counts: on
        // the snow of an iceberg's top the rule saw no ice, the staircase stepped down onto it, and the two took turns
        if (adris.altoclef.util.helpers.LandFinder.ice(mod.getWorld().getBlockState(feet.below()))
                || iceColumn(mod, feet)) {
            if (_iceLand == null || now - _iceLandSince > 600 || feet.closerThan(_iceLand, 1.5)) {
                _iceLand = adris.altoclef.util.helpers.LandFinder.nearestLand(mod, feet, ICE_LAND_REACH, true);
                _iceLandSince = now;
                if (_iceLand != null) adris.altoclef.Debug.logInternal("branch mine: on ice at " + feet.toShortString() + ", to land at " + _iceLand.toShortString() + " first");
            }
            if (_iceLand != null) {
                stopMine(mod);
                setDebugState("Off the ice first, to land at " + _iceLand.toShortString());
                return new GetToBlockTask(_iceLand);
            }
        }
        // the deep dark: sculk within ten blocks and it digs away from it, level, for 48 blocks before it goes on. a staircase
        // down to the diamonds in full iron came out by a shrieker, and the warden it called killed it through the wall with
        // two sonic booms (the runner, 2 Nov 2026)
        // (once it walks away, the walk is finished first, and the way is away from the middle of all the sculk round it: worked
        // out anew every second from the nearest block it turned round at each new nearest one, and went to and fro in six
        // blocks of a deep dark at y -23 for minutes, the runner, 2 Nov 2026, attempt 67)
        if (now >= _sculkLook && _awaySteps == 0) {
            _sculkLook = now + 20;
            BlockPos sculk = nearestSculk(mod, feet, 10);
            if (sculk != null) {
                BlockPos mid = sculkMiddle(mod, feet, 16);
                if (mid == null) mid = sculk;
                int ax = feet.getX() - mid.getX(), az = feet.getZ() - mid.getZ();
                if (ax == 0 && az == 0) { ax = feet.getX() - sculk.getX(); az = feet.getZ() - sculk.getZ(); }
                if (Math.abs(ax) >= Math.abs(az)) { _dx = ax >= 0 ? 1 : -1; _dz = 0; } else { _dx = 0; _dz = az >= 0 ? 1 : -1; }
                _awaySteps = awayLength(now);
                _awayWhy = "Away from the deep dark's sculk";
                _awayStep = null;
                stopMine(mod);
            }
        }
        if (_awaySteps > 0) {
            if (_awayStep != null && now - _awaySince > 200 && !feet.equals(_awayStep)) { _awaySteps = 0; _awayStep = null; }
            else {
                if (_awayStep == null || feet.equals(_awayStep)) {
                    if (_awayStep != null) _awaySteps--;
                    else _awayPrev = null;
                    // never a bridge: a way with nothing under its next cell turns, and with all four like that the walk ends.
                    // the 32 blocks along bridged a ravine thirty deep at y 49, and the staircase down from the bridge had
                    // nowhere to go (the runner, 3 Nov 2026, attempt 108)
                    for (int turn = 0; turn < 4 && floorless(mod, feet.offset(_dx, 0, _dz)); turn++) {
                        int t = _dx;
                        _dx = -_dz;
                        _dz = t;
                    }
                    if (floorless(mod, feet.offset(_dx, 0, _dz))) {
                        _awaySteps = 0;
                        _awayStep = null;
                        return null;
                    }
                    // (the buddy's fork, look 39: with a floor only the way it came, the turns sent it back, and the next step
                    // back again: 32 to 21 counted in 4 s between two cells, the walk ending where it began. that is the end)
                    if (feet.offset(_dx, 0, _dz).equals(_awayPrev)) {
                        _awaySteps = 0;
                        _awayStep = null;
                        _awayPrev = null;
                        return null;
                    }
                    _awayPrev = feet;
                    _awayStep = feet.offset(_dx, 0, _dz);
                    _awaySince = now;
                }
                setDebugState(_awayWhy + ", " + _awaySteps + " blocks to go");
                return new GetToBlockTask(_awayStep);
            }
        }
        // (mine_kit_surface) going down from the surface: the pickaxes and the wood for one more first, up here where the wood is
        Task kit = surfaceKit(mod, feet, y, now);
        if (kit != null) {
            stopMine(mod);
            return kit;
        }
        Task pick = cheapPick(mod, now);
        if (pick != null) {
            stopMine(mod);
            return pick;
        }
        // (stick_reserve) sticks from spare wood, and the next pickaxe before the last one above wood is gone
        Task ahead = pickAhead(mod, now);
        if (ahead != null) {
            stopMine(mod);
            return ahead;
        }
        if (_dx == 0 && _dz == 0) {
            // the way it looks, to the nearest of the four
            var dir = mod.getPlayer().getDirection();
            _dx = dir.getStepX();
            _dz = dir.getStepZ();
            if (_dx == 0 && _dz == 0) _dx = 1;
        }
        if (wideBand()) {
            if (Math.abs(y - _mineY) <= 3) {
                _reached = true;
            } else if (_reached && inBand(y) && Math.abs(y - _mineY) > (_mining ? 8 : 3)) {
                stopMine(mod);
                adris.altoclef.Debug.logInternal("branch mine: at y " + y + ", in the band " + bandLo() + " to " + bandHi()
                        + ": mining here, no staircase back to y " + _mineY + " (gold_wide_band" + (goldCore() ? ", gold_band_core" : "") + ")");
                _mineY = y;
                _step = null;
            } else if (_reached && goldCore() && !inBand(y) && _mineY != _y) {
                // (gold_band_core) out of the gold band: back toward the mine's height, and the band's edge is mined once reached
                stopMine(mod);
                adris.altoclef.Debug.logInternal("branch mine: at y " + y + ", out of the gold band " + GOLD_LO + " to " + GOLD_HI
                        + ": back toward y " + _y + " (gold_band_core)");
                _mineY = _y;
                _step = null;
            }
        }
        // (mine_own_keep) a mine made anew near the tunnel of its own an earlier mine for this height began goes on with it
        if (_ownTunnelAt == null && ownKeepOn()) {
            OwnTunnel.Kept k = OwnTunnel.KEPT.near(dimension(), _y, feet.getX(), _mineY, feet.getZ(), now, OWN_TUNNEL_REACH);
            if (k != null) {
                stopMine(mod);
                _ownTunnelAt = new BlockPos(k.x(), k.y(), k.z());
                _mineY = k.y();
                _dx = k.dx();
                _dz = k.dz();
                _tunnel = null;
                _step = null;
                adris.altoclef.Debug.logInternal("branch mine: made anew " + Math.round(horizontal(feet, _ownTunnelAt))
                        + " blocks from the tunnel of its own begun at " + k.x() + ", " + k.z() + ", on with it at y " + _mineY
                        + " heading " + _dx + ", " + _dz + " (mine_own_keep)");
            }
        }
        if (_ownTunnelAt != null && horizontal(feet, _ownTunnelAt) > OWN_TUNNEL_REACH) {
            adris.altoclef.Debug.logInternal("branch mine: " + OWN_TUNNEL_REACH + " blocks from " + _ownTunnelAt.toShortString()
                    + ", baritone's mine may try again (mine_own_pit)");
            _ownTunnelAt = null;
            if (ownKeepOn()) OwnTunnel.KEPT.drop();
        }
        if ((now < _tunnelUntil || _ownTunnelAt != null) && Math.abs(y - _mineY) <= 8) {
            // straight on at this height, a block at a time; a step not reached in ten game seconds (lava, water, bedrock)
            // turns the tunnel a quarter
            if (_tunnel != null && now - _tunnelSince > 200 && !feet.equals(_tunnel)) {
                int t = _dx;
                _dx = -_dz;
                _dz = t;
                _tunnel = null;
            }
            if (_tunnel == null || feet.equals(_tunnel)) {
                boolean reached = _tunnel != null;
                closedWay(mod, feet, 0);
                _tunnel = new BlockPos(feet.getX() + _dx, _mineY, feet.getZ() + _dz);
                _tunnelSince = now;
                keepTunnel(now);
                // (tunnel_flip_guard) back into the cell just left, the step before back too (A to B to A): a fire of the place
                // watch here on the next tick, so the second such fire gives the depth up
                if (flipGuardOn() && _flip.step(feet, _tunnel, reached)) {
                    _flipStall = true;
                    _flipX = feet.getX();
                    _flipZ = feet.getZ();
                    adris.altoclef.Debug.logInternal("branch mine: the tunnel's step from " + feet.toShortString() + " back into "
                            + _tunnel.toShortString() + ", the cell just left, the second step back in a row: a fire of the place"
                            + " watch here (tunnel_flip_guard)");
                }
            }
            setDebugState(_ownTunnelAt != null ? "A tunnel of its own at y " + _mineY + ", the mine left its band twice here"
                    : "A tunnel of its own at y " + _mineY);
            return new GetToBlockTask(_tunnel);
        }
        // in the band: 3 blocks to start the mine, 8 to stop it, so a jump does not stop it
        int dy = y - _mineY;
        if (_mining ? Math.abs(dy) > 8 : Math.abs(dy) > 3) {
            if (_mining && adris.altoclef.util.helpers.Tuning.get("mine_own_pit", 0) > 0 && _ownTunnelAt == null) {
                bandExits().add(feet.getX(), feet.getZ(), now);
                if (bandExits().last() >= 2) {
                    bandExits().clear();
                    _ownTunnelAt = feet;
                    keepTunnel(now);
                    adris.altoclef.Debug.logInternal("branch mine: out of its band at y " + y + " twice within ten blocks of "
                            + feet.getX() + ", " + feet.getZ() + ", a tunnel of its own at y " + _mineY + " from here on (mine_own_pit)");
                }
            }
            stopMine(mod);
            long tick = now;
            // a fresh clock for each stretch of stairs: the deepest and highest steps and their times were kept through the
            // mine, fights and new pickaxes, so a staircase begun two game minutes after the last one "got no deeper" at once
            // and walked 32 blocks off: ten times in a 40 minute slime hunt that never got far from its start (bal3 run 3,
            // 12 Nov 2026), and the gold phase's mine climbs out of its band the same way
            if (now - _stairLast > STAIR_GAP) {
                _deepest = Integer.MAX_VALUE;
                _deepestAt = now;
                _highest = Integer.MIN_VALUE;
                _highestAt = now;
            }
            _stairLast = now;
            // a staircase down that gets no deeper for two game minutes is going round something it cannot pass (water or
            // lava under the steps, every way blocked): it tunnels level for 32 blocks a quarter turned and starts again
            // there. one went up and down between y 40 and 50 in a square of ten for eleven game minutes, its kit's iron
            // never came (the runner, 2 Nov 2026, attempt 56)
            if (dy > 0) {
                if (y < _deepest) {
                    _deepest = y;
                    _deepestAt = now;
                } else if (now - _deepestAt > STAIR_STUCK && _awaySteps == 0) {
                    int t = _dx;
                    _dx = -_dz;
                    _dz = t;
                    _awaySteps = 32;
                    _awayWhy = "Stairs that got no deeper or higher: along";
                    _awayStep = null;
                    _step = null;
                    _deepest = Integer.MAX_VALUE;
                    _deepestAt = now;
                    setDebugState("The staircase gets no deeper here, 32 blocks along first");
                    return null;
                }
            }
            if (dy < 0) {
                // a staircase up that gets no higher for two game minutes has water or lava over every next step (baritone
                // never breaks a block with a liquid on it): within the twelve the start allows it mines where it is, else it
                // tunnels level for 32 blocks a quarter turned and climbs from there. one stood at y 40 under a lake for coal at
                // 45, a wander of five blocks every three seconds (the runner, 3 Nov 2026, attempt 96)
                if (y > _highest) {
                    _highest = y;
                    _highestAt = now;
                } else if (now - _highestAt > STAIR_STUCK && _awaySteps == 0) {
                    _highest = Integer.MIN_VALUE;
                    _highestAt = now;
                    _step = null;
                    if (y >= _y - 12) {
                        _mineY = y;
                        setDebugState("The staircase gets no higher here, mining at y " + y);
                        return null;
                    }
                    int t = _dx;
                    _dx = -_dz;
                    _dz = t;
                    _awaySteps = 32;
                    _awayWhy = "Stairs that got no deeper or higher: along";
                    _awayStep = null;
                    setDebugState("The staircase gets no higher here, 32 blocks along first");
                    return null;
                }
                // up by a staircase of its own through the rock, as down: getting to y by baritone's cheapest way went up
                // through the caves it had fallen into
                if (_step != null && tick - _stepSince > 200 && !feet.equals(_step)) {
                    int t = _dx;
                    _dx = -_dz;
                    _dz = t;
                    _step = null;
                }
                if (_step == null || feet.equals(_step) || feet.getY() > _step.getY() || feet.getY() < _step.getY() - 1) {
                    closedWay(mod, feet, 1);
                    _step = feet.offset(_dx, 1, _dz);
                    _stepSince = tick;
                }
                setDebugState("A staircase up to y " + _mineY + " to branch mine");
                return new GetToBlockTask(_step);
            }
            if (_step != null && tick - _stepSince > 200 && !feet.equals(_step)) {
                int t = _dx;
                _dx = -_dz;
                _dz = t;
                _step = null;
            }
            // a new step once this one is reached, or it fell below it. not as soon as it is at the step's height: baritone
            // goes down a step by digging down where it stands and then across, so a new step at that height made a shaft
            // straight down, which went through a cave the step's own check never saw (the runner, 2 Nov 2026)
            // nothing under a step any way it turns (a bridge over a ravine, the middle of a cave): no stair can be cut there, and
            // baritone's way down through the open is the one there is (it takes no fall that hurts). on its own bridge over a
            // ravine thirty deep it stood for minutes, every step down a drop (the runner, 3 Nov 2026, attempt 108)
            if (noFloorAnyWay(mod, feet)) {
                setDebugState("Nothing under any step here: baritone's way down to y " + _mineY);
                return leaveOn() ? new DownTask(_mineY) : new adris.altoclef.tasks.movement.GetToYTask(_mineY);
            }
            if (_step == null || feet.equals(_step) || feet.getY() < _step.getY()) {
                closedWay(mod, feet, -1);
                _step = feet.offset(_dx, -1, _dz);
                _stepSince = tick;
            }
            setDebugState("A staircase down to y " + _mineY + " to branch mine");
            return new GetToBlockTask(_step);
        }
        if (pokeOn()) {
            // (gold_poke_shafts) the pattern of poke holes in place of baritone's straight legit tunnel; the branch mine's heading
            // follows the run's, so a fire of the place watch turns from the way the run went
            stopMine(mod);
            if (_poke == null || _poke.y() != _mineY) _poke = new PokeMineTask(_blocks, _mineY);
            if (_poke.takeFallback()) {
                int t = _dx;
                _dx = -_dz;
                _dz = t;
                _tunnelUntil = now + 1200;
                _tunnel = null;
                setDebugState("Poke holes found no way into the rock here, a tunnel of its own");
                return null;
            }
            int[] h = PokeMineTask.runHeading();
            if (h != null) {
                _dx = h[0];
                _dz = h[1];
            }
            _poke.heading(_dx, _dz);
            setDebugState("Poke holes at y " + _mineY);
            return _poke;
        }
        Baritone.settings().legitMineYLevel.value = _mineY;
        var mine = mod.getClientBaritone().getMineProcess();
        // baritone's mine can stay on and get nowhere: "No path found" for its run away goal, two hundred times in five game
        // minutes, within twelve blocks, and the leggings' iron timed out (the practice runner, 2 Nov 2026, attempt 70). the
        // starts below never counted that, the mine was never off. thirty game seconds within four blocks while it mines, and
        // it digs a tunnel of its own for a game minute
        if (mine.isActive()) {
            if (_minePos == null || feet.distManhattan(_minePos) > 4 || now < _mineSince) {
                _minePos = feet;
                _mineSince = now;
            } else if (now - _mineSince > MINE_STILL) {
                _minePos = null;
                _tunnelUntil = now + 1200;
                _tunnel = null;
                stopMine(mod);
                setDebugState("Baritone's mine gets nowhere here, a tunnel of its own");
                return null;
            }
        } else {
            _minePos = null;
        }
        if (!mine.isActive()) {
            // five starts in thirty game seconds: baritone finds no branch from here, so a tunnel of its own for a game minute
            _starts.addLast(now);
            while (!_starts.isEmpty() && now - _starts.peekFirst() > 600) _starts.removeFirst();
            if (_starts.size() > 5) {
                _starts.clear();
                _tunnelUntil = now + 1200;
                _tunnel = null;
                stopMine(mod);
                setDebugState("Baritone's mine will not start here, a tunnel of its own");
                return null;
            }
            bandMine(mod);
            _noPathSeen = baritone.process.MineProcess.noPathStops;
            mine.mine(0, _blocks);
            _mining = true;
        }
        setDebugState("Branch mining at y " + _mineY);
        return null;
    }

    // (mine_water_roof) the place watch fired three times in ten game minutes here: the depth is given up at this spot. the
    // count tells the caller (the buddy's piece fails over to its next one); left to itself the mine walks 32 blocks on, a
    // quarter turned, and starts afresh there. with mine_leave_given_up on, the spot is kept for every branch mine after this
    // one, and this one leaves it 48 blocks first instead of the walk
    // fired: the fires counted here; lastFire: what the last one was, for the log line ("" for the place watch's own)
    private Task giveUpHere(AltoClef mod, long now, int y, int fx, int fz, int fired, String lastFire) {
        return giveUpHere(mod, now, y, fx, fz, "the place watch fired " + fired + " times at " + fx + ", " + fz,
                "mine_water_roof" + (fired < 3 ? ", mine_own_pit" : "") + lastFire,
                "The place watch fired " + fired + " times here");
    }

    // what: why, for the line; keys: the keys for the log's brackets; why: the debug state's start
    private Task giveUpHere(AltoClef mod, long now, int y, int fx, int fz, String what, String keys, String why) {
        String line = "branch mine: " + what + ", giving up y " + _mineY + " here";
        adris.altoclef.Debug.logInternal(line + " (at y " + y + ", " + keys + ")");
        // (mine_leave_given_up) the spot, for any branch mine after this one: the next piece's mine leaves it first
        if (leaveOn()) MineLeave.GIVEN_UP.record(dimension(), fx, fz, now);
        gaveUpLine = line;
        gaveUps++;
        fires().clear();
        MineLeave.WALK_OUTS.clearFires();
        bandExits().clear();
        _ownTunnelAt = null;
        if (ownKeepOn()) OwnTunnel.KEPT.drop();
        _tunnelUntil = now;
        _tunnel = null;
        _step = null;
        // (open_water_land_hold) the land picked before may lie back by the spot: picked anew after the leave
        if (landHoldOn()) {
            _land = null;
            _landWalk = false;
        }
        _starts.clear();
        _minePos = null;
        int t = _dx;
        _dx = -_dz;
        _dz = t;
        if (_dx == 0 && _dz == 0) _dx = 1;
        _downFails.reset();
        _downStall = false;
        // (mine_leave_given_up) the leave from the spot takes the next tick, so no walk along: one would be left to run after it
        _awaySteps = leaveOn() ? 0 : 32;
        _awayWhy = "Gave up y " + _mineY + " here: along";
        _awayStep = null;
        _awayPrev = null;
        setDebugState(why + ", giving up y " + _mineY + " here");
        return null;
    }

    static boolean ownKeepOn() {
        return adris.altoclef.util.helpers.Tuning.get("mine_own_keep", 0) > 0;
    }

    static boolean noPathOn() {
        return adris.altoclef.util.helpers.Tuning.get("mine_no_path_tunnel", 0) > 0;
    }

    // (mine_own_keep) the band exits of every mine of the dimension, as this one's were
    private adris.altoclef.util.helpers.PlaceWatch.Fires bandExits() {
        return ownKeepOn() ? OwnTunnel.KEPT.bandExits(dimension()) : _bandExits;
    }

    // (mine_own_keep) the tunnel of its own kept for a mine made anew: where it began, its height and heading, as of now
    private void keepTunnel(long now) {
        if (_ownTunnelAt != null && ownKeepOn()) {
            OwnTunnel.KEPT.keep(dimension(), _y, _ownTunnelAt.getX(), _mineY, _ownTunnelAt.getZ(), _dx, _dz, now);
        }
    }

    // (mine_no_path_tunnel) a stop of baritone's mine for no path at the feet. the second within ten game minutes and 100 blocks
    // of the one before turns the mine to a tunnel of its own from here, a quarter turned from the way between the two (its
    // legit mine starts again with a branch point at the feet each time and shuttles through the tunnel it dug); with such a
    // turn made within 100 blocks in the last 30 game minutes, the depth is given up here. true when it took the tick
    private boolean noPathStop(AltoClef mod, BlockPos feet, int y, long now) {
        String dim = dimension();
        int[] before = OwnTunnel.KEPT.noPathStop(dim, feet.getX(), feet.getZ(), now);
        if (before == null || _ownTunnelAt != null) return false;
        long apart = Math.round(Math.hypot(feet.getX() - before[0], feet.getZ() - before[1]));
        String two = "baritone's mine found no path twice within ten game minutes, at " + before[0] + ", " + before[1]
                + " and at " + feet.getX() + ", " + feet.getZ() + " (" + apart + " blocks apart)";
        stopMine(mod);
        if (OwnTunnel.KEPT.switchedNear(dim, feet.getX(), feet.getZ(), now)) {
            OwnTunnel.KEPT.switchSpent();
            giveUpHere(mod, now, y, feet.getX(), feet.getZ(), two + ", after a tunnel of its own for the same within "
                    + Math.round(OwnTunnel.NO_PATH_REACH) + " blocks", "mine_no_path_tunnel", "Baritone's mine found no path twice again");
            return true;
        }
        int[] h = OwnTunnel.heading(before[0], before[1], feet.getX(), feet.getZ(), _dx, _dz);
        OwnTunnel.KEPT.switched(dim, feet.getX(), feet.getZ(), now);
        _dx = h[0];
        _dz = h[1];
        _ownTunnelAt = feet;
        _tunnel = null;
        _step = null;
        _starts.clear();
        _minePos = null;
        keepTunnel(now);
        adris.altoclef.Debug.logInternal("branch mine: " + two + ", a tunnel of its own at y " + _mineY + " from here on, heading "
                + _dx + ", " + _dz + ", a quarter turned from the way between them (mine_no_path_tunnel)");
        setDebugState("Baritone's mine found no path twice, a tunnel of its own at y " + _mineY);
        return true;
    }

    // (mine_water_roof, mine_own_pit) the place watch's fires at one spot that give the depth up there
    static int firesToGiveUp() {
        return adris.altoclef.util.helpers.Tuning.get("mine_own_pit", 0) > 0 ? 2 : 3;
    }

    static boolean leaveOn() {
        return adris.altoclef.util.helpers.Tuning.get("mine_leave_given_up", 0) > 0;
    }

    static boolean waterStreakOn() {
        return adris.altoclef.util.helpers.Tuning.get("mine_water_streak", 0) > 0;
    }

    static boolean flipGuardOn() {
        return adris.altoclef.util.helpers.Tuning.get("tunnel_flip_guard", 0) > 0;
    }

    static boolean landHoldOn() {
        return adris.altoclef.util.helpers.Tuning.get("open_water_land_hold", 0) > 0;
    }

    // (open_water_land_hold) what the walk from open water does this tick: LAND_OFF nothing (or the walk is over), LAND_STAND
    // stand (on dry ground, out of the water less than LAND_HOLD ago), LAND_WALK on to the land. open: in open water now;
    // lately: in it less than LAND_HOLD ago; walking: the walk is under way; landed: standing on dry ground or on the land
    static final int LAND_OFF = 0, LAND_STAND = 1, LAND_WALK = 2;

    static int landStep(boolean open, boolean lately, boolean walking, boolean landed) {
        if (!open && !lately && !walking) return LAND_OFF;
        if (landed && !open) return lately ? LAND_STAND : LAND_OFF;
        return LAND_WALK;
    }

    // (open_water_land_hold) dry ground to stand on: a cell to stand in (standable), no fluid in its four neighbours or in the
    // four of the block under it. a ledge at the sea's height had the sea in the cells beside it and was dry for one tick of
    // onGround, and the leave from a flooded cave ended there; a one-block islet at the water's top has the sea beside its
    // floor, where the staircase's first step down goes
    static boolean dryGround(java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> at, BlockPos feet) {
        if (!standable(at.apply(feet.below()), at.apply(feet), at.apply(feet.above()))) return false;
        for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (!at.apply(feet.relative(dir)).getFluidState().isEmpty()) return false;
            if (!at.apply(feet.below().relative(dir)).getFluidState().isEmpty()) return false;
        }
        return true;
    }

    // (mine_water_streak) a tick in water under a roof: the cell is kept as the one walked out of, and the first such tick
    // since the last dry one starts a walk-out, counted by place (MineLeave.WALK_OUTS). a new walk-out picks the staircase's and
    // the tunnel's next step anew, so closedWay's water rule keeps them out of the water: the old step stood until reached, and
    // the way to it led back into the pool about once a second
    private void walkOut(BlockPos feet, long now, boolean roofed) {
        String dim = dimension();
        String where = roofed ? "in water under a roof" : "in open water", keys = roofed ? "mine_water_streak" : "mine_water_streak, open_water_land_hold";
        if (_wetNow) {
            MineLeave.WALK_OUTS.wet(dim, feet.getX(), feet.getY(), feet.getZ(), now);
            return;
        }
        _wetNow = true;
        _step = null;
        _tunnel = null;
        int had = MineLeave.WALK_OUTS.count();
        int[] dry = _lastDry == null ? null : new int[]{_lastDry.getX(), _lastDry.getY(), _lastDry.getZ()};
        if (MineLeave.WALK_OUTS.start(dim, feet.getX(), feet.getY(), feet.getZ(), dry, now)) {
            _wetStall = true;
            _wetOpen = !roofed;
            _wetX = MineLeave.WALK_OUTS.x();
            _wetZ = MineLeave.WALK_OUTS.z();
            adris.altoclef.Debug.logInternal("branch mine: " + where + " again at " + feet.toShortString() + ", the third walk-out"
                    + " in a row within ten blocks of " + _wetX + ", " + _wetZ + ", a fire of the place watch there ("
                    + MineLeave.WALK_OUTS.fires() + " of the walk-outs' within ten game minutes) (" + keys + ")");
        } else if (MineLeave.WALK_OUTS.count() != had && MineLeave.WALK_OUTS.count() > 1) {
            adris.altoclef.Debug.logInternal("branch mine: " + where + " again at " + feet.toShortString() + ", "
                    + MineLeave.WALK_OUTS.count() + " walk-outs in a row here (" + keys + ")");
        }
    }

    private static String dimension() {
        return String.valueOf(adris.altoclef.util.helpers.WorldHelper.getCurrentDimension());
    }

    // (mine_leave_given_up) the way down through the open whose searches that get nowhere are counted by place
    private final class DownTask extends adris.altoclef.tasks.movement.GetToYTask {
        DownTask(int y) {
            super(y);
        }

        @Override
        protected void onNoProgress(AltoClef mod) {
            BlockPos at = mod.getPlayer().blockPosition();
            int had = _downFails.count();
            if (_downFails.fail(at.getX(), at.getZ(), mod.getWorld().getGameTime())) {
                _downStall = true;
            } else if (_downFails.count() != had) {
                adris.altoclef.Debug.logInternal("branch mine: the way down to y " + _mineY + " got nowhere at " + at.toShortString()
                        + ", " + _downFails.count() + " in a row here (mine_leave_given_up)");
            }
        }
    }

    // (mine_leave_given_up) away from a spot a mine gave its depth up at, or null when there is none near or it is left
    private Task leaveGivenUp(AltoClef mod, BlockPos feet, long now) {
        if (_leaveFrom == null) {
            // (mine_leave_once) a spot an earlier mine of the order left, or gave up leaving, is not left again
            boolean once = MineLeave.onceOn();
            MineLeave.Spot spot = MineLeave.GIVEN_UP.near(dimension(), feet.getX(), feet.getZ(), now,
                    MineLeave.NEAR, MineLeave.FRESH, once ? MineLeave.GIVEN_UP.leftAnd(_leaveDone) : _leaveDone);
            if (spot == null) {
                MineLeave.Spot was = once ? MineLeave.GIVEN_UP.near(dimension(), feet.getX(), feet.getZ(), now,
                        MineLeave.NEAR, MineLeave.FRESH, _leaveDone) : null;
                if (was != null) {
                    _leaveDone.add(was);
                    adris.altoclef.Debug.logInternal("branch mine: " + Math.round(Math.hypot(feet.getX() - was.x(), feet.getZ() - was.z()))
                            + " blocks from " + was.x() + ", " + was.z() + ", where a mine gave its depth up " + (now - was.at()) / 20
                            + " s ago, left once already this order: the mine goes on here (mine_leave_once)");
                }
                return null;
            }
            _leaveFrom = spot;
            _leaveTried = 0;
            _leaveWays = 0;
            _leaveWay = -1;
            _leaveTask = null;
            _leaveTarget = null;
            _leaveFarSaid = false;
            adris.altoclef.Debug.logInternal("branch mine: " + Math.round(Math.hypot(feet.getX() - spot.x(), feet.getZ() - spot.z())) + " blocks from "
                    + spot.x() + ", " + spot.z() + ", where a mine gave its depth up " + (now - spot.at()) / 20 + " s ago: "
                    + (int) MineLeave.FAR + " blocks away from there first (mine_leave_given_up)");
        }
        double d = Math.hypot(feet.getX() - _leaveFrom.x(), feet.getZ() - _leaveFrom.z());
        boolean far = MineLeave.left(feet.getX(), feet.getZ(), _leaveFrom);
        boolean dryOn = leaveDryOn();
        // (mine_leave_dry) only on dry ground, and FAR from every fresh spot given up, not this one alone
        // (open_water_land_hold) dry ground, not a one-tick onGround: a leave walked 120 blocks with no dry ground and ended on a
        // ledge at the sea's height, the staircase from it in the same flooded cave
        boolean dry = mod.getPlayer().onGround() && !mod.getPlayer().isInWater()
                && (!landHoldOn() || dryGround(mod.getWorld()::getBlockState, feet));
        boolean clear = MineLeave.GIVEN_UP.clearOf(dimension(), feet.getX(), feet.getZ(), now, MineLeave.FAR, MineLeave.FRESH);
        if (dryOn ? MineLeave.leaveEnds(far, dry, clear) : far) {
            adris.altoclef.Debug.logInternal("branch mine: " + Math.round(d) + " blocks from " + _leaveFrom.x() + ", " + _leaveFrom.z()
                    + " at " + feet.toShortString() + ", the mine goes on from here (mine_leave_given_up" + (dryOn ? ", mine_leave_dry" : "") + ")");
            endLeave();
            return null;
        }
        if (far && dryOn && !_leaveFarSaid) {
            _leaveFarSaid = true;
            adris.altoclef.Debug.logInternal("branch mine: " + Math.round(d) + " blocks from " + _leaveFrom.x() + ", " + _leaveFrom.z()
                    + " at " + feet.toShortString() + " but " + (!dry ? "not on dry ground" : "within " + (int) MineLeave.FAR
                    + " of another spot a mine gave up") + ", on along the way (mine_leave_dry)");
        }
        // (mine_leave_dry) the way's goal reached and the leave not over: the goal EXTEND further along the same way, past
        // REACH_MAX the next way
        boolean nextWay = false;
        if (dryOn && _leaveWay >= 0 && _leaveTarget != null
                && Math.hypot(feet.getX() - _leaveTarget[0], feet.getZ() - _leaveTarget[1]) <= MineLeave.TARGET_RADIUS + 1) {
            int reach = MineLeave.nextReach(_leaveReach);
            if (reach < 0) {
                nextWay = true;
                adris.altoclef.Debug.logInternal("branch mine: no dry ground clear of the spots given up within " + MineLeave.REACH_MAX
                        + " of " + _leaveFrom.x() + ", " + _leaveFrom.z() + " that way, the next way (mine_leave_dry)");
            } else {
                _leaveReach = reach;
                _leaveTarget = MineLeave.target(_leaveFrom.x(), _leaveFrom.z(), _leaveWay, reach);
                _leaveTask = new GetToXZTask(_leaveTarget[0], _leaveTarget[1], MineLeave.TARGET_RADIUS);
                _leaveBestAt = now;
                adris.altoclef.Debug.logInternal("branch mine: on along way " + _leaveWays + " to " + _leaveTarget[0] + ", " + _leaveTarget[1]
                        + ", " + reach + " blocks from " + _leaveFrom.x() + ", " + _leaveFrom.z() + " (mine_leave_dry)");
            }
        }
        if (nextWay || _leaveWay < 0 || now < _leaveBestAt || now - _leaveBestAt > LEAVE_STALL) {
            if (_leaveWay >= 0 && !nextWay) {
                adris.altoclef.Debug.logInternal("branch mine: no headway away from " + _leaveFrom.x() + ", " + _leaveFrom.z() + " in "
                        + LEAVE_STALL / 1200 + " game minutes at " + feet.toShortString() + ", " + Math.round(d) + " blocks off (mine_leave_given_up)");
            }
            int[] water = waterByWay(mod, feet);
            int way = _leaveWays < LEAVE_WAYS ? MineLeave.nextWay(water,
                    feet.getX() - _leaveFrom.x(), feet.getZ() - _leaveFrom.z(), _leaveTried) : -1;
            if (way < 0) {
                adris.altoclef.Debug.logInternal("branch mine: could not get " + (int) MineLeave.FAR + " blocks from "
                        + _leaveFrom.x() + ", " + _leaveFrom.z() + " in " + _leaveWays + " ways, the mine goes on here at "
                        + feet.toShortString() + " (mine_leave_given_up)");
                setDebugState("Could not get away from where a mine gave up, mining here after all");
                endLeave();
                return null;
            }
            _leaveWays++;
            _leaveTried |= 1 << way;
            _leaveWay = way;
            int[] t = MineLeave.target(_leaveFrom.x(), _leaveFrom.z(), way);
            _leaveReach = MineLeave.TARGET;
            _leaveTarget = t;
            _leaveTask = new GetToXZTask(t[0], t[1], MineLeave.TARGET_RADIUS);
            _leaveBest = d;
            _leaveBestAt = now;
            adris.altoclef.Debug.logInternal("branch mine: away from " + _leaveFrom.x() + ", " + _leaveFrom.z() + " to " + t[0] + ", " + t[1]
                    + ", way " + _leaveWays + " of " + LEAVE_WAYS + ", " + water[way] + " water blocks seen that way (mine_leave_given_up)");
        }
        if (d > _leaveBest + 2) {
            _leaveBest = d;
            _leaveBestAt = now;
        }
        stopMine(mod);
        setDebugState("Away from where a mine gave up, " + (int) Math.ceil(MineLeave.FAR - d)
                + " blocks to go (way " + _leaveWays + " of " + LEAVE_WAYS + ")");
        return _leaveTask;
    }

    private void endLeave() {
        _leaveDone.add(_leaveFrom);
        if (MineLeave.onceOn()) MineLeave.GIVEN_UP.left(_leaveFrom);
        _leaveFrom = null;
        _leaveTask = null;
        _leaveWay = -1;
        _downFails.reset();
        _downStall = false;
        _step = null;
        _tunnel = null;
    }

    // (mine_leave_given_up) the water blocks seen each of MineLeave.WAYS: a column six up and down at every six blocks out to 30
    private static int[] waterByWay(AltoClef mod, BlockPos feet) {
        int[][] ways = MineLeave.WAYS;
        int[] out = new int[ways.length];
        var level = mod.getWorld();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < ways.length; i++) {
            double len = Math.hypot(ways[i][0], ways[i][1]);
            for (int r = 6; r <= 30; r += 6) {
                int x = feet.getX() + (int) Math.round(ways[i][0] * r / len), z = feet.getZ() + (int) Math.round(ways[i][1] * r / len);
                for (int dy = -6; dy <= 6; dy++) {
                    p.set(x, feet.getY() + dy, z);
                    if (baritone.pathing.movement.MovementHelper.isWater(level.getBlockState(p))) out[i]++;
                }
            }
        }
        return out;
    }

    // (gold_wide_band) this mine's height lies below 0 and within the wide band
    private boolean wideBand() {
        return wideBand(_y) && adris.altoclef.util.helpers.Tuning.get("gold_wide_band", 0) > 0;
    }

    // (gold_band_core) the gold mine (not the slime dig) keeps to gold's own band
    private boolean goldCore() {
        return !_slimeDig && wideBand(_y) && gold(_blocks) && adris.altoclef.util.helpers.Tuning.get("gold_band_core", 0) > 0;
    }

    private int bandLo() {
        return band(goldCore())[0];
    }

    private int bandHi() {
        return band(goldCore())[1];
    }

    private boolean inBand(int y) {
        return y >= bandLo() && y <= bandHi();
    }

    // the band a mine below 0 starts and stays in: gold's core, or the wide one
    static int[] band(boolean goldCore) {
        return goldCore ? new int[]{GOLD_LO, GOLD_HI} : new int[]{WIDE_LO, WIDE_HI};
    }

    static boolean gold(Block[] blocks) {
        for (Block b : blocks) {
            String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (id.endsWith("gold_ore") && !id.contains("nether")) return true;
        }
        return false;
    }

    // (gold_poke_shafts) the poke hole pattern for this mine: gold and the slime dig, the wide band's scope
    private boolean pokeOn() {
        return wideBand(_y) && adris.altoclef.util.helpers.Tuning.get("gold_poke_shafts", 0) > 0;
    }

    // (gold_rock_iron) the pickaxes in the bag, once a second: how many unworn ones of iron or better pass the kit's, whether an
    // iron one is free to dig rock (worn already, or unworn and past the kit's), and that to ToolPick for the next 30 s. a line
    // when it changes
    private void rockPick(AltoClef mod, long now) {
        if (!wideBand(_y) || adris.altoclef.util.helpers.Tuning.get("gold_rock_iron", 0) <= 0) {
            _rockIron = false;
            return;
        }
        // (a bug fix of 14 Nov 2026: _rockLook starts at Long.MIN_VALUE, and now - Long.MIN_VALUE overflowed to a negative
        // number, so this returned on every tick and gold_rock_iron never ran, test heel's gold mine; Recent.within)
        if (adris.altoclef.util.helpers.Recent.within(now, _rockLook, 20)) {
            ToolPick.rockIron(_rockFree, System.currentTimeMillis() + 30_000);
            return;
        }
        _rockLook = now;
        var inv = mod.getPlayer().getInventory();
        int up = 0;
        boolean wornIron = false, unwornIron = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            boolean iron = st.is(Items.IRON_PICKAXE);
            if (!iron && !st.is(Items.DIAMOND_PICKAXE) && !st.is(Items.NETHERITE_PICKAXE)) continue;
            boolean worn = ToolWear.wornOut(st.getDamageValue(), st.getMaxDamage());
            if (!worn) up++;
            boolean saved = Baritone.settings().itemSaver.value && st.getMaxDamage() > 1
                    && st.getDamageValue() + Baritone.settings().itemSaverThreshold.value >= st.getMaxDamage();
            if (iron && !saved) {
                if (worn) wornIron = true;
                else unwornIron = true;
            }
        }
        _rockFree = Math.max(0, up - KIT_PICKS);
        _rockIron = wornIron || unwornIron && _rockFree > 0;
        ToolPick.rockIron(_rockFree, System.currentTimeMillis() + 30_000);
        if (_rockSaid == null || _rockSaid != _rockIron) {
            _rockSaid = _rockIron;
            adris.altoclef.Debug.logInternal("branch mine: the rock " + (_rockIron ? "with an iron pickaxe (" + (wornIron ? "a worn one" : _rockFree + " free")
                    + " past the kit's " + KIT_PICKS + ")" : "with the cheapest pickaxe, no iron one free past the kit's " + KIT_PICKS
                    + " (" + up + " unworn of iron or better)") + " (gold_rock_iron)");
        }
    }

    static boolean wideBand(int targetY) {
        return targetY < WIDE_HI && targetY >= WIDE_LO;
    }

    static boolean inWide(int y) {
        return y >= WIDE_LO && y <= WIDE_HI;
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    // (mine_water_roof) in water under a roof: the walk to the nearest dry cell to stand in within DRY_REACH, else back to the
    // last dry cell stood on within DRY_BACK; a cell not reached in 20 game seconds is passed over for another. null when there
    // is none, and the checks after this have the tick
    private Task outOfRoofedWater(AltoClef mod, BlockPos feet, long now) {
        if (_dry != null && now - _drySince > DRY_STEP && !feet.equals(_dry)) {
            _dryTried.put(_dry, now);
            _dry = null;
        }
        if (_dry == null || feet.equals(_dry)) {
            _dry = dryCell(mod, feet, _dryTried.keySet());
            if (_dry == null && _lastDry != null && !_dryTried.containsKey(_lastDry) && _lastDry.closerThan(feet, DRY_BACK)) _dry = _lastDry;
            _drySince = now;
            if (_dry == null) return null;
            adris.altoclef.Debug.logInternal("branch mine: in water under a roof at " + feet.toShortString() + ", out of the water first, to "
                    + _dry.toShortString() + (_dry.equals(_lastDry) ? " (the way it came)" : "") + " (mine_water_roof)");
        }
        stopMine(mod);
        setDebugState("In water under a roof, out of the water first, to " + _dry.toShortString());
        return new GetToBlockTask(_dry);
    }

    // the nearest cell within DRY_REACH, four up or down, that the buddy can stand in out of the water: the cell and the one
    // over it free of blocks and fluid, a solid block under it with no fluid in it. by distance, a cell above counting as
    // farther than one level with it
    private static BlockPos dryCell(AltoClef mod, BlockPos feet, java.util.Set<BlockPos> skip) {
        var level = mod.getWorld();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-DRY_REACH, -4, -DRY_REACH), feet.offset(DRY_REACH, 4, DRY_REACH))) {
            int dx = pos.getX() - feet.getX(), dy = pos.getY() - feet.getY(), dz = pos.getZ() - feet.getZ();
            double d = dx * dx + dz * dz + (dy > 0 ? 4.0 * dy * dy : dy * dy);
            if (d >= bestD) continue;
            if (!standable(level.getBlockState(pos.below()), level.getBlockState(pos), level.getBlockState(pos.above()))) continue;
            BlockPos im = pos.immutable();
            if (skip.contains(im)) continue;
            best = im;
            bestD = d;
        }
        return best;
    }

    // a cell to stand in out of the water: feet and head free of blocks and fluid, a solid floor with no fluid in it
    static boolean standable(net.minecraft.world.level.block.state.BlockState floor, net.minecraft.world.level.block.state.BlockState cell,
                             net.minecraft.world.level.block.state.BlockState head) {
        return floor.isSolid() && floor.getFluidState().isEmpty()
                && free(cell) && free(head);
    }

    private static boolean free(net.minecraft.world.level.block.state.BlockState st) {
        return st.getFluidState().isEmpty() && !st.isSolid() && !st.is(Blocks.LAVA) && !st.is(Blocks.FIRE)
                && !st.is(Blocks.POWDER_SNOW) && !st.is(Blocks.COBWEB);
    }

    // (ice_start_tunnel_hold) one step of the tunnel of its own on ice or open water: straight on at the feet's height
    private long _holdLogged = Long.MIN_VALUE;

    private Task holdTunnel(AltoClef mod, BlockPos feet, long now, boolean wet) {
        stopMine(mod);
        if (_holdLogged != _tunnelUntil) {
            _holdLogged = _tunnelUntil;
            adris.altoclef.Debug.logInternal("branch mine: the tunnel of its own keeps the tick on " + (wet ? "water" : "ice") + " at "
                    + feet.toShortString() + ", level at y " + feet.getY() + " for " + (_tunnelUntil - now) / 20 + " s (ice_start_tunnel_hold)");
        }
        if (_dx == 0 && _dz == 0) {
            var dir = mod.getPlayer().getDirection();
            _dx = dir.getStepX();
            _dz = dir.getStepZ();
            if (_dx == 0 && _dz == 0) _dx = 1;
        }
        boolean reached = _tunnel != null && feet.getX() == _tunnel.getX() && feet.getZ() == _tunnel.getZ();
        if (_tunnel != null && !reached && now - _tunnelSince > 200) {
            int t = _dx;
            _dx = -_dz;
            _dz = t;
            _tunnel = null;
        }
        if (_tunnel == null || reached || Math.abs(_tunnel.getY() - feet.getY()) > 1) {
            _tunnel = new BlockPos(feet.getX() + _dx, feet.getY(), feet.getZ() + _dz);
            _tunnelSince = now;
        }
        setDebugState("A tunnel of its own, level at y " + feet.getY() + " on " + (wet ? "open water" : "ice") + " for its whole time");
        return new GetToBlockTask(_tunnel);
    }

    private static ItemTarget stoneTarget() {
        return new ItemTarget("stone_material", 1);
    }

    // a stone pickaxe made now, or the table it put down for it picked up again, or null
    private Task cheapPick(AltoClef mod, long now) {
        if (_ourTable != null) {
            if (now > _tableUntil || mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) > _tablesHad) {
                _ourTable = null;
                return null;
            }
            if (mod.getWorld().getBlockState(_ourTable).is(Blocks.CRAFTING_TABLE)) {
                setDebugState("The crafting table it put down, back in the bag");
                return new DestroyBlockTask(_ourTable);
            }
            setDebugState("The crafting table it broke, picked up");
            return new adris.altoclef.tasks.movement.PickupDroppedItemTask(Items.CRAFTING_TABLE, _tablesHad + 1);
        }
        if (_cheapCraft != null) {
            boolean made = mod.getItemStorage().getItemCount(Items.STONE_PICKAXE) >= _cheapWant;
            if (!made && now < _cheapUntil && !_cheapCraft.isFinished(mod)) {
                setDebugState("A stone pickaxe for the rock, the iron one is for the ore");
                return _cheapCraft;
            }
            _cheapCraft = null;
            // not made in a game minute: not again for five
            _cheapAfter = made ? now : now + 6000;
            _ourTable = newTable(mod, _tablesBefore);
            _tableUntil = now + 600;
            return null;
        }
        // (kit_diamond_off_rock) the kit's diamond pickaxe and wood at most for the rock: a stone one is made first
        boolean kitOnly = kitOffOn() && kitOnlyForRock(mod);
        if (!kitOnly) _kitSaid = false;
        // (gold_rock_iron) no stone pickaxes made while an iron one is free for the rock
        if (now < _cheapAfter || !(needsBetterThanStone(_blocks) || kitOnly) || cheapPicks(mod) > 0 || _rockIron) {
            if (kitOnly && now < _cheapAfter) kitRockSay(mod, "the last craft of one failed, not again for five game minutes");
            return null;
        }
        int stone = mod.getItemStorage().getItemCount(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE);
        int planks = mod.getItemStorage().getItemCount(ItemHelper.PLANKS);
        boolean table = mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) > 0;
        int held = mod.getItemStorage().getItemCount(Items.STICK);
        // a table of four planks first when there is none in the bag: the kit's was put down for the armour on the surface and
        // left there, and without one this never started (the runner, 2 Nov 2026, attempt 82)
        int sticks = held + 4 * (Math.max(0, planks - (table ? 0 : 4)) / 2);
        // (cheap_pick_keep) only the sticks and planks past StickReserve's keep: look 98's batch at 05:17 made two stone pickaxes
        // from the last two planks and left one stick and no wood, so no next pickaxe could be made
        int n = adris.altoclef.util.helpers.StickReserve.keepOn() ? adris.altoclef.util.helpers.StickReserve.cheapBatch(held, planks, table, stone)
                : Math.min(2, Math.min(stone / 3, sticks / 2));
        boolean fromKeep = false;
        if (n < 1 && kitOnly) {
            // (kit_diamond_off_rock) one stone pickaxe from all the bag's wood, the keep's too: it is the next pickaxe the keep is for
            int logs = mod.getItemStorage().getItemCount(ItemHelper.LOG);
            n = adris.altoclef.util.helpers.StickReserve.kitRockPick(held, planks, logs, table, stone);
            if (n < 1) {
                kitRockSay(mod, stone + " stone, " + held + " sticks, " + planks + " planks, " + logs + " logs, " + (table ? "a table" : "no table"));
                return null;
            }
            fromKeep = true;
        }
        if (n < 1 || !table && planks < 4 && !fromKeep) {
            return null;
        }
        // only with the table on it: without one it walks to any it has seen, and down here that is a walk through the caves
        if (!table) {
            setDebugState("A crafting table out of four planks, for a stone pickaxe");
            return adris.altoclef.TaskCatalogue.getItemTask("crafting_table", 1);
        }
        _tablesBefore = tablesNear(mod, 8);
        _tablesHad = mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) - 1;
        _cheapWant = mod.getItemStorage().getItemCount(Items.STONE_PICKAXE) + n;
        _cheapUntil = now + 1200;
        _cheapCraft = new CraftInTableTask(new RecipeTarget(Items.STONE_PICKAXE, _cheapWant, STONE_PICK));
        if (fromKeep) {
            adris.altoclef.Debug.logInternal("branch mine: only the kit's diamond pickaxe for the rock, a stone one made first ("
                    + stone + " stone, " + held + " sticks, " + planks + " planks) (kit_diamond_off_rock)");
        }
        setDebugState("A stone pickaxe for the rock, the iron one is for the ore");
        return _cheapCraft;
    }

    // (stick_reserve) sticks made now, the next pickaxe made now, or the table for it out of four planks, or null
    private Task pickAhead(AltoClef mod, long now) {
        if (!adris.altoclef.util.helpers.StickReserve.on()) {
            return null;
        }
        var bag = mod.getItemStorage();
        if (_aheadCraft != null) {
            boolean made = bag.getItemCount(_aheadItem) >= _aheadWant;
            if (!made && now < _aheadUntil && !_aheadCraft.isFinished(mod)) {
                setDebugState(_aheadItem == Items.STICK ? "Sticks from the bag's wood, for the next pickaxe" : "The next pickaxe, before the last one is gone");
                return _aheadCraft;
            }
            String what = _aheadItem == Items.STICK ? "sticks" : BuiltInRegistries.ITEM.getKey(_aheadItem).getPath().replace('_', ' ');
            adris.altoclef.Debug.logInternal("branch mine: " + what + (made ? " made" : " not made in time, not again for five game minutes")
                    + " (stick_reserve)");
            if (_aheadItem != Items.STICK) {
                _ourTable = newTable(mod, _tablesBefore);
                _tableUntil = now + 600;
            }
            _aheadCraft = null;
            // not made in time: not again for five game minutes
            _aheadAfter = made ? now : now + 6000;
            return null;
        }
        // (next_pick_more) the trip up for logs, until it brought them or ran out of time
        if (_woodTrip != null) {
            int logsNow = bag.getItemCount(ItemHelper.LOG);
            if (logsNow < _woodWant && now < _woodUntil && !_woodTrip.isFinished(mod)) {
                setDebugState("Up for logs, the next pickaxe has no wood");
                return _woodTrip;
            }
            boolean got = logsNow >= _woodWant;
            adris.altoclef.Debug.logInternal("branch mine: the trip for logs " + (got ? "brought them" : "is over with " + logsNow
                    + " logs, not again for five game minutes") + " (next_pick_more)");
            _woodAfter = got ? now : now + 6000;
            _woodTrip = null;
        }
        if (now < _aheadAfter) {
            return null;
        }
        int sticks = bag.getItemCount(Items.STICK);
        int planks = bag.getItemCount(ItemHelper.PLANKS);
        int logs = bag.getItemCount(ItemHelper.LOG);
        if (adris.altoclef.util.helpers.StickReserve.makeSticks(sticks, planks, logs)) {
            _aheadItem = Items.STICK;
            _aheadWant = sticks + 4;
            _aheadUntil = now + 600;
            _aheadCraft = adris.altoclef.TaskCatalogue.getItemTask("stick", _aheadWant);
            adris.altoclef.Debug.logInternal("branch mine: " + sticks + " sticks in the bag, 4 made from its wood (" + planks + " planks, "
                    + logs + " logs) for the next pickaxe (stick_reserve)");
            setDebugState("Sticks from the bag's wood, for the next pickaxe");
            return _aheadCraft;
        }
        boolean table = bag.getItemCount(Items.CRAFTING_TABLE) > 0;
        int stone = bag.getItemCount(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE);
        int good = goodAboveWood(mod);
        adris.altoclef.util.helpers.StickReserve.Next next = adris.altoclef.util.helpers.StickReserve.nextPick(good,
                bag.getItemCount(Items.IRON_INGOT), stone, sticks, planks, logs, table, !needsBetterThanStone(_blocks),
                bag.getItemCount(Items.DIAMOND), adris.altoclef.util.helpers.StickReserve.moreOn());
        // (pickless_way_out) the trip up for logs with no pickaxe above wood, where the table was what the wood lacked: a table
        // standing within the bench's reach does for it, the craft walks there
        boolean tableNear = false;
        if (next == adris.altoclef.util.helpers.StickReserve.Next.LOGS && !table && wayOutOn() && !anyAboveWood(mod) && tableNear(mod, now)) {
            adris.altoclef.util.helpers.StickReserve.Next atTable = adris.altoclef.util.helpers.StickReserve.nextPick(good,
                    bag.getItemCount(Items.IRON_INGOT), stone, sticks, planks, logs, false, !needsBetterThanStone(_blocks),
                    bag.getItemCount(Items.DIAMOND), true, true);
            if (atTable != adris.altoclef.util.helpers.StickReserve.Next.LOGS && atTable != adris.altoclef.util.helpers.StickReserve.Next.NONE) {
                next = atTable;
                tableNear = true;
                adris.altoclef.Debug.logInternal("branch mine: no pickaxe above wood and no wood for a table, a crafting table stands within "
                        + Math.round(adris.altoclef.util.helpers.WalkCost.STATION_NEAR) + " blocks: the next pickaxe made there (pickless_way_out)");
            }
        }
        if (next == adris.altoclef.util.helpers.StickReserve.Next.NONE) {
            return null;
        }
        if (next == adris.altoclef.util.helpers.StickReserve.Next.LOGS) {
            // (next_pick_more) up to the surface for logs (the log climb of log_far_surface), three of them, ten game minutes at most
            if (now < _woodAfter) {
                return null;
            }
            _woodWant = logs + WOOD_TRIP_LOGS;
            _woodUntil = now + 12000;
            boolean up = adris.altoclef.tasks.resources.MineAndCollectTask.climbForLogs(mod, "no wood for the next pickaxe");
            adris.altoclef.Debug.logInternal("branch mine: no pickaxe above wood left good and no wood for the next one (" + sticks
                    + " sticks, " + planks + " planks, " + logs + " logs, " + (table ? "a table" : "no table") + "): " + WOOD_TRIP_LOGS
                    + " logs " + (up ? "from the surface" : "from here") + " (next_pick_more)");
            _woodTrip = adris.altoclef.TaskCatalogue.getItemTask("log", _woodWant);
            setDebugState("Up for logs, the next pickaxe has no wood");
            return _woodTrip;
        }
        // only with the table in the bag, as the stone pickaxes: without one it walks to any it has seen, down here a walk through
        // the caves (pickless_way_out: one within the bench's reach, with no pickaxe to dig at all)
        if (!table && !tableNear) {
            setDebugState("A crafting table out of four planks, for the next pickaxe");
            return adris.altoclef.TaskCatalogue.getItemTask("crafting_table", 1);
        }
        boolean iron = next == adris.altoclef.util.helpers.StickReserve.Next.IRON;
        boolean diamond = next == adris.altoclef.util.helpers.StickReserve.Next.DIAMOND;
        _aheadItem = iron ? Items.IRON_PICKAXE : diamond ? Items.DIAMOND_PICKAXE : Items.STONE_PICKAXE;
        _aheadWant = bag.getItemCount(_aheadItem) + 1;
        _aheadUntil = now + 1200;
        _tablesBefore = tablesNear(mod, 8);
        _tablesHad = bag.getItemCount(Items.CRAFTING_TABLE) - 1;
        _aheadCraft = new CraftInTableTask(new RecipeTarget(_aheadItem, _aheadWant, iron ? IRON_PICK : diamond ? DIAMOND_PICK : STONE_PICK));
        adris.altoclef.Debug.logInternal("branch mine: no unworn pickaxe above wood left in the bag, " + (iron ? "an iron" : diamond ? "a diamond" : "a stone")
                + " one made now from the bag (" + bag.getItemCount(Items.IRON_INGOT) + " iron ingots, " + stone + " stone, "
                + bag.getItemCount(Items.DIAMOND) + " diamonds, " + sticks + " sticks) (stick_reserve" + (diamond ? ", next_pick_more" : "") + ")");
        setDebugState("The next pickaxe, before the last one is gone");
        return _aheadCraft;
    }

    // (mine_kit_surface) the pickaxes and the wood before the mine goes down from the surface, or null: not the gold mine or the
    // slime dig, done for this task, not on the surface (more than LogReach.ROOF under the sky's height) or within 8 of the
    // mine's height, or a fetch of the order ran out of its time less than SURFACE_OFF ago
    private Task surfaceKit(AltoClef mod, BlockPos feet, int y, long now) {
        if (_surfaceDone || !gold(_blocks) || !wideBand(_y) || !adris.altoclef.util.helpers.StickReserve.surfaceOn()) {
            return null;
        }
        int depth = mod.getWorld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()) - y;
        boolean off = surfaceOffUntil != Long.MIN_VALUE && now < surfaceOffUntil && surfaceOffUntil - now <= SURFACE_OFF;
        if (y - _mineY <= 8 || depth > adris.altoclef.util.helpers.LogReach.ROOF || off) {
            return null;
        }
        var bag = mod.getItemStorage();
        int picks = picksBesidesKit(mod);
        int sticks = bag.getItemCount(Items.STICK);
        int planks = bag.getItemCount(ItemHelper.PLANKS);
        int logs = bag.getItemCount(ItemHelper.LOG);
        boolean table = bag.getItemCount(Items.CRAFTING_TABLE) > 0;
        adris.altoclef.util.helpers.StickReserve.Ready ready = adris.altoclef.util.helpers.StickReserve.surfaceReady(picks, sticks, planks, logs, table);
        String bagLine = picks + " pickaxes above wood besides the kit's diamond one, " + sticks + " sticks, " + planks + " planks, "
                + logs + " logs, " + (table ? "a table" : "no table");
        if (ready == adris.altoclef.util.helpers.StickReserve.Ready.READY) {
            _surfaceDone = true;
            if (_surfaceSince != Long.MIN_VALUE) {
                adris.altoclef.Debug.logInternal("branch mine: ready to go down for the gold: " + bagLine + " (mine_kit_surface)");
            }
            _surfaceTask = null;
            return null;
        }
        if (_surfaceSince == Long.MIN_VALUE || now < _surfaceSince) _surfaceSince = now;
        if (now - _surfaceSince > SURFACE_CAP) {
            _surfaceDone = true;
            _surfaceTask = null;
            surfaceOffUntil = now + SURFACE_OFF;
            adris.altoclef.Debug.logInternal("branch mine: the pickaxes and the wood not ready in " + SURFACE_CAP / 1200 + " game minutes on the surface ("
                    + bagLine + "), down anyway (mine_kit_surface)");
            return null;
        }
        if (_surfaceTask != null && ready == _surfaceWhat && !_surfaceTask.isFinished(mod)) {
            return _surfaceTask;
        }
        _surfaceWhat = ready;
        if (ready == adris.altoclef.util.helpers.StickReserve.Ready.PICKS) {
            int want = bag.getItemCount(Items.STONE_PICKAXE) + adris.altoclef.util.helpers.StickReserve.SURFACE_PICKS - picks;
            _surfaceTask = adris.altoclef.TaskCatalogue.getItemTask("stone_pickaxe", want);
            adris.altoclef.Debug.logInternal("branch mine: before going down for the gold, " + bagLine + ": "
                    + (adris.altoclef.util.helpers.StickReserve.SURFACE_PICKS - picks) + " stone pickaxes made here on the surface (mine_kit_surface)");
            setDebugState("Pickaxes before going down, made on the surface");
        } else {
            int want = adris.altoclef.util.helpers.StickReserve.surfaceLogs(sticks, planks, logs, table);
            _surfaceTask = adris.altoclef.TaskCatalogue.getItemTask("log", want);
            adris.altoclef.Debug.logInternal("branch mine: before going down for the gold, " + bagLine + ": " + (want - logs)
                    + " logs here on the surface, the wood for one more pickaxe (mine_kit_surface)");
            setDebugState("Wood for one more pickaxe before going down, from the surface");
        }
        return _surfaceTask;
    }

    // (mine_kit_surface) the unworn pickaxes above wood in the bag that are not the kit's (diamond or netherite) and that itemSaver
    // does not keep back
    private static int picksBesidesKit(AltoClef mod) {
        var inv = mod.getPlayer().getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || st.is(Items.WOODEN_PICKAXE) || st.is(Items.GOLDEN_PICKAXE) || ToolPick.kitPick(st)
                    || !"pickaxe".equals(ItemHelper.toolKind(st.getItem()))) {
                continue;
            }
            if (!saved(st) && !ToolWear.wornOut(st.getDamageValue(), st.getMaxDamage())) n++;
        }
        return n;
    }

    // (Tuning pickless_way_out, a fix of 14 Nov 2026, on) see MineAndCollectTask.startClimb and the table near here
    static boolean wayOutOn() {
        return adris.altoclef.util.helpers.Tuning.get("pickless_way_out", 0) > 0;
    }

    // (pickless_way_out) a crafting table stands within the bench's reach (WalkCost.STATION_NEAR across, 6 up and down), looked at
    // most every TABLE_LOOK ticks
    private boolean tableNear(AltoClef mod, long now) {
        if (now >= _tableLookAt && now - _tableLookAt < TABLE_LOOK) return _tableNear;
        _tableLookAt = now;
        _tableNear = false;
        BlockPos feet = mod.getPlayer().blockPosition();
        int r = (int) adris.altoclef.util.helpers.WalkCost.STATION_NEAR;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -6, -r), feet.offset(r, 6, r))) {
            if (mod.getWorld().getBlockState(pos).is(Blocks.CRAFTING_TABLE)
                    && adris.altoclef.util.helpers.WalkCost.stationDistance(pos.getX(), pos.getY(), pos.getZ(), feet.getX(), feet.getY(), feet.getZ())
                    <= adris.altoclef.util.helpers.WalkCost.STATION_NEAR) {
                _tableNear = true;
                break;
            }
        }
        return _tableNear;
    }

    // the pickaxes above wood in the bag (not wooden, not golden) that are not worn past ToolWear's line
    private static int goodAboveWood(AltoClef mod) {
        var inv = mod.getPlayer().getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || st.is(Items.WOODEN_PICKAXE) || st.is(Items.GOLDEN_PICKAXE) || !"pickaxe".equals(ItemHelper.toolKind(st.getItem()))) {
                continue;
            }
            if (!ToolWear.wornOut(st.getDamageValue(), st.getMaxDamage())) {
                n++;
            }
        }
        return n;
    }

    // one of the blocks wants more than a stone pickaxe (diamond, redstone, gold, emerald)
    static boolean needsBetterThanStone(Block[] blocks) {
        ItemStack stone = new ItemStack(Items.STONE_PICKAXE);
        for (Block b : blocks) {
            if (b.defaultBlockState().requiresCorrectToolForDrops() && !stone.isCorrectToolForDrops(b.defaultBlockState())) {
                return true;
            }
        }
        return false;
    }

    // (kit_diamond_off_rock) in the wide band's scope (gold and the slime dig), as gold_rock_iron
    private boolean kitOffOn() {
        return wideBand(_y) && adris.altoclef.util.helpers.Tuning.get("kit_diamond_off_rock", 0) > 0;
    }

    // (slime_dig_needs_pick) a mine for an ore stone cannot take, or the slime dig
    private boolean needsPickOn() {
        return (_slimeDig || needsBetterThanStone(_blocks)) && adris.altoclef.util.helpers.Tuning.get("slime_dig_needs_pick", 0) > 0;
    }

    private static boolean saved(ItemStack st) {
        return Baritone.settings().itemSaver.value && st.getMaxDamage() > 1
                && st.getDamageValue() + Baritone.settings().itemSaverThreshold.value >= st.getMaxDamage();
    }

    // (kit_diamond_off_rock) the bag's pickaxes for the rock are the kit's diamond one and wood or gold at most: no stone, copper
    // or iron one that itemSaver does not keep back
    private static boolean kitOnlyForRock(AltoClef mod) {
        var inv = mod.getPlayer().getInventory();
        boolean kit = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || !"pickaxe".equals(ItemHelper.toolKind(st.getItem()))) continue;
            if (ToolPick.kitPick(st)) {
                kit = true;
                continue;
            }
            if (st.is(Items.WOODEN_PICKAXE) || st.is(Items.GOLDEN_PICKAXE) || saved(st)) continue;
            return false;
        }
        return kit;
    }

    // (kit_diamond_off_rock) the line for a rock no stone pickaxe can be made for, once until one is there again
    private void kitRockSay(AltoClef mod, String why) {
        if (_kitSaid) return;
        _kitSaid = true;
        boolean wood = mod.getItemStorage().getItemCount(Items.WOODEN_PICKAXE) > 0;
        adris.altoclef.Debug.logInternal("branch mine: no stone pickaxe can be made for the rock (" + why + "): the rock with the "
                + (wood ? "wooden pickaxe, then the kit's diamond one" : "kit's diamond pickaxe, nothing else digs it") + " (kit_diamond_off_rock)");
    }

    // (slime_dig_needs_pick) a pickaxe above wood (not wooden or golden) in the bag that itemSaver does not keep back, worn or not
    private static boolean anyAboveWood(AltoClef mod) {
        var inv = mod.getPlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || st.is(Items.WOODEN_PICKAXE) || st.is(Items.GOLDEN_PICKAXE) || !"pickaxe".equals(ItemHelper.toolKind(st.getItem()))) {
                continue;
            }
            if (!saved(st)) return true;
        }
        return false;
    }

    // (slime_dig_needs_pick) the pickaxe before the mine: stone ones from the bag's spare wood, the next pickaxe from the bag
    // (iron, stone, diamond) or the trip up for logs, else the catalogue's stone pickaxe (cobble with a wooden one)
    private Task pickFirst(AltoClef mod, long now) {
        if (!_firstSaid) {
            _firstSaid = true;
            adris.altoclef.Debug.logInternal("branch mine: no pickaxe above wood in the bag, no mine until one is made (slime_dig_needs_pick)");
        }
        Task t = cheapPick(mod, now);
        if (t != null) return t;
        t = pickAhead(mod, now);
        if (t != null) return t;
        if (_firstPick == null) {
            adris.altoclef.Debug.logInternal("branch mine: no pickaxe from the bag, a stone pickaxe the catalogue's way (slime_dig_needs_pick)");
            _firstPick = adris.altoclef.TaskCatalogue.getItemTask("stone_pickaxe", 1);
        }
        setDebugState("A stone pickaxe before the mine");
        return _firstPick;
    }

    // the stone and copper pickaxes in the bag that are not worn past ToolWear's line
    private static int cheapPicks(AltoClef mod) {
        var inv = mod.getPlayer().getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            if ((st.is(Items.STONE_PICKAXE) || st.is(Items.COPPER_PICKAXE)) && !ToolWear.wornOut(st.getDamageValue(), st.getMaxDamage())) {
                n++;
            }
        }
        return n;
    }

    private static java.util.Set<BlockPos> tablesNear(AltoClef mod, int r) {
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        BlockPos feet = mod.getPlayer().blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -r, -r), feet.offset(r, r, r))) {
            if (mod.getWorld().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) out.add(pos.immutable());
        }
        return out;
    }

    // a table within eight blocks that was not there before the craft: the one the craft put down
    private static BlockPos newTable(AltoClef mod, java.util.Set<BlockPos> before) {
        if (before == null) return null;
        for (BlockPos pos : tablesNear(mod, 8)) {
            if (!before.contains(pos)) return pos;
        }
        return null;
    }

    // the nearest block of the deep dark's sculk within r, or null
    // the middle of the sculk within r, by its columns at the height of the feet and four up and down, or null
    private static BlockPos sculkMiddle(AltoClef mod, BlockPos feet, int r) {
        var level = mod.getWorld();
        long sx = 0, sz = 0;
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -4, -r), feet.offset(r, 4, r))) {
            var b = level.getBlockState(pos).getBlock();
            if (b == net.minecraft.world.level.block.Blocks.SCULK || b == net.minecraft.world.level.block.Blocks.SCULK_VEIN
                    || b == net.minecraft.world.level.block.Blocks.SCULK_SHRIEKER || b == net.minecraft.world.level.block.Blocks.SCULK_SENSOR
                    || b == net.minecraft.world.level.block.Blocks.SCULK_CATALYST || b == net.minecraft.world.level.block.Blocks.CALIBRATED_SCULK_SENSOR) {
                sx += pos.getX();
                sz += pos.getZ();
                n++;
            }
        }
        return n == 0 ? null : new BlockPos((int) Math.floorDiv(sx, n), feet.getY(), (int) Math.floorDiv(sz, n));
    }

    // (the buddy's fork: each walk away from the sculk within a few game minutes of the last one goes twice as far. a deep dark
    // is hundreds of blocks across: 48 along at y 18 and the stair down to y 8 met its sculk again, seventy-two times in two
    // real minutes, to and fro for the golden helmet's gold, attempt 238, 4 Nov 2026)
    private static final long AWAY_AGAIN_TICKS = 3600;
    private int _awayRound;
    private long _awayLast = Long.MIN_VALUE;

    // (Tuning sculk_away_cap, 12 Nov 2026, steering 170: the doubling at most to that round: 48 << 3 spread one run's mine
    // 756 blocks in x; 1 caps the walk at 96)
    private int awayLength(long now) {
        int cap = (int) Math.round(adris.altoclef.util.helpers.Tuning.get("sculk_away_cap", 3));
        _awayRound = _awayLast != Long.MIN_VALUE && now >= _awayLast && now - _awayLast < AWAY_AGAIN_TICKS ? Math.min(_awayRound + 1, cap) : 0;
        _awayLast = now;
        return 48 << _awayRound;
    }

    public static BlockPos nearestSculk(AltoClef mod, BlockPos feet, int r) {
        var level = mod.getWorld();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -r, -r), feet.offset(r, r, r))) {
            var b = level.getBlockState(pos).getBlock();
            if (b == net.minecraft.world.level.block.Blocks.SCULK || b == net.minecraft.world.level.block.Blocks.SCULK_VEIN
                    || b == net.minecraft.world.level.block.Blocks.SCULK_SHRIEKER || b == net.minecraft.world.level.block.Blocks.SCULK_SENSOR
                    || b == net.minecraft.world.level.block.Blocks.SCULK_CATALYST || b == net.minecraft.world.level.block.Blocks.CALIBRATED_SCULK_SENSOR) {
                double d = pos.distSqr(feet);
                if (d < bestD) { bestD = d; best = pos.immutable(); }
            }
        }
        return best;
    }

    // the nearest place to stand on dry ground, ring by ring out to 64 blocks in what is loaded, by the height map
    // ice in the column under the feet (LandFinder.ICE_DEPTH down), water not counted: a staircase over a lake's floor is
    // the water rule's and the liquid steps' business
    private static boolean iceColumn(AltoClef mod, BlockPos feet) {
        var level = mod.getWorld();
        for (int i = 1; i <= adris.altoclef.util.helpers.LandFinder.ICE_DEPTH; i++) {
            if (adris.altoclef.util.helpers.LandFinder.ice(level.getBlockState(feet.below(i)))) return true;
        }
        return false;
    }

    // (mine_leave_dry) the nearest land FAR from every spot given up within FRESH, else the nearest: the nearest lay back toward
    // the spot the leave had just walked 48 blocks from, and the staircase from it met the same flooded layer again
    // (open_water_land_hold) only dry ground (dryGround) is land, looked for out to ICE_LAND_REACH as off the ice: the nearest
    // land by the height map was a one-block islet, and the land the buddy reached at last lay 76 blocks off
    private static BlockPos nearestLand(AltoClef mod, BlockPos feet, long now) {
        var level = mod.getWorld();
        boolean strict = landHoldOn();
        java.util.function.Predicate<BlockPos> dry = strict ? p -> dryGround(level::getBlockState, p) : null;
        int reach = strict ? ICE_LAND_REACH : 64;
        if (!leaveDryOn() || MineLeave.GIVEN_UP.size() == 0) {
            return adris.altoclef.util.helpers.LandFinder.nearestLand(mod, feet, reach, false, dry, null);
        }
        String dim = dimension();
        BlockPos land = adris.altoclef.util.helpers.LandFinder.nearestLand(mod, feet, reach, false, dry,
                p -> MineLeave.GIVEN_UP.clearOf(dim, p.getX(), p.getZ(), now, MineLeave.FAR, MineLeave.FRESH));
        if (land != null && !MineLeave.GIVEN_UP.clearOf(dim, land.getX(), land.getZ(), now, MineLeave.FAR, MineLeave.FRESH)) {
            adris.altoclef.Debug.logInternal("branch mine: in open water at " + feet.toShortString() + ", no land within " + reach + " clear of a spot"
                    + " a mine gave up, to the nearest at " + land.toShortString() + " (mine_leave_dry)");
        }
        return land;
    }

    // the next step of a staircase (down one) or of a tunnel (level) in the way it goes, or the first of the other three
    // ways that does not open into a cave, a hole or lava. a staircase that broke into a cave let a skeleton shoot it, and a
    // ravine's edge let an arrow push it twenty blocks down (the runner, 1 Nov 2026, its first iron without armour). when
    // all four open into one, it keeps its way
    private void closedWay(AltoClef mod, BlockPos feet, int down) {
        if (waterStreakOn()) {
            driestWay(mod, feet, down);
            return;
        }
        for (int turn = 0; turn < 4; turn++) {
            if (!opens(mod, feet, _dx, _dz, down)) return;
            int t = _dx;
            _dx = -_dz;
            _dz = t;
        }
    }

    // a cell to stand in with air under it and under that: a step there is a bridge or a fall
    private static boolean floorless(AltoClef mod, BlockPos cell) {
        var level = mod.getWorld();
        return level.getBlockState(cell.below()).isAir() && level.getBlockState(cell.below(2)).isAir();
    }

    // every way's step down has nothing under it
    private static boolean noFloorAnyWay(AltoClef mod, BlockPos feet) {
        int[][] ways = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] w : ways) if (!floorless(mod, feet.offset(w[0], -1, w[1]))) return false;
        return true;
    }

    // (mine_water_streak) closedWay with water told apart: of the four ways, from the one it goes and round, the first whose
    // step opens into nothing, else the first that opens only into the open (a cave, a hole), else the first; a way whose step
    // comes within a block of the water cell last walked out of is taken only when all four do. in a cave every way opens into
    // the open, so the way kept was the one into the pool beside the dry cell, and baritone breaks nothing beside water
    // (tunnel_flip_guard) and a way whose step comes within a block of the step refused against water in the last game minute
    // likewise; a half turn round from a way into water refuses that step. every turn is said, against the way it went: the
    // line was said only against the old rule's pick, and the half turn back that the old rule also took said nothing
    private void driestWay(AltoClef mod, BlockPos feet, int down) {
        var level = mod.getWorld();
        long now = level.getGameTime();
        int[] wetCell = MineLeave.WALK_OUTS.cell(dimension(), now);
        int[] rank = wayRanks(level::getBlockState, feet, _dx, _dz, down);
        boolean[] never = wayNever(feet, _dx, _dz, down, wetCell);
        boolean flips = flipGuardOn();
        boolean[] nearRefused = wayNever(feet, _dx, _dz, down, flips ? _flip.refused(now) : null);
        int pick = pickWay(rank, flips ? TunnelFlip.either(never, nearRefused) : never);
        boolean refuses = flips && TunnelFlip.refusesWater(rank, pick);
        if (refuses) _flip.refuse(feet.offset(_dx, down, _dz), now);
        int old = 0;
        for (int i = 0; i < 4; i++) {
            if (rank[i] == WAY_CLOSED) {
                old = i;
                break;
            }
        }
        int against = flips ? 0 : old;
        if (pick != against) {
            adris.altoclef.Debug.logInternal("branch mine: the step " + (down < 0 ? "down" : down > 0 ? "up" : "on") + " from "
                    + feet.toShortString() + " " + TURN_NAMES[pick] + ", " + WAY_NAMES[rank[pick]] + " there, against "
                    + WAY_NAMES[rank[against]] + (never[against] ? " beside the water walked out of"
                    : nearRefused[against] ? " beside the step refused against water" : "")
                    + (refuses ? ", that step refused for a game minute" : "")
                    + " (mine_water_streak" + (flips ? ", tunnel_flip_guard" : "") + ")");
        }
        for (int i = 0; i < pick; i++) {
            int t = _dx;
            _dx = -_dz;
            _dz = t;
        }
    }

    // (mine_water_streak) how a way's step opens
    static final int WAY_CLOSED = 0, WAY_OPEN = 1, WAY_WET = 2;
    private static final String[] WAY_NAMES = {"closed", "open to a cave", "water"};
    private static final String[] TURN_NAMES = {"kept its way", "turned a quarter", "turned half round", "turned three quarters"};

    // the four ways' wayOpening, from (dx, dz) and a quarter turned each time (to the right: east, south, west, north)
    static int[] wayRanks(java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> at, BlockPos feet,
                          int dx, int dz, int down) {
        int[] rank = new int[4];
        for (int turn = 0; turn < 4; turn++) {
            rank[turn] = wayOpening(at, feet, dx, dz, down);
            int t = dx;
            dx = -dz;
            dz = t;
        }
        return rank;
    }

    // the four ways' besideWater in the same order, none when there is no water cell
    static boolean[] wayNever(BlockPos feet, int dx, int dz, int down, int[] wetCell) {
        boolean[] never = new boolean[4];
        for (int turn = 0; turn < 4 && wetCell != null; turn++) {
            never[turn] = besideWater(feet, dx, dz, down, wetCell);
            int t = dx;
            dx = -dz;
            dz = t;
        }
        return never;
    }

    // the way to take: the first of the lowest rank that is not never, else the first of the lowest rank of all; rank and never
    // in the order of the turns from the way it goes, so the answer is the number of quarter turns
    static int pickWay(int[] rank, boolean[] never) {
        int best = -1;
        for (int pass = 0; pass < 2 && best < 0; pass++) {
            for (int i = 0; i < rank.length; i++) {
                if (pass == 0 && never[i]) continue;
                if (best < 0 || rank[i] < rank[best]) best = i;
            }
        }
        return Math.max(best, 0);
    }

    // what baritone digs for a step: its feet, its head, and above that where it steps down, or over its own head where it
    // steps up
    static java.util.List<BlockPos> stepCells(BlockPos feet, int dx, int dz, int down) {
        BlockPos a = feet.offset(dx, down, dz);
        java.util.List<BlockPos> dug = new java.util.ArrayList<>(java.util.List.of(a, a.above()));
        if (down < 0) dug.add(a.above(2));
        if (down > 0) dug.add(feet.above(2));
        return dug;
    }

    // (mine_water_streak) the step's cells to stand in, the step and the one over it, within a block of the water cell (x, y, z)
    // on every axis
    static boolean besideWater(BlockPos feet, int dx, int dz, int down, int[] cell) {
        BlockPos a = feet.offset(dx, down, dz);
        for (BlockPos c : new BlockPos[]{a, a.above()}) {
            if (Math.abs(c.getX() - cell[0]) <= 1 && Math.abs(c.getY() - cell[1]) <= 1 && Math.abs(c.getZ() - cell[2]) <= 1) return true;
        }
        return false;
    }

    // (mine_water_streak) WAY_WET when a fluid lies in a cell the step digs or walks through, on top of one, beside one or under
    // the step; else WAY_OPEN when air lies beside a cell or under the step (or the step's floor is no solid block); else
    // WAY_CLOSED. opens() is the old test, which saw no fluid in the cells dug or on top of them
    static int wayOpening(java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> at, BlockPos feet,
                          int dx, int dz, int down) {
        java.util.List<BlockPos> dug = stepCells(feet, dx, dz, down);
        java.util.Set<BlockPos> ours = new java.util.HashSet<>(dug);
        ours.add(feet);
        ours.add(feet.above());
        ours.add(feet.above(2));
        boolean open = false, wet = false;
        var under = at.apply(dug.get(0).below());
        if (!under.getFluidState().isEmpty()) wet = true;
        else if (bad(under, true)) open = true;
        for (BlockPos d : dug) {
            if (!at.apply(d).getFluidState().isEmpty()) wet = true;
            BlockPos top = d.above();
            if (!ours.contains(top) && !at.apply(top).getFluidState().isEmpty()) wet = true;
            for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos n = d.relative(dir);
                if (ours.contains(n)) continue;
                var st = at.apply(n);
                if (!st.getFluidState().isEmpty()) wet = true;
                else if (st.isAir()) open = true;
            }
        }
        return wet ? WAY_WET : open ? WAY_OPEN : WAY_CLOSED;
    }

    private static boolean opens(AltoClef mod, BlockPos feet, int dx, int dz, int down) {
        var level = mod.getWorld();
        BlockPos a = feet.offset(dx, down, dz);
        java.util.List<BlockPos> dug = stepCells(feet, dx, dz, down);
        java.util.Set<BlockPos> ours = new java.util.HashSet<>(dug);
        ours.add(feet);
        ours.add(feet.above());
        ours.add(feet.above(2));
        if (bad(level.getBlockState(a.below()), true)) return true;
        for (BlockPos d : dug) {
            for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos n = d.relative(dir);
                if (!ours.contains(n) && bad(level.getBlockState(n), false)) return true;
            }
        }
        return false;
    }

    private static boolean bad(net.minecraft.world.level.block.state.BlockState st, boolean under) {
        return st.isAir() || !st.getFluidState().isEmpty() || under && !st.isSolid();
    }

    private void stopMine(AltoClef mod) {
        if (_mining) {
            mod.getClientBaritone().getMineProcess().cancel();
            _mining = false;
        }
        unbandMine();
    }

    // (Tuning mine_band_ores, a fix of 13 Nov 2026, on: while this mine runs, baritone's mine takes only ores within 5 of
    // the mine's height (its minYLevelWhileMining and maxYLevelWhileMining), and the settings go back when
    // it stops. it went to ores it knew above the band, out of it, the mine stopped and the staircase down began again: 87
    // turns of that in the golden helmet's 57 minutes of the goudslijm smoke, about 45 minutes of the run's gold)
    private Integer _bandMinBefore, _bandMaxBefore;

    private void bandMine(AltoClef mod) {
        if (_mineY == null || adris.altoclef.util.helpers.Tuning.get("mine_band_ores", 0) <= 0) return;
        baritone.api.Settings s = baritone.api.BaritoneAPI.getSettings();
        if (_bandMinBefore == null) {
            _bandMinBefore = s.minYLevelWhileMining.value;
            _bandMaxBefore = s.maxYLevelWhileMining.value;
        }
        int minY = mod.getWorld().dimensionType().minY();
        // (5, not the stop band's 8: an ore at the band's lower edge put the feet at 9 under it and stopped the mine all the
        // same; the goudslijm smokes 3 and 4 still restarted the staircase 1.2 times a minute, most of it 9 below)
        s.minYLevelWhileMining.value = Math.max(0, _mineY - 5 - minY);
        s.maxYLevelWhileMining.value = _mineY + 5;
    }

    private void unbandMine() {
        if (_bandMinBefore == null) return;
        baritone.api.Settings s = baritone.api.BaritoneAPI.getSettings();
        s.minYLevelWhileMining.value = _bandMinBefore;
        s.maxYLevelWhileMining.value = _bandMaxBefore;
        _bandMinBefore = null;
        _bandMaxBefore = null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        stopMine(mod);
        // (gold_rock_iron) the mine is over; stopped for an ore it takes (another task) the iron pick holds for 30 s, and the
        // kit's diamond one stays off the rock as long (kit_diamond_off_rock)
        if (interruptTask == null) {
            ToolPick.rockIronOff();
            ToolPick.kitOffRockOff();
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BranchMineTask b && b._y == _y && b._slimeDig == _slimeDig && Arrays.equals(b._blocks, _blocks);
    }

    @Override
    protected String toDebugString() {
        return "Branch mine at y " + _y + " for " + Arrays.toString(_blocks);
    }
}
