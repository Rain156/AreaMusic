package datura.areamusic.area;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import datura.areamusic.playback.ParallelPlayback;
import datura.areamusic.playback.PlaybackDefinition;
import datura.areamusic.playback.PlaybackMode;
import datura.areamusic.playback.PlaylistLoopPlayback;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AreaJsonCodec {
    public static final int LEGACY_SCHEMA_VERSION = 1;
    public static final int CURRENT_SCHEMA_VERSION = 3;
    private static final int MULTI_TRACK_SCHEMA_VERSION = 2;
    // Counts simultaneously open objects and arrays; area schemas require at most three.
    private static final int MAX_JSON_NESTING_DEPTH = 64;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> V1_ROOT_FIELDS = Set.of(
            "schemaVersion", "dimension", "pos1", "pos2", "musicId",
            "priority", "volume", "loop", "fadeInMs", "fadeOutMs"
    );
    private static final Set<String> V2_ROOT_FIELDS = Set.of(
            "schemaVersion", "dimension", "pos1", "pos2", "tracks", "resumeOnReenter", "priority"
    );
    private static final Set<String> V3_PARALLEL_ROOT_FIELDS = Set.of(
            "schemaVersion", "dimension", "pos1", "pos2", "playbackMode", "tracks",
            "resumeOnReenter", "priority"
    );
    private static final Set<String> V3_PLAYLIST_LOOP_ROOT_FIELDS = Set.of(
            "schemaVersion", "dimension", "pos1", "pos2", "playbackMode", "playlist",
            "volume", "fadeInMs", "fadeOutMs", "resumeOnReenter", "priority"
    );
    private static final Set<String> TRACK_FIELDS = Set.of(
            "musicId", "delaySeconds", "volume", "loop", "fadeInMs", "fadeOutMs"
    );
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "z");

    public AreaDefinition read(String areaId, Reader reader) throws JsonParseException {
        JsonObject root = requireObject(parseStrict(reader), "root");
        int schemaVersion = requireInt(root, "schemaVersion");
        if (schemaVersion != LEGACY_SCHEMA_VERSION
                && schemaVersion != MULTI_TRACK_SCHEMA_VERSION
                && schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new JsonParseException("Unsupported schemaVersion: " + schemaVersion);
        }
        PlaybackMode playbackMode = PlaybackMode.PARALLEL;
        if (schemaVersion == CURRENT_SCHEMA_VERSION) {
            String playbackModeId = requireString(root, "playbackMode");
            try {
                playbackMode = PlaybackMode.fromJsonId(playbackModeId);
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException("Unsupported playbackMode: " + playbackModeId, exception);
            }
        }
        Set<String> rootFields = switch (schemaVersion) {
            case LEGACY_SCHEMA_VERSION -> V1_ROOT_FIELDS;
            case MULTI_TRACK_SCHEMA_VERSION -> V2_ROOT_FIELDS;
            case CURRENT_SCHEMA_VERSION -> playbackMode == PlaybackMode.PARALLEL
                    ? V3_PARALLEL_ROOT_FIELDS
                    : V3_PLAYLIST_LOOP_ROOT_FIELDS;
            default -> throw new AssertionError("Validated schemaVersion: " + schemaVersion);
        };
        rejectUnknownFields(root, rootFields, "root");

        String dimension = requireString(root, "dimension");
        AreaPosition pos1 = requirePosition(root, "pos1");
        AreaPosition pos2 = requirePosition(root, "pos2");
        int priority = optionalInt(root, "priority", 0);

        try {
            PlaybackDefinition playback;
            boolean resumeOnReenter;
            if (schemaVersion == LEGACY_SCHEMA_VERSION) {
                playback = new ParallelPlayback(List.of(new AreaTrackDefinition(
                        requireString(root, "musicId"),
                        0,
                        optionalVolume(root, "volume", 1.0f),
                        optionalBoolean(root, "loop", true),
                        optionalInt(root, "fadeInMs", 2000),
                        optionalInt(root, "fadeOutMs", 2000)
                )));
                resumeOnReenter = false;
            } else if (schemaVersion == MULTI_TRACK_SCHEMA_VERSION
                    || playbackMode == PlaybackMode.PARALLEL) {
                playback = new ParallelPlayback(requireTracks(root));
                resumeOnReenter = optionalBoolean(root, "resumeOnReenter", false);
            } else {
                playback = new PlaylistLoopPlayback(
                        requirePlaylist(root),
                        optionalVolume(root, "volume", PlaylistLoopPlayback.DEFAULT_VOLUME),
                        optionalInt(root, "fadeInMs", PlaylistLoopPlayback.DEFAULT_FADE_IN_MS),
                        optionalInt(root, "fadeOutMs", PlaylistLoopPlayback.DEFAULT_FADE_OUT_MS)
                );
                resumeOnReenter = optionalBoolean(root, "resumeOnReenter", false);
            }
            return new AreaDefinition(
                    areaId, dimension, pos1, pos2, playback, resumeOnReenter, priority
            );
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException("Invalid area '" + areaId + "': " + exception.getMessage(), exception);
        }
    }

    public String write(AreaDefinition area) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.addProperty("dimension", area.dimension());
        root.add("pos1", position(area.pos1()));
        root.add("pos2", position(area.pos2()));
        root.addProperty("playbackMode", area.playbackMode().jsonId());

        if (area.playback() instanceof ParallelPlayback parallel) {
            JsonArray tracks = new JsonArray();
            for (AreaTrackDefinition track : parallel.tracks()) {
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
        } else if (area.playback() instanceof PlaylistLoopPlayback playlistLoop) {
            JsonArray playlist = new JsonArray();
            playlistLoop.playlist().forEach(playlist::add);
            root.add("playlist", playlist);
            root.addProperty("volume", playlistLoop.volume());
            root.addProperty("fadeInMs", playlistLoop.fadeInMs());
            root.addProperty("fadeOutMs", playlistLoop.fadeOutMs());
        } else {
            throw new AssertionError("Unknown playback definition: " + area.playback().getClass());
        }
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

    private static List<String> requirePlaylist(JsonObject root) {
        JsonArray array = requireArray(root, "playlist");
        if (array.isEmpty() || array.size() > PlaylistLoopPlayback.MAX_ENTRIES) {
            throw new JsonParseException(
                    "playlist must contain between 1 and "
                            + PlaylistLoopPlayback.MAX_ENTRIES + " entries"
            );
        }

        List<String> playlist = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            String location = "playlist[" + index + "]";
            JsonElement entry = array.get(index);
            if (entry == null || entry.isJsonNull()
                    || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                throw new JsonParseException(location + " must be a string");
            }
            playlist.add(entry.getAsString());
        }
        return List.copyOf(playlist);
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

    private static JsonObject position(AreaPosition position) {
        JsonObject json = new JsonObject();
        json.addProperty("x", position.x());
        json.addProperty("y", position.y());
        json.addProperty("z", position.z());
        return json;
    }

    private static AreaPosition requirePosition(JsonObject parent, String name) {
        JsonObject position = requireObject(require(parent, name), name);
        rejectUnknownFields(position, POSITION_FIELDS, name);
        return new AreaPosition(
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

    private static JsonElement parseStrict(Reader source) {
        String json = readJson(source);
        validateStrictJsonLexemes(json);

        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(false);
        try {
            JsonElement root = readElement(reader, "root", 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new JsonParseException("Unexpected content after root JSON value");
            }
            return root;
        } catch (IOException | IllegalStateException exception) {
            throw new JsonParseException("Malformed JSON: " + exception.getMessage(), exception);
        }
    }

    private static String readJson(Reader source) {
        StringWriter destination = new StringWriter();
        try {
            source.transferTo(destination);
        } catch (IOException exception) {
            throw new JsonParseException("Malformed JSON: could not read input", exception);
        }
        return destination.toString();
    }

    private static void validateStrictJsonLexemes(String json) {
        int index = 0;
        while (index < json.length()) {
            char value = json.charAt(index);
            if (isJsonWhitespace(value) || isStructuralCharacter(value)) {
                index++;
            } else if (value == '"') {
                index = scanString(json, index);
            } else if (value == 't') {
                index = scanLiteral(json, index, "true");
            } else if (value == 'f') {
                index = scanLiteral(json, index, "false");
            } else if (value == 'n') {
                index = scanLiteral(json, index, "null");
            } else if (value == '-' || isDigit(value)) {
                index = scanNumber(json, index);
            } else {
                throw malformedJson("unexpected character", index);
            }
        }
    }

    private static int scanString(String json, int start) {
        for (int index = start + 1; index < json.length(); index++) {
            char value = json.charAt(index);
            if (value == '"') {
                return index + 1;
            }
            if (value <= 0x1F) {
                throw malformedJson("unescaped control character in string", index);
            }
            if (value != '\\') {
                continue;
            }

            index++;
            if (index >= json.length()) {
                throw malformedJson("unterminated escape sequence", index - 1);
            }
            char escaped = json.charAt(index);
            if (escaped == 'u') {
                for (int offset = 1; offset <= 4; offset++) {
                    int hexIndex = index + offset;
                    if (hexIndex >= json.length() || !isHexDigit(json.charAt(hexIndex))) {
                        throw malformedJson("invalid Unicode escape sequence", index - 1);
                    }
                }
                index += 4;
            } else if ("\"\\/bfnrt".indexOf(escaped) < 0) {
                throw malformedJson("invalid escape sequence", index - 1);
            }
        }
        throw malformedJson("unterminated string", start);
    }

    private static int scanLiteral(String json, int start, String literal) {
        int end = start + literal.length();
        if (end > json.length()
                || !json.startsWith(literal, start)
                || !isTokenBoundary(json, end)) {
            throw malformedJson("invalid literal; expected lowercase " + literal, start);
        }
        return end;
    }

    private static int scanNumber(String json, int start) {
        int index = start;
        if (json.charAt(index) == '-') {
            index++;
        }
        if (index >= json.length()) {
            throw malformedJson("incomplete number", start);
        }

        char firstDigit = json.charAt(index);
        if (firstDigit == '0') {
            index++;
            if (index < json.length() && isDigit(json.charAt(index))) {
                throw malformedJson("leading zero in number", start);
            }
        } else if (firstDigit >= '1' && firstDigit <= '9') {
            do {
                index++;
            } while (index < json.length() && isDigit(json.charAt(index)));
        } else {
            throw malformedJson("number must contain an integer part", start);
        }

        if (index < json.length() && json.charAt(index) == '.') {
            index++;
            int fractionStart = index;
            while (index < json.length() && isDigit(json.charAt(index))) {
                index++;
            }
            if (index == fractionStart) {
                throw malformedJson("fraction must contain a digit", start);
            }
        }

        if (index < json.length() && (json.charAt(index) == 'e' || json.charAt(index) == 'E')) {
            index++;
            if (index < json.length() && (json.charAt(index) == '+' || json.charAt(index) == '-')) {
                index++;
            }
            int exponentStart = index;
            while (index < json.length() && isDigit(json.charAt(index))) {
                index++;
            }
            if (index == exponentStart) {
                throw malformedJson("exponent must contain a digit", start);
            }
        }

        if (!isTokenBoundary(json, index)) {
            throw malformedJson("invalid character after number", index);
        }
        return index;
    }

    private static boolean isTokenBoundary(String json, int index) {
        if (index == json.length()) {
            return true;
        }
        char value = json.charAt(index);
        return isJsonWhitespace(value) || value == ',' || value == ']' || value == '}';
    }

    private static boolean isJsonWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r';
    }

    private static boolean isStructuralCharacter(char value) {
        return value == '{' || value == '}' || value == '[' || value == ']'
                || value == ',' || value == ':';
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static boolean isHexDigit(char value) {
        return isDigit(value)
                || value >= 'a' && value <= 'f'
                || value >= 'A' && value <= 'F';
    }

    private static JsonParseException malformedJson(String message, int index) {
        return new JsonParseException("Malformed JSON at index " + index + ": " + message);
    }

    private static JsonElement readElement(JsonReader reader, String location, int enclosingDepth)
            throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> readObject(reader, location, enterContainer(enclosingDepth, location));
            case BEGIN_ARRAY -> readArray(reader, location, enterContainer(enclosingDepth, location));
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new JsonNumber(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new JsonParseException("Expected JSON value at " + location);
        };
    }

    private static JsonObject readObject(JsonReader reader, String location, int depth) throws IOException {
        reader.beginObject();
        JsonObject object = new JsonObject();
        Set<String> fields = new HashSet<>();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!fields.add(field)) {
                throw new JsonParseException("Duplicate field in " + location + ": " + field);
            }
            object.add(field, readElement(reader, childLocation(location, field), depth));
        }
        reader.endObject();
        return object;
    }

    private static JsonArray readArray(JsonReader reader, String location, int depth) throws IOException {
        reader.beginArray();
        JsonArray array = new JsonArray();
        while (reader.hasNext()) {
            array.add(readElement(reader, location + "[" + array.size() + "]", depth));
        }
        reader.endArray();
        return array;
    }

    private static int enterContainer(int enclosingDepth, String location) {
        if (enclosingDepth >= MAX_JSON_NESTING_DEPTH) {
            throw new JsonParseException(
                    "Exceeded maximum JSON nesting depth of " + MAX_JSON_NESTING_DEPTH
                            + " at " + location
            );
        }
        return enclosingDepth + 1;
    }

    private static String childLocation(String location, String field) {
        return "root".equals(location) ? field : location + "." + field;
    }

    private static void rejectUnknownFields(JsonObject object, Set<String> allowed, String location) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw new JsonParseException("Unknown field in " + location + ": " + field);
            }
        }
    }

    private static final class JsonNumber extends Number {
        private static final long serialVersionUID = 1L;

        private final String value;

        private JsonNumber(String value) {
            this.value = value;
        }

        @Override
        public int intValue() {
            return decimalValue().intValue();
        }

        @Override
        public long longValue() {
            return decimalValue().longValue();
        }

        @Override
        public float floatValue() {
            return Float.parseFloat(value);
        }

        @Override
        public double doubleValue() {
            return Double.parseDouble(value);
        }

        @Override
        public String toString() {
            return value;
        }

        private BigDecimal decimalValue() {
            return new BigDecimal(value);
        }
    }
}
