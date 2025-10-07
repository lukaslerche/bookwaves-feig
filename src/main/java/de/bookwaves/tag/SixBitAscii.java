package de.bookwaves.tag;

import java.util.List;
import static java.util.Arrays.asList;

/**
 * Six-bit ASCII encoding helper (used by AIS/Smartfreq).
 * Encodes 64 characters into 6-bit values.
 */
class SixBitAscii {
    
    private static final List<Character> ENCODING_TABLE = asList(
        '@', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O',
        'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '[', '\\', ']', '^', '-',
        ' ', '!', '"', '#', '$', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', ';', '<', '=', '>', '?'
    );

    public static byte encode(char c) {
        int index = ENCODING_TABLE.indexOf(c);
        if (index < 0) {
            throw new IllegalArgumentException("Character '" + c + "' not in SixBit ASCII");
        }
        return (byte) index;
    }

    public static char decode(byte b) {
        int index = b & 0xFF;
        if (index >= ENCODING_TABLE.size()) {
            throw new IllegalArgumentException("Byte 0x" + Integer.toHexString(index) + " not in SixBit ASCII");
        }
        return ENCODING_TABLE.get(index);
    }

    public static byte[] encode(String input) {
        int encodedLength = (int) Math.ceil(input.length() / 4d * 3d);
        byte[] result = new byte[encodedLength];
        
        for (int i = 0; i < input.length(); i += 4) {
            byte c1 = (i < input.length()) ? encode(input.charAt(i)) : 0;
            byte c2 = ((i + 1) < input.length()) ? encode(input.charAt(i + 1)) : 0;
            byte c3 = ((i + 2) < input.length()) ? encode(input.charAt(i + 2)) : 0;
            byte c4 = ((i + 3) < input.length()) ? encode(input.charAt(i + 3)) : 0;

            int targetIndex = (i / 4 * 3);

            result[targetIndex] = (byte) ((c1 & 0x3F) << 2);
            result[targetIndex] |= (byte) ((c2 & 0x3F) >> 4);
            
            if ((targetIndex + 1) < encodedLength) {
                result[targetIndex + 1] = (byte) ((c2 & 0x3F) << 4);
                result[targetIndex + 1] |= (byte) ((c3 & 0x3F) >> 2);
            }
            
            if ((targetIndex + 2) < encodedLength) {
                result[targetIndex + 2] = (byte) ((c3 & 0x3F) << 6);
                result[targetIndex + 2] |= (byte) (c4 & 0x3F);
            }
        }

        return result;
    }

    public static String decode(byte[] input) {
        // Build bit string from all input bytes
        StringBuilder sb = new StringBuilder();
        for (byte b : input) {
            String paddedBinary = String.format("%8s", Integer.toBinaryString(b & 0xFF)).replace(" ", "0");
            sb.append(paddedBinary);
        }
        
        String bitString = sb.toString();
        StringBuilder result = new StringBuilder();
        
        // Decode 6-bit symbols
        for (int i = 0; i + 5 < bitString.length(); i += 6) {
            String symbolBits = bitString.substring(i, i + 6);
            byte symbolValue = (byte) Integer.parseInt(symbolBits, 2);
            if (symbolValue > 0) {
                result.append(decode(symbolValue));
            }
        }
        
        return result.toString();
    }
}
