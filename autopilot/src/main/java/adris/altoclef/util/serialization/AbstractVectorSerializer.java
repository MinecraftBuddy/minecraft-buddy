package adris.altoclef.util.serialization;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.util.Collection;

public abstract class AbstractVectorSerializer<T> implements JsonSerializer<T> {

    protected abstract Collection<String> getParts(T value);

    @Override
    public JsonElement serialize(T value, Type typeOfSrc, JsonSerializationContext context) {
        return new JsonPrimitive(String.join(",", getParts(value)));
    }
}
