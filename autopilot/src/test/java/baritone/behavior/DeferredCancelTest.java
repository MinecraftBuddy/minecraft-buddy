/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeferredCancelTest {
    @Test
    public void nothingRequestedNeverFires() {
        DeferredCancel cancel = new DeferredCancel();
        assertFalse(cancel.pending());
        assertFalse(cancel.fire(true));
    }

    @Test
    public void staysPendingThroughUnsafeTicksAndFiresOnTheFirstSafeOne() {
        DeferredCancel cancel = new DeferredCancel();
        cancel.request();
        assertTrue(cancel.pending());
        assertFalse(cancel.fire(false));
        assertFalse(cancel.fire(false));
        assertTrue(cancel.pending());
        assertTrue(cancel.fire(true));
    }

    @Test
    public void firesOnlyOnce() {
        DeferredCancel cancel = new DeferredCancel();
        cancel.request();
        assertTrue(cancel.fire(true));
        assertFalse(cancel.pending());
        assertFalse(cancel.fire(true));
    }

    @Test
    public void aHardCancelThrowsItAway() {
        DeferredCancel cancel = new DeferredCancel();
        cancel.request();
        cancel.clear();
        assertFalse(cancel.pending());
        assertFalse(cancel.fire(true));
    }

    @Test
    public void askingAgainWhilePendingIsStillOneCancel() {
        DeferredCancel cancel = new DeferredCancel();
        cancel.request();
        cancel.request();
        assertTrue(cancel.fire(true));
        assertFalse(cancel.fire(true));
    }
}
