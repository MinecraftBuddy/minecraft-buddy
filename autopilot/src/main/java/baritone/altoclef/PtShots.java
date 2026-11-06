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

import adris.altoclef.Debug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// the play-test harness drops a name in a file, we take an F2 shot under that name. only alive when the harness
// launches the client with -Dsoprano.pt.shotRequest=<file>, so for players this is one null check a tick.
// the harness keeps the window minimized, and grabbing a minimized window from outside gets you a black rectangle.
// the game still renders into the main target while minimized though, and that's what F2 reads
public final class PtShots {

    private static final String REQUEST = System.getProperty("soprano.pt.shotRequest");
    private static int countdown;

    private PtShots() {}

    public static void tick() {
        if (REQUEST == null || --countdown > 0) {
            return;
        }
        // once a second is plenty, the harness is waiting seconds on purpose anyway
        countdown = 20;
        try {
            Path req = Paths.get(REQUEST);
            if (!Files.exists(req)) {
                return;
            }
            // the harness writes it with a rename, so there's no half written name to read here
            String name = Files.readString(req, StandardCharsets.UTF_8).trim().replaceAll("[^A-Za-z0-9_.-]", "_");
            Files.delete(req);
            if (name.isEmpty()) {
                return;
            }
            String file = name.endsWith(".png") ? name : name + ".png";
            Minecraft mc = Minecraft.getInstance();
            // lands in <game dir>/screenshots/<file>, same spot as a real F2. the saved/failed message shows up on the io
            // thread, and the trace ring isn't something to poke from there
            Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1,
                    msg -> mc.execute(() -> Debug.logInternal("pt shot " + file + ": " + msg.getString())));
        } catch (Throwable t) {
            Debug.logInternal("pt shot failed: " + t);
        }
    }
}
