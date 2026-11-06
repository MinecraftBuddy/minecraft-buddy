package adris.altoclef.tasks.speedrun.gamer.config;

// owner: nether worker (NETHER, EYES, RETURN)
public class NetherConfig {
    public enum PearlSource {
        // endermen. v1 never barters on its own: 26.3 barter is 2.13% per ingot (10 in 469) and the gold for it rarely exists
        AUTO,
        ENDERMEN,
        BARTER
    }

    public PearlSource pearlSource = PearlSource.AUTO;
    // gold ingots per pearl we still need before bartering is worth the time
    public int barterGoldPerPearl = 15;
    // chunks between the waypoints of the 2x2 sweep inside one 27 chunk structure cell (offsets 4 and 18). the wider grid leans
    // on the 128 block sighting range. the z=4 row has no leg of its own (legs run along z inside a column) so chunk (11,0)
    // sits right at that edge, the entry leg of the next cell usually covers it
    public int sweepSpacingChunks = 14;
    // stay this far from a bastion we only wanted to see once (blocks)
    public int bastionAvoidRadius = 48;
    public double rodsBudgetMinutes = 12;
    // no rod after this long next to the spawner: try the next fortress cell
    public double spawnerCampMinutes = 6;
    // no rod after this long with a spawner picked, camping or busy with its blazes: same thing, next fortress
    public double spawnerDryMinutes = 8;

    // never sweep more than this many structure cells in one attempt (about 2.5 cells per fortress, so 12 is generous)
    public int maxCells = 12;
    // old flat soft timeout, the sweep goes by max(45, leg / 2.5) now (NetherSweepPlanner.legSeconds) so this is only the
    // fallback for the plain softTimedOut overload and old config files. a waypoint that is not reached in time is skipped
    // (lava lake in the way, bad terrain)
    // (only if it also stopped getting closer, see waypointHardSeconds for the one that always fires)
    public double waypointSeconds = 90;
    // no waypoint gets longer than this, however slowly it creeps closer
    public double waypointHardSeconds = 240;
    // close enough to a waypoint (blocks): the view distance does the rest
    public int waypointArriveBlocks = 24;
    // a waypoint we have not got waypointNoProgressBlocks closer to in this long (walking time, not the clock on the wall)
    // is dropped for the rest of the sweep. 0 = off, only the waypointSeconds backstop is left
    public double waypointNoProgressSeconds = 20;
    public int waypointNoProgressBlocks = 8;
    // a waypoint with no floor under it (lava sea) is moved to the nearest place that has one inside this many blocks
    public int waypointShiftBlocks = 12;
    // total time we are willing to spend on the barter top-up
    public double barterMinutes = 8;
    // give up the nether pearl hunt after this long without a new pearl, forest known or not
    public double huntWanderMinutes = 15;
    // the engine budget fires at nether minutes, leave a little earlier than that when deciding "budget over"
    // so the floor rule wins the race against the timeout
    public double budgetGraceSeconds = 20;
    // two ghast hits inside this window make us run for cover
    public double ghastWindowSeconds = 20;
}
