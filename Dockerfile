FROM maven:3-eclipse-temurin-21-noble AS build
WORKDIR /app

# Copy dependency files
COPY pom.xml .
COPY libs ./libs

# Download external dependencies
RUN mvn -B dependency:copy-dependencies -DskipTests

# Copy sources and build
COPY src ./src
RUN mvn -B compile -DskipTests

FROM eclipse-temurin:21-jre-noble

# Install OpenSSL for TLS features (required by Feig SDK)
RUN apt-get update && \
    apt-get install -y --no-install-recommends libssl3 && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy Maven dependencies
COPY --from=build /app/target/dependency ./libs

# Copy Feig SDK JARs
COPY libs/*.jar ./libs/

# Copy compiled classes
COPY --from=build /app/target/classes ./classes

# Copy native libraries
COPY native/linux.x64 ./native

# Set library path for Feig SDK native libraries
ENV LD_LIBRARY_PATH=/app/native

# Set default config file path (can be overridden)
ENV CONFIG_FILE_PATH=/app/config/config.yaml

# API port and example notification listener port range.
EXPOSE 7070 20001-20020

# Update this with your actual main class
ENTRYPOINT ["java", "-cp", "classes:libs/*", "de.bookwaves.Main"]