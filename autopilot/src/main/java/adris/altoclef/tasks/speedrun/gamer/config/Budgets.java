package adris.altoclef.tasks.speedrun.gamer.config;

import adris.altoclef.tasks.speedrun.gamer.GamerPhase;

// minutes of game time a phase may take per attempt before the watchdog steps in. generous on purpose: a timeout
// costs a retry, but a budget that is too tight turns a slow cave into a failed run
public class Budgets {
    public double gather = 8;
    public double iron = 22;
    public double portal = 14;
    public double nether = 40;
    public double eyes = 3;
    public double returnHome = 8;
    public double locate = 30;
    public double room = 18;
    public double open = 6;
    public double endPrep = 12;
    public double dragon = 10;

    public double minutes(GamerPhase phase) {
        return switch (phase) {
            case GATHER -> gather;
            case IRON -> iron;
            case PORTAL -> portal;
            case NETHER -> nether;
            case EYES -> eyes;
            case RETURN -> returnHome;
            case LOCATE -> locate;
            case ROOM -> room;
            case OPEN -> open;
            case END_PREP -> endPrep;
            case DRAGON -> dragon;
            default -> 0;
        };
    }
}
