package adris.altoclef.util.serialization;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Type;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

// items are written as their registry id, "minecraft:iron_pickaxe"
public class ItemSerializer implements JsonSerializer<Item> {
    @Override
    public JsonElement serialize(Item item, Type typeOfSrc, JsonSerializationContext context) {
        return new JsonPrimitive(BuiltInRegistries.ITEM.getKey(item).toString());
    }
}
