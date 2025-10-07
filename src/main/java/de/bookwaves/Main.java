package de.bookwaves;

import de.bookwaves.tag.DE290FTag;
import de.bookwaves.tag.DE290Tag;
import de.bookwaves.tag.DE386Tag;
import de.bookwaves.tag.DE6Tag;
import de.bookwaves.tag.RawTag;
import de.bookwaves.tag.Tag;
import de.bookwaves.tag.TagFactory;
import de.feig.fedm.ErrorCode;
import de.feig.fedm.InventoryParam;
import de.feig.fedm.ReaderModule;
import de.feig.fedm.TagItem;
import de.feig.fedm.taghandler.ThBase;
import de.feig.fedm.taghandler.ThEpcClass1Gen2;
import de.feig.fedm.taghandler.ThEpcClass1Gen2.Bank;
import de.feig.fedm.taghandler.ThEpcClass1Gen2.LockParam;
import de.feig.fedm.types.DataBuffer;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static ReaderManager readerManager;
    
    // Retry configuration for RF operations
    private static final int MAX_RETRIES = 10;
    private static final int RETRY_DELAY_MS = 100;

    public static void main(String[] args) {
        log.info("Starting RFID Reader Service");
        log.info("java.library.path = {}", System.getProperty("java.library.path"));
        if (!testNativeLibLoading()) {
            System.err.println("Exiting due to failure in loading native library.");
            System.exit(1);
        }

        // Initialize reader manager
        readerManager = new ReaderManager();
        
        try {
            List<ReaderConfig> readers = ConfigLoader.loadReaders("config.yaml");
            
            // Load global password configuration and set it in TagFactory
            Map<String, String> tagPasswords = ConfigLoader.getTagPasswords();
            TagFactory.setPasswordConfiguration(tagPasswords);
            log.info("Loaded tag password configuration with {} entries", tagPasswords.size());
            
            for (ReaderConfig config : readers) {
                readerManager.registerReader(config);
                log.info("Registered reader: {} (mode: {}, antennas: {})", 
                    config.getName(), config.getMode(), config.getAntennas());
            }
        } catch (Exception e) {
            log.error("Failed to load reader configuration", e);
            System.exit(1);
        }

        var app = Javalin.create()
                .get("/", ctx -> ctx.result("Hello Feig!"))
                .start(7070);


        // Register shutdown hook and event to close all readers
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            app.stop();
        }));
        
        app.events(event -> {
            event.serverStopping(() -> {
                log.info("Shutting down - closing all readers...");
                readerManager.closeAll();
            });
        });

        app.post("/notification/start/{readerName}", ctx -> {
            String readerName = ctx.pathParam("readerName");
            ReaderManager.ManagedReader managedReader = readerManager.getReader(readerName);
            
            if (managedReader == null) {
                ctx.status(404).json(Map.of(
                    "success", false,
                    "error", "Reader not found: " + readerName
                ));
                return;
            }

            if (managedReader.isNotificationModeActive()) {
                ctx.status(400).json(Map.of(
                    "success", false,
                    "error", "Notification mode already running for this reader"
                ));
                return;
            }

            try {
                int port = readerManager.allocateListenerPort();
                boolean started = managedReader.startNotificationMode(port);
                
                if (!started) {
                    ctx.status(500).json(Map.of(
                        "success", false,
                        "error", "Failed to start notification mode"
                    ));
                    return;
                }

                ctx.json(Map.of(
                    "success", true,
                    "message", "Notification mode started",
                    "port", port,
                    "readerName", readerName
                ));
            } catch (Exception e) {
                log.error("Failed to start notification mode for {}", readerName, e);
                ctx.status(500).json(Map.of(
                    "success", false,
                    "error", e.getMessage()
                ));
            }
        });

        app.post("/notification/stop/{readerName}", ctx -> {
            String readerName = ctx.pathParam("readerName");
            ReaderManager.ManagedReader managedReader = readerManager.getReader(readerName);
            
            if (managedReader == null) {
                ctx.status(404).json(Map.of(
                    "success", false,
                    "error", "Reader not found: " + readerName
                ));
                return;
            }

            if (!managedReader.isNotificationModeActive()) {
                ctx.status(404).json(Map.of(
                    "success", false,
                    "error", "No active notification session for reader: " + readerName
                ));
                return;
            }

            try {
                boolean stopped = managedReader.stopNotificationMode();
                
                if (!stopped) {
                    ctx.status(500).json(Map.of(
                        "success", false,
                        "error", "Failed to stop notification mode"
                    ));
                    return;
                }

                ctx.json(Map.of(
                    "success", true,
                    "message", "Notification mode stopped"
                ));
            } catch (Exception e) {
                log.error("Failed to stop notification mode for {}", readerName, e);
                ctx.status(500).json(Map.of(
                    "success", false,
                    "error", e.getMessage()
                ));
            }
        });

        app.get("/notification/events/{readerName}", ctx -> {
            String readerName = ctx.pathParam("readerName");
            ReaderManager.ManagedReader managedReader = readerManager.getReader(readerName);
            
            if (managedReader == null || !managedReader.isNotificationModeActive()) {
                ctx.status(404).json(Map.of(
                    "success", false,
                    "error", "No active notification session for reader: " + readerName
                ));
                return;
            }

            NotificationListener listener = managedReader.getNotificationListener();
            List<NotificationListener.NotificationEvent> events = listener.pollEvents();

            ctx.json(Map.of(
                "success", true,
                "readerName", readerName,
                "eventCount", events.size(),
                "isConnected", listener.isConnected(),
                "events", events
            ));
        });

        app.get("/notification/status", ctx -> {
            List<Map<String, Object>> sessions = new ArrayList<>();
            
            for (Map.Entry<String, ReaderManager.ManagedReader> entry : readerManager.getAllReaders().entrySet()) {
                ReaderManager.ManagedReader reader = entry.getValue();
                if (reader.isNotificationModeActive()) {
                    NotificationListener listener = reader.getNotificationListener();
                    sessions.add(Map.of(
                        "readerName", entry.getKey(),
                        "port", reader.getListenerPort(),
                        "isConnected", listener.isConnected(),
                        "queuedEvents", listener.getEventCount()
                    ));
                }
            }

            ctx.json(Map.of(
                "success", true,
                "activeSessions", sessions.size(),
                "sessions", sessions
            ));
        });
        

        

        app.get("/readers", ctx -> {
            List<Map<String, Object>> readerList = new ArrayList<>();
            
            for (Map.Entry<String, ReaderManager.ManagedReader> entry : readerManager.getAllReaders().entrySet()) {
                ReaderManager.ManagedReader managedReader = entry.getValue();
                ReaderConfig config = managedReader.getConfig();
                
                // Check connection status
                boolean isConnected = false;
                String connectionStatus = "disconnected";
                try {
                    ReaderModule module = managedReader.getModule();
                    isConnected = module.isConnected();
                    connectionStatus = isConnected ? "connected" : "disconnected";
                } catch (Exception e) {
                    connectionStatus = "error: " + e.getMessage();
                }
                
                Map<String, Object> readerInfo = new java.util.LinkedHashMap<>();
                readerInfo.put("name", config.getName());
                readerInfo.put("address", config.getAddress());
                readerInfo.put("port", config.getPort());
                readerInfo.put("mode", config.getMode());
                readerInfo.put("antennas", config.getAntennas());
                readerInfo.put("antennaMask", String.format("0x%02X", config.getAntennaMask()));
                readerInfo.put("isConnected", isConnected);
                readerInfo.put("connectionStatus", connectionStatus);
                readerInfo.put("notificationActive", managedReader.isNotificationModeActive());
                if (managedReader.isNotificationModeActive()) {
                    readerInfo.put("notificationPort", managedReader.getListenerPort());
                }
                
                readerList.add(readerInfo);
            }
            
            ctx.json(Map.of(
                "success", true,
                "readerCount", readerList.size(),
                "readers", readerList
            ));
        });

        app.get("/testread/{readerName}", ctx -> {
            String readerName = ctx.pathParam("readerName");
            ReaderManager.ManagedReader managedReader = readerManager.getReader(readerName);
            if (managedReader == null) {
                ctx.status(404).result("Reader not found");
                return;
            }

            try {
                ReaderModule reader = managedReader.getModule();
                
                InventoryParam inventoryParam = new InventoryParam();
                inventoryParam.setAntennas(managedReader.getConfig().getAntennaMask());

                // do this code 3 times
                //for (int d = 0; d < 3; d++) {
                    //System.out.println(reader.isConnected() ? ">>> Reader is connected." : "Reader is not connected.");

                    int returnCode = reader.hm().inventory(true, inventoryParam);
                    System.out.println("Reader last error state: "+reader.lastErrorStatusText());
                    if (returnCode != ErrorCode.Ok) {
                        ctx.status(500).result("Inventory command failed: " + returnCode);
                        return;
                    }

                    System.out.println("Inventory successful: " + reader.hm().itemCount() + " items found.");
                    for (int i = 0; i < reader.hm().itemCount(); i++) {
                        TagItem tagItem =  reader.hm().tagItem(i);
                        if (!tagItem.isValid()) {
                            System.out.println("Invalid tag item at index " + i);
                            continue;
                        }
                        System.out.println("Tag " + (i + 1) + ": " + tagItem.iddToHexString());
                        //RssiItem
                        tagItem.rssiValues().forEach(rssiItem -> {
                            System.out.println(" with Antenna "+rssiItem.antennaNumber() + ": " + rssiItem.rssi() + " dBm");

                        });
                        
                        ThBase tagHandler = reader.hm().createTagHandler(i);
                        
                        
                        if (tagHandler == null) {
                            System.out.println("Failed to create tag handler for tag at index " + i);
                            continue;
                        } else if (tagHandler.tagHandlerType() == ThBase.TypeEpcClass1Gen2 ){
                            System.out.println("Tag handler type: EPC Class 1 Gen 2");
                            ThEpcClass1Gen2 epcTag = (ThEpcClass1Gen2) tagHandler;
                            if(epcTag.isEpcAndTid()) {
                                System.out.println("EPC: " + epcTag.epcToHexString());
                                System.out.println("TID: " + epcTag.tidToHexString());
                            } else {
                                System.out.println("EPC: " + epcTag.epcToHexString());

                            }
                            Thread.sleep(200); // Small delay to ensure tag is ready for next command
                            // Read blocks
                            int blocksToRead = 10; // Number of blocks to read
                            int startBlock = 0; // Starting block address
                            Bank bank = Bank.Epc; // Memory bank to read from
                            DataBuffer data = new DataBuffer(); // Each block is 2 bytes
                            returnCode = epcTag.readMultipleBlocks(bank, startBlock, blocksToRead, data);
                            System.out.println("Read multiple blocks return code: " + returnCode);
                            System.out.println("Last error state: " + reader.lastErrorStatusText());
                            System.out.println("Last tag ISO error: " + tagHandler.lastIsoError());
                            System.out.println(data.toHexString(" "));


                            Thread.sleep(200); // Small delay to ensure tag is ready for next command
                            // example byte array
                            byte[] newEpc = new byte[] { (byte)0x11, (byte)0x22, (byte)0x33, (byte)0x44, (byte)0x55, (byte)0x66 };
                            DataBuffer dataToWrite = new DataBuffer(newEpc);
                            returnCode = epcTag.writeMultipleBlocks(bank, 2, 3, dataToWrite);

                            System.out.println("Write multiple blocks return code: " + returnCode);
                            System.out.println("Last error state: " + reader.lastErrorStatusText());
                            System.out.println("Last tag ISO error: " + tagHandler.lastIsoError());
                            System.out.println(dataToWrite.toHexString(" "));
                            

                        } else {
                            System.out.println("Tag handler type: " + tagHandler.tagHandlerType());
                        }

                        System.out.println("Transponder name:  "+tagHandler.transponderName());

                    }

                // Thread.sleep(500);
                //}

                ctx.json(new String[]{"done"});
            } catch (Exception e) {
                ctx.status(500).result("Error: " + e.getMessage());
                //e.printStackTrace();
            }
        });


        

        app.get("/inventory/{readerName}", ctx -> {
            String readerName = ctx.pathParam("readerName");
            ReaderManager.ManagedReader managedReader = readerManager.getReader(readerName);
            if (managedReader == null) {
                ctx.status(404).json(new InventoryResponse(false, "Reader not found", 0, null));
                return;
            }

            try {
                // Use executeWithReconnect to handle connection failures automatically
                List<Tag> tags = managedReader.executeWithReconnect(reader -> {
                    InventoryParam inventoryParam = new InventoryParam();
                    inventoryParam.setAntennas(managedReader.getConfig().getAntennaMask());

                    int returnCode = reader.hm().inventory(true, inventoryParam);
                    
                    // "No transponder in reader field" is a normal condition, not an error
                    if (returnCode != ErrorCode.Ok) {
                        String errorMsg = reader.lastErrorStatusText();
                        
                        // Check if this is just "no tags found" - this is normal, not an error
                        if (errorMsg != null && errorMsg.toLowerCase().contains("no transponder")) {
                            log.debug("No tags found in reader field for {}", readerName);
                            return new ArrayList<>(); // Return empty list, not an error
                        }
                        
                        // For other errors, throw exception
                        throw new Exception("Inventory failed: " + errorMsg);
                    }

                    long tagCount = reader.hm().itemCount();
                    List<Tag> result = new ArrayList<>();

                    for (int i = 0; i < tagCount; i++) {
                        TagItem tagItem = reader.hm().tagItem(i);
                        if (!tagItem.isValid()) {
                            continue;
                        }

                        String idd = tagItem.iddToHexString();
                        
                        // Create Tag instance (uses global password configuration)
                        Tag tag = TagFactory.createTagFromHexString(idd);
                        
                        // Extract and set RSSI values
                        List<Tag.AntennaRssi> rssiList = new ArrayList<>();
                        tagItem.rssiValues().forEach(rssiItem -> {
                            rssiList.add(new Tag.AntennaRssi(
                                rssiItem.antennaNumber(), 
                                rssiItem.rssi()
                            ));
                        });
                        tag.setRssiValues(rssiList);
                        
                        // Log tag info for debugging
                        log.debug("Tag: {}, Type: {}, MediaId: {}, Secured: {}", 
                            tag.getEpcHexString(), tag.getTagType(), tag.getMediaId(), tag.isSecured());

                        result.add(tag);
                    }

                    return result;
                });

                ctx.json(new InventoryResponse(true, "Inventory successful", tags.size(), tags));
            } catch (ReaderManager.ReaderOperationException e) {
                log.error("Inventory failed for {}", readerName, e);
                ctx.status(500).json(new InventoryResponse(false, e.getMessage(), 0, null));
            }
        });

        app.post("/secure/{readerName}", ctx -> {
            handleSecurityOperationWithReconnect(ctx, readerManager, true);
        });

        app.post("/unsecure/{readerName}", ctx -> {
            handleSecurityOperationWithReconnect(ctx, readerManager, false);
        });

        app.post("/initialize/{readerName}", ctx -> {
            String readerName = ctx.pathParam("readerName");
            String mediaId = ctx.queryParam("mediaId");
            String format = ctx.queryParam("format"); // "DE290", "CD290", "DE6", "DE290F", "DE386" (optional)
            String securedStr = ctx.queryParam("secured"); // "true" or "false" (optional)
            
            if (mediaId == null || mediaId.isEmpty()) {
                ctx.status(400).json(Map.of(
                    "success", false,
                    "error", "Missing 'mediaId' query parameter"
                ));
                return;
            }

            // Use configured default if format not specified
            boolean secured = securedStr == null || securedStr.equalsIgnoreCase("true");
            
            if (format == null || format.isEmpty()) {
                // Use configured default from YAML
                format = ConfigLoader.getDefaultTagFormat();
            }

            ReaderManager.ManagedReader managedReader = readerManager.getReader(readerName);
            if (managedReader == null) {
                ctx.status(404).json(Map.of(
                    "success", false,
                    "error", "Reader not found: " + readerName
                ));
                return;
            }

            try {
                // Create tag instance for format validation
                Tag newTag = createTagForInitialization(format, mediaId, secured);
                
                // Validate media ID format for this tag type
                newTag.validateMediaIdFormat(mediaId);
                
                managedReader.executeWithReconnect(reader -> {
                    initializeTag(reader, managedReader.getConfig(), newTag);
                });

                ctx.json(Map.of(
                    "success", true,
                    "message", "Tag initialized successfully",
                    "epc", newTag.getEpcHexString(),
                    "pc", newTag.getPCHexString(),
                    "mediaId", mediaId,
                    "secured", secured,
                    "format", format,
                    "tagType", newTag.getTagType()
                ));

            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of(
                    "success", false,
                    "error", "Invalid media ID format - " + e.getMessage()
                ));
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of(
                    "success", false,
                    "error", e.getMessage()
                ));
            } catch (ReaderManager.ReaderOperationException e) {
                log.error("Failed to initialize tag for {}", readerName, e);
                ctx.status(500).json(Map.of(
                    "success", false,
                    "error", e.getMessage()
                ));
            }
        });

        app.post("/edit/{readerName}", ctx -> {
            String readerName = ctx.pathParam("readerName");
            String epcHex = ctx.queryParam("epc");
            String newMediaId = ctx.queryParam("mediaId");
            
            if (epcHex == null || epcHex.isEmpty()) {
                ctx.status(400).json(Map.of(
                    "success", false,
                    "error", "Missing 'epc' query parameter"
                ));
                return;
            }

            if (newMediaId == null || newMediaId.isEmpty()) {
                ctx.status(400).json(Map.of(
                    "success", false,
                    "error", "Missing 'mediaId' query parameter"
                ));
                return;
            }

            ReaderManager.ManagedReader managedReader = readerManager.getReader(readerName);
            if (managedReader == null) {
                ctx.status(404).json(Map.of(
                    "success", false,
                    "error", "Reader not found: " + readerName
                ));
                return;
            }

            try {
                Tag oldTag = TagFactory.createTagFromHexString(epcHex);
                
                if (oldTag instanceof RawTag) {
                    ctx.status(400).json(Map.of(
                        "success", false,
                        "error", "Tag format not recognized - use /initialize for unformatted tags"
                    ));
                    return;
                }

                Tag newTag = TagFactory.createTagFromHexString(epcHex);
                
                // Validate media ID format for this tag type BEFORE attempting to set it
                newTag.validateMediaIdFormat(newMediaId);
                newTag.setMediaId(newMediaId);
                
                managedReader.executeWithReconnect(reader -> {
                    editTag(reader, managedReader.getConfig(), epcHex, oldTag, newTag);
                });

                ctx.json(Map.of(
                    "success", true,
                    "message", "Tag updated successfully",
                    "oldEpc", epcHex,
                    "newEpc", newTag.getEpcHexString(),
                    "mediaId", newMediaId,
                    "tagType", newTag.getTagType()
                ));

            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of(
                    "success", false,
                    "error", "Invalid media ID for this tag format: " + e.getMessage()
                ));
            } catch (ReaderManager.ReaderOperationException e) {
                ctx.status(500).json(Map.of(
                    "success", false,
                    "error", e.getMessage()
                ));
            }
        });

        app.post("/clear/{readerName}", ctx -> {
            String readerName = ctx.pathParam("readerName");
            String epcHex = ctx.queryParam("epc");
            
            if (epcHex == null || epcHex.isEmpty()) {
                ctx.status(400).json(Map.of(
                    "success", false,
                    "error", "Missing 'epc' query parameter"
                ));
                return;
            }

            ReaderManager.ManagedReader managedReader = readerManager.getReader(readerName);
            if (managedReader == null) {
                ctx.status(404).json(Map.of(
                    "success", false,
                    "error", "Reader not found: " + readerName
                ));
                return;
            }

            try {
                Tag oldTag = TagFactory.createTagFromHexString(epcHex);
                
                Map<String, String> result = managedReader.executeWithReconnect(reader -> {
                    return clearTag(reader, managedReader.getConfig(), epcHex, oldTag);
                });

                ctx.json(Map.of(
                    "success", true,
                    "message", "Tag cleared successfully - passwords zeroed and EPC restored to TID",
                    "oldEpc", epcHex,
                    "newEpc", result.get("newEpc"),
                    "newPc", result.get("newPc"),
                    "tid", result.get("tid")
                ));

            } catch (ReaderManager.ReaderOperationException e) {
                ctx.status(500).json(Map.of(
                    "success", false,
                    "error", e.getMessage()
                ));
                //e.printStackTrace();
            }
        });

        app.get("/analyze/{readerName}", ctx -> {
            String readerName = ctx.pathParam("readerName");
            String epcHex = ctx.queryParam("epc");
            
            if (epcHex == null || epcHex.isEmpty()) {
                ctx.status(400).json(Map.of(
                    "success", false,
                    "error", "Missing 'epc' query parameter"
                ));
                return;
            }

            ReaderManager.ManagedReader managedReader = readerManager.getReader(readerName);
            if (managedReader == null) {
                ctx.status(404).json(Map.of(
                    "success", false,
                    "error", "Reader not found: " + readerName
                ));
                return;
            }

            try {
                Tag theoreticalTag = TagFactory.createTagFromHexString(epcHex);
                
                Map<String, Object> analysis = managedReader.executeWithReconnect(reader -> {
                    return analyzeTag(reader, managedReader.getConfig(), epcHex, theoreticalTag);
                });

                ctx.json(Map.of(
                    "success", true,
                    "epc", epcHex,
                    "analysis", analysis
                ));

            } catch (ReaderManager.ReaderOperationException e) {
                ctx.status(500).json(Map.of(
                    "success", false,
                    "error", e.getMessage()
                ));
                //e.printStackTrace();
            }
        });

        app.get("/test", ctx -> {
            ctx.result("Test successful");
        });
    }

    /**
     * Common handler for secure/unsecure operations with reconnection support.
     */
    private static void handleSecurityOperationWithReconnect(io.javalin.http.Context ctx, ReaderManager readerManager, boolean secure) {
        String readerName = ctx.pathParam("readerName");
        String epcHex = ctx.queryParam("epc");
        
        if (epcHex == null || epcHex.isEmpty()) {
            ctx.status(400).json(Map.of(
                "success", false,
                "error", "Missing 'epc' query parameter"
            ));
            return;
        }

        ReaderManager.ManagedReader managedReader = readerManager.getReader(readerName);
        if (managedReader == null) {
            ctx.status(404).json(Map.of(
                "success", false,
                "error", "Reader not found: " + readerName
            ));
            return;
        }

        try {
            Tag tag = TagFactory.createTagFromHexString(epcHex);
            
            if (tag instanceof RawTag) {
                ctx.status(400).json(Map.of(
                    "success", false,
                    "error", "Tag format not recognized - cannot modify security on raw tags"
                ));
                return;
            }

            tag.setSecured(secure);
            
            managedReader.executeWithReconnect(reader -> {
                return modifySecurityBit(reader, managedReader.getConfig(), epcHex, tag);
            });

            ctx.json(Map.of(
                "success", true,
                "message", "Tag " + (secure ? "secured" : "unsecured") + " successfully",
                "epc", epcHex,
                "tagType", tag.getTagType(),
                "secured", secure
            ));

        } catch (ReaderManager.ReaderOperationException e) {
            log.error("Failed to modify security for {}", readerName, e);
            ctx.status(500).json(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Initialize a tag with new EPC and passwords.
     */
    private static void initializeTag(ReaderModule reader, ReaderConfig config, Tag newTag) throws Exception {
        // Perform inventory to find a blank tag
        InventoryParam inventoryParam = new InventoryParam();
        inventoryParam.setAntennas(config.getAntennaMask());
        int returnCode = reader.hm().inventory(true, inventoryParam);
        
        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Inventory failed: " + reader.lastErrorStatusText());
        }
        
        if (reader.hm().itemCount() == 0) {
            throw new Exception("No tag found in field");
        }

        if (reader.hm().itemCount() > 1) {
            throw new Exception("Multiple tags found - please ensure only one tag is in the field");
        }

        // Get the tag handler
        ThBase handler = reader.hm().createTagHandler(0);
        if (!(handler instanceof ThEpcClass1Gen2)) {
            throw new Exception("Tag is not EPC Gen2 compatible");
        }

        ThEpcClass1Gen2 epcTag = (ThEpcClass1Gen2) handler;

        // Step 1: Write kill + access passwords together to Reserved bank (with retry)
        // Kill password at word 0-1 (4 bytes), access password at word 2-3 (4 bytes)
        // Total: 8 bytes = 4 blocks, starting at word 0
        byte[] bothPasswords = new byte[8];
        System.arraycopy(newTag.getKillPassword(), 0, bothPasswords, 0, 4);
        System.arraycopy(newTag.getAccessPassword(), 0, bothPasswords, 4, 4);
        
        DataBuffer passwordsData = new DataBuffer(bothPasswords);
        returnCode = writeWithRetry(
            epcTag,
            ThEpcClass1Gen2.Bank.Reserved,
            0, // Start at word address 0 (kill password location)
            4, // 8 bytes = 4 words/blocks
            passwordsData
        );

        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Failed to write passwords: " + reader.lastErrorStatusText() + 
                              " (ISO error: " + epcTag.lastIsoError() + ")");
        }

        // Step 2: Write PC + EPC together (PC is 1 word = 1 block, EPC follows immediately)
        // Combine PC and EPC into single buffer
        byte[] pcAndEpc = new byte[newTag.getPc().length + newTag.getEpc().length];
        System.arraycopy(newTag.getPc(), 0, pcAndEpc, 0, newTag.getPc().length);
        System.arraycopy(newTag.getEpc(), 0, pcAndEpc, newTag.getPc().length, newTag.getEpc().length);
        
        DataBuffer pcEpcData = new DataBuffer(pcAndEpc);
        int totalBlocks = pcAndEpc.length / 2; // PC (1 block) + EPC (8 blocks for DE290) = 9 blocks
        
        returnCode = writeWithRetry(
            epcTag,
            ThEpcClass1Gen2.Bank.Epc,
            1, // Start at word address 1 (PC is at word 1, EPC starts at word 2)
            totalBlocks,
            pcEpcData
        );

        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Failed to write PC+EPC: " + reader.lastErrorStatusText() +
                              " (ISO error: " + epcTag.lastIsoError() + ")");
        }

        // CRITICAL: After writing EPC, must re-inventory to get fresh tag handler
        // The old epcTag instance references the old EPC and won't work for locking
        try {
            Thread.sleep(50); // Brief delay for tag to stabilize
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        returnCode = reader.hm().inventory(true, inventoryParam);
        if (returnCode != ErrorCode.Ok || reader.hm().itemCount() == 0) {
            throw new Exception("Failed to re-select tag after EPC write: " + reader.lastErrorStatusText());
        }

        // Verify the tag has the correct NEW EPC
        String expectedEpcHex = newTag.getEpcHexString();
        ThEpcClass1Gen2 freshEpcTag = null;
        for (int i = 0; i < reader.hm().itemCount(); i++) {
            TagItem tagItem = reader.hm().tagItem(i);
            if (tagItem.iddToHexString().equalsIgnoreCase(expectedEpcHex)) {
                ThBase freshHandler = reader.hm().createTagHandler(i);
                if (freshHandler instanceof ThEpcClass1Gen2) {
                    freshEpcTag = (ThEpcClass1Gen2) freshHandler;
                    break;
                }
            }
        }

        if (freshEpcTag == null) {
            throw new Exception("Tag EPC verification failed - expected " + expectedEpcHex + " but not found in field");
        }

        epcTag = freshEpcTag; // Use fresh handler for locking

        // Step 3: Lock memory banks (now using fresh tag handler)
        // Lock kill password, access password, and EPC memory
        // Parameters: kill, access, epc, tid, user
        DataBuffer accessPwdData = new DataBuffer(newTag.getAccessPassword());
        returnCode = lockWithRetry(
            epcTag,
            LockParam.Lock,      // Lock kill password
            LockParam.Lock,      // Lock access password  
            LockParam.Lock,      // Lock EPC memory
            LockParam.Unchanged, // Leave TID unchanged
            LockParam.Unchanged, // Leave User memory unchanged
            accessPwdData        // Use access password for locking
        );

        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Failed to lock memory banks: " + reader.lastErrorStatusText() +
                              " (ISO error: " + epcTag.lastIsoError() + ")");
        }
    }

    /**
     * Edit an existing tag with new media ID.
     */
    private static void editTag(ReaderModule reader, ReaderConfig config, String epcHex, Tag oldTag, Tag newTag) throws Exception {
        // Parse existing tag (with old media ID to get correct old passwords)
        int oldEpcLength = oldTag.getEpc().length;
        // Create new tag object with new media ID to calculate new passwords
        int newEpcLength = newTag.getEpc().length;
        
        // Perform inventory
        InventoryParam inventoryParam = new InventoryParam();
        inventoryParam.setAntennas(config.getAntennaMask());
        int returnCode = reader.hm().inventory(true, inventoryParam);
        
        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Inventory failed: " + reader.lastErrorStatusText());
        }
        
        if (reader.hm().itemCount() == 0) {
            throw new Exception("No tags found in field");
        }

        // Find matching tag
        ThEpcClass1Gen2 epcTag = findTagByEpc(reader, epcHex);
        if (epcTag == null) {
            throw new Exception("Specified tag not found or not EPC Gen2");
        }

        // Use old access password for unlocking
        DataBuffer oldAccessPwd = new DataBuffer(oldTag.getAccessPassword());
        
        // Step 1: Unlock memory banks using old access password
        // Parameters: kill, access, epc, tid, user
        returnCode = lockWithRetry(
            epcTag,
            LockParam.Unlock,    // Unlock kill password
            LockParam.Unlock,    // Unlock access password
            LockParam.Unlock,    // Unlock EPC memory
            LockParam.Unchanged, // Leave TID unchanged
            LockParam.Unchanged, // Leave User memory unchanged
            oldAccessPwd         // Use OLD access password
        );

        if (returnCode != ErrorCode.Ok) {
            System.err.println("Warning: Failed to unlock memory banks: " + reader.lastErrorStatusText());
            // Continue anyway - tag might not be locked
        }

        // Step 2: Write new kill + access passwords together in one operation
        // Kill password at word 0-1 (4 bytes), access password at word 2-3 (4 bytes)
        byte[] bothNewPasswords = new byte[8];
        System.arraycopy(newTag.getKillPassword(), 0, bothNewPasswords, 0, 4);
        System.arraycopy(newTag.getAccessPassword(), 0, bothNewPasswords, 4, 4);
        
        DataBuffer newPasswordsData = new DataBuffer(bothNewPasswords);
        returnCode = writeWithRetry(
            epcTag,
            ThEpcClass1Gen2.Bank.Reserved,
            0, // Start at word 0 (kill password location)
            4, // 8 bytes = 4 words
            newPasswordsData
        );

        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Failed to write new passwords: " + reader.lastErrorStatusText() +
                              " (ISO error: " + epcTag.lastIsoError() + ")");
        }

        // Step 3: Write EPC (and PC if length changed)
        // Use NEW access password from this point forward
        DataBuffer newAccessPwd = new DataBuffer(newTag.getAccessPassword());
        
        if (oldEpcLength != newEpcLength) {
            // Length changed - must write PC+EPC together
            byte[] pcAndEpc = new byte[newTag.getPc().length + newTag.getEpc().length];
            System.arraycopy(newTag.getPc(), 0, pcAndEpc, 0, newTag.getPc().length);
            System.arraycopy(newTag.getEpc(), 0, pcAndEpc, newTag.getPc().length, newTag.getEpc().length);
            
            DataBuffer pcEpcData = new DataBuffer(pcAndEpc);
            returnCode = writeWithRetry(
                epcTag,
                ThEpcClass1Gen2.Bank.Epc,
                1, // Start at word 1 (PC+EPC)
                pcAndEpc.length / 2,
                pcEpcData
            );
        } else {
            // Length unchanged - write only EPC data
            DataBuffer epcData = new DataBuffer(newTag.getEpc());
            returnCode = writeWithRetry(
                epcTag,
                ThEpcClass1Gen2.Bank.Epc,
                2, // EPC starts at word 2 (after PC at word 1)
                newTag.getEpc().length / 2,
                epcData
            );
        }

        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Failed to write new EPC: " + reader.lastErrorStatusText() +
                              " (ISO error: " + epcTag.lastIsoError() + ")");
        }

        // CRITICAL: After writing EPC, must re-inventory to get fresh tag handler
        try {
            Thread.sleep(50); // Brief delay for tag to stabilize
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        returnCode = reader.hm().inventory(true, inventoryParam);
        if (returnCode != ErrorCode.Ok || reader.hm().itemCount() == 0) {
            throw new Exception("Failed to re-select tag after EPC write: " + reader.lastErrorStatusText());
        }

        // Find the tag with NEW EPC
        String newEpcHex = newTag.getEpcHexString();
        ThEpcClass1Gen2 freshEpcTag = findTagByEpc(reader, newEpcHex);
        if (freshEpcTag == null) {
            throw new Exception("Could not re-select tag with new EPC: " + newEpcHex);
        }

        epcTag = freshEpcTag; // Use fresh handler for locking

        // Step 4: Lock memory banks again using new access password (with fresh handler)
        returnCode = lockWithRetry(
            epcTag,
            LockParam.Lock,      // Lock kill password
            LockParam.Lock,      // Lock access password
            LockParam.Lock,      // Lock EPC memory
            LockParam.Unchanged, // Leave TID unchanged
            LockParam.Unchanged, // Leave User memory unchanged
            newAccessPwd         // Use NEW access password
        );

        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Failed to lock memory banks: " + reader.lastErrorStatusText() +
                              " (ISO error: " + epcTag.lastIsoError() + ")");
        }
    }

    /**
     * Clear a tag by zeroing passwords and restoring TID as EPC.
     */
    private static Map<String, String> clearTag(ReaderModule reader, ReaderConfig config, String epcHex, Tag oldTag) throws Exception {
        // Parse existing tag to get old passwords
        
        // Perform inventory to find the tag
        InventoryParam inventoryParam = new InventoryParam();
        inventoryParam.setAntennas(config.getAntennaMask());
        int returnCode = reader.hm().inventory(true, inventoryParam);
        
        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Inventory failed: " + reader.lastErrorStatusText());
        }
        
        if (reader.hm().itemCount() == 0) {
            throw new Exception("No tags found in field");
        }

        // Find matching tag
        ThEpcClass1Gen2 epcTag = findTagByEpc(reader, epcHex);
        if (epcTag == null) {
            throw new Exception("Specified tag not found or not EPC Gen2");
        }

        // Step 1: Read TID bank (96 bits = 12 bytes = 6 words)
        DataBuffer tidData = new DataBuffer();
        returnCode = epcTag.readMultipleBlocks(
            ThEpcClass1Gen2.Bank.Tid,
            0, // Start at word 0
            6, // Read 6 words (12 bytes = 96 bits)
            tidData
        );

        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Failed to read TID: " + reader.lastErrorStatusText() +
                              " (ISO error: " + epcTag.lastIsoError() + ")");
        }

        byte[] tidBytes = tidData.data();
        if (tidBytes.length != 12) {
            throw new Exception("TID read returned unexpected length: " + tidBytes.length + " bytes (expected 12)");
        }

        // Use old access password for unlocking (if tag has password)
        byte[] oldAccessPwd = oldTag.getAccessPassword();
        boolean hasPassword = false;
        for (byte b : oldAccessPwd) {
            if (b != 0) {
                hasPassword = true;
                break;
            }
        }

        DataBuffer oldAccessPwdBuf = hasPassword ? new DataBuffer(oldAccessPwd) : null;

        // Step 2: Unlock memory banks using old access password (if needed)
        if (hasPassword) {
            returnCode = lockWithRetry(
                epcTag,
                LockParam.Unlock,    // Unlock kill password
                LockParam.Unlock,    // Unlock access password
                LockParam.Unlock,    // Unlock EPC memory
                LockParam.Unchanged, // Leave TID unchanged
                LockParam.Unchanged, // Leave User memory unchanged
                oldAccessPwdBuf      // Use OLD access password
            );

            if (returnCode != ErrorCode.Ok) {
                System.err.println("Warning: Failed to unlock memory banks: " + reader.lastErrorStatusText());
                // Continue anyway - tag might not be locked
            }
        }

        // Step 3: Clear both passwords (write all zeros)
        byte[] zeroPasswords = new byte[8]; // All zeros
        DataBuffer zeroPasswordsData = new DataBuffer(zeroPasswords);
        
        // Write without authentication
        returnCode = writeWithRetry(
            epcTag,
            ThEpcClass1Gen2.Bank.Reserved,
            0,
            4,
            zeroPasswordsData
        );
        
        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Failed to clear passwords: " + reader.lastErrorStatusText() +
                              " (ISO error: " + epcTag.lastIsoError() + ")");
        }

        // Step 4: Write PC + TID-as-EPC together
        // PC for 96-bit EPC: 0x3000 (length=6 words, no special flags)
        byte[] newPc = new byte[] { (byte) 0x30, (byte) 0x00 };
        byte[] pcAndEpc = new byte[newPc.length + tidBytes.length]; // 2 + 12 = 14 bytes
        System.arraycopy(newPc, 0, pcAndEpc, 0, newPc.length);
        System.arraycopy(tidBytes, 0, pcAndEpc, newPc.length, tidBytes.length);
        
        DataBuffer pcEpcData = new DataBuffer(pcAndEpc);
        
        returnCode = writeWithRetry(
            epcTag,
            ThEpcClass1Gen2.Bank.Epc,
            1, // Start at word 1 (PC + EPC)
            pcAndEpc.length / 2, // 14 bytes = 7 words
            pcEpcData
        );

        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Failed to write PC+EPC: " + reader.lastErrorStatusText() +
                              " (ISO error: " + epcTag.lastIsoError() + ")");
        }

        // Convert TID to hex string for response
        String tidHex = bytesToHex(tidBytes);
        return Map.of(
            "newEpc", tidHex,
            "newPc", "3000",
            "tid", tidHex
        );
    }

    /**
     * Analyze a tag's memory banks and security status.
     */
    private static Map<String, Object> analyzeTag(ReaderModule reader, ReaderConfig config, String epcHex, Tag theoreticalTag) throws Exception {
        // Parse tag to get theoretical values
        
        // Perform inventory to find the tag
        InventoryParam inventoryParam = new InventoryParam();
        inventoryParam.setAntennas(config.getAntennaMask());
        int returnCode = reader.hm().inventory(true, inventoryParam);
        
        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Inventory failed: " + reader.lastErrorStatusText());
        }
        
        if (reader.hm().itemCount() == 0) {
            throw new Exception("No tags found in field");
        }

        // Find matching tag
        ThEpcClass1Gen2 epcTag = findTagByEpc(reader, epcHex);
        if (epcTag == null) {
            throw new Exception("Specified tag not found or not EPC Gen2");
        }

        // Prepare response structure
        Map<String, Object> analysis = new java.util.LinkedHashMap<>();
        analysis.put("tagType", theoreticalTag.getTagType());
        analysis.put("mediaId", theoreticalTag.getMediaId());
        
        // First, read just the PC to determine EPC length
        DataBuffer pcData = new DataBuffer();
        returnCode = epcTag.readMultipleBlocks(
            ThEpcClass1Gen2.Bank.Epc,
            1, // PC is at word 1
            1, // Read 1 word (2 bytes)
            pcData
        );
        
        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Failed to read PC: " + reader.lastErrorStatusText());
        }
        
        // Decode PC to get EPC length in words
        // PC bits 15-11 contain the length (in 16-bit words)
        byte[] pcBytes = pcData.data();
        int pcValue = ((pcBytes[0] & 0xFF) << 8) | (pcBytes[1] & 0xFF);
        int epcLengthInWords = (pcValue >> 11) & 0x1F; // Extract bits 15-11
        
        // Now read PC + EPC together based on actual length
        DataBuffer epcBankData = new DataBuffer();
        returnCode = epcTag.readMultipleBlocks(
            ThEpcClass1Gen2.Bank.Epc,
            1, // Start at word 1 (PC)
            1 + epcLengthInWords, // PC (1 word) + EPC (variable words)
            epcBankData
        );
        
        byte[] actualPcEpc = (returnCode == ErrorCode.Ok) ? epcBankData.data() : new byte[0];
        String actualPcEpcHex = bytesToHex(actualPcEpc);
        
        byte[] theoreticalPc = theoreticalTag.getPc();
        byte[] theoreticalEpc = theoreticalTag.getEpc();
        byte[] theoreticalPcEpc = new byte[theoreticalPc.length + theoreticalEpc.length];
        System.arraycopy(theoreticalPc, 0, theoreticalPcEpc, 0, theoreticalPc.length);
        System.arraycopy(theoreticalEpc, 0, theoreticalPcEpc, theoreticalPc.length, theoreticalEpc.length);
        String theoreticalPcEpcHex = bytesToHex(theoreticalPcEpc);
        
        boolean epcMatches = actualPcEpcHex.equals(theoreticalPcEpcHex);
        
        Map<String, Object> epcBank = new java.util.LinkedHashMap<>();
        epcBank.put("readSuccess", returnCode == ErrorCode.Ok);
        epcBank.put("pcValue", String.format("0x%04X", pcValue));
        epcBank.put("epcLengthInWords", epcLengthInWords);
        epcBank.put("epcLengthInBytes", epcLengthInWords * 2);
        epcBank.put("actual", actualPcEpcHex);
        epcBank.put("theoretical", theoreticalPcEpcHex);
        epcBank.put("matches", epcMatches);
        if (returnCode != ErrorCode.Ok) {
            epcBank.put("error", reader.lastErrorStatusText());
        }
        analysis.put("epcBank", epcBank);
        
        // Read TID bank (96 bits = 6 words)
        DataBuffer tidData = new DataBuffer();
        returnCode = epcTag.readMultipleBlocks(
            ThEpcClass1Gen2.Bank.Tid,
            0,
            6,
            tidData
        );
        
        Map<String, Object> tidBank = new java.util.LinkedHashMap<>();
        tidBank.put("readSuccess", returnCode == ErrorCode.Ok);
        if (returnCode == ErrorCode.Ok) {
            tidBank.put("data", bytesToHex(tidData.data()));
            tidBank.put("length", tidData.data().length);
        } else {
            tidBank.put("error", reader.lastErrorStatusText());
        }
        analysis.put("tidBank", tidBank);
        
        // Analyze Reserved bank and passwords
        analyzeReservedBank(epcTag, theoreticalTag, analysis);
        
        return analysis;
    }

    /**
     * Analyze Reserved bank for password security.
     */
    private static void analyzeReservedBank(ThEpcClass1Gen2 epcTag, Tag theoreticalTag, Map<String, Object> analysis) throws Exception {
        // Attempt to read Reserved bank WITHOUT password (to check lock status)
        DataBuffer reservedDataNoAuth = new DataBuffer();
        int returnCode = epcTag.readMultipleBlocks(
            ThEpcClass1Gen2.Bank.Reserved,
            0, // Kill password at word 0
            4, // Read all 4 words (kill + access passwords)
            reservedDataNoAuth
        );
        
        boolean reservedReadableWithoutAuth = (returnCode == ErrorCode.Ok);
        
        // Attempt to read Reserved bank WITH password
        byte[] accessPwd = theoreticalTag.getAccessPassword();
        DataBuffer accessPwdBuf = new DataBuffer(accessPwd);
        DataBuffer reservedDataWithAuth = new DataBuffer();
        returnCode = epcTag.readMultipleBlocks(
            ThEpcClass1Gen2.Bank.Reserved,
            0,
            4,
            reservedDataWithAuth,
            accessPwdBuf
        );
        
        boolean reservedReadableWithAuth = (returnCode == ErrorCode.Ok);
        
        // Calculate theoretical passwords (always, regardless of read success)
        byte[] theoreticalKill = theoreticalTag.getKillPassword();
        byte[] theoreticalAccess = theoreticalTag.getAccessPassword();
        byte[] theoreticalPasswords = new byte[8];
        System.arraycopy(theoreticalKill, 0, theoreticalPasswords, 0, 4);
        System.arraycopy(theoreticalAccess, 0, theoreticalPasswords, 4, 4);
        String theoreticalPasswordsHex = bytesToHex(theoreticalPasswords);
        
        Map<String, Object> reservedBank = new java.util.LinkedHashMap<>();
        reservedBank.put("readableWithoutAuth", reservedReadableWithoutAuth);
        reservedBank.put("readableWithAuth", reservedReadableWithAuth);
        reservedBank.put("theoretical", theoreticalPasswordsHex);
        
        boolean passwordsMatch = false;
        boolean passwordsAreZero = false;
        
        if (reservedReadableWithAuth) {
            byte[] actualPasswords = reservedDataWithAuth.data();
            String actualPasswordsHex = bytesToHex(actualPasswords);
            
            passwordsMatch = actualPasswordsHex.equals(theoreticalPasswordsHex);
            
            // Check if passwords are all zeros
            passwordsAreZero = true;
            for (byte b : actualPasswords) {
                if (b != 0) {
                    passwordsAreZero = false;
                    break;
                }
            }
            
            reservedBank.put("actual", actualPasswordsHex);
            reservedBank.put("matches", passwordsMatch);
            reservedBank.put("passwordsAreZero", passwordsAreZero);
        } else if (reservedReadableWithoutAuth) {
            byte[] actualPasswords = reservedDataNoAuth.data();
            String actualPasswordsHex = bytesToHex(actualPasswords);
            
            passwordsMatch = actualPasswordsHex.equals(theoreticalPasswordsHex);
            
            // Check if passwords are all zeros
            passwordsAreZero = true;
            for (byte b : actualPasswords) {
                if (b != 0) {
                    passwordsAreZero = false;
                    break;
                }
            }
            
            reservedBank.put("actual", actualPasswordsHex);
            reservedBank.put("matches", passwordsMatch);
            reservedBank.put("passwordsAreZero", passwordsAreZero);
            
            if (passwordsAreZero) {
                reservedBank.put("info", "Tag has no password protection (passwords are zero)");
            } else {
                reservedBank.put("warning", "Reserved bank readable without password - not properly secured");
            }
        } else {
            reservedBank.put("error", "Unable to read Reserved bank even with password");
            reservedBank.put("matches", false);
        }
        
        analysis.put("reservedBank", reservedBank);
        
        // Determine lock status
        Map<String, Object> lockStatus = new java.util.LinkedHashMap<>();
        
        if (!reservedReadableWithoutAuth && reservedReadableWithAuth) {
            lockStatus.put("reservedBank", "LOCKED");
            lockStatus.put("reservedBankStatus", "Read-protected with access password");
        } else if (reservedReadableWithoutAuth) {
            if (passwordsAreZero) {
                lockStatus.put("reservedBank", "UNLOCKED_NO_PASSWORD");
                lockStatus.put("reservedBankStatus", "No password protection configured");
            } else {
                lockStatus.put("reservedBank", "UNLOCKED");
                lockStatus.put("reservedBankStatus", "Readable without authentication (insecure)");
            }
        } else {
            lockStatus.put("reservedBank", "UNKNOWN");
            lockStatus.put("reservedBankStatus", "Cannot determine lock status");
        }
        
        // Attempt to write to EPC bank WITHOUT password to check write lock
        // We'll try to write the same value back, so we don't modify the tag
        // TODO : Disabled for safety - uncomment to enable write test, but make sure to use  not block 9, but the last block of the tag
        /*if (epcBankData.data().length >= 2) {
            byte[] lastTwoBytes = new byte[2];
            System.arraycopy(epcBankData.data(), epcBankData.data().length - 2, lastTwoBytes, 0, 2);
            
            DataBuffer testWriteData = new DataBuffer(lastTwoBytes);
            returnCode = epcTag.writeMultipleBlocks(
                ThEpcClass1Gen2.Bank.Epc,
                9, // Last block of typical DE290 EPC
                1, // Write 1 word
                testWriteData
            );
            
            if (returnCode == ErrorCode.Ok) {
                lockStatus.put("epcBank", "UNLOCKED");
                lockStatus.put("epcBankStatus", "Writable without authentication");
            } else {
                lockStatus.put("epcBank", "LOCKED");
                lockStatus.put("epcBankStatus", "Write-protected (requires access password)");
            }
        }*/
        
        analysis.put("lockStatus", lockStatus);
        
        // Overall security assessment
        Map<String, Object> securityAssessment = new java.util.LinkedHashMap<>();
        boolean properlySecured = !reservedReadableWithoutAuth && 
                                 reservedReadableWithAuth && 
                                 passwordsMatch;
        
        securityAssessment.put("properlySecured", properlySecured);
        securityAssessment.put("passwordCorrect", passwordsMatch);
        
        List<String> issues = new ArrayList<>();
        
        // Check if this tag format requires password protection
        boolean shouldHavePasswords = !(theoreticalTag instanceof RawTag);
        
        if (reservedReadableWithoutAuth && !passwordsAreZero) {
            issues.add("Reserved bank not password-protected but contains non-zero passwords");
        }
        if (reservedReadableWithAuth && !passwordsMatch && !passwordsAreZero) {
            issues.add("Password does not match theoretical calculation");
        }
        if (passwordsAreZero && shouldHavePasswords) {
            issues.add("Tag format requires password protection but passwords are not configured (initialization incomplete or failed)");
        }
        
        securityAssessment.put("issues", issues);
        securityAssessment.put("passwordProtectionConfigured", !passwordsAreZero);
        securityAssessment.put("passwordProtectionRequired", shouldHavePasswords);
        analysis.put("securityAssessment", securityAssessment);
    }

    /**
     * Modify security bit on a tag.
     */
    private static Void modifySecurityBit(ReaderModule reader, ReaderConfig config, String epcHex, Tag tag) throws Exception {
        // Set security bit
        // Get dynamic blocks that need to be written
        byte[] dynamicBlocks = tag.getDynamicBlocks();
        int startBlock = tag.getDynamicBlocksInitialNumber();
        
        // Perform inventory to select the tag
        InventoryParam inventoryParam = new InventoryParam();
        inventoryParam.setAntennas(config.getAntennaMask());
        int returnCode = reader.hm().inventory(true, inventoryParam);
        
        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Inventory failed: " + reader.lastErrorStatusText());
        }
        
        if (reader.hm().itemCount() == 0) {
            throw new Exception("No tags found in field");
        }

        // Find matching tag
        ThEpcClass1Gen2 epcTag = findTagByEpc(reader, epcHex);
        if (epcTag == null) {
            throw new Exception("Specified tag not found or not EPC Gen2");
        }

        // Write dynamic blocks with access password (if tag has password protection)
        DataBuffer dataToWrite = new DataBuffer(dynamicBlocks);
        
        // Check if tag has password protection (non-zero access password)
        byte[] accessPwd = tag.getAccessPassword();
        boolean hasPassword = false;
        for (byte b : accessPwd) {
            if (b != 0) {
                hasPassword = true;
                break;
            }
        }
        
        // For BRTag, dynamic blocks are PC (word address 1)
        // For DE290/DE6 tags, dynamic blocks are in EPC memory at calculated position
        DataBuffer accessPwdData = hasPassword ? new DataBuffer(accessPwd) : null;
        // Even if the tag is unlocked, sending a potential password is harmless
        returnCode = writeWithRetry(
            epcTag,
            ThEpcClass1Gen2.Bank.Epc,
            startBlock,
            dynamicBlocks.length / 2,
            dataToWrite,
            accessPwdData
        );

        if (returnCode != ErrorCode.Ok) {
            throw new Exception("Failed to write security bit: " + reader.lastErrorStatusText() +
                              " (ISO error: " + epcTag.lastIsoError() + ")");
        }

        return null;
    }

    /**
     * Find a tag by its EPC hex string in current inventory.
     */
    private static ThEpcClass1Gen2 findTagByEpc(ReaderModule reader, String epcHex) throws Exception {
        for (int i = 0; i < reader.hm().itemCount(); i++) {
            TagItem tagItem = reader.hm().tagItem(i);
            if (tagItem.iddToHexString().equalsIgnoreCase(epcHex)) {
                ThBase handler = reader.hm().createTagHandler(i);
                if (handler instanceof ThEpcClass1Gen2) {
                    return (ThEpcClass1Gen2) handler;
                }
            }
        }
        return null;
    }

    private static boolean testNativeLibLoading() {
        try {
            System.loadLibrary("fedm-funit"); // as an example
            System.out.println("Native library fedm-funit loaded successfully.");
            return true;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load native library fedm-funit: " + e.getMessage());
            return false;
        }
    }

    /**
     * Convert byte array to hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    /**
     * Write to tag with retry logic for transient RF errors (no authentication).
     * @param epcTag Tag handler to write to
     * @param bank Memory bank to write
     * @param startBlock Starting block address
     * @param blockCount Number of blocks to write
     * @param data Data to write
     * @return ErrorCode from final attempt
     */
    private static int writeWithRetry(ThEpcClass1Gen2 epcTag, ThEpcClass1Gen2.Bank bank, 
                                     int startBlock, int blockCount, DataBuffer data) {
        return writeWithRetry(epcTag, bank, startBlock, blockCount, data, null);
    }

    /**
     * Write to tag with retry logic for transient RF errors.
     * @param epcTag Tag handler to write to
     * @param bank Memory bank to write
     * @param startBlock Starting block address
     * @param blockCount Number of blocks to write
     * @param data Data to write
     * @param password Access password (null for no authentication)
     * @return ErrorCode from final attempt
     */
    private static int writeWithRetry(ThEpcClass1Gen2 epcTag, ThEpcClass1Gen2.Bank bank, 
                                     int startBlock, int blockCount, DataBuffer data, DataBuffer password) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            int returnCode = (password != null) 
                ? epcTag.writeMultipleBlocks(bank, startBlock, blockCount, data, password)
                : epcTag.writeMultipleBlocks(bank, startBlock, blockCount, data);
            
            if (returnCode == ErrorCode.Ok) {
                if (attempt > 1) {
                    log.info("Write to {}[{}] succeeded on attempt {}/{}", 
                        bank, startBlock, attempt, MAX_RETRIES);
                }
                return returnCode;
            }
            
            log.warn("Write to {}[{}] failed on attempt {}/{} (error: {})", 
                bank, startBlock, attempt, MAX_RETRIES, returnCode);
            
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Write retry interrupted");
                    return returnCode;
                }
            }
        }
        
        return -1;
    }

    /**
     * Lock/unlock tag memory with retry logic.
     * @param epcTag Tag handler to lock
     * @param kill Kill password lock parameter
     * @param access Access password lock parameter
     * @param epc EPC memory lock parameter
     * @param tid TID memory lock parameter
     * @param user User memory lock parameter
     * @param password Access password for authentication
     * @return ErrorCode from final attempt
     */
    private static int lockWithRetry(ThEpcClass1Gen2 epcTag, LockParam kill, LockParam access,
                                    LockParam epc, LockParam tid, LockParam user, DataBuffer password) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            int returnCode = epcTag.lock(kill, access, epc, tid, user, password);
            
            if (returnCode == ErrorCode.Ok) {
                if (attempt > 1) {
                    log.info("Lock operation succeeded on attempt {}/{}", attempt, MAX_RETRIES);
                }
                return returnCode;
            }
            
            log.warn("Lock operation failed on attempt {}/{} (error: {})", 
                attempt, MAX_RETRIES, returnCode);
            
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_DELAY_MS + (attempt - 1) * 50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Lock retry interrupted");
                    return returnCode;
                }
            }
        }
        
        return -1;
    }

    /**
     * Create a new tag instance for initialization based on format string.
     * Supports: DE290, CD290, DE6, DE290F, DE386
     */
    private static Tag createTagForInitialization(String format, String mediaId, boolean secured) {
        String formatUpper = format.toUpperCase();
        
        switch (formatUpper) {
            case "DE290":
                return new DE290Tag(Long.parseLong(mediaId), secured, DE290Tag.TagVariant.DE290,
                    TagFactory.getPasswordForType("DE290Tag", "access"),
                    TagFactory.getPasswordForType("DE290Tag", "kill"));
                
            case "CD290":
                return new DE290Tag(Long.parseLong(mediaId), secured, DE290Tag.TagVariant.CD290,
                    TagFactory.getPasswordForType("DE290Tag", "access"),
                    TagFactory.getPasswordForType("DE290Tag", "kill"));
                
            case "DE6":
                DE6Tag de6Tag = new DE6Tag(null, new byte[16],
                    TagFactory.getPasswordForType("DE6Tag", "access"),
                    TagFactory.getPasswordForType("DE6Tag", "kill"));
                de6Tag.setMediaId(mediaId);
                de6Tag.setSecured(secured);
                return de6Tag;
                
            case "DE290F":
                DE290FTag de290fTag = new DE290FTag(null, new byte[16],
                    TagFactory.getPasswordForType("DE290Tag", "access"),
                    TagFactory.getPasswordForType("DE290Tag", "kill"));
                de290fTag.setMediaId(mediaId);
                de290fTag.setSecured(secured);
                return de290fTag;
                
            case "DE386":
                // DE386Tag accepts ASCII string media ID directly
                DE386Tag de386Tag = new DE386Tag(mediaId, (byte) 0x00, secured,
                    TagFactory.getPasswordForType("DE386Tag", "access"),
                    TagFactory.getPasswordForType("DE386Tag", "kill"));
                return de386Tag;
                
            default:
                throw new IllegalArgumentException("Unsupported tag format: " + format + 
                    ". Supported formats: DE290, CD290, DE6, DE290F, DE386");
        }
    }
}
