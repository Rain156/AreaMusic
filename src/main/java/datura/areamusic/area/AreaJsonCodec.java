package datura.areamusic.area;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class AreaJsonCodec {
    public static final int LEGACY_SCHEMA_VERSION = 1;
    public static final int CURRENT_SCHEMA_VERSION = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> V1_ROOT_FIELDS = Set.of(
            "schemaVersion", "dimension", "pos1", "pos2", "musicId",
            "priority", "volume", "loop", "fadeInMs", "fadeOutMs"
    );
    private static final Set<String> V2_ROOT_FIELDS = Set.of(
            "schemaVersion", "dimension", "pos1", "pos2", "tracks", "resumeOnReenter", "priority"
    );
    private static final Set<String> TRACK_FIELDS = Set.of(
            "musicId", "delaySeconds", "volume", "loop", "fadeInMs", "fadeOutMs"
    );
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "z");

    public AreaDefinition read(String areaId, Reader reader) throws JsonParseException {
        JsonObject root = requireObject(JsonParser.parseReader(reader), "root");
        int schemaVersion = requireInt(root, "schemaVersion");
        if (schemaVersion != LEGACY_SCHEMA_VERSION && schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new JsonParseException("Unsupported schemaVersion: " + schemaVersion);
        }
        rejectUnknownFields(
                root,
                schemaVersion == LEGACY_SCHEMA_VERSION ? V1_ROOT_FIELDS : V2_ROOT_FIELDS,
                "root"
        );

        String dimensionText = requireString(root, "dimension");
        ResourceLocation dimension = ResourceLocation.tryParse(dimensionText);
        if (dimension == null) {
            throw new JsonParseException("Invalid dimension: " + dimensionText);
        }

        BlockPos pos1 = requirePosition(root, "pos1");
        BlockPos pos2 = requirePosition(root, "pos2");
        int priority = optionalInt(root, "priority", 0);

        try {
            List<AreaTrackDefinition> tracks;
            boolean resumeOnReenter;
            if (schemaVersion == LEGACY_SCHEMA_VERSION) {
                tracks = List.of(new AreaTrackDefinition(
                        requireString(root, "musicId"),
                        0,
                        optionalVolume(root, "volume", 1.0f),
                        optionalBoolean(root, "loop", true),
                        optionalInt(root, "fadeInMs", 2000),
                        optionalInt(root, "fadeOutMs", 2000)
                ));
                resumeOnReenter = false;
            } else {
                tracks = requireTracks(root);
                resumeOnReenter = optionalBoolean(root, "resumeOnReenter", false);
            }
            return AreaDefinition.create(
                    areaId, dimension, pos1, pos2, tracks, resumeOnReenter, priority
            );
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException("Invalid area '" + areaId + "': " + exception.getMessage(), exception);
        }
    }

    public String write(AreaDefinition area) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.addProperty("dimension", area.dimension().toString());
        root.add("pos1", position(area.min()));
        root.add("pos2", position(area.max()));

        JsonArray tracks = new JsonArray();
        for (AreaTrackDefinition track : area.tracks()) {
            JsonObject trackJson = new JsonObject();
            trackJson.addProperty("musicId", track.musicId());
            trackJson.addProperty("delaySeconds", track.delaySeconds());
            trackJson.addProperty("volume", track.volume());
            trackJson.addProperty("loop", track.loop());
            trackJson.addProperty("fadeInMs", track.fadeInMs());
            trackJson.addProperty("fadeOutMs", track.fadeOutMs());
            tracks.add(trackJson);
        }
        root.add("tracks", tracks);
        root.addProperty("resumeOnReenter", area.resumeOnReenter());
        root.addProperty("priority", area.priority());
        return GSON.toJson(root) + System.lineSeparator();
    }

    private static List<AreaTrackDefinition> requireTracks(JsonObject root) {
        JsonArray array = requireArray(root, "tracks");
        if (array.isEmpty() || array.size() > AreaDefinition.MAX_TRACKS) {
            throw new JsonParseException(
                    "tracks must contain between 1 and " + AreaDefinition.MAX_TRACKS + " entries"
            );
        }

        List<AreaTrackDefinition> tracks = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            String location = "tracks[" + index + "]";
            JsonObject track = requireObject(array.get(index), location);
            rejectUnknownFields(track, TRACK_FIELDS, location);
            tracks.add(requireTrack(track, location));
        }
        return List.copyOf(tracks);
    }

    private static AreaTrackDefinition requireTrack(JsonObject track, String location) {
        try {
            return new AreaTrackDefinition(
                    requireString(track, "musicId"),
                    optionalInt(track, "delaySeconds", 0),
                    optionalVolume(track, "volume", 1.0f),
                    optionalBoolean(track, "loop", true),
                    optionalInt(track, "fadeInMs", 2000),
                    optionalInt(track, "fadeOutMs", 2000)
            );
        } catch (JsonParseException exception) {
            throw new JsonParseException(location + ": " + exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(location + ": " + exception.getMessage(), exception);
        }
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

    private static JsonArray requireArray(JsonObject object, String name) {
        JsonElement element = require(object, name);
        if (!element.isJsonArray()) {
            throw new JsonParseException(name + " must be an array");
        }
        return element.getAsJsonArray();
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

    private static float optionalVolume(JsonObject object, String name, float defaultValue) {
        if (!object.has(name)) {
            return defaultValue;
        }
        JsonPrimitive value = requirePrimitive(object, name);
        if (!value.isNumber()) {
            throw new JsonParseException(name + " must be a number");
        }
        try {
            BigDecimal decimal = new BigDecimal(value.getAsString());
            if (decimal.compareTo(BigDecimal.ZERO) < 0 || decimal.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("volume must be between 0 and 1");
            }
            return decimal.floatValue();
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
