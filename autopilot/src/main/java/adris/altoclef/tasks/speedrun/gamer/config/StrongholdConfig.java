package adris.altoclef.tasks.speedrun.gamer.config;

// owner: stronghold worker (LOCATE, ROOM, OPEN) and the estimator params
public class StrongholdConfig {
    // bearing noise we assume for an eye throw, degrees. measure it in a creative world before trusting it lower
    public double sigmaDeg = 0.25;
    public int maxThrows = 14;
    // a throw where no eye entity ever showed up (3 s) does not count as a ray, this many in a row = fail
    public int maxEmptyThrows = 3;
    // snap the estimate to the chunk lattice once it is this tight (blocks)
    public double snapRadius = 12;
    // the deepest the dig down the start column goes if it never sees bricks (the stronghold bottoms out around minY + 10)
    public int digFloorY = -54;
    public double perChunkSeconds = 90;
    // chunks around the start chunk the stronghold can reach (112 blocks)
    public int spiralRadiusChunks = 7;
    // go through the nether for the long walk. off: FastTravelTask ignores its threshold and needs a second portal
    public boolean netherFastTravel = false;
    public boolean breakSilverfishSpawner = true;
    // how long digging down the start column may take before we search sideways at whatever depth we got to
    // (surface to the floor is ~125 blocks)
    public double digDownSeconds = 420;
    // after the ring centre is known: this long to walk over and see the frames before the rest are taken as given
    public double approachSeconds = 45;
    // walking to the start chunk when we are further than this from it (blocks)
    public double startReachBlocks = 8;
}
