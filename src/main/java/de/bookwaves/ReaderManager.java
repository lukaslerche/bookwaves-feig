package de.bookwaves;

import de.feig.fedm.Connector;
import de.feig.fedm.ErrorCode;
import de.feig.fedm.ListenerParam;
import de.feig.fedm.ReaderModule;
import de.feig.fedm.RequestMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manager class to handle multiple RFID readers.
 */
public class ReaderManager {
    private static final Logger log = LoggerFactory.getLogger(ReaderManager.class);
    private final Map<String, ManagedReader> readers = new HashMap<>();
    private int nextListenerPort = 20001;
    
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

    public static class ManagedReader {
        private final ReaderConfig config;
        private ReaderModule readerModule;
        private NotificationListener notificationListener;
        private int listenerPort = -1;
        private final Lock lock = new ReentrantLock(true);

        public ManagedReader(ReaderConfig config) {
            this.config = config;
        }

        public synchronized ReaderModule getModule() throws Exception {
            if (readerModule == null) {
                readerModule = new ReaderModule(RequestMode.UniDirectional);
            }

            if (!readerModule.isConnected()) {
                // Try to reconnect first, fall back to fresh connection if that fails
                int returnCode = readerModule.reconnect();
                
                if (returnCode != ErrorCode.Ok) {
                    // Reconnect failed, try fresh connection
                    Connector connector = Connector.createTcpConnector(config.getAddress(), config.getPort());
                    connector.setTcpConnectTimeout(5000);
                    returnCode = readerModule.connect(connector);
                }

                if (returnCode != ErrorCode.Ok) {
                    throw new Exception("Failed to connect to reader " + config.getName() + ": " + 
                                      readerModule.lastErrorStatusText() + " (code: " + returnCode + ")");
                }
                log.info("Connected to reader {}", config.getName());
            }

            return readerModule;
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
            log.info("Creating new ReaderModule instance for {}", config.getName());
            readerModule = new ReaderModule(RequestMode.UniDirectional);
            
            // Establish fresh connection
            log.info("Attempting fresh connection to {}", config.getName());
            Connector connector = Connector.createTcpConnector(config.getAddress(), config.getPort());
            connector.setTcpConnectTimeout(5000);
            int returnCode = readerModule.connect(connector);
            
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
         * Execute a reader operation with automatic reconnection on connection errors.
         * This method handles transient network failures by retrying the operation
         * after reconnecting to the reader.
         * 
         * @param operation The operation to execute
         * @param <T> The return type of the operation
         * @return The result of the operation
         * @throws ReaderOperationException if the operation fails after all retries
         */
        public <T> T executeWithReconnect(ReaderOperation<T> operation) throws ReaderOperationException {
            Exception lastException = null;
            
            for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
                try {
                    ReaderModule reader = getModule();
                    return operation.execute(reader);
                } catch (Exception e) {
                    lastException = e;
                    String errorMsg = e.getMessage();
                    
                    // Check if it's a connection error
                    if (isConnectionError(errorMsg)) {
                        log.error("Connection error on attempt {}/{} for {}: {}", 
                            attempt, MAX_RECONNECT_ATTEMPTS, config.getName(), errorMsg);
                        
                        if (attempt < MAX_RECONNECT_ATTEMPTS) {
                            try {
                                Thread.sleep(RECONNECT_DELAY_MS * attempt); // Exponential backoff
                                forceReconnect();
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new ReaderOperationException("Operation interrupted during reconnection");
                            } catch (Exception reconnectError) {
                                log.error("Reconnection failed: {}", reconnectError.getMessage());
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
                return false; // Already running
            }

            try {
                lock.lock();
                
                if (readerModule == null) {
                    getModule(); // Ensure reader is initialized and connected
                }

                notificationListener = new NotificationListener(readerModule, 1000, lock);
                
                int state = readerModule.async().startNotification(notificationListener);
                if (state != ErrorCode.Ok) {
                    log.error("Failed to start notification: {}", readerModule.lastErrorStatusText());
                    notificationListener = null;
                    return false;
                }

                boolean keepAlive = true;
                state = readerModule.startListenerThread(
                    ListenerParam.createTcpListenerParam(port, "0.0.0.0", keepAlive),
                    notificationListener
                );
                
                if (state != ErrorCode.Ok) {
                    log.error("Failed to start listener thread: {}", readerModule.lastErrorStatusText());
                    readerModule.async().stopNotification();
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
            }
        }

        public ReaderConfig getConfig() {
            return config;
        }

        public Lock getLock() {
            return lock;
        }
    }

    public void registerReader(ReaderConfig config) {
        readers.put(config.getName(), new ManagedReader(config));
    }

    public ManagedReader getReader(String name) {
        return readers.get(name);
    }

    public Map<String, ManagedReader> getAllReaders() {
        return readers;
    }

    public synchronized int allocateListenerPort() {
        return nextListenerPort++;
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
