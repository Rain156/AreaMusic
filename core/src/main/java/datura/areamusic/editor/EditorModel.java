package datura.areamusic.editor;

import datura.areamusic.area.AreaBox;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.edit.AreaEdits;
import datura.areamusic.edit.AreaIds;
import datura.areamusic.edit.EditOp;
import datura.areamusic.edit.RejectReason;
import datura.areamusic.net.SyncMessage;
import datura.areamusic.net.SyncMessage.AreaDelta;
import datura.areamusic.net.SyncMessage.EditReply;
import datura.areamusic.net.SyncMessage.EditRequest;
import datura.areamusic.net.SyncMessage.EditorClose;
import datura.areamusic.net.SyncMessage.EditorOpen;
import datura.areamusic.net.SyncMessage.EditorSnapshot;
import datura.areamusic.net.SyncMessage.LibraryList;
import datura.areamusic.sync.MusicFingerprint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * What an open editor knows and can do. Both front ends, the built-in panel and the Axiom tool,
 * read and drive this one model. Edits are applied here at once and confirmed by the server; a
 * rejected edit triggers a fresh snapshot. Client-thread confined.
 */
public final class EditorModel {
    public static final int DEFAULT_AROUND_RADIUS = 16;

    private final Consumer<SyncMessage.Serverbound> sender;
    private final TreeMap<String, AreaDefinition> areas = new TreeMap<>();
    private final List<MusicFingerprint> library = new ArrayList<>();
    private final List<AreaDefinition> incomingAreas = new ArrayList<>();
    private final List<MusicFingerprint> incomingLibrary = new ArrayList<>();
    private final Map<Integer, Pending> pending = new HashMap<>();
    private final Set<String> hidden = new HashSet<>();
    private final UndoStack undo = new UndoStack();
    private List<AreaDefinition> areaSnapshot = List.of();
    private List<String> musicIds = List.of();
    private boolean open;
    private boolean ready;
    private long revision;
    private int nextRequestId = 1;
    private String selectedId;
    private Draft draft;
    private EditError lastError;

    public EditorModel(Consumer<SyncMessage.Serverbound> sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    // ---- lifecycle ----

    /** Subscribes to the server's area list. */
    public void open() {
        open = true;
        ready = false;
        sender.accept(new EditorOpen());
    }

    public void close() {
        if (open) {
            sender.accept(new EditorClose());
        }
        open = false;
        draft = null;
    }

    /** Forgets everything, as on disconnect. Sends nothing. */
    public void reset() {
        open = false;
        ready = false;
        areas.clear();
        library.clear();
        incomingAreas.clear();
        incomingLibrary.clear();
        pending.clear();
        hidden.clear();
        undo.clear();
        areaSnapshot = List.of();
        musicIds = List.of();
        selectedId = null;
        draft = null;
        lastError = null;
    }

    public boolean isOpen() {
        return open;
    }

    /** True once the first full snapshot has arrived. */
    public boolean ready() {
        return ready;
    }

    // ---- messages from the server ----

    public void onSnapshot(EditorSnapshot snapshot) {
        if (snapshot.first()) {
            incomingAreas.clear();
        }
        incomingAreas.addAll(snapshot.areas());
        if (snapshot.last()) {
            areas.clear();
            for (AreaDefinition area : incomingAreas) {
                areas.put(area.id(), area);
            }
            incomingAreas.clear();
            revision = snapshot.revision();
            ready = true;
            areasChanged();
        }
    }

    public void onLibrary(LibraryList list) {
        if (list.first()) {
            incomingLibrary.clear();
        }
        incomingLibrary.addAll(list.entries());
        if (list.last()) {
            library.clear();
            library.addAll(incomingLibrary);
            incomingLibrary.clear();
            musicIds = library.stream().map(MusicFingerprint::musicId).toList();
        }
    }

    public void onDelta(AreaDelta delta) {
        applyLocally(delta.op());
        revision = Math.max(revision, delta.revision());
    }

    public void onReply(EditReply reply) {
        Pending request = pending.remove(reply.requestId());
        if (request == null) {
            return;
        }
        if (!reply.applied()) {
            lastError = new EditError(reply.reason(), reply.detail());
            if (request.kind != Kind.NORMAL) {
                undo.clear();
            }
            if (open) {
                sender.accept(new EditorOpen());
            }
            return;
        }
        if (reply.changed() && reply.inverse() != null && request.kind == Kind.NORMAL) {
            undo.record(reply.inverse(), request.op);
        }
    }

    // ---- queries ----

    /** Areas ordered by ID. The list is replaced, never mutated, when anything changes. */
    public List<AreaDefinition> areas() {
        return areaSnapshot;
    }

    public Optional<AreaDefinition> find(String areaId) {
        return Optional.ofNullable(areas.get(areaId));
    }

    public Optional<AreaDefinition> selected() {
        return selectedId == null ? Optional.empty() : find(selectedId);
    }

    public String selectedId() {
        return selectedId;
    }

    /** Every MusicID in the server's library, sorted. */
    public List<String> musicIds() {
        return musicIds;
    }

    public long revision() {
        return revision;
    }

    public Optional<EditError> lastError() {
        return Optional.ofNullable(lastError);
    }

    public void clearError() {
        lastError = null;
    }

    public boolean hidden(String areaId) {
        return hidden.contains(areaId);
    }

    public void setHidden(String areaId, boolean value) {
        if (value) {
            hidden.add(areaId);
        } else {
            hidden.remove(areaId);
        }
    }

    public Draft draft() {
        return draft;
    }

    public boolean canUndo() {
        return undo.canUndo();
    }

    public boolean canRedo() {
        return undo.canRedo();
    }

    // ---- actions ----

    public void select(String areaId) {
        selectedId = areaId != null && areas.containsKey(areaId) ? areaId : null;
    }

    /**
     * Changes the selected area. A change that throws {@link IllegalArgumentException} is reported
     * through {@link #lastError()} and nothing is sent.
     */
    public void apply(UnaryOperator<AreaDefinition> change) {
        AreaDefinition current = selected().orElse(null);
        if (current == null) {
            return;
        }
        AreaDefinition updated;
        try {
            updated = change.apply(current);
        } catch (IllegalArgumentException exception) {
            lastError = new EditError(null, String.valueOf(exception.getMessage()));
            return;
        }
        if (!updated.equals(current)) {
            request(new EditOp.Put(updated), Kind.NORMAL);
        }
    }

    public void deleteSelected() {
        if (selected().isPresent()) {
            request(new EditOp.Remove(selectedId), Kind.NORMAL);
        }
    }

    /** Copies the selected area next to itself and selects the copy. */
    public Optional<String> duplicateSelected() {
        return duplicateSelected(false);
    }

    /**
     * @param inPlace whether the copy covers exactly the same place. That is how one place gets a
     *                second tune: copy it in place, then tell each copy when it plays.
     */
    public Optional<String> duplicateSelected(boolean inPlace) {
        AreaDefinition source = selected().orElse(null);
        if (source == null) {
            return Optional.empty();
        }
        String id = AreaIds.generate(source.id(), areas::containsKey);
        int shift = source.max().x() - source.min().x() + 1;
        AreaDefinition copy = source.withId(id).withColor(AreaDefinition.AUTO_COLOR);
        if (!source.wholeDimension() && !inPlace) {
            // Beside the original, so both can be seen; every box moves by the same amount.
            copy = copy.withBounds(shifted(source.pos1(), shift), shifted(source.pos2(), shift))
                    .withExtraBoxes(source.extraBoxes().stream()
                            .map(box -> new AreaBox(shifted(box.pos1(), shift), shifted(box.pos2(), shift)))
                            .toList());
        }
        request(new EditOp.Put(copy), Kind.NORMAL);
        selectedId = id;
        return Optional.of(id);
    }

    /** Begins a new area; its two corners are set next. */
    private static AreaPosition shifted(AreaPosition position, int blocksAlongX) {
        return new AreaPosition(position.x() + blocksAlongX, position.y(), position.z());
    }

    public void startDraft(String dimension) {
        draft = new Draft(Objects.requireNonNull(dimension, "dimension"), null, null);
        selectedId = null;
    }

    /**
     * Starts drawing one more box for the selected area, for a room that is not one cuboid. The
     * box is added as soon as its second corner is set; no music needs choosing.
     *
     * @return false when no area is selected or it cannot take another box
     */
    public boolean startExtraBox() {
        AreaDefinition area = selected().orElse(null);
        if (area == null || area.wholeDimension()
                || area.extraBoxes().size() >= AreaDefinition.MAX_EXTRA_BOXES) {
            return false;
        }
        draft = new Draft(area.dimension(), null, null, area.id(), false);
        return true;
    }

    /**
     * A draft for background music that plays everywhere in a dimension. It has no corners to
     * set; it only needs music.
     */
    public void draftWholeDimension(String dimension) {
        AreaPosition origin = new AreaPosition(0, 0, 0);
        draft = new Draft(Objects.requireNonNull(dimension, "dimension"), origin, origin, null, true);
        selectedId = null;
    }

    /**
     * Sets corner 1 or 2 of the draft.
     *
     * @return true when that finished a box being added to an area, which is then done
     */
    public boolean draftCorner(int corner, AreaPosition position) {
        if (draft == null || draft.wholeDimension()) {
            return false;
        }
        draft = corner == 1
                ? new Draft(draft.dimension(), position, draft.pos2(), draft.extraFor(), false)
                : new Draft(draft.dimension(), draft.pos1(), position, draft.extraFor(), false);
        if (draft.extraFor() == null || !draft.complete()) {
            return false;
        }
        Draft box = draft;
        draft = null;
        if (areas.containsKey(box.extraFor())) {
            selectedId = box.extraFor();
            apply(area -> AreaEdits.addBox(area, box.pos1(), box.pos2()));
        }
        return true;
    }

    /** A ready-made draft: a cube of the given radius centered on a position. */
    public void draftAround(String dimension, AreaPosition center, int radius) {
        draft = new Draft(
                dimension,
                new AreaPosition(center.x() - radius, center.y() - radius, center.z() - radius),
                new AreaPosition(center.x() + radius, center.y() + radius, center.z() + radius));
        selectedId = null;
    }

    public void cancelDraft() {
        draft = null;
    }

    /** Creates the drafted area with one looping track and selects it. */
    public Optional<String> completeDraft(String musicId) {
        if (draft == null || !draft.complete() || draft.extraFor() != null) {
            return Optional.empty();
        }
        String id = AreaIds.generate(musicId, areas::containsKey);
        AreaDefinition area;
        try {
            area = AreaEdits.single(id, draft.dimension(), draft.pos1(), draft.pos2(), musicId)
                    .withWholeDimension(draft.wholeDimension());
        } catch (IllegalArgumentException exception) {
            lastError = new EditError(null, String.valueOf(exception.getMessage()));
            return Optional.empty();
        }
        draft = null;
        request(new EditOp.Put(area), Kind.NORMAL);
        selectedId = id;
        return Optional.of(id);
    }

    public void undo() {
        undo.undo().ifPresent(step -> request(step.undo(), Kind.UNDO));
    }

    public void redo() {
        undo.redo().ifPresent(step -> request(step.redo(), Kind.REDO));
    }

    private void request(EditOp op, Kind kind) {
        int requestId = nextRequestId++;
        pending.put(requestId, new Pending(op, kind));
        applyLocally(op);
        sender.accept(new EditRequest(requestId, op));
    }

    private void applyLocally(EditOp op) {
        if (op instanceof EditOp.Put put) {
            areas.put(put.area().id(), put.area());
        } else {
            areas.remove(op.areaId());
            hidden.remove(op.areaId());
        }
        areasChanged();
    }

    private void areasChanged() {
        areaSnapshot = List.copyOf(areas.values());
        if (selectedId != null && !areas.containsKey(selectedId)) {
            selectedId = null;
        }
    }

    /** An area being drawn. Either corner may still be null. */
    /**
     * Something being drawn: a new area, one more box for an existing area, or background music
     * for a whole dimension.
     *
     * @param extraFor       the area the box is being added to, or null for a new area
     * @param wholeDimension whether the new area covers its dimension and so has no corners
     */
    public record Draft(
            String dimension, AreaPosition pos1, AreaPosition pos2, String extraFor, boolean wholeDimension
    ) {
        public Draft(String dimension, AreaPosition pos1, AreaPosition pos2) {
            this(dimension, pos1, pos2, null, false);
        }

        public boolean complete() {
            return pos1 != null && pos2 != null;
        }
    }

    /** Why the last edit failed. {@code reason} is null for a locally invalid value. */
    public record EditError(RejectReason reason, String detail) {
    }

    private enum Kind {
        NORMAL,
        UNDO,
        REDO
    }

    private record Pending(EditOp op, Kind kind) {
    }
}
