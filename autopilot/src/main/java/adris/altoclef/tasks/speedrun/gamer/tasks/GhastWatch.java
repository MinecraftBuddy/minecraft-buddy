package adris.altoclef.tasks.speedrun.gamer.tasks;

import java.util.ArrayDeque;

// counts fireball hits so the phase can decide "this is not working, leave". one hit is bad luck, two inside the window
// is a ghast that has our number
public final class GhastWatch {
    private final int hits;
    private final double windowSeconds;
    private final ArrayDeque<Double> recent = new ArrayDeque<>();

    public GhastWatch(int hits, double windowSeconds) {
        this.hits = hits;
        this.windowSeconds = windowSeconds;
    }

    // true when this hit is the one that tips it over. the tally restarts after that so we do not flee forever
    public boolean onHit(double now) {
        while (!recent.isEmpty() && now - recent.peekFirst() > windowSeconds) {
            recent.pollFirst();
        }
        recent.addLast(now);
        if (recent.size() >= hits) {
            recent.clear();
            return true;
        }
        return false;
    }
}
