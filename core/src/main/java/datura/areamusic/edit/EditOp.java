package datura.areamusic.edit;

import datura.areamusic.area.AreaDefinition;

import java.util.Objects;

/** One change to the server's area set. Every applied op has an inverse that undoes it. */
public sealed interface EditOp permits EditOp.Put, EditOp.Remove {
    String areaId();

    /** Creates the area, or replaces the area that has the same ID. */
    record Put(AreaDefinition area) implements EditOp {
        public Put {
            Objects.requireNonNull(area, "area");
        }

        @Override
        public String areaId() {
            return area.id();
        }
    }

    record Remove(String areaId) implements EditOp {
        public Remove {
            Objects.requireNonNull(areaId, "areaId");
        }
    }
}
