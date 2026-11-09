package adris.altoclef.util.helpers;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the registry of the run memories: a size per name, every clear run, one that throws hides neither the rest nor their sizes.
// (what is registered here stays for the JVM: a named field must be a real one, RunMemoryGuardTest checks them all)
public class RunMemoryTest {
    @Test
    public void measuresAndClearsEveryRegisteredMemory() {
        AtomicInteger a = new AtomicInteger(3), b = new AtomicInteger(1);
        RunMemory.register("test_a", a::get, () -> a.set(0));
        RunMemory.register("test_broken", () -> {
            throw new IllegalStateException("no world");
        }, () -> {
            throw new IllegalStateException("no world");
        });
        RunMemory.register("test_b", b::get, () -> b.set(0));
        Map<String, Long> sizes = RunMemory.sizes();
        assertEquals(3L, (long) sizes.get("test_a"));
        assertEquals(-1L, (long) sizes.get("test_broken"));
        assertEquals(1L, (long) sizes.get("test_b"));
        List<String> failed = RunMemory.clearAll();
        assertEquals(0, a.get());
        assertEquals(0, b.get());
        assertTrue(failed.stream().anyMatch(f -> f.startsWith("test_broken: ")));
    }

    @Test
    public void aSecondRegistrationReplacesTheFirst() {
        RunMemory.register("test_twice", () -> 1, () -> { });
        RunMemory.register("test_twice", () -> 2, () -> { }, "adris.altoclef.util.helpers.RouteRules#outSaid");
        assertEquals(1, RunMemory.all().stream().filter(m -> m.name().equals("test_twice")).count());
        assertEquals(2L, (long) RunMemory.sizes().get("test_twice"));
        assertEquals(List.of("adris.altoclef.util.helpers.RouteRules#outSaid"), RunMemory.all().stream().filter(m -> m.name().equals("test_twice")).findFirst().get().fields());
    }

    @Test
    public void theForkRegistersWhatTheBriefNames() {
        List<String> names = RunMemory.all().stream().map(RunMemory.Memory::name).toList();
        for (String n : List.of("trackers", "portals_and_dry_land", "bans", "baritone_cache", "brew_stand", "portal_frame",
                "land_steers", "legit_mine_y", "route", "obtain_sweep")) {
            assertTrue(n + " registered", names.contains(n));
        }
    }
}
