package de.bookwaves;

import de.bookwaves.sync.ConfigurationSync;
import de.bookwaves.sync.FeigReaderConfigPort;
import de.bookwaves.sync.ReaderProfile;
import de.bookwaves.sync.SyncReport;

import de.feig.fedm.Connector;
import de.feig.fedm.ErrorCode;
import de.feig.fedm.ListenerParam;
import de.feig.fedm.ReaderModule;
import de.feig.fedm.RequestMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manager class to handle multiple RFID readers.
 */
public class ReaderManager {
    private static final Logger log = LoggerFactory.getLogger(ReaderManager.class);
    private final Map<String, ManagedReader> readers = new HashMap<>();
    
    // Reconnection configuration
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final int RECONNECT_DELAY_MS = 1000;

    /**
     * Functional interface for reader operations that may fail due to connection issues.
     */
    @FunctionalInterface
    public interface ReaderOperation<T> {
        T execute(ReaderModule reader) throws Exception;
    }

    /**
     * Functional interface for void reader operations (no return value).
     */
    @FunctionalInterface
    public interface VoidReaderOperation {
        void execute(ReaderModule reader) throws Exception;
    }

    /**
     * Exception thrown when a reader operation fails after all retry attempts.
     */
    public static class ReaderOperationException extends Exception {
        private final int errorCode;
        
        public ReaderOperationException(String message, int errorCode) {
            super(message);
            this.errorCode = errorCode;
        }
        
        public ReaderOperationException(String message) {
            super(message);
            this.errorCode = -1;
        }
        
        public int getErrorCode() {
            return errorCode;
        }
    }

    /**
     * How a reader's live configuration stands against its YAML configuration.
     */
    public enum SyncState {
        /** The reader's configuration is not managed by this service. */
        UNMANAGED,
        /** The reader has not been reachable since startup, so nothing is known. */
        NEVER_CHECKED,
        /** The reader could not be contacted. */
        UNREACHABLE,
        /** The reader matches its configuration. */
        IN_SYNC,
        /** The reader differs from its configuration and could not be repaired. */
        DRIFTED
    }

    public static class ManagedReader {
        private final ReaderConfig config;
        private ReaderModule readerModule;
        private NotificationListener notificationListener;
        private int listenerPort = -1;
        private final Lock lock = new ReentrantLock(true);
        private volatile String lastConnectionStatus = "not_initialized";
        private volatile String lastConnectionError = null;
        private volatile SyncState syncState = SyncState.NEVER_CHECKED;
        private volatile String syncDetail = null;

        public ManagedReader(ReaderConfig config) {
            this.config = config;
            if (!config.isManaged()) {
                this.syncState = SyncState.UNMANAGED;
            }
        }

        public synchronized ReaderModule getModule() throws Exception {
            if (readerModule == null) {
                readerModule = new ReaderModule(RequestMode.UniDirectional);
                log.debug("Created ReaderModule for {} in UniDirectional mode", config.getName());
                lastConnectionStatus = "disconnected";
            }

            if (!readerModule.isConnected()) {
                log.debug("Attempting fresh TCP connect to {}", config.getName());
                int returnCode = readerModule.connect(createConnector());

                if (returnCode != ErrorCode.Ok) {
                    lastConnectionStatus = "error";
                    lastConnectionError = readerModule.lastErrorStatusText();
                    throw new Exception("Failed to connect to reader " + config.getName() + ": " +
                                      readerModule.lastErrorStatusText() + " (code: " + returnCode + ")");
                }

                log.debug("Connected to reader {}", config.getName());
                lastConnectionStatus = "connected";
                lastConnectionError = null;
            } else {
                lastConnectionStatus = "connected";
            }

            return readerModule;
        }

        /**
         * Builds the connector for this reader. One place, so a reconnect uses the same
         * credentials as the initial connect.
         */
        private Connector createConnector() {
            Connector connector = Connector.createTcpConnector(config.getAddress(), config.getPort());
            connector.setTcpConnectTimeout(5000);
            if (config.hasCredentials()) {
                connector.setAuthentication(config.getUsername(), config.getPassword());
                log.debug("Using authenticated connection for reader {}", config.getName());
            }
            return connector;
        }

        /**
         * Fast status check for listing endpoints.
         * Never triggers reconnect/connect attempts.
         */
        public synchronized boolean isConnectedFast() {
            return readerModule != null && readerModule.isConnected();
        }

        /**
         * Fast status text for listing endpoints.
         * Never triggers reconnect/connect attempts.
         */
        public synchronized String getConnectionStatusFast() {
            if (readerModule == null) {
                return lastConnectionStatus;
            }
            return readerModule.isConnected() ? "connected" : "disconnected";
        }

        public String getLastConnectionError() {
            return lastConnectionError;
        }

        /**
         * Force a full disconnect and reconnect cycle.
         * Useful when the connection is stale or corrupted.
         */
        public synchronized void forceReconnect() throws Exception {
            // Close existing module completely
            if (readerModule != null) {
                try {
                    if (readerModule.isConnected()) {
                        log.info("Forcing disconnect from {}", config.getName());
                        readerModule.disconnect();
                    }
                    readerModule.close();
                } catch (Exception e) {
                    log.warn("Error during forced disconnect: {}", e.getMessage());
                }
                readerModule = null; // Important: clear the reference
            }
            
            // Create fresh ReaderModule instance
            log.debug("Creating new ReaderModule instance for {}", config.getName());
            readerModule = new ReaderModule(RequestMode.UniDirectional);

            // Establish fresh connection, with the same credentials as the initial connect
            log.debug("Attempting fresh connection to {}", config.getName());
            int returnCode = readerModule.connect(createConnector());

            if (returnCode != ErrorCode.Ok) {
                String errorMsg = readerModule.lastErrorStatusText();
                readerModule.close();
                readerModule = null;
                throw new Exception("Failed to reconnect to reader " + config.getName() + ": " + 
                                  errorMsg + " (code: " + returnCode + ")");
            }
            
            log.info("Successfully reconnected to {}", config.getName());
        }

        /**
         * Brings the reader's configuration in line with {@code config.yaml}. Only
         * parameters that differ are written.
         *
         * @param force write every parameter whether or not it differs
         * @throws Exception if the reader cannot be reached or a parameter cannot be written
         */
        public synchronized SyncReport syncConfiguration(boolean force) throws Exception {
            Optional<ReaderProfile> profile = config.getProfile();
            if (profile.isEmpty()) {
                syncState = SyncState.UNMANAGED;
                log.debug("Reader {} is not configuration managed, nothing to synchronise", config.getName());
                return new SyncReport(config.getName(), java.util.List.of(), java.util.List.of());
            }

            if (!config.isNotificationMode()) {
                log.warn("Reader {} runs in host mode, where configuration sync covers only "
                    + "the operating mode and antenna settings; the remaining host mode "
                    + "settings from the README must still be set by hand", config.getName());
            }

            ReaderModule module;
            try {
                module = getModule();
            } catch (Exception e) {
                syncState = SyncState.UNREACHABLE;
                syncDetail = e.getMessage();
                throw e;
            }

            ConfigurationSync sync = new ConfigurationSync(
                profile.get(),
                ConfigLoader.getHostName(),
                ConfigLoader.isReaderConfigurationPersistent());

            try {
                SyncReport report = sync.apply(
                    new FeigReaderConfigPort(module, config.getName()), config, force);
                syncState = SyncState.IN_SYNC;
                syncDetail = report.written().isEmpty()
                    ? "configuration matches"
                    : "repaired " + report.written().size() + " parameter(s)";
                log.info("{}", report.summary());
                return report;
            } catch (ReaderOperationException e) {
                syncState = SyncState.DRIFTED;
                syncDetail = e.getMessage();
                throw e;
            }
        }

        /** How this reader's configuration stands against {@code config.yaml}. */
        public SyncState getSyncState() {
            return syncState;
        }

        /** Why the reader is in its current sync state, or null when never checked. */
        public String getSyncDetail() {
            return syncDetail;
        }

        /**
         * Execute a reader operation with automatic reconnection on connection errors.
         * This method handles transient network failures by retrying the operation
         * after reconnecting to the reader.
         * 
         * @param operation The operation to execute
         * @param <T> The return type of the operation
         * @return The result of the operation
         * @throws ReaderOperationException if the operation fails after all retries
         */
        public synchronized <T> T executeWithReconnect(ReaderOperation<T> operation) throws ReaderOperationException {
            lock.lock();
            try {
                Exception lastException = null;

                for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
                    try {
                        ReaderModule reader = getModule();
                        if (attempt > 1) {
                            log.info("Recovered connection to {} on attempt {}", config.getName(), attempt);
                        }
                        return operation.execute(reader);
                    } catch (Exception e) {
                        lastException = e;
                        String errorMsg = e.getMessage();

                        // Check if it's a connection error
                        if (isConnectionError(errorMsg)) {
                            log.warn("Connection error on attempt {}/{} for {}: {}",
                                attempt, MAX_RECONNECT_ATTEMPTS, config.getName(), errorMsg);

                            if (attempt < MAX_RECONNECT_ATTEMPTS) {
                                try {
                                    Thread.sleep(RECONNECT_DELAY_MS * attempt); // Exponential backoff
                                    forceReconnect();
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    throw new ReaderOperationException("Operation interrupted during reconnection");
                                } catch (Exception reconnectError) {
                                    log.error("Reconnection failed for {}: {}", config.getName(), reconnectError.getMessage());
                                }
                            }
                        } else {
                            // Non-connection error, don't retry
                            throw new ReaderOperationException("Operation failed: " + errorMsg);
                        }
                    }
                }

                // All attempts failed
                throw new ReaderOperationException(
                    "Operation failed after " + MAX_RECONNECT_ATTEMPTS + " attempts: " +
                    (lastException != null ? lastException.getMessage() : "Unknown error")
                );
            } finally {
                lock.unlock();
            }
        }

        /**
         * Execute a void reader operation with automatic reconnection on connection errors.
         * Convenience method for operations that don't return a value.
         * 
         * @param operation The operation to execute
         * @throws ReaderOperationException if the operation fails after all retries
         */
        public void executeWithReconnect(VoidReaderOperation operation) throws ReaderOperationException {
            executeWithReconnect(reader -> {
                operation.execute(reader);
                return null;
            });
        }

        /**
         * Check if an error message indicates a connection problem.
         */
        private boolean isConnectionError(String errorMessage) {
            if (errorMessage == null) {
                return false;
            }
            String lowerMsg = errorMessage.toLowerCase();
            return lowerMsg.contains("disconnected") || 
                   lowerMsg.contains("connection lost") ||
                   lowerMsg.contains("connection timeout") ||
                   lowerMsg.contains("transmit failed") ||
                   lowerMsg.contains("peer") ||
                   lowerMsg.contains("-5012") ||
                   lowerMsg.contains("-5011") ||
                   lowerMsg.contains("-5010") ||
                   lowerMsg.contains("-1520");
        }

        public synchronized boolean startNotificationMode(int port) throws Exception {
            if (notificationListener != null) {
                log.debug("Notification already active for {} on port {}", config.getName(), listenerPort);
                return false; // Already running
            }

            try {
                lock.lock();

                // Ensure reader is initialized and currently connected
                getModule();

                // Nothing else calls a notification reader, so this is where it gets
                // synchronised if it was unreachable at startup.
                syncConfiguration(false);

                notificationListener = new NotificationListener(
                    readerModule,
                    1000,
                    lock,
                    config.getAntennas(),
                    config.isHfProtocol()
                );
                
                int state = readerModule.async().startNotification(notificationListener);
                if (state != ErrorCode.Ok) {
                    log.error("Failed to start notification: {}", readerModule.lastErrorStatusText());
                    notificationListener.close();
                    notificationListener = null;
                    return false;
                }

                boolean keepAlive = true;
                state = readerModule.startListenerThread(
                    ListenerParam.createTcpListenerParam(port, "0.0.0.0", keepAlive),
                    notificationListener
                );
                
                if (state != ErrorCode.Ok) {
                    // A failure here means something else holds the port, or this process
                    // may not bind it.
                    log.error("Reader {}: could not bind notification listener port {} - {}. "
                        + "Free the port, or check that another instance of this service is "
                        + "not already running.",
                        config.getName(), port, readerModule.lastErrorStatusText());
                    readerModule.async().stopNotification();
                    notificationListener.close();
                    notificationListener = null;
                    return false;
                }

                listenerPort = port;
                log.info("Notification mode started for {} on port {}", config.getName(), port);
                return true;
            } finally {
                lock.unlock();
            }
        }

        public synchronized boolean stopNotificationMode() {
            if (notificationListener == null) {
                log.debug("Notification stop requested but none active for {}", config.getName());
                return false; // Not running
            }

            try {
                lock.lock();
                
                if (readerModule != null) {
                    int state = readerModule.stopListenerThread();
                    if (state != ErrorCode.Ok) {
                        log.warn("Failed to stop listener thread: {}", readerModule.lastErrorStatusText());
                    }

                    state = readerModule.async().stopNotification();
                    if (state != ErrorCode.Ok) {
                        log.warn("Failed to stop notification: {}", readerModule.lastErrorStatusText());
                    }
                }

                notificationListener.close();

                notificationListener = null;
                listenerPort = -1;
                log.info("Notification mode stopped for {}", config.getName());
                return true;
            } finally {
                lock.unlock();
            }
        }

        public NotificationListener getNotificationListener() {
            return notificationListener;
        }

        public int getListenerPort() {
            return listenerPort;
        }

        public boolean isNotificationModeActive() {
            return notificationListener != null;
        }

        public synchronized void disconnect() {
            if (readerModule != null && readerModule.isConnected()) {
                readerModule.disconnect();
                lastConnectionStatus = "disconnected";
            }
        }

        public synchronized void close() {
            stopNotificationMode();
            
            if (readerModule != null) {
                if (readerModule.isConnected()) {
                    log.info("Disconnecting from reader {}", config.getName());
                    readerModule.disconnect();
                }
                log.info("Closing reader {}", config.getName());
                readerModule.close();
                readerModule = null;
                lastConnectionStatus = "disconnected";
            }
        }

        public ReaderConfig getConfig() {
            return config;
        }

        public Lock getLock() {
            return lock;
        }
    }

    /**
     * Registers a reader and, if it can be reached, synchronises its configuration.
     *
     * <p>An unreachable reader is still registered, recorded as
     * {@link SyncState#UNREACHABLE} and synchronised on the next connection, so one
     * dark reader does not stop the service.
     */
    public void registerReader(ReaderConfig config) {
        ManagedReader managed = new ManagedReader(config);
        readers.put(config.getName(), managed);

        if (config.isHfProtocol()) {
            log.info("HF reader {} registered without configured antennas", config.getName());
        }

        try {
            managed.syncConfiguration(false);
        } catch (Exception e) {
            log.warn("Reader {} could not be synchronised at startup ({}); "
                + "it stays registered and is retried on the next connection",
                config.getName(), e.getMessage());
        } finally {
            // Host mode readers reconnect lazily on first use; notification readers
            // reconnect when their listener starts.
            managed.disconnect();
        }
    }

    /**
     * Resynchronises a registered reader on demand.
     *
     * @param force rewrite every parameter, whether or not it differs
     * @throws IllegalArgumentException if no reader of that name is registered
     */
    public SyncReport syncReader(String name, boolean force) throws Exception {
        ManagedReader managed = readers.get(name);
        if (managed == null) {
            throw new IllegalArgumentException("No reader registered with name " + name);
        }
        return managed.syncConfiguration(force);
    }

    public ManagedReader getReader(String name) {
        return readers.get(name);
    }

    public Map<String, ManagedReader> getAllReaders() {
        return readers;
    }

    public void closeAll() {
        for (ManagedReader reader : readers.values()) {
            try {
                reader.close();
            } catch (Exception e) {
                log.error("Error closing reader: {}", e.getMessage());
            }
        }
        readers.clear();
    }
}
