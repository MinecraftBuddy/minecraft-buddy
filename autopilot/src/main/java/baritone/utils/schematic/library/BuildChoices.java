package baritone.utils.schematic.library;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

// what the player chose about building one file in one world, next to where it's placed. excluded = item ids left out
// of the build (the unchecked rows), empty = everything is in. nether = the answer to "go to the nether for these?",
// null while nobody has asked. immutable, every edit hands back a new one
public record BuildChoices(Set<String> excluded, Boolean nether) {

    public static final BuildChoices NONE = new BuildChoices(Set.of(), null);

    public BuildChoices {
        // insertion order, so the file and the chat lines list them the way they were unchecked
        excluded = Collections.unmodifiableSet(new LinkedHashSet<>(excluded));
    }

    public BuildChoices(Set<String> excluded) {
        this(excluded, null);
    }

    // nothing to write down: the file only keeps choices somebody actually made
    public boolean isEmpty() {
        return excluded.isEmpty() && nether == null;
    }

    // ticking a row back on after a "no" is the player changing their mind, so the next start asks again. otherwise the
    // stored no would quietly untick it again the moment the build saw it was short
    public BuildChoices withIncluded(String itemId, boolean included) {
        Set<String> next = new LinkedHashSet<>(excluded);
        if (included) {
            next.remove(itemId);
        } else {
            next.add(itemId);
        }
        boolean rechecked = included && excluded.contains(itemId);
        return new BuildChoices(next, rechecked && Boolean.FALSE.equals(nether) ? null : nether);
    }

    public BuildChoices withNether(Boolean answer) {
        return new BuildChoices(excluded, answer);
    }
}
