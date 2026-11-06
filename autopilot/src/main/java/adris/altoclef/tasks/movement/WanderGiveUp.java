package adris.altoclef.tasks.movement;

// when is a wander itself going nowhere. pure numbers in, a stage out, so the line can be tested without a world
public final class WanderGiveUp {
    public enum Stage {
        OK,
        FAILING
    }

    // a wander that has been exploring this long and still hasn't left its spot is not about to. normal wanders
    // clear three blocks in a few seconds, so this is mostly slack for lag, doors and a bit of shimmying
    static final double FAILING_AFTER_SECONDS = 45;

    // the wander counts as getting somewhere once it has been this far from where it started. under that it could
    // just be pacing around the same hole
    static final double GETTING_SOMEWHERE_BLOCKS = 3;

    private WanderGiveUp() {
    }

    // strict on the time: exactly 45 s is still ok. the excursion is a running max, so one trip out is ok for good
    public static Stage decide(double secondsOnTask, double maxExcursionBlocks) {
        if (maxExcursionBlocks >= GETTING_SOMEWHERE_BLOCKS) {
            return Stage.OK;
        }
        return secondsOnTask > FAILING_AFTER_SECONDS ? Stage.FAILING : Stage.OK;
    }

    // how far from its first position a wander has ever been. the first note is the anchor and nothing moves it after,
    // so an onStart that runs again after an interrupt can't hand out a fresh start
    public static final class Excursion {
        private boolean anchored;
        private double ax;
        private double ay;
        private double az;
        private double max;

        public void note(double x, double y, double z) {
            if (!anchored) {
                anchored = true;
                ax = x;
                ay = y;
                az = z;
            }
            double dx = x - ax;
            double dy = y - ay;
            double dz = z - az;
            max = Math.max(max, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }

        public double max() {
            return max;
        }
    }
}
