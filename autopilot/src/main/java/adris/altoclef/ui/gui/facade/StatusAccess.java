package adris.altoclef.ui.gui.facade;

// the status strip: what the bot is doing right now and the one button that always works
public interface StatusAccess {

    // line is the headline ("Mining iron_ore", "Idle"), detail the small text after it ("goal 120 64 -30"),
    // gamerPhase the run's phase name while #gamer is active, null otherwise
    record Activity(boolean busy, String line, String detail, String gamerPhase) {
        public boolean gamerRunning() {
            return gamerPhase != null;
        }
    }

    Activity activity();

    // same calls as #stop
    Result stop();
}
