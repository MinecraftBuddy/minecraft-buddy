package adris.altoclef.control;

import adris.altoclef.tasksystem.Task;
import baritone.api.utils.input.Input;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Sometimes we want to trigger a "press" for one frame, or do other input forcing.
 * <p>
 * Dealing with keeping track of a press and timing each time you do this is annoying.
 * <p>
 * For some reason using baritone's "Forcestate" doesn't always work, perhaps that's my bad.
 * <p>
 * But this will alleviate all confusion.
 */
@SuppressWarnings("UnnecessaryDefault")
public class InputControls {

    private final Queue<Input> _toUnpress = new ArrayDeque<>();
    private final Set<Input> _waitForRelease = new HashSet<>(); // a click requires a release.
    private final KeyPort _keys;
    // who has a hand on each key. a key can have several owners, and holds with no task behind them (chains) are
    // tracked as "unowned": those are never let go of by anybody's stop, only by a plain release
    private final Map<Input, Set<Object>> _owners = new EnumMap<>(Input.class);
    private final Set<Input> _unowned = EnumSet.noneOf(Input.class);
    private final Map<Input, Set<Object>> _pins = new EnumMap<>(Input.class);

    public InputControls() {
        this(new GameKeys());
    }

    public InputControls(KeyPort keys) {
        _keys = keys;
    }

    // the real keys. the only place that knows about Minecraft.getInstance()
    private static final class GameKeys implements KeyPort {
        private static KeyMapping inputToKeyBinding(Input input) {
            Options o = Minecraft.getInstance().options;
            return switch (input) {
                case MOVE_FORWARD -> o.keyUp;
                case MOVE_BACK -> o.keyDown;
                case MOVE_LEFT -> o.keyLeft;
                case MOVE_RIGHT -> o.keyRight;
                case CLICK_LEFT -> o.keyAttack;
                case CLICK_RIGHT -> o.keyUse;
                case JUMP -> o.keyJump;
                case SNEAK -> o.keyShift;
                case SPRINT -> o.keySprint;
                default -> throw new IllegalArgumentException("Invalid key input/not accounted for: " + input);
            };
        }

        @Override
        public boolean isDown(Input input) {
            return inputToKeyBinding(input).isDown();
        }

        @Override
        public void setDown(Input input, boolean down) {
            inputToKeyBinding(input).setDown(down);
        }

        @Override
        public void click(Input input) {
            KeyMapping.click(inputToKeyBinding(input).getDefaultKey());
        }

        @Override
        public boolean aimedAtEntity() {
            return Minecraft.getInstance().hitResult instanceof EntityHitResult;
        }
    }

    public void tryPress(Input input) {
        // We just pressed, so let us release.
        if (_waitForRelease.contains(input)) {
            return;
        }
        _keys.setDown(input, true);
        // Also necessary to ensure the game registers the input as "pressed"
        _keys.click(input);
        _toUnpress.add(input);
        _waitForRelease.add(input);
    }

    // whoever is ticking right now (a task in onStart/onTick) is the owner, anything else (a chain) is nobody
    public void hold(Input input) {
        hold(input, Task.currentOwner());
    }

    public void hold(Input input, Object owner) {
        // the only way a block click turns into a swing at a mob. tryPress is left alone, the dragon task wants its hits
        if (input == Input.CLICK_LEFT && !ClickGuard.allowLeftHold(_keys.aimedAtEntity())) {
            forget(input);
            _keys.setDown(input, false);
            return;
        }
        // a key that is up has no real holders, whatever the books say (the game lets every key go when a screen opens,
        // and onTickPre drops tryPress keys). a stale claim would keep the next owner's stop from lifting it
        boolean wasDown = _keys.isDown(input);
        if (!wasDown) {
            forget(input);
        }
        claim(input, owner);
        if (!wasDown) {
            _keys.click(input);
        }
        _keys.setDown(input, true);
    }

    // a key a survival rule needs held no matter who lets go (a dozen tasks release sneak by hand every tick they run). the
    // pin outlives plain releases, only unpin lifts it
    public void pin(Input input, Object owner) {
        _pins.computeIfAbsent(input, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(owner);
        hold(input, owner);
    }

    // only this owner's pin goes, another owner's pin on the same key keeps it down (magma sneak vs the edge brake)
    public void unpin(Input input, Object owner) {
        Set<Object> pinners = _pins.get(input);
        if (pinners != null) {
            pinners.remove(owner);
            if (pinners.isEmpty()) {
                _pins.remove(input);
            } else {
                // a hold on a key the game let up wipes every claim (see hold), the pins that are left put theirs back
                // so this owner going doesn't lift the key under them
                for (Object pinner : pinners) {
                    claim(input, pinner);
                }
            }
        }
        releaseOwnedBy(owner);
    }

    public boolean isPinnedBy(Input input, Object owner) {
        Set<Object> pinners = _pins.get(input);
        return pinners != null && pinners.contains(owner);
    }

    // unconditional: the key goes up and every claim on it is gone, owned or not (a pinned key stays down, see pin)
    public void release(Input input) {
        forget(input);
        Set<Object> pinned = _pins.get(input);
        if (pinned != null && !pinned.isEmpty()) {
            for (Object pinner : pinned) {
                claim(input, pinner);
            }
            return;
        }
        _keys.setDown(input, false);
    }

    // the owner is done (stopped, interrupted, finished). its claims go, and a key only comes up when nobody else is
    // still holding it, owned or not
    public void releaseOwnedBy(Object owner) {
        if (owner == null) {
            return;
        }
        Iterator<Map.Entry<Input, Set<Object>>> it = _owners.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Input, Set<Object>> e = it.next();
            Input input = e.getKey();
            if (e.getValue().remove(owner) && e.getValue().isEmpty()) {
                it.remove();
                if (!_unowned.contains(input)) {
                    _keys.setDown(input, false);
                }
            }
        }
    }

    private void claim(Input input, Object owner) {
        if (owner == null) {
            _unowned.add(input);
            return;
        }
        _owners.computeIfAbsent(input, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(owner);
    }

    private void forget(Input input) {
        _unowned.remove(input);
        _owners.remove(input);
    }

    // true when this owner has a claim on the key (the key itself may be held by others too)
    public boolean isHeldBy(Input input, Object owner) {
        Set<Object> holders = _owners.get(input);
        return holders != null && holders.contains(owner);
    }

    public boolean isHeldDown(Input input) {
        return _keys.isDown(input);
    }

    public void forceLook(float yaw, float pitch) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.setYRot(yaw);
            Minecraft.getInstance().player.setXRot(pitch);
        }
    }

    // Before the user calls input commands for the frame
    public void onTickPre() {
        while (!_toUnpress.isEmpty()) {
            _keys.setDown(_toUnpress.remove(), false);
        }
    }

    // After the user calls input commands for the frame
    public void onTickPost() {
        _waitForRelease.clear();
    }
}
