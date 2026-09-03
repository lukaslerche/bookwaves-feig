package de.bookwaves.sync;

import java.util.Map;
import java.util.TreeSet;

import static java.util.Map.entry;

/**
 * Translation between the fractional output power in {@code config.yaml} and the code
 * the reader stores.
 *
 * <p>The reader encodes output power as ten steps, {@code 0x10} through {@code 0x19}.
 * There is no code for switching an antenna off, so {@code 0.0} is not a valid value.
 */
public final class OutputPowerCodec {

    private static final Map<Double, Integer> POWER_TO_CODE = Map.ofEntries(
        entry(0.1, 0x10),
        entry(0.2, 0x11),
        entry(0.3, 0x12),
        entry(0.4, 0x13),
        entry(0.5, 0x14),
        entry(0.6, 0x15),
        entry(0.7, 0x16),
        entry(0.8, 0x17),
        entry(0.9, 0x18),
        entry(1.0, 0x19)
    );

    private OutputPowerCodec() {
    }

    /** Whether {@code power} is one of the values the reader can store. */
    public static boolean isSupported(double power) {
        return POWER_TO_CODE.containsKey(power);
    }

    /**
     * The reader code for a fractional output power.
     *
     * @throws IllegalArgumentException if the value is not one the reader supports
     */
    public static int code(double power) {
        Integer code = POWER_TO_CODE.get(power);
        if (code == null) {
            throw new IllegalArgumentException(
                "Output power " + power + " is not supported; supported values are " + supportedValues());
        }
        return code;
    }

    /** The supported output power values, ascending. */
    public static TreeSet<Double> supportedValues() {
        return new TreeSet<>(POWER_TO_CODE.keySet());
    }
}
