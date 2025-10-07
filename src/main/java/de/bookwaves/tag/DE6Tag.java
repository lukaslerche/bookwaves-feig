package de.bookwaves.tag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * POJO representing a DE6 format tag for external institutions.
 * Uses ISIL DE-6 header with custom password calculation.
 */
public class DE6Tag extends Tag {

    public static final byte[] DE6_HEADER = new byte[] { (byte) 0x19, (byte) 0xED, (byte) 0x00, (byte) 0x01 };
    
    private final String accessKey;
    private final String killKey;

    public DE6Tag(byte[] pc, byte[] epc, String accessKey, String killKey) {
        super(pc, epc);
        this.accessKey = accessKey;
        this.killKey = killKey;
    }

    @Override
    public String getMediaId() {
        byte[] epcMediaId = Arrays.copyOfRange(epc, 4, 12);
        return Long.toString(bytesToLong(epcMediaId));
    }

    @Override
    public void setMediaId(String mediaIdString) {
        long mediaId = Long.parseLong(mediaIdString);
        byte[] newEpc = new byte[16];
        byte[] payload = longToBytes(mediaId);
        
        System.arraycopy(DE6_HEADER, 0, newEpc, 0, DE6_HEADER.length);
        System.arraycopy(payload, 0, newEpc, DE6_HEADER.length, payload.length);
        
        epc = newEpc;
        
        // Fix PC to 0x4400 (specific for DE6)
        pc[0] = (byte) 0x44;
        pc[1] = (byte) 0x00;
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
        return calculatePassword(killKey);
    }

    @Override
    public byte[] getAccessPassword() {
        return calculatePassword(accessKey);
    }

    @Override
    public byte[] getDynamicBlocks() {
        byte[] dynamicBlocks = new byte[2];
        System.arraycopy(epc, 14, dynamicBlocks, 0, 2);
        return dynamicBlocks;
    }

    @Override
    public int getDynamicBlocksInitialNumber() {
        return 9;
    }

    private byte[] calculatePassword(String secretKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            
            byte[] epcFirst96Bit = Arrays.copyOfRange(epc, 0, 12);
            md.update(epcFirst96Bit);
            
            byte[] secretKeyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            byte[] hashedPassword = md.digest(secretKeyBytes);
            
            return Arrays.copyOfRange(hashedPassword, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-512 algorithm not available", e);
        }
    }

    private static byte[] longToBytes(long value) {
        byte[] result = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            result[i] = (byte)(value & 0xFF);
            value >>= Byte.SIZE;
        }
        return result;
    }

    private static long bytesToLong(final byte[] bytes) {
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            result <<= Byte.SIZE;
            result |= (bytes[i] & 0xFF);
        }
        return result;
    }

    @Override
    public String toString() {
        return String.format("DE6<%s|%s>", getMediaId(), isSecured());
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
                String.format("DE6 format requires numeric media ID (got: '%s')", mediaId));
        }
    }
}
