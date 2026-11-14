package datura.areamusic.server;

import datura.areamusic.area.AreaBox;
import datura.areamusic.area.AreaCondition;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.edit.AreaEdits;
import datura.areamusic.edit.TrackTime;
import datura.areamusic.music.MusicLibrary;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the {@code /areamusic} commands say. Kept in one place so that replies share one
 * look: labels in grey, values in white, anything clickable in aqua, and an area always shown as
 * its color swatch and name, which opens its details when clicked.
 */
final class CommandReplies {
    private static final ChatFormatting LABEL = ChatFormatting.GRAY;
    /** Only for the dots between values: darker text is hard to read on the chat background. */
    private static final ChatFormatting DOT = ChatFormatting.DARK_GRAY;
    private static final ChatFormatting LINK = ChatFormatting.AQUA;
    private static final ChatFormatting WARNING = ChatFormatting.YELLOW;
    private static final String ROOT = "/areamusic ";
    /** What {@code set} can change, in the order the help lists them. */
    private static final List<String> PROPERTIES = List.of(
            "name", "volume", "mode", "start", "fadein", "fadeout", "shuffle", "gap",
            "time", "weather", "priority", "resume", "enabled", "announce", "color",
            "wholedimension", "pos1", "pos2");

    private CommandReplies() {
    }

    // ---- areas ----

    /** Swatch and name; clicking it shows the area's details. */
    static MutableComponent name(AreaDefinition area) {
        MutableComponent text = Component.empty()
                .append(swatch(area))
                .append(Component.literal(area.label()).withStyle(ChatFormatting.WHITE));
        return clickable(
                text, ROOT + "info " + area.id(), true,
                Component.translatable("commands.areamusic.hover.info", area.label()));
    }

    static Component created(AreaDefinition area) {
        return Component.translatable("commands.areamusic.create.success", name(area));
    }

    static Component deleted(String label) {
        return Component.translatable(
                "commands.areamusic.delete.success",
                Component.literal(label).withStyle(ChatFormatting.WHITE));
    }

    static Component teleported(AreaDefinition area) {
        return Component.translatable("commands.areamusic.tp.success", name(area));
    }

    /** One line saying what an edit changed, e.g. "Updated Plaza: volume → 60%". */
    static Component updated(AreaDefinition before, AreaDefinition after) {
        List<Component> changes = changes(before, after);
        if (changes.isEmpty()) {
            return Component.translatable("commands.areamusic.edit.unchanged", name(after));
        }
        MutableComponent all = Component.empty();
        for (int index = 0; index < changes.size(); index++) {
            if (index > 0) {
                all.append(Component.translatable("commands.areamusic.edit.separator").withStyle(LABEL));
            }
            all.append(changes.get(index));
        }
        return Component.translatable("commands.areamusic.edit.success", name(after), all);
    }

    private static List<Component> changes(AreaDefinition before, AreaDefinition after) {
        List<Component> changes = new ArrayList<>();
        if (!before.displayName().equals(after.displayName())) {
            changes.add(change("name", after.displayName().isEmpty()
                    ? Component.translatable("commands.areamusic.value.none")
                    : Component.literal(after.displayName())));
        }
        if (AreaEdits.modeOf(before) != AreaEdits.modeOf(after)) {
            changes.add(change("mode", mode(after)));
        }
        if (!before.musicIds().equals(after.musicIds())) {
            changes.add(change("music", Component.literal(String.join(", ", after.musicIds()))));
        }
        if (AreaEdits.volumeOf(before) != AreaEdits.volumeOf(after)) {
            changes.add(change("volume", Component.literal(percent(AreaEdits.volumeOf(after)))));
        }
        if (before.startOffsetMs() != after.startOffsetMs()) {
            changes.add(change("start", Component.literal(TrackTime.format(after.startOffsetMs()))));
        }
        if (AreaEdits.fadeInOf(before) != AreaEdits.fadeInOf(after)) {
            changes.add(change("fadein", seconds(AreaEdits.fadeInOf(after))));
        }
        if (AreaEdits.fadeOutOf(before) != AreaEdits.fadeOutOf(after)) {
            changes.add(change("fadeout", seconds(AreaEdits.fadeOutOf(after))));
        }
        if (AreaEdits.shuffleOf(before) != AreaEdits.shuffleOf(after)) {
            changes.add(change("shuffle", onOff(AreaEdits.shuffleOf(after))));
        }
        if (AreaEdits.gapOf(before) != AreaEdits.gapOf(after)) {
            changes.add(change("gap", seconds(AreaEdits.gapOf(after))));
        }
        if (before.condition().dayPart() != after.condition().dayPart()) {
            changes.add(change("time", dayPart(after)));
        }
        if (before.condition().weather() != after.condition().weather()) {
            changes.add(change("weather", weather(after)));
        }
        if (before.announce() != after.announce()) {
            changes.add(change("announce", onOff(after.announce())));
        }
        if (before.wholeDimension() != after.wholeDimension()) {
            changes.add(change("wholedimension", onOff(after.wholeDimension())));
        }
        if (before.priority() != after.priority()) {
            changes.add(change("priority", Component.literal(Integer.toString(after.priority()))));
        }
        if (before.resumeOnReenter() != after.resumeOnReenter()) {
            changes.add(change("resume", onOff(after.resumeOnReenter())));
        }
        if (before.enabled() != after.enabled()) {
            changes.add(change("enabled", onOff(after.enabled())));
        }
        if (before.color() != after.color()) {
            changes.add(change("color", after.color() == AreaDefinition.AUTO_COLOR
                    ? Component.translatable("commands.areamusic.value.auto")
                    : Component.empty().append(swatch(after)).append(
                            String.format(Locale.ROOT, "#%06X", after.color()))));
        }
        if (!before.boxes().equals(after.boxes()) && !after.wholeDimension()) {
            changes.add(change("bounds", extent(after)));
        }
        return changes;
    }

    private static Component change(String property, Component value) {
        return Component.translatable(
                "commands.areamusic.edit.change",
                Component.translatable("commands.areamusic.property." + property).withStyle(LABEL),
                value.copy().withStyle(ChatFormatting.WHITE));
    }

    // ---- list ----

    static List<Component> list(List<AreaDefinition> areas, MusicLibrary library) {
        List<Component> lines = new ArrayList<>(areas.size() + 2);
        lines.add(Component.translatable("commands.areamusic.list.header", areas.size())
                .withStyle(ChatFormatting.GOLD));
        for (AreaDefinition area : areas) {
            MutableComponent line = Component.literal(" ").append(name(area));
            if (!area.displayName().isEmpty()) {
                line.append(Component.literal("  " + area.id()).withStyle(LABEL));
            }
            line.append(separator()).append(extent(area).copy().withStyle(LABEL));
            line.append(separator()).append(music(area).withStyle(LABEL));
            if (!area.dimension().equals("minecraft:overworld") && !area.wholeDimension()) {
                line.append(separator()).append(Component.literal(shortId(area.dimension())).withStyle(LABEL));
            }
            if (!area.condition().equals(AreaCondition.ALWAYS)) {
                line.append(separator()).append(when(area).withStyle(LINK));
            }
            if (!area.enabled()) {
                line.append(separator()).append(
                        Component.translatable("commands.areamusic.list.disabled").withStyle(WARNING));
            }
            if (!area.musicIds().stream().allMatch(library::contains)) {
                line.append(separator()).append(
                        Component.translatable("commands.areamusic.list.missing_music").withStyle(ChatFormatting.RED));
            }
            lines.add(line);
        }
        lines.add(Component.translatable("commands.areamusic.list.hint").withStyle(LABEL));
        return lines;
    }

    /** "Loop · boss" for one track, "Playlist · 3 tracks" for several. */
    private static MutableComponent music(AreaDefinition area) {
        Component what = area.musicIds().size() == 1
                ? Component.literal(shortName(area.musicIds().get(0)))
                : Component.translatable("commands.areamusic.list.tracks", area.musicIds().size());
        return Component.empty().append(mode(area)).append(" · ").append(what);
    }

    // ---- info ----

    static List<Component> info(AreaDefinition area, MusicLibrary library) {
        List<Component> lines = new ArrayList<>();
        MutableComponent header = Component.empty()
                .append(swatch(area))
                .append(Component.literal(area.label()).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
        if (!area.displayName().isEmpty()) {
            header.append(Component.literal("  " + area.id()).withStyle(LABEL));
        }
        if (!area.enabled()) {
            header.append("  ").append(
                    Component.translatable("commands.areamusic.list.disabled").withStyle(WARNING));
        }
        if (!area.wholeDimension()) {
            // Music for a whole dimension has no place to go to.
            header.append("  ").append(clickable(
                    Component.translatable("commands.areamusic.info.teleport").withStyle(LINK),
                    ROOT + "tp " + area.id(), true,
                    Component.translatable("commands.areamusic.hover.teleport")));
        }
        lines.add(header);

        if (area.wholeDimension()) {
            lines.add(row("bounds", Component.translatable(
                    "commands.areamusic.info.whole_dimension", area.dimension())));
        } else {
            List<AreaBox> boxes = area.boxes();
            for (int index = 0; index < boxes.size(); index++) {
                AreaBox box = boxes.get(index);
                MutableComponent line = Component.empty();
                if (boxes.size() > 1) {
                    line.append(Component.literal((index + 1) + "  ").withStyle(LABEL));
                }
                line.append(corners(box.pos1(), box.pos2()))
                        .append(separator()).append(size(box.pos1(), box.pos2()));
                if (index == 0) {
                    line.append(separator()).append(area.dimension());
                }
                lines.add(index == 0 ? row("bounds", line) : continued("bounds", line));
            }
        }

        MutableComponent playback = Component.empty()
                .append(mode(area))
                .append(separator())
                .append(Component.translatable("commands.areamusic.info.volume", percent(AreaEdits.volumeOf(area))));
        if (area.startOffsetMs() > 0) {
            playback.append(separator()).append(Component.translatable(
                    "commands.areamusic.info.start", TrackTime.format(area.startOffsetMs())));
        }
        if (AreaEdits.shuffleOf(area)) {
            playback.append(separator()).append(Component.translatable("commands.areamusic.info.shuffle"));
        }
        if (AreaEdits.gapOf(area) > 0) {
            playback.append(separator()).append(Component.translatable(
                    "commands.areamusic.info.gap", secondsText(AreaEdits.gapOf(area))));
        }
        lines.add(row("playback", playback));
        if (!area.condition().equals(AreaCondition.ALWAYS)) {
            lines.add(row("when", when(area)));
        }

        List<String> musicIds = area.musicIds();
        for (int index = 0; index < musicIds.size(); index++) {
            String musicId = musicIds.get(index);
            MutableComponent track = Component.empty();
            if (musicIds.size() > 1) {
                track.append(Component.literal((index + 1) + "  ").withStyle(LABEL));
            }
            track.append(musicId);
            if (!library.contains(musicId)) {
                track.append("  ").append(
                        Component.translatable("commands.areamusic.info.music_missing").withStyle(ChatFormatting.RED));
            }
            lines.add(index == 0 ? row("music", track) : continued("music", track));
        }

        lines.add(row("fades", Component.translatable(
                "commands.areamusic.info.fades.value",
                secondsText(AreaEdits.fadeInOf(area)), secondsText(AreaEdits.fadeOutOf(area)))));
        lines.add(row("other", Component.empty()
                .append(Component.translatable("commands.areamusic.info.priority", area.priority()))
                .append(separator())
                .append(Component.translatable("commands.areamusic.info.resume", onOff(area.resumeOnReenter())))
                .append(separator())
                .append(Component.translatable("commands.areamusic.info.announce", onOff(area.announce())))));
        return lines;
    }

    private static Component row(String label, Component value) {
        return Component.literal("  ")
                .append(Component.translatable("commands.areamusic.info." + label).withStyle(LABEL))
                .append("  ")
                .append(value.copy().withStyle(ChatFormatting.WHITE));
    }

    /** A further line under the same label, indented to where the first value began. */
    private static Component continued(String label, Component value) {
        return Component.literal("  ")
                .append(Component.translatable("commands.areamusic.info." + label + ".indent"))
                .append("  ")
                .append(value.copy().withStyle(ChatFormatting.WHITE));
    }

    // ---- help ----

    static List<Component> help() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("commands.areamusic.help.header").withStyle(ChatFormatting.GOLD));
        lines.add(usage("list", "list"));
        lines.add(usage("info", "info <area>"));
        lines.add(usage("create", "create <area> <pos1> <pos2> <music>"));
        lines.add(usage("set", "set <area> <setting> <value>"));
        lines.add(Component.literal("   ").append(Component.translatable(
                "commands.areamusic.help.settings", String.join("  ", PROPERTIES)).withStyle(LABEL)));
        lines.add(usage("music", "music add/remove <area> <music>"));
        lines.add(usage("box", "box add <area> <pos1> <pos2>"));
        lines.add(usage("box_remove", "box remove <area> <number>"));
        lines.add(usage("tp", "tp <area>"));
        lines.add(usage("delete", "delete <area>"));
        lines.add(usage("reload", "reload"));
        lines.add(Component.translatable("commands.areamusic.help.footer").withStyle(LABEL));
        return lines;
    }

    /** A command that fills the chat box when clicked, followed by what it does. */
    private static Component usage(String key, String arguments) {
        String command = ROOT + arguments;
        String typed = ROOT + arguments.split(" ", 2)[0] + (arguments.contains(" ") ? " " : "");
        return Component.literal(" ")
                .append(clickable(
                        Component.literal(command).withStyle(LINK), typed, false,
                        Component.translatable("commands.areamusic.hover.suggest")))
                .append(Component.literal("  "))
                .append(Component.translatable("commands.areamusic.help." + key).withStyle(LABEL));
    }

    // ---- pieces ----

    private static MutableComponent swatch(AreaDefinition area) {
        int rgb = area.effectiveColor();
        return Component.literal("■ ").withStyle(style -> style.withColor(TextColor.fromRgb(rgb)));
    }

    /** "Night · Rain": the part of the day and the weather the area keeps to. */
    private static MutableComponent when(AreaDefinition area) {
        MutableComponent text = Component.empty();
        boolean timed = area.condition().dayPart() != AreaCondition.DayPart.ANY;
        if (timed) {
            text.append(dayPart(area));
        }
        if (area.condition().weather() != AreaCondition.Weather.ANY) {
            if (timed) {
                text.append(" · ");
            }
            text.append(weather(area));
        }
        return text;
    }

    private static Component dayPart(AreaDefinition area) {
        return Component.translatable("commands.areamusic.time." + area.condition().dayPart().id());
    }

    private static Component weather(AreaDefinition area) {
        return Component.translatable("commands.areamusic.weather." + area.condition().weather().id());
    }

    /** How big the area is: its size, the number of its boxes, or "whole dimension". */
    private static Component extent(AreaDefinition area) {
        if (area.wholeDimension()) {
            return Component.translatable("commands.areamusic.list.whole_dimension", shortId(area.dimension()));
        }
        if (area.extraBoxes().isEmpty()) {
            return Component.literal(size(area));
        }
        return Component.translatable("commands.areamusic.list.boxes", area.boxes().size());
    }

    private static MutableComponent mode(AreaDefinition area) {
        return Component.translatable("areamusic.mode." + AreaEdits.modeOf(area).id());
    }

    private static Component separator() {
        return Component.literal(" · ").withStyle(DOT);
    }

    private static Component onOff(boolean value) {
        return Component.translatable(value ? "commands.areamusic.value.on" : "commands.areamusic.value.off");
    }

    private static Component seconds(int millis) {
        return Component.translatable("commands.areamusic.value.seconds", secondsText(millis));
    }

    /** "2" for two seconds, "0.5" for half a second. */
    private static String secondsText(int millis) {
        return millis % 1000 == 0
                ? Integer.toString(millis / 1000)
                : String.format(Locale.ROOT, "%.3f", millis / 1000.0).replaceAll("0+$", "");
    }

    private static String percent(float volume) {
        return Math.round(volume * 100.0f) + "%";
    }

    private static String corners(AreaPosition pos1, AreaPosition pos2) {
        return pos1.x() + " " + pos1.y() + " " + pos1.z()
                + " → " + pos2.x() + " " + pos2.y() + " " + pos2.z();
    }

    static String size(AreaDefinition area) {
        return size(area.pos1(), area.pos2());
    }

    private static String size(AreaPosition pos1, AreaPosition pos2) {
        AreaBox box = new AreaBox(pos1, pos2);
        AreaPosition min = box.min();
        AreaPosition max = box.max();
        return (max.x() - min.x() + 1L) + "×" + (max.y() - min.y() + 1L)
                + "×" + (max.z() - min.z() + 1L);
    }

    /** "night theme" from "village/night theme.mp3". */
    private static String shortName(String musicId) {
        String name = musicId.substring(musicId.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** "the_nether" from "minecraft:the_nether". */
    private static String shortId(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    private static MutableComponent clickable(
            MutableComponent text, String command, boolean run, Component hover
    ) {
        return text.withStyle(style -> style
                .withClickEvent(run
                        ? new ClickEvent.RunCommand(command)
                        : new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hover)));
    }
}
