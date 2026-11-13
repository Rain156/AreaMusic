package datura.areamusic.server;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

final class ServerLifecycleCoordinator<S, N, K, T> {
    private final N inactiveSender;
    private final Map<K, T> trackers = new HashMap<>();
    private final Map<String, CreateOperation<S>> createsInFlight = new HashMap<>();

    private Lifecycle<S> activeLifecycle;
    private N sender;
    private long generation;
    private ReloadOperation<S> reloadInFlight;

    ServerLifecycleCoordinator(N inactiveSender) {
        this.inactiveSender = Objects.requireNonNull(inactiveSender, "inactiveSender");
        sender = inactiveSender;
    }

    synchronized Lifecycle<S> start(S server, N sender) {
        S checkedServer = Objects.requireNonNull(server, "server");
        N checkedSender = Objects.requireNonNull(sender, "sender");
        activeLifecycle = new Lifecycle<>(checkedServer, ++generation);
        this.sender = checkedSender;
        resetOwnedState();
        return activeLifecycle;
    }

    synchronized boolean stop(S server) {
        if (server == null || activeLifecycle == null || activeLifecycle.server() != server) {
            return false;
        }
        activeLifecycle = null;
        sender = inactiveSender;
        generation++;
        resetOwnedState();
        return true;
    }

    synchronized S server() {
        return activeLifecycle == null ? null : activeLifecycle.server();
    }

    synchronized N senderFor(Lifecycle<S> lifecycle) {
        return owns(lifecycle) ? sender : inactiveSender;
    }

    synchronized boolean owns(Lifecycle<S> lifecycle) {
        return lifecycle != null && activeLifecycle == lifecycle;
    }

    synchronized ReloadOperation<S> beginReload(Lifecycle<S> lifecycle) {
        if (!owns(lifecycle) || reloadInFlight != null || !createsInFlight.isEmpty()) {
            return null;
        }
        reloadInFlight = new ReloadOperation<>(lifecycle);
        return reloadInFlight;
    }

    synchronized Completion<S, N> finishReload(ReloadOperation<S> operation) {
        if (operation == null
                || reloadInFlight != operation
                || !owns(operation.lifecycle())) {
            return null;
        }
        reloadInFlight = null;
        return new Completion<>(activeLifecycle, sender);
    }

    synchronized boolean reloadInFlight() {
        return reloadInFlight != null;
    }

    synchronized CreateOperation<S> beginCreate(Lifecycle<S> lifecycle, String areaId) {
        String checkedAreaId = Objects.requireNonNull(areaId, "areaId");
        if (createBusy(lifecycle, checkedAreaId)) {
            return null;
        }
        CreateOperation<S> operation = new CreateOperation<>(lifecycle, checkedAreaId);
        createsInFlight.put(checkedAreaId, operation);
        return operation;
    }

    synchronized Completion<S, N> finishCreate(CreateOperation<S> operation) {
        if (operation == null
                || createsInFlight.get(operation.areaId()) != operation
                || !owns(operation.lifecycle())) {
            return null;
        }
        createsInFlight.remove(operation.areaId());
        return new Completion<>(activeLifecycle, sender);
    }

    synchronized boolean createInFlight(String areaId) {
        return createsInFlight.containsKey(areaId);
    }

    synchronized boolean createBusy(Lifecycle<S> lifecycle, String areaId) {
        return !owns(lifecycle) || reloadInFlight != null || createInFlight(areaId);
    }

    synchronized T tracker(Lifecycle<S> lifecycle, K key, Supplier<T> factory) {
        Objects.requireNonNull(factory, "factory");
        if (!owns(lifecycle)) {
            return null;
        }
        return trackers.computeIfAbsent(key, ignored -> Objects.requireNonNull(factory.get(), "tracker"));
    }

    synchronized boolean removeTracker(Lifecycle<S> lifecycle, K key) {
        return owns(lifecycle) && trackers.remove(key) != null;
    }

    synchronized boolean clearTrackers(Lifecycle<S> lifecycle) {
        if (!owns(lifecycle)) {
            return false;
        }
        trackers.clear();
        return true;
    }

    synchronized int trackerCount() {
        return trackers.size();
    }

    private void resetOwnedState() {
        trackers.clear();
        createsInFlight.clear();
        reloadInFlight = null;
    }

    record Lifecycle<S>(S server, long generation) {
        Lifecycle {
            Objects.requireNonNull(server, "server");
        }
    }

    record ReloadOperation<S>(Lifecycle<S> lifecycle) {
        S server() {
            return lifecycle.server();
        }
    }

    record CreateOperation<S>(Lifecycle<S> lifecycle, String areaId) {
        S server() {
            return lifecycle.server();
        }
    }

    record Completion<S, N>(Lifecycle<S> lifecycle, N sender) {
        S server() {
            return lifecycle.server();
        }
    }
}
