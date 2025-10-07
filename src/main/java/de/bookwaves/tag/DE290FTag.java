package de.bookwaves.tag;

import java.util.Arrays;

/**
 * POJO representing a DE290F format tag for "Passive Fernleihe" (interlibrary loans).
 * Introduced 2023, extends DE290 with different header and flexible media ID encoding.
 */
public class DE290FTag extends DE290Tag {
    
    public static final byte[] DE290F_HEADER = new byte[] { (byte) 0x19, (byte) 0xE9, (byte) 0xF8, (byte) 0x77 };
    
    // ID Type constants
    private static final byte ID_TYPE_NUMERIC = 0x01;        // Plain number
    private static final byte ID_TYPE_AT_PREFIX = 0x02;      // @-prefixed number
    private static final byte ID_TYPE_HBZU_PREFIX = 0x03;    // 49HBZUBD prefix + 7 digits
    private static final byte ID_TYPE_URN_CODE40 = 0x04;     // 8 chars URN Code40

    private static final int MAX_HBZU_NUMBER = 9999999;
    private static final int HBZU_NUMBER_LENGTH = 7;
    private static final String HBZU_PREFIX = "49HBZUBD";
    private static final int URN_CODE40_LENGTH = 8;
    
    public DE290FTag(byte[] pc, byte[] epc, String accessKey, String killKey) {
        super(pc, epc, accessKey, killKey);
    }

    @Override
    public String getMediaId() {
        if (epc.length < 12) {
            throw new IllegalStateException(String.format(
                "EPC too short for DE290F format: expected at least 12 bytes, got %d", 
                epc.length
            ));
        }
        
        byte idType = epc[4];
        byte[] idBytes = Arrays.copyOfRange(epc, 5, 12);
        
        switch (idType) {
            case ID_TYPE_NUMERIC: {
                byte[] padded = new byte[Long.BYTES];
                System.arraycopy(idBytes, 0, padded, Long.BYTES - idBytes.length, idBytes.length);
                return Long.toString(bytesToLong(padded));
            }
            
            case ID_TYPE_AT_PREFIX: {
                byte[] padded = new byte[Long.BYTES];
                System.arraycopy(idBytes, 0, padded, Long.BYTES - idBytes.length, idBytes.length);
                return "@" + bytesToLong(padded);
            }
            
            case ID_TYPE_HBZU_PREFIX: {
                byte[] padded = new byte[Long.BYTES];
                System.arraycopy(idBytes, 0, padded, Long.BYTES - idBytes.length, idBytes.length);
                long number = bytesToLong(padded);
                if (number > MAX_HBZU_NUMBER) {
                    throw new IllegalStateException(String.format(
                        "DE290F HBZU number too large: %d exceeds maximum %d", 
                        number, MAX_HBZU_NUMBER
                    ));
                }
                return String.format("%s%0" + HBZU_NUMBER_LENGTH + "d", HBZU_PREFIX, number);
            }
            
            case ID_TYPE_URN_CODE40: {
                byte[] encodedId = new byte[6];
                System.arraycopy(idBytes, 1, encodedId, 0, 6);
                return URNCode40.decode(encodedId).trim();
            }
            
            default:
                throw new IllegalStateException(String.format(
                    "Unknown DE290F ID type: 0x%02X (expected 0x01-0x04)", 
                    idType
                ));
        }
    }

    @Override
    public void setMediaId(String mediaIdString) {
        if (mediaIdString == null || mediaIdString.isBlank()) {
            throw new IllegalArgumentException("Media ID cannot be empty");
        }

        byte idType;
        byte[] idBytes = new byte[7];

        // Determine ID type and encode
        if (mediaIdString.length() == URN_CODE40_LENGTH && isCode40Compatible(mediaIdString)) {
            // URN Code40 format
            idType = ID_TYPE_URN_CODE40;
            byte[] encoded = URNCode40.encode(mediaIdString);
            System.arraycopy(encoded, 0, idBytes, 1, encoded.length);
            
        } else if (mediaIdString.startsWith(HBZU_PREFIX) && 
                   mediaIdString.length() == HBZU_PREFIX.length() + HBZU_NUMBER_LENGTH) {
            // HBZU prefix format
            idType = ID_TYPE_HBZU_PREFIX;
            String numericPart = mediaIdString.substring(HBZU_PREFIX.length());
            encodeNumericId(numericPart, idBytes);
            
        } else if (mediaIdString.startsWith("@")) {
            // @-prefixed format
            idType = ID_TYPE_AT_PREFIX;
            String numericPart = mediaIdString.substring(1);
            encodeNumericId(numericPart, idBytes);
            
        } else {
            // Plain numeric format
            idType = ID_TYPE_NUMERIC;
            encodeNumericId(mediaIdString, idBytes);
        }

        // Build new EPC
        byte[] newEpc = new byte[16];
        System.arraycopy(DE290F_HEADER, 0, newEpc, 0, 4);
        newEpc[4] = idType;
        System.arraycopy(idBytes, 0, newEpc, 5, 7);
        // Bytes 12-15 remain 0x00 (footer)

        updatePCLength(newEpc.length);
        epc = newEpc;
    }

    private void encodeNumericId(String numericStr, byte[] target) {
        try {
            long mediaId = Long.parseLong(numericStr);
            byte[] longBytes = longToBytes(mediaId);
            if (longBytes[0] != 0x00) {
                throw new IllegalArgumentException("Media ID too large for DE290F format");
            }
            System.arraycopy(longBytes, 1, target, 0, 7);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric media ID: " + numericStr, e);
        }
    }

    private boolean isCode40Compatible(String str) {
        // URN Code40 supports: space, A-Z, -, ., :, 0-9
        return str.matches("^[A-Z0-9 \\-.:]+$");
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
        return String.format("DE290F<%s|%s>", getMediaId(), isSecured());
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
                String.format("DE290F format requires numeric media ID (got: '%s')", mediaId));
        }
    }
}
