package datura.areamusic.edit;

/**
 * Outcome of {@link AreaEditService#apply(EditOp)}.
 *
 * @param applied  whether the op was accepted
 * @param changed  whether the area set differs from before; false for a Put of an identical area
 * @param inverse  the op that undoes this one; null when rejected
 * @param reason   why the op was rejected; null when applied
 * @param detail   the offending area or music ID when rejected, otherwise empty
 * @param revision the service revision after the op
 */
public record EditResult(
        boolean applied,
        boolean changed,
        EditOp inverse,
        RejectReason reason,
        String detail,
        long revision
) {
    static EditResult applied(boolean changed, EditOp inverse, long revision) {
        return new EditResult(true, changed, inverse, null, "", revision);
    }

    static EditResult rejected(RejectReason reason, String detail, long revision) {
        return new EditResult(false, false, null, reason, detail, revision);
    }
}
