package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasksystem.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

// a phase handler made of lambdas, with a log of what the engine did to it
public class FakeHandler implements PhaseHandler {
    public final GamerPhase phase;
    public Predicate<GamerFacts> done = f -> false;
    public Optional<GamerPhase> regress = Optional.empty();
    public Timeout onTimeout = null;
    public double stall = 120;
    public boolean holdsBudget;
    public RuntimeException tickThrows;
    public final List<String> events = new ArrayList<>();
    // when set, every event is also written here as "PHASE:event", to see the order across handlers and the host
    public List<String> shared;
    public int ticks;

    private void event(String e) {
        events.add(e);
        if (shared != null) {
            shared.add(phase + ":" + e);
        }
    }

    public FakeHandler(GamerPhase phase) {
        this.phase = phase;
    }

    public FakeHandler doneWhen(Predicate<GamerFacts> p) {
        done = p;
        return this;
    }

    public FakeHandler alwaysDone() {
        done = f -> true;
        return this;
    }

    @Override
    public boolean holdsBudget() {
        return holdsBudget;
    }

    @Override
    public GamerPhase phase() {
        return phase;
    }

    @Override
    public String hud() {
        return phase.hud();
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return done.test(facts);
    }

    @Override
    public Optional<GamerPhase> regressTo(GamerFacts facts, RunState state, GamerConfig cfg) {
        return regress;
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        ticks++;
        if (tickThrows != null) {
            throw tickThrows;
        }
        return null;
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        event("enter" + ctx.attempt());
    }

    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
        event("exit");
    }

    @Override
    public double stallSeconds() {
        return stall;
    }

    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        event("timeout" + attempt);
        return onTimeout != null ? onTimeout : PhaseHandler.super.onTimeout(ctx, attempt, reason);
    }

    // one of each phase that has a handler, GATHER..DRAGON
    public static List<FakeHandler> full() {
        List<FakeHandler> out = new ArrayList<>();
        for (GamerPhase p : GamerPhase.values()) {
            if (!p.isTerminal()) {
                out.add(new FakeHandler(p));
            }
        }
        return out;
    }
}
