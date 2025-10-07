package de.bookwaves;

import de.feig.fedm.ErrorCode;
import de.feig.fedm.IConnectListener;
import de.feig.fedm.IReaderListener;
import de.feig.fedm.PeerInfo;
import de.feig.fedm.ReaderModule;
import de.feig.fedm.TagEventItem;
import de.feig.fedm.EventType;
import de.feig.fedm.RssiItem;
import de.feig.fedm.types.IntRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;

public class NotificationListener implements IReaderListener, IConnectListener {
    
    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);
    private final ReaderModule reader;
    private final ConcurrentLinkedQueue<NotificationEvent> eventQueue;
    private volatile boolean isConnected = false;
    private final int maxQueueSize;
    private final Lock lock;

    public NotificationListener(ReaderModule reader, int maxQueueSize, Lock lock) {
        this.reader = reader;
        this.maxQueueSize = maxQueueSize;
        this.eventQueue = new ConcurrentLinkedQueue<>();
        this.lock = lock;
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
                event.idd = tagEvent.tag().iddToHexString();
                
                // Extract RSSI values
                List<NotificationEvent.AntennaRssi> rssiList = new ArrayList<>();
                for (RssiItem rssiItem : tagEvent.tag().rssiValues()) {
                    if (rssiItem.isValid()) {
                        rssiList.add(new NotificationEvent.AntennaRssi(
                            rssiItem.antennaNumber(), 
                            rssiItem.rssi()
                        ));
                    }
                }
                event.rssiValues = rssiList;
                
                // Extract timestamp if valid
                if (tagEvent.dateTime().isValid()) {
                    event.readerTimestamp = tagEvent.dateTime().toString();
                }
                
                addEvent(event);
                log.debug("Tag event: {} (RSSI values: {})", event.idd, rssiList.size());
            }
            
            tagEvent = reader.tagEvent().popItem();
        }
    }

    private void processIdentificationEvent() {
        if (reader.identification().isValid()) {
            NotificationEvent event = new NotificationEvent();
            event.timestamp = java.time.LocalDateTime.now().toString();
            event.eventType = "IDENTIFICATION_EVENT";
            event.readerType = reader.identification().readerTypeToString();
            event.firmwareVersion = reader.identification().versionToString();
            
            addEvent(event);
            log.debug("Identification event: {}", event.readerType);
        }
    }

    private void addEvent(NotificationEvent event) {
        eventQueue.offer(event);
        
        // Keep queue size limited
        while (eventQueue.size() > maxQueueSize) {
            NotificationEvent dropped = eventQueue.poll();
            if (dropped != null) {
                log.warn("Event queue full, dropped event: {}", dropped.eventType);
            }
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
        }
        return events;
    }

    public void clearEvents() {
        eventQueue.clear();
    }

    public boolean isConnected() {
        return isConnected;
    }

    public int getEventCount() {
        return eventQueue.size();
    }

    public static class NotificationEvent {
        public String timestamp;
        public String eventType;
        public String idd;
        public List<AntennaRssi> rssiValues;
        public String readerTimestamp;
        public String readerType;
        public String firmwareVersion;

        public static class AntennaRssi {
            public int antenna;
            public int rssi;

            public AntennaRssi(int antenna, int rssi) {
                this.antenna = antenna;
                this.rssi = rssi;
            }
        }
    }
}
