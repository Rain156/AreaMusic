package datura.areamusic.ui;

import datura.areamusic.area.AreaBox;
import datura.areamusic.area.AreaCondition;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPalette;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.edit.AreaEdits;
import datura.areamusic.edit.AreaEdits.SimpleMode;
import datura.areamusic.edit.TrackTime;
import datura.areamusic.editor.EditorModel;
import datura.areamusic.net.ClientSync;
import datura.areamusic.playback.PlaylistLoopPlayback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The whole editor panel, built from {@link PanelUi} widgets every frame. Everyday controls come
 * first; everything else sits in one collapsed "advanced" section.
 */
public final class EditorPanel {
    private static final int FILTER_THRESHOLD = 8;
    private static final long NOTICE_NANOS = 8_000_000_000L;
    private static final String CLOSE = "\u00d7";
    private static final String REMOVE = "\u00d7";

    private final ClientSync sync;
    private final EditorModel model;
    private final PanelHost host;
    private String filter = "";
    private String notice;
    private boolean noticeIsError;
    private long noticeSince;
    /** Area for which "layers" was chosen while it still has a single track. */
    private String layersIntent;

    /**
     * Clicks that change what the panel is about: another area, a new one, closing. They take
     * effect once the whole panel has been built, because the same click is also what commits a
     * name still being typed, and that name belongs to the area it was typed for.
     */
    /** A jingle shorter than this has nowhere worth starting from but its beginning. */
    private static final int SHORTEST_TRACK_WITH_BAR_MS = 2_000;

    private final List<Runnable> afterBuild = new ArrayList<>();
    /** The area being auditioned and the version of it that is playing, or null for none. */
    private String previewedId;
    private AreaDefinition previewed;

    public EditorPanel(ClientSync sync, PanelHost host) {
        this.sync = Objects.requireNonNull(sync, "sync");
        this.host = Objects.requireNonNull(host, "host");
        model = sync.editor();
    }

    /** Uploads dropped audio files and puts each one to use where the user is working. */
    public void filesDropped(List<Path> files) {
        for (Path file : files) {
            String name = file.getFileName().toString();
            sync.upload(file, (musicId, rejection) -> {
                if (musicId == null) {
                    notice = t("areamusic.upload.failed", name, t("areamusic.upload.rejected." + rejection));
                    noticeIsError = true;
                } else {
                    notice = t(useMusic(musicId) ? "areamusic.upload.bound" : "areamusic.upload.done", musicId);
                    noticeIsError = false;
                }
                noticeSince = System.nanoTime();
            });
        }
    }

    public void build(PanelUi ui) {
        boolean usable = sync.serverPresent() && sync.canEdit() && model.ready();
        List<String> actions = new ArrayList<>();
        if (usable) {
            actions.add(t("areamusic.editor.undo"));
            actions.add(t("areamusic.editor.redo"));
        }
        if (host.closable()) {
            actions.add(CLOSE);
        }
        int action = ui.header(t("areamusic.editor.title"), actions);
        boolean close = host.closable() && action == actions.size() - 1;
        if (close && !usable) {
            host.finishEditing();
            return;
        }
        if (!sync.serverPresent()) {
            ui.notice(t("areamusic.editor.no_server"), true);
            return;
        }
        if (!sync.canEdit()) {
            ui.notice(t("areamusic.editor.no_permission"), true);
            return;
        }
        if (!model.ready()) {
            ui.hint(t("areamusic.editor.loading"));
            return;
        }
        if (action == 0 && model.canUndo()) {
            model.undo();
        } else if (action == 1 && model.canRedo()) {
            model.redo();
        }

        model.lastError().ifPresent(error -> {
            ui.notice(describe(error), true);
            if (ui.quietButton("dismiss_error", t("areamusic.editor.dismiss"))) {
                model.clearError();
            }
        });
        if (notice != null && !noticeIsError && System.nanoTime() - noticeSince > NOTICE_NANOS) {
            notice = null;
        }
        if (notice != null) {
            ui.notice(notice, noticeIsError);
            if (noticeIsError && ui.quietButton("dismiss_notice", t("areamusic.editor.dismiss"))) {
                notice = null;
            }
        }
        for (ClientSync.UploadView upload : sync.uploads()) {
            ui.progress(t("areamusic.upload.progress", upload.fileName()), upload.progress());
        }

        maintainPreview();
        buildCreate(ui);
        buildAreaList(ui);
        model.selected().ifPresent(area -> buildSelected(ui, area));

        String footer = host.footerHint();
        if (!footer.isEmpty()) {
            ui.space();
            ui.hint(footer);
        }

        if (close) {
            afterBuild.add(host::finishEditing);
        }
        List<Runnable> actionsNow = List.copyOf(afterBuild);
        afterBuild.clear();
        actionsNow.forEach(Runnable::run);
    }

    /** The one way to make an area: a button, then three guided steps. */
    private void buildCreate(PanelUi ui) {
        EditorModel.Draft draft = model.draft();
        if (draft == null) {
            if (ui.primaryButton("new_area", t("areamusic.editor.new_area"))) {
                afterBuild.add(() -> {
                    model.startDraft(host.playerDimension());
                    host.pickCornersInWorld();
                });
            }
            if (ui.quietButton("around_me", t("areamusic.editor.around_me"))) {
                afterBuild.add(() -> model.draftAround(
                        host.playerDimension(), host.playerPosition(), EditorModel.DEFAULT_AROUND_RADIUS,
                        host.minBuildY(), host.maxBuildY()));
            }
            ui.sameRow();
            if (ui.quietButton("whole_dimension", t("areamusic.editor.whole_dimension"))) {
                afterBuild.add(() -> model.draftWholeDimension(host.playerDimension()));
            }
            if (model.areas().isEmpty()) {
                ui.hint(t("areamusic.editor.first_hint"));
            }
            return;
        }

        int corners = (draft.pos1() == null ? 0 : 1) + (draft.pos2() == null ? 0 : 1);
        if (draft.wholeDimension()) {
            // Nothing to draw: it plays everywhere in this dimension and only needs music.
            ui.label(t("areamusic.editor.draft.whole_dimension", shortId(draft.dimension())));
            ui.hint(t("areamusic.editor.draft.whole_dimension_hint"));
        } else if (draft.extraFor() != null) {
            ui.steps(List.of(
                    t("areamusic.editor.step.corner1"),
                    t("areamusic.editor.step.corner2")
            ), corners);
            ui.hint(t("areamusic.editor.draft.extra_box",
                    model.find(draft.extraFor()).map(AreaDefinition::label).orElse(draft.extraFor())));
        } else {
            ui.steps(List.of(
                    t("areamusic.editor.step.corner1"),
                    t("areamusic.editor.step.corner2"),
                    t("areamusic.editor.step.music")
            ), draft.complete() ? 2 : corners);
        }
        if (!draft.complete()) {
            ui.label(t(host.crosshairPicking()
                    ? "areamusic.editor.draft.pick_crosshair"
                    : "areamusic.editor.draft.pick_cursor"));
            if (host.crosshairPicking()) {
                if (ui.button("pick", t("areamusic.editor.draft.pick"))) {
                    host.pickCornersInWorld();
                }
                ui.sameRow();
            }
        } else {
            if (!draft.wholeDimension()) {
                ui.label(t("areamusic.editor.draft.size", size(draft.pos1(), draft.pos2())));
            }
            if (model.musicIds().isEmpty()) {
                ui.hint(t("areamusic.editor.draft.no_music"));
            } else {
                String chosen = ui.combo(
                        "draft_music", t("areamusic.editor.music"), model.musicIds(), null);
                if (chosen != null) {
                    model.completeDraft(chosen);
                }
                ui.hint(t("areamusic.editor.drop_hint"));
            }
        }
        if (ui.quietButton("cancel_draft", t("areamusic.editor.cancel"))) {
            model.cancelDraft();
        }
    }

    private void buildAreaList(PanelUi ui) {
        List<AreaDefinition> areas = model.areas();
        if (areas.isEmpty()) {
            return;
        }
        ui.section(t("areamusic.editor.areas", areas.size()));
        if (areas.size() > FILTER_THRESHOLD) {
            String typed = ui.textInput("filter", t("areamusic.editor.search"), filter, 64);
            if (typed != null) {
                filter = typed;
            }
        } else {
            filter = "";
        }
        String needle = filter.strip().toLowerCase(Locale.ROOT);
        String here = host.playerDimension();
        for (AreaDefinition area : areas) {
            if (!needle.isEmpty()
                    && !area.label().toLowerCase(Locale.ROOT).contains(needle)
                    && !area.id().contains(needle)) {
                continue;
            }
            String label = area.enabled()
                    ? area.label()
                    : area.label() + "  \u00b7  " + t("areamusic.editor.disabled");
            String meta = area.wholeDimension()
                    ? t("areamusic.editor.whole_dimension_of", shortId(area.dimension()))
                    : !area.dimension().equals(here)
                    ? shortId(area.dimension())
                    : area.musicIds().size() == 1
                    ? shortName(area.musicIds().get(0))
                    : t("areamusic.editor.track_count", area.musicIds().size());
            boolean selected = area.id().equals(model.selectedId());
            if (ui.listRow("area:" + area.id(), label, meta, selected, area.effectiveColor())) {
                afterBuild.add(() -> model.select(selected ? null : area.id()));
            }
        }
    }

    private void buildSelected(PanelUi ui, AreaDefinition area) {
        ui.section(t("areamusic.editor.selected"));

        String name = ui.textInput("name", t("areamusic.editor.name"), area.displayName(), AreaDefinition.MAX_DISPLAY_NAME_LENGTH);
        if (name != null) {
            model.apply(current -> current.withDisplayName(name));
        }

        SimpleMode actual = AreaEdits.modeOf(area);
        SimpleMode shown = actual == SimpleMode.LOOP && area.id().equals(layersIntent)
                ? SimpleMode.LAYERS
                : actual;
        Integer picked = ui.choice("mode", List.of(
                t("areamusic.mode.loop"), t("areamusic.mode.playlist"), t("areamusic.mode.layers")
        ), shown.ordinal());
        if (picked != null) {
            SimpleMode mode = SimpleMode.values()[picked];
            layersIntent = mode == SimpleMode.LAYERS ? area.id() : null;
            model.apply(current -> AreaEdits.withMode(current, mode));
            shown = mode;
        }

        buildMusic(ui, area, shown);

        Float volume = ui.slider(
                "volume", t("areamusic.editor.volume"), AreaEdits.volumeOf(area) * 100.0f, 0.0f, 100.0f, "%.0f%%");
        if (volume != null) {
            model.apply(current -> AreaEdits.withVolume(current, Math.round(volume) / 100.0f));
        }
        buildStartAndPreview(ui, area);

        ui.space();
        if (!area.wholeDimension()) {
            if (ui.button("teleport", t("areamusic.editor.teleport"))) {
                host.teleportTo(area.id());
            }
            ui.sameRow();
            if (ui.button("repick", t("areamusic.editor.repick"))) {
                host.pickCornersInWorld();
            }
            ui.sameRow();
        }
        boolean hidden = model.hidden(area.id());
        if (ui.quietButton("toggle_hidden", t(hidden ? "areamusic.editor.show" : "areamusic.editor.hide"))) {
            model.setHidden(area.id(), !hidden);
        }
        ui.sameRow();
        if (ui.quietButton("duplicate", t("areamusic.editor.duplicate"))) {
            model.duplicateSelected();
        }
        ui.sameRow();
        if (ui.dangerButton("delete", t("areamusic.editor.delete"))) {
            model.deleteSelected();
            return;
        }

        if (ui.collapsing("advanced", t("areamusic.editor.advanced"), false)) {
            buildAdvanced(ui, area, shown);
        }
    }

    /**
     * Where the music begins, as a bar that is dragged like in a music player, and a button to
     * hear the area without walking into it. The bar needs the length of the first track, which
     * takes a moment to work out the first time; until then only the button shows.
     */
    private void buildStartAndPreview(PanelUi ui, AreaDefinition area) {
        OptionalInt duration = host.trackDurationMs(area.musicIds().get(0));
        if (duration.isPresent() && duration.getAsInt() >= SHORTEST_TRACK_WITH_BAR_MS) {
            int length = duration.getAsInt();
            // A start past the end plays from where that leads once the music has gone round.
            Integer picked = ui.timeline(
                    "start_bar", t("areamusic.editor.start"), area.startOffsetMs() % length, length);
            if (picked != null) {
                model.apply(current -> current.withStartOffsetMs(picked));
            }
        }
        if (!host.canPreview(area)) {
            return;
        }
        boolean playing = host.previewing() && area.id().equals(previewedId);
        if (ui.button("preview", t(playing ? "areamusic.editor.preview_stop" : "areamusic.editor.preview"))) {
            if (playing) {
                stopPreview();
            } else {
                previewedId = area.id();
                previewed = area;
                host.startPreview(area);
            }
        }
        if (duration.isPresent()) {
            ui.sameRow();
            ui.label(t("areamusic.editor.track_length", TrackTime.format(duration.getAsInt() / 1000 * 1000)));
        }
    }

    /** Keeps an audition in step with the panel: it follows edits and ends with its area. */
    private void maintainPreview() {
        if (previewedId == null) {
            return;
        }
        AreaDefinition selected = model.selected().orElse(null);
        if (!host.previewing() || selected == null || !selected.id().equals(previewedId)
                || !host.canPreview(selected)) {
            stopPreview();
        } else if (!selected.equals(previewed)) {
            // Something about the area changed: hear the new version from its start.
            previewed = selected;
            host.startPreview(selected);
        }
    }

    private void stopPreview() {
        previewedId = null;
        previewed = null;
        if (host.previewing()) {
            host.stopPreview();
        }
    }

    private void buildMusic(PanelUi ui, AreaDefinition area, SimpleMode shown) {
        List<String> library = model.musicIds();
        List<String> playing = area.musicIds();
        if (shown == SimpleMode.LOOP) {
            String chosen = ui.combo("music", t("areamusic.editor.music"), library, playing.get(0));
            if (chosen != null) {
                model.apply(current -> AreaEdits.withOnlyMusic(current, chosen));
            }
        } else {
            for (int index = 0; index < playing.size(); index++) {
                int position = index;
                boolean removable = playing.size() > 1;
                if (ui.listRow(
                        "track:" + index, (index + 1) + "  " + shortName(playing.get(index)),
                        removable ? REMOVE : "", false, -1) && removable) {
                    model.apply(current -> AreaEdits.removeMusicAt(current, position));
                    return;
                }
            }
            String added = ui.combo("add_track", t("areamusic.editor.add_track"), library, null);
            if (added != null) {
                model.apply(current -> shown == SimpleMode.LAYERS
                        ? AreaEdits.addLayer(current, added)
                        : AreaEdits.addMusic(current, added));
            }
        }
        for (String musicId : new ArrayList<>(playing)) {
            if (!library.contains(musicId)) {
                ui.notice(t("areamusic.editor.missing_music", musicId), true);
            }
        }
    }

    private void buildAdvanced(PanelUi ui, AreaDefinition area, SimpleMode shown) {
        String start = ui.textInput(
                "start", t("areamusic.editor.start_exact"), TrackTime.format(area.startOffsetMs()), 12);
        if (start != null) {
            OptionalInt millis = TrackTime.parseMillis(start);
            if (millis.isPresent()) {
                model.apply(current -> current.withStartOffsetMs(millis.getAsInt()));
            } else {
                notice = t("areamusic.editor.start_invalid", start);
                noticeIsError = true;
                noticeSince = System.nanoTime();
            }
        }
        ui.hint(t("areamusic.editor.start_hint"));
        Float fadeIn = ui.slider(
                "fade_in", t("areamusic.editor.fade_in"), AreaEdits.fadeInOf(area) / 1000.0f, 0.0f, 10.0f, "%.1f s");
        if (fadeIn != null) {
            model.apply(current -> AreaEdits.withFadeIn(current, Math.round(fadeIn * 10.0f) * 100));
        }
        Float fadeOut = ui.slider(
                "fade_out", t("areamusic.editor.fade_out"), AreaEdits.fadeOutOf(area) / 1000.0f, 0.0f, 10.0f, "%.1f s");
        if (fadeOut != null) {
            model.apply(current -> AreaEdits.withFadeOut(current, Math.round(fadeOut * 10.0f) * 100));
        }
        if (area.playback() instanceof PlaylistLoopPlayback) {
            Boolean shuffle = ui.checkbox("shuffle", t("areamusic.editor.shuffle"), AreaEdits.shuffleOf(area));
            if (shuffle != null) {
                model.apply(current -> AreaEdits.withShuffle(current, shuffle));
            }
            Float gap = ui.slider(
                    "gap", t("areamusic.editor.gap"), AreaEdits.gapOf(area) / 1000.0f, 0.0f, 30.0f, "%.1f s");
            if (gap != null) {
                model.apply(current -> AreaEdits.withGap(current, Math.round(gap * 2.0f) * 500));
            }
        }
        Integer priority = ui.intInput("priority", t("areamusic.editor.priority"), area.priority(), -1000, 1000);
        if (priority != null) {
            model.apply(current -> current.withPriority(priority));
        }
        ui.hint(t("areamusic.editor.priority_hint"));
        Boolean resume = ui.checkbox("resume", t("areamusic.editor.resume"), area.resumeOnReenter());
        if (resume != null) {
            model.apply(current -> current.withResumeOnReenter(resume));
        }
        Boolean enabled = ui.checkbox("enabled", t("areamusic.editor.enabled"), area.enabled());
        if (enabled != null) {
            model.apply(current -> current.withEnabled(enabled));
        }
        Boolean announce = ui.checkbox("announce", t("areamusic.editor.announce"), area.announce());
        if (announce != null) {
            model.apply(current -> current.withAnnounce(announce));
        }

        ui.section(t("areamusic.editor.when"));
        AreaCondition condition = area.condition();
        Integer dayPart = ui.choice("when_time", List.of(
                t("areamusic.editor.time.any"), t("areamusic.editor.time.day"), t("areamusic.editor.time.night")
        ), condition.dayPart().ordinal());
        if (dayPart != null) {
            model.apply(current -> current.withCondition(
                    current.condition().withDayPart(AreaCondition.DayPart.values()[dayPart])));
        }
        Integer weather = ui.choice("when_weather", List.of(
                t("areamusic.editor.weather.any"), t("areamusic.editor.weather.clear"),
                t("areamusic.editor.weather.rain"), t("areamusic.editor.weather.thunder")
        ), condition.weather().ordinal());
        if (weather != null) {
            model.apply(current -> current.withCondition(
                    current.condition().withWeather(AreaCondition.Weather.values()[weather])));
        }
        ui.hint(t("areamusic.editor.when_hint"));
        if (ui.quietButton("duplicate_here", t("areamusic.editor.duplicate_here"))) {
            afterBuild.add(() -> model.duplicateSelected(true));
        }

        ui.section(t("areamusic.editor.color"));
        List<Integer> colors = new ArrayList<>();
        colors.add(AreaDefinition.AUTO_COLOR);
        colors.addAll(AreaPalette.colors());
        Integer color = ui.swatches("color", colors, area.color());
        if (color != null) {
            model.apply(current -> current.withColor(color));
        }

        if (!area.wholeDimension()) {
            buildBounds(ui, area);
        }

        if (shown == SimpleMode.LAYERS && area.tracks().size() > 1) {
            ui.section(t("areamusic.editor.per_track"));
            for (int index = 0; index < area.tracks().size(); index++) {
                buildTrack(ui, index, area.tracks().get(index));
            }
        }
    }

    /** The main box with its exact corners, and any further boxes the area is made of. */
    private void buildBounds(PanelUi ui, AreaDefinition area) {
        ui.section(t("areamusic.editor.bounds", size(area.pos1(), area.pos2())));
        if (ui.button("grow", t("areamusic.editor.grow"))) {
            model.apply(current -> AreaEdits.grow(current, 1));
        }
        ui.sameRow();
        if (ui.button("shrink", t("areamusic.editor.shrink"))) {
            model.apply(current -> AreaEdits.grow(current, -1));
        }
        buildCorner(ui, area, 1);
        buildCorner(ui, area, 2);

        List<AreaBox> extras = area.extraBoxes();
        for (int index = 0; index < extras.size(); index++) {
            AreaBox box = extras.get(index);
            // Numbered as the user counts them: the main box is number one.
            int number = index + 2;
            if (ui.listRow("box" + number,
                    t("areamusic.editor.box", number, size(box.pos1(), box.pos2())), REMOVE, false, -1)) {
                model.apply(current -> AreaEdits.removeBox(current, number));
            }
        }
        if (extras.size() < AreaDefinition.MAX_EXTRA_BOXES) {
            if (ui.button("add_box", t("areamusic.editor.add_box"))) {
                afterBuild.add(() -> {
                    if (model.startExtraBox()) {
                        host.pickCornersInWorld();
                    }
                });
            }
            ui.hint(t("areamusic.editor.add_box_hint"));
        }
    }

    /** "the_nether" from "minecraft:the_nether". */
    private static String shortId(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    private void buildCorner(PanelUi ui, AreaDefinition area, int corner) {
        AreaPosition position = corner == 1 ? area.pos1() : area.pos2();
        String[] axes = {"X", "Y", "Z"};
        int[] values = {position.x(), position.y(), position.z()};
        for (int axis = 0; axis < 3; axis++) {
            int which = axis;
            Integer value = ui.intInput(
                    "corner" + corner + axes[axis],
                    t("areamusic.editor.corner", corner, axes[axis]),
                    values[axis], -30_000_000, 30_000_000);
            if (value == null) {
                continue;
            }
            model.apply(current -> {
                AreaPosition old = corner == 1 ? current.pos1() : current.pos2();
                AreaPosition moved = new AreaPosition(
                        which == 0 ? value : old.x(), which == 1 ? value : old.y(), which == 2 ? value : old.z());
                return corner == 1
                        ? current.withBounds(moved, current.pos2())
                        : current.withBounds(current.pos1(), moved);
            });
        }
    }

    private void buildTrack(PanelUi ui, int index, AreaTrackDefinition track) {
        ui.label((index + 1) + "  " + shortName(track.musicId()));
        Float volume = ui.slider(
                "track_volume:" + index, t("areamusic.editor.volume"), track.volume() * 100.0f, 0.0f, 100.0f, "%.0f%%");
        if (volume != null) {
            model.apply(current -> AreaEdits.withTrack(current, index, new AreaTrackDefinition(
                    track.musicId(), track.delaySeconds(), Math.round(volume) / 100.0f, track.loop(),
                    track.fadeInMs(), track.fadeOutMs())));
        }
        Integer delay = ui.intInput(
                "track_delay:" + index, t("areamusic.editor.delay"), track.delaySeconds(), 0, 3600);
        if (delay != null) {
            model.apply(current -> AreaEdits.withTrack(current, index, new AreaTrackDefinition(
                    track.musicId(), delay, track.volume(), track.loop(), track.fadeInMs(), track.fadeOutMs())));
        }
        Boolean loop = ui.checkbox("track_loop:" + index, t("areamusic.editor.loop"), track.loop());
        if (loop != null) {
            model.apply(current -> AreaEdits.withTrack(current, index, new AreaTrackDefinition(
                    track.musicId(), track.delaySeconds(), track.volume(), loop, track.fadeInMs(), track.fadeOutMs())));
        }
    }

    /** Uses an uploaded or dropped track where the user is working. Returns whether it was used. */
    private boolean useMusic(String musicId) {
        EditorModel.Draft draft = model.draft();
        if (draft != null && draft.complete()) {
            return model.completeDraft(musicId).isPresent();
        }
        AreaDefinition area = model.selected().orElse(null);
        if (area == null) {
            return false;
        }
        SimpleMode mode = AreaEdits.modeOf(area);
        boolean layers = mode == SimpleMode.LAYERS || area.id().equals(layersIntent);
        model.apply(current -> layers
                ? AreaEdits.addLayer(current, musicId)
                : mode == SimpleMode.PLAYLIST
                ? AreaEdits.addMusic(current, musicId)
                : AreaEdits.withOnlyMusic(current, musicId));
        return true;
    }

    private String describe(EditorModel.EditError error) {
        if (error.reason() == null) {
            return t("areamusic.editor.error.invalid", error.detail());
        }
        return t("areamusic.editor.error." + error.reason().name().toLowerCase(Locale.ROOT), error.detail());
    }

    /** A track's file name without its folder and extension, for places where space is tight. */
    private static String shortName(String musicId) {
        String name = musicId.substring(musicId.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        int limit = 18;
        return name.codePointCount(0, name.length()) <= limit
                ? name
                : name.substring(0, name.offsetByCodePoints(0, limit - 1)) + "\u2026";
    }

    private static String size(AreaPosition first, AreaPosition second) {
        return (Math.abs((long) first.x() - second.x()) + 1) + " × "
                + (Math.abs((long) first.y() - second.y()) + 1) + " × "
                + (Math.abs((long) first.z() - second.z()) + 1);
    }

    private String t(String key, Object... args) {
        return host.text(key, args);
    }
}
