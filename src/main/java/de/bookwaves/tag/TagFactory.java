package de.bookwaves.tag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
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
        validatePasswordConfiguration();
    }

    /**
     * Validate that critical passwords are not using placeholder values.
     */
    private static void validatePasswordConfiguration() {
        for (Map.Entry<String, String> entry : passwordConfig.entrySet()) {
            if (entry.getValue().contains("CHANGE-ME")) {
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
        return passwordConfig.getOrDefault(key, defaultValue);
    }

    /**
     * Create tag from PC and EPC data, auto-detecting format.
     */
    public static Tag createTag(byte[] pc, byte[] epc) {
        if (epc == null || epc.length < 1) {
            // Provide safe defaults for completely invalid tags
            return new RawTag(
                pc != null ? pc : new byte[2], 
                epc != null ? epc : new byte[0]
            );
        }

        if (epc.length < 4) {
            return new RawTag(pc != null ? pc : new byte[2], epc);
        }

        // Check for DE290 variants (must check DE290F before DE290 due to overlapping headers)
        byte[] header = Arrays.copyOfRange(epc, 0, 4);
        
        if (Arrays.equals(header, DE386Tag.DE386_HEADER)) {
            return new DE386Tag(pc, epc, 
                getPassword("DE386Tag", "access", "CHANGE-ME-IN-YAML-ACCESS"),
                getPassword("DE386Tag", "kill", "CHANGE-ME-IN-YAML-KILL"));
        }
        
        if (Arrays.equals(header, DE290FTag.DE290F_HEADER)) {
            return new DE290FTag(pc, epc, 
                getPassword("DE290Tag", "access", "CHANGE-ME-IN-YAML-ACCESS"),
                getPassword("DE290Tag", "kill", "CHANGE-ME-IN-YAML-KILL"));
        }
        
        if (Arrays.equals(header, DE6Tag.DE6_HEADER)) {
            return new DE6Tag(pc, epc, 
                getPassword("DE6Tag", "access", "CHANGE-ME-IN-YAML-ACCESS"),
                getPassword("DE6Tag", "kill", "CHANGE-ME-IN-YAML-KILL"));
        }
        
        if (Arrays.equals(header, DE290Tag.DE290_HEADER) || 
            Arrays.equals(header, DE290Tag.CD290_HEADER)) {
            return new DE290Tag(pc, epc,
                getPassword("DE290Tag", "access", "CHANGE-ME-IN-YAML-ACCESS"),
                getPassword("DE290Tag", "kill", "CHANGE-ME-IN-YAML-KILL"));
        }

        if (epc[0] == BRTag.BR_HEADER && BRTag.isBRTag(epc)) {
            return new BRTag(pc, epc, 
                getPassword("BRTag", "secret", "CHANGE-ME-IN-YAML-SECRET"));
        }

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
}
