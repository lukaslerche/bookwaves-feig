package de.bookwaves.sync;

import de.bookwaves.ReaderConfig;

import java.util.List;

/**
 * What a generation of reader expects its configuration to look like.
 *
 * <p>Generations use different parameter names for the same concepts, which is why
 * this is a profile rather than a fixed set of constants.
 */
public interface ReaderProfile {

    /** The name used in {@code config.yaml}'s {@code type} key. */
    String id();

    /** Whether readers of this generation support username and password login. */
    boolean supportsAuthentication();

    /**
     * The parameters this configuration implies, in the order they should be written.
     *
     * @param config   the reader's YAML configuration
     * @param hostName the address the reader should send notifications to, or
     *                 {@code null} when it is not configured
     */
    List<ParamSpec> parametersFor(ReaderConfig config, String hostName);
}
