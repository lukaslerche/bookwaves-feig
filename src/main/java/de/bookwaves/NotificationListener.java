package de.bookwaves;

import de.bookwaves.tag.Tag;
import de.bookwaves.tag.TagFactory;
import de.feig.fedm.BrmItem;
import de.feig.fedm.ErrorCode;
import de.feig.fedm.IConnectListener;
import de.feig.fedm.IReaderListener;
import de.feig.fedm.PeerInfo;
import de.feig.fedm.ReaderModule;
import de.feig.fedm.TagEventItem;
import de.feig.fedm.TagItem;
import de.feig.fedm.EventType;
import de.feig.fedm.RssiItem;
import de.feig.fedm.types.IntRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.concurrent.locks.Lock;

public class NotificationListener implements IReaderListener, IConnectListener {
    private static final long PRESENCE_TIMEOUT_MS = 1000L;
    private static final long PRESENCE_SWEEP_INTERVAL_MS = 500L;
    private static final int MIN_STABLE_OBSERVATIONS = 3;
    private static final long MIN_STABLE_DURATION_MS = 750L;
    private static final int MAX_STABLE_RSSI = 39;
    
    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);
    private final ReaderModule reader;
    private final ConcurrentLinkedQueue<NotificationEvent> eventQueue;
    private final AtomicInteger eventCount;
    private volatile boolean isConnected = false;
    private final int maxQueueSize;
    private final Lock lock;
    private final ConcurrentHashMap<String, TagItem> latestTagItemsByEpc = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PresenceState> presenceByEpc = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<NotificationEvent>> eventSubscribers = new CopyOnWriteArrayList<>();
    private final Set<Integer> allowedAntennas;
    private final ScheduledExecutorService presenceSweepExecutor;
    private final AtomicLong removedEventCount = new AtomicLong(0);

    public NotificationListener(ReaderModule reader, int maxQueueSize, Lock lock, List<Integer> configuredAntennas) {
        this.reader = reader;
        this.maxQueueSize = maxQueueSize;
        this.eventQueue = new ConcurrentLinkedQueue<>();
        this.eventCount = new AtomicInteger(0);
        this.lock = lock;
        this.allowedAntennas = new HashSet<>();
        if (configuredAntennas != null) {
            for (Integer antenna : configuredAntennas) {
                if (antenna != null && antenna > 0) {
                    this.allowedAntennas.add(antenna);
                }
            }
        }

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "notification-presence-sweep");
            thread.setDaemon(true);
            return thread;
        };
        this.presenceSweepExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);
        this.presenceSweepExecutor.scheduleAtFixedRate(
            this::emitRemovedEventsByTimeout,
            PRESENCE_SWEEP_INTERVAL_MS,
            PRESENCE_SWEEP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        log.info("Notification antenna filter active: {}", this.allowedAntennas.isEmpty() ? "none" : this.allowedAntennas);
        log.info("Notification presence timeout active: {} ms (sweep every {} ms)",
            PRESENCE_TIMEOUT_MS, PRESENCE_SWEEP_INTERVAL_MS);
        log.info("Notification stability criteria active: minObservations={} minDurationMs={} maxStableRssi={}",
            MIN_STABLE_OBSERVATIONS, MIN_STABLE_DURATION_MS, MAX_STABLE_RSSI);
    }

    @Override
    public void onConnect(PeerInfo peerInfo) {
        isConnected = true;
        log.info("Notification mode: Reader connected from {}:{}", 
                 peerInfo.ipAddress(), peerInfo.port());
    }

    @Override
    public void onDisconnect() {
        isConnected = false;
        log.info("Notification mode: Reader disconnected");
    }

    @Override
    public void onNewRequest() {
        // This runs in a separate thread created by the SDK
        try {
            lock.lock();
            
            IntRef stateRef = new IntRef(ErrorCode.Ok);
            EventType eventType = reader.async().popEvent(stateRef);
            int state = stateRef.getValue();
            
            if (state != ErrorCode.Ok) {
                log.error("Error receiving notification: {}", reader.lastErrorStatusText());
                return;
            }

            while (eventType != EventType.Invalid) {
                switch (eventType) {
                    case BrmEvent: // This is supported by the old readers
                        processBrmEvent();
                        break;
                    case TagEvent: // This (and all following) is supported by the new readers
                        processTagEvent();
                        break;
                    case IdentificationEvent:
                        processIdentificationEvent();
                        break;
                    case InputEvent:
                        log.debug("Input event received");
                        break;
                    case DiagEvent:
                        log.debug("Diagnostic event received");
                        break;
                    case PeopleCounterEvent: // This is supported by new and old readers
                        log.debug("People counter event received");
                        break;
                    default:
                        log.debug("Unknown event type: {}", eventType);
                        break;
                }
                
                eventType = reader.async().popEvent(stateRef);
                state = stateRef.getValue();
                if (state != ErrorCode.Ok) {
                    log.error("Error receiving follow-up notification: {}", reader.lastErrorStatusText());
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void processTagEvent() {
        TagEventItem tagEvent = reader.tagEvent().popItem();
        
        while (tagEvent != null) {
            if (tagEvent.tag().isValid()) {
                String readerTimestamp = tagEvent.dateTime().isValid() ? tagEvent.dateTime().toString() : null;
                processTagObservation(tagEvent.tag(), readerTimestamp, "Tag event");
            }
            
            tagEvent = reader.tagEvent().popItem();
        }
    }

    private void processBrmEvent() {
        BrmItem brmItem = reader.brm().popItem();

        while (brmItem != null) {
            if (brmItem.tag().isValid()) {
                String readerTimestamp = brmItem.dateTime().isValid() ? brmItem.dateTime().toString() : null;
                processTagObservation(brmItem.tag(), readerTimestamp, "Brm event");
            }

            brmItem = reader.brm().popItem();
        }
    }

    private void processTagObservation(TagItem tagItem, String readerTimestamp, String sourceLogName) {
        NotificationEvent event = new NotificationEvent();
        event.timestamp = nowTimestamp();
        event.eventType = "TAG_EVENT";
        event.epc = tagItem.iddToHexString();
        enrichEventWithTagMetadata(event);
        latestTagItemsByEpc.put(event.epc.toUpperCase(Locale.ROOT), tagItem);

        // Keep old/new-reader event handling consistent and reuse antenna filtering.
        List<NotificationEvent.AntennaRssi> rssiList = extractAllowedRssiValues(tagItem);
        if (shouldDropByAntennaFilter(tagItem, rssiList)) {
            log.debug("Dropped {} {} due to non-configured antenna RSSI values", sourceLogName, event.epc);
            return;
        }

        event.rssiValues = rssiList;
        event.readerTimestamp = readerTimestamp;

        updatePresence(event);
        addEvent(event);

        log.debug("{}: {} (RSSI values: {})", sourceLogName, event.epc,
            rssiList.stream()
                .map(r -> String.format("Ant%d:%ddBm", r.antennaNumber, r.rssi))
                .reduce((a, b) -> a + ", " + b)
                .orElse("none"));
    }

    private List<NotificationEvent.AntennaRssi> extractAllowedRssiValues(TagItem tagItem) {
        List<NotificationEvent.AntennaRssi> rssiList = new ArrayList<>();
        for (RssiItem rssiItem : tagItem.rssiValues()) {
            if (rssiItem.isValid()) {
                int antennaNumber = rssiItem.antennaNumber();
                if (allowedAntennas.isEmpty() || allowedAntennas.contains(antennaNumber)) {
                    rssiList.add(new NotificationEvent.AntennaRssi(
                        antennaNumber,
                        rssiItem.rssi()
                    ));
                }
            }
        }
        return rssiList;
    }

    private boolean shouldDropByAntennaFilter(TagItem tagItem, List<NotificationEvent.AntennaRssi> rssiList) {
        return !allowedAntennas.isEmpty() && !tagItem.rssiValues().isEmpty() && rssiList.isEmpty();
    }

    public TagItem getLatestTagItemByEpc(String epcHex) {
        if (epcHex == null || epcHex.isBlank()) {
            return null;
        }
        return latestTagItemsByEpc.get(epcHex.toUpperCase(Locale.ROOT));
    }

    private void processIdentificationEvent() {
        if (reader.identification().isValid()) {
            NotificationEvent event = new NotificationEvent();
            event.timestamp = nowTimestamp();
            event.eventType = "IDENTIFICATION_EVENT";
            
            addEvent(event);
            log.debug("Identification event received");
        }
    }

    private void updatePresence(NotificationEvent event) {
        if (event == null || event.epc == null || event.epc.isBlank()) {
            return;
        }

        String normalizedEpc = event.epc.toUpperCase(Locale.ROOT);
        long nowMillis = System.currentTimeMillis();

        final boolean[] becameStable = new boolean[1];
        final boolean[] becameUnstable = new boolean[1];

        presenceByEpc.compute(normalizedEpc, (epc, previous) -> {
            PresenceState state = previous == null ? new PresenceState() : previous;
            boolean wasStable = state.stable;

            if (previous == null) {
                state.firstSeenMillis = nowMillis;
                state.seenCount = 0;
            }

            state.lastSeenMillis = nowMillis;
            state.seenCount += 1;
            state.lastReaderTimestamp = event.readerTimestamp;
            state.lastRssiValues = event.rssiValues == null ? new ArrayList<>() : new ArrayList<>(event.rssiValues);
            state.lastBestRssi = bestRssi(state.lastRssiValues);
            state.lastTagType = event.tagType;
            state.lastMediaId = event.mediaId;
            state.lastSecured = event.secured;
            state.lastPc = event.pc;

            long presenceDurationMs = Math.max(0L, nowMillis - state.firstSeenMillis);
            boolean isStableNow = state.seenCount >= MIN_STABLE_OBSERVATIONS
                && presenceDurationMs >= MIN_STABLE_DURATION_MS
                && state.lastBestRssi <= MAX_STABLE_RSSI;

            state.stable = isStableNow;

            if (!wasStable && isStableNow) {
                becameStable[0] = true;
            } else if (wasStable && !isStableNow) {
                becameUnstable[0] = true;
            }

            event.stable = isStableNow;
            event.seenCount = state.seenCount;
            event.presenceDurationMs = presenceDurationMs;
            event.bestRssi = state.lastBestRssi;

            return state;
        });

        if (becameStable[0]) {
            addStabilityTransitionEvent("TAG_STABLE", normalizedEpc);
        } else if (becameUnstable[0]) {
            addStabilityTransitionEvent("TAG_UNSTABLE", normalizedEpc);
        }
    }

    private void addStabilityTransitionEvent(String eventType, String epc) {
        PresenceState state = presenceByEpc.get(epc);
        if (state == null) {
            return;
        }

        NotificationEvent transitionEvent = new NotificationEvent();
        transitionEvent.timestamp = nowTimestamp();
        transitionEvent.eventType = eventType;
        transitionEvent.epc = epc;
        transitionEvent.readerTimestamp = state.lastReaderTimestamp;
        transitionEvent.rssiValues = state.lastRssiValues == null ? List.of() : new ArrayList<>(state.lastRssiValues);
        transitionEvent.stable = state.stable;
        transitionEvent.seenCount = state.seenCount;
        transitionEvent.presenceDurationMs = Math.max(0L, System.currentTimeMillis() - state.firstSeenMillis);
        transitionEvent.bestRssi = state.lastBestRssi;
        transitionEvent.tagType = state.lastTagType;
        transitionEvent.mediaId = state.lastMediaId;
        transitionEvent.secured = state.lastSecured;
        transitionEvent.pc = state.lastPc;
        addEvent(transitionEvent);
    }

    private void emitRemovedEventsByTimeout() {
        long nowMillis = System.currentTimeMillis();
        long staleThreshold = nowMillis - PRESENCE_TIMEOUT_MS;

        for (var entry : presenceByEpc.entrySet()) {
            String epc = entry.getKey();
            PresenceState state = entry.getValue();
            if (state == null || state.lastSeenMillis > staleThreshold) {
                continue;
            }

            boolean removed = presenceByEpc.remove(epc, state);
            if (!removed) {
                continue;
            }

            latestTagItemsByEpc.remove(epc);

            if (state.stable) {
                NotificationEvent unstableEvent = new NotificationEvent();
                unstableEvent.timestamp = nowTimestamp();
                unstableEvent.eventType = "TAG_UNSTABLE";
                unstableEvent.epc = epc;
                unstableEvent.readerTimestamp = state.lastReaderTimestamp;
                unstableEvent.rssiValues = state.lastRssiValues == null ? List.of() : new ArrayList<>(state.lastRssiValues);
                unstableEvent.stable = false;
                unstableEvent.seenCount = state.seenCount;
                unstableEvent.presenceDurationMs = Math.max(0L, state.lastSeenMillis - state.firstSeenMillis);
                unstableEvent.bestRssi = state.lastBestRssi;
                unstableEvent.tagType = state.lastTagType;
                unstableEvent.mediaId = state.lastMediaId;
                unstableEvent.secured = state.lastSecured;
                unstableEvent.pc = state.lastPc;
                addEvent(unstableEvent);
            }

            NotificationEvent removedEvent = new NotificationEvent();
            removedEvent.timestamp = nowTimestamp();
            removedEvent.eventType = "TAG_REMOVED";
            removedEvent.epc = epc;
            removedEvent.readerTimestamp = state.lastReaderTimestamp;
            removedEvent.rssiValues = state.lastRssiValues == null ? List.of() : new ArrayList<>(state.lastRssiValues);
            removedEvent.stable = false;
            removedEvent.seenCount = state.seenCount;
            removedEvent.presenceDurationMs = Math.max(0L, state.lastSeenMillis - state.firstSeenMillis);
            removedEvent.bestRssi = state.lastBestRssi;
            removedEvent.tagType = state.lastTagType;
            removedEvent.mediaId = state.lastMediaId;
            removedEvent.secured = state.lastSecured;
            removedEvent.pc = state.lastPc;

            addEvent(removedEvent);
            long totalRemoved = removedEventCount.incrementAndGet();
            log.debug("Tag removed by timeout: {} (timeout={}ms, totalRemovedEvents={})", epc, PRESENCE_TIMEOUT_MS, totalRemoved);
        }
    }

    private static String nowTimestamp() {
        return LocalDateTime.now().toString();
    }

    private static int bestRssi(List<NotificationEvent.AntennaRssi> rssiValues) {
        if (rssiValues == null || rssiValues.isEmpty()) {
            return Integer.MIN_VALUE;
        }

        int best = Integer.MIN_VALUE;
        for (NotificationEvent.AntennaRssi value : rssiValues) {
            if (value != null) {
                best = Math.max(best, value.rssi);
            }
        }
        return best;
    }

    private void enrichEventWithTagMetadata(NotificationEvent event) {
        if (event == null || event.epc == null || event.epc.isBlank()) {
            return;
        }

        try {
            Tag parsedTag = TagFactory.createTagFromHexString(event.epc);
            event.tagType = parsedTag.getTagType();
            event.mediaId = parsedTag.getMediaId();
            event.secured = parsedTag.isSecured();
            event.pc = parsedTag.getPCHexString();
        } catch (IllegalArgumentException e) {
            log.debug("Could not parse EPC {} into known tag metadata: {}", event.epc, e.getMessage());
        } catch (Exception e) {
            log.warn("Unexpected error while parsing EPC {} for notification metadata: {}", event.epc, e.getMessage());
        }
    }

    public void close() {
        presenceSweepExecutor.shutdownNow();
        presenceByEpc.clear();
    }

    private void addEvent(NotificationEvent event) {
        eventQueue.offer(event);
        int currentSize = eventCount.incrementAndGet();

        // Keep queue size limited without O(n) queue size scans
        while (currentSize > maxQueueSize) {
            NotificationEvent dropped = eventQueue.poll();
            if (dropped != null) {
                currentSize = eventCount.decrementAndGet();
                log.warn("Event queue full, dropped event: {}", dropped.eventType);
            } else {
                break;
            }
        }

        for (Consumer<NotificationEvent> subscriber : eventSubscribers) {
            try {
                subscriber.accept(event);
            } catch (Exception e) {
                log.debug("Notification event subscriber failed: {}", e.getMessage());
            }
        }
    }

    public void addEventSubscriber(Consumer<NotificationEvent> subscriber) {
        if (subscriber != null) {
            eventSubscribers.add(subscriber);
        }
    }

    public void removeEventSubscriber(Consumer<NotificationEvent> subscriber) {
        if (subscriber != null) {
            eventSubscribers.remove(subscriber);
        }
    }

    public List<NotificationEvent> getEvents() {
        return new ArrayList<>(eventQueue);
    }

    public List<NotificationEvent> pollEvents() {
        List<NotificationEvent> events = new ArrayList<>();
        NotificationEvent event;
        while ((event = eventQueue.poll()) != null) {
            events.add(event);
            eventCount.decrementAndGet();
        }
        return events;
    }

    public void clearEvents() {
        eventQueue.clear();
        eventCount.set(0);
    }

    public boolean isConnected() {
        return isConnected;
    }

    public int getEventCount() {
        return eventCount.get();
    }

    public static class NotificationEvent {
        public String timestamp;
        public String eventType;
        public String epc;
        public List<AntennaRssi> rssiValues;
        public String readerTimestamp;
        public boolean stable;
        public int seenCount;
        public long presenceDurationMs;
        public int bestRssi = Integer.MIN_VALUE;
        public String mediaId;
        public Boolean secured;
        public String tagType;
        public String pc;

        public static class AntennaRssi {
            public int antennaNumber;
            public int rssi;

            public AntennaRssi(int antennaNumber, int rssi) {
                this.antennaNumber = antennaNumber;
                this.rssi = rssi;
            }
        }
    }

    private static class PresenceState {
        volatile long firstSeenMillis;
        volatile long lastSeenMillis;
        volatile String lastReaderTimestamp;
        volatile List<NotificationEvent.AntennaRssi> lastRssiValues;
        volatile int seenCount;
        volatile int lastBestRssi;
        volatile boolean stable;
        volatile String lastMediaId;
        volatile Boolean lastSecured;
        volatile String lastTagType;
        volatile String lastPc;
    }
}
