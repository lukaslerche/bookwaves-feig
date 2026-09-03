package de.bookwaves.sync;

import de.bookwaves.ReaderConfig;
import de.bookwaves.ReaderManager.ReaderOperationException;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compares a reader's live configuration against the YAML configuration and repairs
 * what has drifted. Only parameters that actually differ are written.
 */
public class ConfigurationSync {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationSync.class);

    private final ReaderProfile profile;
    private final String hostName;
    private final boolean persistent;

    /**
     * @param profile    the generation profile describing this reader's parameters
     * @param hostName   the address the reader should send notifications to, or null
     * @param persistent whether applied changes survive a reader power cycle
     */
    public ConfigurationSync(ReaderProfile profile, String hostName, boolean persistent) {
        this.profile = profile;
        this.hostName = hostName;
        this.persistent = persistent;
    }

    /** Reads the reader's configuration and reports which parameters have drifted. */
    public SyncReport check(ReaderConfigPort port, ReaderConfig config) throws ReaderOperationException {
        port.readCompleteConfiguration();
        return new SyncReport(config.getName(), findDrift(port, config), List.of());
    }

    /**
     * Repairs the reader so it matches its configuration.
     *
     * @param force write every parameter, whether or not it differs
     * @return what was found and what was written
     */
    public SyncReport apply(ReaderConfigPort port, ReaderConfig config, boolean force)
            throws ReaderOperationException {
        port.readCompleteConfiguration();

        List<SyncReport.Drift> drifts = findDrift(port, config);
        List<ParamSpec> toWrite = force
            ? profile.parametersFor(config, hostName)
            : drifts.stream().map(SyncReport.Drift::spec).toList();

        if (toWrite.isEmpty()) {
            log.debug("Reader {}: configuration matches, nothing to write", config.getName());
            return new SyncReport(config.getName(), drifts, List.of());
        }

        List<String> written = new ArrayList<>();
        for (ParamSpec spec : toWrite) {
            refuseIfProtected(spec.name());
            log.debug("Reader {}: setting {} to {}", config.getName(), spec.name(), spec.desired().describe());
            port.set(spec.name(), spec.desired());
            written.add(spec.name());
        }
        port.apply(persistent);

        log.info("Reader {}: wrote {} parameter(s){}", config.getName(), written.size(),
            persistent ? " persistently" : " until the reader restarts");
        return new SyncReport(config.getName(), drifts, written);
    }

    private List<SyncReport.Drift> findDrift(ReaderConfigPort port, ReaderConfig config)
            throws ReaderOperationException {
        List<SyncReport.Drift> drifts = new ArrayList<>();

        for (ParamSpec spec : profile.parametersFor(config, hostName)) {
            refuseIfProtected(spec.name());

            if (!port.supports(spec.name())) {
                throw new ReaderOperationException(
                    "Reader " + config.getName() + " does not support parameter " + spec.name()
                        + " required by the " + profile.id() + " profile (" + spec.purpose() + ")");
            }

            ParamValue actual = port.get(spec.name(), spec.type());
            if (!spec.desired().equals(actual)) {
                drifts.add(new SyncReport.Drift(spec, actual));
            }
        }
        return drifts;
    }

    private void refuseIfProtected(String parameter) throws ReaderOperationException {
        if (ProtectedParameters.isProtected(parameter)) {
            throw new ReaderOperationException(ProtectedParameters.refusalMessage(parameter));
        }
    }
}
