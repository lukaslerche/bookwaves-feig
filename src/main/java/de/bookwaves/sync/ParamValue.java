package de.bookwaves.sync;

/**
 * The value of a single reader configuration parameter.
 *
 * <p>Desired and actual values are both built here from the type declared by the
 * parameter's {@link ParamSpec}, so comparing them is a record equality between two
 * values of the same shape.
 *
 * <p>Numeric values are held as {@code long}. Reader byte parameters are unsigned, so
 * a value such as {@code 0xC0} stays 192 here and is narrowed only at the SDK boundary;
 * holding it in a Java {@code byte} would make it -64.
 */
public sealed interface ParamValue {

    /** The type this value is stored as on the reader. */
    ParamType type();

    /** A short rendering for log and error messages. */
    String describe();

    /** A boolean parameter. */
    static ParamValue bool(boolean value) {
        return new Numeric(ParamType.BOOL, value ? 1 : 0);
    }

    /** An unsigned byte parameter. {@code value} must be in 0..255. */
    static ParamValue ofByte(int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("Byte parameter value out of range 0..255: " + value);
        }
        return new Numeric(ParamType.BYTE, value);
    }

    /** A multi-byte numeric parameter. */
    static ParamValue ofLong(long value) {
        return new Numeric(ParamType.LONG, value);
    }

    /** A text parameter. */
    static ParamValue text(String value) {
        return new Text(value == null ? "" : value);
    }

    /** A numeric value: a bool held as 0 or 1, an unsigned byte, or a long. */
    record Numeric(ParamType type, long value) implements ParamValue {
        public Numeric {
            if (type == ParamType.STRING) {
                throw new IllegalArgumentException("STRING is not a numeric parameter type");
            }
        }

        /** The value narrowed for the SDK's unsigned byte overloads. */
        public byte asByte() {
            return (byte) value;
        }

        /** Whether a bool parameter is set. */
        public boolean asBoolean() {
            return value != 0;
        }

        @Override
        public String describe() {
            return switch (type) {
                case BOOL -> Boolean.toString(asBoolean());
                case BYTE -> String.format("0x%02X", value);
                default -> Long.toString(value);
            };
        }
    }

    /** A text value. */
    record Text(String value) implements ParamValue {

        @Override
        public ParamType type() {
            return ParamType.STRING;
        }

        @Override
        public String describe() {
            return "\"" + value + "\"";
        }
    }
}
