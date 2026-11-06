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


package baritone.utils.update;

// what the title screen card shows. AVAILABLE with canInstall false means "open the download page" only
public enum UpdateState {
    DISABLED,
    CHECKING,
    UP_TO_DATE,
    // quiet on purpose: offline, rate limited, github having a day. never a red error box for that
    CHECK_FAILED,
    AVAILABLE,
    DOWNLOADING,
    // staged and the helper is waiting for the game to close
    READY,
    // two launches in a row and the swap still didn't happen, time for the manual steps
    STUCK,
    // the download or its checks failed, download() again is fine
    FAILED;

    // a reopened title screen gets one free recheck after a failed check, except for the rate limit: asking github
    // again right away just earns the same no
    static boolean shouldAutoRetry(UpdateState state, boolean rateLimited, boolean alreadyTried) {
        return state == CHECK_FAILED && !rateLimited && !alreadyTried;
    }
}
