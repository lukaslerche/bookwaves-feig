package de.bookwaves.tag;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * HF DE361 tag representation derived from ISO15693 block data.
 *
 * The IDD remains the stable identifier, while media ID and library sigle
 * are parsed from fixed DE361 block regions.
 */
public class DE361Tag extends Tag {

    private static final int MEDIA_ID_OFFSET = 3;
    private static final int MEDIA_ID_MAX_LENGTH = 16;
    private static final int LIBRARY_SIGLE_OFFSET = 21;
    private static final int LIBRARY_SIGLE_MAX_LENGTH = 8;

    private final String mediaId;
    private final String librarySigle;

    public DE361Tag(String iddHex, byte[] readData) {
        super(new byte[2], parseHex(iddHex));
        byte[] safeReadData = readData == null ? new byte[0] : readData;
        this.mediaId = parseAsciiField(safeReadData, MEDIA_ID_OFFSET, MEDIA_ID_MAX_LENGTH);
        this.librarySigle = parseAsciiField(safeReadData, LIBRARY_SIGLE_OFFSET, LIBRARY_SIGLE_MAX_LENGTH);
    }

    @Override
    public String getMediaId() {
        return mediaId;
    }

    @Override
    public void setMediaId(String mediaId) {
        throw new UnsupportedOperationException("HF DE361 tags are read-only in this version");
    }

    @Override
    public boolean isSecured() {
        return false;
    }

    @Override
    public void setSecured(boolean secured) {
        // AFI-based security is not implemented yet.
    }

    @Override
    public byte[] getKillPassword() {
        return new byte[] {0, 0, 0, 0};
    }

    @Override
    public byte[] getAccessPassword() {
        return new byte[] {0, 0, 0, 0};
    }

    @Override
    public byte[] getDynamicBlocks() {
        return new byte[0];
    }

    @Override
    public int getDynamicBlocksInitialNumber() {
        return 0;
    }

    @Override
    public void validateMediaIdFormat(String mediaId) throws IllegalArgumentException {
        if (mediaId == null || mediaId.isBlank()) {
            throw new IllegalArgumentException("Media ID cannot be blank");
        }
        if (!mediaId.matches("^[A-Za-z0-9/+\\-]+$")) {
            throw new IllegalArgumentException("Unsupported DE361 media ID format");
        }
    }

    @JsonProperty("librarySigle")
    public String getLibrarySigle() {
        return librarySigle;
    }

    private static String parseAsciiField(byte[] readData, int offset, int maxLength) {
        if (readData.length <= offset || maxLength <= 0) {
            return "";
        }

        int endExclusive = Math.min(readData.length, offset + maxLength);
        StringBuilder builder = new StringBuilder();

        for (int i = offset; i < endExclusive; i++) {
            int value = readData[i] & 0xFF;
            if (value == 0x00) {
                break;
            }
            if (value >= 32 && value <= 126) {
                builder.append((char) value);
            }
        }

        return builder.toString().trim();
    }

    private static byte[] parseHex(String hex) {
        if (hex == null) {
            return new byte[0];
        }

        String normalized = hex.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || (normalized.length() % 2) != 0 || !normalized.matches("^[0-9A-F]+$")) {
            return normalized.getBytes(StandardCharsets.US_ASCII);
        }

        byte[] result = new byte[normalized.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int from = i * 2;
            result[i] = (byte) Integer.parseInt(normalized.substring(from, from + 2), 16);
        }
        return result;
    }
}
