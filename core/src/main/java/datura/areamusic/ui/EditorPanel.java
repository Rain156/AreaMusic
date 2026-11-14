package datura.areamusic.ui;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPalette;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.edit.AreaEdits;
import datura.areamusic.edit.AreaEdits.SimpleMode;
import datura.areamusic.editor.EditorModel;
import datura.areamusic.net.ClientSync;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
        if (host.closable() && action == actions.size() - 1) {
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

        buildCreate(ui);
        buildAreaList(ui);
        model.selected().ifPresent(area -> buildSelected(ui, area));
    }

    /** The one way to make an area: a button, then three guided steps. */
    private void buildCreate(PanelUi ui) {
        EditorModel.Draft draft = model.draft();
        if (draft == null) {
            if (ui.primaryButton("new_area", t("areamusic.editor.new_area"))) {
                model.startDraft(host.playerDimension());
                host.pickCornersInWorld();
            }
            if (ui.quietButton("around_me", t("areamusic.editor.around_me"))) {
                model.draftAround(
                        host.playerDimension(), host.playerPosition(), EditorModel.DEFAULT_AROUND_RADIUS);
            }
            if (model.areas().isEmpty()) {
                ui.hint(t("areamusic.editor.first_hint"));
            }
            return;
        }

        int corners = (draft.pos1() == null ? 0 : 1) + (draft.pos2() == null ? 0 : 1);
        ui.steps(List.of(
                t("areamusic.editor.step.corner1"),
                t("areamusic.editor.step.corner2"),
                t("areamusic.editor.step.music")
        ), draft.complete() ? 2 : corners);
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
            ui.label(t("areamusic.editor.draft.size", size(draft.pos1(), draft.pos2())));
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
            String meta = !area.dimension().equals(here)
                    ? area.dimension().substring(area.dimension().indexOf(':') + 1)
                    : area.musicIds().size() == 1
                    ? shortName(area.musicIds().get(0))
                    : t("areamusic.editor.track_count", area.musicIds().size());
            boolean selected = area.id().equals(model.selectedId());
            if (ui.listRow("area:" + area.id(), label, meta, selected, area.effectiveColor())) {
                model.select(selected ? null : area.id());
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

        ui.space();
        if (ui.button("teleport", t("areamusic.editor.teleport"))) {
            host.teleportTo(area.id());
        }
        ui.sameRow();
        if (ui.button("repick", t("areamusic.editor.repick"))) {
            host.pickCornersInWorld();
        }
        ui.sameRow();
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

        ui.section(t("areamusic.editor.color"));
        List<Integer> colors = new ArrayList<>();
        colors.add(AreaDefinition.AUTO_COLOR);
        colors.addAll(AreaPalette.colors());
        Integer color = ui.swatches("color", colors, area.color());
        if (color != null) {
            model.apply(current -> current.withColor(color));
        }

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

        if (shown == SimpleMode.LAYERS && area.tracks().size() > 1) {
            ui.section(t("areamusic.editor.per_track"));
            for (int index = 0; index < area.tracks().size(); index++) {
                buildTrack(ui, index, area.tracks().get(index));
            }
        }
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
