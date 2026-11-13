package datura.areamusic.edit;

public enum RejectReason {
    UNKNOWN_AREA,
    UNKNOWN_MUSIC,
    TOO_MANY_AREAS,
    /** The player may not edit areas. */
    NOT_ALLOWED,
    /** A corner lies outside the dimension's build limits. */
    OUT_OF_BOUNDS,
    /** A reload is running; try again in a moment. */
    BUSY
}
