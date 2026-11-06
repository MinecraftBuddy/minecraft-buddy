package adris.altoclef.tasks.speedrun.gamer.config;

// owner: end worker (END_PREP, DRAGON)
public class EndConfig {
    // beds in the bag before the End, the pillar fight goes through them (wool is collected in IRON: 3 per bed). END_PREP does not
    // walk in with fewer, whatever it takes to find them
    public int beds = 9;
    // one more bed to set the spawn near the portal, so a death in the End costs a short walk
    public boolean placeSpawnNearPortal = true;
    public double spawnBedRange = 8;
    // placing the extra bed gets this long from the moment its step starts, then we go without a spawn bed (the fight's beds have no clock)
    public double spawnBedBudgetSeconds = 300;
    // an errand (or the walk back) farther than this from the portal wants the food floor first, and the phase budget waits for the walk
    public double farErrandBlocks = 150;
    public int buildBlocks = 64;
    // the gate: below this we collect up to buildBlocks. the bridge from the arrival platform eats ~30, what's left is the fight's
    public int minBuildBlocks = 48;
    // inside the End: below this and the dragon not coming down, mine end stone close by. all the fight needs is a floor under the stand
    public int endMinBlocks = 8;
    // cooked steak is 8, the fight is minutes long so this is only here so we do not walk in starving
    public int minFoodUnits = 24;
    // the surface prep ahead of the dig tops up to this while its 5 minute cap lasts. soft: never a gate, never ahead of the beds.
    // 24 is a bag that gets to the fight empty, the walk to the portal alone can eat 8
    public int wantFoodUnits = 56;
    // END_PREP wants this many armor points for the walk in. the bed fight itself doesn't ask, the pillar takes the blast
    public int bedMinArmor = 10;
    // nothing reads this since beds stopped caring about armor, kept so old config files still load
    public int bedArmorPerAttempt = 4;
    // END_PREP below bedMinArmor makes iron armor for this long (ticks the step ran), then walks to the End anyway
    public double armorFloorSeconds = 360;
    // dragon fight attempts (a death sends us back to END_PREP) before the run is STUCK
    public int attempts = 3;
    // nothing reads this (PillarBed.HEAD_RANGE is the real one), kept so old config files still load
    public double dragonHeadCloseEnoughClickBedRange = 5.3;
    // exit portal not needed: chunk (0,0) loaded and the dragon entity gone this long = dead
    public double dragonGoneSeconds = 5;
    // dropped items despawn after 6000 ticks, leave margin before we count them as gear we still own
    public int dropLifetimeTicks = 5400;
    // right after a death the entity list is empty for a moment, do not believe it before this
    public double dropEmptyWaitSeconds = 2;
    // total time per End visit spent walking to dropped gear, so an unreachable pickaxe cannot eat the fight
    public double pickupBudgetSeconds = 120;
}
