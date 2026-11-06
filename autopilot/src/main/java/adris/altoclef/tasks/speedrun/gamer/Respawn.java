package adris.altoclef.tasks.speedrun.gamer;

import java.util.List;

// where we respawn, as the game tells it. pure so the rules are testable without a game. the old way was each task writing
// down "the spawn is set" when its own timer ran out, which a bed that got broken six seconds later never took back
public final class Respawn {
    // what the server says on a bed click, and what the client says on a respawn that found nothing (block.minecraft.set_spawn,
    // block.minecraft.spawn.not_valid). prefix match on the second one, it carries on with "or charged respawn anchor..."
    private static final String SET = "Respawn point set";
    private static final String LOST = "You have no home bed";
    // a bed click is within this of us, the sleeping pos is closer still
    public static final double CLICK_REACH = 7;
    // a respawn bed this close to the stronghold start is as good as one we place there
    public static final double NEAR_STRONGHOLD = 300;
    // a respawn bed this close to the overworld nether portal is as good as one we place by it
    public static final double NEAR_NETHER_PORTAL = 100;
    // and the bed gets placed from this close, the walk to the portal brings us there anyway
    public static final double PORTAL_BED_REACH = 8;
    // a spawn bed this close to the End portal is a short walk after a death in the End
    public static final double NEAR_PORTAL = 300;

    // the gamer's run state, for the hooks that live far from the gamer (the bed task, the night bed finder). set while a run
    // is on, null otherwise
    private static volatile RunState bound;

    private Respawn() {
    }

    public enum Line {
        SET, LOST, OTHER
    }

    public static Line read(String message) {
        if (message == null) {
            return Line.OTHER;
        }
        if (message.contains(SET)) {
            return Line.SET;
        }
        return message.contains(LOST) ? Line.LOST : Line.OTHER;
    }

    // the run that's on, null otherwise
    public static RunState boundRun() {
        return bound;
    }

    public static void bind(RunState state) {
        bound = state;
    }

    public static void unbind() {
        bound = null;
    }

    // the overworld bed we respawn at while a run is on, null when we do not know of one
    public static RunState.Pos overworldBed() {
        RunState s = bound;
        return s == null ? null : bed(s);
    }

    // either half of the bed we respawn at (while a run is on)
    public static boolean isSpawnBed(int x, int y, int z) {
        RunState.Pos spawn = overworldBed();
        return spawn != null && BedRules.touches(spawn, new RunState.Pos(x, y, z));
    }

    public static RunState.Pos bed(RunState state) {
        return "OVERWORLD".equals(state.respawnDim) ? state.respawnPos : null;
    }

    public static void set(RunState state, RunState.Pos foot, String dimension) {
        state.respawnPos = foot;
        state.respawnDim = dimension;
    }

    public static void lose(RunState state) {
        state.respawnPos = null;
        state.respawnDim = "OVERWORLD";
    }

    // the bed block the click was at: the one closest to us out of the beds in reach. null = there is none, so the line was
    // about something that is not a bed (an anchor), and the old spawn is gone all the same
    public static RunState.Pos nearest(List<RunState.Pos> beds, double px, double py, double pz) {
        RunState.Pos best = null;
        double bestDist = CLICK_REACH * CLICK_REACH;
        for (RunState.Pos bed : beds) {
            double dx = bed.x + 0.5 - px;
            double dy = bed.y + 0.5 - py;
            double dz = bed.z + 0.5 - pz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestDist) {
                best = bed;
                bestDist = d;
            }
        }
        return best;
    }

    // the world says there is no bed where we thought: only believed when we can actually see the spot, an unloaded chunk reads as
    // air and would wipe a good spawn
    public static boolean stale(String respawnDim, String nowDim, boolean chunkLoaded, boolean bedStands) {
        return respawnDim.equals(nowDim) && chunkLoaded && !bedStands;
    }

    // the night bed is ours to take back unless it is the one that holds our respawn and we are on the stronghold leg, where a
    // death costs the eyes and the kit and a spawn a few hundred blocks off is worth more than the bed in the bag
    public static boolean keepNightBed(GamerPhase phase, RunState state) {
        RunState.Pos night = state.nightBed;
        RunState.Pos spawn = bed(state);
        return night != null && spawn != null && BedRules.touches(night, spawn) && onStrongholdLeg(phase);
    }

    public static boolean onStrongholdLeg(GamerPhase phase) {
        return phase == GamerPhase.LOCATE || phase == GamerPhase.ROOM || phase == GamerPhase.OPEN;
    }

    // the night bed becomes the spawn bed: remembered as ours for good, and the next night trip is not a pickup. true when it moved.
    // only when the bag already holds the beds the End needs without it: a bed left standing out here is one the dragon fight
    // never sees (the END_PREP bed errand used to walk a thousand blocks back to our own)
    public static boolean adoptNightBed(RunState state, int bagBeds, int needBeds) {
        if (bagBeds < needBeds || !keepNightBed(state.phase, state)) {
            return false;
        }
        state.spawnBed = state.nightBed;
        state.nightBed = null;
        return true;
    }

    // is a respawn bed good enough that END_PREP does not need another one by the portal. no portal known yet = any is fine
    public static boolean coversPortal(RunState state) {
        RunState.Pos bed = bed(state);
        if (bed == null) {
            return false;
        }
        RunState.Pos portal = state.endPortalCenter;
        return portal == null || Math.hypot(bed.x - portal.x, bed.z - portal.z) <= NEAR_PORTAL;
    }

    // ROOM: no respawn bed anywhere near the stronghold start, so the dig down has none to come back to
    public static boolean nothingNearStronghold(RunState state) {
        RunState.Pos bed = bed(state);
        RunState.Pos start = state.strongholdStart;
        return bed == null || start == null || Math.hypot(bed.x - start.x, bed.z - start.z) > NEAR_STRONGHOLD;
    }

    // is the standing bed worth skipping a night for. in END_PREP only one by the portal is, any other is the thing the prep is fixing.
    // ROOM and OPEN only one by the stronghold, an old bed back at the nether portal does nothing for a death down there
    public static boolean worthKeeping(GamerPhase phase, RunState state) {
        return switch (phase) {
            case END_PREP -> coversPortal(state);
            case ROOM, OPEN -> !nothingNearStronghold(state);
            default -> true;
        };
    }

    // the fallback bed by the stronghold: we stand at its start, nothing holds our respawn near it, and a bed is to spare (the End's
    // beds are not, EndGear.surplusBeds). the caller latches it, once per room search and not again mid dig
    public static boolean wantRoomBed(RunState state, boolean atStart, int spareBeds) {
        return atStart && spareBeds > 0 && state.strongholdStart != null && nothingNearStronghold(state);
    }

    // the bed by the overworld nether portal: a bed we carry to spare (the End's beds are not, none gets made for this), and nothing
    // holding our respawn near that portal already. night is fine, the click sets the spawn before the sleep check and we just
    // sleep through it. the caller latches it, once per run
    public static boolean wantPortalBed(RunState state, int spareBeds, double portalX, double portalZ) {
        if (spareBeds <= 0) {
            return false;
        }
        RunState.Pos bed = bed(state);
        return bed == null || Math.hypot(bed.x - portalX, bed.z - portalZ) > NEAR_NETHER_PORTAL;
    }
}
