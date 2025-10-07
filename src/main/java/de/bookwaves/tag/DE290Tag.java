package de.bookwaves.tag;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * POJO representing a DE290 format tag used in UB Dortmund Zentralbibliothek (ZB).
 * Supports both DE290 (correct header 0x19E9F871) and CD290 (legacy header 0x1381F871).
 * Introduced 2021, with backwards compatibility for earlier tag formats.
 */
public class DE290Tag extends Tag {

    public static final byte[] DE290_HEADER = new byte[] { (byte) 0x19, (byte) 0xE9, (byte) 0xF8, (byte) 0x71 };
    public static final byte[] CD290_HEADER = new byte[] { (byte) 0x13, (byte) 0x81, (byte) 0xF8, (byte) 0x71 };
    
    public static final int EPC_LENGTH = 16; // 128 bits
    public static final int HEADER_LENGTH = 4;
    public static final int MEDIA_ID_LENGTH = 8;
    public static final int FOOTER_LENGTH = 4;

    private final TagVariant variant;
    private final String secretKeyAccess;
    private final String secretKeyKill;

    public enum TagVariant {
        DE290,  // Correct header (0x19E9F871)
        CD290   // Legacy header (0x1381F871) - compatibility for first 5000 tags
    }

    /**
     * Create tag from existing PC and EPC data with custom passwords
     */
    public DE290Tag(byte[] pc, byte[] epc, String accessKey, String killKey) {
        super(pc, epc);
        this.variant = detectVariant(epc);
        this.secretKeyAccess = accessKey;
        this.secretKeyKill = killKey;
    }

    /**
     * Create tag from media ID with specified variant and security bit
     */
    public DE290Tag(long mediaId, boolean secured, TagVariant variant, String accessKey, String killKey) {
        super(createPC(EPC_LENGTH), createEPC(mediaId, secured, variant));
        this.variant = variant;
        this.secretKeyAccess = accessKey;
        this.secretKeyKill = killKey;
    }

    /**
     * Create tag from media ID (defaults to DE290 variant, secured)
     */
    public DE290Tag(long mediaId, String accessKey, String killKey) {
        this(mediaId, true, TagVariant.DE290, accessKey, killKey);
    }

    @Override
    public String getMediaId() {
        byte[] epcMediaId = Arrays.copyOfRange(epc, HEADER_LENGTH, HEADER_LENGTH + MEDIA_ID_LENGTH);
        return Long.toString(bytesToLong(epcMediaId));
    }

    @Override
    public void setMediaId(String mediaIdString) {
        long mediaId = Long.parseLong(mediaIdString);
        //byte[] header = (variant == TagVariant.DE290) ? DE290_HEADER : CD290_HEADER;
        byte[] newEpc = createEPC(mediaId, isSecured(), variant);
        
        // Update the length of the EPC in the PC
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
        // Get the last block (bytes 14-15) containing the security flag
        byte[] dynamicBlocks = new byte[2];
        System.arraycopy(epc, 14, dynamicBlocks, 0, 2);
        return dynamicBlocks;
    }

    @Override
    public int getDynamicBlocksInitialNumber() {
        return 9; // The security flag is in block 9 (last block)
    }

    @JsonIgnore
    public TagVariant getVariant() {
        return variant;
    }

    /**
     * Detect tag variant based on EPC header
     */
    private static TagVariant detectVariant(byte[] epc) {
        if (epc.length >= HEADER_LENGTH) {
            if (Arrays.equals(Arrays.copyOfRange(epc, 0, HEADER_LENGTH), DE290_HEADER)) {
                return TagVariant.DE290;
            } else if (Arrays.equals(Arrays.copyOfRange(epc, 0, HEADER_LENGTH), CD290_HEADER)) {
                return TagVariant.CD290;
            }
        }
        // Default to DE290 if unknown
        return TagVariant.DE290;
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
     * Create EPC from media ID, security bit, and variant
     */
    private static byte[] createEPC(long mediaId, boolean secured, TagVariant variant) {
        byte[] epc = new byte[EPC_LENGTH];
        byte[] header = (variant == TagVariant.DE290) ? DE290_HEADER : CD290_HEADER;
        byte[] mediaIdBytes = longToBytes(mediaId);
        
        // Copy header (4 bytes)
        System.arraycopy(header, 0, epc, 0, HEADER_LENGTH);
        
        // Copy media ID (8 bytes)
        System.arraycopy(mediaIdBytes, 0, epc, HEADER_LENGTH, MEDIA_ID_LENGTH);
        
        // Set security bit in last byte
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

    /**
     * Convert long to 8-byte array (big-endian)
     */
    protected static byte[] longToBytes(long value) {
        byte[] result = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            result[i] = (byte)(value & 0xFF);
            value >>= Byte.SIZE;
        }
        return result;
    }

    /**
     * Convert 8-byte array to long (big-endian)
     */
    protected static long bytesToLong(final byte[] bytes) {
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            result <<= Byte.SIZE;
            result |= (bytes[i] & 0xFF);
        }
        return result;
    }

    @Override
    public String toString() {
        return String.format("%s<%s|%s>", variant, getMediaId(), isSecured());
    }

    @Override
    public void validateMediaIdFormat(String mediaId) throws IllegalArgumentException {
        if (mediaId == null || mediaId.isBlank()) {
            throw new IllegalArgumentException("Media ID cannot be empty");
        }
        
        try {
            long value = Long.parseLong(mediaId);
            if (value < 0) {
                throw new IllegalArgumentException("Media ID must be a non-negative number");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                String.format("%s format requires numeric media ID (got: '%s')", 
                    variant.name(), mediaId));
        }
    }
}
