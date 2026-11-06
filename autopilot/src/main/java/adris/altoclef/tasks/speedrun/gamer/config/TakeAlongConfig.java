package adris.altoclef.tasks.speedrun.gamer.config;

import adris.altoclef.trackers.BanPolicy;

// the numbers of taking things along on the way (TakeAlongRules, TakeAlongChain), beside the gamer's own detour numbers in
// OverworldConfig. not the gamer's: the chain runs under any user task but #gamer. tuned on the practice rig, not in a panel.
// every time is in seconds unless the name says ticks, every reach is WalkCost (blocks across plus 4 per block up or down)
public class TakeAlongConfig {
    // take a cluster when what it brings is worth at least this many times what the detour costs
    public double k = 2;
    // the urgency of an item the gate ahead or the current step lacks, and of one the rest of the order needs in this dimension
    public double urgencyNow = 1.0;
    public double urgencyLater = 0.6;
    // beyond the need, a reserve of reserveShare x the need (rounded up), its first item counting reserveStart and the
    // factor falling evenly to nothing at the end of the reserve: a need of 10 makes 0.5, 0.4, 0.3, 0.2, 0.1 of items 11 to 15
    public double reserveShare = 0.5;
    public double reserveStart = 0.5;
    // DetourSight's range for this chain, straight from the eyes: about what a player makes out an ore at in a lit cave
    public double sightRange = 16;
    // the nearest block of a cluster is never further than this, whatever it is worth
    public double reachCeiling = 32;
    // walking speed for the cost of the walk there and back
    public double walkBlocksPerSecond = 4;
    // the break time of one block is breakFactor x hardness / the tool's speed (the game's 1.5 for the right tool)
    public double breakFactor = 1.5;
    // killing one animal of a herd taken along (the walk up to it, the hits, its run when hit)
    public double killSeconds = 3;
    // an animal more than this many blocks above or below the feet is not taken along (one on the surface over a cave)
    public int preyHeight = 4;
    // picking up the drops, and the switch: the interrupted user task's tree restarts its onStart on return
    public double dropSeconds = 1;
    public double switchSeconds = 3;
    // the clock of one detour, then whatever is still standing is banned
    public double seconds = 30;
    // the rest after any detour, so it does not trade places with the user task every other second
    public double cooldownSeconds = 5;
    // how long a cluster stays off after a timeout or a stray, game ticks (5 game minutes)
    public long banTicks = BanPolicy.TAKE_ALONG;
    // never a detour under this health
    public float minHealth = 12;
    // three free slots or fewer start nothing, unless the drop stacks onto a stack in the bag
    public int minFreeSlots = 4;
    // detours take at most this share of any shareWindowSeconds (10 game minutes); a detour under way finishes its clock
    public double shareCeiling = 1.0 / 3;
    public double shareWindowSeconds = 600;
    // a hostile this close (straight) and in sight holds every new detour back
    public double foeRange = 12;
    // looking for a cluster reads thousands of blocks, once a second is plenty for a walk at 4 blocks a second
    public int lookTicks = 20;
    // blocks this close to each other (any axis) are one cluster
    public int clusterGap = 2;
    // a detour's keep reach and leash are measured from this budget at least, so a cluster at arm's length still has room
    public double minBudget = 6;
}
