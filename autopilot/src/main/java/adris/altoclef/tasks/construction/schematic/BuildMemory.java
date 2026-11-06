package adris.altoclef.tasks.construction.schematic;

import java.util.LinkedHashSet;
import java.util.Set;

// what a survival build remembers between starts. the schematics tab keeps one per world and file (PlacementStore's
// choices), so a stop and a start again, or a restart of the game, picks up the same answers. game thread only
public interface BuildMemory {

    // item ids the player unchecked in the tab, read once when the build starts
    Set<String> excluded();

    // the answer to "go to the nether for these?", null while nobody has asked
    Boolean nether();

    // yes, or no plus the placed items that get left out because of it (they join excluded, the tab shows them off)
    void netherAnswered(boolean yes, Set<String> leftOut);

    // the build is done. the answer was for this build, the next one asks again
    void finished();

    // for tests and anything else without a tab behind it: remembers for as long as the object lives and no longer
    static BuildMemory inMemory(Set<String> excluded) {
        return new BuildMemory() {
            private final Set<String> out = new LinkedHashSet<>(excluded);
            private Boolean answer;

            @Override
            public Set<String> excluded() {
                return Set.copyOf(out);
            }

            @Override
            public Boolean nether() {
                return answer;
            }

            @Override
            public void netherAnswered(boolean yes, Set<String> leftOut) {
                answer = yes;
                out.addAll(leftOut);
            }

            @Override
            public void finished() {
                answer = null;
            }
        };
    }
}
