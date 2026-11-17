# BookWaves Feig

A Java-based middleware for Feig Electronic RFID readers. It speaks to Feig hardware over TCP/IP
and exposes reader discovery, inventory, tag operations and live tag events over HTTP, so that it
can be used as a **middleware** for [BookWaves](https://github.com/lukaslerche/bookwaves). BookWaves
never talks to a reader directly — every RFID operation goes through a middleware like this one,
registered against a BookWaves installation with `type: http` (or the older `type: feig`).

## Documentation

Full documentation — deployment, reader configuration, supported tag formats, the complete HTTP API
and troubleshooting — lives at **https://docs.bookwaves.de**. This README covers only what's needed
to build and run this repository. Relevant starting points:

- [Supported readers and tag formats](https://docs.bookwaves.de/rfid-hardware/supported-readers-and-tag-formats/)
- [Configuring a Feig reader](https://docs.bookwaves.de/rfid-hardware/configuring-a-feig-reader/)
- [Host and notification operating modes](https://docs.bookwaves.de/rfid-hardware/host-and-notification-operating-modes/)
- [BookWaves Feig configuration reference](https://docs.bookwaves.de/deploy/bookwaves-feig-configuration-reference/)
- [BookWaves Feig HTTP reference](https://docs.bookwaves.de/extend/feig-http-reference/)
- [Production deployment](https://docs.bookwaves.de/deploy/production-deployment/) (container
  networking with BookWaves, reverse proxy and TLS, secrets)
- [Troubleshooting](https://docs.bookwaves.de/operate/troubleshooting/)

### Repository-specific additions

Local documentation covers features added after the documentation-site migration:

- [Software-defined RSSI filtering](docs/adr/0002-filter-signal-strength-in-this-service.md)

For readers with active software RSSI thresholds, `GET /readers` includes
`rssiFilterHealth`, separate from configuration sync and notification status.
Its state is `NOT_YET_OBSERVED` after service startup, `APPLIED` after Feig
has used a threshold and no bypass is outstanding, or `DEGRADED` when a tag
observation passed through without filtering. Check `affectedAntennas` and
`unknownAntennaReasons` for missing or invalid RSSI, then check the reader's
notification selectors, configured antennas, and notification connection.
Recovery requires applying the threshold again on the affected antennas (all
active antennas if the bypass antenna was unknown). Reconnects do not clear
warnings; service restarts reset the in-memory status.
  (`rssiFilterMode: software` is the default; managed readers must synchronise successfully
  before use, including after reconnecting)
- [Domain context](CONTEXT.md) and [architecture decisions](docs/adr/)

## Quick Start (Docker)

```bash
cp config.example.yaml config.yaml
# Edit config.yaml with your reader details and passwords — see the configuration reference above.
```

**Never commit `config.yaml` to version control** — it contains sensitive passwords. The service
also validates configured passwords on startup and logs a warning for any left at a placeholder
value.

Run the pre-built image:

```bash
docker run -d \
  -p 7070:7070 \
  -p 20001-20020:20001-20020 \
  -v $(pwd)/config.yaml:/app/config/config.yaml:ro \
  ghcr.io/lukaslerche/bookwaves-feig:latest
```

Or with the provided `docker-compose.yml`:

```bash
docker compose up -d
```

The service is then available at `http://localhost:7070`. For reader configuration, operating
modes and the full configuration and HTTP references, see [Documentation](#documentation) above.

## Requirements

- Java 21 or newer (64-bit platforms only)
- Maven
- Feig SDK files — proprietary; see [FEIG SDK Setup](#feig-sdk-setup) below
- OpenSSL 3.2+ on Linux, for the SDK's TLS features
- Feig RFID readers with a TCP/IP network interface

The pre-built Docker image bundles the native dependencies above; these requirements apply when
building or running the service from source.

## FEIG SDK Setup

Before running the application from source, you must obtain the Feig SDK files.

1. **Download the Feig SDK** from the official Feig Electronic website:
   - Navigate to the SDK download section for Java
   - Download the latest Java SDK package (64-bit Linux version required)
   - Note: Current version used is: `FEIG.ID.SDK.Gen3.Java-v7.1.0`

2. **Extract required files** to the project directories:

   **JAR files** (`libs/` directory):
   ```
   libs/fedm-funit-java-api-1.1.0.jar
   libs/fedm-java-api-7.1.0.jar
   libs/fedm-service-java-api-11.2.1.jar
   ```

   **Native libraries** (`native/linux.x64/` directory):
   ```
   native/linux.x64/install.sh
   native/linux.x64/libfecom.so.5.1.0
   native/linux.x64/libfedm-funit.so.1.1.0
   native/linux.x64/libfedm-funit4j.so.1.1.0
   native/linux.x64/libfedm-service.so.11.2.1
   native/linux.x64/libfedm-service4j.so.11.2.1
   native/linux.x64/libfedm.so.7.1.0
   native/linux.x64/libfedm4j.so.7.1.0
   native/linux.x64/libfeisp.so.1.5.1
   native/linux.x64/libfetls.so.0.9.0
   native/linux.x64/libfeudp.so.1.0.0
   native/linux.x64/libfeusb2.so.1.1.0
   ```

3. **Create symbolic links** by running the provided install script:

   ```bash
   cd native/linux.x64
   chmod +x install.sh
   ./install.sh
   ```

   This creates the required `.so` and `.so.X` symbolic links pointing to the versioned files.

4. **Verify directory structure** after running install.sh:

   ```
   .
   ├── libs/
   │   ├── fedm-funit-java-api-1.1.0.jar
   │   ├── fedm-java-api-7.1.0.jar
   │   └── fedm-service-java-api-11.2.1.jar
   ├── native/
   │   └── linux.x64/
   │       ├── install.sh
   │       ├── libfecom.so -> libfecom.so.5
   │       ├── libfecom.so.5 -> libfecom.so.5.1.0
   │       ├── libfecom.so.5.1.0
   │       ├── libfedm.so -> libfedm.so.7
   │       ├── libfedm.so.7 -> libfedm.so.7.1.0
   │       ├── libfedm.so.7.1.0
   │       └── (etc. - all libraries with symlinks)
   └── ...
   ```

**Note:** These files are proprietary and cannot be included in the repository. You must obtain
them directly from Feig Electronic.

**Troubleshooting native library setup:** if the application fails to start with an error related
to loading a native library, `LD_LIBRARY_PATH` isn't pointing at `native/linux.x64` (Linux) or the
symlinks from step 3 above are missing. Check the startup logs for `java.library.path` to see what
Java is actually searching.

## Development

The following sections are for developers who want to build, modify, or contribute to the project.

### Local Configuration

```bash
cp config.example.yaml config.yaml
# Edit config.yaml with your reader settings
```

### Running the Application

Use the provided helper script:

```bash
./run.sh
```

The script automatically:

- Compiles the project
- Copies Maven runtime dependencies into `target/dependency`
- Sets the required runtime environment variables
- Starts `de.bookwaves.Main`

### Manual Execution (Optional)

For debugging or custom workflows, you can also run the application manually:

```bash
mvn compile dependency:copy-dependencies

export CONFIG_FILE_PATH=$(pwd)/config.yaml
export LD_LIBRARY_PATH=$(pwd)/native/linux.x64

java -cp "target/classes:target/dependency/*:libs/*" de.bookwaves.Main
```

You can also use the provided VS Code launch configuration.

### Running the tests

```bash
mvn test                # unit tests; no reader needed
mvn test -Phardware     # against the readers in config.hardware.yaml
```

`config.hardware.yaml` is gitignored and has the same shape as `config.yaml`. Copy
`config.example.yaml` to it and point it at the readers to test. Without that file, or
when a reader does not answer, or when the SDK's native library is missing, the
hardware tests are skipped with a reason rather than failed — so `-Phardware` is safe to
run anywhere.

The hardware run **writes to the readers**. It brings each one into agreement with
`config.hardware.yaml`, then mutates one RSSI filter to prove drift is detected and only
that parameter is repaired. Every write is permanent — no tested reader has a RAM-only
configuration bank — so a run that crashes midway leaves the reader changed. The
mutation prints the value it displaces before writing it, so it can be put back by hand
from the output. Point `config.hardware.yaml` at test readers, not at a live gate.

### Probing a reader

`probe.sh` reports which configuration parameters a physical reader actually supports, so
the synchronisation profiles can be checked against hardware rather than against the SDK's
parameter names. It reads the reader named in `config.yaml` and is read-only unless told
otherwise:

```bash
./probe.sh gate-in > probe-gate-in.txt 2>&1        # read-only
./probe.sh gate-in --write                          # also step one RSSI filter and put it back
./probe.sh gate-in --write --leave-written          # step it and leave the change in place
./probe.sh gate-in --set <parameter>=<value>        # write one numeric parameter exactly
```

Every write is permanent, as above.

### Developing with Devcontainer

The devcontainer provides a preconfigured Java and Maven development environment.

#### Usage

1. **Open in VS Code** with the Dev Containers extension installed

2. **Reopen in Container** - VS Code will prompt to reopen in the devcontainer, or use:
   - Command Palette (`Cmd+Shift+P` / `Ctrl+Shift+P`)
   - Select: "Dev Containers: Reopen in Container"

3. **Ensure the FEIG SDK files are available** as described in the FEIG SDK Setup section above.

4. **Create your configuration** if you have not already:

   ```bash
   cp config.example.yaml config.yaml
   # Edit config.yaml with your reader settings
   ```

5. **Run the application**:

   ```bash
   ./run.sh
   ```

   You can also use the provided VS Code launch configuration.

### Building the Docker Image

#### Prerequisites

The same SDK files are required for Docker builds:

1. **Ensure SDK files are in place** as described in [FEIG SDK Setup](#feig-sdk-setup) above
   - `libs/` - JAR files
   - `native/linux.x64/` - Native library files with symlinks

2. **Install Docker** with buildx support (included in Docker Desktop)

3. **Authenticate with GitHub Container Registry** (for pushing images):
   ```bash
   echo $GITHUB_TOKEN | docker login ghcr.io -u USERNAME --password-stdin
   ```

   Or create a Personal Access Token (PAT) with `write:packages` scope.

#### Build for Local Testing

```bash
docker buildx build --platform linux/amd64 --tag ghcr.io/lukaslerche/bookwaves-feig:latest --load .
```

**Note:** The `--platform linux/amd64` flag is required because the native libraries are platform-specific. The `--load` flag imports the image into your local Docker daemon for testing.

#### Push to GitHub Container Registry

1. **Build and push** with version tags:
   ```bash
   docker buildx build --platform linux/amd64 \
     --tag ghcr.io/lukaslerche/bookwaves-feig:latest \
     --tag ghcr.io/lukaslerche/bookwaves-feig:2.0.0 \
     --push .
   ```

2. **Verify the push** by checking the GitHub Container Registry:
   - Navigate to your GitHub profile → Packages
   - Find `bookwaves-feig` package

3. **Make the package public** (optional):
   - Go to package settings
   - Change visibility to public

**Note:** Currently only `linux/amd64` is supported because the native libraries are x64-specific. Additional platforms would require corresponding native libraries from Feig.

### Environment Variables

- `CONFIG_FILE_PATH` - Path to configuration file (optional in Docker, defaults to `/app/config/config.yaml`)
- `LD_LIBRARY_PATH` - Path to native libraries (set automatically in Dockerfile)

### Code architecture

A brief map of the main classes, for orientation when reading the source (this is not covered by
the documentation site, which documents behaviour rather than internal code structure):

- **ReaderManager** - Central registry for all readers
- **ManagedReader** - Wraps ReaderModule with mode-specific operations
- **TagFactory** - Auto-detects and creates appropriate Tag instances
- **ConfigLoader** - YAML configuration parser
