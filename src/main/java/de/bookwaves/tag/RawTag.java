package de.bookwaves.tag;

/**
 * Raw tag representation for unknown or generic RFID tags.
 * Provides minimal functionality without format-specific interpretation.
 */
public class RawTag extends Tag {

    public RawTag(byte[] pc, byte[] epc) {
        super(pc != null ? pc : new byte[2], epc); // Ensure PC is never null
    }

    @Override
    public String getMediaId() {
        if (epc == null || epc.length == 0) {
            return "";
        }
        
        // Interpret entire EPC as hex string
        StringBuilder hexString = new StringBuilder();
        for (byte b : epc) {
            hexString.append(String.format("%02X", b));
        }
        return hexString.toString();
    }

    @Override
    public void setMediaId(String mediaIdString) {
        // Parse hex string to bytes
        if (mediaIdString.length() % 2 != 0) {
            throw new IllegalArgumentException("Media ID must be even-length hex string");
        }
        
        byte[] newEpc = new byte[mediaIdString.length() / 2];
        for (int i = 0; i < newEpc.length; i++) {
            String hexByte = mediaIdString.substring(i * 2, i * 2 + 2);
            newEpc[i] = (byte) Integer.parseInt(hexByte, 16);
        }
        
        // Update PC length
        byte newPC0 = (byte) (pc[0] & 0b00000111);
        newPC0 |= ((newEpc.length / 2) << 3);
        pc[0] = newPC0;
        
        epc = newEpc;
    }

    @Override
    public boolean isSecured() {
        return false;
    }

    @Override
    public void setSecured(boolean secured) {
        // Raw tags don't support security bit
    }

    @Override
    public byte[] getKillPassword() {
        return new byte[] { 0, 0, 0, 0 };
    }

    @Override
    public byte[] getAccessPassword() {
        return new byte[] { 0, 0, 0, 0 };
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
    public String toString() {
        return "RawTag<" + getEpcHexString() + ">";
    }

    @Override
    public void validateMediaIdFormat(String mediaId) throws IllegalArgumentException {
        // Raw tags don't support media ID setting at all
        throw new IllegalArgumentException(
            "RawTag (unknown/unformatted tag) does not support media ID operations. " +
            "Use /initialize endpoint to format the tag first.");
    }
}
