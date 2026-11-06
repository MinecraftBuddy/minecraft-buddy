package adris.altoclef.util.progresscheck;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public class MovementProgressChecker {

    private final IProgressChecker<Vec3> _distanceChecker;
    private final IProgressChecker<Double> _mineChecker;

    private BlockPos _lastBreakingBlock = null;
    private final AimGate _aim = new AimGate();

    public MovementProgressChecker(double distanceTimeout, double minDistance, double mineTimeout, double minMineProgress, int attempts) {
        _distanceChecker = new ProgressCheckerRetry<>(new DistanceProgressChecker(distanceTimeout, minDistance), attempts);
        _mineChecker = new LinearProgressChecker(mineTimeout, minMineProgress);
    }

    public MovementProgressChecker(double distanceTimeout, double minDistance, double mineTimeout, double minMineProgress) {
        this(distanceTimeout, minDistance, mineTimeout, minMineProgress, 1);
    }

    public MovementProgressChecker(int attempts) {
        this(6, 0.1, 0.5, 0.001, attempts);
    }

    public MovementProgressChecker() {
        this(1);
    }

    public boolean check(AltoClef mod) {
        return check(mod, null);
    }

    // `target` is the block we are here to break: the mine clock waits until the crosshair is on it, see AimGate. (the
    // block being cracked is whatever the crosshair is on, so it can't say whether we are on the target)
    public boolean check(AltoClef mod, BlockPos target) {

        // Allow pause on eat
        if (mod.getFoodChain().needsToEat()) {
            _distanceChecker.reset();
            _mineChecker.reset();
        }

        if (mod.getControllerExtras().isBreakingBlock()) {
            BlockPos breakBlock = mod.getControllerExtras().getBreakingBlockPos();
            // If we broke a block, we made progress.
            // We must also delay reseting the distance checker UNTIL we break a block.
            // Because otherwise we risk not failing if we keep retrtying to mine and don't succeed.
            if (_lastBreakingBlock != null && WorldHelper.isAir(mod, _lastBreakingBlock)) {
                _distanceChecker.reset();
                _mineChecker.reset();
                _aim.reset();
            }
            if (target != null && !_aim.spent() && _lastBreakingBlock != null && !_lastBreakingBlock.equals(breakBlock)) {
                // a different block started cracking (the crosshair crossed the one in front on its way to the target): it starts
                // from zero, that is not a stall. not once the aim allowance is spent, or a crosshair that keeps swapping
                // between two blocks would restart the clock forever
                _mineChecker.reset();
            }
            _lastBreakingBlock = breakBlock;
            if (target != null && !_aim.judging(LookHelper.isLookingAt(mod, target))) {
                // not on it yet, so the half second hasn't started
                _mineChecker.reset();
                return true;
            }
            _mineChecker.setProgress(mod.getControllerExtras().getBreakingBlockProgress());
            return !_mineChecker.failed();
        } else {
            _mineChecker.reset();
            _distanceChecker.setProgress(mod.getPlayer().position());
            return !_distanceChecker.failed();
        }
    }

    public void reset() {
        _distanceChecker.reset();
        _mineChecker.reset();
    }

    // a different block now (or a fresh task): the aim wait starts over too. reset() alone keeps it, a path in between two
    // swings at the same block must not hand it a new three seconds every time
    public void newTarget() {
        reset();
        _aim.reset();
    }

}
