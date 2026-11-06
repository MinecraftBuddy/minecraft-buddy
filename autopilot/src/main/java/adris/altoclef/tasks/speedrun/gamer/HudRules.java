package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules;

import java.util.ArrayList;
import java.util.List;

// the sums behind the gamer card, with no game in them so the tests can poke at every edge. the card itself is
// GamerHudOverlay, what goes on it is GamerHud
public final class HudRules {
    // GATHER..DRAGON, the dots on the card. DONE and STUCK are not phases you are in, they are how it ended
    public static final int PHASES = GamerPhase.DRAGON.ordinal() + 1;
    // kit rows on the card at most, current need first. more than this and the card is a second task tree
    public static final int MAX_ROWS = 5;
    // furnace rows at most: a split smelt runs three (SmeltSplit.MAX_LOADS). with the coal row that leaves the head need one kit row,
    // which is the floor kitRowBudget keeps anyway
    public static final int MAX_FURNACES = 3;
    // a need that just got satisfied stays on the card this long, dim with green numbers, so you see it land
    public static final long DONE_LINGER_TICKS = 8 * 20;
    // the card after a win stays up this long (wall clock, nothing on it counts down). the clock only starts once it can be
    // seen, the credits cover the whole screen
    public static final long WIN_LINGER_MILLIS = 30_000;

    public enum Dot {
        DONE, NOW, LATER
    }

    // how the title row fits in the card's width: the title at 2x with a unit between the letters and the full clock
    // ("9:41 / 22:00"), or the letters packed, or the clock without its budget (the bar under it still says). the title
    // never changes size, a phase name that is bigger in one phase than the next would look like a different card
    public enum TitleFit {
        SPACED, TIGHT, SHORT_CLOCK
    }

    // the gap between the title and its clock, and between a row's name and its number
    public static final int GAP = 4;

    // glyphs2x: the sum of the letter widths at 2x. letters: how many, each gets one unit after it but the last.
    // "END PREP" with "11:59 / 12:00" is 92 + 7 + 4 + 66 = 169 against 144 inside, NETHER with a ten minute clock is 147
    public static TitleFit titleFit(int glyphs2x, int letters, int clockWidth, int shortClockWidth, int inner) {
        int spaced = glyphs2x + Math.max(0, letters - 1);
        if (spaced + GAP + clockWidth <= inner) {
            return TitleFit.SPACED;
        }
        if (glyphs2x + GAP + clockWidth <= inner) {
            return TitleFit.TIGHT;
        }
        return TitleFit.SHORT_CLOCK;
    }

    // kit rows the card has room for next to the furnace and coal rows: MAX_ROWS in all, and the need being worked
    // always has its row
    public static int kitRowBudget(int furnaceRows, boolean coalRow) {
        return Math.max(1, MAX_ROWS - furnaceRows - (coalRow ? 1 : 0));
    }

    private HudRules() {
    }

    // the dim line at the bottom of the card, so a screenshot says which jar it came from
    public static String buildTag(String id) {
        return "build " + (id == null || id.isBlank() ? "unknown" : id.trim());
    }

    // "9:41". hours never happen in a phase, the budgets are minutes
    public static String clock(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        return (s / 60) + ":" + pad(s % 60);
    }

    // "22:00" for 22, "8:30" for 8.5. same shape as the clock next to it so the two read as one pair
    public static String budget(double minutes) {
        return clock(Math.max(0, minutes) * 60);
    }

    private static String pad(long n) {
        return n < 10 ? "0" + n : Long.toString(n);
    }

    // 0..1, and 0 for a total that makes no sense (a phase with no budget)
    public static double fraction(double done, double total) {
        if (total <= 0 || done <= 0) {
            return 0;
        }
        return Math.min(1, done / total);
    }

    // pixels of a bar's inside that get filled. a fraction that is anything at all shows at least one pixel, a bar that
    // reads empty while 2% in looks broken
    public static int fillWidth(int innerWidth, double fraction) {
        if (innerWidth <= 0 || fraction <= 0) {
            return 0;
        }
        return Math.max(1, Math.min(innerWidth, (int) Math.round(innerWidth * Math.min(1, fraction))));
    }

    // one per playable phase. DONE lights them all, STUCK leaves the one it died in lit so you can see where
    public static Dot[] dots(GamerPhase phase) {
        Dot[] out = new Dot[PHASES];
        int now = phase == GamerPhase.DONE ? PHASES : Math.min(phase.ordinal(), PHASES - 1);
        for (int i = 0; i < PHASES; i++) {
            out[i] = i < now ? Dot.DONE : i == now ? Dot.NOW : Dot.LATER;
        }
        return out;
    }

    // "IRON", "END PREP"
    public static String title(GamerPhase phase) {
        return phase.name().replace('_', ' ');
    }

    // the clock next to the title: "9:41 / 22:00" against the phase's budget, and once the run is won just the total, there is no
    // budget left to be against
    public static String titleClock(GamerPhase phase, double seconds, double budgetMinutes) {
        return phase == GamerPhase.DONE ? clock(seconds) : clock(seconds) + " / " + budget(budgetMinutes);
    }

    // the bar under the title: time against the budget, full once the run is won
    public static double titleFraction(GamerPhase phase, double seconds, double budgetMinutes) {
        return phase == GamerPhase.DONE ? 1 : fraction(seconds, budgetMinutes * 60);
    }

    // the whole run in seconds: game time since the run began, so a fight or a meal the engine sat out still counts, and a relog
    // does not. a save from before the start was written down (startedGameTime < 0) only knows the ticks the engine ran
    public static double runSeconds(long startedGameTime, long nowGameTime, long runTicks) {
        long ticks = startedGameTime >= 0 && nowGameTime >= startedGameTime ? nowGameTime - startedGameTime : runTicks;
        return Math.max(0, ticks) / 20.0;
    }

    // the clock of the card after a win. it starts on the first frame the card can be seen: a frame under the credits (covered)
    // neither starts it nor draws, and the thirty seconds count from the first one that is not
    public static final class Linger {
        private long shownAt = -1;

        // draw the card this frame. false while covered, and for good once the time is up
        public boolean visible(long now, boolean covered) {
            if (covered) {
                return false;
            }
            if (shownAt < 0) {
                shownAt = now;
            }
            return !over(now);
        }

        // the time is up, the card can be dropped
        public boolean over(long now) {
            return shownAt >= 0 && now - shownAt >= WIN_LINGER_MILLIS;
        }
    }

    // whole blocks of straight line, for the recovery words
    public static int blocksAway(double dx, double dy, double dz) {
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    // ", 47 blocks". nothing for an unknown distance or when we are standing on it
    private static String away(int blocks) {
        return blocks < 1 ? "" : ", " + blocks + (blocks == 1 ? " block" : " blocks");
    }

    // the action line while a death recovery has the wheel, the same words the tree uses plus how far there is to go
    public static String recoveryWords(int blocks) {
        return "Getting our stuff back" + away(blocks);
    }

    // and for the walk back into the nether for a pile: one line per stage of the trip. blocks is only known for the two stages
    // that are in the nether with the pile (-1 for the rest). a stage that does not parse is just the plain recovery
    public static String tripWords(NetherTripRules.Stage stage, int blocks) {
        if (stage == null) {
            return recoveryWords(blocks);
        }
        return switch (stage) {
            case BLOCKS -> "Getting blocks for the walk";
            case PICK -> "Getting a pickaxe";
            case FOOD -> "Getting food for the walk";
            case HEAL -> "Healing up before the walk";
            case PORTAL -> "Heading back to the nether";
            case WALK -> "Walking to our stuff" + away(blocks);
            case RECOVER -> recoveryWords(blocks);
            case WEAR -> "Putting our armor back on";
            case HOME -> "Heading home empty handed";
        };
    }

    // whole blocks still to run, from where the run began. 0 once the distance is covered, the run may still be on
    // (nothing near for two seconds is the other half of it) but there is nothing left to count down
    public static int blocksToGo(double x, double z, double originX, double originZ, double distance) {
        double gone = Math.hypot(x - originX, z - originZ);
        return (int) Math.max(0, Math.ceil(distance - gone));
    }

    // the kit rows: the need being worked first, then the rest in plan order, then whatever just finished, capped
    public static <T> List<T> rows(List<T> needs, T current, List<T> done, int cap) {
        List<T> out = new ArrayList<>(cap);
        if (current != null && needs.contains(current)) {
            out.add(current);
        }
        for (T need : needs) {
            if (out.size() >= cap) {
                break;
            }
            if (!out.contains(need)) {
                out.add(need);
            }
        }
        for (T d : done) {
            if (out.size() >= cap) {
                break;
            }
            out.add(d);
        }
        return out;
    }

    // seconds until a furnace job is done, whole, never under 0
    public static int secondsLeft(long doneTick, long now) {
        return (int) Math.max(0, (doneTick - now + 19) / 20);
    }

    // how far along a furnace batch is. a job that was never timed (no done tick) reads as 0, not as finished
    public static double furnaceFraction(long startTick, long doneTick, long now) {
        return fraction(now - startTick, doneTick - startTick);
    }

    // "Smelting 37 iron", "Cooking 5 beef". the output item's id, with the bits that are only there for the registry gone
    public static String furnaceWords(String kind, String output, int count, int unitsEach) {
        boolean food = unitsEach > 0 || "smoker".equals(kind);
        String what = output == null ? "" : output.replace("cooked_", "").replace("_ingot", "").replace("_nugget", "").replace('_', ' ');
        return (food ? "Cooking " : "Smelting ") + count + (what.isEmpty() ? "" : " " + what);
    }

    // the detour row's words. coal and diamonds say how far the ore is, gravel how much of its cap it dug (the cap is the stop
    // that matters there, the patch is right in front of us anyway). `name` is the spec's, lowercase
    public static String detourName(String name, int blocks, int dug, int digCap) {
        String label = Character.toUpperCase(name.charAt(0)) + name.substring(1) + " detour";
        if (digCap > 0) {
            return label + ", " + dug + "/" + digCap + " dug";
        }
        return blocks < 0 ? label : label + ", " + blocks + " blocks";
    }

    // seconds a mining detour has left of its budget, whole, never under 0
    public static int detourSecondsLeft(long startTick, double budgetSeconds, long now) {
        return (int) Math.max(0, Math.ceil(budgetSeconds - (now - startTick) / 20.0));
    }
}
