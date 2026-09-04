package de.bookwaves.sync;

import java.util.Map;
import java.util.TreeSet;

import static java.util.Map.entry;

/**
 * Translation between the fractional output power in {@code config.yaml} and the code
 * the reader stores.
 *
 * <p>Shared codes mean the same power, but the generations reach different maxima, so a
 * value is only valid against the profile that has to store it. Neither has a code for
 * switching an antenna off.
 */
public enum OutputPowerCodec {

    /** The current generation, for example the MRU400X. */
    NEW_GEN(Map.ofEntries(
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
    )),

    /** The older generation, for example the MRU102, whose full power is 0.5 W. */
    OLD_GEN(Map.ofEntries(
        entry(0.05, 0x08),
        entry(0.1, 0x10),
        entry(0.2, 0x11),
        entry(0.3, 0x12),
        entry(0.4, 0x13),
        entry(0.5, 0x14)
    ));

    private final Map<Double, Integer> powerToCode;

    OutputPowerCodec(Map<Double, Integer> powerToCode) {
        this.powerToCode = powerToCode;
    }

    /** Whether {@code power} is one of the values this generation can store. */
    public boolean isSupported(double power) {
        return powerToCode.containsKey(power);
    }

    /**
     * The reader code for a fractional output power.
     *
     * @throws IllegalArgumentException if the value is not one this generation supports
     */
    public int code(double power) {
        Integer code = powerToCode.get(power);
        if (code == null) {
            throw new IllegalArgumentException(
                "Output power " + power + " is not supported; supported values are " + supportedValues());
        }
        return code;
    }

    /** The supported output power values, ascending. */
    public TreeSet<Double> supportedValues() {
        return new TreeSet<>(powerToCode.keySet());
    }
}
