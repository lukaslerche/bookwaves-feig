package de.bookwaves.sync;

import de.bookwaves.ReaderManager.ReaderOperationException;

/**
 * The reader configuration operations the synchronisation engine needs.
 *
 * <p>Implementations throw on failure rather than returning a status code, so a failed
 * call cannot be mistaken for a successful one.
 */
public interface ReaderConfigPort {

    /**
     * Loads the reader's configuration into the SDK's local copy.
     * Must be called before any {@link #get} or {@link #set}.
     */
    void readCompleteConfiguration() throws ReaderOperationException;

    /**
     * Whether this reader supports the given parameter. Parameter trees differ between
     * reader generations, so a parameter meaningful on one reader may not exist on another.
     */
    boolean supports(String parameter);

    /** Reads one parameter, using the overload matching {@code type}. */
    ParamValue get(String parameter, ParamType type) throws ReaderOperationException;

    /** Writes one parameter to the SDK's local copy. */
    void set(String parameter, ParamValue value) throws ReaderOperationException;

    /**
     * Transfers the local copy to the reader.
     *
     * @param persistent whether the change is written to EEPROM and so survives a
     *                   reader restart
     */
    void apply(boolean persistent) throws ReaderOperationException;
}
