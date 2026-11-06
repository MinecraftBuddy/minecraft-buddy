package adris.altoclef.control;

import baritone.api.utils.input.Input;
import java.util.EnumSet;
import java.util.Set;

// the keyboard without the game
public class FakeKeys implements KeyPort {
    public final Set<Input> down = EnumSet.noneOf(Input.class);
    public int clicks;
    public boolean entityUnderCrosshair;

    @Override
    public boolean isDown(Input input) {
        return down.contains(input);
    }

    @Override
    public void setDown(Input input, boolean isDown) {
        if (isDown) {
            down.add(input);
        } else {
            down.remove(input);
        }
    }

    @Override
    public void click(Input input) {
        clicks++;
    }

    @Override
    public boolean aimedAtEntity() {
        return entityUnderCrosshair;
    }
}
