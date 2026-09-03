package de.bookwaves.sync;

/**
 * One configuration parameter a profile wants set to a particular value.
 *
 * @param name    the dotted SDK parameter name, for example {@code OperatingMode.Mode}
 * @param desired the value the YAML configuration asks for
 * @param purpose a short description used in drift and failure messages
 */
public record ParamSpec(String name, ParamValue desired, String purpose) {

    public ParamSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Parameter name must not be blank");
        }
        if (desired == null) {
            throw new IllegalArgumentException("Parameter " + name + " must declare a desired value");
        }
    }

    /** The type this parameter is read and written as. */
    public ParamType type() {
        return desired.type();
    }

    @Override
    public String toString() {
        return name + "=" + desired.describe();
    }
}
