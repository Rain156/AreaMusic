package datura.areamusic.server;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.area.AreaResolver;
import datura.areamusic.area.WorldMoment;
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
    private WorldMoment lastMoment;
    private PlaybackState lastSentState;

    public Optional<PlaybackState> update(
            ResourceLocation dimension,
            BlockPos position,
            long revision,
            Collection<AreaDefinition> areas
    ) {
        return update(dimension, position, revision, areas, WorldMoment.CLEAR_DAY);
    }

    /**
     * @param moment the time of day and weather where the player is; an area may play only in
     *               some of them, so a player standing still can still change areas
     */
    public Optional<PlaybackState> update(
            ResourceLocation dimension,
            BlockPos position,
            long revision,
            Collection<AreaDefinition> areas,
            WorldMoment moment
    ) {
        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(areas, "areas");
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
        }

        if (dimension.equals(lastDimension) && position.equals(lastPosition) && revision == lastRevision
                && moment.equals(lastMoment)) {
            return Optional.empty();
        }
        lastMoment = moment;

        lastDimension = dimension;
        lastPosition = position.immutable();
        lastRevision = revision;

        PlaybackState resolved = AreaResolver.resolve(
                        areas,
                        dimension.toString(),
                        new AreaPosition(position.getX(), position.getY(), position.getZ()),
                        moment
                )
                .map(PlaybackState::fromArea)
                .orElseGet(PlaybackState::stopped);
        if (resolved.equals(lastSentState)) {
            return Optional.empty();
        }
        lastSentState = resolved;
        return Optional.of(resolved);
    }
}
