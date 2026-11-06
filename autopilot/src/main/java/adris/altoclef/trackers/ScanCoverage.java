package adris.altoclef.trackers;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

// which things a finished scan has actually looked at. "the tracker has none of these" can mean "there are none" or
// "nobody has looked yet", and a task that wanders on the second one is just walking away from the logs in front of it.
// generic so a test doesn't need a whole minecraft to poke it
class ScanCoverage<T> {

    private final Set<T> covered = new HashSet<>();

    // a scan that finished and was snapshotted from this list of tracked things
    void scanned(Collection<T> scannedThings) {
        covered.addAll(scannedThings);
    }

    // untracked means the cache for it goes stale and nothing keeps it fresh, so the next track is a first look again
    void forget(T thing) {
        covered.remove(thing);
    }

    void clear() {
        covered.clear();
    }

    boolean covers(T thing) {
        return covered.contains(thing);
    }

    boolean coversAll(T[] things) {
        for (T thing : things) {
            if (!covered.contains(thing)) return false;
        }
        return true;
    }

    // true if anything in `tracked` has never been through a scan
    boolean anyUncovered(Collection<T> tracked) {
        for (T thing : tracked) {
            if (!covered.contains(thing)) return true;
        }
        return false;
    }
}
