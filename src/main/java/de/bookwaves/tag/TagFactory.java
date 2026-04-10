package de.bookwaves.tag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Factory class for creating appropriate Tag instances based on EPC data.
 * Auto-detects tag format from header bytes.
 * Supports configurable passwords globally via convention-based lookup.
 * 
 * Password keys follow the pattern: <TagClassName>.<passwordType>
 * Example: "DE290Tag.access", "BRTag.secret", "DE6Tag.kill", "DE386Tag.access"
 * 
 * Default passwords are placeholders - configure real passwords in YAML!
 */
public class TagFactory {

    private static final Logger log = LoggerFactory.getLogger(TagFactory.class);

    // Global password configuration (loaded from YAML)
    private static Map<String, String> passwordConfig = new HashMap<>();

    /**
     * Set global password configuration from external source (e.g., YAML config).
     * Keys should follow the pattern: <TagClassName>.<passwordType>
     * 
     * @param passwords Map of password keys to values
     */
    public static void setPasswordConfiguration(Map<String, String> passwords) {
        passwordConfig = passwords != null ? new HashMap<>(passwords) : new HashMap<>();
        log.info("Password configuration set with {} entries", passwordConfig.size());
        validatePasswordConfiguration();
    }

    /**
     * Validate that critical passwords are not using placeholder values.
     */
    private static void validatePasswordConfiguration() {
        for (Map.Entry<String, String> entry : passwordConfig.entrySet()) {
            String value = entry.getValue();
            if (value != null && value.contains("CHANGE-ME")) {
                log.warn("SECURITY: Password '{}' is still using placeholder value! " +
                         "Configure real passwords in config.yaml", entry.getKey());
            }
        }
    }

    /**
     * Get a password for a specific tag class and type (public for Main.java).
     */
    public static String getPasswordForType(String tagClassName, String passwordType) {
        return getPassword(tagClassName, passwordType, "CHANGE-ME-IN-YAML");
    }

    /**
     * Get a password for a specific tag class and type.
     * Falls back to default value if not configured.
     * 
     * @param tagClassName Simple class name (e.g., "DE290Tag", "BRTag")
     * @param passwordType Type of password (e.g., "access", "kill", "secret")
     * @param defaultValue Default value if not configured
     * @return Configured password or default
     */
    private static String getPassword(String tagClassName, String passwordType, String defaultValue) {
        String key = tagClassName + "." + passwordType;
        String configuredValue = passwordConfig.get(key);
        return configuredValue != null ? configuredValue : defaultValue;
    }

    /**
     * Create tag from PC and EPC data, auto-detecting format.
     */
    public static Tag createTag(byte[] pc, byte[] epc) {
        if (epc == null || epc.length < 1) {
            // Provide safe defaults for completely invalid tags
            log.warn("Received null/empty EPC while creating tag; returning RawTag");
            return new RawTag(
                pc != null ? pc : new byte[2], 
                epc != null ? epc : new byte[0]
            );
        }

        if (epc.length < 4) {
            log.warn("EPC too short ({} bytes) to determine format; returning RawTag", epc.length);
            return new RawTag(pc != null ? pc : new byte[2], epc);
        }

        // Check for DE290 variants (must check DE290F before DE290 due to overlapping headers)
        byte[] header = Arrays.copyOfRange(epc, 0, 4);
        
        if (Arrays.equals(header, BookWavesTag.DE386_HEADER) ||
            Arrays.equals(header, BookWavesTag.DE385_HEADER) ||
            Arrays.equals(header, BookWavesTag.DELAN1_HEADER)) {
            BookWavesTag.HeaderType headerType = BookWavesTag.HeaderType.fromHeader(header);
            String passwordKeyPrefix = headerType.getPasswordKeyPrefix();
            log.debug("Detected {} tag from header", headerType.getDisplayName());
            return new BookWavesTag(pc, epc,
                getPassword(passwordKeyPrefix, "access", "CHANGE-ME-IN-YAML-ACCESS"),
                getPassword(passwordKeyPrefix, "kill", "CHANGE-ME-IN-YAML-KILL"));
        }
        
        if (Arrays.equals(header, DE290FTag.DE290F_HEADER)) {
            log.debug("Detected DE290F tag from header");
            // IMPORTANT: Passwords for DE290FTag are still looked up with "DE290Tag" prefix since it's a variant
            return new DE290FTag(pc, epc, 
                getPassword("DE290Tag", "access", "CHANGE-ME-IN-YAML-ACCESS"),
                getPassword("DE290Tag", "kill", "CHANGE-ME-IN-YAML-KILL"));
        }
        
        if (Arrays.equals(header, DE6Tag.DE6_HEADER)) {
            log.debug("Detected DE6 tag from header");
            return new DE6Tag(pc, epc, 
                getPassword("DE6Tag", "access", "CHANGE-ME-IN-YAML-ACCESS"),
                getPassword("DE6Tag", "kill", "CHANGE-ME-IN-YAML-KILL"));
        }
        
        if (Arrays.equals(header, DE290Tag.DE290_HEADER) || 
            Arrays.equals(header, DE290Tag.CD290_HEADER)) {
            log.debug("Detected DE290/CD290 tag from header");
            return new DE290Tag(pc, epc,
                getPassword("DE290Tag", "access", "CHANGE-ME-IN-YAML-ACCESS"),
                getPassword("DE290Tag", "kill", "CHANGE-ME-IN-YAML-KILL"));
        }

        if (epc[0] == BRTag.BR_HEADER && BRTag.isBRTag(epc)) {
            log.debug("Detected BR tag from header");
            return new BRTag(pc, epc, 
                getPassword("BRTag", "secret", "CHANGE-ME-IN-YAML-SECRET"));
        }

        log.debug("Unknown EPC header {}, defaulting to RawTag", bytesToHexPrefix(epc));
        return new RawTag(pc != null ? pc : new byte[2], epc);
    }

    /**
     * Create tag from hex string EPC.
     */
    public static Tag createTagFromHexString(String epcHex) {
        if (epcHex == null || epcHex.isEmpty()) {
            throw new IllegalArgumentException("EPC hex string cannot be null or empty");
        }

        epcHex = epcHex.toUpperCase().replaceAll("\\s+", ""); // Remove whitespace
        
        if (!epcHex.matches("^[A-F0-9]+$")) {
            throw new IllegalArgumentException("EPC must be valid hexadecimal string");
        }

        if (epcHex.length() % 2 != 0) {
            throw new IllegalArgumentException("EPC hex string must have even length");
        }

        byte[] epc = new byte[epcHex.length() / 2];
        for (int i = 0; i < epc.length; i++) {
            epc[i] = (byte) Integer.parseInt(epcHex.substring(i * 2, i * 2 + 2), 16);
        }

        // Create PC based on EPC length
        byte[] pc = new byte[2];
        pc[0] = (byte) ((epc.length / 2) << 3);

        return createTag(pc, epc);
    }

    /**
     * Create HF tag metadata from ISO15693 read payload.
     * Falls back to RawTag if DE361 parsing does not yield meaningful fields.
     */
    public static Tag createHfTagFromIso15693(String iddHex, byte[] readData) {
        DE361Tag de361Tag = new DE361Tag(iddHex, readData);

        boolean hasMediaId = de361Tag.getMediaId() != null && !de361Tag.getMediaId().isBlank();
        boolean hasLibrarySigle = de361Tag.getLibrarySigle() != null && !de361Tag.getLibrarySigle().isBlank();
        if (hasMediaId || hasLibrarySigle) {
            return de361Tag;
        }

        return new RawTag(new byte[2], hexToBytes(iddHex));
    }

    private static byte[] hexToBytes(String value) {
        if (value == null || value.isBlank()) {
            return new byte[0];
        }

        String normalized = value.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if ((normalized.length() % 2) != 0 || !normalized.matches("^[A-F0-9]+$")) {
            return normalized.getBytes(StandardCharsets.US_ASCII);
        }

        byte[] bytes = new byte[normalized.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static String bytesToHexPrefix(byte[] epc) {
        int len = Math.min(epc.length, 4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X", epc[i]));
        }
        return sb.toString();
    }
}
