# Feig RFID Reader Service

A Java-based RFID reader service using the Feig SDK, providing REST API endpoints for tag operations and multi-reader management.

## Features

- **Multi-reader support** - Manage multiple RFID readers simultaneously
- **Auto-detection** - Supports DE290, DE290F, DE386, DE6, and BR tag formats
- **Configurable passwords** - Per-tag-type password management
- **Host & Notification modes** - Support for both polling and event-driven tag detection
- **Tag initialization** - Write new tags with proper format and security
- **Tag editing** - Update media IDs on existing tags
- **Security operations** - Lock/unlock, secure/unsecure tags
- **Tag analysis** - Deep inspection of tag memory and security status

## Quick Start (Docker)

The easiest way to run the service is using the pre-built Docker image:

### 1. Create Configuration File

**IMPORTANT:** This application requires an external configuration file. No default configuration is embedded.

```bash
# Copy the example configuration
cp config.yaml.example config.yaml

# Edit config.yaml with your reader details and passwords
```

**Never commit `config.yaml` to version control** (it contains sensitive passwords)

### 2. Run with Docker

```bash
docker run -d \
  -p 7070:7070 \
  -v $(pwd)/config.yaml:/app/config/config.yaml:ro \
  ghcr.io/lukaslerche/bookwaves-feig:latest
```

Or use Docker Compose with the provided `docker-compose.yml`:

```bash
docker compose up -d
```

The service will be available at `http://localhost:7070`

## Configuration

### Configuration File Format

See `config.yaml.example` for the complete configuration template.

**Tag passwords by type:**
- `DE290Tag.access` / `DE290Tag.kill` - For DE290 tags
- `DE6Tag.access` / `DE6Tag.kill` - For DE6 tags
- `DE386Tag.access` / `DE386Tag.kill` - For DE386 tags
- `BRTag.secret` - For BR (Smartfreq) tags

**Default tag format** - Used for tag initialization (DE290, DE6, DE290F, or DE386)

**Reader configurations:**
- Name, IP address, port
- Operating mode: `host` or `notification`
- Antenna numbers (as array)

### Example Configuration

```yaml
# Global tag password configuration
tagPasswords:
  DE290Tag.access: "12345678"
  DE290Tag.kill: "87654321"
  DE6Tag.access: "AABBCCDD"
  DE6Tag.kill: "EEFF0011"
  DE386Tag.access: "11223344"
  DE386Tag.kill: "55667788"
  BRTag.secret: "secret-key"

# Default tag format for initialization
defaultTagFormat: DE290

readers:
  - name: "reader1"
    address: "192.168.1.225"
    port: 10001
    mode: host
    antennas: [4]
  - name: "reader2"
    address: "192.168.1.70"
    port: 10001
    mode: notification
    antennas: [1,2]
```

**Mode values:**
- `host` - Polling mode for manual inventory scans
- `notification` - Event-driven automatic tag detection

**Important notes:**
- Only configure passwords for tag formats you actually use
- Replace placeholder passwords with your actual tag passwords

## API Endpoints

Base URL: `http://localhost:7070`

### Health Check

#### Get Service Status
```http
GET /
```
Returns "Hello Feig!" to verify service is running.

#### Test Endpoint
```http
GET /test
```
Returns "Test successful" for connectivity testing.

### Reader Management

#### List Readers
```http
GET /readers
```

Returns all configured readers with their status and configuration.

**Response:**
```json
{
  "success": true,
  "readerCount": 2,
  "readers": [
    {
      "name": "reader1",
      "address": "192.168.1.225",
      "port": 10001,
      "mode": "host",
      "antennas": [1, 2, 3, 4],
      "antennaMask": "0x0F",
      "isConnected": true,
      "connectionStatus": "connected",
      "notificationActive": false
    }
  ]
}
```

### Tag Operations

#### Inventory Scan
```http
GET /inventory/{readerName}
```

Performs a single inventory scan and returns all detected tags with their format-specific information.

**Response:**
```json
{
  "success": true,
  "message": "Inventory successful",
  "count": 2,
  "tags": [
    {
      "tagType": "DE290Tag",
      "epc": "3034257BF468D4800000162E",
      "pc": "3400",
      "mediaId": "5678",
      "secured": true,
      "rssiValues": [
        {
          "antenna": 1,
          "rssi": -42
        }
      ]
    }
  ]
}
```

#### Initialize Tag
```http
POST /initialize/{readerName}?mediaId={mediaId}&format={format}&secured={secured}
```

Initialize a blank tag with specified format and media ID. Writes EPC, passwords, and locks memory.

**Query Parameters:**
- `mediaId` (required) - Media identifier (format depends on tag type)
- `format` (optional) - Tag format: DE290, DE6, DE290F, or DE386 (defaults to configured defaultTagFormat)
- `secured` (optional) - Security bit value: true or false (default: true)

**Response:**
```json
{
  "success": true,
  "message": "Tag initialized successfully",
  "epc": "3034257BF468D4800000162E",
  "pc": "3400",
  "mediaId": "5678",
  "secured": true,
  "format": "DE290",
  "tagType": "DE290Tag"
}
```

**Errors:**
- `400` - Invalid mediaId format or unsupported tag format
- `404` - Reader not found
- `500` - Initialization failed (no tag in field, multiple tags, write error)

#### Edit Tag
```http
POST /edit/{readerName}?epc={epc}&mediaId={newMediaId}
```

Update the media ID on an existing formatted tag. Automatically handles password changes and memory updates.

**Query Parameters:**
- `epc` (required) - Current EPC hex string of the tag
- `mediaId` (required) - New media identifier

**Response:**
```json
{
  "success": true,
  "message": "Tag updated successfully",
  "oldEpc": "3034257BF468D4800000162E",
  "newEpc": "3034257BF468D480000019C8",
  "mediaId": "6600",
  "tagType": "DE290Tag"
}
```

**Errors:**
- `400` - Invalid EPC, unrecognized format, or invalid mediaId
- `404` - Reader not found
- `500` - Update failed (tag not found, write error)

#### Clear Tag
```http
POST /clear/{readerName}?epc={epc}
```

Reset a tag to factory state: zeros passwords, restores TID as EPC, and unlocks memory.

**Query Parameters:**
- `epc` (required) - EPC hex string of tag to clear

**Response:**
```json
{
  "success": true,
  "message": "Tag cleared successfully - passwords zeroed and EPC restored to TID",
  "oldEpc": "3034257BF468D4800000162E",
  "newEpc": "E280689400005003F76A18ED",
  "newPc": "3000",
  "tid": "E280689400005003F76A18ED"
}
```

#### Secure Tag
```http
POST /secure/{readerName}?epc={epc}
```

Set the security bit on a tag (marks tag as secured/locked in circulation).

**Query Parameters:**
- `epc` (required) - EPC hex string of tag to secure

**Response:**
```json
{
  "success": true,
  "message": "Tag secured successfully",
  "epc": "3034257BF468D4800000162E",
  "tagType": "DE290Tag",
  "secured": true
}
```

#### Unsecure Tag
```http
POST /unsecure/{readerName}?epc={epc}
```

Clear the security bit on a tag (marks tag as available for circulation).

**Query Parameters:**
- `epc` (required) - EPC hex string of tag to unsecure

**Response:**
```json
{
  "success": true,
  "message": "Tag unsecured successfully",
  "epc": "3034257BF468D4800000162E",
  "tagType": "DE290Tag",
  "secured": false
}
```

#### Analyze Tag
```http
GET /analyze/{readerName}?epc={epc}
```

Perform deep analysis of a tag's memory banks, passwords, and security configuration. Useful for debugging and verification.

**Query Parameters:**
- `epc` (required) - EPC hex string of tag to analyze

**Response:**
```json
{
  "success": true,
  "epc": "3034257BF468D4800000162E",
  "analysis": {
    "tagType": "DE290Tag",
    "mediaId": "5678",
    "epcBank": {
      "readSuccess": true,
      "pcValue": "0x3400",
      "epcLengthInWords": 8,
      "epcLengthInBytes": 16,
      "actual": "34003034257BF468D4800000162E",
      "theoretical": "34003034257BF468D4800000162E",
      "matches": true
    },
    "tidBank": {
      "readSuccess": true,
      "data": "E280689400005003F76A18ED",
      "length": 12
    },
    "reservedBank": {
      "readableWithoutAuth": false,
      "readableWithAuth": true,
      "theoretical": "162E5678162E5678",
      "actual": "162E5678162E5678",
      "matches": true,
      "passwordsAreZero": false
    },
    "lockStatus": {
      "reservedBank": "LOCKED",
      "reservedBankStatus": "Read-protected with access password"
    },
    "securityAssessment": {
      "properlySecured": true,
      "passwordCorrect": true,
      "issues": [],
      "passwordProtectionConfigured": true,
      "passwordProtectionRequired": true
    }
  }
}
```

**Analysis Fields:**
- `epcBank` - PC and EPC memory verification
- `tidBank` - Tag Identifier data
- `reservedBank` - Password memory and authentication status
- `lockStatus` - Lock configuration for each memory bank
- `securityAssessment` - Overall security evaluation and detected issues

## Supported Tag Types

- **DE290Tag** - TU Dortmund university library standard
- **DE290FTag** - TU Dortmund university library Fernleihe variant
- **BRTag** - TU Dortmund university library legacy tags by Smartfreq
- **DE386Tag** - RPTU Kaiserslautern university library standard
- **DE6Tag** - ULB Münster library standard
- **RawTag** - Fallback for unrecognized formats

All tag types support automatic password lookup from configuration.

## Architecture

- **ReaderManager** - Central registry for all readers
- **ManagedReader** - Wraps ReaderModule with mode-specific operations
- **TagFactory** - Auto-detects and creates appropriate Tag instances
- **ConfigLoader** - YAML configuration parser

## Security Notes

- Configuration file contains sensitive passwords
- Use volume mounts with `:ro` (read-only) flag in production
- Passwords are validated on startup - warnings logged for placeholder values
- Never commit `config.yaml` to version control

## Requirements

- Java 21 or newer (64-bit platforms only)
- Feig RFID readers (TCP/IP network interface)
- OpenSSL 3.2+ on Linux for TLS features
- Native libraries in `LD_LIBRARY_PATH`

## Troubleshooting

### Native Library Issues
Ensure `LD_LIBRARY_PATH` points to correct native libraries:
- Linux: `native/linux.x64`
- Check logs for "java.library.path" on startup

### Connection Failures
- Verify reader IP address and port in config
- Check network connectivity to readers
- Ensure readers are powered on and network-accessible

### Tag Not Detected
- Verify antenna numbers match physical configuration
- Check RF field is enabled
- Ensure tag is within read range
- Ensure passwords in config.yaml match tag programming

---

## Development

The following sections are for developers who want to build, modify, or contribute to the project.

### Running Locally

**Prerequisites:**
- Java 21 or newer
- Maven
- Feig SDK files (see Devcontainer Prerequisites below)

Set the environment variables and run the application:

```bash
export CONFIG_FILE_PATH=$(pwd)/config.yaml
export LD_LIBRARY_PATH=$(pwd)/native/linux.x64
java -cp "target/classes:target/dependency/*:libs/*" de.bookwaves.Main
```

Or use the provided VS Code launch configuration (already configured).

### Developing with Devcontainer

#### Prerequisites

Before using the devcontainer, you must obtain the Feig SDK files:

1. **Download the Feig SDK** from the official Feig Electronic website:
   - Navigate to the SDK download section for Java
   - Download the latest Java SDK package (64-bit Linux version required)

2. **Extract required files** to the project directories:
   
   **JAR files** (`libs/` directory):
   ```
   libs/fedm-funit-java-api-1.1.0.jar
   libs/fedm-java-api-6.10.jar
   libs/fedm-service-java-api-11.0.2.jar
   ```
   
   **Native libraries** (`native/linux.x64/` directory):
   ```
   native/linux.x64/install.sh
   native/linux.x64/libfecom.so.5.1.0
   native/linux.x64/libfedm-funti.so.1.1.0
   native/linux.x64/libfedm-funti4.so
   native/linux.x64/libfedm-funti4j.so.1.1.0
   native/linux.x64/libfedm-service.so.11.0.2
   native/linux.x64/libfedm-service4j.so.11.0.2
   native/linux.x64/libfedm.so.6.11.0
   native/linux.x64/libfedm4j.so.6.11.0
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
   
   This will create the required `.so` and `.so.X` symbolic links pointing to the versioned files.

4. **Verify directory structure** after running install.sh:
   ```
   .
   ├── libs/
   │   ├── fedm-funit-java-api-1.1.0.jar
   │   ├── fedm-java-api-6.10.jar
   │   └── fedm-service-java-api-11.0.2.jar
   ├── native/
   │   └── linux.x64/
   │       ├── install.sh
   │       ├── libfecom.so -> libfecom.so.5
   │       ├── libfecom.so.5 -> libfecom.so.5.1.0
   │       ├── libfecom.so.5.1.0
   │       ├── libfedm.so -> libfedm.so.6
   │       ├── libfedm.so.6 -> libfedm.so.6.11.0
   │       ├── libfedm.so.6.11.0
   │       └── (etc. - all libraries with symlinks)
   └── ...
   ```

**Note:** These files are proprietary and cannot be included in the repository. You must obtain them directly from Feig Electronic.

#### Starting the Devcontainer

1. **Open in VS Code** with the Dev Containers extension installed

2. **Reopen in Container** - VS Code will prompt to reopen in the devcontainer, or use:
   - Command Palette (`Cmd+Shift+P` / `Ctrl+Shift+P`)
   - Select: "Dev Containers: Reopen in Container"

3. **Build the project** (done automatically, or manually):
   ```bash
   mvn clean compile dependency:copy-dependencies
   ```

4. **Create your configuration**:
   ```bash
   cp config.yaml.example config.yaml
   # Edit config.yaml with your reader settings
   ```

5. **Run the application** using VS Code launch configuration or:
   ```bash
   export CONFIG_FILE_PATH=$(pwd)/config.yaml
   export LD_LIBRARY_PATH=$(pwd)/native/linux.x64
   java -cp "target/classes:target/dependency/*:libs/*" de.bookwaves.Main
   ```

### Building the Docker Image

#### Prerequisites

The same SDK files are required for Docker builds:

1. **Ensure SDK files are in place** as described in the Devcontainer Prerequisites section above
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
     --tag ghcr.io/lukaslerche/bookwaves-feig:v1.0.0 \
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

