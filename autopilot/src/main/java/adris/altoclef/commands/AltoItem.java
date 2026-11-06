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

import adris.altoclef.TaskCatalogue;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import baritone.api.command.datatypes.IDatatypeContext;
import baritone.api.command.datatypes.IDatatypeFor;
import baritone.api.command.exception.CommandException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

// an item slot in an altoclef command. altoclef's own names ("log", "planks", "food" and friends, see TaskCatalogue)
// are mostly item ids but some are whole groups, so ItemById can't do the job alone. same shape as ItemById otherwise
public enum AltoItem implements IDatatypeFor<String> {
    // only what the catalogue can get. what `get` takes
    CATALOGUE,
    // the catalogue plus any real item id, for the commands that just move stuff around (deposit, stash, give)
    ANY_ITEM,
    // the armor in the catalogue plus the whole-set shortcuts, for equip
    ARMOR;

    // equip knows these as a one word shortcut for all four pieces
    public static final List<String> ARMOR_SETS = List.of("leather", "iron", "gold", "diamond", "netherite");

    private volatile List<String> cache;

    @Override
    public String get(IDatatypeContext ctx) throws CommandException {
        String name = ItemArgs.normalize(ctx.getConsumer().getString());
        if (!accepts(name)) {
            throw new IllegalArgumentException("not something altoclef can work with: " + name);
        }
        return name;
    }

    @Override
    public Stream<String> tabComplete(IDatatypeContext ctx) throws CommandException {
        // rawRest instead of getString so a whole "iron_ingot 3 dia" list completes its last word
        return ItemArgs.complete(ctx.getConsumer().rawRest(), names(), this == ARMOR ? ARMOR_SETS : List.of());
    }

    public boolean accepts(String name) {
        return switch (this) {
            case CATALOGUE -> TaskCatalogue.taskExists(name);
            case ANY_ITEM -> TaskCatalogue.taskExists(name) || registryItem(name) != null;
            case ARMOR -> isArmor(name);
        };
    }

    // for the commands that do not want the datatype machinery, a name that already passed accepts()
    public ItemTarget target(String name, int count) {
        if (TaskCatalogue.taskExists(name)) {
            return TaskCatalogue.getItemTarget(name, count);
        }
        return new ItemTarget(registryItem(name), count);
    }

    public Collection<String> names() {
        List<String> c = cache;
        if (c == null) {
            c = build();
            cache = c;
        }
        return c;
    }

    private List<String> build() {
        List<String> out = new ArrayList<>();
        for (String name : TaskCatalogue.resourceNames()) {
            if (this != ARMOR || isArmor(name)) {
                out.add(name);
            }
        }
        if (this == ANY_ITEM) {
            for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
                String n = id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
                if (registryItem(n) != null && !TaskCatalogue.taskExists(n)) {
                    out.add(n);
                }
            }
        }
        out.sort(null);
        return out;
    }

    private static boolean isArmor(String name) {
        if (!TaskCatalogue.taskExists(name)) {
            return false;
        }
        Item[] matches = TaskCatalogue.getItemMatches(name);
        if (matches.length == 0) {
            return false;
        }
        for (Item i : matches) {
            if (!ItemHelper.isArmor(i)) {
                return false;
            }
        }
        return true;
    }

    private static Item registryItem(String name) {
        Identifier id = Identifier.tryParse(name);
        // a bare name is a minecraft one, tryParse already does that
        Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        // "air" is in the registry as an item. it is not one you can put in a chest
        return item == Items.AIR ? null : item;
    }
}
