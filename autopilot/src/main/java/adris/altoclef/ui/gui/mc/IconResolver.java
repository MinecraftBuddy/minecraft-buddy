package adris.altoclef.ui.gui.mc;

import adris.altoclef.TaskCatalogue;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// every "what picture goes with this" question in the gui lands here, so nobody else has to know that planks are a
// group, that water has no item or that the giant has no egg. answers are registry ids ("minecraft:oak_log"), null
// means the canvas draws its own thing (a player face, the entity glyph)
public final class IconResolver {

    private static final Map<String, String> BLOCK_STAND_INS = Map.of(
            "water", "minecraft:water_bucket",
            "lava", "minecraft:lava_bucket",
            "fire", "minecraft:flint_and_steel",
            "soul_fire", "minecraft:flint_and_steel"
    );

    // the only two mobs the game never gave an egg
    private static final Map<String, String> ENTITY_STAND_INS = Map.of(
            "giant", "minecraft:zombie_head",
            "illusioner", "minecraft:totem_of_undying"
    );

    // types get asked about every refresh for every mob in range, the answer never changes. "" is the cached null
    private static final Map<EntityType<?>, String> TYPE_CACHE = new ConcurrentHashMap<>();

    private IconResolver() {
    }

    // a catalogue name: "planks" is a group, the first thing in it speaks for the lot
    public static String forResource(String catalogueName) {
        if (catalogueName == null) {
            return null;
        }
        Item[] matches = TaskCatalogue.getItemMatches(catalogueName);
        for (Item item : matches) {
            String id = idOf(item);
            if (id != null) {
                return id;
            }
        }
        return forItemId(catalogueName);
    }

    public static String forBlock(Block block) {
        if (block == null) {
            return null;
        }
        String id = idOf(block.asItem());
        if (id != null) {
            return id;
        }
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        String standIn = BLOCK_STAND_INS.get(path);
        return standIn != null ? standIn : forItemId(path);
    }

    public static String forEntityType(EntityType<?> type) {
        if (type == null || type == EntityTypes.PLAYER) {
            return null;
        }
        String cached = TYPE_CACHE.computeIfAbsent(type, t -> {
            String id = resolveType(t);
            return id == null ? "" : id;
        });
        return cached.isEmpty() ? null : cached;
    }

    private static String resolveType(EntityType<?> type) {
        Optional<Holder<Item>> egg = SpawnEggItem.byId(type);
        if (egg.isPresent()) {
            return idOf(egg.get().value());
        }
        String path = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        String standIn = ENTITY_STAND_INS.get(path);
        if (standIn != null) {
            return standIn;
        }
        // boats, minecarts and friends are items with their own name
        return forItemId(path);
    }

    // "oak_log" or "minecraft:oak_log", null when there is no such item (air does not count)
    public static String forItemId(String id) {
        if (id == null) {
            return null;
        }
        Identifier rl = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getOptional(rl).map(IconResolver::idOf).orElse(null);
    }

    private static String idOf(Item item) {
        if (item == null || item == Items.AIR) {
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
