package adris.altoclef.tasks.speedrun.gamer;

// what the planner's held flags remember from the last look, so the plan does not flip at the line (WorkbenchRules.returnRadius /
// bandRadius, StationChoice.HOLD). MinecraftFacts owns one and building a StationVerdict is the only thing that moves it
final class StationLatches {
    // a table is recorded and was out of NEAR last look, see WorkbenchRules.returnRadius
    boolean tableFar;
    // the table was held last look by the walk back and not by being near (StationVerdict.of): it stays held out to the walk back
    // line + StationChoice.HOLD, the way the task keeps the one it is walking to
    boolean tableBack;
    // last look's answer for a furnace and a smoker, null while none is recorded (WorkbenchRules.bandRadius)
    Boolean furnaceHeld;
    Boolean smokerHeld;

    void clear() {
        tableFar = false;
        tableBack = false;
        furnaceHeld = null;
        smokerHeld = null;
    }
}
