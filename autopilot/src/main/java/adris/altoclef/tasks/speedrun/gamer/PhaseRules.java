package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// the "which phase should we be in" half of the engine, with no minecraft in it. only isDone / regressTo of the handlers
// and the pure snapshot are consulted, so a test can run a whole scripted game with fakes
public final class PhaseRules {
    public enum Kind {
        STAY,
        ADVANCE,
        REGRESS
    }

    public record Decision(Kind kind, GamerPhase to, String reason) {
        static final Decision STAY = new Decision(Kind.STAY, null, "");
    }

    // a resumed run that already has everything skips ahead this many phases per tick, then the next tick carries on
    public static final int MAX_CASCADE = 4;
    // the same phase may only regress once in this long, otherwise "A needs B" and "B is done so go to A" ping pong forever
    public static final double REGRESS_COOLDOWN_SECONDS = 60;

    private final Map<GamerPhase, PhaseHandler> byPhase = new EnumMap<>(GamerPhase.class);
    private final Map<GamerPhase, Double> lastRegress = new EnumMap<>(GamerPhase.class);

    public PhaseRules(List<PhaseHandler> handlers) {
        for (PhaseHandler h : handlers) {
            byPhase.put(h.phase(), h);
        }
    }

    public PhaseHandler handler(GamerPhase phase) {
        return byPhase.get(phase);
    }

    // advance first, because a phase that is done has nothing to regress from. REGRESS records its time here, so
    // asking again inside the cooldown says STAY (the caller does not have to remember anything)
    public Decision decide(GamerPhase current, GamerFacts facts, RunState state, GamerConfig cfg, double nowSeconds) {
        return decide(current, facts, state, cfg, null, nowSeconds);
    }

    // the engine's way: the phases are asked isDone with the tick's context, so they read the tick's food plan and wood budget
    // and not ones of their own
    public Decision decide(GamerPhase current, GamerContext ctx, double nowSeconds) {
        return decide(current, ctx.facts(), ctx.state(), ctx.cfg(), ctx, nowSeconds);
    }

    private Decision decide(GamerPhase current, GamerFacts facts, RunState state, GamerConfig cfg, GamerContext ctx, double nowSeconds) {
        if (current.isTerminal()) {
            return Decision.STAY;
        }
        GamerPhase furthest = cascade(current, facts, state, cfg, ctx);
        if (furthest != current) {
            return new Decision(Kind.ADVANCE, furthest, "");
        }
        return regress(current, facts, state, cfg, nowSeconds);
    }

    private GamerPhase cascade(GamerPhase current, GamerFacts facts, RunState state, GamerConfig cfg, GamerContext ctx) {
        GamerPhase p = current;
        for (int steps = 0; steps < MAX_CASCADE && !p.isTerminal(); steps++) {
            PhaseHandler h = byPhase.get(p);
            if (h == null || !(ctx == null ? h.isDone(facts, state, cfg) : h.isDone(ctx))) {
                break;
            }
            // DONE is the last ordinal before STUCK, so the only way onto it is the DRAGON handler saying it is done
            p = GamerPhase.values()[p.ordinal() + 1];
        }
        return p;
    }

    private Decision regress(GamerPhase current, GamerFacts facts, RunState state, GamerConfig cfg, double nowSeconds) {
        PhaseHandler h = byPhase.get(current);
        if (h == null) {
            return Decision.STAY;
        }
        Optional<GamerPhase> target = h.regressTo(facts, state, cfg);
        if (target.isEmpty() || target.get().isTerminal() || target.get().ordinal() >= current.ordinal()) {
            return Decision.STAY;
        }
        Double last = lastRegress.get(current);
        if (last != null && nowSeconds - last < REGRESS_COOLDOWN_SECONDS) {
            return Decision.STAY;
        }
        lastRegress.put(current, nowSeconds);
        return new Decision(Kind.REGRESS, target.get(), "going back to " + target.get().name());
    }
}
