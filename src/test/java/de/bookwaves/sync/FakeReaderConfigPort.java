package de.bookwaves.sync;

import de.bookwaves.ReaderConfig;
import de.bookwaves.ReaderManager.ReaderOperationException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An in-memory reader used to test synchronisation without hardware.
 *
 * <p>It holds parameter values the way a reader does, records what was written, and
 * can be told to refuse a particular write or to not support a particular parameter.
 */
class FakeReaderConfigPort implements ReaderConfigPort {

    private final Map<String, ParamValue> values = new LinkedHashMap<>();
    private final Set<String> unsupported = new HashSet<>();
    private final List<String> writes = new ArrayList<>();

    private String failWriteOf;
    private int readCount;
    private int applyCount;
    private Boolean lastApplyPersistent;

    /** Seeds every parameter the profile wants at exactly the configured value. */
    static FakeReaderConfigPort inSyncWith(ReaderProfile profile, ReaderConfig config, String hostName) {
        FakeReaderConfigPort port = new FakeReaderConfigPort();
        for (ParamSpec spec : profile.parametersFor(config, hostName)) {
            port.values.put(spec.name(), spec.desired());
        }
        return port;
    }

    /** Overrides one parameter's value, simulating configuration drift. */
    FakeReaderConfigPort with(String parameter, ParamValue value) {
        values.put(parameter, value);
        return this;
    }

    /** Marks a parameter as absent on this reader. */
    FakeReaderConfigPort without(String parameter) {
        unsupported.add(parameter);
        return this;
    }

    /** Makes the next write of {@code parameter} fail, as a reader rejecting a value would. */
    FakeReaderConfigPort failingWriteOf(String parameter) {
        this.failWriteOf = parameter;
        return this;
    }

    List<String> writes() {
        return List.copyOf(writes);
    }

    ParamValue valueOf(String parameter) {
        return values.get(parameter);
    }

    int readCount() {
        return readCount;
    }

    int applyCount() {
        return applyCount;
    }

    Boolean lastApplyPersistent() {
        return lastApplyPersistent;
    }

    @Override
    public void readCompleteConfiguration() {
        readCount++;
    }

    @Override
    public boolean supports(String parameter) {
        return !unsupported.contains(parameter);
    }

    @Override
    public ParamValue get(String parameter, ParamType type) throws ReaderOperationException {
        ParamValue value = values.get(parameter);
        if (value == null) {
            throw new ReaderOperationException("Fake reader has no value seeded for " + parameter);
        }
        if (value.type() != type) {
            throw new ReaderOperationException(
                "Fake reader holds " + parameter + " as " + value.type() + ", read as " + type);
        }
        return value;
    }

    @Override
    public void set(String parameter, ParamValue value) throws ReaderOperationException {
        if (ProtectedParameters.isProtected(parameter)) {
            throw new ReaderOperationException(ProtectedParameters.refusalMessage(parameter));
        }
        if (parameter.equals(failWriteOf)) {
            throw new ReaderOperationException("Fake reader refused to write " + parameter);
        }
        values.put(parameter, value);
        writes.add(parameter);
    }

    @Override
    public void apply(boolean persistent) {
        applyCount++;
        lastApplyPersistent = persistent;
    }
}
