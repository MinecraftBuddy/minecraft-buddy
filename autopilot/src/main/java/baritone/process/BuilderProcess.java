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

package baritone.process;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.*;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.InputOverrideHandler;
import baritone.utils.PathingCommandContext;
import baritone.utils.accessor.IClientBlockBreak;
import baritone.utils.accessor.ISignEditScreen;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {

    private static final Set<Property<?>> ORIENTATION_PROPS =
            ImmutableSet.of(
                    RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING,
                    StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
                    PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP,
                    TrapDoorBlock.OPEN, TrapDoorBlock.HALF
            );

    private HashSet<BetterBlockPos> incorrectPositions;
    private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
    private String name;
    private ISchematic realSchematic;
    private ISchematic schematic;
    private Vec3i origin;
    private int ticks;
    private boolean paused;
    private int layer;
    private int numRepeats;
    private List<BlockState> approxPlaceable;
    public int stopAtHeight = 0;
    // bumped by every start, so whoever kicked off a build can tell later if it's still theirs or a #build took over
    private long buildSerial;
    // what the logMissing pass found with no item for it, kept for whoever wants to go and get it (SchematicBuildTask).
    // no lock: the builder, the gui and altoclef all run on the game thread
    private Map<BlockState, Integer> missingAtPause = Map.of();
    // whoever started the build gets to say which wrong blocks are fine anyway (SchematicBuildTask's scaffolding, so the
    // builder stops mining the pillar it is standing on). volatile because the path thread reads it through the bcc
    private volatile Keep keep;
    // the clicks still down are ours. onLostControl lets go of exactly those, once: it runs for every loser every tick
    // and clearAllKeys there would wipe whatever the winner just pressed
    private boolean forcedClicks;
    // same deal for the sneak and the walking keys hanging() and centre() hold. clearAllKeys at the top of the next
    // tick lets go of them, so it's only a stop that leaves them down
    private boolean keysForced;
    // the builder's own clock, recursions don't count
    private long builderTicks;
    // ticks in a row at the same feet with nothing clicked, see stallCheck
    private int stallTicks;
    private BetterBlockPos stallFeet;
    private static final int STALL_TICKS = 40;
    // see quickGiveUp
    private static final int NOTHING_TICKS = 5;
    // the last tick the place branch turned us towards a click, a rotation still on its way isn't a stare
    private long lastPlaceAim = Long.MIN_VALUE / 2;
    // walking to the middle of a planned spot, see centre. sneaking is about 0.06 a tick, 20 is the far edge twice over
    private int centreTicks;
    private BetterBlockPos centreFeet;
    private static final int CENTRE_TICKS = 20;
    private static final double CENTRE_TOLERANCE = 0.1;
    // centre sneaks with a drop this close, we're 0.3 either side of the middle so that's our edge over it already
    private static final double DROP_MARGIN = 0.3;
    // the swap into the hotbar had nowhere to go this tick, every slot is in use by something near here. see quickGiveUp
    private boolean hotbarFull;
    // ticks without the build getting further or us getting closer to where the path goes, whoever is driving. see
    // watchdog. much longer than STALL_TICKS, a long path calc or a slow pillar sits still for a while too
    private int watchdogTicks;
    private WatchdogProgress watchdogProgress = new WatchdogProgress();
    private float lastDestroyProgress;
    // the left click down last tick was the builder's own break, not a movement's
    private boolean builderDig;
    // ticks the dig held the watchdog since the last real progress. obsidian by hand fits, a dig and put back loop
    // runs out of it
    private int digHeldTicks;
    private static final int DIG_HOLD_TICKS = 400;
    // where the last path we saw was headed. paths get cancelled all the time, so getPath is empty half the ticks
    private BetterBlockPos lastPathDest;
    private static final int WATCHDOG_TICKS = 200;
    // a big composite has hundreds of owners, giving all of them up would skip half the build for one bad spot
    private static final int FAR_STALL_CELLS = 8;
    private BetterBlockPos lastBreak;
    private boolean digging;
    private GivenUp givenUp;
    // the give ups of a build that lost control, for when the same build at the same spot starts again (altoclef
    // restarts its build after every meal and every night). otherwise each restart was three fresh goes at the same
    // stuck cells
    private GivenUp keptGivenUp;
    private String keptName;
    private Vec3i keptOrigin;
    // only the caller that restarts its own build asks for that (keepGiveUpsOnce). a #build from chat or a one block
    // PlaceBlockTask retry gets its three fresh goes like before
    private boolean restoreGivenUp;
    // the scaffold this build put up and the right blocks paths dug out of it. mining a pillar the moment it went up was
    // the scaffold loop (upstream #2173): the path needs it to stand on, we mine it, it places it again. now it all
    // stays up till the build is done and comes down after, see cleanupStep
    private ScaffoldLedger ledger = new ScaffoldLedger();
    // the bcc's copy of it, made again only when the ledger or the cleanup moved
    private ScaffoldLedger.View ledgerView = ScaffoldLedger.View.EMPTY;
    private int ledgerViewVersion = -1;
    private int ledgerViewClean = -1;
    private int cleanVersion;
    // 0 while building, else which cleanup round this is
    private int cleanRound;
    private CleanStage cleanStage = CleanStage.NONE;
    // the scaffold this round takes down. anything a path puts up while we're at it waits for the next round
    private LongOpenHashSet cleanCells;
    // the path's cells plus the two over each, as of the last path we saw. a dug out cell in here is still in the way
    private LongOpenHashSet pathCells = new LongOpenHashSet();
    private Object pathCellsOf;
    // the last build that got all the way to "Done building", cleanup and all
    private long finishedSerial;

    private enum CleanStage {
        NONE,
        // scaffold inside the box, through the normal goal
        SCAFFOLD,
        // then the scaffold the builder can't see (outside the box, cut out cells), through our own GoalBreak pass
        OUTSIDE,
        // then the dug out cells that waited for the end
        DETOUR
    }
    // where to stand for the cells a movement can't place right (stairs, logs, anything aimed), see PlacementPlanner
    private PlacementPlanner planner;
    private Long2ObjectOpenHashMap<CellPlan> plans;
    // cells planned per tick, nearest first. the rest use upstream's goal till their turn
    private static final int PLANS_PER_TICK = 8;
    // a block this close changing can move the raytrace or the feet of a plan, chebyshev, same box the scans use
    private static final int PLAN_STALE_RADIUS = 5;
    private int planBudget;
    // an aimed cell this tick's goal left out because its plan isn't in yet (or is stale with nowhere to stand), see
    // placementGoal. set by assemble
    private boolean plansPending;
    // any cell at all waiting on a plan this tick, left out or not
    private boolean pendingSeen;
    // ticks in a row the whole goal was pending plans. past PENDING_WAIT_TICKS they get upstream's goal after all, till
    // a tick with nothing pending. the planner normally catches up in a few ticks, this is for when it doesn't
    private int pendingWaitTicks;
    private boolean pendingWaitedOut;
    private static final int PENDING_WAIT_TICKS = 100;
    // the goal each unplanned cell got last time, so it stays put until something about the cell changes. it used to be
    // worked out fresh every tick and flipped between GoalAdjacent and GoalPlace, and every flip cancelled the path
    private Long2ObjectOpenHashMap<StickyGoal> stickyGoals;
    // cells left out of the goal because the block can't stay there yet (a wall torch with no wall), logged once each.
    // cell -> builderTicks when it got its line
    private Long2LongOpenHashMap droppedLogged;
    // a cell that pops out of the dropped set and back in (a neighbour flickering in and out of placeable) waits this long
    // before it gets a second line
    private static final long DROPPED_LOG_FLOOR_TICKS = 200;
    private LongOpenHashSet torchNoPlanLogged;
    private List<BetterBlockPos> dropped = List.of();
    // when the builder last picked or filled each hotbar slot, the oldest one gets overwritten first
    private long[] slotUsed;
    // the throwaway stack creative keeps on the hotbar, so pillaring and bridging keep working
    private int throwawaySlot = -1;
    private long throwawayAt = -1000;
    // creative: whether a movement putting this state's own item down gets this state, see creativeMovementOk. filled on
    // the game thread by assemble, read by costOfPlacingAt on the path thread
    private volatile Map<BlockState, Boolean> movementOk;
    private static final int THROWAWAY_AGAIN_TICKS = 20;
    // the planner's tenth hotbar slot in creative, the block a click would get handed right before it. see possibleToPlace
    static final int VIRTUAL_SLOT = 9;
    // which cell asked for which part of the last goal, so the stall check knows who parked us
    private List<Pair<BetterBlockPos, Goal>> goalOwners = List.of();
    // what recalcNearby saw last time per cell, so it can tell a change from a cell that just came into range
    private Long2ObjectOpenHashMap<BlockState> seen;
    private Object seenWorld;
    // wrong blocks nothing short of creative breaks (bedrock where the file wants air), out of the goal and the scan
    private List<BetterBlockPos> unbreakableCells = List.of();
    // which fluid the bucket pass is on (water, then lava), null while there's block work left. see pourGoals
    private Block pouring;
    // sources left dry on purpose (water that boils off here, lava that would meet water), for the pause line
    private List<BetterBlockPos> unpourable = List.of();
    private LongOpenHashSet unpourableLogged;
    // the file as start left it, and per fluid whether it has a source of it anywhere. see spilled
    private ISchematic pourScan;
    private Map<Block, Boolean> sourceInFile;
    // the game's ticks, not the builder's: the sign editor and the empty bucket both outlive the builder's control
    private long clientTicks;
    private long pouredAt = Long.MIN_VALUE / 2;
    private int pouredSlot = -1;
    private static final int EMPTY_BUCKET_TICKS = 20;
    // signs we clicked in and when. the editor for one of those gets closed, nobody else's
    private final Map<BlockPos, Long> signsPlaced = new HashMap<>();
    private static final int SIGN_TICKS = 40;

    public BuilderProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onBlockChange(BlockChangeEvent event) {
                if (schematic == null || ctx.player() == null) {
                    return;
                }
                // a farm or a waterfall across the map sends these all day and each one walks every cell we care about,
                // so only the ones near us. the nearby scan in recalcNearby has the close ones covered anyway
                BetterBlockPos feet = ctx.playerFeet();
                for (Pair<BlockPos, BlockState> change : event.getBlocks()) {
                    BlockPos p = change.first();
                    if (Math.abs(p.getX() - feet.x) <= 32 && Math.abs(p.getY() - feet.y) <= 32 && Math.abs(p.getZ() - feet.z) <= 32) {
                        blockChanged(p.getX(), p.getY(), p.getZ());
                    }
                }
            }

            @Override
            public void onTick(TickEvent event) {
                if (event.getType() == TickEvent.Type.IN) {
                    clientTicks++;
                    afterClicks();
                }
            }
        });
    }

    // what the builder's own clicks leave behind, every tick whether it has control or not. the last block of a build is
    // as likely as not the sign, and then it's done before the editor even shows up
    private void afterClicks() {
        if (clientTicks - pouredAt <= EMPTY_BUCKET_TICKS) {
            dropEmptyBucket();
        }
        if (!signsPlaced.isEmpty()) {
            closeOurSignEditor();
        }
    }

    // a survival pour leaves an empty bucket in hand, and the next right click from anybody (a movement, the user)
    // scoops the water right back up. BlockPlaceHelper's use fallback stays as it is, MovementFall picks its water up
    // through it
    private void dropEmptyBucket() {
        if (ctx.player().getInventory().getSelectedSlot() != pouredSlot || !emptyBucket(ctx.player().getMainHandItem())) {
            return;
        }
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            if (!(inv.get(i).getItem() instanceof BucketItem)) {
                ctx.player().getInventory().setSelectedSlot(i);
                pouredAt = Long.MIN_VALUE / 2;
                return;
            }
        }
    }

    private static boolean emptyBucket(ItemStack stack) {
        return stack.getItem() instanceof BucketItem bucket && bucket.getContent() == Fluids.EMPTY;
    }

    // the editor shows up a round trip after the click, so this keeps looking for a bit. removed() sends the blank text
    // like pressing done would. PlaceSignTask writes its own signs and never goes through the builder's click, so its
    // editor stays open
    private void closeOurSignEditor() {
        signsPlaced.values().removeIf(at -> clientTicks - at > SIGN_TICKS);
        if (ctx.minecraft().gui.screen() instanceof AbstractSignEditScreen screen
                && signsPlaced.remove(((ISignEditScreen) screen).baritone$sign().getBlockPos()) != null) {
            ctx.minecraft().gui.setScreen(null);
        }
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        start(name, schematic, origin, Baritone.settings().buildSchematicRotation.value,
                Baritone.settings().buildSchematicMirror.value, true);
    }

    @Override
    public void build(String name, IStaticSchematic schematic, Vec3i origin, net.minecraft.world.level.block.Rotation rotation,
                      net.minecraft.world.level.block.Mirror mirror) {
        // map art reads the raw columns, so it goes under the transform. the selection is in world space, and origin is
        // already the corner of the turned footprint, so it goes over it (the global setting path does it the other way
        // round, which only lines up when nothing is rotated)
        ISchematic placed = placed(schematic, rotation, mirror);
        start(name, onlySelection(placed, origin), origin, net.minecraft.world.level.block.Rotation.NONE,
                net.minecraft.world.level.block.Mirror.NONE, false);
    }

    // the old entry point with the orientation pulled out. useOrientation = add the schematicOrientation offsets, which
    // only the global setting path wants: they use the unturned size and would push a placed origin off its corner.
    // no ctx in here, BuilderPlacedBuildTest makes the builder without a baritone
    private void start(String name, ISchematic schematic, Vec3i origin, net.minecraft.world.level.block.Rotation rotation,
                       net.minecraft.world.level.block.Mirror mirror, boolean useOrientation) {
        this.buildSerial++;
        this.missingAtPause = Map.of();
        // a #build from chat doesn't get to inherit somebody else's pillar
        this.keep = null;
        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, Baritone.settings().buildSubstitutes.value);
        }
        this.schematic = SchematicTransforms.apply(this.schematic, rotation, mirror);
        // TODO this preserves the old behavior, but maybe we should bake the setting value right here
        this.schematic = new MaskSchematic(this.schematic) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic so desiredState is not null
                return !Baritone.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
        this.pourScan = this.schematic;
        this.sourceInFile = null;
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (useOrientation && Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (useOrientation && Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (useOrientation && Baritone.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.paused = false;
        this.layer = Baritone.settings().startAtLayer.value;
        this.stopAtHeight = schematic.heightY();
        if (Baritone.settings().buildOnlySelection.value && buildingSelectionSchematic) {  // currently redundant but safer maybe
            if (baritone.getSelectionManager().getSelections().length == 0) {
                logDirect("Poor little kitten forgot to set a selection while BuildOnlySelection is true");
                this.stopAtHeight = 0;
            } else if (Baritone.settings().buildInLayers.value) {
                OptionalInt minim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.min().y).min();
                OptionalInt maxim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.max().y).max();
                if (minim.isPresent() && maxim.isPresent()) {
                    int startAtHeight = Baritone.settings().layerOrder.value ? y + schematic.heightY() - maxim.getAsInt() : minim.getAsInt() - y;
                    this.stopAtHeight = (Baritone.settings().layerOrder.value ? y + schematic.heightY() - minim.getAsInt() : maxim.getAsInt() - y) + 1;
                    this.layer = Math.max(this.layer, startAtHeight / Baritone.settings().layerHeight.value);  // startAtLayer or startAtHeight, whichever is highest
                    logDebug(String.format("Schematic starts at y=%s with height %s", y, schematic.heightY()));
                    logDebug(String.format("Selection starts at y=%s and ends at y=%s", minim.getAsInt(), maxim.getAsInt()));
                    logDebug(String.format("Considering relevant height %s - %s", startAtHeight, this.stopAtHeight));
                }
            }
        }

        this.numRepeats = 0;
        this.observedCompleted = new LongOpenHashSet();
        this.incorrectPositions = null;
        forgetStalls();
        forgetCreative();
        // the same build again (altoclef after a meal, a night, a gather) still owns the scaffold it put up. a chat
        // #build somewhere else doesn't get to tear it down
        if (ledger == null) {
            ledger = new ScaffoldLedger(); // BuilderPlacedBuildTest skips the constructor, and the field initializers with it
        }
        if (ledger.own(name, this.origin)) {
            ledger.unabandon();
        }
        forgetCleanup();
        if (restoreGivenUp && keptGivenUp != null && Objects.equals(name, keptName) && this.origin.equals(keptOrigin)) {
            // a world change in between still wipes it, recalcNearby's seenWorld check sees to that. the for good ones
            // get one more go: nobody was watching their neighbours while we were stopped, and altoclef's cleanup
            // takes scaffold down in exactly that gap
            givenUp = keptGivenUp;
            givenUp.unwatched(builderTicks);
        }
        keptGivenUp = null;
        restoreGivenUp = false;
    }

    // the next start of this same build (name and origin) picks up the give ups the last one lost control with
    public void keepGiveUpsOnce() {
        restoreGivenUp = true;
    }

    // a fresh build, a resume or a repeat: whatever we gave up on before was about a different situation
    // what the build hands itself in creative, see creativeSlot. no ctx in here either, start calls it
    private void forgetCreative() {
        slotUsed = null;
        movementOk = null;
        throwawaySlot = -1;
        throwawayAt = -1000;
    }

    private void forgetStalls() {
        givenUp = null;
        stallTicks = 0;
        stallFeet = null;
        resetWatchdog();
        lastPathDest = null;
        lastBreak = null;
        centreFeet = null;
        centreTicks = 0;
        goalOwners = List.of();
        seen = null;
        // and the plans with them, they're cheap to make again and a stale one costs a walk
        plans = null;
        stickyGoals = null;
        pendingWaitTicks = 0;
        pendingWaitedOut = false;
        droppedLogged = null;
        torchNoPlanLogged = null;
        dropped = List.of();
        pouring = null;
        unpourable = List.of();
        unpourableLogged = null;
        pathCells = new LongOpenHashSet();
        pathCellsOf = null;
    }

    // the ledger stays, only where the cleanup was goes. the next time there's nothing else left it starts over
    private void forgetCleanup() {
        cleanRound = 0;
        cleanStage = CleanStage.NONE;
        cleanCells = null;
        cleanVersion++;
    }

    public void resume() {
        paused = false;
        missingAtPause = Map.of();
        // everything left got given up on for good if we paused over it, so the resume is the next go
        forgetStalls();
    }

    public void pause() {
        paused = true;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public Map<BlockState, Integer> missingAtPause() {
        return missingAtPause;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return false;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        ISchematic schem = applyMapArtAndSelection(origin, parsed);
        build(name, schem, origin);
        return true;
    }

    private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
        return onlySelection(mapArt(parsed), origin);
    }

    // the file laid out the way the build above lays it out, before the selection, substitutes and skip blocks. public so
    // SchematicBuildTask prices the rest of a build off the same blocks the builder will ask for
    public static ISchematic placed(IStaticSchematic schematic, net.minecraft.world.level.block.Rotation rotation,
                                    net.minecraft.world.level.block.Mirror mirror) {
        return SchematicTransforms.apply(mapArt(schematic), rotation, mirror);
    }

    private static ISchematic mapArt(IStaticSchematic parsed) {
        return Baritone.settings().mapArtMode.value ? new MapArtSchematic(parsed) : parsed;
    }

    private ISchematic onlySelection(ISchematic schematic, Vec3i origin) {
        if (Baritone.settings().buildOnlySelection.value) {
            return new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
        }
        return schematic;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Pair<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().first();
                BlockPos origin = schematic.get().second();
                ISchematic schem = applyMapArtAndSelection(origin, raw);
                this.build(raw.toString(), schem, origin);
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (LitematicaHelper.isLitematicaPresent()) {
            //if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
            if (LitematicaHelper.hasLoadedSchematic(i)) {
                Pair<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.second();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, schematic.first());
                build(schematic.first().toString(), schematic2, correctedOrigin);
            } else {
                logDirect(String.format("List of placements has no entry %s", i + 1));
            }
        } else {
            logDirect("Litematica is not present");
        }
    }

    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    @Override
    public void setKeep(Keep keep) {
        this.keep = keep;
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    @Override
    public long buildSerial() {
        // onLostControl nulls the schematic, so a finished or cancelled build reads as none
        return schematic != null ? buildSerial : 0;
    }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }
        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        return state;
    }

    private Optional<Pair<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
        if (cleanStage == CleanStage.SCAFFOLD) {
            // the scaffold in the box in cleanupOrder, the scan below would go bottom up
            Optional<Pair<BetterBlockPos, Rotation>> scaffold = firstReachable(bcc, cleanTargets(bcc, true), pathStart);
            if (scaffold.isPresent()) {
                return scaffold;
            }
        }
        boolean hanging = hanging();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = Baritone.settings().breakFromAbove.value ? -1 : 0; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (dy == -1 && x == pathStart.x && z == pathStart.z) {
                        continue; // dont mine what we're supported by, but not directly standing on
                    }
                    if (hanging && dx == 0 && dy == 0 && dz == 0) {
                        continue; // the ladder we're holding on to. from the ground it's just another block
                    }
                    if (givenUp(x, y, z)) {
                        continue;
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (hanging && dx == 0 && dz == 0 && (dy == 1 || dy == -1) && curr.is(BlockTags.CLIMBABLE)) {
                        // the rungs above our head and under our feet are the column we're on. mine one and we drop or
                        // lose the grip, it gets fixed once we're off the ladder and it's just a wrong block 1 away
                        continue;
                    }
                    // fine, not valid: a kept block is the caller's scaffolding and the ledger's is ours, the builder
                    // never gets to mine either till the cleanup
                    if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA) && !bcc.fine(x, y, z, curr, desired) && !unbreakable(x, y, z, curr)
                            && !besideUs(center, x, y, z)) {
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
                        if (rot.isPresent()) {
                            return Optional.of(new Pair<>(pos, rot.get()));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    // scaffold where the file wants a real block isn't cleanup work, the builder swaps it like any wrong block. just not
    // while it's what we stand on or right next to us, that's the pillar we're on and the edge we're leaning on. only the
    // ones this build put there, the dirt we started on is just ground
    private boolean besideUs(BlockPos feet, int x, int y, int z) {
        return ledger.isSolid(BetterBlockPos.serializeToLong(x, y, z)) && besideFeet(feet, x, y, z);
    }

    // under the feet, or touching the body from the side (feet level, head level or the one under feet level)
    static boolean besideFeet(BlockPos feet, int x, int y, int z) {
        int dy = y - feet.getY();
        return dy >= -1 && dy <= 1 && Math.abs(x - feet.getX()) + Math.abs(z - feet.getZ()) <= 1;
    }

    // the first of these we can hit from here, never the block we're standing on
    private Optional<Pair<BetterBlockPos, Rotation>> firstReachable(BuilderCalculationContext bcc, List<BetterBlockPos> cells, BetterBlockPos pathStart) {
        BetterBlockPos feet = ctx.playerFeet();
        for (BetterBlockPos pos : cells) {
            if (Math.abs(pos.x - feet.x) > 6 || Math.abs(pos.y - feet.y) > 6 || Math.abs(pos.z - feet.z) > 6) {
                continue; // nearest column first, but it's still a list of the whole build
            }
            if (pos.equals(feet.below()) || pos.equals(pathStart.below())) {
                continue;
            }
            if (unbreakable(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z))) {
                continue;
            }
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
            if (rot.isPresent()) {
                return Optional.of(new Pair<>(pos, rot.get()));
            }
        }
        return Optional.empty();
    }

    // what this cleanup round still has to take down: in the box where the file wants air, or the cells the builder
    // can't see at all. in cleanupOrder from our feet
    private List<BetterBlockPos> cleanTargets(BuilderCalculationContext bcc, boolean inBox) {
        if (cleanCells == null) {
            return List.of();
        }
        List<BetterBlockPos> out = new ArrayList<>();
        LongIterator it = cleanCells.iterator();
        while (it.hasNext()) {
            long cell = it.nextLong();
            if (!ledger.scaffold().contains(cell)) {
                continue; // already down
            }
            BetterBlockPos pos = BetterBlockPos.deserializeFromLong(cell);
            if (AltoClefSettings.getInstance().shouldAvoidBreaking(pos.x, pos.y, pos.z)) {
                // altoclef says hands off (a portal frame), so whoever booked it, it's not ours to take down. one
                // volatile read and out when altoclef isn't around
                ledger.gone(cell, false);
                it.remove();
                cleanVersion++;
                continue;
            }
            if (givenUp(pos.x, pos.y, pos.z)) {
                continue;
            }
            BlockState current = bcc.bsi.get0(pos.x, pos.y, pos.z);
            if (bcc.bsi.worldContainsLoadedChunk(pos.x, pos.z) && current.getBlock() instanceof AirBlock) {
                continue; // tidyLedger gets it next tick
            }
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, current);
            if (inBox ? desired != null && desired.getBlock() instanceof AirBlock : desired == null) {
                out.add(pos);
            }
        }
        return ScaffoldLedger.cleanupOrder(out, ctx.playerFeet());
    }

    public static class Placement {

        private final int hotbarSelection;
        private final BlockPos placeAgainst;
        private final Direction side;
        private final Rotation rot;
        // creative: the item this click needs handed over first, null when it's already in hotbarSelection
        private final Item give;
        // the shift key this click goes out with, and the eye height the plan was worked out from (crouched under a low
        // ceiling even without the sneak). aimLanded waits for both
        private final boolean sneak;
        private final double eyeHeight;

        Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot, Item give, boolean sneak, double eyeHeight) {
            this.hotbarSelection = hotbarSelection;
            this.placeAgainst = placeAgainst;
            this.side = side;
            this.rot = rot;
            this.give = give;
            this.sneak = sneak;
            this.eyeHeight = eyeHeight;
        }

        // the same click once the give landed in a real slot
        Placement inSlot(int slot) {
            return new Placement(slot, placeAgainst, side, rot, null, sneak, eyeHeight);
        }

        // side is the face of placeAgainst we click, so the block lands one step out of it
        BlockPos target() {
            return placeAgainst.relative(side);
        }
    }

    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        BetterBlockPos center = ctx.playerFeet();
        // standing where the planner said to stand for some cell: that one first, off the face it planned
        Optional<Placement> planned = plannedFromHere(bcc, center, desirableOnHotbar);
        if (planned.isPresent()) {
            return planned;
        }
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 1; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = wantsPlace(bcc, center, x, y, z);
                    if (desired == null) {
                        continue;
                    }
                    desirableOnHotbar.add(desired);
                    Optional<Placement> opt = possibleToPlace(bcc, desired, x, y, z, null);
                    if (opt.isPresent()) {
                        return opt;
                    }
                }
            }
        }
        return Optional.empty();
    }

    // what the scan would want placed at this cell from center, null if it wouldn't place anything there
    private BlockState wantsPlace(BuilderCalculationContext bcc, BetterBlockPos center, int x, int y, int z) {
        int dy = y - center.y;
        if (Math.abs(x - center.x) > 5 || Math.abs(z - center.z) > 5 || dy < -5 || dy > 1) {
            return null;
        }
        BlockState curr = bcc.bsi.get0(x, y, z);
        BlockState desired = bcc.getSchematic(x, y, z, curr);
        if (desired == null || givenUp(x, y, z)) {
            return null; // irrelevant
        }
        if (!MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) || bcc.fine(x, y, z, curr, desired)) {
            return null;
        }
        if (dy == 1 && bcc.bsi.get0(x, y + 1, z).getBlock() instanceof AirBlock) {
            return null;
        }
        if (desired.getBlock() instanceof AirBlock) {
            // grass where the file wants air. no item places air, and an empty slot "supplies" it, so this kept every
            // empty slot off limits for the hotbar swap below
            return null;
        }
        if (fluidSource(desired) && !pourHere(bcc, center, x, y, z, desired)) {
            return null;
        }
        return desired;
    }

    // the scan's half of the bucket pass: only the fluid that's up, not the ones we leave dry on purpose, and survival
    // lava only from above. lava runs sideways and down, never up
    private boolean pourHere(BuilderCalculationContext bcc, BetterBlockPos feet, int x, int y, int z, BlockState desired) {
        if (pouring != desired.getBlock() || pourSkip(bcc, x, y, z, desired) != null) {
            return false;
        }
        return desired.getBlock() != Blocks.LAVA || ctx.player().hasInfiniteMaterials() || feet.y > y;
    }

    private Optional<Placement> plannedFromHere(BuilderCalculationContext bcc, BetterBlockPos feet, List<BlockState> desirableOnHotbar) {
        if (plans == null || plans.isEmpty()) {
            return Optional.empty();
        }
        for (Long2ObjectMap.Entry<CellPlan> e : plans.long2ObjectEntrySet()) {
            for (PlacementPlanner.Feet f : e.getValue().feet) {
                if (!f.feet().equals(feet)) {
                    continue;
                }
                BetterBlockPos cell = BetterBlockPos.deserializeFromLong(e.getLongKey());
                BlockState desired = wantsPlace(bcc, feet, cell.x, cell.y, cell.z);
                if (desired == null) {
                    break;
                }
                desirableOnHotbar.add(desired);
                Optional<Placement> opt = possibleToPlace(bcc, desired, cell.x, cell.y, cell.z, f.recipe().side().getOpposite());
                if (opt.isPresent()) {
                    return opt;
                }
                break;
            }
        }
        return Optional.empty();
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    // upstream's possibleToPlace, with the per face work moved into PlacementPlanner.plan so the planner's guesses and
    // this real click ask the exact same question. the real eye and the aim processor's noise, like upstream
    private Optional<Placement> possibleToPlace(BuilderCalculationContext bcc, BlockState toPlace, int x, int y, int z, Direction preferAgainst) {
        BetterBlockPos pos = new BetterBlockPos(x, y, z);
        if (!placementPlausible(pos, toPlace)) {
            return Optional.empty();
        }
        List<ItemStack> hotbar = ctx.player().getInventory().getNonEquipmentItems().subList(0, 9);
        // creative: the block we'd hand ourselves rides along as a tenth slot, so a cell gets a real click planned before
        // its item is anywhere near the hotbar. the real slots go first, so one that already has it wins
        Item virtual = virtualItem(toPlace);
        if (virtual != null) {
            hotbar = new ArrayList<>(hotbar);
            hotbar.add(new ItemStack(virtual));
        }
        // whatever the shift key did last tick first, so a run of clicks doesn't bob up and down between them. the other
        // one only where it can change the answer: a face that'd rather be used, a block that reads the sneak, or
        // getting out of a crouch nobody needs any more
        boolean must = mustSneak(bcc, toPlace);
        boolean first = must || ctx.player().isShiftKeyDown();
        Optional<Placement> placement = possibleToPlace(bcc, toPlace, pos, preferAgainst, hotbar, virtual, first);
        if (placement.isEmpty() && !must && (first || PlaceRules.readsSneak(toPlace.getBlock()) || planner().usableNeighbour(pos))) {
            placement = possibleToPlace(bcc, toPlace, pos, preferAgainst, hotbar, virtual, !first);
        }
        return placement;
    }

    private Optional<Placement> possibleToPlace(BuilderCalculationContext bcc, BlockState toPlace, BetterBlockPos pos, Direction preferAgainst,
                                                List<ItemStack> hotbar, Item virtual, boolean sneak) {
        PlacementPlanner planner = planner();
        double eyeHeight = planner.eyeHeight(ctx.player().position(), sneak);
        Vec3 eye = ctx.player().position().add(0, eyeHeight, 0);
        double reach = ctx.playerController().getBlockReachDistance();
        UnaryOperator<Rotation> noise = PlacementPlanner.noise(baritone.getLookBehavior().getAimProcessor());
        // room to the next quadrant if there's a way to get it, a stair aimed right at the boundary only works on the
        // ticks the noise feels like it. the click gate catches the rest either way
        Optional<PlacementPlanner.Recipe> recipe = PlaceRules.aimProof(toPlace) || fluidSource(toPlace)
                ? Optional.empty()
                : planner.plan(bcc.bsi, eye, pos, toPlace, hotbar, noise, true, reach, preferAgainst, sneak);
        if (recipe.isEmpty()) {
            recipe = planner.plan(bcc.bsi, eye, pos, toPlace, hotbar, noise, false, reach, preferAgainst, sneak);
        }
        return recipe.map(r -> new Placement(r.slot(), r.placeAgainst(), r.side(), r.rot(), r.slot() == VIRTUAL_SLOT ? virtual : null, sneak, eyeHeight));
    }

    // no choice about the sneak: hanging off a ladder (onTick holds it anyway), a bucket (unsneaked it pours into
    // whatever we clicked), or rocking on the edge of the block next door with air under our own cell
    private boolean mustSneak(BuilderCalculationContext bcc, BlockState toPlace) {
        if (hanging() || fluidSource(toPlace)) {
            return true;
        }
        BetterBlockPos feet = ctx.playerFeet();
        return ctx.player().onGround() && !MovementHelper.canWalkOn(bcc.bsi, feet.x, feet.y - 1, feet.z);
    }

    // what creative would hand over for this cell, null in survival or with no slot to put it in
    private Item virtualItem(BlockState toPlace) {
        if (!ctx.player().hasInfiniteMaterials()) {
            return null;
        }
        Item item = creativeItem(toPlace);
        return item == null || creativeSlot() < 0 ? null : item;
    }

    private PlacementPlanner planner() {
        if (planner == null) {
            planner = new PlacementPlanner(ctx);
        }
        return planner;
    }

    // the click goes out later this same tick (InputOverrideHandler ticks after the processes) off the rotation the
    // server already has, which is what ctx.playerRotations and objectMouseOver are with freeLook. so run the placement
    // again on exactly that ray and rotation and only click if the right state comes out. upstream also clicked when
    // the rotation was merely close, which is how stairs went in backwards
    private boolean placeGateOpen(BuilderCalculationContext bcc, Placement p) {
        if (emptyBucket(ctx.player().getMainHandItem())) {
            // the click would scoop up whatever fluid it's looking at, likely the one we just poured
            return false;
        }
        if (!aimLanded(p)) {
            return false;
        }
        BlockHitResult hit = (BlockHitResult) ctx.objectMouseOver();
        BlockPos cell = p.target();
        BlockState desired = bcc.getSchematic(cell.getX(), cell.getY(), cell.getZ(), bcc.bsi.get0(cell));
        if (desired == null) {
            return false;
        }
        ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(p.hotbarSelection);
        return planner().placesRight(desired, hit, ctx.playerRotations(), stack, ctx.player().isShiftKeyDown());
    }

    // the half of placeGateOpen that doesn't care what's in our hand, so the creative give can wait for it too
    private boolean aimLanded(Placement p) {
        // the shift key the server has is last tick's, and that's what isShiftKeyDown says too. the click goes out before
        // this tick's input does. the pose is a separate thing: a low ceiling crouches us with no shift at all, and the
        // plan's eye was worked out for whichever one we'd have, so that has to be in too
        if (ctx.player().isShiftKeyDown() != p.sneak || Math.abs(ctx.player().getEyeHeight() - p.eyeHeight) > 1.0E-3) {
            return false;
        }
        HitResult over = ctx.objectMouseOver();
        return over instanceof BlockHitResult hit && over.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(p.placeAgainst) && hit.getDirection() == p.side;
    }

    private void forceSneak() {
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        keysForced = true;
    }

    // what afterClicks cleans up after. a click that doesn't land gets another go a few ticks on, and this just writes
    // the same thing again
    private void clicked(BuilderCalculationContext bcc, Placement p) {
        if (pours(ctx.player().getMainHandItem()) != null) {
            pouredAt = clientTicks;
            pouredSlot = p.hotbarSelection;
        }
        BlockPos cell = p.target();
        BlockState desired = bcc.getSchematic(cell.getX(), cell.getY(), cell.getZ(), bcc.bsi.get0(cell));
        if (desired != null && desired.getBlock() instanceof SignBlock) {
            signsPlaced.put(cell.immutable(), clientTicks);
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        builderTicks++;
        planBudget = PLANS_PER_TICK;
        // a dig the break branch started last tick and the block is still there: obsidian by hand is ~250 ticks of
        // this and none of them are a stare
        // forcedClicks still says whether last tick's click was ours, the inner onTick only lets go of it after this
        builderDig = forcedClicks && baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT);
        digging = baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT) && lastBreak != null
                && !ctx.world().getBlockState(lastBreak).isAir();
        return onTick(calcFailed, isSafeToCancel, 0);
    }

    private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions) {
        if (recursions > 100) { // onTick calls itself, don't crash
            return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
        }
        approxPlaceable = approxPlaceable(36);
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            ticks = 5;
        } else {
            ticks--;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        forcedClicks = false;
        keysForced = false;
        hotbarFull = false;
        if (hanging()) {
            // every tick, not just at the click. let go of sneak on a ladder and we slide, push on it and vanilla bumps
            // us up 0.2, and the click gates wait for steady() forever. a climb still works: the path's movement clears
            // the keys after us (Movement.update) and presses its own
            forceSneak();
        }
        if (paused) {
            stallTicks = 0;
            resetWatchdog();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (recursions == 0 && ctx.player().hasInfiniteMaterials() && !AltoClefSettings.getInstance().isInteractionPaused()) {
            creativeThrowaway();
        }
        if (Baritone.settings().buildInLayers.value) {
            if (realSchematic == null) {
                realSchematic = schematic;
            }
            ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
            int minYInclusive;
            int maxYInclusive;
            // layer = 0 should be nothing
            // layer = realSchematic.heightY() should be everything
            if (Baritone.settings().layerOrder.value) { // top to bottom
                maxYInclusive = realSchematic.heightY() - 1;
                minYInclusive = realSchematic.heightY() - layer * Baritone.settings().layerHeight.value;
            } else {
                maxYInclusive = layer * Baritone.settings().layerHeight.value - 1;
                minYInclusive = 0;
            }
            schematic = new ISchematic() {
                @Override
                public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                    return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
                }

                @Override
                public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                    return ISchematic.super.inSchematic(x, y, z, currentState) && y >= minYInclusive && y <= maxYInclusive && realSchematic.inSchematic(x, y, z, currentState);
                }

                @Override
                public void reset() {
                    realSchematic.reset();
                }

                @Override
                public int widthX() {
                    return realSchematic.widthX();
                }

                @Override
                public int heightY() {
                    return realSchematic.heightY();
                }

                @Override
                public int lengthZ() {
                    return realSchematic.lengthZ();
                }
            };
        }
        if (recursions == 0) {
            // before the bcc copies the ledger, so it gets this tick's answer
            tidyLedger();
        }
        BuilderCalculationContext bcc = new BuilderCalculationContext();
        if (recursions == 0) {
            // up here and not next to stallCheck, the click and break branches return before that
            watchdog(bcc);
        }
        // altoclef pausing interactions means no breaking, no placing and no hotbar shuffling, we just keep walking
        boolean interactionPaused = AltoClefSettings.getInstance().isInteractionPaused();
        if (cleanStage == CleanStage.OUTSIDE) {
            PathingCommand outside = cleanOutside(bcc, interactionPaused, isSafeToCancel);
            if (outside != null) {
                return outside;
            }
            // nothing left out there, on to the dug out cells with a fresh bcc
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        if (!recalc(bcc)) {
            if (Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < stopAtHeight) {
                logDirect("Starting layer " + layer);
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            // the whole build is right, the repeat too. before the origin moves, so the scaffold is still ours to find
            if (endPassing() || cleanupStep()) {
                incorrectPositions = null;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            Vec3i repeat = Baritone.settings().buildRepeat.value;
            int max = Baritone.settings().buildRepeatCount.value;
            numRepeats++;
            if (repeat.equals(new Vec3i(0, 0, 0)) || (max != -1 && numRepeats >= max)) {
                logDirect("Done building");
                if (Baritone.settings().notificationOnBuildFinished.value) {
                    logNotification("Done building", false);
                }
                // altoclef asks this to tell done from knocked off, onLostControl takes the serial with it
                finishedSerial = buildSerial;
                onLostControl();
                return null;
            }
            // build repeat time
            layer = 0;
            forgetStalls(); // keyed by world position, and the origin is about to move
            origin = new BlockPos(origin.getX(), origin.getY(), origin.getZ()).offset(repeat);
            if (!Baritone.settings().buildRepeatSneaky.value) {
                schematic.reset();
            }
            logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }

        Optional<Pair<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayer(bcc);
        if (!interactionPaused && toBreak.isPresent() && isSafeToCancel && steady()) {
            return breakAt(bcc, toBreak.get().first(), toBreak.get().second());
        }
        List<BlockState> desirableOnHotbar = new ArrayList<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        // creative breaks land the tick we click, there's no dig to wait out
        boolean digCooldown = ticks > 0 && !ctx.player().getAbilities().instabuild;
        if (!interactionPaused && toPlace.isPresent() && isSafeToCancel && steady() && !digCooldown) {
            dropPathBeforeClicking();
            lastPlaceAim = builderTicks;
            Placement place = toPlace.get();
            if (place.give != null) {
                if (!aimLanded(place) || !baritone.getInputOverrideHandler().getBlockPlaceHelper().ready()) {
                    // the first tick after a walk the sneak (if this one wants it) hasn't kicked in and the head turns
                    // after us (updateTarget), so the gate is shut whatever we hand over. same for a click the place
                    // timer would hold back. a give now just lands in the lru slot and maybe kicks out a block the next
                    // cell wanted. turn, sneak if needed, and give once the click can go. the slot is still
                    // VIRTUAL_SLOT here, which is no hotbar slot at all, so nothing below this gets to see it
                    baritone.getLookBehavior().updateTarget(place.rot, true);
                    if (place.sneak) {
                        forceSneak();
                    }
                    if (stalled()) {
                        giveUpOn(bcc, List.of(BetterBlockPos.from(place.target())), "Couldn't get a place click in");
                    }
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                // the one give this click needs, right before it. the creative slot packet goes out now, the click later
                // this tick, and useItemOn sends the slot change ahead of the use packet, so the server sees it in order
                int slot = creativeSlot();
                give(place.give, slot);
                place = place.inSlot(slot);
            }
            Rotation rot = place.rot;
            baritone.getLookBehavior().updateTarget(rot, true);
            ctx.player().getInventory().setSelectedSlot(place.hotbarSelection);
            slotUsed()[place.hotbarSelection] = builderTicks;
            if (place.sneak) {
                forceSneak();
            }
            // a click pressed while BlockPlaceHelper's timer runs just gets dropped, so only on the ticks it'd go out
            if (baritone.getInputOverrideHandler().getBlockPlaceHelper().ready() && placeGateOpen(bcc, place)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                forcedClicks = true;
                // a click going out isn't a block going in: a Fail, a mob in the cell or a server saying no all land
                // nothing. placed() resets the count once one of ours really shows up right, so a click that never lands
                // runs out like any other stall. a bucket isn't a block item and never gets that far, so a pour counts here
                if (pours(ctx.player().getMainHandItem()) != null) {
                    stallTicks = 0;
                } else if (countStall() && stallTicks >= STALL_TICKS) {
                    // rightClickSpeed 1 clicks every tick and never gets to the stalled() below
                    stallTicks = 0;
                    giveUpOn(bcc, List.of(BetterBlockPos.from(place.target())), "Couldn't get a place click in");
                }
                // whatever centre() was counting was about getting this click in, and it's in
                centreFeet = null;
                centreTicks = 0;
                clicked(bcc, place);
            } else if (stalled()) {
                giveUpOn(bcc, List.of(BetterBlockPos.from(place.target())), "Couldn't get a place click in");
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // creative hands itself blocks one click at a time (the give above), nothing to fetch here
        if (!interactionPaused && !ctx.player().hasInfiniteMaterials() && Baritone.settings().allowInventory.value) {
            ArrayList<Integer> usefulSlots = new ArrayList<>();
            List<BlockState> noValidHotbarOption = new ArrayList<>();
            outer:
            for (BlockState desired : desirableOnHotbar) {
                for (int i = 0; i < 9; i++) {
                    if (canSupply(approxPlaceable.get(i), desired)) {
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (canSupply(approxPlaceable.get(i), desired)) {
                        if (!baritone.getInventoryBehavior().attemptToPutOnHotbar(i, usefulSlots::contains)) {
                            if (baritone.getInventoryBehavior().movePending()) {
                                // awaiting inventory move, so pause
                                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                            }
                            // no slot to put it in, so it isn't coming however long we wait. pausing for it was standing
                            // still forever, and going on as if it had worked was standing in the goal with nothing to
                            // click. go on, and let the stall check know not to wait on it
                            hotbarFull = true;
                        }
                        break outer;
                    }
                }
            }
        }

        Goal goal = goalNearFirst(bcc);
        if (!pendingSeen) {
            pendingWaitTicks = 0;
            pendingWaitedOut = false;
        } else if (goal != null) {
            pendingWaitTicks = 0; // in a row, a stretch of building in between starts it over
        }
        if (goal == null && plansPending) {
            // all that's left here is aimed cells the planner hasn't got to yet. they're first in line (planOrder),
            // a few ticks of standing still beats giving up on them or pausing the build
            if (++pendingWaitTicks >= PENDING_WAIT_TICKS) {
                pendingWaitedOut = true;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (goal == null) {
            if (retryGivenUp()) {
                // all that's left is stuff we let go of for a bit. that's not "unable to do it" and not a reason to
                // skip a layer, so give it another go now. each go is an attempt, so this runs out
                logDirect("Only blocks I gave up on for a bit are left, trying them again");
                // the logMissing pass just wrote it, but this isn't a pause
                missingAtPause = Map.of();
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            if (endPassing()) {
                // after the given up ones get their retry, the dug out cells we were walking past are other work too
                missingAtPause = Map.of();
                incorrectPositions = null;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            if (Baritone.settings().skipFailedLayers.value && Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < realSchematic.heightY()) {
                logDirect("Skipping layer that I cannot construct! Layer #" + layer);
                // not a pause, so nothing is missing as far as missingAtPause is concerned
                missingAtPause = Map.of();
                forgetStalls();
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            // a build that ends paused (a few cells given up on for good) still takes its scaffold down. not for a
            // materials pause, more blocks are on the way and the pillars are how we'll get them in
            if (missingAtPause.isEmpty() && cleanupStep()) {
                incorrectPositions = null;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
            logWhyUnable(bcc);
            paused = true;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        // null with a candidate, the click branch had its chance and the gate is what's holding it
        List<BetterBlockPos> plannedHere = toPlace.isPresent() ? null : plannedHere(bcc, ctx.playerFeet());
        if (plannedHere != null && !plannedHere.isEmpty() && centre(bcc, goal, interactionPaused, isSafeToCancel)) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (stallCheck(bcc, goal, interactionPaused, isSafeToCancel, plannedHere)) {
            // the cells that parked us here are out of the goal now, so build it again without them
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
    }

    // the planner only promised the click from within EYE_SLOP of the middle of its spot, and the traverse calls it done
    // the moment our feet are in the block, then we coast to a stop wherever. so before calling a planned spot a stare,
    // shuffle to the middle of it. sneaking only next to a drop (nearDrop), so the edge of a roof stays where it is. true
    // while we're doing that
    private boolean centre(BuilderCalculationContext bcc, Goal goal, boolean interactionPaused, boolean isSafeToCancel) {
        BetterBlockPos feet = ctx.playerFeet();
        if (!feet.equals(centreFeet)) {
            centreFeet = feet;
            centreTicks = 0;
        }
        if (interactionPaused || busyElsewhere() || !isSafeToCancel || !ctx.player().onGround() || !goal.isInGoal(feet)
                || centreTicks >= CENTRE_TICKS) {
            return false;
        }
        Vec3 pos = ctx.player().position();
        double dx = feet.x + 0.5 - pos.x;
        double dz = feet.z + 0.5 - pos.z;
        if (centred(dx, dz)) {
            return false;
        }
        centreTicks++;
        stallTicks = 0;
        dropPathBeforeClicking();
        // keys relative to where we look, so the aim stays put for the click that comes after
        double yaw = Math.toRadians(ctx.player().getYRot());
        double forward = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;
        double left = Math.cos(yaw) * dx + Math.sin(yaw) * dz;
        InputOverrideHandler keys = baritone.getInputOverrideHandler();
        if (nearDrop(bcc, feet, pos)) {
            forceSneak();
        }
        keysForced = true; // onLostControl lets go of the walking keys below
        keys.setInputForceState(forward > 0 ? Input.MOVE_FORWARD : Input.MOVE_BACK, Math.abs(forward) > CENTRE_TOLERANCE / 2);
        keys.setInputForceState(left > 0 ? Input.MOVE_LEFT : Input.MOVE_RIGHT, Math.abs(left) > CENTRE_TOLERANCE / 2);
        return true;
    }

    static boolean centred(double dx, double dz) {
        return Math.abs(dx) <= CENTRE_TOLERANCE && Math.abs(dz) <= CENTRE_TOLERANCE;
    }

    // nothing under our own cell (hanging on by the edge of the next one), or a side with a drop past it closer than
    // DROP_MARGIN. a shuffle that overshoots by a bit is fine anywhere else, so no crouch for it
    private boolean nearDrop(BuilderCalculationContext bcc, BetterBlockPos feet, Vec3 pos) {
        if (!MovementHelper.canWalkOn(bcc.bsi, feet.x, feet.y - 1, feet.z)) {
            return true;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int x = feet.x + d.getStepX();
            int z = feet.z + d.getStepZ();
            if (edgeDistance(pos.x - feet.x, pos.z - feet.z, d) < DROP_MARGIN
                    && MovementHelper.canWalkThrough(bcc.bsi, x, feet.y, z) && !MovementHelper.canWalkOn(bcc.bsi, x, feet.y - 1, z)) {
                return true;
            }
        }
        return false;
    }

    // how far the middle of us is from our cell's side towards d, fx and fz being where we are inside the cell
    static double edgeDistance(double fx, double fz, Direction d) {
        return switch (d) {
            case EAST -> 1 - fx;
            case WEST -> fx;
            case SOUTH -> 1 - fz;
            case NORTH -> fz;
            default -> Double.MAX_VALUE;
        };
    }

    // the cells still waiting for a click that the planner said to stand right here for. same match as plannedFromHere
    private List<BetterBlockPos> plannedHere(BuilderCalculationContext bcc, BetterBlockPos feet) {
        if (plans == null || plans.isEmpty()) {
            return List.of();
        }
        List<BetterBlockPos> cells = new ArrayList<>();
        for (Long2ObjectMap.Entry<CellPlan> e : plans.long2ObjectEntrySet()) {
            for (PlacementPlanner.Feet f : e.getValue().feet) {
                if (f.feet().equals(feet)) {
                    BetterBlockPos cell = BetterBlockPos.deserializeFromLong(e.getLongKey());
                    if (wantsPlace(bcc, feet, cell.x, cell.y, cell.z) != null) {
                        cells.add(cell);
                    }
                    break;
                }
            }
        }
        return cells;
    }

    // on the ground, or hanging off a ladder with the sneak in (onTick holds it), so nothing slides or bobs under the click
    private boolean steady() {
        return ctx.player().onGround() || (hanging() && ctx.player().isCrouching());
    }

    // on a ladder or a vine, where holding sneak keeps us put. scaffolding is climbable too but a sneak in it is the way
    // down (same exception as PlacementPlanner.hangable)
    private boolean hanging() {
        LocalPlayer player = ctx.player();
        return player.onClimbable() && !player.getAbilities().flying && !player.getInBlockState().is(Blocks.SCAFFOLDING);
    }

    // upstream's goal composites are happy if any member is. a member that's happy with where we stand while nothing
    // around here is clickable means the pather never moves us and nothing else does either: the stare. true when this
    // tick gave some cells up and the goal needs building again
    private boolean stallCheck(BuilderCalculationContext bcc, Goal goal, boolean interactionPaused, boolean isSafeToCancel, List<BetterBlockPos> plannedHere) {
        BetterBlockPos feet = ctx.playerFeet();
        if (interactionPaused || busyElsewhere()) {
            stallTicks = 0;
            return false;
        }
        if (!goal.isInGoal(feet)) {
            stallTicks = 0; // not there yet, getting there is the watchdog's business
            return false;
        }
        if (!isSafeToCancel || !steady()) {
            // walking, swimming, mid movement, told not to click, or something is happening that just isn't a click.
            // the click branches need safe to cancel and steady footing too, so none of that is a stare
            stallTicks = 0;
            return false;
        }
        if (!baritone.getInputOverrideHandler().getBlockPlaceHelper().ready()) {
            return false; // a click just went out and may not be in the world yet, that's not a stare
        }
        int limit = quickGiveUp(bcc, feet, plannedHere) ? NOTHING_TICKS : STALL_TICKS;
        if (!stalled(limit)) {
            return false;
        }
        List<BetterBlockPos> owners = new ArrayList<>();
        for (Pair<BetterBlockPos, Goal> owner : goalOwners) {
            BetterBlockPos pos = owner.first();
            // a stale plan's old feet: the new look may well say stand somewhere else, so it's not ours to give up yet.
            // planOrder puts the one we're parked for first, so this is a tick or two
            // once the wait ran out it's upstream's goal like any other, and gets given up like any other
            if (!owner.second().isInGoal(feet)) {
                continue;
            }
            BlockState current = bcc.bsi.get0(pos.x, pos.y, pos.z);
            // same test assemble sorts placeable by
            boolean placing = current.getBlock() instanceof AirBlock;
            if (!heldForPlan(placing, pendingWaitedOut, () -> replanPending(pos, bcc.getSchematic(pos.x, pos.y, pos.z, current)))) {
                owners.add(pos);
            }
        }
        if (owners.isEmpty()) {
            return false;
        }
        giveUpOn(bcc, owners, "Stood in the goal with nothing to click", limit);
        return true;
    }

    // whether stallCheck leaves an owner alone because the planner still owes it a look. only place cells ever get a
    // plan, and replanPending says yes to any planless cell that doesn't want a full cube. stone where the file wants a
    // stair has to be mined first, so asking about that one was a yes forever and the break never got given up
    static boolean heldForPlan(boolean placing, boolean waitedOut, BooleanSupplier replanPending) {
        return placing && !waitedOut && replanPending.getAsBoolean();
    }

    // stuck, whatever the reason: a goal the pather can't reach, a move that keeps failing, a click that never lands, a
    // loop. stallCheck only covers standing in the goal and the click branches return before it, so this one counts
    // every tick and only trusts progress (WatchdogProgress): more of the build right than ever, or closer to where the
    // path goes than ever. moving, digging and blocks going in and out don't count, a loop does all of those forever
    private void watchdog(BuilderCalculationContext bcc) {
        Optional<IPath> path = baritone.getPathingBehavior().getPath();
        path.ifPresent(p -> lastPathDest = p.getDest());
        snapshotPath(path);
        BetterBlockPos feet = ctx.playerFeet();
        // scaffold coming down is the cleanup's progress, and it doesn't show up in observedCompleted outside the box.
        // only then: a path digging out a throwaway and putting it back would count as progress every time
        long done = (observedCompleted == null ? 0 : observedCompleted.size()) + (cleanStage != CleanStage.NONE ? ledger.cleared() : 0);
        float destroy = ctx.minecraft().gameMode instanceof IClientBlockBreak gameMode ? gameMode.getCurrentBreakingProgress() : 0;
        boolean digGoing = builderDig && destroy > lastDestroyProgress;
        lastDestroyProgress = destroy;
        if (watchdogProgress.progressed(done, lastPathDest, feet)) {
            watchdogTicks = 0;
            digHeldTicks = 0;
            return;
        }
        if (digGoing && ++digHeldTicks <= DIG_HOLD_TICKS) {
            // our own dig getting further holds the count, it doesn't reset it. obsidian by hand is longer than the
            // whole watchdog, and a wrong block that keeps going back in wrong still runs out between the digs
            return;
        }
        if (++watchdogTicks < WATCHDOG_TICKS) {
            return;
        }
        watchdogTicks = 0;
        List<BetterBlockPos> owners = watchdogOwners(goalOwners, lastPathDest, feet, FAR_STALL_CELLS);
        if (!owners.isEmpty()) {
            giveUpOn(bcc, owners, "Stuck short of the goal", WATCHDOG_TICKS);
        }
    }

    // the cells the path walks through, ours and the two over each (head, and the jump). PathingBehavior drops a path
    // the moment it's done, so this keeps the last one till the next shows up: "the path that just finished"
    private void snapshotPath(Optional<IPath> path) {
        if (path.isEmpty() || path.get() == pathCellsOf) {
            return;
        }
        pathCellsOf = path.get();
        LongOpenHashSet cells = new LongOpenHashSet();
        for (BetterBlockPos pos : path.get().positions()) {
            for (int dy = 0; dy <= 2; dy++) {
                cells.add(BetterBlockPos.serializeToLong(pos.x, pos.y + dy, pos.z));
            }
        }
        pathCells = cells;
    }

    // a pause, a resume, a new build. the old bests were about a different situation
    private void resetWatchdog() {
        watchdogTicks = 0;
        lastPathDest = null;
        digHeldTicks = 0;
        if (watchdogProgress == null) {
            watchdogProgress = new WatchdogProgress(); // same as the ledger in start
        } else {
            watchdogProgress.reset();
        }
    }

    // the cells whose goal the path was headed for, same filter as stallCheck but at the path's end instead of our feet.
    // none of those (a partial path, which is most of what a stuck bot has) means the nearest few by their own
    // heuristic, from the path's end if there is one: that's as close as the pather thought it could get
    static List<BetterBlockPos> watchdogOwners(List<Pair<BetterBlockPos, Goal>> goalOwners, BetterBlockPos dest, BetterBlockPos feet, int fallback) {
        List<BetterBlockPos> owners = new ArrayList<>();
        if (dest != null) {
            for (Pair<BetterBlockPos, Goal> owner : goalOwners) {
                if (owner.second().isInGoal(dest)) {
                    owners.add(owner.first());
                }
            }
        }
        if (!owners.isEmpty()) {
            return owners;
        }
        BetterBlockPos from = dest != null ? dest : feet;
        return goalOwners.stream()
                .sorted(Comparator.comparingDouble(owner -> owner.second().heuristic(from)))
                .limit(fallback)
                .map(Pair::first)
                .collect(Collectors.toList());
    }

    // centred in a planned spot (or done trying), nothing aimed for a click lately, and the scan has nothing at all from
    // here. two seconds of that is two seconds of the same answer, the world isn't changing under us. a candidate whose
    // aim hasn't settled yet still gets the full STALL_TICKS, and so does anything that clears up by itself: a mob in the
    // cell, or the item not on the hotbar yet
    private boolean quickGiveUp(BuilderCalculationContext bcc, BetterBlockPos feet, List<BetterBlockPos> plannedHere) {
        if (plannedHere == null || !ctx.player().onGround() || builderTicks - lastPlaceAim <= NOTHING_TICKS) {
            return false;
        }
        if (plannedHere.isEmpty()) {
            return nothingFromUpstream(bcc, feet);
        }
        Vec3 pos = ctx.player().position();
        if (!(feet.equals(centreFeet) && centreTicks >= CENTRE_TICKS) && !centred(feet.x + 0.5 - pos.x, feet.z + 0.5 - pos.z)) {
            return false;
        }
        for (BetterBlockPos cell : plannedHere) {
            BlockState desired = bcc.getSchematic(cell.x, cell.y, cell.z, bcc.bsi.get0(cell));
            if (desired == null || !placementPlausible(cell, desired) || !nothingToWaitFor(desired)) {
                return false;
            }
        }
        return true;
    }

    // the same two seconds of the same answer, in one of upstream's goals instead (no plan, just somewhere next to the
    // cell). those never got the quick give up, so a log that wanted a side with nothing to click on stood its full
    // STALL_TICKS every time round. every cell that parked us here has to be one of those and have no click from here
    private boolean nothingFromUpstream(BuilderCalculationContext bcc, BetterBlockPos feet) {
        if (ticks > 0 && !ctx.player().getAbilities().instabuild) {
            return false; // a dig just let go, the click branch sat this one out
        }
        boolean any = false;
        for (Pair<BetterBlockPos, Goal> owner : goalOwners) {
            if (!owner.second().isInGoal(feet)) {
                continue;
            }
            if (!(owner.second() instanceof GoalAdjacent)) {
                return false; // a break or a planned spot is parked here too, that's not ours to rush
            }
            BetterBlockPos cell = owner.first();
            BlockState current = bcc.bsi.get0(cell);
            BlockState desired = bcc.getSchematic(cell.x, cell.y, cell.z, current);
            if (!(current.getBlock() instanceof AirBlock) || desired == null || !placementPlausible(cell, desired) || !nothingToWaitFor(desired)) {
                return false;
            }
            if (possibleToPlace(bcc, desired, cell.x, cell.y, cell.z, null).isPresent()) {
                return false;
            }
            any = true;
        }
        return any;
    }

    // on the hotbar, or not coming however long we wait (no slot to swap it into). either way waiting won't help
    private boolean nothingToWaitFor(BlockState desired) {
        return onHotbar(desired) || hotbarFull;
    }

    // a hotbar stack that makes it, or creative's give that would
    private boolean onHotbar(BlockState desired) {
        if (virtualItem(desired) != null) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (canSupply(approxPlaceable.get(i), desired)) {
                return true;
            }
        }
        return false;
    }

    private boolean stalled() {
        return stalled(STALL_TICKS);
    }

    // one more tick at the same feet with nothing to show for it. true on the tick that makes it limit
    private boolean stalled(int limit) {
        if (!countStall() || stallTicks < limit) {
            return false;
        }
        stallTicks = 0;
        return true;
    }

    // the counting half of stalled, for a click tick: it counts, but the give up waits for a tick with no click going
    // out, so we don't let go of a cell with our own click on its way into it. false while something else is going on
    private boolean countStall() {
        BetterBlockPos feet = ctx.playerFeet();
        if (!feet.equals(stallFeet)) {
            stallFeet = feet;
            stallTicks = 0;
        }
        if (busyElsewhere()) {
            return false;
        }
        stallTicks++;
        return true;
    }

    // not a click, but not nothing either
    private boolean busyElsewhere() {
        // a creative give isn't on this list: it goes out with its click now, a gate that keeps failing after one is a
        // stall like any other
        return digging || baritone.getInventoryBehavior().movePending();
    }

    private void giveUpOn(BuilderCalculationContext bcc, List<BetterBlockPos> cells, String why) {
        giveUpOn(bcc, cells, why, STALL_TICKS);
    }

    private void giveUpOn(BuilderCalculationContext bcc, List<BetterBlockPos> cells, String why, int stuckTicks) {
        StringBuilder line = new StringBuilder();
        int named = 0;
        int breakTicks = -1;
        for (BetterBlockPos pos : cells) {
            int attempts = givenUp().giveUp(pos, builderTicks);
            forgetStickyGoal(pos.x, pos.y, pos.z);
            if (breakTicks < 0 && attempts < GivenUp.MAX_ATTEMPTS) {
                breakTicks = GivenUp.breakTicks(attempts);
            }
            if (named++ >= 5) {
                continue; // still given up, just not all in one chat line
            }
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(describe(bcc, pos));
            if (attempts >= GivenUp.MAX_ATTEMPTS) {
                line.append(" (for good, till something next to it changes)");
            }
        }
        if (cells.size() > 5) {
            line.append(" and ").append(cells.size() - 5).append(" more");
        }
        String gone = breakTicks < 0 ? "for good" : "for " + breakTicks / 20 + "s";
        logDebug(String.format("%s for %ss, leaving it alone %s: %s", why, stuckTicks / 20.0, gone, line));
    }

    // "stone" for a place, "dirt -> air" for a break, "dirt -> stone" for a swap
    private String describe(BuilderCalculationContext bcc, BetterBlockPos pos) {
        BlockState curr = bcc.bsi.get0(pos.x, pos.y, pos.z);
        BlockState want = bcc.getSchematic(pos.x, pos.y, pos.z, curr);
        String what;
        if (want == null) {
            what = name(curr);
        } else if (curr.getBlock() instanceof AirBlock) {
            what = name(want);
        } else {
            what = name(curr) + " -> " + name(want);
        }
        return what + " at " + pos.x + " " + pos.y + " " + pos.z;
    }

    private static String name(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    private boolean givenUp(int x, int y, int z) {
        return givenUp != null && givenUp.isGivenUp(x, y, z, builderTicks);
    }

    private GivenUp givenUp() {
        if (givenUp == null) {
            givenUp = new GivenUp();
        }
        return givenUp;
    }

    private boolean retryGivenUp() {
        return givenUp != null && givenUp.retryNow(builderTicks);
    }

    // which wipe of the ledger a click goes out under, BlockPlaceHelper stamps every traced click with it
    public int ledgerGeneration() {
        return ledger == null ? 0 : ledger.generation();
    }

    // InputOverrideHandler hands over every traced place that landed (only while we're building), whoever clicked, and
    // the ledger generation the click went out under
    public void placed(BlockPos pos, BlockState now, String who, int generation) {
        if ("builder".equals(who) && landedRight(pos, now)) {
            // the only place that counts as getting somewhere for the stall count. confirmTraced calls anything that
            // changed a landing, a log on the wrong axis included, and that one just gets mined again
            stallTicks = 0;
        }
        // the confirm comes a tick or more after the click. a one block build (altoclef's portal frame, a cell at a
        // time) is the next cell's build by then, and this one landed just outside the new box: scaffold, says the
        // ledger, and the cleanup broke the frame it had just put in. a click from a wiped ledger isn't this build's
        if (ledger != null && ledger.current(generation)) {
            ledgerPlaced(pos, now);
        }
    }

    // what the file wants there, or the lone first half of a chest pair. same answer the bcc's fine gives
    private boolean landedRight(BlockPos pos, BlockState now) {
        BlockState want = placeAt(pos.getX(), pos.getY(), pos.getZ(), now);
        if (want == null) {
            return false; // air or outside the file, a block in there is nothing to be proud of
        }
        if (valid(now, want, false)) {
            return true;
        }
        BlockPos partner = chestPartner(pos, want);
        return partner != null && chestHalfWaiting(now, want, ctx.world().getBlockState(partner), false);
    }

    // and every traced break. ours resets the stall count, a right block somebody else dug out is a detour
    public void broke(BlockPos pos, BlockState before, String who) {
        if ("builder".equals(who)) {
            // a dig that landed. clicking at one that never breaks isn't getting anywhere
            stallTicks = 0;
            return;
        }
        if (before == null || !isActive() || !landedRight(pos, before)) {
            return;
        }
        BetterBlockPos cell = BetterBlockPos.from(pos);
        if (ledger.detour(BetterBlockPos.serializeToLong(cell.x, cell.y, cell.z)) == ScaffoldLedger.Detour.GIVE_UP) {
            // third time the path went through this one. putting it back is just handing it the same block to dig
            givenUp().forGood(cell);
            forgetStickyGoal(cell.x, cell.y, cell.z);
            logDebug(String.format("Paths keep digging out %s at %s %s %s, leaving it out for good", name(before), cell.x, cell.y, cell.z));
        }
    }

    // the file the whole build is about. the layer wrapper says no to every cell above the layer, and a pillar in one
    // of those is still a pillar in a cell that wants air
    private ISchematic wholeSchematic() {
        return realSchematic != null ? realSchematic : schematic;
    }

    // what the file wants at a world cell: null outside the box or in a cut out cell, else the block (air included)
    private BlockState wholeWants(int wx, int wy, int wz, BlockState current) {
        ISchematic full = wholeSchematic();
        int x = wx - origin.getX();
        int y = wy - origin.getY();
        int z = wz - origin.getZ();
        if (x < 0 || y < 0 || z < 0 || x >= full.widthX() || y >= full.heightY() || z >= full.lengthZ() || !full.inSchematic(x, y, z, current)) {
            return null;
        }
        return full.desiredState(x, y, z, current, approxPlaceable == null ? List.of() : approxPlaceable);
    }

    // a block went in where the file wants air, or somewhere near the box the file doesn't cover. who put it there
    // doesn't matter, it's in the way of the build either way and comes down at the end. one where the file wants a
    // real block isn't scaffold, the builder swaps it (besideUs)
    private void ledgerPlaced(BlockPos pos, BlockState now) {
        if (!isActive() || now.isAir()) {
            return;
        }
        BlockState want = wholeWants(pos.getX(), pos.getY(), pos.getZ(), now);
        if (want != null && !(want.getBlock() instanceof AirBlock)) {
            if (!valid(now, want, false)) {
                ledger.solid(BetterBlockPos.serializeToLong(pos.getX(), pos.getY(), pos.getZ()), now.getBlock());
            }
            return;
        }
        ISchematic full = wholeSchematic();
        if (want == null && !ScaffoldLedger.nearBox(pos.getX(), pos.getY(), pos.getZ(), origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + full.widthX() - 1, origin.getY() + full.heightY() - 1, origin.getZ() + full.lengthZ() - 1)) {
            return; // the walk over, not ours
        }
        if (AltoClefSettings.getInstance().shouldAvoidBreaking(pos.getX(), pos.getY(), pos.getZ())) {
            return; // altoclef wants it kept (a portal frame), so it's never cleanup work
        }
        ledger.scaffold(BetterBlockPos.serializeToLong(pos.getX(), pos.getY(), pos.getZ()), now.getBlock());
    }

    // once a tick, before the bcc copies it: scaffold that's gone (or turned into the right block) leaves the ledger,
    // dug out cells that got filled again stop waiting, and the ones we're past rejoin the build
    private void tidyLedger() {
        if (!isActive() || ctx.world() == null) {
            return;
        }
        ledger.world(ctx.world().dimension());
        for (long cell : ledger.scaffold().toLongArray()) {
            tidyScaffold(cell);
        }
        for (long cell : ledger.abandoned().toLongArray()) {
            tidyScaffold(cell);
        }
        for (long cell : ledger.solid().toLongArray()) {
            tidyScaffold(cell);
        }
        for (long cell : ledger.waiting().toLongArray()) {
            BetterBlockPos pos = BetterBlockPos.deserializeFromLong(cell);
            if (baritone.bsi.worldContainsLoadedChunk(pos.x, pos.z)) {
                BlockState now = baritone.bsi.get0(pos.x, pos.y, pos.z);
                BlockState want = wholeWants(pos.x, pos.y, pos.z, now);
                if (want != null && valid(now, want, false)) {
                    ledger.filled(cell);
                }
            }
        }
        BetterBlockPos feet = ctx.playerFeet();
        double reach = ctx.playerController().getBlockReachDistance();
        LongArrayList rejoined = ledger.updatePassing(pathCells::contains,
                cell -> BetterBlockPos.deserializeFromLong(cell).distSqr(feet) <= reach * reach);
        if (!rejoined.isEmpty()) {
            workChanged();
        }
        if (incorrectPositions != null) {
            // recalcNearby only looks around our feet, and a path can carry us well past one before it lets go
            for (long cell : rejoined) {
                incorrectPositions.add(BetterBlockPos.deserializeFromLong(cell));
            }
        }
    }

    private void tidyScaffold(long cell) {
        BetterBlockPos pos = BetterBlockPos.deserializeFromLong(cell);
        if (!baritone.bsi.worldContainsLoadedChunk(pos.x, pos.z)) {
            return; // reads as air till it loads, that's not the block being gone
        }
        BlockState now = baritone.bsi.get0(pos.x, pos.y, pos.z);
        BlockState want = wholeWants(pos.x, pos.y, pos.z, now);
        // water, snow, tall grass: nothing a break gets rid of. somebody else's block: not ours to break. only air or
        // the right block counts as the cleanup getting somewhere
        boolean down = now.getBlock() instanceof AirBlock || (want != null && valid(now, want, false));
        if (down || now.canBeReplaced() || !stillOurs(ledger.what(cell), now)) {
            ledger.gone(cell, down);
            if (cleanCells != null && cleanCells.remove(cell)) {
                // a path that pillars back up the same column puts it back as this round's own, the next round's
                // work. otherwise it's a target again right away and every break looks like progress
                cleanVersion++;
            }
        }
    }

    // by block, not state: leaves change their distance the tick they land, and a dirt pillar top in the light goes
    // grassy
    static boolean stillOurs(Object placed, BlockState now) {
        if (!(placed instanceof Block block) || now.is(block)) {
            return true;
        }
        return block == Blocks.DIRT && (now.is(Blocks.GRASS_BLOCK) || now.is(Blocks.MYCELIUM) || now.is(Blocks.PODZOL));
    }

    // nothing else in the whole build is left (or the rest is given up for good): the scaffold is the work now. in
    // rounds, each takes down what was up when it started (in the box first, then outside), then puts back the dug out
    // cells that waited. a round's own pillars go in the next one. true when the tick should start over with a fresh
    // recalc
    private boolean cleanupStep() {
        switch (cleanStage) {
            case NONE -> {
                if (!ledger.hasScaffold() && !ledger.hasLate()) {
                    return false;
                }
                cleanRound = 1;
                if (ledger.hasScaffold()) {
                    logDirect(String.format("Taking down %s scaffold blocks", ledger.scaffold().size()));
                }
                startRound();
            }
            case SCAFFOLD -> {
                cleanStage = CleanStage.OUTSIDE;
                cleanVersion++;
            }
            case OUTSIDE -> toDetours();
            case DETOUR -> {
                ledger.dropLate(); // put back, or given up on by now. plain cells like any other from here
                // a round's own pillars, or whatever it gave up on for a bit, get the next round
                if (ledger.hasScaffold() && cleanRound < ScaffoldLedger.ROUNDS) {
                    cleanRound++;
                    startRound();
                    return true;
                }
                int left = ledger.abandon();
                if (left > 0) {
                    logDirect(String.format("Left %s scaffold blocks up after %s cleanup rounds", left, cleanRound));
                }
                forgetCleanup();
            }
        }
        return true;
    }

    private void startRound() {
        cleanCells = new LongOpenHashSet(ledger.scaffold());
        cleanStage = CleanStage.SCAFFOLD;
        cleanVersion++;
        workChanged();
    }

    private void toDetours() {
        cleanStage = CleanStage.DETOUR;
        cleanVersion++;
        workChanged();
    }

    // cells that were fine a moment ago are work again: the fresh recalc takes them out of observedCompleted, and the
    // watchdog's best from before would take half the cleanup to beat
    private void workChanged() {
        if (watchdogProgress != null) {
            watchdogProgress.reset();
        }
    }

    // the scaffold the builder can't see. it never makes a goal for those, so this is the goal and the click, nearest
    // column first and top down. null once there's none left
    private PathingCommand cleanOutside(BuilderCalculationContext bcc, boolean interactionPaused, boolean isSafeToCancel) {
        List<BetterBlockPos> targets = cleanTargets(bcc, false);
        if (targets.isEmpty()) {
            toDetours();
            incorrectPositions = null;
            return null;
        }
        if (!interactionPaused && isSafeToCancel && steady()) {
            Optional<Pair<BetterBlockPos, Rotation>> hit = firstReachable(bcc, targets, baritone.getPathingBehavior().pathStart());
            if (hit.isPresent()) {
                return breakAt(bcc, hit.get().first(), hit.get().second());
            }
        }
        BetterBlockPos target = targets.get(0);
        Goal goal = new GoalBreak(target);
        goalOwners = List.of(new Pair<>(target, goal));
        if (stallCheck(bcc, goal, interactionPaused, isSafeToCancel, List.of())) {
            // given up on, the next tick takes the next one
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
    }

    // the break click, for the scan's pick and the cleanup's
    private PathingCommand breakAt(BuilderCalculationContext bcc, BetterBlockPos pos, Rotation rot) {
        // we'd like to pause to break this block
        // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
        dropPathBeforeClicking();
        lastBreak = pos;
        baritone.getLookBehavior().updateTarget(rot, true);
        MovementHelper.switchToBestToolFor(ctx, bcc.get(pos));
        if (ctx.player().isCrouching()) {
            // really horrible bug where a block is visible for breaking while sneaking but not otherwise
            // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
            // and is unable since it's unsneaked in the intermediary tick
            forceSneak();
        }
        if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot)) {
            // no stall reset here, broke() does that once the block is really gone. a wrong block that goes back in
            // wrong every time used to look like progress forever
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            forcedClicks = true;
        } else if (stalled()) {
            // turned towards it for two seconds and never got to look at it
            giveUpOn(bcc, List.of(pos), "Couldn't aim at a block to break");
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    // the bcc's copy of the ledger, made again only when something moved
    private ScaffoldLedger.View ledgerView() {
        if (ledger.version() != ledgerViewVersion || cleanVersion != ledgerViewClean) {
            ledgerView = ledger.view(cleanStage == CleanStage.NONE ? null : cleanCells, cleanStage == CleanStage.DETOUR);
            ledgerViewVersion = ledger.version();
            ledgerViewClean = cleanVersion;
        }
        return ledgerView;
    }

    // the dug out cells we were walking past are the work now
    private boolean endPassing() {
        if (!ledger.endPassing()) {
            return false;
        }
        workChanged();
        return true;
    }

    // altoclef's way to tell a build that got to the end (cleanup and all) from one that got knocked off
    public boolean finished(long serial) {
        return serial != 0 && serial == finishedSerial;
    }

    // upstream held left click on bedrock until somebody cancelled
    private boolean unbreakable(int x, int y, int z, BlockState state) {
        return !ctx.player().hasInfiniteMaterials() && state.getDestroySpeed(ctx.world(), new BlockPos(x, y, z)) < 0;
    }

    // the pause line used to be just "unable to do it". what's left and why, the missing materials already got theirs
    private void logWhyUnable(BuilderCalculationContext bcc) {
        if (!unbreakableCells.isEmpty()) {
            logDirect(String.format("Can't break %s blocks, they don't break:", unbreakableCells.size()));
            logDirect(unbreakableCells.stream().limit(10).map(p -> describe(bcc, p)).collect(Collectors.joining("\n")) + (unbreakableCells.size() > 10 ? "\n..." : ""));
        }
        if (!unpourable.isEmpty()) {
            logDirect(String.format("Left %s fluid sources dry on purpose (water boils off here, or lava would meet water):", unpourable.size()));
            logDirect(unpourable.stream().limit(10).map(p -> describe(bcc, p)).collect(Collectors.joining("\n")) + (unpourable.size() > 10 ? "\n..." : ""));
        }
        if (!dropped.isEmpty()) {
            logDirect(String.format("Can't place %s blocks there yet, nothing to hold them up:", dropped.size()));
            logDirect(dropped.stream().limit(10).map(p -> describe(bcc, p)).collect(Collectors.joining("\n")) + (dropped.size() > 10 ? "\n..." : ""));
        }
        if (givenUp == null || incorrectPositions == null) {
            return;
        }
        List<String> stuck = new ArrayList<>();
        for (BetterBlockPos pos : incorrectPositions) {
            if (givenUp(pos.x, pos.y, pos.z)) {
                stuck.add(describe(bcc, pos));
            }
        }
        if (!stuck.isEmpty()) {
            logDirect(String.format("Gave up on %s blocks after %s tries each, nothing next to them changed since:", stuck.size(), GivenUp.MAX_ATTEMPTS));
            logDirect(String.join("\n", stuck.subList(0, Math.min(stuck.size(), 10))) + (stuck.size() > 10 ? "\n..." : ""));
        }
    }

    // a block near one we gave up on for good changed, so it gets another chance. both the multi block packet event and
    // the nearby scan in recalcNearby land here, the event alone misses single block updates
    private void blockChanged(int x, int y, int z) {
        if (givenUp != null) {
            givenUp.nearbyChanged(x, y, z, builderTicks);
        }
        if (stickyGoals != null && !stickyGoals.isEmpty()) {
            // what the goal looked at: the cell, the faces it could go on and the block over it (allowSameLevel)
            forgetStickyGoal(x, y, z);
            for (Direction d : Direction.values()) {
                forgetStickyGoal(x - d.getStepX(), y - d.getStepY(), z - d.getStepZ());
            }
        }
        if (plans != null && !plans.isEmpty()) {
            // stale, not gone: the old feet stay the goal till the new plan is in, or the goal would flip to upstream's
            // and back and drag the path with it
            for (Long2ObjectMap.Entry<CellPlan> e : plans.long2ObjectEntrySet()) {
                BetterBlockPos cell = BetterBlockPos.deserializeFromLong(e.getLongKey());
                if (Math.abs(cell.x - x) <= PLAN_STALE_RADIUS && Math.abs(cell.y - y) <= PLAN_STALE_RADIUS && Math.abs(cell.z - z) <= PLAN_STALE_RADIUS) {
                    e.getValue().stale = true;
                }
            }
        }
    }

    // one line for the cells that just got left out of the goal, not one per cell per tick. a cell is forgotten once it
    // leaves the dropped set (placed, or it can stay now), not when something next to it goes in: in a build something
    // next to it goes in about every other tick, and every one of those was the same line again
    private void logDropped(BuilderCalculationContext bcc, List<BetterBlockPos> cells) {
        if (droppedLogged == null) {
            if (cells.isEmpty()) {
                return;
            }
            droppedLogged = new Long2LongOpenHashMap();
        }
        if (cells.isEmpty() && droppedLogged.isEmpty()) {
            return;
        }
        LongOpenHashSet now = new LongOpenHashSet(cells.size());
        for (BetterBlockPos pos : cells) {
            now.add(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z));
        }
        droppedLogged.long2LongEntrySet().removeIf(e -> !now.contains(e.getLongKey()) && builderTicks - e.getLongValue() >= DROPPED_LOG_FLOOR_TICKS);
        List<String> fresh = new ArrayList<>();
        for (BetterBlockPos pos : cells) {
            long key = BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z);
            if (!droppedLogged.containsKey(key)) {
                droppedLogged.put(key, builderTicks);
                fresh.add(describe(bcc, pos));
            }
        }
        if (!fresh.isEmpty()) {
            logDebug("Can't place these yet, nothing to hold them up, leaving them out till they would stay: "
                    + String.join(", ", fresh.subList(0, Math.min(fresh.size(), 5))) + (fresh.size() > 5 ? " and " + (fresh.size() - 5) + " more" : ""));
        }
    }

    // the item creative would hand itself for this state, null if there's nothing that places it. block items, plus the
    // bucket for a water or lava source. an item that comes out as a different block (kelp for a kelp plant) would just
    // be a goal the stall check has to burn down, those stay missing and the pause names them. the torch item for a
    // wall torch is fine though, the planner asks the item and gets the wall one out of it
    static Item creativeItem(BlockState desired) {
        if (desired.getBlock() instanceof LiquidBlock) {
            return bucketFor(desired); // flowing ones are the source's business
        }
        Item vine = vineItem(desired.getBlock());
        if (vine != null) {
            return vine;
        }
        Item item = desired.getBlock().asItem();
        return item instanceof BlockItem block && (block.getBlock() == desired.getBlock() || item instanceof StandingAndWallBlockItem) ? item : null;
    }

    private long[] slotUsed() {
        if (slotUsed == null) {
            slotUsed = new long[9];
        }
        return slotUsed;
    }

    // one stack of the first throwaway on the hotbar during a creative build, or every pillar and bridge costs INF
    // (hasThrowaway). checked every tick and not just once: if anything ever does take it off the hotbar, the next pillar
    // shouldn't be the one to find out. creativeSlot never hands its slot out
    private void creativeThrowaway() {
        List<Item> acceptable = Baritone.settings().acceptableThrowawayItems.value;
        if (acceptable.isEmpty()) {
            return;
        }
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        if (throwawaySlot >= 0 && acceptable.contains(inv.get(throwawaySlot).getItem())) {
            return;
        }
        throwawaySlot = -1;
        for (int i = 0; i < 9; i++) {
            if (acceptable.contains(inv.get(i).getItem())) {
                throwawaySlot = i;
                return;
            }
        }
        // a hotbar of tools and food has no slot for it yet, so this asks again in a bit instead of never. and not every
        // tick, a server that eats creative gives would get one a tick forever
        if (builderTicks - throwawayAt < THROWAWAY_AGAIN_TICKS) {
            return;
        }
        int slot = creativeSlot();
        if (slot >= 0) {
            give(acceptable.get(0), slot);
            throwawaySlot = slot;
            throwawayAt = builderTicks;
        }
    }

    // a movement on our own path wants this cell's block put down and it isn't on the hotbar. creative hands it over the
    // same way as one of our clicks, one give into creativeSlot, so the movement's place goes in. costOfPlacingAt counted
    // on this (creativeGives), it has to say yes whenever that did
    public boolean creativeHand(BlockState desired, boolean select) {
        if (!isActive() || !ctx.player().hasInfiniteMaterials() || !creativeMovementOk(desired)) {
            return false;
        }
        Item item = creativeItem(desired);
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            if (inv.get(i).is(item)) {
                // already here and the movement's own lookup turned it down anyway (altoclef saving it, say). handing
                // over another one every tick would just eat the hotbar
                if (select) {
                    ctx.player().getInventory().setSelectedSlot(i);
                    slotUsed()[i] = builderTicks;
                }
                return true;
            }
        }
        int slot = creativeSlot();
        if (slot < 0) {
            return false;
        }
        if (select) {
            give(item, slot);
            ctx.player().getInventory().setSelectedSlot(slot);
        }
        return true;
    }

    // a block item that comes out as this very block, so the movement's same block check finds it once it's handed over.
    // a torch for a wall torch would be handed over again every tick and never match
    static boolean movementPlaceable(BlockState desired) {
        return creativeItem(desired) instanceof BlockItem block && block.getBlock() == desired.getBlock();
    }

    // movementPlaceable, and the state the item puts down is one valid() takes. a movement has no place gate, so leaves the
    // file wants decaying (the item places them persistent) or snowy grass would go in wrong, get mined and get priced
    // at 0 again. placed off our feet facing up, same as approxPlaceable, so it's the same guess survival makes. game thread
    private boolean creativeMovementOk(BlockState desired) {
        Map<BlockState, Boolean> cache = movementOk;
        if (cache == null) {
            cache = new ConcurrentHashMap<>();
            movementOk = cache;
        }
        Boolean ok = cache.get(desired);
        if (ok == null) {
            ok = movementPlaceable(desired) && placesAsIs(desired);
            cache.put(desired, ok);
        }
        return ok;
    }

    private boolean placesAsIs(BlockState desired) {
        ItemStack stack = new ItemStack(creativeItem(desired));
        BlockState placed = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(),
                InteractionHand.MAIN_HAND, stack, new BlockHitResult(ctx.player().position(), Direction.UP, ctx.playerFeet(), false)) {}));
        return placed != null && valid(placed, desired, false);
    }

    // the path thread's half: only what the game thread already worked out, an unknown state isn't free yet
    private boolean knownMovementOk(BlockState desired) {
        Map<BlockState, Boolean> cache = movementOk;
        return cache != null && Boolean.TRUE.equals(cache.get(desired));
    }

    private int creativeSlot() {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        boolean[] empty = new boolean[9];
        boolean[] block = new boolean[9];
        for (int i = 0; i < 9; i++) {
            empty[i] = inv.get(i).isEmpty();
            // a bucket counts too: in creative it's never emptied and the next pour gets handed another, so the ones we
            // gave for pours don't sit on the hotbar for good
            block[i] = inv.get(i).getItem() instanceof BlockItem || pours(inv.get(i)) != null;
        }
        return creativeSlot(empty, block, slotUsed(), ctx.player().getInventory().getSelectedSlot(), throwawaySlot);
    }

    // where a give goes: an empty slot, else the block that's gone longest without a click, never the one in hand or the
    // throwaway. tools and food stay put, those are the player's. -1 if every slot is spoken for. only ever asked
    // for a click that's going out now, so a build with twenty kinds of block in reach still gives once per new block
    // clicked and not once a tick
    static int creativeSlot(boolean[] empty, boolean[] block, long[] used, int selected, int throwaway) {
        for (int i = 0; i < empty.length; i++) {
            if (empty[i]) {
                return i;
            }
        }
        int best = -1;
        for (int i = 0; i < block.length; i++) {
            if (i == selected || i == throwaway || !block[i]) {
                continue;
            }
            if (best < 0 || used[i] < used[best]) {
                best = i;
            }
        }
        return best;
    }

    // the server doesn't send the slot back after a creative set (checked), so the local slot gets written too and
    // nothing waits for a sync
    private void give(Item item, int slot) {
        ItemStack stack = new ItemStack(item, item.getDefaultMaxStackSize());
        ctx.player().getInventory().setItem(slot, stack);
        // a copy for the packet, it gets encoded off thread and the one in the slot is ours to change
        ctx.minecraft().gameMode.handleCreativeModeItemAdd(stack.copy(), 36 + slot);
        slotUsed()[slot] = builderTicks;
    }

    // what the planner said about a cell. stale = something near it changed and it's due a new look
    static final class CellPlan {
        final BlockState desired;
        final List<PlacementPlanner.Feet> feet;
        boolean stale;

        CellPlan(BlockState desired, List<PlacementPlanner.Feet> feet) {
            this.desired = desired;
            this.feet = feet;
        }
    }

    // wall vs standing comes off the pitch, not the face we click, so a standing torch right next to a wall wants us
    // looking down steep. if those never get a plan this is where it shows. once per cell, replans would spam it
    private void logNoTorchPlan(BetterBlockPos pos, BlockState desired) {
        if (torchNoPlanLogged == null) {
            torchNoPlanLogged = new LongOpenHashSet();
        }
        if (torchNoPlanLogged.add(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z))) {
            logDebug(String.format("No spot in reach to place %s at %s", desired, pos));
        }
    }

    // plans the nearest cells that don't have one (or have a stale one) yet, up to this tick's budget
    private void planCells(BuilderCalculationContext bcc, List<BetterBlockPos> cells) {
        if (planBudget <= 0 || cells.isEmpty()) {
            return;
        }
        if (plans == null) {
            plans = new Long2ObjectOpenHashMap<>();
        }
        List<BetterBlockPos> todo = new ArrayList<>();
        for (BetterBlockPos pos : cells) {
            if (!bcc.bsi.worldContainsLoadedChunk(pos.x, pos.z)) {
                continue; // it'd be planned against air, and nothing tells us when the chunk shows up
            }
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z));
            CellPlan plan = plans.get(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z));
            if (plan == null || plan.stale || plan.desired != desired) {
                todo.add(pos);
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        BetterBlockPos feet = ctx.playerFeet();
        // every place stales the plans five blocks round it, so near the action the stale ones never run out. nearest
        // first alone starved the ones with no goal at all behind them, see planOrder
        Map<BetterBlockPos, Integer> order = new HashMap<>();
        for (BetterBlockPos pos : todo) {
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z));
            order.put(pos, planOrder(plans.get(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z)), desired, feet));
        }
        todo.sort(Comparator.<BetterBlockPos>comparingInt(order::get).thenComparingDouble(p -> p.distSqr(feet)));
        for (BetterBlockPos pos : todo) {
            if (planBudget-- <= 0) {
                return;
            }
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z));
            List<PlacementPlanner.Feet> found = planner().planFeet(pos, desired, bcc.bsi, bcc::getSchematic);
            if (found.isEmpty() && desired.getBlock().asItem() instanceof StandingAndWallBlockItem) {
                logNoTorchPlan(pos, desired);
            }
            plans.put(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z), new CellPlan(desired, found));
            forgetStickyGoal(pos.x, pos.y, pos.z);
        }
    }

    // 0: stale and we're standing on one of its old feet, it's the one we're parked for. 1: no goal at all till it's
    // planned (placementGoal leaves it out). 2: stale, but its old feet still do as a goal meanwhile
    static int planOrder(CellPlan plan, BlockState desired, BlockPos feet) {
        if (plan == null || plan.desired != desired || plan.feet.isEmpty()) {
            return 1;
        }
        for (PlacementPlanner.Feet f : plan.feet) {
            if (f.feet().equals(feet)) {
                return 0;
            }
        }
        return 2;
    }

    // the planner had a fresh look and found nowhere at all a click comes out right from, with the world as it is now (a
    // log on its side with nothing on that side to click). upstream's goal for one of those was somewhere to stand and
    // stare. so it sits out like the dropped ones till something near it changes: blockChanged stales the plan and
    // planCells has another go. assemble hands them upstream's goal anyway once they're all that's left
    private boolean waitsOnNeighbours(BuilderCalculationContext bcc, BetterBlockPos pos) {
        if (plans == null || !bcc.bsi.worldContainsLoadedChunk(pos.x, pos.z)) {
            return false;
        }
        CellPlan plan = plans.get(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z));
        return plan != null && plan.feet.isEmpty() && !plan.stale && plan.desired == bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos));
    }

    // a cell is waiting on the planner: aimed, and either never planned for what it wants now or due a new look
    private boolean replanPending(BetterBlockPos pos, BlockState desired) {
        if (desired == null || PlaceRules.aimProof(desired)) {
            return false;
        }
        CellPlan plan = plans == null ? null : plans.get(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z));
        return plan == null || plan.stale || plan.desired != desired;
    }

    private void forgetStickyGoal(int x, int y, int z) {
        if (stickyGoals != null) {
            stickyGoals.remove(BetterBlockPos.serializeToLong(x, y, z));
        }
    }

    // the old plans of cells that got placed (or stopped being wanted) only cost memory, so clean up now and then
    private void prunePlans() {
        if (plans != null && plans.size() > 2048 && incorrectPositions != null) {
            plans.long2ObjectEntrySet().removeIf(e -> !incorrectPositions.contains(BetterBlockPos.deserializeFromLong(e.getLongKey())));
        }
        if (stickyGoals != null && stickyGoals.size() > 2048 && incorrectPositions != null) {
            stickyGoals.long2ObjectEntrySet().removeIf(e -> !incorrectPositions.contains(BetterBlockPos.deserializeFromLong(e.getLongKey())));
        }
    }

    // PathingControlManager cancels the segment after the process tick, and that cancel clears every key, so the sneak
    // and the click of the first tick after a walk never went out. cancel it ourselves first and keep what we press
    private void dropPathBeforeClicking() {
        if (baritone.getPathingBehavior().getCurrent() != null) {
            baritone.getPathingBehavior().cancelSegmentIfSafe();
        }
    }

    private boolean recalc(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) {
            incorrectPositions = new HashSet<>();
            fullRecalc(bcc);
            if (incorrectPositions.isEmpty()) {
                return false;
            }
        }
        recalcNearby(bcc);
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        return !incorrectPositions.isEmpty();
    }

    // distanceTrim used to delete everything past this from incorrectPositions. only recalcNearby's little box ever put
    // cells back, so anything we walked away from half done was gone for good. now the set keeps them and only the goal
    // is trimmed
    static final double NEAR_DIST_SQ = 200;
    // the far cells handed to the goal when nothing near makes one. a handful, GoalComposite.heuristic loops every
    // member for every node A* opens, so the whole far set would be a very slow way to walk across a build
    static final int FAR_FALLBACK_CELLS = 48;

    // the goal off the cells near us, and if those make nothing (all given up, missing, waiting), off the nearest far
    // ones too. before the retry / release / skip / pause branch, those are for when there's really nothing left
    private Goal goalNearFirst(BuilderCalculationContext bcc) {
        plansPending = false;
        pendingSeen = false;
        boolean trim = Baritone.settings().distanceTrim.value;
        Collection<BetterBlockPos> near = trim ? nearCells(incorrectPositions, ctx.player().blockPosition(), NEAR_DIST_SQ) : incorrectPositions;
        boolean everything = near.size() == incorrectPositions.size();
        Goal goal = assembleEitherInventory(bcc, near, everything);
        if (goal != null || plansPending || everything) {
            return goal;
        }
        List<BetterBlockPos> cells = new ArrayList<>(near);
        cells.addAll(farFallback(bcc));
        // near goes in again with them so the pause line (missing, dropped, unbreakable) still covers everything
        return assembleEitherInventory(bcc, cells, true);
    }

    private Goal assembleEitherInventory(BuilderCalculationContext bcc, Collection<BetterBlockPos> cells, boolean logMissing) {
        Goal goal = assemble(bcc, approxPlaceable.subList(0, 9), cells, false);
        if (goal != null) {
            return goal;
        }
        // we're far away, so assume that we have our whole inventory to recalculate placeable properly
        return assemble(bcc, approxPlaceable, cells, logMissing);
    }

    // a copy, assemble takes cells out of incorrectPositions while it walks its list
    static List<BetterBlockPos> nearCells(Collection<BetterBlockPos> all, BlockPos player, double maxDistSqr) {
        List<BetterBlockPos> out = new ArrayList<>();
        for (BetterBlockPos pos : all) {
            if (pos.distSqr(player) <= maxDistSqr) {
                out.add(pos);
            }
        }
        return out;
    }

    // the k nearest past nearDistSqr, nearest first. a bounded max heap, the set can be the whole build
    static List<BetterBlockPos> nearestFar(Collection<BetterBlockPos> all, BlockPos player, double nearDistSqr, int k) {
        PriorityQueue<BetterBlockPos> heap = new PriorityQueue<>(k + 1, Comparator.comparingDouble((BetterBlockPos p) -> p.distSqr(player)).reversed());
        for (BetterBlockPos pos : all) {
            double d = pos.distSqr(player);
            if (d <= nearDistSqr) {
                continue;
            }
            if (heap.size() < k) {
                heap.add(pos);
            } else if (d < heap.peek().distSqr(player)) {
                heap.poll();
                heap.add(pos);
            }
        }
        List<BetterBlockPos> out = new ArrayList<>(heap);
        out.sort(Comparator.comparingDouble(p -> p.distSqr(player)));
        return out;
    }

    // the far ones are only rescanned when we walk past, so one somebody else finished since would read as a wrong block
    // to break (assemble takes everything in the set as wrong). a look at these few first, loaded chunks only
    private List<BetterBlockPos> farFallback(BuilderCalculationContext bcc) {
        List<BetterBlockPos> out = new ArrayList<>();
        for (BetterBlockPos pos : nearestFar(incorrectPositions, ctx.player().blockPosition(), NEAR_DIST_SQ, FAR_FALLBACK_CELLS)) {
            if (bcc.bsi.worldContainsLoadedChunk(pos.x, pos.z)) {
                BlockState current = bcc.bsi.get0(pos.x, pos.y, pos.z);
                BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, current);
                if (desired != null && bcc.fine(pos.x, pos.y, pos.z, current, desired)) {
                    incorrectPositions.remove(pos);
                    observedCompleted.add(BetterBlockPos.longHash(pos));
                    continue;
                }
            }
            out.add(pos);
        }
        return out;
    }

    private void recalcNearby(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        int radius = Baritone.settings().builderTickScanRadius.value;
        if (ctx.world() != seenWorld) {
            // another dimension or a reconnect. the same coordinates are somewhere else now, so none of it means anything
            forgetStalls();
            seenWorld = ctx.world();
        }
        if (seen == null || seen.size() > 100_000) {
            seen = new Long2ObjectOpenHashMap<>(); // a long walk across a big build, forgetting is cheaper than trimming
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    // states are interned, so != is a change. a cell we never saw before isn't one, and an unloaded
                    // chunk reads as air, which isn't one either
                    if (bcc.bsi.worldContainsLoadedChunk(x, z)) {
                        BlockState now = bcc.bsi.get0(x, y, z);
                        BlockState before = seen.put(BetterBlockPos.serializeToLong(x, y, z), now);
                        if (before != null && before != now) {
                            blockChanged(x, y, z);
                        }
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (bcc.fine(x, y, z, bcc.bsi.get0(x, y, z), desired)) {
                            incorrectPositions.remove(pos);
                            observedCompleted.add(BetterBlockPos.longHash(pos));
                        } else {
                            incorrectPositions.add(pos);
                            observedCompleted.remove(BetterBlockPos.longHash(pos));
                        }
                    }
                }
            }
        }
    }

    private void fullRecalc(BuilderCalculationContext bcc) {
        incorrectPositions = new HashSet<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        // kept counts before the incorrectSize bail, a site full of pillars would hit the cap otherwise
                        if (bcc.fine(blockX, blockY, blockZ, current, schematic.desiredState(x, y, z, current, this.approxPlaceable))) {
                            observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
                        } else {
                            incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                            observedCompleted.remove(BetterBlockPos.longHash(blockX, blockY, blockZ));
                            if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                                return;
                            }
                        }
                        continue;
                    }
                    // this is not in render distance
                    if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        // and we've never seen this position be correct
                        // therefore mark as incorrect
                        incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                        if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, Collection<BetterBlockPos> cells, boolean logMissing) {
        // with nowhere to hand a block over, possibleToPlace and costOfPlacingAt say no too, so the goal shouldn't say yes
        boolean creative = ctx.player().hasInfiniteMaterials() && creativeSlot() >= 0;
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        Map<BlockState, Integer> missing = new HashMap<>();
        List<BetterBlockPos> outOfBounds = new ArrayList<>();
        List<BetterBlockPos> pourCells = new ArrayList<>();
        List<BetterBlockPos> ledgerFine = new ArrayList<>();
        cells.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            if (bcc.ledgerFine(pos.x, pos.y, pos.z, state)) {
                // went into the ledger after the scan put it here, recalcNearby only sees our end of the build
                ledgerFine.add(pos);
                return;
            }
            if (pourCell(bcc, pos, state)) {
                pourCells.add(pos);
                return;
            }
            if (state.getBlock() instanceof AirBlock) {
                BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
                if (desired == null) {
                    outOfBounds.add(pos);
                } else if (containsBlockState(approxPlaceable, desired) || (!PlaceRules.aimProof(desired) && containsItem(approxPlaceable, desired))
                        || (creative && creativeItem(desired) != null)) {
                    if (creative) {
                        creativeMovementOk(desired); // fills the path thread's answer for costOfPlacingAt
                    }
                    // creative hands itself the block when it gets there (see creativeSlot), so it's never missing
                    // the states in approxPlaceable are what each stack places with us facing wherever we face right
                    // now, so a stack of stairs only counted for the one facing. an aimed block gets planned (see
                    // planCells) and the click gate checks the real state, so for those owning the item is enough
                    placeable.add(pos);
                } else {
                    missing.put(desired, 1 + missing.getOrDefault(desired, 0));
                }
            } else {
                if (state.getBlock() instanceof LiquidBlock) {
                    // if the block itself is JUST a liquid (i.e. not just a waterlogged block), we CANNOT break it
                    // TODO for 1.13 make sure that this only matches pure water, not waterlogged blocks
                    if (!MovementHelper.possiblyFlowing(state)) {
                        // if it's a source block then we want to replace it with a throwaway
                        sourceLiquids.add(pos);
                    } else {
                        flowingLiquids.add(pos);
                    }
                } else {
                    breakable.add(pos);
                }
            }
        });
        incorrectPositions.removeAll(outOfBounds);
        incorrectPositions.removeAll(ledgerFine);
        List<Pair<BetterBlockPos, Goal>> owners = new ArrayList<>();
        List<Goal> toBreak = new ArrayList<>();
        List<BetterBlockPos> cantBreak = new ArrayList<>();
        breakable.forEach(pos -> {
            if (unbreakable(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z))) {
                cantBreak.add(pos);
            } else if (!givenUp(pos.x, pos.y, pos.z)) {
                // our own scaffold in a real block's cell gets broken from a step away, every time and not only while
                // we're next to it. a goal that flips with where we stand cancels the path every flip
                Goal goal = ledger.isSolid(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z)) ? new GoalBreakAside(pos) : breakGoal(pos, bcc);
                toBreak.add(goal);
                owners.add(new Pair<>(pos, goal));
            }
        });
        unbreakableCells = cantBreak;
        List<Goal> toPlace = new ArrayList<>();
        // a set, List.contains for every position was quadratic and placeable is in the thousands on a big build
        Set<BetterBlockPos> placeableSet = new HashSet<>(placeable);
        List<BetterBlockPos> placeCells = new ArrayList<>();
        List<BetterBlockPos> droppedNow = new ArrayList<>();
        placeable.forEach(pos -> {
            // given up ones stay in placeableSet, or the block above a hole we gave up on becomes a goal over thin air
            BlockState wanted = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z));
            if (!waitsOnSupport(placeableSet, pos, wanted.getBlock(), p -> bcc.getSchematic(p.x, p.y, p.z, bcc.bsi.get0(p.x, p.y, p.z)).getBlock()) && !givenUp(pos.x, pos.y, pos.z)) {
                BlockState desired = wanted;
                // loaded only, an unloaded chunk reads as air and nothing survives on air
                if (bcc.bsi.worldContainsLoadedChunk(pos.x, pos.z) && !desired.canSurvive(ctx.world(), pos)) {
                    // nothing could go in there right now from any face (a wall torch whose wall isn't up yet), so a
                    // goal for it is just somewhere to stand and stare
                    droppedNow.add(pos);
                } else {
                    placeCells.add(pos);
                }
            }
        });
        dropped = droppedNow;
        logDropped(bcc, droppedNow);
        List<BetterBlockPos> aimed = new ArrayList<>();
        for (BetterBlockPos pos : placeCells) {
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z));
            if (!PlaceRules.aimProof(desired)) {
                aimed.add(pos);
            }
        }
        planCells(bcc, aimed);
        prunePlans();
        List<BetterBlockPos> waiting = new ArrayList<>();
        for (BetterBlockPos pos : placeCells) {
            if (waitsOnNeighbours(bcc, pos)) {
                waiting.add(pos);
                continue;
            }
            Goal goal = placementGoal(pos, bcc);
            if (goal == null) {
                plansPending = true;
                continue;
            }
            toPlace.add(goal);
            owners.add(new Pair<>(pos, goal));
        }
        sourceLiquids.forEach(pos -> {
            if (!givenUp(pos.x, pos.y, pos.z)) {
                Goal goal = new GoalBlock(pos.above());
                toPlace.add(goal);
                owners.add(new Pair<>(pos, goal));
            }
        });
        if (logMissing && toPlace.isEmpty() && toBreak.isEmpty() && !plansPending && !waiting.isEmpty()) {
            // they're all that's left, and with nothing else going on nothing next to them is going to change either.
            // upstream's goal like before, the stall check lets go of them from there. only on the last pass, a hotbar
            // pass or the near cells alone leaving these out is how the other work gets found first
            for (BetterBlockPos pos : waiting) {
                Goal goal = placementGoal(pos, bcc);
                if (goal == null) {
                    continue;
                }
                toPlace.add(goal);
                owners.add(new Pair<>(pos, goal));
            }
        }
        pouring = null;
        List<BetterBlockPos> unpourableNow = new ArrayList<>();
        // a stair in the rim waiting on its plan (or on a neighbour) is still a hole in the wall
        if (!pourCells.isEmpty() && toPlace.isEmpty() && toBreak.isEmpty() && !plansPending && waiting.isEmpty() && !missingBlocks(missing, creative)
                && !waitingOnBlocks(bcc)) {
            pourGoals(bcc, pourCells, approxPlaceable, creative, toPlace, owners, missing, unpourableNow);
        }
        unpourable = unpourableNow;
        goalOwners = owners;

        if (!toPlace.isEmpty()) {
            return new JankyGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (toBreak.isEmpty()) {
            if (logMissing) {
                // the same "at least" the chat line below prints, a copy so the next tick's pass can't change it
                missingAtPause = Map.copyOf(missing);
            }
            if (logMissing && !missing.isEmpty()) {
                logDirect("Missing materials for at least:");
                logDirect(missing.entrySet().stream()
                        .map(e -> String.format("%sx %s", e.getValue(), e.getKey()))
                        .collect(Collectors.joining("\n")));
            }
            if (logMissing && !flowingLiquids.isEmpty()) {
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            return null;
        }
        return new GoalComposite(toBreak.toArray(new Goal[0]));
    }

    // a source the file wants where a bucket would pour it. these sit out till the blocks are done, see pourGoals
    private boolean pourCell(BuilderCalculationContext bcc, BetterBlockPos pos, BlockState current) {
        if (!(current.getBlock() instanceof AirBlock) && !current.canBeReplaced()) {
            // a torch would wash away too, but the scan won't click into it (isReplaceable), so it gets broken like any
            // wrong block first and comes back here as air
            return false;
        }
        BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, current);
        return desired != null && fluidSource(desired) && pourInto(current, desired);
    }

    // a wall that isn't up yet because we don't have it holds the water back, or it just runs out the gap. the hotbar
    // only pass calls a block in the backpack missing too, and that one's coming. kelp plant, bubble column and friends
    // have no item at all, nobody's bringing those, so they don't count
    static boolean missingBlocks(Map<BlockState, Integer> missing, boolean creative) {
        for (BlockState state : missing.keySet()) {
            if (creative ? creativeItem(state) != null : itemOf(state) != Items.AIR) {
                return true;
            }
        }
        return false;
    }

    // a block we let go of for a bit is still coming back, and the water goes in after it. the for good ones, the
    // unbreakable ones and the ones that can't stay yet are as done as they get
    private boolean waitingOnBlocks(BuilderCalculationContext bcc) {
        return givenUp != null && givenUp.anyWaiting(builderTicks, (x, y, z) -> {
            BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
            return desired == null || !fluidSource(desired);
        });
    }

    // the bucket pass, once the blocks are done: walls first or the water just runs off. all the water before any lava,
    // lava poured next to water is a cobble generator
    private void pourGoals(BuilderCalculationContext bcc, List<BetterBlockPos> cells, List<BlockState> approxPlaceable, boolean creative,
                           List<Goal> toPlace, List<Pair<BetterBlockPos, Goal>> owners, Map<BlockState, Integer> missing, List<BetterBlockPos> skipped) {
        List<BetterBlockPos> water = new ArrayList<>();
        List<BetterBlockPos> lava = new ArrayList<>();
        Map<String, List<BetterBlockPos>> why = new LinkedHashMap<>();
        for (BetterBlockPos pos : cells) {
            if (givenUp(pos.x, pos.y, pos.z)) {
                continue;
            }
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z));
            String skip = pourSkip(bcc, pos.x, pos.y, pos.z, desired);
            if (skip != null) {
                skipped.add(pos);
                why.computeIfAbsent(skip, k -> new ArrayList<>()).add(pos);
            } else if (creative ? creativeItem(desired) == null : !containsBlockState(approxPlaceable, desired)) {
                // one bucket is one source, so a survival pool runs dry here and the pause asks for more
                missing.merge(desired, 1, Integer::sum);
            } else {
                (desired.getBlock() == Blocks.WATER ? water : lava).add(pos);
            }
        }
        logUnpourable(bcc, why);
        // water we're out of buckets for or let go of for a bit still goes first. lava running into where it ends up
        // is cobble all over the pool
        boolean waterComing = missing.containsKey(Blocks.WATER.defaultBlockState()) || givenUp != null
                && givenUp.anyWaiting(builderTicks, (x, y, z) -> {
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    return desired != null && desired.getBlock() == Blocks.WATER && fluidSource(desired);
                });
        List<BetterBlockPos> pass = !water.isEmpty() ? water : waterComing ? List.of() : lava;
        if (pass.isEmpty()) {
            return;
        }
        pouring = pass == water ? Blocks.WATER : Blocks.LAVA;
        boolean fromAbove = !creative && pouring == Blocks.LAVA;
        for (BetterBlockPos pos : pass) {
            Goal goal = new GoalPour(pos, fromAbove);
            toPlace.add(goal);
            owners.add(new Pair<>(pos, goal));
        }
    }

    // why a source stays dry, null if it doesn't. the scan asks too, so the two can't disagree
    private String pourSkip(BuilderCalculationContext bcc, int x, int y, int z, BlockState desired) {
        BlockPos pos = new BlockPos(x, y, z);
        if (desired.getBlock() == Blocks.WATER && Boolean.TRUE.equals(ctx.world().environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos))) {
            return "water boils off in this dimension";
        }
        if (desired.getBlock() == Blocks.LAVA && touchesWater(pos, p -> bcc.bsi.get0(p.getX(), p.getY(), p.getZ()),
                p -> bcc.getSchematic(p.getX(), p.getY(), p.getZ(), bcc.bsi.get0(p.getX(), p.getY(), p.getZ())))) {
            return "lava would meet water";
        }
        return null;
    }

    // the cell and its six neighbours, water in the world now or in the file later. waterlogged counts, it's water too
    static boolean touchesWater(BlockPos pos, Function<BlockPos, BlockState> world, Function<BlockPos, BlockState> wants) {
        if (water(world.apply(pos))) {
            return true;
        }
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (water(world.apply(n)) || water(wants.apply(n))) {
                return true;
            }
        }
        return false;
    }

    // by fluid and not the tag, tags aren't bound in the tests
    private static boolean water(BlockState state) {
        if (state == null) {
            return false;
        }
        Fluid fluid = state.getFluidState().getType();
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
    }

    // one line per reason for the cells that just got left dry, same idea as logDropped
    private void logUnpourable(BuilderCalculationContext bcc, Map<String, List<BetterBlockPos>> why) {
        if (why.isEmpty()) {
            return;
        }
        if (unpourableLogged == null) {
            unpourableLogged = new LongOpenHashSet();
        }
        why.forEach((reason, cells) -> {
            List<String> fresh = new ArrayList<>();
            for (BetterBlockPos pos : cells) {
                if (unpourableLogged.add(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z))) {
                    fresh.add(describe(bcc, pos));
                }
            }
            if (!fresh.isEmpty()) {
                logDirect("Not pouring these, " + reason + ": " + String.join(", ", fresh.subList(0, Math.min(fresh.size(), 5)))
                        + (fresh.size() > 5 ? " and " + (fresh.size() - 5) + " more" : ""));
            }
        });
    }

    public static class JankyGoalComposite implements Goal {

        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }


        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            JankyGoalComposite goal = (JankyGoalComposite) o;
            return Objects.equals(primary, goal.primary)
                    && Objects.equals(fallback, goal.fallback);
        }

        @Override
        public int hashCode() {
            int hash = -1701079641;
            hash = hash * 1196141026 + primary.hashCode();
            hash = hash * -80327868 + fallback.hashCode();
            return hash;
        }

        @Override
        public String toString() {
            return "JankyComposite Primary: " + primary + " Fallback: " + fallback;
        }
    }

    public static class GoalBreak extends GoalGetToBlock {

        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
            if (y > this.y) {
                return false;
            }
            // but any other adjacent works for breaking, including inside or below
            return super.isInGoal(x, y, z);
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalBreak{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1636324008;
        }
    }

    // in reach of the cell without standing on it or right next to it (besideFeet), see besideUs. two out sideways,
    // two under to one over, which a 4.5 reach covers from anywhere in it
    public static class GoalBreakAside implements Goal {

        private final int x;
        private final int y;
        private final int z;
        private final GoalNear near;

        public GoalBreakAside(BlockPos pos) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.near = new GoalNear(pos, 2);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            int dy = y - this.y;
            if (Math.abs(x - this.x) > 2 || Math.abs(z - this.z) > 2 || dy < -2 || dy > 1) {
                return false;
            }
            return !besideFeet(new BlockPos(x, y, z), this.x, this.y, this.z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return near.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof GoalBreakAside g && g.x == x && g.y == y && g.z == z;
        }

        @Override
        public int hashCode() {
            return (int) BetterBlockPos.longHash(x, y, z) * 709;
        }

        @Override
        public String toString() {
            return String.format("GoalBreakAside{x=%s,y=%s,z=%s}", SettingsUtil.maybeCensor(x), SettingsUtil.maybeCensor(y), SettingsUtil.maybeCensor(z));
        }
    }

    // null for an aimed cell whose plan isn't in yet. upstream's any-adjacent goal for one of those is somewhere we can't
    // click it from as often as not, then the stall check gave it up for standing there. planCells gets to it first
    private Goal placementGoal(BetterBlockPos pos, BuilderCalculationContext bcc) {
        BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos));
        long key = BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z);
        CellPlan plan = plans == null ? null : plans.get(key);
        if (plan != null && !plan.feet.isEmpty() && plan.desired == desired) {
            // somewhere a click comes out right from, not just anywhere next to it. GoalAdjacent was happy one block
            // over with the stair facing the wrong way and we stood there till the stall check let it go
            Goal[] feet = new Goal[plan.feet.size()];
            for (int i = 0; i < feet.length; i++) {
                feet[i] = new GoalPlanned(plan.feet.get(i).feet(), pos.getY());
            }
            return feet.length == 1 ? feet[0] : new GoalComposite(feet);
        }
        if (!bcc.bsi.worldContainsLoadedChunk(pos.x, pos.z)) {
            // reads as air till it loads, and loading doesn't come through blockChanged. fresh every tick, like upstream
            return upstreamPlacementGoal(pos, desired);
        }
        if (replanPending(pos, desired)) {
            pendingSeen = true;
            if (!pendingWaitedOut) {
                return null; // only a plan that ran and found nowhere gets upstream's goal
            }
        }
        StickyGoal sticky = stickyGoals == null ? null : stickyGoals.get(key);
        if (sticky != null && sticky.desired == desired) {
            return sticky.goal;
        }
        Goal goal = upstreamPlacementGoal(pos, desired);
        if (stickyGoals == null) {
            stickyGoals = new Long2ObjectOpenHashMap<>();
        }
        stickyGoals.put(key, new StickyGoal(desired, goal));
        return goal;
    }

    // not an aimed block, or the planner found nowhere: upstream's, and the stall check covers a bad arrival. only
    // blocks in here, nothing that moves, or the sticky copy goes stale without blockChanged ever hearing about it
    private Goal upstreamPlacementGoal(BlockPos pos, BlockState desired) {
        if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {  // TODO can this even happen?
            return new GoalPlace(pos);
        }
        boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
        // a solid cell can't go in with our head in it, so standing right under it is no good
        boolean solid = desired != null && !desired.getCollisionShape(ctx.world(), pos).isEmpty();
        // upstream also asked placementPlausible here. that's only an entity check, and it counted our own head the
        // tick an ascend next to the cell put it there, so the goal flipped to GoalPlace and back and took the path
        // with it. a mob in the cell is the click's problem (possibleToPlace still asks), not the goal's
        for (Direction facing : Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
            if (MovementHelper.canPlaceAgainst(ctx, pos.relative(facing))) {
                return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel, solid);
            }
        }
        return new GoalPlace(pos);
    }

    private static final class StickyGoal {
        final BlockState desired;
        final Goal goal;

        StickyGoal(BlockState desired, Goal goal) {
            this.desired = desired;
            this.goal = goal;
        }
    }

    private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (Baritone.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) { // TODO maybe possible without the up(2) check?
            return new JankyGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                        return false;
                    }
                    return super.isInGoal(x, y, z);
                }
            });
        }
        return new GoalBreak(pos);
    }

    public static class GoalAdjacent extends GoalGetToBlock {

        private boolean allowSameLevel;
        private BlockPos no;
        // the block will have a collision box, so feet right under it put our head where it goes and the click gets
        // refused every time. the stall check then gave it up for "nothing to click" while we stood right there
        private boolean solid;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            this(pos, no, allowSameLevel, false);
        }

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel, boolean solid) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
            this.solid = solid;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (solid && x == this.x && y == this.y - 1 && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowSameLevel && y == this.y - 1) {
                return false;
            }
            if (y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }

            GoalAdjacent goal = (GoalAdjacent) o;
            return allowSameLevel == goal.allowSameLevel
                    && solid == goal.solid
                    && Objects.equals(no, goal.no);
        }

        @Override
        public int hashCode() {
            int hash = 806368046;
            hash = hash * 1412661222 + super.hashCode();
            hash = hash * 1730799370 + (int) BetterBlockPos.longHash(no.getX(), no.getY(), no.getZ());
            hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
            hash = hash * 31 + (solid ? 1 : 0);
            return hash;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalAdjacent{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    // a feet cell the planner picked. lower cells first, same as GoalAdjacent and GoalPlace, or a composite of these
    // next to theirs would always look closer and the build would go up in whatever order the stairs are
    public static class GoalPlanned extends GoalBlock {

        private final int cellY;

        public GoalPlanned(BlockPos feet, int cellY) {
            super(feet);
            this.cellY = cellY;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return this.cellY * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o) && ((GoalPlanned) o).cellY == cellY;
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 31 + cellY;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlanned{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public static class GoalPlace extends GoalBlock {

        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1910811835;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlace{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    // somewhere to pour a source from: beside it or up to two above, about two blocks out at most. GoalAdjacent wanted us next to it at the same height, which round a sunk pool is inside the
    // wall. survival lava only from above, it runs sideways and down and a bucket of it at our feet is how you die
    public static class GoalPour implements Goal {

        private final int x;
        private final int y;
        private final int z;
        private final boolean fromAbove;

        public GoalPour(BlockPos cell, boolean fromAbove) {
            this.x = cell.getX();
            this.y = cell.getY();
            this.z = cell.getZ();
            this.fromAbove = fromAbove;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            int dx = x - this.x;
            int dy = y - this.y;
            int dz = z - this.z;
            if (dx == 0 && dz == 0) {
                return false; // in it, on it, or on top of whatever's over it, none of which sees a face to pour off
            }
            // not lower than the cell either: the scan skips a head height cell with air over it, and a pool top always
            // has air over it
            if (dy < (fromAbove ? 1 : 0) || dy > 2) {
                return false;
            }
            // about reach from the sneaking eye to the far side of the cell, the scan does the real check
            return dx * dx + dy * dy + dz * dz <= 6;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // the nearest rim spot, not the one straight over the pool, a partial path leaning out there falls in.
            // lower cells first, same as GoalAdjacent
            double best = Double.MAX_VALUE;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                best = Math.min(best, GoalBlock.calculate(x - (this.x + d.getStepX()), y - (this.y + 1), z - (this.z + d.getStepZ())));
            }
            return this.y * 100 + best;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            GoalPour goal = (GoalPour) o;
            return x == goal.x && y == goal.y && z == goal.z && fromAbove == goal.fromAbove;
        }

        @Override
        public int hashCode() {
            return (int) BetterBlockPos.longHash(x, y, z) * 31 + (fromAbove ? 1 : 0);
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPour{x=%s,y=%s,z=%s,fromAbove=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z),
                    fromAbove
            );
        }
    }

    // whether the builder pressed the click that's down right now. InputOverrideHandler asks it for the trace, so a click
    // that outlived the builder's control still shows up as the builder's
    public boolean clickedByBuilder() {
        return forcedClicks;
    }

    @Override
    public void onLostControl() {
        if (forcedClicks) {
            // whoever has the wheel now may never touch the clicks, and a left click left down mines whatever's under
            // the crosshair until somebody does
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            forcedClicks = false;
        }
        if (keysForced) {
            // a sneak left down leaves the next process creeping along, and a held walking key walks us off somewhere.
            // not on the REQUEST_PAUSE returns, the builder keeps ticking there and hanging() needs the sneak held or we
            // slide. this can run after the winner's onTick in the same tick and let go of a sneak it just pressed, same
            // risk the clicks above take. a path is fine, PathExecutor sets its keys after the processes
            InputOverrideHandler keys = baritone.getInputOverrideHandler();
            keys.setInputForceState(Input.SNEAK, false);
            keys.setInputForceState(Input.MOVE_FORWARD, false);
            keys.setInputForceState(Input.MOVE_BACK, false);
            keys.setInputForceState(Input.MOVE_LEFT, false);
            keys.setInputForceState(Input.MOVE_RIGHT, false);
            keysForced = false;
        }
        missingAtPause = Map.of();
        keep = null;
        if (schematic == null) {
            // cancelEverything calls every process, so a stop that already got here would hand keptGivenUp a null
            return;
        }
        keptGivenUp = givenUp;
        keptName = name;
        keptOrigin = origin;
        forgetStalls(); // the watchdog with it
        forgetCreative();
        // not the ledger: the same build starting again still owns its scaffold (see start)
        forgetCleanup();
        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        observedCompleted = null;
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    @Override
    public Optional<Integer> getMinLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.layer);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.stopAtHeight);
        }
        return Optional.empty();
    }

    private List<BlockState> approxPlaceable(int size) {
        List<BlockState> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            BlockState fluid = pours(stack);
            if (fluid != null) {
                // a full bucket is a source on legs, so owning one counts and the hotbar swap fetches it
                result.add(fluid);
                continue;
            }
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                result.add(Blocks.AIR.defaultBlockState());
                continue;
            }
            // <toxic cloud>
            BlockState itemState = ((BlockItem) stack.getItem())
                .getBlock()
                .getStateForPlacement(
                    new BlockPlaceContext(
                        new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}
                    )
                );
            if (itemState != null) {
                result.add(itemState);
            } else {
                result.add(Blocks.AIR.defaultBlockState());
            }
            // </toxic cloud>
        }
        return result;
    }

    private static boolean sameBlockstate(BlockState first, BlockState second) {
        return sameBlockstate(first, second, Baritone.settings().buildIgnoreDirection.value, Baritone.settings().buildIgnoreProperties.value);
    }

    // the neighbour derived ones (PlaceRules.derived) never count: a straight stair the file wants next to a sideways
    // one comes out a corner, and asking for the straight one anyway is a place and break loop.
    // Objects.equals and not != because not every property value is an interned enum (upstream #5122)
    static boolean sameBlockstate(BlockState first, BlockState second, boolean ignoreDirection, List<String> ignoredProps) {
        // a column the file draws as plant with a head on the tip is built from heads only, the game makes the plant
        // itself. so a head standing where the file says plant (or the other way round) is that cell done. returns
        // before the property loop on purpose: the head has age, the plant doesn't, and getValue would throw
        Block head = vineHead(first.getBlock());
        if (head != null && head == vineHead(second.getBlock())) {
            return true;
        }
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        if (first.equals(second)) {
            return true;
        }
        Block block = first.getBlock();
        for (Property<?> prop : first.getProperties()) {
            if (!Objects.equals(first.getValue(prop), second.getValue(prop))
                    && !PlaceRules.derived(block, prop)
                    && !(ignoreDirection && ORIENTATION_PROPS.contains(prop))
                    && !ignoredProps.contains(prop.getName())) {
                return false;
            }
        }
        return true;
    }

    // same item, any state. for "do i own this" on aimed blocks, whose approxPlaceable state is just whichever way we
    // happened to face. AIR on both sides is no item, not a match (kelp plant has no item either, so it can't pass as
    // kelp). by item, not block: approxPlaceable says standing torch for a torch stack, and that's still the stack the
    // wall torch comes out of, the planner asks the item which one it places
    static boolean sameItem(BlockState have, BlockState desired) {
        Item item = itemOf(have);
        return item != Items.AIR && item == itemOf(desired);
    }

    // the bucket for a source, the block's own item for everything else (flowing water has none)
    static Item itemOf(BlockState state) {
        Item bucket = bucketFor(state);
        if (bucket != null) {
            return bucket;
        }
        Item vine = vineItem(state.getBlock());
        return vine != null ? vine : state.getBlock().asItem();
    }

    // the item that places a vine head, for the head and for the plant body under it. nobody places a plant: the game
    // turns the head above into one when another head goes in below it (or above, for twisting). null for any other block
    static Item vineItem(Block block) {
        if (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT) {
            return Items.GLOW_BERRIES;
        }
        if (block == Blocks.WEEPING_VINES || block == Blocks.WEEPING_VINES_PLANT) {
            return Items.WEEPING_VINES;
        }
        return block == Blocks.TWISTING_VINES || block == Blocks.TWISTING_VINES_PLANT ? Items.TWISTING_VINES : null;
    }

    // the head block of a vine family, null for anything else. head and plant of one family count as the same cell
    private static Block vineHead(Block block) {
        if (block == Blocks.CAVE_VINES_PLANT) {
            return Blocks.CAVE_VINES;
        }
        if (block == Blocks.WEEPING_VINES_PLANT) {
            return Blocks.WEEPING_VINES;
        }
        if (block == Blocks.TWISTING_VINES_PLANT) {
            return Blocks.TWISTING_VINES;
        }
        return block == Blocks.CAVE_VINES || block == Blocks.WEEPING_VINES || block == Blocks.TWISTING_VINES ? block : null;
    }

    // hanging vines grow down, so their support is the cell above and the column goes top first. bottom first would try
    // to hang the tip off thin air. the ceiling over a column doesn't wait on the vines under it either, or the two
    // wait on each other forever
    static boolean waitsOnSupport(Set<BetterBlockPos> placeable, BetterBlockPos pos, Block block, Function<BetterBlockPos, Block> schematicAt) {
        if (growsDown(block)) {
            return placeable.contains(pos.above()) || placeable.contains(pos.above(2));
        }
        BetterBlockPos below = pos.below();
        BetterBlockPos below2 = pos.below(2);
        return placeable.contains(below) && !growsDown(schematicAt.apply(below)) || placeable.contains(below2) && !growsDown(schematicAt.apply(below2));
    }

    // cave and weeping vines hang, twisting grows up
    static boolean growsDown(Block block) {
        Block head = vineHead(block);
        return head == Blocks.CAVE_VINES || head == Blocks.WEEPING_VINES;
    }

    // a water or lava source, the only kind of fluid a bucket puts down
    static boolean fluidSource(BlockState state) {
        return state.getBlock() instanceof LiquidBlock && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    // the bucket that pours this source, null for anything else. vanilla's two only, a modded fluid stays missing
    static Item bucketFor(BlockState state) {
        if (!fluidSource(state)) {
            return null;
        }
        if (state.getBlock() == Blocks.WATER) {
            return Items.WATER_BUCKET;
        }
        return state.getBlock() == Blocks.LAVA ? Items.LAVA_BUCKET : null;
    }

    // the source a full bucket pours, null for any other stack
    static BlockState pours(ItemStack stack) {
        if (stack.is(Items.WATER_BUCKET)) {
            return Blocks.WATER.defaultBlockState();
        }
        return stack.is(Items.LAVA_BUCKET) ? Blocks.LAVA.defaultBlockState() : null;
    }

    // would BucketItem.emptyContents put this source into a cell holding current. same rule it uses
    static boolean pourInto(BlockState current, BlockState fluid) {
        return current.isAir() || current.canBeReplaced(fluid.getFluidState().getType());
    }

    static boolean containsItem(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameItem(testee, state)) {
                return true;
            }
        }
        return false;
    }

    // the hotbar swap's question. a stair stack facing the wrong way is still the stack the stair comes out of
    static boolean canSupply(BlockState have, BlockState desired) {
        return valid(have, desired, true) || (!PlaceRules.aimProof(desired) && sameItem(have, desired));
    }

    static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameBlockstate(testee, state)) {
                return true;
            }
        }
        return false;
    }

    // public so the schematic ghost and the diff ask the exact same question, a ghost over a block #build calls done is a liar
    public static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (pistonPart(current)) {
            // breaking a head breaks its base too, and nothing else fits in this cell while the piston's out anyway.
            // if the base is the wrong thing it gets broken on its own and takes the head with it. moving_piston is
            // gone in two ticks and can't be mined. a head from a piston outside the file poking into a cell that
            // wants a block stays "right" forever, but that's a stall, not a loop
            return true;
        }
        if (pistonPart(desired) && current.getBlock() instanceof AirBlock) {
            // the file got saved with the piston out. a head isn't an item, it shows up when the piston gets power
            return true;
        }
        if (desired.getBlock() instanceof LiquidBlock && desired.getValue(LiquidBlock.LEVEL) != 0
                && (current.getBlock() instanceof AirBlock || current.getBlock() == desired.getBlock())) {
            // flowing is whatever the source next to it makes of it, at whatever level it feels like. a bucket only
            // pours sources, so nothing could ever make this cell more right
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockstate(current, desired);
    }

    // the bits of a piston nobody places: the head and the block it's halfway through being
    private static boolean pistonPart(BlockState state) {
        return state.getBlock() instanceof PistonHeadBlock || state.getBlock() instanceof MovingPistonBlock;
    }

    // where the other half of a chest the file wants paired goes, null for anything that isn't half a pair
    static BlockPos chestPartner(BlockPos pos, BlockState desired) {
        if (!(desired.getBlock() instanceof ChestBlock) || desired.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        return ChestBlock.getConnectedBlockPos(pos, desired);
    }

    // a pair goes in one half at a time and the first half can only ever come out single, there's nothing to join yet.
    // asking for left or right straight away meant no plan for it, or one that put it in, broke it and put it in again.
    // so a lone half is right for now while the partner cell has no chest it could join. once it does, both have to be
    // exactly what the file says, and the second half's sneak click on the first one's side is what joins them
    static boolean chestHalfWaiting(BlockState current, BlockState desired, BlockState partner, boolean itemVerify) {
        if (!(desired.getBlock() instanceof ChestBlock chest) || current.getBlock() != desired.getBlock()
                || desired.getValue(ChestBlock.TYPE) == ChestType.SINGLE || current.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return false;
        }
        return !chest.chestCanConnectTo(partner) && valid(current, desired.setValue(ChestBlock.TYPE, ChestType.SINGLE), itemVerify);
    }

    // flowing water where the file wants air, from a file that has water in it: our own pool running round a corner or
    // out past the selection, not something to fix. a file with no water anywhere still gets it reported
    private boolean spilled(BlockState current, BlockState desired) {
        return desired.getBlock() instanceof AirBlock && current.getBlock() instanceof LiquidBlock
                && current.getValue(LiquidBlock.LEVEL) != 0 && hasSource(current.getBlock());
    }

    private boolean hasSource(Block fluid) {
        if (pourScan == null) {
            return false;
        }
        if (sourceInFile == null) {
            sourceInFile = new HashMap<>();
        }
        return sourceInFile.computeIfAbsent(fluid, f -> scanForSource(pourScan, f));
    }

    // the whole file, once per fluid per build, and only once some flowing fluid is sitting where air is wanted
    static boolean scanForSource(ISchematic schematic, Block fluid) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    if (schematic.inSchematic(x, y, z, air)) {
                        BlockState desired = schematic.desiredState(x, y, z, air, List.of());
                        if (desired.getBlock() == fluid && fluidSource(desired)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public class BuilderCalculationContext extends CalculationContext {

        private final List<BlockState> placeable;
        private final ISchematic schematic;
        private final int originX;
        private final int originY;
        private final int originZ;
        // one read per context, so a keep swapped mid search can't give half a path each answer
        private final Keep keep;
        // same deal for the ledger, the game thread adds to it while the path thread asks. a copy made per change
        private final ScaffoldLedger.View ledger;
        // creative with a slot free: any block the file wants is placeable whether or not it's on the hotbar yet, the
        // movement gets it handed over when it gets there (creativeHand). read here, the path thread can't ask the hotbar
        private final boolean creativeGives;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            this.placeable = approxPlaceable(9);
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();
            this.keep = BuilderProcess.this.keep;
            this.ledger = ledgerView();
            this.creativeGives = ctx.player().hasInfiniteMaterials() && !AltoClefSettings.getInstance().isInteractionPaused() && creativeSlot() >= 0;

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;
            // the first context gets built before we're the one in control, so clutchAllowed can't see us yet
            this.hasClutchItem = false;
            this.clutchPicksUp = false;
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
                return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
            } else {
                return null;
            }
        }

        // valid() plus whatever the starter said to keep. every per cell "is this done" asks this one
        private boolean fine(int x, int y, int z, BlockState current, BlockState desired) {
            return valid(current, desired, false) || kept(x, y, z, current) || spilled(current, desired) || chestWaiting(x, y, z, current, desired)
                    || ledgerFine(x, y, z, current, desired);
        }

        // our scaffold where the file wants air (till the cleanup gets to it), and a dug out cell that's empty while
        // we're still going past it (or till the end, the second time)
        private boolean ledgerFine(int x, int y, int z, BlockState current, BlockState desired) {
            long cell = BetterBlockPos.serializeToLong(x, y, z);
            if (current.getBlock() instanceof AirBlock) {
                return ledger.deferred().contains(cell);
            }
            return desired.getBlock() instanceof AirBlock && ledger.kept().contains(cell);
        }

        // the same, for assemble's cells, which never asked what the file wants
        private boolean ledgerFine(int x, int y, int z, BlockState current) {
            if (ledger.kept().isEmpty() && ledger.deferred().isEmpty()) {
                return false;
            }
            BlockState desired = getSchematic(x, y, z, current);
            return desired != null && ledgerFine(x, y, z, current, desired);
        }

        private boolean chestWaiting(int x, int y, int z, BlockState current, BlockState desired) {
            BlockPos partner = chestPartner(new BlockPos(x, y, z), desired);
            return partner != null && chestHalfWaiting(current, desired, bsi.get0(partner), false);
        }

        private boolean kept(int x, int y, int z, BlockState current) {
            return keep != null && !(current.getBlock() instanceof AirBlock) && keep.keep(x, y, z, current);
        }

        // this has to agree with what a movement will actually put there (InventoryBehavior.selectThrowawayForLocation),
        // or A* plans through a cell the movement then refuses to fill and we recalc forever
        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (isPlaceProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
                    // we want this to be air, but they're asking if they can place here
                    // this won't be a schematic block, this will be a throwaway
                    return placeBlockCost * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value; // we're going to have to break it eventually
                }
                if (!PlaceRules.aimProof(sch)) {
                    // a movement clicks whatever face is handy, so a stair or a log from it is a coin flip and a fence
                    // under our own feet is no flip at all. the builder places those itself
                    return COST_INF;
                }
                // containsBlockState and not contains: snowy grass and buildIgnoreProperties leaves were placeable to
                // assemble and unreachable here
                if (containsBlockState(placeable, sch) || (creativeGives && knownMovementOk(sch))) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                // upstream put a throwaway here at 1.5x. it goes into a cell the build wants filled, the builder breaks
                // it back out, the next path puts it back. that's the scaffold loop (upstream #2173)
                return COST_INF;
            } else {
                if (hasThrowaway) {
                    return placeBlockCost;
                } else {
                    return COST_INF;
                }
            }
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if ((!allowBreak && !allowBreakAnyway.contains(current.getBlock())) || isBreakProtected(x, y, z)) {
                return COST_INF;
            }
            if (kept(x, y, z, current) || ledger.kept().contains(BetterBlockPos.serializeToLong(x, y, z))) {
                // the caller's pillar or the ledger's, inside the box or out. x10 was still cheaper than a detour now
                // and then, and a path that mines the scaffold it just put up is a loop that places it again. not INF
                // though, standing on top of a tall pillar with no bucket the only way down is through it. the round
                // the cleanup takes down isn't in kept, that one prices like any block the file wants gone
                return 1000;
            }
            if (current.getBlock() instanceof PistonHeadBlock) {
                // same reason as kept: a path that mines a head mines the piston, which gets placed again, extends
                // again, and the next path mines it again. not INF, a bot shut in by a piston door still gets out
                return 1000;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                if (sch.getBlock() instanceof AirBlock) {
                    // it should be air
                    // regardless of current contents, we can break it
                    return 1;
                }
                // it should be a real block
                // is it already that block?
                if (valid(bsi.get0(x, y, z), sch, false) || chestWaiting(x, y, z, bsi.get0(x, y, z), sch)) {
                    return Baritone.settings().breakCorrectBlockPenaltyMultiplier.value;
                } else {
                    // can break if it's wrong
                    // would be great to return less than 1 here, but that would actually make the cost calculation messed up
                    // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
                    return 1;

                }
                // TODO do blocks in render distace only?
                // TODO allow breaking blocks that we have a tool to harvest and immediately place back?
            } else {
                return 1; // why not lol
            }
        }
    }
}
