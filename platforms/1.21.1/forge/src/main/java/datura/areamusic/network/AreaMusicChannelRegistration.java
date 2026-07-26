package datura.areamusic.network;

import java.util.Objects;
import java.util.function.Supplier;

final class AreaMusicChannelRegistration<T> {
    private enum State {
        NEW,
        REGISTERING,
        REGISTERED,
        FAILED
    }

    private final String description;
    private final Supplier<? extends T> factory;

    private State state = State.NEW;
    private T registeredValue;
    private Throwable failure;

    AreaMusicChannelRegistration(String description, Supplier<? extends T> factory) {
        this.description = Objects.requireNonNull(description, "description");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    synchronized T register() {
        return switch (state) {
            case REGISTERED -> registeredValue;
            case FAILED -> throw permanentFailure();
            case REGISTERING -> throw new IllegalStateException(
                    description + " registration is already in progress"
            );
            case NEW -> beginRegistration();
        };
    }

    synchronized T requireRegistered() {
        return switch (state) {
            case REGISTERED -> registeredValue;
            case FAILED -> throw permanentFailure();
            case NEW -> throw new IllegalStateException(
                    description + " has not been registered"
            );
            case REGISTERING -> throw new IllegalStateException(
                    description + " registration is still in progress"
            );
        };
    }

    private T beginRegistration() {
        state = State.REGISTERING;
        try {
            T created = Objects.requireNonNull(
                    factory.get(),
                    description + " factory returned null"
            );
            registeredValue = created;
            state = State.REGISTERED;
            return created;
        } catch (RuntimeException | LinkageError registrationFailure) {
            failure = registrationFailure;
            state = State.FAILED;
            throw permanentFailure();
        }
    }

    private IllegalStateException permanentFailure() {
        return new IllegalStateException(
                description + " registration failed permanently; " +
                        "Forge registry state may already have been mutated",
                failure
        );
    }
}
