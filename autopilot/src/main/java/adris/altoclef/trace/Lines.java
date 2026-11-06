package adris.altoclef.trace;

// the text of the lines the monitors and switch traces write, in one place so the shapes are pinned by a test and the
// callers only build them when they are about to say them
public final class Lines {

    private Lines() {
    }

    // a null name is nobody, a NaN priority is a chain that was not active to ask
    public static String chain(String name, float priority) {
        if (name == null) {
            return "none";
        }
        return name + ":" + (Float.isNaN(priority) ? "off" : Float.toString(priority));
    }

    public static String chainSwitch(String from, float fromPriority, String to, float toPriority, String next, float nextPriority) {
        return "switch chain from=" + chain(from, fromPriority) + " to=" + chain(to, toPriority) + " next=" + chain(next, nextPriority);
    }

    public static String repeat(String taskClass, String state, int times) {
        return "repeat: " + taskClass + " \"" + state + "\" x" + times + " in 60 s";
    }

    public static String startStorm(String taskClass) {
        return "MONITOR: start storm " + taskClass + " x" + (StartStormWatch.LIMIT + 1) + " in 60 s";
    }

    // by is who stopped it most, parent says whether that was a parent dropping it (and not another task taking over). null by = nobody to blame
    public static String startStorm(String taskClass, String by, boolean parent) {
        if (by == null) {
            return startStorm(taskClass);
        }
        return startStorm(taskClass) + ", mostly " + (parent ? "dropped by parent " : "cut off by ") + by;
    }

    public static String cursorStuck(String item, int count, long seconds) {
        return "MONITOR: cursor stuck " + item + " x" + count + " for " + seconds + " s, no container screen open";
    }

    // everything the stand line says, read off the game by the caller. strings are as they come: this decides what null
    // and an empty string look like
    public record Stand(int ticks, double x, double y, double z, String move, int pathIndex, int pathLength, String goal,
                        boolean paused, String lastCmd, String by, boolean interactionPaused, String keys, String held, boolean sneaking, String screen,
                        String using, boolean onGround, boolean inWater, String calc, String chain, String task,
                        String last, String pausedBy, String prepared) {
        public Stand(int ticks, double x, double y, double z, String move, int pathIndex, int pathLength, String goal,
                     boolean paused, String lastCmd, String by, boolean interactionPaused, String keys, String held, boolean sneaking, String screen,
                     String using, boolean onGround, boolean inWater, String calc, String chain, String task,
                     String last) {
            this(ticks, x, y, z, move, pathIndex, pathLength, goal, paused, lastCmd, by, interactionPaused, keys, held, sneaking, screen,
                    using, onGround, inWater, calc, chain, task, last, null, null);
        }
    }

    private static final int GOAL_MAX = 60;
    private static final int TASK_MAX = 100;

    // one row, so a grep for "standing on a live path" gets the whole story
    public static String standing(Stand s) {
        StringBuilder sb = new StringBuilder(480);
        sb.append("MONITOR: standing on a live path ").append(s.ticks()).append("t at ").append(one(s.x())).append(' ')
                .append(one(s.y())).append(' ').append(one(s.z()));
        sb.append(" | move=").append(word(s.move()));
        // only when there is something to say, most stands have neither
        if (s.prepared() != null) sb.append(" prepared=").append(flat(s.prepared()));
        sb.append(" | pathPos=").append(s.pathIndex()).append('/').append(s.pathLength());
        sb.append(" goal=").append(clip(s.goal(), GOAL_MAX));
        sb.append(" | paused=").append(s.paused()).append(" lastCmd=").append(word(s.lastCmd())).append(" by=").append(word(s.by()));
        if (s.pausedBy() != null) sb.append(" pausedBy=").append(flat(s.pausedBy()));
        sb.append(" | interactionPaused=").append(s.interactionPaused());
        sb.append(" | keys=").append(word(s.keys())).append(" held=").append(word(s.held())).append(" sneak=").append(s.sneaking());
        sb.append(" | screen=").append(word(s.screen()));
        sb.append(" | using=").append(word(s.using())).append(" onGround=").append(s.onGround()).append(" inWater=").append(s.inWater());
        sb.append(" | calc=").append(word(s.calc()));
        sb.append(" | chain=").append(word(s.chain())).append(" task=").append(clip(s.task(), TASK_MAX));
        sb.append(" | last=").append(word(s.last()));
        return sb.toString();
    }

    public static String standOver(int ticks, boolean moved) {
        return "MONITOR: stand over after " + ticks + "t (" + (moved ? "moved" : "path gone") + ")";
    }

    // owner is who had the tick: the movement, or one of the executor's own segments. state and onCurrent are "?" when
    // the game would not tell us
    public static String move(String movement, String src, String dest, String state, String onCurrent, String owner) {
        return word(movement) + " " + word(src) + "->" + word(dest) + " state=" + word(state) + " onCurrent=" + word(onCurrent)
                + " owner=" + word(owner);
    }

    // what baritone last said and how long ago, in ticks. none when it never said anything
    public static String lastDebug(String text, long agoTicks) {
        if (text == null || text.isEmpty()) {
            return "none";
        }
        return clip(text, TASK_MAX) + " (" + Math.max(0, agoTicks) + "t ago)";
    }

    private static String one(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String word(String s) {
        return s == null || s.isEmpty() ? "none" : flat(s);
    }

    // a newline would end the row, and a long goal toString is a wall of text
    private static String clip(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "none";
        }
        String flat = flat(s);
        return flat.length() <= max ? flat : flat.substring(0, max - 3) + "...";
    }

    private static String flat(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}
