/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.altoclef;

import baritone.api.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

// every temporary value altoclef puts into baritone's settings (or into vanilla's options) goes through here, and
// nowhere else. the first time a setting is touched we write down what the user had, and that is the only thing that
// may ever reach settings.txt / options.txt or come back when the task ends. one registry for the scope and for
// BotBehaviour used to be two half ones, and the half that BotBehaviour wrote (placement penalty infinity, anyone) got saved
//
// static on purpose: when altoclef gives up for the session the instance is gone but the holds must still be undoable,
// and the saves still have to know about them
public final class SettingsOverrides {

    private static final class Hold<T> {
        final Supplier<T> get;
        final Consumer<T> set;
        // vanilla options live in options.txt, not settings.txt, so they swap on a different save
        final boolean vanilla;
        // what the user had. set once, never touched again until the hold is gone
        final T user;
        T ours;

        Hold(Supplier<T> get, Consumer<T> set, boolean vanilla, T user, T ours) {
            this.get = get;
            this.set = set;
            this.vanilla = vanilla;
            this.user = user;
            this.ours = ours;
        }

        // equals, not ==: boxed booleans and small longs are cached, so an identity compare thinks the user's
        // `#set allowParkour false` is still ours. (explicit sets also go through userChanged, this is the fallback
        // for everybody else who pokes a setting)
        boolean stillOurs() {
            return Objects.equals(get.get(), ours);
        }

        void putUser() {
            set.accept(user);
        }

        void putOurs() {
            set.accept(ours);
        }
    }

    // keyed by the Setting object (identity, Setting has no equals) or by a string for the vanilla ones
    private static final Map<Object, Hold<?>> HOLDS = new LinkedHashMap<>();
    // the vanilla holds that are swapped out while options.txt is being written
    private static List<Hold<?>> swappedForOptionsSave;

    private SettingsOverrides() {
    }

    // make a baritone setting `value` until restoreAll. the user's own value is remembered the first time only
    public static synchronized <T> void put(Settings.Setting<T> setting, T value) {
        put(setting, false, () -> setting.value, v -> setting.value = v, value);
    }

    // same for something that is not a baritone setting (vanilla options). key has to be stable, a plain string will do
    public static synchronized <T> void put(Object key, Supplier<T> get, Consumer<T> set, T value) {
        put(key, true, get, set, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> void put(Object key, boolean vanilla, Supplier<T> get, Consumer<T> set, T value) {
        Hold<T> hold = (Hold<T>) HOLDS.get(key);
        if (hold != null && !hold.stillOurs()) {
            // somebody changed it behind our back, what is in there now is theirs and it is the new baseline
            HOLDS.remove(key);
            hold = null;
        }
        if (hold == null) {
            T current = get.get();
            if (Objects.equals(current, value)) {
                // already what we want, nothing to take back later
                return;
            }
            HOLDS.put(key, new Hold<>(get, set, vanilla, current, value));
        } else if (Objects.equals(hold.user, value)) {
            // we were asked for exactly what the user had, which is the same as letting go
            HOLDS.remove(key);
        } else {
            hold.ours = value;
        }
        set.accept(value);
    }

    // the user set this themselves (#set, #set reset): their value wins and sticks, we have nothing to give back
    public static synchronized void userChanged(Object key) {
        HOLDS.remove(key);
    }

    public static synchronized void userChangedAll() {
        HOLDS.clear();
    }

    // task over (or altoclef gave up): everything we still hold goes back to what the user had. a value somebody else
    // changed in the meantime is left alone
    public static synchronized void restoreAll() {
        List<Hold<?>> all = new ArrayList<>(HOLDS.values());
        HOLDS.clear();
        // newest first, same as an undo stack
        for (int i = all.size() - 1; i >= 0; i--) {
            Hold<?> hold = all.get(i);
            try {
                if (hold.stillOurs()) {
                    hold.putUser();
                }
            } catch (Throwable t) {
                // one setting refusing must not leave the rest of them overridden
                t.printStackTrace();
            }
        }
        swappedForOptionsSave = null;
    }

    // runs a settings.txt save as if none of this was applied. the pathing thread can read the odd old value in there
    // for the duration, which is fine, it is the user's own value
    public static synchronized void saveWithoutOverrides(Runnable save) {
        List<Hold<?>> swapped = swapOut(false);
        try {
            save.run();
        } finally {
            swapBack(swapped);
        }
    }

    // vanilla saves options.txt from all over the place (the options screens, F3+P, quitting), so Options#save is
    // mixed into and calls these. begin hands the user's values back, end puts ours in again
    public static synchronized void beginOptionsSave() {
        if (swappedForOptionsSave == null) {
            swappedForOptionsSave = swapOut(true);
        }
    }

    public static synchronized void endOptionsSave() {
        List<Hold<?>> swapped = swappedForOptionsSave;
        swappedForOptionsSave = null;
        if (swapped != null) {
            swapBack(swapped);
        }
    }

    private static List<Hold<?>> swapOut(boolean vanilla) {
        List<Hold<?>> swapped = new ArrayList<>();
        for (Hold<?> hold : HOLDS.values()) {
            if (hold.vanilla == vanilla && hold.stillOurs()) {
                swapped.add(hold);
                hold.putUser();
            }
        }
        return swapped;
    }

    private static void swapBack(List<Hold<?>> swapped) {
        for (Hold<?> hold : swapped) {
            // a hold that was dropped while we were swapped (restoreAll in the middle of a save) stays dropped
            if (HOLDS.containsValue(hold)) {
                hold.putOurs();
            }
        }
    }

    // for tests and for the failure paths that want to know
    public static synchronized int heldCount() {
        return HOLDS.size();
    }

    public static synchronized boolean isHeld(Object key) {
        return HOLDS.containsKey(key);
    }
}
