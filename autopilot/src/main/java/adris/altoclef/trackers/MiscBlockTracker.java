package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Sometimes we want to track specific block related things, like the last nether portal we used.
 * I don't want to pollute other trackers to add these.
 */
public class MiscBlockTracker {

    private final AltoClef _mod;

    private final Map<Dimension, BlockPos> _lastNetherPortalsUsed = new HashMap<>();
    // where we last stood on something that was not water, per dimension. a bot out at sea with a station to place has nowhere
    // to put it, and "the shore within 32" is no help 150 blocks out. this is the way back
    private final Map<Dimension, BlockPos> _lastDryLand = new HashMap<>();

    // Make sure we only care about the nether portal we ENTERED through
    private Dimension _lastDimension;
    private boolean _newDimensionTriggered;

    public MiscBlockTracker(AltoClef mod) {
        _mod = mod;
    }

    public void tick() {
        if (AltoClef.inGame() && isDryLand(_mod.getPlayer().onGround(), _mod.getPlayer().isInWater(), _mod.getPlayer().isPassenger())) {
            _lastDryLand.put(WorldHelper.getCurrentDimension(), _mod.getPlayer().blockPosition());
        }
        if (WorldHelper.getCurrentDimension() != _lastDimension) {
            _lastDimension = WorldHelper.getCurrentDimension();
            _newDimensionTriggered = true;
        }

        if (AltoClef.inGame() && _newDimensionTriggered) {
            for (BlockPos check : WorldHelper.scanRegion(_mod, _mod.getPlayer().blockPosition().offset(-1, -1, -1), _mod.getPlayer().blockPosition().offset(1, 1, 1))) {
                Block currentBlock = _mod.getWorld().getBlockState(check).getBlock();
                if (currentBlock == Blocks.NETHER_PORTAL) {
                    // Make sure we get the lowest nether portal, as we can only really enter from the bottom.
                    while (check.getY() > 0) {
                        if (_mod.getWorld().getBlockState(check.below()).getBlock() == Blocks.NETHER_PORTAL) {
                            check = check.below();
                        } else {
                            break;
                        }
                    }
                    BlockPos below = check.below();
                    if (WorldHelper.isSolid(_mod, below)) {
                        _lastNetherPortalsUsed.put(WorldHelper.getCurrentDimension(), check);
                        _newDimensionTriggered = false;
                    }
                    break;
                }
            }
        }
    }

    public void reset() {
        _lastNetherPortalsUsed.clear();
        _lastDryLand.clear();
    }

    // feet on the ground and not wet. a boat is not land (its deck reads as ground on open sea), and a bot sunk to the sea floor
    // reads onGround too, the water check is what keeps that one out
    public static boolean isDryLand(boolean onGround, boolean inWater, boolean passenger) {
        return onGround && !inWater && !passenger;
    }

    public Optional<BlockPos> getLastDryLand(Dimension dimension) {
        return Optional.ofNullable(_lastDryLand.get(dimension));
    }

    public Optional<BlockPos> getLastUsedNetherPortal(Dimension dimension) {
        if (_lastNetherPortalsUsed.containsKey(dimension)) {
            BlockPos portalPos = _lastNetherPortalsUsed.get(dimension);
            // Check whether our nether portal pos is invalid.
            if (_mod.getChunkTracker().isChunkLoaded(portalPos)) {
                if (!_mod.getBlockTracker().blockIsValid(portalPos, Blocks.NETHER_PORTAL)) {
                    _lastNetherPortalsUsed.remove(dimension);
                    return Optional.empty();
                }
            }
            return Optional.ofNullable(portalPos);
        }
        return Optional.empty();
    }
}
