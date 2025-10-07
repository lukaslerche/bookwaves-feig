package de.bookwaves.tag;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Abstract base class for all RFID tag formats.
 * Provides common functionality for EPC Gen2 tags with PC and EPC memory.
 * 
 * <p><b>Thread Safety:</b> Tag instances are NOT thread-safe. Modifications to
 * tag state (via setMediaId, setSecured) should not be performed concurrently.
 * Tags can be safely read from multiple threads if no modifications occur.</p>
 * 
 * <p><b>Immutability:</b> getPc() and getEpc() return defensive copies to prevent
 * external modification of internal state.</p>
 */
public abstract class Tag {

    protected byte[] pc;
    protected byte[] epc;
    protected List<AntennaRssi> rssiValues;

    /**
     * Construct a tag from PC and EPC data.
     * @param pc Protocol Control word (2 bytes), can be null (will be initialized to [0,0])
     * @param epc Electronic Product Code data (must not be null)
     */
    public Tag(byte[] pc, byte[] epc) {
        Objects.requireNonNull(epc, "EPC cannot be null");
        if (pc != null && pc.length != 2) {
            throw new IllegalArgumentException("PC must be 2 bytes or null");
        }
        // Always ensure PC is non-null internally for simplicity
        this.pc = pc != null ? Arrays.copyOf(pc, pc.length) : new byte[2];
        this.epc = Arrays.copyOf(epc, epc.length);
        this.rssiValues = new ArrayList<>();
    }

    /**
     * Get the media ID encoded in this tag.
     * @return media ID as string
     */
    @JsonProperty("mediaId")
    public abstract String getMediaId();

    /**
     * Set the media ID for this tag.
     * @param mediaId media ID to encode
     */
    @JsonIgnore
    public abstract void setMediaId(String mediaId);

    /**
     * Check if the tag is in secured state.
     * @return true if secured
     */
    @JsonProperty("secured")
    public abstract boolean isSecured();

    /**
     * Set the security state of the tag.
     * @param secured true to secure, false to unsecure
     */
    @JsonIgnore
    public abstract void setSecured(boolean secured);

    /**
     * Get the kill password for this tag.
     * @return 4-byte kill password
     */
    @JsonIgnore
    public abstract byte[] getKillPassword();

    /**
     * Get the access password for this tag.
     * @return 4-byte access password
     */
    @JsonIgnore
    public abstract byte[] getAccessPassword();

    /**
     * Get the dynamic blocks that change when security state changes.
     * @return dynamic data blocks
     */
    @JsonIgnore
    public abstract byte[] getDynamicBlocks();

    /**
     * Get the initial block number where dynamic data starts in EPC memory.
     * @return block number (0-based)
     */
    @JsonIgnore
    public abstract int getDynamicBlocksInitialNumber();

    /**
     * Get a copy of the PC word.
     * @return PC bytes
     */
    @JsonIgnore
    public byte[] getPc() {
        return Arrays.copyOf(this.pc, this.pc.length);
    }

    /**
     * Get a copy of the EPC data.
     * @return EPC bytes
     */
    @JsonIgnore
    public byte[] getEpc() {
        return Arrays.copyOf(this.epc, this.epc.length);
    }

    /**
     * Set RSSI values for this tag (from inventory scan).
     * @param rssiValues list of antenna/RSSI pairs
     */
    @JsonIgnore
    public void setRssiValues(List<AntennaRssi> rssiValues) {
        this.rssiValues = new ArrayList<>(rssiValues);
    }

    /**
     * Get RSSI values for this tag.
     * @return unmodifiable list of antenna/RSSI pairs
     */
    @JsonProperty("rssiValues")
    public List<AntennaRssi> getRssiValues() {
        return Collections.unmodifiableList(rssiValues);
    }

    /**
     * Get the EPC as a hex string.
     * @return hex string representation of EPC
     */
    @JsonProperty("epc")
    public String getEpcHexString() {
        StringBuilder hex = new StringBuilder();
        for (byte b : epc) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    /**
     * Get the PC as a hex string.
     * @return hex string representation of PC
     */
    @JsonProperty("pc")
    public String getPCHexString() {
        StringBuilder hex = new StringBuilder();
        for (byte b : pc) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    /**
     * Get the tag format/type name.
     * @return simple class name (e.g., "DE290Tag", "BRTag")
     */
    @JsonProperty("tagType")
    public String getTagType() {
        return getClass().getSimpleName();
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 97 * hash + Arrays.hashCode(this.pc);
        hash = 97 * hash + Arrays.hashCode(this.epc);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final Tag other = (Tag) obj;
        return Arrays.equals(this.pc, other.pc) && Arrays.equals(this.epc, other.epc);
    }

    @Override
    public String toString() {
        return String.format("%s[PC=%s, EPC=%s, MediaId=%s, Secured=%b]", 
            getClass().getSimpleName(), toHex(this.pc), toHex(this.epc), getMediaId(), isSecured());
    }

    /**
     * Convert byte array to hex string representation.
     * @param bytes byte array to convert
     * @return hex string with length prefix
     */
    @JsonIgnore
    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "NULL";
        }
        StringBuilder result = new StringBuilder();
        result.append("[").append(bytes.length).append("] ");
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                result.append(":");
            }
            result.append(String.format("%02X", bytes[i]));
        }
        return result.toString();
    }

    /**
     * Get the EPC length in bytes as encoded in the PC word.
     * PC bits 15-11 contain the EPC length in 16-bit words.
     * @return EPC length in bytes (words * 2)
     */
    @JsonIgnore
    public int getEpcLengthFromPC() {
        if (pc == null || pc.length < 2) {
            return 0;
        }
        int pcValue = ((pc[0] & 0xFF) << 8) | (pc[1] & 0xFF);
        int epcLengthInWords = (pcValue >> 11) & 0x1F; // Extract bits 15-11
        return epcLengthInWords * 2; // Convert words to bytes
    }

    /**
     * Verify that the actual EPC length matches the PC-encoded length.
     * @return true if lengths match
     */
    @JsonIgnore
    public boolean isEpcLengthValid() {
        return epc.length == getEpcLengthFromPC();
    }

    /**
     * Validate that the media ID string is in the correct format for this tag type.
     * Throws IllegalArgumentException if invalid.
     * 
     * @param mediaId The media ID string to validate
     * @throws IllegalArgumentException if the format is invalid for this tag type
     */
    public abstract void validateMediaIdFormat(String mediaId) throws IllegalArgumentException;

    /**
     * RSSI value for a specific antenna.
     */
    public static class AntennaRssi {
        private final int antennaNumber;
        private final int rssi;

        public AntennaRssi(int antennaNumber, int rssi) {
            this.antennaNumber = antennaNumber;
            this.rssi = rssi;
        }

        public int getAntennaNumber() {
            return antennaNumber;
        }

        public int getRssi() {
            return rssi;
        }

        @Override
        public String toString() {
            return String.format("Antenna %d: %d dBm", antennaNumber, rssi);
        }
    }
}
