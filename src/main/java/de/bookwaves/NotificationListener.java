package de.bookwaves;

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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.concurrent.locks.Lock;

public class NotificationListener implements IReaderListener, IConnectListener {
    
    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);
    private final ReaderModule reader;
    private final ConcurrentLinkedQueue<NotificationEvent> eventQueue;
    private final AtomicInteger eventCount;
    private volatile boolean isConnected = false;
    private final int maxQueueSize;
    private final Lock lock;
    private final ConcurrentHashMap<String, TagItem> latestTagItemsByEpc = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<NotificationEvent>> eventSubscribers = new CopyOnWriteArrayList<>();
    private final Set<Integer> allowedAntennas;

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
        log.info("Notification antenna filter active: {}", this.allowedAntennas.isEmpty() ? "none" : this.allowedAntennas);
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
                    case TagEvent:
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
                NotificationEvent event = new NotificationEvent();
                event.timestamp = java.time.LocalDateTime.now().toString();
                event.eventType = "TAG_EVENT";
                event.epc = tagEvent.tag().iddToHexString();
                latestTagItemsByEpc.put(event.epc.toUpperCase(Locale.ROOT), tagEvent.tag());
                
                // Extract RSSI values
                List<NotificationEvent.AntennaRssi> rssiList = new ArrayList<>();
                for (RssiItem rssiItem : tagEvent.tag().rssiValues()) {
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

                if (!allowedAntennas.isEmpty() && !tagEvent.tag().rssiValues().isEmpty() && rssiList.isEmpty()) {
                    log.debug("Dropped tag event {} due to non-configured antenna RSSI values", event.epc);
                    tagEvent = reader.tagEvent().popItem();
                    continue;
                }

                event.rssiValues = rssiList;
                
                // Extract timestamp if valid
                if (tagEvent.dateTime().isValid()) {
                    event.readerTimestamp = tagEvent.dateTime().toString();
                }
                
                addEvent(event);
                log.debug("Tag event: {} (RSSI values: {})", event.epc, 
                    rssiList.stream()
                        .map(r -> String.format("Ant%d:%ddBm", r.antennaNumber, r.rssi))
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("none"));
            }
            
            tagEvent = reader.tagEvent().popItem();
        }
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
            event.timestamp = java.time.LocalDateTime.now().toString();
            event.eventType = "IDENTIFICATION_EVENT";
            
            addEvent(event);
            log.debug("Identification event received");
        }
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

        public static class AntennaRssi {
            public int antennaNumber;
            public int rssi;

            public AntennaRssi(int antennaNumber, int rssi) {
                this.antennaNumber = antennaNumber;
                this.rssi = rssi;
            }
        }
    }
}
