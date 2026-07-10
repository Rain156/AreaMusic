package datura.areamusic.server;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaResolver;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

public final class PlayerAreaTracker {
    private ResourceLocation lastDimension;
    private BlockPos lastPosition;
    private long lastRevision = -1L;
    private PlaybackState lastSentState;

    public Optional<PlaybackState> update(
            ResourceLocation dimension,
            BlockPos position,
            long revision,
            Collection<AreaDefinition> areas
    ) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(areas, "areas");
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
        }

        if (dimension.equals(lastDimension) && position.equals(lastPosition) && revision == lastRevision) {
            return Optional.empty();
        }

        lastDimension = dimension;
        lastPosition = position.immutable();
        lastRevision = revision;

        PlaybackState resolved = AreaResolver.resolve(areas, dimension, position)
                .map(PlaybackState::fromArea)
                .orElseGet(PlaybackState::stopped);
        if (resolved.equals(lastSentState)) {
            return Optional.empty();
        }
        lastSentState = resolved;
        return Optional.of(resolved);
    }
}
