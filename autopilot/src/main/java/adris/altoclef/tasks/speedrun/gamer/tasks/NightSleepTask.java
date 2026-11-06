package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.GameOverlayEvent;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.speedrun.gamer.NightSleep;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.BedSource;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.Refusal;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.Stage;
import adris.altoclef.tasks.speedrun.gamer.Respawn;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

// one night, start to finish: get a bed, get it out of the monsters' reach when it has to be, sleep, take it back, come down.
// the rules (what counts as clear, how high, how long, what mob defense does in which stage) are NightSleep, this is the part
// that walks and clicks. GamerTask owns the budget and decides when to start one. a trip that finds one of our own beds still
// standing (state.nightBed) only takes that back and ends, the next trip is a fresh decision
public class NightSleepTask extends Task {
    public enum Outcome {
        // NIGHT_OVER: the morning got there before the bed did. nothing failed, there is just nothing left to sleep through
        SLEPT, GAVE_UP, NIGHT_OVER
    }

    private static final int INF = 1_000_000;
    // a bed we keep clicking that does not put us to sleep in this long said no in a way we did not hear
    private static final int CLICK_PATIENCE_TICKS = 100;
    private static final double TICK_SECONDS = 0.05;
    // how long the wool and the planks have to look gone, with nothing lying about to put back, before the craft is given up on
    private static final int SETTLE_TICKS = 10;
    // under the interact's 5 s click timer and 6 s no-progress check, both of which end in a wander
    private static final int PLACE_PATIENCE_TICKS = 60;
    static final String BED_WOULD_NOT_GO_DOWN = "the bed would not go down";
    // a click is answered within a few ticks. a bed that left the bag and shows up nowhere near the spot after this long is not
    // coming, and crafting a new one mid night is not on
    private static final int BED_SHOW_UP_TICKS = 20;
    static final String LOST_THE_BED = "the bed went missing after the click";
    // (the bag said bed when the trip started and says none now, without a click from us)
    static final String NO_BED_IN_BAG = "no bed in the bag";

    private final RunState state;
    private final Supplier<OverworldConfig> cfg;
    private final NightSleep.Budget budget;
    private final IntSupplier buildBlocks;
    private final Consumer<String> say;
    private final Runnable save;
    private final BedSource source;
    private final BlockPos found;
    // an old bed of ours is standing: this trip only takes it back
    private final boolean cleanup;
    // the beds the End wants in the bag, a night bed only stays up as the spawn bed when the bag has them without it
    private final int endBeds;

    private Stage stage;
    private long stageSince;
    private boolean begun;
    private boolean finished;
    private Outcome outcome = Outcome.GAVE_UP;
    private String why = "";

    // the cell the pillar began in (the way down ends there), and the top the last lift step aimed for
    private BlockPos ground;
    private BlockPos lastTop;
    private int lifts;
    private int closers;

    // the bed this try: the foot block is the one we write down as ours, the facing is the way the head points from it
    private Direction facing;
    private BlockPos foot;
    // the cell the bed is built beside, kept from the tick the direction was picked
    private BlockPos standCell;
    // the bed click for this try, kept so the task system sees the same task every tick (see placeTask)
    private Task placeBed;
    private BlockPos placeFor;
    private long placeSince;
    // where a bed from the click can land (the foot cell and its four neighbours) and which of those held a bed before it. a
    // new bed there is ours, an old one never is: a village bed is not ours to pick up
    private List<BlockPos> clickCells;
    private Set<BlockPos> bedsBefore;
    private int bagBeforeClick;
    private long bedLeftBagAt = -1;
    // the day count the trip began on, so a "not at night" still reads as the morning when the sky has not caught up yet
    private long nightAtStart;
    // holding back + sneak to get a toe out of the foot cell
    private boolean stepping;
    private long retrievalSince;
    // set while we are getting out of the bed, the try ends when the game says we are up
    private String leaving;
    private long clickedAt = -1;
    private boolean wasAsleep;
    private long asleepTicks;
    // the day time when we lay down, and the tick the sleeping flag first read clear (for the wake grace)
    private long asleepDayTime;
    private long upSince = -1;
    // where PICKUP goes next when the refusal said to go higher
    private boolean liftAfterPickup;
    private int bedsBeforePickup;
    private boolean pickupArmed;
    private final BedCraft bedCraft = new BedCraft();
    private long shortSince = -1;

    private AltoClef mod;
    private volatile String overlay;
    private Subscription<GameOverlayEvent> overlayWatch;

    public NightSleepTask(RunState state, Supplier<OverworldConfig> cfg, NightSleep.Budget budget, IntSupplier buildBlocks,
                          Consumer<String> say, Runnable save, BedSource source, BlockPos found, int endBeds) {
        this.state = state;
        this.cfg = cfg;
        this.budget = budget;
        this.buildBlocks = buildBlocks;
        this.say = say;
        this.save = save;
        this.source = source;
        this.found = found;
        this.cleanup = state.nightBed != null;
        this.endBeds = endBeds;
    }

    // null once the trip is over. GamerTask hands this to mob defense every tick
    public Stage stage() {
        return finished ? null : stage;
    }

    public Outcome outcome() {
        return outcome;
    }

    public boolean cleanupOnly() {
        return cleanup;
    }

    // ---- plumbing

    @Override
    protected void onStart(AltoClef mod) {
        overlay = null;
        overlayWatch = EventBus.subscribe(GameOverlayEvent.class, evt -> overlay = evt.message);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (overlayWatch != null) {
            EventBus.unsubscribe(overlayWatch);
            overlayWatch = null;
        }
        // (the keys themselves go with the task's own claims when it stops)
        stepping = false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return finished;
    }

    @Override
    protected String toDebugString() {
        return "Sleeping through the night (" + stage + ")";
    }

    @Override
    protected String toHudString() {
        return "Sleeping through the night";
    }

    private void enter(Stage next, long now, String line) {
        if (next == Stage.PICKUP || next == Stage.RETURN) {
            if (stage != Stage.PICKUP && stage != Stage.RETURN) {
                retrievalSince = now;
            }
        }
        stage = next;
        stageSince = now;
        if (next != Stage.BUILD) {
            // the snapshot is the last click's, a later BUILD takes its own
            clickCells = null;
            bedLeftBagAt = -1;
        }
        if (next == Stage.SLEEP) {
            // a line from before the click is not the bed's answer
            overlay = null;
        }
        Debug.logInternal("night: " + next + (line.isEmpty() ? "" : ", " + line));
    }

    private double secondsIn(long now) {
        return (now - stageSince) * TICK_SECONDS;
    }

    // getting the bed back is PICKUP and RETURN trading places, one clock for both or each bounce would start it over
    private double retrievalSeconds(long now) {
        return (now - retrievalSince) * TICK_SECONDS;
    }

    // ---- the tick

    @Override
    protected Task onTick(AltoClef mod) {
        this.mod = mod;
        if (finished) {
            return null;
        }
        if (stage != Stage.BUILD) {
            // BUILD can end mid step back, the next stage should not walk backwards with it
            letGoOfStep(mod);
        }
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        // (once we slept or the morning came, the pickup and the way down are not tonight's, the budget has rolled on to the next
        // night)
        if (!cleanup && outcome == Outcome.GAVE_UP) {
            budget.spend(TICK_SECONDS);
        }
        if (!begun) {
            begun = true;
            begin(mod, now);
            if (finished || stage == null) {
                return null;
            }
        }
        if (NightSleep.shouldAbandon(player.getHealth())) {
            // normal defense has the wheel from this tick on. a bed left standing is taken back on a later trip
            if (player.isSleeping()) {
                getUp(player);
            }
            fail("low health", now);
            finish(now);
            return null;
        }
        catchUpWithSleep(mod, now);
        if (!cleanup && budget.seconds() >= cfg.get().nightSeconds
                && stage != Stage.PICKUP && stage != Stage.RETURN && stage != Stage.DESCEND && stage != Stage.SLEEP) {
            fail("out of time for tonight", now);
            return null;
        }
        return switch (stage) {
            case CRAFT -> craft(mod, now);
            case WALK -> walk(mod, now);
            case LIFT -> lift(mod, now);
            case BUILD -> build(mod, now);
            case SLEEP -> sleep(mod, now);
            case PICKUP -> pickup(mod, now);
            case RETURN -> returnToBed(mod, now);
            case DESCEND -> descend(mod, now);
        };
    }

    // only SLEEP looks at the bed flag, so a stray click that put us in bed mid build went unseen and the night skip with it.
    // the bed we are in is tonight's: SLEEP takes it from here and credits the night when we get up
    private void catchUpWithSleep(AltoClef mod, long now) {
        LocalPlayer player = mod.getPlayer();
        if (NightSleep.stageWhenAsleep(inBed(player), stage) == stage) {
            return;
        }
        // a bed of ours that came down on the way is written down first, so the morning picks up the right one
        BlockPos placed = placedBed(mod);
        if (placed != null) {
            claim(placed);
        }
        ClientLevel world = mod.getWorld();
        BlockPos slept = player.getSleepingPos().map(p -> footOf(p, world.getBlockState(p))).orElse(null);
        if (slept != null) {
            foot = slept;
        } else if (placed != null) {
            foot = placed;
        }
        clickedAt = -1;
        enter(Stage.SLEEP, now, "already in bed");
    }

    private static boolean inBed(LocalPlayer player) {
        return player.isSleeping() || Minecraft.getInstance().gui.screen() instanceof InBedChatScreen;
    }

    private void begin(AltoClef mod, long now) {
        stageSince = now;
        nightAtStart = NightSleep.nightOf(mod.getWorld().getOverworldClockTime());
        // (a container screen that sat open past the patience of the busy gate is stuck, and a bed click or a block break behind it
        // goes nowhere)
        StorageHelper.closeScreen();
        if (cleanup) {
            if (Respawn.adoptNightBed(state, bedsInBag(mod), endBeds)) {
                // the old bed is where we respawn and the stronghold is next: nothing to take back
                save.run();
                finish(now);
                return;
            }
            enter(Stage.PICKUP, now, "an old bed of ours is still standing");
            return;
        }
        say.accept("Night is here, going to bed");
        switch (source) {
            case FOUND -> enter(Stage.WALK, now, "a bed at " + found.toShortString());
            case BAG -> afterBed(mod, now);
            case CRAFT -> enter(Stage.CRAFT, now, "making one");
            default -> {
                fail("no bed", now);
            }
        }
    }

    // ---- getting the bed

    private int bedsInBag(AltoClef mod) {
        return mod.getItemStorage().getItemCount(ItemHelper.BED);
    }

    private Task craft(AltoClef mod, long now) {
        if (bedsInBag(mod) > 0) {
            afterBed(mod, now);
            return null;
        }
        if (NightSleep.timedOut(Stage.CRAFT, 0, secondsIn(now))) {
            fail("the bed took too long to craft", now);
            return null;
        }
        // only what is in the bag: the catalogue's bed task mines the nearest bed it knows of and hunts sheep for missing wool.
        // logs become planks first (the same as the evening prep does), the bed craft itself will not
        Task planks = bedCraft.planksFirst(mod);
        if (planks != null) {
            setDebugState("Making planks for the bed.");
            return planks;
        }
        Task make = bedCraft.next(mod);
        if (make == null) {
            // a craft that got interrupted (ours or the phase's) can have left the wool or the planks in a grid or on the cursor,
            // where they do not count. put them back and look again before calling them gone
            if (shortSince < 0) {
                shortSince = now;
            }
            Task stray = BedCraft.stray(mod);
            if (stray != null) {
                setDebugState("Putting the crafting leftovers back.");
                return stray;
            }
            if (now - shortSince > SETTLE_TICKS) {
                fail("the wool or the planks are gone", now);
            }
            return null;
        }
        shortSince = -1;
        setDebugState("Crafting a bed.");
        return make;
    }

    private Task walk(AltoClef mod, long now) {
        BlockPos bed = found;
        if (!(mod.getWorld().getBlockState(bed).getBlock() instanceof BedBlock)) {
            fail("the bed we were walking to is gone", now);
            return null;
        }
        if (NightSleep.timedOut(Stage.WALK, 0, secondsIn(now))) {
            fail("could not get to the bed", now);
            return null;
        }
        foot = WorldHelper.getBedFoot(mod, bed);
        BlockPos head = WorldHelper.getBedHead(mod, bed);
        if (monstersBlock(mod, head)) {
            // a bed that is already standing cannot be lifted, ours can: this one is not worth it
            fail("monsters by the bed", now);
            return null;
        }
        if (bed.closerToCenterThan(mod.getPlayer().position(), 2.5)) {
            enter(Stage.SLEEP, now, "at the bed");
            return null;
        }
        setDebugState("Walking to the bed.");
        return new GetToBlockTask(bed);
    }

    // a bed in hand: pillar first if something is in the way, otherwise put it down here
    private void afterBed(AltoClef mod, long now) {
        BlockPos feet = mod.getPlayer().blockPosition();
        if (ground == null) {
            ground = feet;
        }
        int raise = raiseAt(mod, feet);
        if (raise < 0) {
            fail("the monsters are too high to get above", now);
        } else if (raise > 0) {
            if (roomToRaise(mod, feet, raise, now)) {
                lastTop = null;
                enter(Stage.LIFT, now, "need " + raise + " blocks up");
            }
        } else {
            facing = null;
            enter(Stage.BUILD, now, "clear where we stand");
        }
    }

    // ---- monsters

    private static List<NightSleep.Mob> mobsAround(AltoClef mod, BlockPos at) {
        double r = NightSleep.COMING_RANGE;
        AABB box = new AABB(at.getX() + 0.5 - r, at.getY() - 24, at.getZ() + 0.5 - r, at.getX() + 0.5 + r, at.getY() + 24, at.getZ() + 0.5 + r);
        // 1.21.2 made isPreventingPlayerRest want a ServerLevel we do not have. every monster says true except zombified piglins,
        // who want to be angry at us
        List<Monster> near = mod.getWorld().getEntitiesOfClass(Monster.class, box,
                m -> m.isAlive() && (!(m instanceof ZombifiedPiglin) || EntityHelper.isAngryAtPlayer(mod, m)));
        List<NightSleep.Mob> out = new ArrayList<>(near.size());
        for (Monster m : near) {
            AABB b = m.getBoundingBox();
            out.add(new NightSleep.Mob(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
        }
        return out;
    }

    // the game's own check at the click, for a head block that is already there
    public static boolean monstersBlock(AltoClef mod, BlockPos head) {
        return NightSleep.blocked(head.getX(), head.getY(), head.getZ(), mobsAround(mod, head));
    }

    // blocks to go up from the cell we stand in, for a bed whose head will end up two cells off at this height
    private int raiseAt(AltoClef mod, BlockPos feet) {
        OverworldConfig c = cfg.get();
        return NightSleep.blocksToRaise(feet.getX(), feet.getY(), feet.getZ(), mobsAround(mod, feet), c.nightClearMargin, c.nightMaxPillar);
    }

    // the column has to be open, dry and we need the blocks. a no is a failed try
    private boolean roomToRaise(AltoClef mod, BlockPos feet, int raise, long now) {
        ClientLevel world = mod.getWorld();
        if (mod.getPlayer().isInWater() || mod.getPlayer().isInLava() || !world.getFluidState(feet.below()).isEmpty()) {
            fail("standing in liquid", now);
            return false;
        }
        int open = 0;
        for (int i = 1; i <= raise + 2; i++) {
            BlockPos up = feet.above(i);
            BlockState s = world.getBlockState(up);
            // (a portal has no collision, it still takes us to the nether the moment we pillar into it)
            if (!s.getCollisionShape(world, up).isEmpty() || !s.getFluidState().isEmpty() || s.is(Blocks.NETHER_PORTAL)) {
                break;
            }
            open++;
        }
        if (NightSleep.roofAt(raise, open)) {
            fail("a roof is in the way of the pillar", now);
            return false;
        }
        int have = buildBlocks.getAsInt();
        int need = NightSleep.blocksNeeded(raise);
        if (have < need) {
            fail("not enough blocks for the pillar (" + have + " of " + need + ")", now);
            return false;
        }
        return true;
    }

    // ---- the pillar

    private Task lift(AltoClef mod, long now) {
        LocalPlayer player = mod.getPlayer();
        ClientLevel world = mod.getWorld();
        BlockPos feet = player.blockPosition();
        int raise = raiseAt(mod, feet);
        if (raise < 0) {
            fail("the monsters are too high to get above", now);
            return null;
        }
        boolean standing = player.onGround() && world.getBlockState(feet.below()).isFaceSturdy(world, feet.below(), Direction.UP);
        if (raise == 0 && standing) {
            facing = null;
            enter(Stage.BUILD, now, "high enough at y=" + feet.getY());
            return null;
        }
        int planned = Math.max(1, (lastTop == null ? feet.above(Math.max(raise, 1)) : lastTop).getY() - (ground == null ? feet.getY() : ground.getY()));
        if (NightSleep.timedOut(Stage.LIFT, planned, secondsIn(now))) {
            fail("the pillar went nowhere", now);
            return null;
        }
        if (buildBlocks.getAsInt() < 1) {
            fail("out of blocks on the way up", now);
            return null;
        }
        // mid jump the count can read zero for a tick before the block is down, and a monster hopping about moves the line by one
        // now and then: the target only ever goes up, a new one is a new goal for baritone
        BlockPos top = feet.above(Math.max(raise, 1));
        if (lastTop != null && lastTop.getY() >= top.getY()) {
            top = lastTop;
        }
        lastTop = top;
        setDebugState("Pillaring up to get away from the monsters.", "Getting above the monsters");
        return new PillarHereTask(top);
    }

    // ---- the bed

    // the step back keys stay down only for as long as each tick says step back, whatever else build hands out next
    private Task build(AltoClef mod, long now) {
        boolean wasStepping = stepping;
        stepping = false;
        Task next = putBedDown(mod, now);
        if (next != placeBed) {
            // anything but the click this tick (a walk, a clear, a step back) and the next click starts from scratch,
            // or its patience clock would count the walk too
            placeBed = null;
        }
        if (wasStepping && !stepping) {
            mod.getInputControls().releaseOwnedBy(this);
        }
        return next;
    }

    private Task putBedDown(AltoClef mod, long now) {
        LocalPlayer player = mod.getPlayer();
        ClientLevel world = mod.getWorld();
        // the cell the bed is built beside stays the one it was chosen from: placing the supports walks us about, and a foot cell
        // that follows us around never gets a bed
        BlockPos stand = facing != null && standCell != null ? standCell : player.blockPosition();
        if (NightSleep.timedOut(Stage.BUILD, 0, secondsIn(now))) {
            fail(BED_WOULD_NOT_GO_DOWN, now);
            return null;
        }
        if (settlingClick(mod, now)) {
            return null;
        }
        // monsters came while we were building and we have not been up yet: start over from here, higher
        if (foot == null || !(world.getBlockState(foot).getBlock() instanceof BedBlock)) {
            int raise = raiseAt(mod, stand);
            if (raise > 0 && lifts < 1) {
                lifts++;
                if (roomToRaise(mod, stand, raise, now)) {
                    lastTop = null;
                    enter(Stage.LIFT, now, "monsters showed up, need " + raise + " blocks");
                }
                return null;
            }
        }
        if (facing == null) {
            facing = bestFacing(mod, stand);
            if (facing == null) {
                fail("no room for a bed here", now);
                return null;
            }
            standCell = stand;
            placeBed = null;
        }
        BlockPos footCell = stand.relative(facing);
        BlockPos headCell = footCell.relative(facing);
        for (BlockPos cell : new BlockPos[]{footCell, headCell, footCell.above(), headCell.above()}) {
            if (world.getBlockState(cell).getBlock() instanceof BedBlock) {
                // somebody's bed (ours turned up in settlingClick already): not ours to break, pick another side
                facing = null;
                return null;
            }
            if (inTheWay(world.getBlockState(cell), world, cell)) {
                setDebugState("Clearing room for the bed.");
                return new DestroyBlockTask(cell);
            }
        }
        // the click's ray starts at our eye, so a tuft in the cell we stand in (or tall grass up at eye height) eats it the same
        // way. only the no-collision kind: a slab or a carpet in there is what we are standing on
        for (BlockPos cell : new BlockPos[]{stand, stand.above()}) {
            if (plantInTheWay(world.getBlockState(cell), world, cell)) {
                setDebugState("Clearing the plants where we stand.");
                return new DestroyBlockTask(cell);
            }
        }
        // the game has no support rule for a bed, but the foot needs something to be clicked onto and the item of a bed taken
        // down later drops at the head: over nothing it falls off the pillar
        for (BlockPos support : new BlockPos[]{footCell.below(), headCell.below()}) {
            if (!world.getBlockState(support).isFaceSturdy(world, support, Direction.UP)) {
                if (buildBlocks.getAsInt() < 1) {
                    fail("no blocks left to hold the bed up", now);
                    return null;
                }
                setDebugState("Putting something under the bed.");
                return new PlaceBlockTask(support, new Block[]{}, true, false);
            }
        }
        BlockPos support = footCell.below();
        // the head goes the way we look at the click, so the click is made from the stand cell and nowhere else. from inside it
        // the support's top is always within 45 degrees of facing (the closest it gets is half a block over, half a block on)
        Spot spot = spot(player.blockPosition().equals(stand), player.getBoundingBox().intersects(new AABB(footCell).deflate(1.0E-3)),
                towards(support.getX() + 0.5 - player.getX(), support.getZ() + 0.5 - player.getZ()) == facing);
        if (spot == Spot.WALK) {
            setDebugState("Getting back to where the bed is built from.");
            return new GetToBlockTask(stand);
        }
        if (spot == Spot.STEP_BACK) {
            // a toe in the foot cell and the game says the bed is obstructed (by us). back off a hair, sneaking so a pillar top
            // does not turn into a fall
            mod.getInputControls().forceLook(facing.toYRot(), player.getXRot());
            mod.getInputControls().hold(Input.SNEAK);
            mod.getInputControls().hold(Input.MOVE_BACK);
            stepping = true;
            setDebugState("Stepping off the bed spot.");
            return null;
        }
        if (LookHelper.getReach(support, Direction.UP).isEmpty()) {
            // in the right cell and still no line to the support's top. the interact would give up after a few seconds and
            // wander off five blocks, which on a pillar top is a fall, and mark the support unreachable for the next try too.
            // standing here until the build clock runs out is the lesser evil
            setDebugState("Can't see the top of the bed spot.");
            return null;
        }
        setDebugState("Putting the bed down.", "Putting the bed down");
        return placeTask(stand, support, now);
    }

    private void letGoOfStep(AltoClef mod) {
        if (stepping) {
            stepping = false;
            mod.getInputControls().releaseOwnedBy(this);
        }
    }

    // one task for the whole try: isEqual compares the goal lambda by identity, so a new lambda every tick is a new task every
    // tick and its onStart cancels the path every time (same reason DoStuffInContainerTask keeps a static)
    private Task placeTask(BlockPos stand, BlockPos support, long now) {
        if (placeBed != null && now - placeSince > PLACE_PATIENCE_TICKS) {
            // clicks that keep not making a bed (a mob over the spot, a refused click): the interact's own patience ends in a
            // five block wander, off a pillar that is a fall. a fresh one starts its clocks over, the build clock still caps it
            placeBed = null;
        }
        if (placeBed == null || !support.equals(placeFor)) {
            placeSince = now;
            snapshotBeds(support.above());
            // when the support looks out of reach the walk is back to the stand cell, not the goal next to the support's top
            // that nobody can stand in
            placeBed = new InteractWithBlockTask(new ItemTarget("bed", 1), Direction.UP, support, false).walkTo(p -> new GoalBlock(stand));
            placeFor = support;
        }
        return placeBed;
    }

    // ---- after the click

    private void snapshotBeds(BlockPos footCell) {
        ClientLevel world = mod.getWorld();
        clickCells = List.of(footCell, footCell.north(), footCell.south(), footCell.east(), footCell.west());
        bedsBefore = new HashSet<>();
        for (BlockPos cell : clickCells) {
            if (world.getBlockState(cell).getBlock() instanceof BedBlock) {
                bedsBefore.add(cell);
            }
        }
        bagBeforeClick = bedsInBag(mod);
        bedLeftBagAt = -1;
    }

    // the bed can land a cell off from the one we aimed at (the ray ends where it ends), so the foot cell alone misses it, and
    // walking back to click again is how one night ends up with two beds or none. so after a click: a new bed near the spot is
    // ours, a bed gone from the bag gets a few ticks to show up with nothing else going on, and an empty bag with no bed in
    // sight ends the try. the place interact only ever runs with a bed in the bag (it would hand the missing bed to the
    // catalogue, which crafts one and mines the nearest bed it knows of, ours included). true when this tick is spoken for
    private boolean settlingClick(AltoClef mod, long now) {
        BlockPos placed = placedBed(mod);
        if (placed != null) {
            bedDown(placed, now);
            return true;
        }
        int bag = bedsInBag(mod);
        if (!bedLeftBag(bag)) {
            bedLeftBagAt = -1;
            return false;
        }
        if (bedLeftBagAt < 0) {
            bedLeftBagAt = now;
        }
        if (now - bedLeftBagAt <= BED_SHOW_UP_TICKS) {
            setDebugState("Waiting for the bed to show up.");
            return true;
        }
        if (bag == 0) {
            fail(clickCells == null ? NO_BED_IN_BAG : LOST_THE_BED, now);
            return true;
        }
        // a second bed in the bag: the first went somewhere we do not look, the next click takes a fresh snapshot
        clickCells = null;
        bedLeftBagAt = -1;
        return false;
    }

    private boolean bedLeftBag(int bag) {
        return bag == 0 || (clickCells != null && bag < bagBeforeClick);
    }

    // the foot of a bed that showed up near the spot since the snapshot, null when none did (or no click was made yet)
    private BlockPos placedBed(AltoClef mod) {
        if (clickCells == null) {
            return null;
        }
        return appearedFoot(clickCells, bedsBefore, mod.getWorld()::getBlockState);
    }

    private void bedDown(BlockPos at, long now) {
        foot = at;
        claim(at);
        clickedAt = -1;
        enter(Stage.SLEEP, now, "bed is down at " + at.toShortString());
    }

    private void claim(BlockPos at) {
        state.nightBed = new RunState.Pos(at.getX(), at.getY(), at.getZ());
        save.run();
    }

    // the first cell holding a bed that was not there before, walked back to that bed's foot
    public static BlockPos appearedFoot(List<BlockPos> cells, Set<BlockPos> before, Function<BlockPos, BlockState> at) {
        for (BlockPos cell : cells) {
            if (!before.contains(cell)) {
                BlockPos bedFoot = footOf(cell, at.apply(cell));
                if (bedFoot != null) {
                    return bedFoot;
                }
            }
        }
        return null;
    }

    // either half of a bed to its foot, the way the bed's own facing says. null for anything that is not a bed
    public static BlockPos footOf(BlockPos at, BlockState s) {
        if (!(s.getBlock() instanceof BedBlock)) {
            return null;
        }
        return s.getValue(BedBlock.PART) == BedPart.FOOT ? at : at.relative(s.getValue(BedBlock.FACING).getOpposite());
    }

    public enum Spot {
        WALK, STEP_BACK, CLICK
    }

    // where we are against the stand cell: somewhere else is a walk, in it but with a bit of us over the foot cell (or looking
    // off to the side, which from inside the cell does not happen) is a step back, otherwise click
    public static Spot spot(boolean inStand, boolean overFoot, boolean aimsAlongFacing) {
        if (!inStand) {
            return Spot.WALK;
        }
        return overFoot || !aimsAlongFacing ? Spot.STEP_BACK : Spot.CLICK;
    }

    // the horizontal direction a look along (dx, dz) counts as, the same 45 degree buckets the game puts the bed's head in
    public static Direction towards(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // the outline, not the collision. grass and flowers have no collision but the click's ray stops on their outline, so a
    // tuft in the foot cell means the support top is "out of reach" forever. liquid is left alone: the ray goes through it
    // and a bed takes a water cell just fine (siteCost already says no to a wet site)
    public static boolean inTheWay(BlockState s, BlockGetter world, BlockPos cell) {
        if (s.getBlock() instanceof LiquidBlock) {
            return false;
        }
        return !s.getShape(world, cell).isEmpty();
    }

    // inTheWay, but nothing we could be standing on or in: grass, flowers, cobwebs, one snow layer
    public static boolean plantInTheWay(BlockState s, BlockGetter world, BlockPos cell) {
        return inTheWay(s, world, cell) && s.getCollisionShape(world, cell).isEmpty();
    }

    // the direction with the least work: cells to clear, a support to place. null when none can be done at all
    private Direction bestFacing(AltoClef mod, BlockPos stand) {
        Direction best = null;
        int bestCost = INF;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int cost = siteCost(mod, stand, d);
            if (cost < bestCost) {
                bestCost = cost;
                best = d;
            }
        }
        return best;
    }

    // a bed site next to a portal, or made of its frame, is no site: the portal teleports us mid build, and obsidian is not ours to
    // dig up for a bed (a diamond pick makes it "breakable"). the six cells the bed uses may not be portal or obsidian, and none
    // of them may touch a portal
    public static boolean portalSite(BlockPos foot, BlockPos head, Function<BlockPos, Block> at) {
        BlockPos[] cells = {foot, head, foot.above(), head.above(), foot.below(), head.below()};
        for (BlockPos cell : cells) {
            Block b = at.apply(cell);
            if (b == Blocks.NETHER_PORTAL || b == Blocks.OBSIDIAN) {
                return true;
            }
            for (Direction d : Direction.values()) {
                if (at.apply(cell.relative(d)) == Blocks.NETHER_PORTAL) {
                    return true;
                }
            }
        }
        return false;
    }

    // the foot, the head and the air over both have to be free (the game refuses an obstructed bed), liquid is a no
    private int siteCost(AltoClef mod, BlockPos stand, Direction d) {
        ClientLevel world = mod.getWorld();
        BlockPos footCell = stand.relative(d);
        BlockPos headCell = footCell.relative(d);
        if (portalSite(footCell, headCell, c -> world.getBlockState(c).getBlock())) {
            return INF;
        }
        int cost = 0;
        for (BlockPos cell : new BlockPos[]{footCell, headCell, footCell.above(), headCell.above()}) {
            BlockState s = world.getBlockState(cell);
            // (a bed there is somebody's, it does not get broken to make room for ours)
            if (!s.getFluidState().isEmpty() || s.getBlock() instanceof BedBlock) {
                return INF;
            }
            if (inTheWay(s, world, cell)) {
                if (!WorldHelper.canBreak(mod, cell) || WorldHelper.tooSlowToBreak(mod, cell)) {
                    return INF;
                }
                cost += 2;
            }
        }
        int missing = 0;
        for (BlockPos support : new BlockPos[]{footCell.below(), headCell.below()}) {
            if (!world.getBlockState(support).isFaceSturdy(world, support, Direction.UP)) {
                missing++;
            }
        }
        if (missing > buildBlocks.getAsInt()) {
            return INF;
        }
        return cost + 3 * missing;
    }

    // ---- sleeping

    private Task sleep(AltoClef mod, long now) {
        LocalPlayer player = mod.getPlayer();
        ClientLevel world = mod.getWorld();
        if (inBed(player)) {
            if (!wasAsleep) {
                asleepDayTime = world.getOverworldClockTime();
            }
            wasAsleep = true;
            asleepTicks++;
            upSince = -1;
            setDebugState("Sleeping.", "Sleeping");
            if (leaving == null && asleepTicks > cfg.get().nightSleepSeconds * 20) {
                // the night is not passing (somebody else is awake), no point lying here. the try ends once we are out of it
                leaving = "the night did not pass";
                getUp(player);
            } else if (leaving != null && asleepTicks % 40 == 0) {
                // the first ask can get lost
                getUp(player);
            }
            return null;
        }
        if (wasAsleep) {
            // woken: by the morning, or by something that hit us
            if (upSince < 0) {
                upSince = now;
            }
            NightSleep.Wake wake = NightSleep.wake(asleepTicks, world.isBrightOutside(), asleepDayTime, world.getOverworldClockTime(), now - upSince,
                    cfg.get().nightWakeGraceTicks);
            if (wake == NightSleep.Wake.WAIT) {
                // (still reads night, the time packet may be on its way)
                return null;
            }
            if (wake == NightSleep.Wake.SLEPT) {
                // the night we lay down in: the skip has already rolled the day count, and the budget with it
                budget.sleptOk(NightSleep.nightOf(asleepDayTime));
                outcome = Outcome.SLEPT;
                say.accept("Slept through the night");
                wrapUp(mod, now);
            } else {
                // (if we got up ourselves because the night would not pass, that is the reason, the morning getting there first
                // was the SLEPT case above)
                fail(leaving != null ? leaving : "woken before the night passed", now);
            }
            return null;
        }
        if (foot == null || !(world.getBlockState(foot).getBlock() instanceof BedBlock)) {
            fail("the bed is gone", now);
            return null;
        }
        BlockPos head = WorldHelper.getBedHead(mod, foot);
        Refusal refusal = NightSleep.refusal(overlay);
        if (refusal != Refusal.NONE) {
            overlay = null;
            if (NightSleep.nightIsOver(refusal, world.isBrightOutside(), nightAtStart, NightSleep.nightOf(world.getOverworldClockTime()))) {
                nightOver(mod, now);
                return null;
            }
            return refused(mod, refusal, now);
        }
        if (monstersBlock(mod, head)) {
            // the game would say no. ask the same question first and skip the click
            return refused(mod, Refusal.MONSTERS, now);
        }
        if (clickedAt < 0) {
            clickedAt = now;
        } else if (now - clickedAt > CLICK_PATIENCE_TICKS) {
            fail("the bed would not take us", now);
            return null;
        }
        setDebugState("Getting into bed.", "Going to bed");
        return new InteractWithBlockTask(head);
    }

    private Task refused(AltoClef mod, Refusal refusal, long now) {
        NightSleep.Next next = NightSleep.afterRefusal(refusal, lifts, closers);
        Debug.logInternal("night: refused (" + refusal + "), " + next);
        if (next == NightSleep.Next.CLOSER) {
            closers++;
            clickedAt = -1;
            return new GetToBlockTask(foot);
        }
        if (next == NightSleep.Next.HIGHER && state.nightBed != null) {
            // our own bed comes up, then the whole plan again from higher up
            lifts++;
            clickedAt = -1;
            liftAfterPickup = true;
            enter(Stage.PICKUP, now, "monsters by the bed, going higher");
            return null;
        }
        fail("the bed said no (" + refusal.name().toLowerCase() + ")", now);
        return null;
    }

    // the morning beat the bed to it. nothing failed: no cooldown, no "not sleeping tonight", the bed still comes back
    private void nightOver(AltoClef mod, long now) {
        outcome = Outcome.NIGHT_OVER;
        why = "";
        wrapUp(mod, now);
    }

    private void getUp(LocalPlayer player) {
        if (player.connection != null) {
            player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
        }
    }

    // ---- afterwards

    private Task pickup(AltoClef mod, long now) {
        ClientLevel world = mod.getWorld();
        RunState.Pos at = state.nightBed;
        if (at == null) {
            afterPickup(mod, now);
            return null;
        }
        BlockPos bed = new BlockPos(at.x, at.y, at.z);
        boolean standing = world.getBlockState(bed).getBlock() instanceof BedBlock;
        if (!pickupArmed) {
            pickupArmed = true;
            bedsBeforePickup = bedsInBag(mod);
        }
        if (NightSleep.timedOut(Stage.PICKUP, 0, retrievalSeconds(now))) {
            // our own try gets one more go on a later trip (nightBed stays set), a second time round it is not worth chasing
            Debug.logInternal("night: could not take the bed back, leaving it at " + bed.toShortString());
            if (cleanup) {
                state.nightBed = null;
                save.run();
            }
            afterPickup(mod, now);
            return null;
        }
        if (standing) {
            if (!bed.closerToCenterThan(mod.getPlayer().position(), 4)) {
                // an old bed of ours across the landscape: that is a walk, with mob defense on
                enter(Stage.RETURN, now, "walking back to the bed");
                return null;
            }
            setDebugState("Taking the bed back.", "Taking the bed back");
            return new DestroyBlockTask(bed);
        }
        if (bedsInBag(mod) > bedsBeforePickup) {
            state.nightBed = null;
            save.run();
            afterPickup(mod, now);
            return null;
        }
        // broken, the item is lying where it stood
        setDebugState("Picking the bed up.");
        return new PickupDroppedItemTask(new ItemTarget(ItemHelper.BED, bedsBeforePickup + 1), false);
    }

    private Task returnToBed(AltoClef mod, long now) {
        RunState.Pos at = state.nightBed;
        BlockPos bed = at == null ? null : new BlockPos(at.x, at.y, at.z);
        boolean standing = bed != null && mod.getWorld().getBlockState(bed).getBlock() instanceof BedBlock;
        if (!standing || bed.closerToCenterThan(mod.getPlayer().position(), 4)) {
            enter(Stage.PICKUP, now, "at the bed");
            return null;
        }
        if (NightSleep.timedOut(Stage.RETURN, 0, retrievalSeconds(now))
                || !bed.closerToCenterThan(mod.getPlayer().position(), cfg.get().nightBedRadius + 8)) {
            // too far or too slow to walk back for, and not ours to keep a claim on any more
            state.nightBed = null;
            save.run();
            Debug.logInternal("night: gave up walking back to the bed at " + bed.toShortString());
            if (cleanup) {
                finish(now);
            } else {
                fail("could not walk back to the bed", now);
            }
            return null;
        }
        setDebugState("Going back for the bed.", "Going back for the bed");
        return new GetToBlockTask(bed);
    }

    private void afterPickup(AltoClef mod, long now) {
        pickupArmed = false;
        foot = null;
        if (liftAfterPickup) {
            liftAfterPickup = false;
            if (state.nightBed == null) {
                afterBed(mod, now);
            } else {
                // it would not come up, a second bed beside it is not the answer
                fail("could not take the bed back to move it", now);
            }
            return;
        }
        wrapUp(mod, now);
    }

    // the sleep is over or the try is: the bed comes back first, then the way down
    private void wrapUp(AltoClef mod, long now) {
        // the bed that holds our respawn on the way to the stronghold stays up: a death down there respawns at it and not at the
        // world spawn a thousand blocks away. it is the spawn bed from here on, so there is no pickup (Respawn has the rule)
        if (Respawn.adoptNightBed(state, bedsInBag(mod), endBeds)) {
            save.run();
        }
        if (state.nightBed != null && stage != Stage.PICKUP) {
            enter(Stage.PICKUP, now, "taking the bed back");
            return;
        }
        BlockPos feet = mod.getPlayer().blockPosition();
        if (ground != null && feet.getY() > ground.getY() + 1) {
            enter(Stage.DESCEND, now, "back down to y=" + ground.getY());
            return;
        }
        finish(now);
    }

    private Task descend(AltoClef mod, long now) {
        BlockPos feet = mod.getPlayer().blockPosition();
        int blocks = ground == null ? 0 : Math.max(0, feet.getY() - ground.getY());
        if (ground == null || feet.getY() <= ground.getY() + 1 || NightSleep.timedOut(Stage.DESCEND, blocks, secondsIn(now))) {
            finish(now);
            return null;
        }
        setDebugState("Coming down off the pillar.", "Coming down");
        return new NightDescendTask(ground);
    }

    // ---- ways out

    // this try is over and did not work: the cooldown starts, the bed (if we put one down) comes back, the pillar (if we built
    // one) is walked down. low health skips all of that, see onTick
    private void fail(String reason, long now) {
        why = reason;
        outcome = Outcome.GAVE_UP;
        // (a failed cleanup cools down too, or the next tick starts it again)
        budget.failed(now * TICK_SECONDS, cfg.get().nightCooldownSeconds);
        Debug.logInternal("night: gave up this try, " + reason);
        if (mod == null || mod.getPlayer() == null) {
            finish(now);
            return;
        }
        wrapUp(mod, now);
    }

    private void finish(long now) {
        if (finished) {
            return;
        }
        finished = true;
        if (mod != null) {
            letGoOfStep(mod);
        }
        if (outcome == Outcome.GAVE_UP && !why.isEmpty() && !cleanup) {
            say.accept(failLine(why, retryLikely()));
        }
        Debug.logInternal("night: over, " + outcome + (why.isEmpty() ? "" : " (" + why + ")"));
    }

    // what whoever started us will want to see before it starts another try: a bed (in the bag, or the found one still
    // standing), still night, tries and seconds left. the busy and fighting gates are not ours to guess, this is only about
    // what the chat line says. a craft that failed is left out on purpose, it fails when the wool or the planks are gone
    private boolean retryLikely() {
        if (mod == null || mod.getPlayer() == null || mod.getWorld() == null) {
            return false;
        }
        boolean bed = bedsInBag(mod) > 0
                || (source == BedSource.FOUND && found != null && mod.getWorld().getBlockState(found).getBlock() instanceof BedBlock);
        OverworldConfig c = cfg.get();
        return canRetry(bed, !mod.getWorld().isBrightOutside(), budget.spent(c.nightAttempts, c.nightSeconds));
    }

    public static boolean canRetry(boolean haveBed, boolean night, boolean spent) {
        return haveBed && night && !spent;
    }

    // "not sleeping tonight" for a try that gets another go in 20 seconds is a lie the user reads as the bot giving up on sleep
    public static String failLine(String why, boolean retry) {
        if (!retry) {
            return "Not sleeping tonight: " + why;
        }
        if (BED_WOULD_NOT_GO_DOWN.equals(why)) {
            return "Couldn't get the bed down, trying again in a bit";
        }
        return "Couldn't sleep yet (" + why + "), trying again in a bit";
    }
}
