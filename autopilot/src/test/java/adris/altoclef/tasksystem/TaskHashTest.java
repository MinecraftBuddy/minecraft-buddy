package adris.altoclef.tasksystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.AltoClef;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

// equals without hashCode breaks hash sets the moment two equal tasks are different objects
public class TaskHashTest {
    private static class Named extends Task {
        final String name;

        Named(String name) {
            this.name = name;
        }

        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof Named n && n.name.equals(name);
        }

        @Override
        protected String toDebugString() {
            return name;
        }
    }

    @Test
    public void equalTasksHashEqual() {
        Named a = new Named("x");
        Named b = new Named("x");
        assertTrue(a.equals(b));
        assertEquals(a.hashCode(), b.hashCode());
    }

    // a plain pickup equals the recover subclass of it, so a hash by class would disagree with equals
    private static class NamedKid extends Named {
        NamedKid(String name) {
            super(name);
        }
    }

    @Test
    public void equalTasksOfDifferentClassesHashEqual() {
        Named a = new Named("x");
        Named kid = new NamedKid("x");
        assertTrue(a.equals(kid));
        assertEquals(a.hashCode(), kid.hashCode());
    }

    @Test
    public void aHashSetFindsAnEqualTask() {
        Set<Task> set = new HashSet<>();
        set.add(new Named("x"));
        assertTrue(set.contains(new Named("x")));
    }
}
