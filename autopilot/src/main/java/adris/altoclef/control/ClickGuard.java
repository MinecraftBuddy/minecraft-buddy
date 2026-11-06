package adris.altoclef.control;

// the pure half of "is it ok to hold the real attack key right now". vanilla swings at whatever entity is under the
// crosshair, so a held click meant for a fire block or a wall hits the zombified piglin standing in front of it, and
// every zombified piglin within 32 blocks hears about it. baritone's own forced CLICK_LEFT never does this (BlockBreakHelper
// only acts on a block trace), it's the real key from InputControls.hold that can
public final class ClickGuard {
    private ClickGuard() {
    }

    // a held left click is for blocks: with an entity under the crosshair it waits a tick, the mob moves or the aim does
    public static boolean allowLeftHold(boolean entityUnderCrosshair) {
        return !entityUnderCrosshair;
    }
}
