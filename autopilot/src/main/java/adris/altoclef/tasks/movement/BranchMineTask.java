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
    private final adris.altoclef.util.helpers.PlaceWatch.Fires _fires = new adris.altoclef.util.helpers.PlaceWatch.Fires(3, 12000, 10);
    // every give-up so far and the last one's line: whoever asked for the mine (the buddy's kit piece) compares the count
    // with the one at its start and fails over to its next piece
    public static volatile int gaveUps;
    public static volatile String gaveUpLine = "";
    // (mine_water_roof) out of water under a roof: the dry cell walked to, since when, the cells not reached in time, and the
    // last dry cell stood on (the way the buddy came)
    private BlockPos _dry, _lastDry;
    private long _drySince;
    private final java.util.Set<BlockPos> _dryTried = new java.util.HashSet<>();
    private static final long DRY_STEP = 400;
    static final int DRY_REACH = 12, DRY_BACK = 48;
    // the cell the walk along (the sculk's, or the 32 blocks of a stuck staircase) came from: never stepped back onto
    private BlockPos _awayPrev;
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

    public BranchMineTask(Block[] blocks, int y) {
        _blocks = blocks;
        _y = y;
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
    }

    @Override
    protected Task onTick(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        int y = mod.getPlayer().getBlockY();
        if (_mineY == null) {
            // (twelve and not forty: with forty a fall into a cave at y -16 had it branch mine for iron down there in the
            // deepslate, without armour, and a zombie killed it, the runner, 2 Nov 2026)
            _mineY = y < _y && y >= _y - 12 ? y : _y;
        }
        BlockPos feet = mod.getPlayer().blockPosition();
        // the cheap pick for the rock into the hotbar, where baritone's tools can see it (it looks at the hotbar only). first of
        // all: the walk away from sculk digs too, and the iron one went on it with a stone one in the bag (attempt 84)
        HotbarTools.forBlocks(mod, Blocks.STONE, Blocks.DEEPSLATE);
        if (_place.tick(feet.getX(), feet.getZ(), now)) {
            stopMine(mod);
            if (adris.altoclef.util.helpers.Tuning.get("mine_water_roof", 0) > 0 && _fires.add(_place.x(), _place.z(), now)) {
                return giveUpHere(mod, now, y);
            }
            int turn = _dx;
            _dx = -_dz;
            _dz = turn;
            _awaySteps = 0;
            _awayStep = null;
            _awayPrev = null;
            _step = null;
            _deepest = Integer.MAX_VALUE;
            _deepestAt = now;
            _highest = Integer.MIN_VALUE;
            _highestAt = now;
            _minePos = null;
            _starts.clear();
            _tunnelUntil = now + 2400;
            _tunnel = null;
            adris.altoclef.Debug.logInternal("branch mine: three game minutes within ten blocks of " + _place.x() + ", " + _place.z()
                    + " at y " + y + ", a tunnel of its own at y " + _mineY + " for two game minutes");
            setDebugState("Three game minutes in one place, a tunnel of its own");
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
        if (!water && mod.getPlayer().onGround()) {
            _lastDry = feet;
            _dry = null;
            _dryTried.clear();
        }
        if (roofed) {
            Task out = outOfRoofedWater(mod, feet, now);
            if (out != null) return out;
        }
        if (now < _tunnelUntil && adris.altoclef.util.helpers.Tuning.get("ice_start_tunnel_hold", 0) > 0) {
            boolean wet = roofed || mod.getPlayer().isInWater() && mod.getWorld().canSeeSky(feet.above());
            if (wet || adris.altoclef.util.helpers.LandFinder.ice(mod.getWorld().getBlockState(feet.below())) || iceColumn(mod, feet)) {
                return holdTunnel(mod, feet, now, wet);
            }
        }
        // in open water no staircase or tunnel starts: dry land first. it swam about the sea in steps of five blocks for
        // fifteen game minutes and never got its shield's iron (the runner, 2 Nov 2026)
        if (mod.getPlayer().isInWater() && mod.getWorld().canSeeSky(feet.above())) {
            stopMine(mod);
            if (_land == null || now - _landSince > 600 || feet.closerThan(_land, 1.5)) {
                _land = nearestLand(mod, feet);
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
        Task pick = cheapPick(mod, now);
        if (pick != null) {
            stopMine(mod);
            return pick;
        }
        if (_dx == 0 && _dz == 0) {
            // the way it looks, to the nearest of the four
            var dir = mod.getPlayer().getDirection();
            _dx = dir.getStepX();
            _dz = dir.getStepZ();
            if (_dx == 0 && _dz == 0) _dx = 1;
        }
        if (now < _tunnelUntil && Math.abs(y - _mineY) <= 8) {
            // straight on at this height, a block at a time; a step not reached in ten game seconds (lava, water, bedrock)
            // turns the tunnel a quarter
            if (_tunnel != null && now - _tunnelSince > 200 && !feet.equals(_tunnel)) {
                int t = _dx;
                _dx = -_dz;
                _dz = t;
                _tunnel = null;
            }
            if (_tunnel == null || feet.equals(_tunnel)) {
                closedWay(mod, feet, 0);
                _tunnel = new BlockPos(feet.getX() + _dx, _mineY, feet.getZ() + _dz);
                _tunnelSince = now;
            }
            setDebugState("A tunnel of its own at y " + _mineY);
            return new GetToBlockTask(_tunnel);
        }
        // in the band: 3 blocks to start the mine, 8 to stop it, so a jump does not stop it
        int dy = y - _mineY;
        if (_mining ? Math.abs(dy) > 8 : Math.abs(dy) > 3) {
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
                return new adris.altoclef.tasks.movement.GetToYTask(_mineY);
            }
            if (_step == null || feet.equals(_step) || feet.getY() < _step.getY()) {
                closedWay(mod, feet, -1);
                _step = feet.offset(_dx, -1, _dz);
                _stepSince = tick;
            }
            setDebugState("A staircase down to y " + _mineY + " to branch mine");
            return new GetToBlockTask(_step);
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
            mine.mine(0, _blocks);
            _mining = true;
        }
        setDebugState("Branch mining at y " + _mineY);
        return null;
    }

    // (mine_water_roof) the place watch fired three times in ten game minutes here: the depth is given up at this spot. the
    // count tells the caller (the buddy's piece fails over to its next one); left to itself the mine walks 32 blocks on, a
    // quarter turned, and starts afresh there
    private Task giveUpHere(AltoClef mod, long now, int y) {
        String line = "branch mine: the place watch fired " + _fires.last() + " times at " + _place.x() + ", " + _place.z()
                + ", giving up y " + _mineY + " here";
        adris.altoclef.Debug.logInternal(line + " (at y " + y + ", mine_water_roof)");
        gaveUpLine = line;
        gaveUps++;
        _fires.clear();
        _tunnelUntil = now;
        _tunnel = null;
        _step = null;
        _starts.clear();
        _minePos = null;
        int t = _dx;
        _dx = -_dz;
        _dz = t;
        if (_dx == 0 && _dz == 0) _dx = 1;
        _awaySteps = 32;
        _awayWhy = "Gave up y " + _mineY + " here: along";
        _awayStep = null;
        _awayPrev = null;
        setDebugState("The place watch fired three times here, giving up y " + _mineY + " here");
        return null;
    }

    // (mine_water_roof) in water under a roof: the walk to the nearest dry cell to stand in within DRY_REACH, else back to the
    // last dry cell stood on within DRY_BACK; a cell not reached in 20 game seconds is passed over for another. null when there
    // is none, and the checks after this have the tick
    private Task outOfRoofedWater(AltoClef mod, BlockPos feet, long now) {
        if (_dry != null && now - _drySince > DRY_STEP && !feet.equals(_dry)) {
            _dryTried.add(_dry);
            _dry = null;
        }
        if (_dry == null || feet.equals(_dry)) {
            _dry = dryCell(mod, feet, _dryTried);
            if (_dry == null && _lastDry != null && !_dryTried.contains(_lastDry) && _lastDry.closerThan(feet, DRY_BACK)) _dry = _lastDry;
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
        if (now < _cheapAfter || !needsBetterThanStone(_blocks) || cheapPicks(mod) > 0) {
            return null;
        }
        int stone = mod.getItemStorage().getItemCount(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE);
        int planks = mod.getItemStorage().getItemCount(ItemHelper.PLANKS);
        boolean table = mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) > 0;
        // a table of four planks first when there is none in the bag: the kit's was put down for the armour on the surface and
        // left there, and without one this never started (the runner, 2 Nov 2026, attempt 82)
        int sticks = mod.getItemStorage().getItemCount(Items.STICK) + 4 * (Math.max(0, planks - (table ? 0 : 4)) / 2);
        int n = Math.min(2, Math.min(stone / 3, sticks / 2));
        if (n < 1 || !table && planks < 4) {
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
        setDebugState("A stone pickaxe for the rock, the iron one is for the ore");
        return _cheapCraft;
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

    private static BlockPos nearestLand(AltoClef mod, BlockPos feet) {
        return adris.altoclef.util.helpers.LandFinder.nearestLand(mod, feet, 64);
    }

    // the next step of a staircase (down one) or of a tunnel (level) in the way it goes, or the first of the other three
    // ways that does not open into a cave, a hole or lava. a staircase that broke into a cave let a skeleton shoot it, and a
    // ravine's edge let an arrow push it twenty blocks down (the runner, 1 Nov 2026, its first iron without armour). when
    // all four open into one, it keeps its way
    private void closedWay(AltoClef mod, BlockPos feet, int down) {
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

    private static boolean opens(AltoClef mod, BlockPos feet, int dx, int dz, int down) {
        var level = mod.getWorld();
        BlockPos a = feet.offset(dx, down, dz);
        // what baritone digs for the step: its feet, its head, and above the head where it steps down
        java.util.List<BlockPos> dug = new java.util.ArrayList<>(java.util.List.of(a, a.above()));
        if (down < 0) dug.add(a.above(2));
        if (down > 0) dug.add(feet.above(2));
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
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BranchMineTask b && b._y == _y && Arrays.equals(b._blocks, _blocks);
    }

    @Override
    protected String toDebugString() {
        return "Branch mine at y " + _y + " for " + Arrays.toString(_blocks);
    }
}
