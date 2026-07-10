package datura.areamusic.area;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.io.Reader;
import java.math.BigDecimal;
import java.util.Set;

public final class AreaJsonCodec {
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "dimension", "pos1", "pos2", "musicId",
            "priority", "volume", "loop", "fadeInMs", "fadeOutMs"
    );
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "z");

    public AreaDefinition read(String areaId, Reader reader) throws JsonParseException {
        JsonObject root = requireObject(JsonParser.parseReader(reader), "root");
        rejectUnknownFields(root, ROOT_FIELDS, "root");

        int schemaVersion = requireInt(root, "schemaVersion");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new JsonParseException("Unsupported schemaVersion: " + schemaVersion);
        }

        String dimensionText = requireString(root, "dimension");
        ResourceLocation dimension = ResourceLocation.tryParse(dimensionText);
        if (dimension == null) {
            throw new JsonParseException("Invalid dimension: " + dimensionText);
        }

        BlockPos pos1 = requirePosition(root, "pos1");
        BlockPos pos2 = requirePosition(root, "pos2");
        String musicId = requireString(root, "musicId");
        int priority = optionalInt(root, "priority", 0);
        float volume = optionalFloat(root, "volume", 1.0f);
        boolean loop = optionalBoolean(root, "loop", true);
        int fadeInMs = optionalInt(root, "fadeInMs", 2000);
        int fadeOutMs = optionalInt(root, "fadeOutMs", 2000);

        try {
            return AreaDefinition.create(
                    areaId, dimension, pos1, pos2, musicId, priority, volume, loop, fadeInMs, fadeOutMs
            );
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException("Invalid area '" + areaId + "': " + exception.getMessage(), exception);
        }
    }

    public String write(AreaDefinition area) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("dimension", area.dimension().toString());
        root.add("pos1", position(area.min()));
        root.add("pos2", position(area.max()));
        root.addProperty("musicId", area.musicId());
        root.addProperty("priority", area.priority());
        root.addProperty("volume", area.volume());
        root.addProperty("loop", area.loop());
        root.addProperty("fadeInMs", area.fadeInMs());
        root.addProperty("fadeOutMs", area.fadeOutMs());
        return GSON.toJson(root) + System.lineSeparator();
    }

    private static JsonObject position(BlockPos position) {
        JsonObject json = new JsonObject();
        json.addProperty("x", position.getX());
        json.addProperty("y", position.getY());
        json.addProperty("z", position.getZ());
        return json;
    }

    private static BlockPos requirePosition(JsonObject parent, String name) {
        JsonObject position = requireObject(require(parent, name), name);
        rejectUnknownFields(position, POSITION_FIELDS, name);
        return new BlockPos(
                requireInt(position, "x"),
                requireInt(position, "y"),
                requireInt(position, "z")
        );
    }

    private static JsonElement require(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            throw new JsonParseException("Missing required field: " + name);
        }
        return value;
    }

    private static JsonObject requireObject(JsonElement element, String name) {
        if (!element.isJsonObject()) {
            throw new JsonParseException(name + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static String requireString(JsonObject object, String name) {
        JsonPrimitive value = requirePrimitive(object, name);
        if (!value.isString()) {
            throw new JsonParseException(name + " must be a string");
        }
        return value.getAsString();
    }

    private static int requireInt(JsonObject object, String name) {
        JsonPrimitive value = requirePrimitive(object, name);
        if (!value.isNumber()) {
            throw new JsonParseException(name + " must be an integer");
        }
        try {
            return new BigDecimal(value.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new JsonParseException(name + " must be a 32-bit integer", exception);
        }
    }

    private static int optionalInt(JsonObject object, String name, int defaultValue) {
        return object.has(name) ? requireInt(object, name) : defaultValue;
    }

    private static float optionalFloat(JsonObject object, String name, float defaultValue) {
        if (!object.has(name)) {
            return defaultValue;
        }
        JsonPrimitive value = requirePrimitive(object, name);
        if (!value.isNumber()) {
            throw new JsonParseException(name + " must be a number");
        }
        try {
            return new BigDecimal(value.getAsString()).floatValue();
        } catch (NumberFormatException exception) {
            throw new JsonParseException(name + " must be a number", exception);
        }
    }

    private static boolean optionalBoolean(JsonObject object, String name, boolean defaultValue) {
        if (!object.has(name)) {
            return defaultValue;
        }
        JsonPrimitive value = requirePrimitive(object, name);
        if (!value.isBoolean()) {
            throw new JsonParseException(name + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static JsonPrimitive requirePrimitive(JsonObject object, String name) {
        JsonElement element = require(object, name);
        if (!element.isJsonPrimitive()) {
            throw new JsonParseException(name + " must be a scalar value");
        }
        return element.getAsJsonPrimitive();
    }

    private static void rejectUnknownFields(JsonObject object, Set<String> allowed, String location) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw new JsonParseException("Unknown field in " + location + ": " + field);
            }
        }
    }
}
