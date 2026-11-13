package datura.areamusic.edit;

import datura.areamusic.area.AreaDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The authoritative in-memory area set. Callers persist and broadcast after each changed result;
 * this class only validates, applies and versions.
 */
public final class AreaEditService {
    public static final int MAX_AREAS = 4096;

    private final Predicate<String> musicExists;
    private final TreeMap<String, AreaDefinition> areas = new TreeMap<>();
    private volatile List<AreaDefinition> snapshot;
    private volatile long revision;

    public AreaEditService(
            Collection<AreaDefinition> initial,
            long initialRevision,
            Predicate<String> musicExists
    ) {
        Objects.requireNonNull(initial, "initial");
        this.musicExists = Objects.requireNonNull(musicExists, "musicExists");
        if (initialRevision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
        for (AreaDefinition area : initial) {
            areas.put(area.id(), area);
        }
        snapshot = List.copyOf(areas.values());
        revision = initialRevision;
    }

    public synchronized EditResult apply(EditOp op) {
        Objects.requireNonNull(op, "op");
        if (op instanceof EditOp.Remove remove) {
            AreaDefinition previous = areas.remove(remove.areaId());
            if (previous == null) {
                return EditResult.rejected(RejectReason.UNKNOWN_AREA, remove.areaId(), revision);
            }
            return changed(new EditOp.Put(previous));
        }

        AreaDefinition area = ((EditOp.Put) op).area();
        AreaDefinition previous = areas.get(area.id());
        if (area.equals(previous)) {
            return EditResult.applied(false, op, revision);
        }
        if (previous == null && areas.size() >= MAX_AREAS) {
            return EditResult.rejected(RejectReason.TOO_MANY_AREAS, area.id(), revision);
        }
        for (String musicId : area.musicIds()) {
            if (!musicExists.test(musicId)) {
                return EditResult.rejected(RejectReason.UNKNOWN_MUSIC, musicId, revision);
            }
        }
        areas.put(area.id(), area);
        return changed(previous == null ? new EditOp.Remove(area.id()) : new EditOp.Put(previous));
    }

    /** Replaces every area, as after a reload from disk. Music IDs are not validated. */
    public synchronized void replaceAll(Collection<AreaDefinition> replacement) {
        Objects.requireNonNull(replacement, "replacement");
        areas.clear();
        for (AreaDefinition area : replacement) {
            areas.put(area.id(), area);
        }
        snapshot = List.copyOf(areas.values());
        revision++;
    }

    /** Immutable snapshot ordered by area ID. */
    public List<AreaDefinition> areas() {
        return snapshot;
    }

    public synchronized Optional<AreaDefinition> find(String areaId) {
        return Optional.ofNullable(areas.get(areaId));
    }

    public synchronized boolean contains(String areaId) {
        return areas.containsKey(areaId);
    }

    public long revision() {
        return revision;
    }

    private EditResult changed(EditOp inverse) {
        snapshot = List.copyOf(areas.values());
        revision++;
        return EditResult.applied(true, inverse, revision);
    }
}
