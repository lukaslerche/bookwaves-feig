package de.bookwaves.tag;

import java.util.HashMap;
import java.util.Map;

/**
 * URN Code40 encoding/decoding utility.
 * Encodes 40 characters (A-Z, 0-9, space, -, ., :) into compact binary format.
 */
class URNCode40 {

    private static final Map<Character, Integer> ENCODE_MAP = Map.ofEntries(
        Map.entry(' ', 0), Map.entry('A', 1), Map.entry('B', 2), Map.entry('C', 3),
        Map.entry('D', 4), Map.entry('E', 5), Map.entry('F', 6), Map.entry('G', 7),
        Map.entry('H', 8), Map.entry('I', 9), Map.entry('J', 10), Map.entry('K', 11),
        Map.entry('L', 12), Map.entry('M', 13), Map.entry('N', 14), Map.entry('O', 15),
        Map.entry('P', 16), Map.entry('Q', 17), Map.entry('R', 18), Map.entry('S', 19),
        Map.entry('T', 20), Map.entry('U', 21), Map.entry('V', 22), Map.entry('W', 23),
        Map.entry('X', 24), Map.entry('Y', 25), Map.entry('Z', 26), Map.entry('-', 27),
        Map.entry('.', 28), Map.entry(':', 29), Map.entry('0', 30), Map.entry('1', 31),
        Map.entry('2', 32), Map.entry('3', 33), Map.entry('4', 34), Map.entry('5', 35),
        Map.entry('6', 36), Map.entry('7', 37), Map.entry('8', 38), Map.entry('9', 39)
    );

    private static final Map<Integer, Character> DECODE_MAP = reverseMap(ENCODE_MAP);

    public static byte[] encode(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Input text is empty");
        }

        int charCount = 0;
        int currentSum = 1;
        byte[] encoded = new byte[((input.length() - 1) / 3 + 1) * 2];
        int arrayPointer = 0;

        for (int i = 0; i < input.length(); i++) {
            if (charCount == 3) {
                byte[] twoBytes = intToByteArray(currentSum);
                System.arraycopy(twoBytes, 0, encoded, arrayPointer * 2, twoBytes.length);
                arrayPointer++;
                charCount = 0;
                currentSum = 1;
            }

            Integer charValue = ENCODE_MAP.get(input.charAt(i));
            if (charValue == null) {
                throw new IllegalArgumentException("Character not in Code40 table: " + input.charAt(i));
            }

            currentSum += charValue * Math.pow(40, 2 - (i % 3));
            charCount++;
        }

        byte[] twoBytes = intToByteArray(currentSum);
        System.arraycopy(twoBytes, 0, encoded, arrayPointer * 2, twoBytes.length);
        return encoded;
    }

    public static String decode(byte[] input) {
        if (input == null || input.length % 2 != 0) {
            throw new IllegalArgumentException("Input must be even length");
        }

        StringBuilder result = new StringBuilder();

        for (int i = 0; i < input.length; i += 2) {
            int higher = (input[i] & 0xFF);
            int lower = (input[i + 1] & 0xFF);
            int value = higher * 256 + lower;

            int char1 = 0;
            int char2 = 0;
            int char3;

            if (value > 1600) {
                int rest = value % 1600;
                char1 = (value - rest) / 1600;
                value = rest;
            }
            if (value > 40) {
                int rest = value % 40;
                char2 = (value - rest) / 40;
                value = rest;
            }
            char3 = value - 1;

            Character c1 = DECODE_MAP.get(char1);
            Character c2 = DECODE_MAP.get(char2);
            Character c3 = DECODE_MAP.get(char3);
            
            if (c1 == null || c2 == null || c3 == null) {
                throw new IllegalStateException(String.format(
                    "Invalid Code40 sequence: %d,%d,%d from value 0x%04X",
                    char1, char2, char3, (higher * 256 + lower)
                ));
            }
            
            result.append(c1).append(c2).append(c3);
        }
        
        return result.toString();
    }

    private static byte[] intToByteArray(int x) {
        return new byte[] { (byte) ((x >> 8) & 0xFF), (byte) (x & 0xFF) };
    }

    private static <K, V> Map<V, K> reverseMap(Map<K, V> map) {
        Map<V, K> reversed = new HashMap<>();
        for (Map.Entry<K, V> entry : map.entrySet()) {
            reversed.put(entry.getValue(), entry.getKey());
        }
        return reversed;
    }
}
