package adris.altoclef.tasks.speedrun.gamer.config;

// configs/beat_minecraft.json. "version" is checked before gson sees the file: any other value (including the old
// BeatMinecraftConfig shape, which has none) means defaults, saved over it, one log line. nested objects are one class
// per phase group so the workers do not step on each other.
// an older file from OLDEST_KEPT up is not thrown away: its numbers stay and the kit lists (every List<KitItem> in here)
// go back to the defaults, see GamerConfigs.migrate
public class GamerConfig {
    // bump VERSION when you change a default kit list (or add one). a saved file replaces the default list outright, so
    // without the bump every existing run keeps the old kit forever (that is how the bot went to the nether with no
    // ladders). GamerConfigsTest has a hash of the defaults that fails until you do
    // (5 moved the old station pickup defaults. those keys are gone now, an old file still loads, gson skips them)
    // (6 moved the sweep spacing default 9 -> 14, a saved 9 is the old default and goes along, see GamerConfigs.migrate)
    // (7 swapped the stone and iron axes for swords, the sword is the weapon now)
    // (8 moved the spare shields default 2 -> 3, a saved 2 is the old default and goes along, see GamerConfigs.migrate)
    // (9 moved the pearl hunt window 10 -> 15 minutes, a saved 10 is the old default and goes along, see GamerConfigs.migrate)
    // (10 moved the End beds 8 -> 5 and dropped the wool_beds stock-up, a saved 8 is the old default and goes along)
    // (11 moved the floor eyes 12 -> 13, one spare for the first trip, a saved 12 is the old default and goes along)
    // (12 moved the End beds to 9 and the sheep clock 3 -> 6 minutes, a saved 5, 8 or 3 is an old default and goes along)
    // (13 moved the End's block lines, minBuildBlocks 40 -> 48 and endMinBlocks 20 -> 8, a saved 40 or 20 is the old default and goes along)
    // (14 moved the nether entry stack 128 -> 56 and its low mark 96 -> 40, the rest gets dug on arrival. a saved 128 or 96 is the old default and goes along)
    // (15 moved the deaths per phase 3 -> 8, a saved 3 is the old default and goes along)
    // (16 moved the night wool radius 40 -> 128, a saved 40 is the old default and goes along)
    // (17 moved the target eyes 14 -> 16, a saved 14 is the old default and goes along)
    public static final int VERSION = 17;
    // files from here up to VERSION get the migration instead of the defaults. raise it if a bump ever changes the shape
    // in a way the migration can not paper over
    public static final int OLDEST_KEPT = 2;

    public int version = VERSION;
    // a failing phase gets this many tries before it is skipped or the run goes STUCK
    public int maxAttempts = 2;
    // eyes of ender: craft this many in the nether, the portal needs 12 minus the ~1.2 that are pre filled, and the search
    // for the stronghold eats about 2, 4 when it's feeling mean. a second nether trip costs way more than 2 pearls
    public int targetEyes = 16;
    // leave the nether / start the stronghold with at least this many when the pearl budget ran out. 12 fills the ring
    // with nothing to spare, one more covers the odd lost throw
    public int floorEyes = 13;
    // extra rods on top of the target while the ring is still unread: rods are nether only, pearls can be topped up up top
    public int spareRods = 1;

    public Budgets budgets = new Budgets();
    public Death death = new Death();
    public OverworldConfig overworld = new OverworldConfig();
    public NetherConfig nether = new NetherConfig();
    public StrongholdConfig stronghold = new StrongholdConfig();
    public EndConfig end = new EndConfig();

    public static class Death {
        // go back for our stuff when we died this close (flat distance) in the dimension we respawned in, and the pile was worth it
        // (DeathRules.refuse). far is fine: a pile in a chunk nobody has loaded does not age. this used to be recoverBlocks (300),
        // a saved file keeps its numbers, so a new name is how the new default reaches the old files
        public int recoverReach = 1000;
        // items despawn after 5 minutes, leave margin. counted from when the pile can be loaded (RecoverRules.Allowance), the
        // walk there is not part of it
        public int recoverSeconds = 240;
        // a pile within this many blocks (square, not round: chunks tick in a square) of where we respawn stays loaded and its items
        // despawn on the clock, so waiting for the day with it is a loss. only a farther pile waits (DeathRules.waitsForDay)
        public int tickingBlocks = 176;
        // a death with an empty bag costs a minute, this stop costs the game until somebody retypes #gamer. 8 sits out a whole
        // bad night, maxTotal is the loop breaker
        public int maxPerPhase = 8;
        // deaths since the run was (re)started by hand, over every phase together
        public int maxTotal = 12;
        // a death in the nether leaves a pile that does not age while we are away: go back through the portal for it
        public boolean netherRecover = true;
        // the nether part of the trip (walk, pile), game seconds, counted from the moment we arrive. the overworld stages have caps of their own. over it we rebuild the kit like before
        public int netherTripSeconds = 360;
        // throwaway blocks to get by hand before the portal, and how long we spend on them before going with what we have
        public int netherBlocks = 24;
        public int netherBlocksSeconds = 60;
    }
}
