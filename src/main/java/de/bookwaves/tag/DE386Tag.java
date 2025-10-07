package de.bookwaves.tag;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * POJO representing a DE386 format tag.
 * Uses URN Code40-encoded ISIL (DE386 = 0x19EAF321) with ASCII-encoded media ID.
 * Variable section is 2 bytes: version byte + security bit flag.
 * Padding between header and media ID is flexible to accommodate variable-length IDs.
 */
public class DE386Tag extends Tag {

    public static final byte[] DE386_HEADER = new byte[] { (byte) 0x19, (byte) 0xEA, (byte) 0xF3, (byte) 0x21 };
    
    public static final int EPC_LENGTH = 16; // 128 bits
    public static final int HEADER_LENGTH = 4;
    public static final int VARIABLE_LENGTH = 2; // version + security flag
    public static final int MAX_MEDIA_ID_LENGTH = 10; // Maximum: 16 - 4 (header) - 2 (variable) = 10 bytes

    private final String secretKeyAccess;
    private final String secretKeyKill;

    /**
     * Create tag from existing PC and EPC data with custom passwords
     */
    public DE386Tag(byte[] pc, byte[] epc, String accessKey, String killKey) {
        super(pc, epc);
        this.secretKeyAccess = accessKey;
        this.secretKeyKill = killKey;
    }

    /**
     * Create tag from media ID with specified version and security bit
     */
    public DE386Tag(String mediaId, byte version, boolean secured, String accessKey, String killKey) {
        super(createPC(EPC_LENGTH), createEPC(mediaId, version, secured));
        this.secretKeyAccess = accessKey;
        this.secretKeyKill = killKey;
    }

    /**
     * Create tag from media ID (defaults to version 0, secured)
     */
    public DE386Tag(String mediaId, String accessKey, String killKey) {
        this(mediaId, (byte) 0x00, true, accessKey, killKey);
    }

    @Override
    public String getMediaId() {
        // Media ID is right-aligned: starts after padding, ends at byte 13 (before variable section at 14-15)
        int mediaIdEnd = EPC_LENGTH - VARIABLE_LENGTH; // byte 14
        
        // Find the start of media ID by skipping leading null bytes and spaces after header
        int mediaIdStart = HEADER_LENGTH;
        while (mediaIdStart < mediaIdEnd && (epc[mediaIdStart] == 0x00 || epc[mediaIdStart] == 0x20)) {
            mediaIdStart++;
        }
        
        // Extract media ID bytes
        byte[] mediaIdBytes = Arrays.copyOfRange(epc, mediaIdStart, mediaIdEnd);
        
        return new String(mediaIdBytes, StandardCharsets.US_ASCII);
    }

    @Override
    public void setMediaId(String mediaIdString) {
        if (mediaIdString == null || mediaIdString.isBlank()) {
            throw new IllegalArgumentException("Media ID cannot be empty");
        }
        
        // Validate format first
        validateMediaIdFormat(mediaIdString);
        
        if (mediaIdString.length() > MAX_MEDIA_ID_LENGTH) {
            throw new IllegalArgumentException(String.format(
                "Media ID too long: maximum %d characters, got %d", 
                MAX_MEDIA_ID_LENGTH, mediaIdString.length()
            ));
        }

        byte version = getVersion();
        boolean secured = isSecured();
        byte[] newEpc = createEPC(mediaIdString, version, secured);
        
        updatePCLength(newEpc.length);
        epc = newEpc;
    }

    @Override
    public boolean isSecured() {
        return ((epc[15] & 0b00000001) == 1);
    }

    @Override
    public void setSecured(boolean secured) {
        epc[15] = (byte) ((epc[15] & 0b11111110) | (secured ? 1 : 0));
    }

    /**
     * Get version byte from variable section
     */
    @JsonIgnore
    public byte getVersion() {
        return epc[14];
    }

    /**
     * Set version byte in variable section
     */
    public void setVersion(byte version) {
        epc[14] = version;
    }

    @Override
    public byte[] getKillPassword() {
        return calculatePassword(secretKeyKill);
    }

    @Override
    public byte[] getAccessPassword() {
        return calculatePassword(secretKeyAccess);
    }

    @Override
    public byte[] getDynamicBlocks() {
        // Get the last block (bytes 14-15) containing version and security flag
        byte[] dynamicBlocks = new byte[2];
        System.arraycopy(epc, 14, dynamicBlocks, 0, 2);
        return dynamicBlocks;
    }

    @Override
    public int getDynamicBlocksInitialNumber() {
        return 9; // Variable section is in block 9 (last block)
    }

    /**
     * Calculate password based on SHA-512 hash of EPC (first 96 bits) + secret key
     */
    private byte[] calculatePassword(String secretKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            
            // Use first 96 bits (12 bytes) of EPC as salt
            byte[] epcFirst96Bit = Arrays.copyOfRange(epc, 0, 12);
            md.update(epcFirst96Bit);
            
            // Hash with secret key
            byte[] secretKeyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            byte[] hashedPassword = md.digest(secretKeyBytes);
            
            // Return first 32 bits (4 bytes) as password
            return Arrays.copyOfRange(hashedPassword, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-512 algorithm not available", e);
        }
    }

    /**
     * Create EPC from media ID, version, and security bit
     */
    private static byte[] createEPC(String mediaId, byte version, boolean secured) {
        byte[] epc = new byte[EPC_LENGTH];
        
        // Copy header (4 bytes: 0x19EAF321)
        System.arraycopy(DE386_HEADER, 0, epc, 0, HEADER_LENGTH);
        
        // Right-align media ID: place it just before the variable section
        // Media ID ends at byte 13 (variable section starts at byte 14)
        byte[] mediaIdBytes = mediaId.getBytes(StandardCharsets.US_ASCII);
        int mediaIdStart = EPC_LENGTH - VARIABLE_LENGTH - mediaIdBytes.length; // Grows leftward
        System.arraycopy(mediaIdBytes, 0, epc, mediaIdStart, mediaIdBytes.length);
        
        // Variable section at the end (last 2 bytes)
        epc[14] = version;
        epc[15] = (byte) (secured ? 1 : 0);
        
        return epc;
    }

    /**
     * Create PC word with proper EPC length encoding
     */
    private static byte[] createPC(int epcLength) {
        byte[] pc = new byte[2];
        // First 5 bits of PC contain the length of the EPC in words (16-bit words)
        pc[0] = (byte) ((epcLength / 2) << 3);
        return pc;
    }

    /**
     * Update PC with new EPC length
     */
    private void updatePCLength(int epcLength) {
        byte newPC0 = (byte) (pc[0] & 0b00000111);
        newPC0 |= ((epcLength / 2) << 3);
        pc[0] = newPC0;
    }

    @Override
    public String toString() {
        return String.format("DE386<v%d|%s|%s>", getVersion(), getMediaId(), isSecured());
    }

    @Override
    public void validateMediaIdFormat(String mediaId) throws IllegalArgumentException {
        if (mediaId == null || mediaId.isBlank()) {
            throw new IllegalArgumentException("Media ID cannot be empty");
        }
        
        // Validate ASCII compatibility
        if (!mediaId.matches("^[\\x00-\\x7F]+$")) {
            throw new IllegalArgumentException(
                "DE386 format requires ASCII-only media ID (got non-ASCII characters)");
        }
        
        if (mediaId.length() > MAX_MEDIA_ID_LENGTH) {
            throw new IllegalArgumentException(String.format(
                "DE386 format media ID too long: maximum %d characters, got %d", 
                MAX_MEDIA_ID_LENGTH, mediaId.length()));
        }
    }
}
