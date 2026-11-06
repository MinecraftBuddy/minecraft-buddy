package adris.altoclef.control;

import baritone.api.utils.input.Input;

// the four things InputControls needs from the game's keys, so it can be tested without a Minecraft instance
public interface KeyPort {
    boolean isDown(Input input);

    void setDown(Input input, boolean down);

    // the extra "click happened" nudge the game wants next to setDown for a fresh press
    void click(Input input);

    // vanilla swings at whatever entity is under the crosshair, see ClickGuard
    boolean aimedAtEntity();
}
