package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.StubContext;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// handler isDone / regressTo are pure over fake facts. Items need the registry, hence the bootstrap
public class StrongholdPhasesTest {
    private final GamerConfig cfg = new GamerConfig();
    private final LocatePhase locate = new LocatePhase();
    private final RoomPhase room = new RoomPhase();
    private final OpenPhase open = new OpenPhase();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static class Facts implements GamerFacts {
        final Map<Item, Integer> items = new HashMap<>();
        long time = 100_000;

        Facts with(Item item, int n) {
            items.put(item, n);
            return this;
        }

        @Override
        public Dimension dimension() {
            return Dimension.OVERWORLD;
        }

        @Override
        public int count(Item item) {
            return items.getOrDefault(item, 0);
        }

        @Override
        public boolean armorEquipped(Item item) {
            return false;
        }

        @Override
        public int armorPoints() {
            return 0;
        }

        @Override
        public int foodUnits() {
            return 0;
        }

        @Override
        public int buildBlocks() {
            return 0;
        }

        @Override
        public int x() {
            return 0;
        }

        @Override
        public int y() {
            return 64;
        }

        @Override
        public int z() {
            return 0;
        }

        @Override
        public long gameTime() {
            return time;
        }

        @Override
        public boolean creditsShown() {
            return false;
        }
    }

    // ---- LOCATE

    @Test
    public void locateIsDoneOnceThereIsAStartOrAPortal() {
        RunState s = new RunState();
        assertFalse(locate.isDone(new Facts(), s, cfg));
        s.strongholdStart = new RunState.Pos(1700, 0, -1204);
        assertTrue(locate.isDone(new Facts(), s, cfg));
        RunState p = new RunState();
        p.endPortalCenter = new RunState.Pos(1, 30, 2);
        assertTrue(locate.isDone(new Facts(), p, cfg));
    }

    @Test
    public void locateRegressesWhenWeCannotGetToTwelveEyes() {
        RunState s = new RunState();
        // 4 eyes, nothing to craft with: way short
        assertEquals(Optional.of(GamerPhase.NETHER), locate.regressTo(new Facts().with(Items.ENDER_EYE, 4), s, cfg));
        assertEquals(Optional.of(GamerPhase.NETHER), locate.regressTo(new Facts(), s, cfg));
    }

    @Test
    public void locateOnlyGivesUpWhenItIsHopeless() {
        RunState s = new RunState();
        assertEquals(Optional.empty(), locate.regressTo(new Facts().with(Items.ENDER_EYE, 12), s, cfg));
        // 12 eyes, one just thrown: 11 in the bag must not send us back to the nether, nor must 10 (the frames are
        // pre-filled 10% of the time, OPEN counts the real ones and decides)
        assertEquals(Optional.empty(), locate.regressTo(new Facts().with(Items.ENDER_EYE, 11), s, cfg));
        assertEquals(Optional.empty(), locate.regressTo(new Facts().with(Items.ENDER_EYE, 10), s, cfg));
        // 8 in the bag + the one in the air = 9, the edge of hopeless
        assertEquals(Optional.empty(), locate.regressTo(new Facts().with(Items.ENDER_EYE, 8), s, cfg));
        assertEquals(Optional.of(GamerPhase.NETHER), locate.regressTo(new Facts().with(Items.ENDER_EYE, 7), s, cfg));
    }

    @Test
    public void locateCountsFramesAlreadyFilledAndCraftableEyes() {
        RunState s = new RunState();
        s.framesFilled = 2;
        // 6 eyes + 2 filled + 1 in flight = 9: fine. 5 is not
        assertEquals(Optional.empty(), locate.regressTo(new Facts().with(Items.ENDER_EYE, 6), s, cfg));
        assertEquals(Optional.of(GamerPhase.NETHER), locate.regressTo(new Facts().with(Items.ENDER_EYE, 5), s, cfg));
        // 3 eyes + 3 pearls with 2 rods (4 powder) craft 3 more: 3 + 3 + 2 filled + 1 = 9
        Facts f = new Facts().with(Items.ENDER_EYE, 3).with(Items.ENDER_PEARL, 3).with(Items.BLAZE_ROD, 2);
        assertEquals(Optional.empty(), locate.regressTo(f, s, cfg));
    }

    @Test
    public void craftableEyesAreLimitedByThePearlsAndThePowder() {
        assertEquals(3, StrongholdRules.craftableEyes(new Facts().with(Items.ENDER_PEARL, 3).with(Items.BLAZE_POWDER, 9)));
        assertEquals(2, StrongholdRules.craftableEyes(new Facts().with(Items.ENDER_PEARL, 9).with(Items.BLAZE_POWDER, 2)));
        // a rod is two powder
        assertEquals(4, StrongholdRules.craftableEyes(new Facts().with(Items.ENDER_PEARL, 9).with(Items.BLAZE_ROD, 2)));
        assertEquals(0, StrongholdRules.craftableEyes(new Facts().with(Items.BLAZE_ROD, 5)));
    }

    @Test
    public void onlyOneNetherRevisit() {
        RunState s = new RunState();
        s.netherRevisits = 1;
        assertEquals(Optional.empty(), locate.regressTo(new Facts(), s, cfg));
        assertEquals(Optional.empty(), room.regressTo(new Facts(), withStart(s), cfg));
    }

    // ---- ROOM

    private static RunState withStart(RunState s) {
        s.strongholdStart = new RunState.Pos(10, 0, 10);
        return s;
    }

    @Test
    public void roomIsDoneWithTheRingKnownAndAllFramesAccountedFor() {
        RunState s = withStart(new RunState());
        assertFalse(room.isDone(new Facts(), s, cfg));
        s.endPortalCenter = new RunState.Pos(10, 30, 20);
        s.framesSeen = 11;
        assertFalse(room.isDone(new Facts(), s, cfg));
        s.framesSeen = 12;
        assertTrue(room.isDone(new Facts(), s, cfg));
    }

    @Test
    public void roomWithNothingToSearchFromGoesBackToLocate() {
        RunState s = new RunState();
        assertEquals(Optional.of(GamerPhase.LOCATE), room.regressTo(new Facts().with(Items.ENDER_EYE, 12), s, cfg));
        // with a start (or a portal) there is no reason to
        assertEquals(Optional.empty(), room.regressTo(new Facts().with(Items.ENDER_EYE, 12), withStart(s), cfg));
        RunState p = new RunState();
        p.endPortalCenter = new RunState.Pos(1, 2, 3);
        assertEquals(Optional.empty(), room.regressTo(new Facts().with(Items.ENDER_EYE, 12), p, cfg));
    }

    @Test
    public void roomRegressesToNetherWhenEyesAreGone() {
        RunState s = withStart(new RunState());
        assertEquals(Optional.of(GamerPhase.NETHER), room.regressTo(new Facts().with(Items.ENDER_EYE, 3), s, cfg));
    }

    @Test
    public void roomWithNoTaskYetFallsBackToTheDefaultTimeout() {
        StubContext ctx = new StubContext();
        room.onEnter(null, ctx);
        ctx.attempt = 1;
        assertEquals(Timeout.RETRY, room.onTimeout(ctx, 1, "took longer than 18 minutes"));
        assertEquals(Timeout.STUCK, room.onTimeout(ctx, 2, "took longer than 18 minutes"));
    }

    @Test
    public void roomKeepsGoingOnlyForTheWatchdogAndOnlyWithRealProgress() {
        // chunks reached in the window and the watchdog ran out: a long legit spiral
        assertTrue(StrongholdRules.roomStillMoving(1, false, "took longer than 18 minutes"));
        // a stall says the last 180 s did nothing, chunks from before it do not make it a moving window
        assertFalse(StrongholdRules.roomStillMoving(3, false, "no progress for 180 seconds"));
        // a window that reached nothing (the 90 s chunk timer alone does not count) is stuck
        assertFalse(StrongholdRules.roomStillMoving(0, false, "took longer than 18 minutes"));
        // the handler's own endings are not timeouts to wait out
        assertFalse(StrongholdRules.roomStillMoving(5, true, "searched every chunk around the stronghold and found no portal room"));
        assertFalse(StrongholdRules.roomStillMoving(5, false, "searched every chunk around the stronghold and found no portal room"));
        assertFalse(StrongholdRules.roomStillMoving(5, false, "not in the overworld while searching the stronghold"));
        assertFalse(StrongholdRules.roomStillMoving(5, false, "error in the room phase (NullPointerException)"));
        // a spent spiral at the budget is exhausted, not moving
        assertFalse(StrongholdRules.roomStillMoving(5, true, "took longer than 18 minutes"));
        assertFalse(StrongholdRules.roomStillMoving(5, false, null));
    }
    // ---- OPEN

    @Test
    public void openIsDoneWhenThePortalIsOpen() {
        RunState s = new RunState();
        assertFalse(open.isDone(new Facts(), s, cfg));
        s.endPortalOpened = true;
        assertTrue(open.isDone(new Facts(), s, cfg));
    }

    @Test
    public void openWaitsOutTheLagBeforeCallingItAShortage() {
        RunState s = new RunState();
        s.endPortalCenter = new RunState.Pos(1, 2, 3);
        s.framesFilled = 10;
        Facts f = new Facts();
        // two frames empty, no eyes, nothing to craft: starved
        assertTrue(StrongholdRules.starved(f, s));
        // but the handler has not noticed yet (openNoEyesSince 0): no regress
        assertEquals(Optional.empty(), open.regressTo(f, s, cfg));
        s.openNoEyesSince = f.time;
        assertEquals(Optional.empty(), open.regressTo(f, s, cfg));
        f.time += 99;
        assertEquals(Optional.empty(), open.regressTo(f, s, cfg));
        f.time += 1;
        assertEquals(Optional.of(GamerPhase.NETHER), open.regressTo(f, s, cfg));
    }

    @Test
    public void openDoesNotRegressWhenTheLastEyeIsJustGoingIn() {
        RunState s = new RunState();
        s.endPortalCenter = new RunState.Pos(1, 2, 3);
        // 11 filled and the last eye in hand: fine
        s.framesFilled = 11;
        Facts f = new Facts().with(Items.ENDER_EYE, 1);
        assertFalse(StrongholdRules.starved(f, s));
        s.openNoEyesSince = 1;
        f.time = 1_000_000;
        assertEquals(Optional.empty(), open.regressTo(f, s, cfg));
        // portal opened: never starved
        s.framesFilled = 5;
        s.endPortalOpened = true;
        assertFalse(StrongholdRules.starved(new Facts(), s));
    }

    @Test
    public void openCanCraftItsWayOutOfAShortage() {
        RunState s = new RunState();
        s.framesFilled = 10;
        Facts f = new Facts().with(Items.ENDER_PEARL, 2).with(Items.BLAZE_POWDER, 2);
        assertFalse(StrongholdRules.starved(f, s));
    }

    @Test
    public void openWaitsOutThePearlTopUpBeforeRegressing() {
        RunState s = new RunState();
        s.framesFilled = 10;
        Facts f = new Facts();
        s.openNoEyesSince = f.time;
        f.time += 1000;
        s.openTopUpStart = f.time;
        assertEquals(Optional.empty(), open.regressTo(f, s, cfg));
        // it ended (covered or gave up): the usual rule has its say again
        s.openTopUpDone = true;
        assertEquals(Optional.of(GamerPhase.NETHER), open.regressTo(f, s, cfg));
        // and after the revisit it is not a reason to fail while the hunt runs
        RunState r = new RunState();
        r.framesFilled = 10;
        r.netherRevisits = 1;
        r.openNoEyesSince = 1;
        r.openTopUpStart = 5;
        assertFalse(StrongholdRules.starvedForGood(f, r));
        r.openTopUpDone = true;
        assertTrue(StrongholdRules.starvedForGood(f, r));
    }

    @Test
    public void openGivesUpForGoodAfterTheNetherRevisit() {
        RunState s = new RunState();
        s.framesFilled = 10;
        s.netherRevisits = 1;
        Facts f = new Facts();
        s.openNoEyesSince = f.time;
        assertFalse(StrongholdRules.starvedForGood(f, s));
        f.time += 100;
        assertTrue(StrongholdRules.starvedForGood(f, s));
        assertEquals(Optional.empty(), open.regressTo(f, s, cfg));
    }

    @Test
    public void handlersNameThemselves() {
        assertEquals(GamerPhase.LOCATE, locate.phase());
        assertEquals(GamerPhase.ROOM, room.phase());
        assertEquals(GamerPhase.OPEN, open.phase());
        assertEquals(GamerPhase.LOCATE.hud(), locate.hud());
        assertEquals(GamerPhase.ROOM.hud(), room.hud());
        assertEquals(GamerPhase.OPEN.hud(), open.hud());
    }

    // ---- the end prep ahead of the dig

    @Test
    public void prepOnlyStartsOnTheSurfaceAtTheStart() {
        assertTrue(RoomPhase.prepStarts(true, 70, true));
        // the line itself counts, same as the room bed
        assertTrue(RoomPhase.prepStarts(true, 50, true));
        assertFalse(RoomPhase.prepStarts(false, 70, true));
        assertFalse(RoomPhase.prepStarts(true, 49.9, true));
        assertFalse(RoomPhase.prepStarts(true, 70, false));
    }

    @Test
    public void prepGetsFiveMinutesOfGameTime() {
        assertFalse(RoomPhase.prepOutOfTime(1000, 1000));
        assertFalse(RoomPhase.prepOutOfTime(1000, 1000 + 5999));
        assertTrue(RoomPhase.prepOutOfTime(1000, 1000 + 6000));
    }

    @Test
    public void aPrepWithNothingLeftLetsTheSearchGo() {
        StubContext ctx = new StubContext();
        RoomPhase r = new RoomPhase();
        assertFalse(r.holdsBudget());
        r.startPrep(new BlockPos(0, 64, 0), 0);
        assertTrue(r.holdsBudget());
        // no search task yet, so nothing wants a mod
        assertNull(r.prepResult(null, ctx, null));
        assertFalse(r.holdsBudget());
        assertEquals(1, ctx.progress.size());
    }

    @Test
    public void theRoomBedGetsAnotherLookOnceThePrepIsOver() {
        StubContext ctx = new StubContext();
        RoomPhase r = new RoomPhase();
        r.startPrep(new BlockPos(0, 64, 0), 0);
        r.roomBedDecided = true;
        r.prepResult(null, ctx, null);
        assertFalse(r.roomBedDecided);
    }

    @Test
    public void aRunningPrepHoldsTheBudgetAndFeedsTheStallClock() {
        StubContext ctx = new StubContext();
        RoomPhase r = new RoomPhase();
        r.startPrep(new BlockPos(0, 64, 0), 0);
        Task errand = new Task() {
            @Override
            protected void onStart(AltoClef mod) {
            }

            @Override
            protected Task onTick(AltoClef mod) {
                return null;
            }

            @Override
            protected void onStop(AltoClef mod, Task interruptTask) {
            }

            @Override
            protected boolean isEqual(Task other) {
                return other == this;
            }

            @Override
            protected String toDebugString() {
                return "errand";
            }
        };
        assertSame(errand, r.prepResult(null, ctx, errand));
        assertTrue(r.holdsBudget());
        assertEquals(1, ctx.progress.size());
    }

    @Test
    public void theTopUpOnlyGetsTheTurnNobodyElseWants() {
        Task errand = new Task() {
            @Override
            protected void onStart(AltoClef mod) {
            }

            @Override
            protected Task onTick(AltoClef mod) {
                return null;
            }

            @Override
            protected void onStop(AltoClef mod, Task interruptTask) {
            }

            @Override
            protected boolean isEqual(Task other) {
                return other == this;
            }

            @Override
            protected String toDebugString() {
                return "bed errand";
            }
        };
        assertTrue(EndPrepPhase.topUpsNow(null, true));
        // short on beds: the bed errand is picked, and it stays picked
        assertFalse(EndPrepPhase.topUpsNow(errand, true));
        // the cap ran out: over, however short we are
        assertFalse(EndPrepPhase.topUpsNow(null, false));
    }

    @Test
    public void aNewVisitForgetsThePrep() {
        StubContext ctx = new StubContext();
        RoomPhase r = new RoomPhase();
        r.startPrep(new BlockPos(0, 64, 0), 0);
        r.prepResult(null, ctx, null);
        r.onEnter(null, ctx);
        r.startPrep(new BlockPos(0, 64, 0), 0);
        assertTrue(r.holdsBudget());
    }

    @Test
    public void movingRetriesAreCapped() {
        assertTrue(StrongholdRules.mayRetryMoving(0));
        assertTrue(StrongholdRules.mayRetryMoving(1));
        assertFalse(StrongholdRules.mayRetryMoving(2));
    }
}
