package de.bookwaves.tag;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Generalized tag for DE386/DE385/DELAN1 formats.
 * Uses URN Code40-encoded ISIL headers with configurable media ID encoding.
 * Variable section is 2 bytes: version byte + security bit flag.
 * Padding between header and media ID is flexible to accommodate variable-length IDs.
 */
public class ASCIITag extends Tag {

    private static final Logger log = LoggerFactory.getLogger(ASCIITag.class);

    public static final byte[] DE386_HEADER = new byte[] { (byte) 0x19, (byte) 0xEA, (byte) 0xF3, (byte) 0x21 };
    public static final byte[] DE385_HEADER = new byte[] { (byte) 0x19, (byte) 0xEA, (byte) 0xF2, (byte) 0xF9 };
    public static final byte[] DELAN1_HEADER = new byte[] { (byte) 0x19, (byte) 0xD5, (byte) 0x08, (byte) 0x90 };

    public static final int EPC_LENGTH = 16; // 128 bits
    public static final int HEADER_LENGTH = 4;
    public static final int VARIABLE_LENGTH = 2; // version + security flag
    public static final int MEDIA_ID_BYTES_CAPACITY = EPC_LENGTH - HEADER_LENGTH - VARIABLE_LENGTH;
    public static final int MAX_MEDIA_ID_LENGTH = MEDIA_ID_BYTES_CAPACITY; // ASCII bytes
    public static final int MAX_MEDIA_ID_LENGTH_URN_CODE40 = (MEDIA_ID_BYTES_CAPACITY / 2) * 3; // 15 chars

    private final HeaderType headerType;
    private final MediaIdEncoding mediaIdEncoding;
    private final String secretKeyAccess;
    private final String secretKeyKill;

    public enum MediaIdEncoding {
        ASCII,
        URN_CODE40
    }

    public enum HeaderType {
        DE386("DE386", "DE386Tag", DE386_HEADER),
        DE385("DE385", "DE385Tag", DE385_HEADER),
        DELAN1("DELAN1", "DELAN1Tag", DELAN1_HEADER);

        private final String displayName;
        private final String passwordKeyPrefix;
        private final byte[] header;

        HeaderType(String displayName, String passwordKeyPrefix, byte[] header) {
            this.displayName = displayName;
            this.passwordKeyPrefix = passwordKeyPrefix;
            this.header = header;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getPasswordKeyPrefix() {
            return passwordKeyPrefix;
        }

        public byte[] getHeader() {
            return header;
        }

        public static HeaderType fromHeader(byte[] header) {
            if (Arrays.equals(header, DE386_HEADER)) {
                return DE386;
            }
            if (Arrays.equals(header, DE385_HEADER)) {
                return DE385;
            }
            if (Arrays.equals(header, DELAN1_HEADER)) {
                return DELAN1;
            }
            throw new IllegalArgumentException("Unsupported ASCIITag header: " + Tag.toHex(header));
        }
    }

    /**
     * Create tag from existing PC and EPC data with custom passwords
     */
    public ASCIITag(byte[] pc, byte[] epc, String accessKey, String killKey) {
        this(pc, epc, MediaIdEncoding.ASCII, accessKey, killKey);
    }

    /**
     * Create tag from existing PC and EPC data with custom passwords and media ID encoding.
     */
    public ASCIITag(byte[] pc, byte[] epc, MediaIdEncoding mediaIdEncoding, String accessKey, String killKey) {
        super(pc, epc);
        byte[] header = Arrays.copyOfRange(epc, 0, HEADER_LENGTH);
        this.headerType = HeaderType.fromHeader(header);
        this.mediaIdEncoding = mediaIdEncoding;
        this.secretKeyAccess = accessKey;
        this.secretKeyKill = killKey;
    }

    /**
     * Create tag from media ID with specified header, version, and security bit
     */
    public ASCIITag(HeaderType headerType, String mediaId, byte version, boolean secured,
                    String accessKey, String killKey) {
        this(headerType, mediaId, version, secured, MediaIdEncoding.ASCII, accessKey, killKey);
    }

    /**
     * Create tag from media ID with specified header, version, security bit, and encoding.
     */
    public ASCIITag(HeaderType headerType, String mediaId, byte version, boolean secured,
                    MediaIdEncoding mediaIdEncoding, String accessKey, String killKey) {
        super(createPC(EPC_LENGTH), createEPC(mediaId, version, secured, headerType, mediaIdEncoding));
        this.headerType = headerType;
        this.mediaIdEncoding = mediaIdEncoding;
        this.secretKeyAccess = accessKey;
        this.secretKeyKill = killKey;
    }

    /**
     * Create tag from media ID (defaults to version 0, secured)
     */
    public ASCIITag(HeaderType headerType, String mediaId, String accessKey, String killKey) {
        this(headerType, mediaId, (byte) 0x00, true, accessKey, killKey);
    }

    /**
     * Create tag from media ID (defaults to version 0, secured) with selected encoding.
     */
    public ASCIITag(HeaderType headerType, String mediaId, MediaIdEncoding mediaIdEncoding,
                    String accessKey, String killKey) {
        this(headerType, mediaId, (byte) 0x00, true, mediaIdEncoding, accessKey, killKey);
    }

    @JsonIgnore
    public HeaderType getHeaderType() {
        return headerType;
    }

    @JsonIgnore
    public MediaIdEncoding getMediaIdEncoding() {
        return mediaIdEncoding;
    }

    @Override
    public String getMediaId() {
        int mediaIdStart = HEADER_LENGTH;
        int mediaIdLimit = EPC_LENGTH - VARIABLE_LENGTH;

        if (mediaIdEncoding == MediaIdEncoding.URN_CODE40) {
            int encodedLength = 0;
            for (int i = mediaIdStart; i + 1 < mediaIdLimit; i += 2) {
                int pairValue = ((epc[i] & 0xFF) << 8) | (epc[i + 1] & 0xFF);
                if (pairValue == 0) {
                    break;
                }
                encodedLength += 2;
            }

            if (encodedLength == 0) {
                return "";
            }

            byte[] encodedMediaId = Arrays.copyOfRange(epc, mediaIdStart, mediaIdStart + encodedLength);
            return URNCode40.decode(encodedMediaId).replaceFirst("\\s+$", "");
        }

        int mediaIdEnd = mediaIdLimit;
        while (mediaIdEnd > mediaIdStart
            && (epc[mediaIdEnd - 1] == 0x00 || epc[mediaIdEnd - 1] == 0x20)) {
            mediaIdEnd--;
        }

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

        byte version = getVersion();
        boolean secured = isSecured();
        byte[] newEpc = createEPC(mediaIdString, version, secured, headerType, mediaIdEncoding);

        updatePCLength(newEpc.length);
        epc = newEpc;
        log.debug("Updated {} mediaId to '{}' (encoding={}, secured={})",
            headerType.getDisplayName(), mediaIdString, mediaIdEncoding, secured);
    }

    @Override
    public boolean isSecured() {
        return ((epc[15] & 0b00000001) == 1);
    }

    @Override
    public void setSecured(boolean secured) {
        epc[15] = (byte) ((epc[15] & 0b11111110) | (secured ? 1 : 0));
        log.debug("Set {} secured={} (byte 15 now 0x{})", headerType.getDisplayName(), secured,
            String.format("%02X", epc[15]));
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
        log.debug("Set {} version byte to 0x{}", headerType.getDisplayName(), String.format("%02X", version));
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
     * Create EPC from media ID, version, security bit, and header type
     */
    private static byte[] createEPC(String mediaId, byte version, boolean secured, HeaderType headerType,
                                    MediaIdEncoding mediaIdEncoding) {
        byte[] epc = new byte[EPC_LENGTH];

        // Copy header (4 bytes)
        System.arraycopy(headerType.getHeader(), 0, epc, 0, HEADER_LENGTH);

        byte[] mediaIdBytes;
        if (mediaIdEncoding == MediaIdEncoding.URN_CODE40) {
            mediaIdBytes = URNCode40.encode(mediaId);
            if (mediaIdBytes.length > MEDIA_ID_BYTES_CAPACITY) {
                throw new IllegalArgumentException(String.format(
                    "URN Code40 media ID too long: maximum %d characters, got %d",
                    MAX_MEDIA_ID_LENGTH_URN_CODE40, mediaId.length()));
            }
        } else {
            mediaIdBytes = mediaId.getBytes(StandardCharsets.US_ASCII);
            if (mediaIdBytes.length > MAX_MEDIA_ID_LENGTH) {
                throw new IllegalArgumentException(String.format(
                    "ASCII media ID too long: maximum %d characters, got %d",
                    MAX_MEDIA_ID_LENGTH, mediaId.length()));
            }
        }

        // Left-align media ID bytes: start immediately after header
        int mediaIdStart = HEADER_LENGTH;
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
        return String.format("%s<%s|v%d|%s|%s>",
            headerType.getDisplayName(), mediaIdEncoding, getVersion(), getMediaId(), isSecured());
    }

    @Override
    public void validateMediaIdFormat(String mediaId) throws IllegalArgumentException {
        if (mediaId == null || mediaId.isBlank()) {
            throw new IllegalArgumentException("Media ID cannot be empty");
        }

        if (mediaIdEncoding == MediaIdEncoding.URN_CODE40) {
            if (!mediaId.matches("^[A-Z0-9 \\-.:]+$")) {
                throw new IllegalArgumentException(
                    headerType.getDisplayName()
                        + " URN Code40 media ID supports only A-Z, 0-9, space, '-', '.', ':'");
            }
            if (mediaId.length() > MAX_MEDIA_ID_LENGTH_URN_CODE40) {
                throw new IllegalArgumentException(String.format(
                    "%s URN Code40 media ID too long: maximum %d characters, got %d",
                    headerType.getDisplayName(), MAX_MEDIA_ID_LENGTH_URN_CODE40, mediaId.length()));
            }
        } else {
            if (!mediaId.matches("^[\\x00-\\x7F]+$")) {
                throw new IllegalArgumentException(
                    headerType.getDisplayName()
                        + " format requires ASCII-only media ID (got non-ASCII characters)");
            }
            if (mediaId.length() > MAX_MEDIA_ID_LENGTH) {
                throw new IllegalArgumentException(String.format(
                    "%s format media ID too long: maximum %d characters, got %d",
                    headerType.getDisplayName(), MAX_MEDIA_ID_LENGTH, mediaId.length()));
            }
        }
        log.debug("Validated {} mediaId '{}' with {} encoding",
            headerType.getDisplayName(), mediaId, mediaIdEncoding);
    }
}
