package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasksystem.Task;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

// overworld -> nether, the way the run does it: walk to the portal we built first, then let the default task step in. the
// default task only knows portals the block tracker has loaded and otherwise builds a brand new one, a thousand blocks from
// here. NetherPhase and the nether death trip both walk home with this one, so the "is it gone" rules live in one place
public class HomePortalWalk {
    // close enough to the remembered overworld portal that the tracker can see it and the default task takes over
    private static final double PORTAL_NEAR_BLOCKS = 8;
    private static final int HOME_SCAN_TICKS = 100;

    // made when it is first needed: building one touches the game, and the phases are built in tests that have none
    private Task goNether;
    private GetToBlockTask walkToPortal;
    private RunState.Pos walkingTo;
    // the portal we remembered is not there any more: stop walking back to it and let the default task build
    private boolean gone;
    // game ticks (TickEvent.currentCount), so an esc pause can't run the grace out; 0 = not there yet
    private int homeArrivedTick;
    private boolean tracking;
    private String hud;

    // a fresh start (a phase entered again): the old walk and the old verdict are not ours any more
    public void reset() {
        goNether = null;
        walkToPortal = null;
        walkingTo = null;
        gone = false;
        homeArrivedTick = 0;
        hud = null;
    }

    // true once we stood where the portal should be and it was not there. RunState.overworldPortal is forgotten by then
    public boolean gone() {
        return gone;
    }

    // what the walk is doing, for the hud ("Walking back to the portal"...)
    public String hud() {
        return hud;
    }

    public Task toTheNether(AltoClef mod, RunState state, Runnable save) {
        RunState.Pos home = state.overworldPortal;
        if (home != null && !gone) {
            // nothing else tracks portals while we walk, and "no portal near" means nothing if nobody is looking
            if (!tracking) {
                mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL);
                tracking = true;
            }
            if (portalNear(mod)) {
                homeArrivedTick = 0;
            } else {
                BlockPos at = new BlockPos(home.x, home.y, home.z);
                if (!at.closerToCenterThan(mod.getPlayer().position(), PORTAL_NEAR_BLOCKS)) {
                    // left the spot: the next arrival gets its own grace
                    homeArrivedTick = 0;
                    if (!home.equals(walkingTo)) {
                        walkingTo = home;
                        walkToPortal = new GetToBlockTask(at);
                    }
                    hud = "Walking back to the portal";
                    return walkToPortal;
                }
                // standing where it should be and a scan has looked: it is gone (a ghast, a relog), so the walk back must
                // not drag us home again every time the default task wanders off to build a new one. the scan flag only
                // says some scan finished since we started looking, maybe far from here, so give the chunks around us a
                // few seconds to be scanned first
                if (homeArrivedTick == 0) {
                    homeArrivedTick = Math.max(1, TickEvent.currentCount());
                }
                // an unloaded chunk reads as "no portal" too, so it has to be loaded before the spot can be called empty
                if (mod.getChunkTracker().isChunkLoaded(at)
                        && mod.getBlockTracker().hasBeenScanned(Blocks.NETHER_PORTAL)
                        && graceOver(homeArrivedTick, TickEvent.currentCount())) {
                    gone = true;
                    // forget it too, or every later trip and relog walks to the same empty spot again
                    state.overworldPortal = null;
                    walkingTo = null;
                    save.run();
                } else {
                    hud = "Looking for the portal";
                    return walkToPortal;
                }
            }
        }
        hud = "Heading to the Nether";
        if (goNether == null) {
            goNether = new DefaultGoToDimensionTask(Dimension.NETHER);
        }
        return goNether;
    }

    // arrivedTick 0 = not standing at the spot yet
    static boolean graceOver(int arrivedTick, int nowTick) {
        return arrivedTick != 0 && nowTick - arrivedTick > HOME_SCAN_TICKS;
    }

    // a portal really within reach, not just "we used one once": the tracker remembers the last used portal for good, which
    // made the walk back skip itself on every trip after the first and the default task build a new portal on the spot
    private static boolean portalNear(AltoClef mod) {
        if (!mod.getBlockTracker().isTracking(Blocks.NETHER_PORTAL)) {
            return false;
        }
        Vec3 me = mod.getPlayer().position();
        return mod.getBlockTracker().getNearestTracking(me, Blocks.NETHER_PORTAL)
                .filter(p -> p.closerToCenterThan(me, PORTAL_NEAR_BLOCKS)).isPresent();
    }

    public void stopTracking(AltoClef mod) {
        if (tracking) {
            mod.getBlockTracker().stopTracking(Blocks.NETHER_PORTAL);
            tracking = false;
        }
    }
}
