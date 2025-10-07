package de.bookwaves.tag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * POJO representing a Smartfreq BR format tag (previously used vendor format).
 * Uses variable-length EPC with specific encoding for media ID.
 * Header: 0x41 (first byte)
 */
public class BRTag extends Tag {

    public static final byte BR_HEADER = (byte) 0x41;
    private final String secretKey;
    private static final byte UNSECURED_BITS = (byte) 0xC2;
    private static final byte SECURED_BITS = (byte) 0x07;

    public BRTag(byte[] pc, byte[] epc, String secretKey) {
        super(pc, epc);
        this.secretKey = secretKey;
    }

    @Override
    public String getMediaId() {
        if (epc.length < 2) {
            return "";
        }
        
        int numberOfBytes = epc[1] & 0xFF;
        if (epc.length < 2 + numberOfBytes) {
            return "";
        }
        
        byte[] epcMain = new byte[numberOfBytes];
        System.arraycopy(epc, 2, epcMain, 0, numberOfBytes);
        
        // Use SixBitAscii decoder (proper implementation)
        return SixBitAscii.decode(epcMain);
    }

    @Override
    public void setMediaId(String mediaIdString) {
        byte[] encodedMediaId = SixBitAscii.encode(mediaIdString);
        
        // Padded to even length (block size of EPC)
        byte[] result = new byte[encodedMediaId.length + 2 + (encodedMediaId.length % 2)];
        
        // Write the Smartfreq marker
        result[0] = BR_HEADER;
        
        // Encode the length
        result[1] = (byte) encodedMediaId.length;
        
        // Copy encoded media ID
        System.arraycopy(encodedMediaId, 0, result, 2, encodedMediaId.length);

        // Update the length of the EPC in the PC
        byte newPC0 = (byte) (pc[0] & 0b00000111);
        newPC0 |= ((result.length / 2) << 3);
        pc[0] = newPC0;

        epc = result;
    }

    @Override
    public boolean isSecured() {
        if (pc == null) {
            throw new IllegalStateException("PC is null");
        }

        if (pc[1] == SECURED_BITS) {
            return true;
        } else if (pc[1] == UNSECURED_BITS) {
            return false;
        } else {
            throw new IllegalStateException(String.format(
                "Unknown value for PC AFI bits found: 0x%02X 0x%02X", pc[0], pc[1]
            ));
        }
    }

    @Override
    public void setSecured(boolean secured) {
        if (pc == null) {
            throw new IllegalStateException("PC is null");
        }
        // Ensure tag is marked non-GS1 EPCglobal Application
        pc[0] = (byte) (pc[0] | 0b00000001);
        pc[1] = secured ? SECURED_BITS : UNSECURED_BITS;
    }

    @Override
    public byte[] getKillPassword() {
        // Kill password is never set for Smartfreq tags
        return new byte[] { 0, 0, 0, 0 };
    }

    @Override
    public byte[] getAccessPassword() {
        return calculateAccessPassword();
    }

    @Override
    public byte[] getDynamicBlocks() {
        return pc; // The security flag is in the PC (1 block = 2 bytes)
    }

    @Override
    public int getDynamicBlocksInitialNumber() {
        return 1; // The security flag is in the PC, which is block 1
    }

    /**
     * Calculate access password using SHA-1 (Smartfreq method).
     * Uses EPC as ASCII string + secret key.
     * Returns bytes [0, 2, 3, 6] from the SHA-1 hash.
     */
    private byte[] calculateAccessPassword() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            
            // Convert EPC bytes to uppercase hex string
            StringBuilder epcHex = new StringBuilder();
            for (byte b : epc) {
                epcHex.append(String.format("%02X", b));
            }
            
            // Use EPC hex string as ASCII bytes for salt
            byte[] epcStringBytes = epcHex.toString().getBytes(StandardCharsets.US_ASCII);
            md.update(epcStringBytes);
            
            // Hash with secret key (as ASCII)
            byte[] secretKeyBytes = secretKey.getBytes(StandardCharsets.US_ASCII);
            byte[] hashedPassword = md.digest(secretKeyBytes);
            
            // Smartfreq selects bytes 0, 2, 3, and 6 to form the password (4 bytes = 32 bits)
            return new byte[] { 
                hashedPassword[0], 
                hashedPassword[2], 
                hashedPassword[3], 
                hashedPassword[6] 
            };
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not available", e);
        }
    }

    /**
     * Check if EPC data represents a valid BR tag.
     * @param epc EPC bytes to check
     * @return true if valid BR tag format
     */
    public static boolean isBRTag(byte[] epc) {
        if (epc == null || epc.length < 2) {
            return false;
        }

        if (epc[0] != BR_HEADER) {
            return false;
        }

        int payloadLength = epc[1] & 0xFF;
        if (payloadLength == 0) {
            return false;
        }

        // Payload is padded to even length
        int expectedLength = 2 + payloadLength + (payloadLength % 2);
        return epc.length == expectedLength;
    }

    @Override
    public String toString() {
        return String.format("SMARTFREQ_BR<%s>", getMediaId());
    }

    @Override
    public void validateMediaIdFormat(String mediaId) throws IllegalArgumentException {
        if (mediaId == null || mediaId.isBlank()) {
            throw new IllegalArgumentException("Media ID cannot be empty");
        }
        
        // BRTag uses 6-bit ASCII encoding, which supports uppercase letters, digits, and space
        if (!mediaId.matches("^[A-Z0-9 ]+$")) {
            throw new IllegalArgumentException(
                "BRTag (Smartfreq BR) format requires uppercase alphanumeric media ID with optional spaces " +
                "(got: '" + mediaId + "')");
        }
    }
}
