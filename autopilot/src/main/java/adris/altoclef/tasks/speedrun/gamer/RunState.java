package adris.altoclef.tasks.speedrun.gamer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// everything a relog must not forget. plain fields and no minecraft types on purpose: gson reads it, tests build it
// without a game, and a human can open the file. one file per world, see RunStateStore
public class RunState {
    public static final int SCHEMA = 1;

    public int schema = SCHEMA;
    // "<level name>|<server ip>", a stale file from another world with the same folder name gets ignored
    public String fingerprint = "";
    public long startedEpochMs;
    // the level's game time when the run began, for the total on the win card. -1 on a save from before it was written down
    public long startedGameTime = -1;
    public GamerPhase phase = GamerPhase.GATHER;
    // attempts per phase name, so a retry survives a relog
    public Map<String, Integer> phaseAttempts = new HashMap<>();
    public long phaseEnteredGameTime;
    public int netherRevisits;
    public String stuckReason = "";
    // engine (w1). stuck keeps `phase` where it was so #gamer resumes there, finished = the run is over for good
    public boolean stuck;
    public boolean finished;
    // client ticks the gamer actually ran, for #gamer status
    public long runTicks;
    // reset on every phase change and on a manual resume, three of these in one phase is stuck
    public int deathsThisPhase;
    // "FROM>TO" -> how often the engine regressed that way, so a regress loop ends in STUCK instead of resetting every clock forever
    public Map<String, Integer> regressCounts = new HashMap<>();
    // this phase entry already used its one restart after running out of retries on the clock (PhaseMachine.restart). a relog
    // keeps it, or logging out would buy another. an old save has no key and reads false
    public boolean restartedThisPhase;

    public List<Death> deaths = new ArrayList<>();

    // the pair we built or used. one pair is enough, bots do not hop between portals
    public Pos overworldPortal;
    public Pos netherPortal;
    // CAST or OBSIDIAN, decided by the portal phase
    public String portalMethod = "CAST";
    // the lava pool stalled the portal phase once: the retries cast, and the pool's try does not use up an attempt
    public boolean portalPoolTimedOut;

    // nether: 27 chunk structure cells as "cx,cz"
    public Set<String> visitedCells = new LinkedHashSet<>();
    // cells a pocket gave up on: cell -> revisits used. not visited, they get one more go once nothing fresh is near the portal
    public Map<String, Integer> deferredCells = new HashMap<>();
    public Set<String> bastionCells = new LinkedHashSet<>();
    public Set<String> fortressCells = new LinkedHashSet<>();
    public List<Pos> fortress = new ArrayList<>();
    public List<Pos> bastion = new ArrayList<>();
    public Pos spawner;
    public Pos warpedForest;

    // stronghold
    public List<Ray> strongholdRays = new ArrayList<>();
    public Pos strongholdEstimate;
    public double strongholdRadius;
    public Pos strongholdStart;
    public Set<String> roomChunksVisited = new LinkedHashSet<>();
    public int eyeThrows;

    // end portal
    public Pos endPortalCenter;
    public int framesFilled;
    public boolean endPortalOpened;
    // OPEN has read all 12 frames at least once, so framesFilled is a real count and not the zero it starts as
    public boolean openRead;
    // game time the overworld pearl top-up began (0 = it has not), and whether it is over. one per run, see OpenTopUp
    public long openTopUpStart;
    public boolean openTopUpDone;
    public Pos spawnBed;
    // where the game says we respawn (a bed's foot), from the "Respawn point set" line and not from what we think we did.
    // null = the world spawn. an old save has no key and reads null, which just means we place one again (see Respawn)
    public Pos respawnPos;
    public String respawnDim = "OVERWORLD";
    // the bed we put down for the night (NightSleep) and have not picked up yet. not the spawn bed, and never a village's
    public Pos nightBed;

    // gear we left lying in the End (item registry name -> count), replaces BM2's cache so a death there does not re-craft it
    public Map<String, Integer> endDrops = new HashMap<>();
    public boolean dragonDead;

    public static class Pos {
        public int x;
        public int y;
        public int z;

        public Pos() {
        }

        public Pos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Pos p && p.x == x && p.y == y && p.z == z;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * x + y) + z;
        }

        @Override
        public String toString() {
            return x + ", " + y + ", " + z;
        }
    }

    // one furnace cooking something for us. plain strings (registry paths) so gson and the tests need no minecraft
    public static class FurnaceJob {
        public Pos pos;
        // "OVERWORLD", "NETHER"... a furnace is only ever collected from in the dimension it stands in
        public String dimension = "OVERWORLD";
        // block registry path: furnace, blast_furnace, smoker
        public String kind = "furnace";
        public String input = "";
        // what was in the input slot when we left, so also what we expect to come back as output
        public int count;
        public String output = "";
        public long startTick;
        // a hint, never a promise: the furnace contents on return are what counts
        public long doneTick;
        // nutrition of ONE finished item, 0 for anything that is not food. the food planner counts count * unitsEach as food
        // on the way. an old save has no key and reads 0, which is just "not food"
        public int unitsEach;
        // doneTick came from the furnace's own slots on a visit, not from our guess when we loaded it (FurnacePlan.due). an
        // old save has no key and reads false, which is just "a guess"
        public boolean visited;
        // visits that stood at the furnace for the whole estimate and still saw no item come out (FurnaceJobs.afterVisit). an old
        // save has no key and reads 0
        public int stalls;
        // food the cook left in the station without lighting it (AsyncSmelting.leftBehind): a pickup, not a cook. it is not pending
        // food and not a smoker to stand by, and the visit lights it if the bag has the fuel now, else takes it back. an old
        // save has no key and reads false
        public boolean stranded;
        // what FurnacePlan last decided about this job and the stand-by budget it started with. transient: a relog decides again, and
        // a replaced job (a new load at the same spot) is a new batch with a fresh budget
        public transient FurnacePlan.Track track = new FurnacePlan.Track();

        public FurnaceJob() {
        }

        public FurnaceJob(Pos pos, String dimension, String kind, String input, int count, String output, long startTick, long doneTick) {
            this.pos = pos;
            this.dimension = dimension;
            this.kind = kind;
            this.input = input;
            this.count = count;
            this.output = output;
            this.startTick = startTick;
            this.doneTick = doneTick;
        }
    }

    public static class Death {
        public String dimension;
        public int x;
        public int y;
        public int z;
        public long gameTime;
        public String phase;
        // "lava", "void" or "other" (NetherTripRules.Cause). an old save has no key and reads null, which is just "unknown"
        public String cause;
    }

    // a walk back into the nether for the pile a nether death left behind (NetherRecoverTask). stage is a NetherTripRules.Stage
    // name, the ticks are game time so a relog mid trip carries on with what is left of the budget
    public static class NetherTrip {
        public Pos pile;
        public String stage = "BLOCKS";
        public long startTick;
        public long stageTick;
        // first tick we stood in the nether, 0 until then. the budget counts from here (an old save reads 0 and starts fresh)
        public long netherTick;

        public NetherTrip() {
        }

        public NetherTrip(Pos pile, String stage, long tick) {
            this.pile = pile;
            this.stage = stage;
            this.startTick = tick;
            this.stageTick = tick;
        }
    }

    // eye throw bearing: origin and unit direction in XZ, dived = the eye sank into the ground (we are within ~12 blocks of the target)
    public static class Ray {
        public double ox;
        public double oz;
        public double dx;
        public double dz;
        public boolean dived;
    }

    // w5 (stronghold), additive
    // how many of the 12 frames are known (seen, or inferred from the seen ones: the ring is rigid)
    public int framesSeen;
    // spiral searches that came up empty. 1 = one re-estimate was spent
    public int roomRetries;
    // where the last room search started, so the re-estimate steps sideways before its first throw. null = no re-estimate pending
    public Pos relocateFrom;
    // game time the open phase first noticed it could not fill the rest of the frames. 0 = fine
    public long openNoEyesSince;
    // eyes held right before the last throw, 0 = nothing out there to collect. an eye in the air or on the floor is owed
    // to us until the count is back to this (or it shattered), also across an interrupt or a relog
    public int eyeBaseline;

    // w4 (nether), additive
    // the spawner camping took too long: the rod hunt moves on to the next fortress
    public boolean netherRodsGaveUp;
    // fortresses we already gave up on (their blaze spawner never paid out)
    public List<Pos> fortressExhausted = new ArrayList<>();
    // warped forests the walk never reached, same idea
    public List<Pos> warpedExhausted = new ArrayList<>();

    // w6
    // the extra bed near the portal took too long (or could not be placed): go to the End without a spawn bed
    public boolean spawnBedGaveUp;

    // game seconds spent with the diamond pick dig at the head of the iron plan, across retries and relogs (DiamondHunt)
    public double diamondHuntSeconds;
    // the dig ran out of time once: no more diamond pick this run, even if a later one gets lost. never cleared, like spawnBedGaveUp
    public boolean diamondHuntGaveUp;
    // same two for the raw gold dig (GoldHunt)
    public double goldHuntSeconds;
    public boolean goldHuntGaveUp;
    // and for the sheep chase (WoolHunt)
    public double woolHuntSeconds;
    public boolean woolHuntGaveUp;

    // crafting tables this run placed, the only ones the pickup may take back. these three lists are what is saved of the
    // station registry (Workbenches), the rest of an entry (state, clocks, tries) is rebuilt from them after a relog
    public List<Pos> placedTables = new ArrayList<>();
    // same for furnaces (plain ones only, a blast furnace of ours goes in placedJobBlocks below)
    public List<Pos> placedFurnaces = new ArrayList<>();
    // smokers we crafted for the food. a list of their own so an old save without the key just loads empty
    public List<Pos> placedSmokers = new ArrayList<>();
    // "x,y,z" -> dimension for the stations above that stand outside the overworld. an old save has no key, and every station
    // in it is an overworld one (that is the only place they were ever recorded)
    public Map<String, String> placedDimension = new HashMap<>();

    // the registry entries for the three lists above, see Workbenches.sync. transient: gson never writes it, a relog rebuilds it
    public transient List<Bench> benches = new ArrayList<>();
    // game tick the early iron batch (EarlyIronPick) started loading, -1 = no load in flight. transient: a relog starting
    // it over is fine, the planner just goes back to looking at the bag
    public transient long earlyLoadTick = -1;
    // what the running cook task tells the planner (backoff, the station it picked), see FurnacePlan.cookSuspended. it used to be
    // static fields that outlived the run. transient: a relog starts the cook fresh, which is what clearing them on start did
    public transient Cook cook = new Cook();
    // a food trip is under way: started under refillStartFoodUnits, runs to refillStopFoodUnits (FoodPlan.latched moves it every tick).
    // transient: after a relog the bag says again whether one is needed, worst case the trip waits for the next 45
    public transient boolean foodRefilling;
    // the game tick a trip first looked due with no trip on, -1 when it does not (FoodPlan.latched waits REFILL_CONFIRM_TICKS on it)
    public transient long foodDueSince = -1;
    // the dry spell (FoodDry): since when the food task has been searching with no animal loaded (-1 = not searching), whether the
    // latch is on and the game tick it runs to. transient: a relog starts the search over, which is the safe side
    public transient long foodSearchSince = -1;
    public transient boolean foodDryOn;
    public transient long foodDryUntil;
    // build blocks (BuildBlocks): the target of a refill under way (0 = none), how many fresh ones this phase entry has started (the base need, and the stock-up on top of it apart),
    // and whether the cap's line was said. transient: a relog counts again from nothing, the bag says whether one is needed
    public transient int buildRefill;
    public transient int buildRefills;
    public transient int buildExtraRefills;
    public transient boolean buildCapSaid;

    public static class Cook {
        // game tick the backoff after a cook gave up ends at, -1 = not backed off
        public long until = -1;
        // "smoker" or "furnace" while a cook task has its station, null = none
        public String station;
        // game tick the task last said so (a task that stopped being ticked without a stop goes stale on its own)
        public long stamp;
    }

    // village blacksmith chests we already opened (or started to), and the game ticks spent in them. the budget in
    // VillageChests reads both, so a relog does not hand out a fresh one
    public List<Pos> villageChestsTried = new ArrayList<>();
    public long villageLootTicks;
    // grindstones, smithing tables and blast furnaces this run placed. a village's own job blocks vouch for its chests,
    // ours must not (we craft blast furnaces). same guess as placedTables, see GamerTask.watchPlacements
    public List<Pos> placedJobBlocks = new ArrayList<>();

    // village beds we already went for (either half, both count) and the game ticks spent on them. BedRules reads the
    // ticks for the budget, so a relog does not hand out a fresh one
    public List<Pos> villageBedsTried = new ArrayList<>();
    public long villageBedTicks;

    // furnaces we loaded and walked away from, see FurnaceJobs. the furnace's own contents are the truth when we get back,
    // this is the memory that says there is something to go back for (and survives a relog)
    public List<FurnaceJob> furnaceJobs = new ArrayList<>();

    // non null = a trip back to a nether death pile is pending (survives a relog). null = none
    public NetherTrip netherTrip;
    // the pile the last trip went for, whatever came of it: one trip per pile, so a death on the way or a failed trip falls
    // back to the rebuild and the same pile never gets a second one
    public Pos lastTripPile;
    // a nether pile too far from home to walk to naked: it waits for the first time we stand in the nether with the kit on
    public Pos deferredPile;

    public int attemptsOf(GamerPhase p) {
        return phaseAttempts.getOrDefault(p.name(), 0);
    }
}
