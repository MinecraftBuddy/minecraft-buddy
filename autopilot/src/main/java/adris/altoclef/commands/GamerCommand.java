package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.GamerTask;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.RunStateStore;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trace.Trace;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

// #gamer: beat the game. no argument starts it (or carries on from the saved run of this world), the words after it look at
// the run or poke it
public class GamerCommand extends AltoClefCommand {
    private static final List<String> SUBCOMMANDS = List.of("status", "reset", "phase", "trace");

    public GamerCommand(IBaritone baritone) {
        super(baritone, "gamer");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireMax(2);
        if (!args.hasAny()) {
            // same story as the phase jump: a second #gamer is thrown away as the same task, it must not cost the bans
            if (running(mod) != null) {
                throw new CommandInvalidStateException("The gamer is already running.");
            }
            // a new gamer task is a new run, the bans of the last one (and of whatever ran before it) go with it
            mod.getBans().clearRun("new #gamer");
            startTask(mod, new GamerTask());
            return;
        }
        String sub = args.getString().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                args.requireMax(0);
                status(mod);
                done();
            }
            case "reset" -> {
                args.requireMax(0);
                reset(mod);
                done();
            }
            case "trace" -> {
                args.requireMax(0);
                // the last few hundred log lines, to the log file and not the chat
                Trace.dump("command");
                Debug.logMessage("Dumped the last " + Trace.snapshot().size() + " log lines to the log.");
                done();
            }
            case "phase" -> {
                args.requireExactly(1);
                GamerPhase phase = parsePhase(args.getString());
                // a running gamer counts as the same task whatever phase we ask for, so the jump would be dropped on the
                // floor after wiping the run's bans. say so instead of pretending
                if (running(mod) != null) {
                    throw new CommandInvalidStateException("The gamer is running right now, stop it first and then jump.");
                }
                GamerTask task = new GamerTask(phase);
                mod.getBans().clearRun("new #gamer");
                startTask(mod, task);
            }
            default -> throw new CommandInvalidStateException("\"" + sub + "\" is not a gamer command, try one of: " + String.join(", ", SUBCOMMANDS));
        }
    }

    private static GamerTask running(AltoClef mod) {
        Task current = mod.getUserTaskChain().getCurrentTask();
        return current instanceof GamerTask g ? g : null;
    }

    private static void status(AltoClef mod) {
        GamerTask g = running(mod);
        if (g != null) {
            Debug.logMessage("The gamer is running.");
            g.statusLines().forEach(Debug::logMessage);
            return;
        }
        RunStateStore.Loaded loaded = RunStateStore.load(RunStateStore.resolvePath(mod), RunStateStore.fingerprint());
        if (!loaded.resumed()) {
            Debug.logMessage("No gamer run is saved for this world. #gamer starts one.");
            return;
        }
        RunState s = loaded.state();
        String phase = s.finished ? GamerPhase.DONE.hud() : s.stuck ? "Gave up (" + s.stuckReason + ")" : s.phase.hud();
        Debug.logMessage("A saved run is waiting, #gamer resumes it.");
        Debug.logMessage("Phase: " + phase + ", deaths: " + s.deaths.size());
        GamerTask.milestones(s).forEach(Debug::logMessage);
    }

    private static void reset(AltoClef mod) throws CommandInvalidStateException {
        if (running(mod) != null) {
            throw new CommandInvalidStateException("The gamer is running right now, stop it first.");
        }
        Path file = RunStateStore.resolvePath(mod);
        Debug.logMessage(RunStateStore.delete(file) ? "Forgot the saved run, #gamer starts from the beginning." : "There was no saved run to forget.");
    }

    // "end_prep", "end prep", "END-PREP" and "endprep" all work, the dev jump is something you type fast
    static GamerPhase parsePhase(String word) throws CommandInvalidStateException {
        String key = word.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        for (GamerPhase p : playablePhases()) {
            if (p.name().toLowerCase(Locale.ROOT).replace("_", "").equals(key)) {
                return p;
            }
        }
        throw new CommandInvalidStateException("\"" + word + "\" is not a phase, try one of: "
                + String.join(", ", playablePhases().stream().map(p -> p.name().toLowerCase(Locale.ROOT)).toList()));
    }

    // the ones you can start in, everything but the two end states
    static List<GamerPhase> playablePhases() {
        return Arrays.stream(GamerPhase.values()).filter(p -> !p.isTerminal()).toList();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper().append(SUBCOMMANDS.stream()).filterPrefix(args.getString()).sortAlphabetically().stream();
        }
        if (args.has(2) && args.getString().equalsIgnoreCase("phase") && args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append(playablePhases().stream().map(p -> p.name().toLowerCase(Locale.ROOT)))
                    .filterPrefix(args.getString())
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Beats the game";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Starts altoclef's full speedrun: gear up, enter the nether, find the stronghold and kill the dragon.",
                "It takes a long while and it will eat, fight and respawn on its own. Use stop to call it off.",
                "",
                "Progress is saved per world. After a relog, a stop or a crash, gamer carries on from the phase it was in.",
                "",
                "Usage:",
                "> gamer - Start, or resume the saved run of this world.",
                "> gamer status - Show the phase and what has been found so far.",
                "> gamer reset - Forget the saved run (not while it is running).",
                "> gamer phase <name> - Start in that phase, for testing one part of the run.",
                "> gamer trace - Write the last 300 log lines to the log file.",
                "",
                "Examples:",
                "> gamer phase locate"
        );
    }
}
