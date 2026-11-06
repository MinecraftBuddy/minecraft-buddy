package adris.altoclef.tasks.speedrun.gamer;

// what a handler wants after its budget or stall timer ran out
public enum Timeout {
    // reset the phase's sub state and go again
    RETRY,
    // give up on this phase and move on (only sane for optional phases)
    SKIP,
    // stop the whole run, state stays saved. if the clock is what got us here the engine starts the phase over once first (PhaseMachine.restart)
    STUCK,
    // the same stop with no restart first: a fresh phase entry doesn't bring the dead back (the dragon's death cap)
    STUCK_NO_RESTART
}
