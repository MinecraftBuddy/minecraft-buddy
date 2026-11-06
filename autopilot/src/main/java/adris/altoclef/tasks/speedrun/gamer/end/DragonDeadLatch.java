package adris.altoclef.tasks.speedrun.gamer.end;

// "the dragon is dead", in two strengths. HARD: the exit portal blocks exist, latched for good, the only thing that may
// be saved to disk (a wrong "dead" in RunState ends the run as DONE after one fall into the void). SOFT: we HAVE seen the
// dragon and it has been gone for goneSeconds with chunk (0,0) loaded. the client only tracks entities ~10 chunks out, so
// from the arrival platform the dragon is "gone" half of its lap: soft is reversible (seeing the dragon again clears it)
// and never persisted. an unloaded chunk resets the timer, the dragon could be anywhere
public class DragonDeadLatch {
    private final double goneSeconds;
    private boolean seen;
    private boolean portalSeen;
    private boolean goneDead;
    private double goneSince = -1;

    public DragonDeadLatch(double goneSeconds) {
        this.goneSeconds = goneSeconds;
    }

    // true = dead in either strength
    public boolean update(boolean exitPortalPresent, boolean dragonVisible, boolean centerChunkLoaded, double now) {
        if (portalSeen) {
            return true;
        }
        if (exitPortalPresent) {
            portalSeen = true;
        } else if (dragonVisible) {
            seen = true;
            goneSince = -1;
            goneDead = false;
        } else if (seen && centerChunkLoaded) {
            if (goneSince < 0) {
                goneSince = now;
            }
            goneDead = now - goneSince >= goneSeconds;
        } else {
            goneSince = -1;
            goneDead = false;
        }
        return isDead();
    }

    public boolean isDead() {
        return portalSeen || goneDead;
    }

    // the hard one: this is what RunState.dragonDead may be set from
    public boolean exitPortalSeen() {
        return portalSeen;
    }

    public boolean hasSeenDragon() {
        return seen;
    }

    // a run that already knows (RunState.dragonDead, which only ever came from the exit portal)
    public void markDead() {
        portalSeen = true;
    }

    public void reset() {
        seen = false;
        portalSeen = false;
        goneDead = false;
        goneSince = -1;
    }
}
