package adris.altoclef.util.serialization;

import adris.altoclef.Debug;
import adris.altoclef.util.helpers.ItemHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

// reads an item from a registry id ("minecraft:iron_pickaxe", "iron_pickaxe", old translation keys) or an ancient raw int id.
// unknown items come back null and get warned about, same as the old list reader that just skipped them
public class ItemDeserializer implements JsonDeserializer<Item> {

    @Override
    public Item deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (json.isJsonNull()) {
            return null;
        }
        if (!json.isJsonPrimitive()) {
            throw new JsonParseException("Item should be a string, got: " + json);
        }
        if (json.getAsJsonPrimitive().isNumber()) {
            // Old raw id (ew stinky)
            return Item.byId(json.getAsInt());
        }
        // Translation key (the proper way)
        String itemKey = ItemHelper.trimItemName(json.getAsString());
        Identifier identifier = Identifier.tryParse(itemKey);
        // ITEM is a defaulted registry, getValue would hand us air for a typo instead of failing
        if (identifier != null && BuiltInRegistries.ITEM.containsKey(identifier)) {
            return BuiltInRegistries.ITEM.getValue(identifier);
        }
        Debug.logWarning("Invalid item name:" + itemKey);
        return null;
    }

    // for List<Item> fields: invalid entries are dropped instead of leaving nulls in the list
    public static class ListOf implements JsonDeserializer<List<Item>> {
        private final ItemDeserializer _item = new ItemDeserializer();

        @Override
        public List<Item> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (!json.isJsonArray()) {
                throw new JsonParseException("Start array expected");
            }
            JsonArray array = json.getAsJsonArray();
            List<Item> result = new ArrayList<>();
            for (JsonElement element : array) {
                Item item = _item.deserialize(element, Item.class, context);
                if (item != null) {
                    result.add(item);
                }
            }
            return result;
        }
    }
}
