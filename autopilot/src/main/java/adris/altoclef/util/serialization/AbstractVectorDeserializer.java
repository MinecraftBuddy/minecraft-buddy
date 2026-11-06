package adris.altoclef.util.serialization;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

// reads "1,2,3", [1,2,3] or {"x":1,"y":2,"z":3}. only the string form is ever written
public abstract class AbstractVectorDeserializer<T, UnitType> implements JsonDeserializer<T> {

    protected abstract String getTypeName();

    protected abstract String[] getComponents();

    protected abstract UnitType parseUnit(String unit) throws Exception;

    protected abstract T deserializeFromUnits(List<UnitType> units);

    private UnitType tryParse(String whole, String part) {
        try {
            return parseUnit(part.trim());
        } catch (Exception e) {
            throw new JsonParseException("Failed to parse " + getTypeName() + " \"" + whole + "\", specifically part \"" + part + "\".");
        }
    }

    private UnitType tryParseElement(JsonElement element, String whole) {
        if (!element.isJsonPrimitive()) {
            throw new JsonParseException("Invalid token for " + getTypeName() + ". Got: " + element);
        }
        return tryParse(whole, element.getAsString());
    }

    @Override
    public T deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        String[] neededComponents = getComponents();
        if (json.isJsonPrimitive()) {
            JsonPrimitive primitive = json.getAsJsonPrimitive();
            String whole = primitive.getAsString();
            String[] parts = whole.split(",");
            if (parts.length != neededComponents.length) {
                throw new JsonParseException("Invalid " + getTypeName() + " string: \"" + whole + "\", must be in form \"" + String.join(",", neededComponents) + "\".");
            }
            List<UnitType> units = new ArrayList<>();
            for (String part : parts) {
                units.add(tryParse(whole, part));
            }
            return deserializeFromUnits(units);
        } else if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            if (array.size() != neededComponents.length) {
                throw new JsonParseException("Invalid " + getTypeName() + " array: " + array + ", must have " + neededComponents.length + " entries (" + String.join(",", neededComponents) + ").");
            }
            List<UnitType> units = new ArrayList<>();
            for (JsonElement element : array) {
                units.add(tryParseElement(element, array.toString()));
            }
            return deserializeFromUnits(units);
        } else if (json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            List<UnitType> units = new ArrayList<>();
            for (String componentName : neededComponents) {
                if (!object.has(componentName)) {
                    throw new JsonParseException(getTypeName() + " should have key for " + componentName + " key, but one was not found.");
                }
                units.add(tryParseElement(object.get(componentName), object.toString()));
            }
            return deserializeFromUnits(units);
        }
        throw new JsonParseException("Invalid token for " + getTypeName() + ": " + json);
    }
}
