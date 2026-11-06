package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.EscapeRoute.Plan;
import adris.altoclef.tasks.speedrun.gamer.EscapeRoute.Route;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class EscapeRunTest {
    private static Route route(int placements, boolean wall) {
        return new Route(List.of(new int[]{0, 70, 0}), placements, wall);
    }

    @Test
    public void longBridgeOverLavaEndsTheRun() {
        assertEquals(EscapeRun.Verdict.LAKE, EscapeRun.verdict(new Plan(route(40, false), true), () -> true));
    }

    @Test
    public void noRouteOverLavaEndsTheRun() {
        assertEquals(EscapeRun.Verdict.LAKE, EscapeRun.verdict(new Plan(null, true), () -> true));
    }

    @Test
    public void cheapWallOverLavaStillDigs() {
        assertEquals(EscapeRun.Verdict.BRIDGE, EscapeRun.verdict(new Plan(route(0, true), true), () -> true));
    }

    @Test
    public void longBridgeWithoutLavaStillBridges() {
        assertEquals(EscapeRun.Verdict.BRIDGE, EscapeRun.verdict(new Plan(route(40, false), true), () -> false));
    }

    @Test
    public void noRouteWithoutLavaIsStranded() {
        assertEquals(EscapeRun.Verdict.STRANDED, EscapeRun.verdict(new Plan(null, true), () -> false));
    }

    @Test
    public void floorBesideThePortalNeverAsksAboutLava() {
        assertEquals(EscapeRun.Verdict.STAY, EscapeRun.verdict(new Plan(route(0, false), false), () -> {
            throw new AssertionError("asked");
        }));
    }
}
