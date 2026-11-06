package adris.altoclef.tasks.speedrun.gamer.end;

// beds or sword. bedsLeft counts the one on the pillar too, or a placed last bed reads as none and we walk away from it. the
// pillar takes the blast, so armor and health have no say here, and no beds left is the only way to the sword
public enum DragonStrat {
    BEDS,
    SWORD;

    public static DragonStrat choose(int bedsLeft) {
        return bedsLeft > 0 ? BEDS : SWORD;
    }
}
