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

import java.nio.file.Path;

// one immutable picture of the updater, swapped in whole through a volatile so the render thread can read it every
// frame without a lock and without ever seeing half a state
public record Snapshot(
        UpdateState state,
        String currentVersion,
        boolean canInstall,
        String cannotInstallReason,
        Release release,
        long downloadedBytes,
        long totalBytes,
        String error,
        boolean unfinishedLastTime,
        Path modsFolder,
        String oldJarName,
        String stagedName,
        String finalName,
        String releasesPageUrl,
        // the other track's newest build (stable <-> nightly), only offered from UP_TO_DATE. null when there isn't one
        Release switchTarget,
        // release is the switch target and not an update, the card words it as a switch
        boolean switching
) {

    public static final String RELEASES_PAGE = "https://github.com/AverWasTaken/soprano/releases";

    // notesPlain has the markdown taken out already, see Markdown
    public record Release(String version, String title, String notesPlain, String htmlUrl, long sizeBytes) {
    }
}
