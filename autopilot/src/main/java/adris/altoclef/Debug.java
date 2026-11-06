package adris.altoclef;

import adris.altoclef.trace.Trace;
import adris.altoclef.util.ChatThrottle;
import baritone.Baritone;
import baritone.api.utils.Helper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

// everything altoclef prints to chat goes through soprano's own logger now, so it gets the same [Soprano] prefix, the
// logAsToast/useMessageTag settings and the same chat hooks as every other message. the verbosity switch
// (altoHideAllWarningLogs) is a normal soprano setting now
public class Debug {

    public static AltoClef jankModInstance;

    // errors and warnings come from tasks that tick 20 times a second, so "the same complaint every tick" is the normal
    // way for them to fail. chat gets each distinct line once per window (with a count of the ones it ate) and a hard
    // cap on the whole stream, the log file keeps the stack trace for whatever got through
    private static final ChatThrottle CHAT_THROTTLE = new ChatThrottle(10_000, 6);

    public static void logInternal(String message) {
        String line = "ALTO CLEF: " + message;
        System.out.println(line);
        Trace.record(line);
    }

    public static void logInternal(String format, Object... args) {
        logInternal(String.format(format, args));
    }

    // before there is a world there is nowhere to put chat, so it goes to the console like it always did
    private static boolean canChat() {
        return Minecraft.getInstance() != null && Minecraft.getInstance().player != null;
    }

    public static void logMessage(String message) {
        if (canChat()) {
            Trace.record("chat: " + message);
            Helper.HELPER.logDirect(message);
        } else {
            logInternal(message);
        }
    }

    public static void logMessage(String format, Object... args) {
        logMessage(String.format(format, args));
    }

    // chat only with chatDebug on, the trace keeps it either way. for the play by play nobody asked to read
    public static void logDebug(String message) {
        Trace.record("debug: " + message);
        Helper.HELPER.logDebug(message);
    }

    public static void logWarning(String message) {
        logInternal("WARNING: " + message);
        if (jankModInstance != null && !Baritone.settings().altoHideAllWarningLogs.value) {
            String line = CHAT_THROTTLE.filter("WARNING: " + ChatThrottle.firstLine(message), System.currentTimeMillis());
            if (line != null && canChat()) {
                Helper.HELPER.logDirect(line.substring("WARNING: ".length()), ChatFormatting.RED);
            }
        }
    }

    public static void logWarning(String format, Object... args) {
        logWarning(String.format(format, args));
    }

    public static void logError(String message) {
        String line = CHAT_THROTTLE.filter("[ERROR] " + ChatThrottle.firstLine(message), System.currentTimeMillis());
        // swallowed repeats don't print a stack either, 520 copies of the same trace is how the log got unreadable
        if (line == null) return;
        String stacktrace = getStack(2);
        Trace.record("ERROR: " + message);
        // the trace goes to the log file only (stderr lands there), chat gets the one line
        System.err.println(message);
        System.err.println("at:");
        System.err.println(stacktrace);
        if (canChat()) {
            Helper.HELPER.logDirect(line, ChatFormatting.RED);
        }
    }

    public static void logError(String format, Object... args) {
        logError(String.format(format, args));
    }

    public static void logStack() {
        logInternal("STACKTRACE: \n" + getStack(2));
    }

    private static String getStack(int toSkip) {
        StringBuilder stacktrace = new StringBuilder();
        for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
            if (toSkip-- <= 0) {
                stacktrace.append(ste.toString()).append("\n");
            }
        }
        return stacktrace.toString();
    }
}
