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

package adris.altoclef.commands;

import baritone.api.command.datatypes.IDatatypeContext;
import baritone.api.command.datatypes.IDatatypeFor;
import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

// a player on the server, from the tab list. NearbyPlayer only knows who is loaded around you, but give/punk are for
// people who are across the map just as often
public enum TabListPlayer implements IDatatypeFor<String> {
    INSTANCE;

    @Override
    public String get(IDatatypeContext ctx) throws CommandException {
        String typed = ctx.getConsumer().getString();
        // fix the capitalization if we can see them, the tasks look players up by exact name
        for (String name : online()) {
            if (name.equalsIgnoreCase(typed)) {
                return name;
            }
        }
        // not in the tab list is fine, some servers hide it and the task just waits for them to show up
        return typed;
    }

    @Override
    public Stream<String> tabComplete(IDatatypeContext ctx) throws CommandException {
        return new TabCompleteHelper()
                .append(online().stream())
                .filterPrefix(ctx.getConsumer().getString())
                .sortAlphabetically()
                .stream();
    }

    public static List<String> online() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return List.of();
        }
        return connection.getOnlinePlayers().stream().map(PlayerInfo::getProfile).map(p -> p.name()).toList();
    }
}
