package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;

import java.util.ArrayList;
import java.util.List;

// the engine's side of a handler call, written down so a test can read what the handler said
public class StubContext implements GamerContext {
    public final GamerConfig cfg = new GamerConfig();
    {
        cfg.overworld.netherSpareShields = 0;
        cfg.overworld.netherSpareSwords = 0;
    }
    public final RunState state = new RunState();
    public final FakeFacts facts = new FakeFacts();
    public final List<String> progress = new ArrayList<>();
    public final List<String> fails = new ArrayList<>();
    public final List<String> logs = new ArrayList<>();
    public int attempt = 1;
    public double seconds;
    public int saves;

    @Override
    public GamerConfig cfg() {
        return cfg;
    }

    @Override
    public RunState state() {
        return state;
    }

    @Override
    public GamerFacts facts() {
        return facts;
    }

    @Override
    public int attempt() {
        return attempt;
    }

    @Override
    public double secondsInPhase() {
        return seconds;
    }

    @Override
    public void save() {
        saves++;
    }

    @Override
    public void progress(String what) {
        progress.add(what);
    }

    @Override
    public void fail(String reason) {
        fails.add(reason);
    }

    @Override
    public void log(String line) {
        logs.add(line);
    }

    @Override
    public void walkOnEndPortal(boolean on) {
    }
}
