package de.bookwaves.sync;

/**
 * The storage type of a reader configuration parameter.
 *
 * <p>The SDK exposes one {@code getConfigPara}/{@code changeConfigPara} overload per
 * type, so a parameter must be read and written through the overload matching how the
 * reader stores it.
 */
public enum ParamType {
    /** Single bit. */
    BOOL,
    /** Unsigned 8-bit value, 0..255. */
    BYTE,
    /** Multi-byte numeric value. */
    LONG,
    /** Text value, such as a host address. */
    STRING
}
