FROM maven:3-eclipse-temurin-21-noble AS build
WORKDIR /app

# Copy project files
COPY pom.xml .
COPY src ./src
COPY libs ./libs

# Build and copy dependencies
RUN mvn clean compile dependency:copy-dependencies -DskipTests

FROM eclipse-temurin:21-jre-noble

# Install OpenSSL for TLS features (required by Feig SDK)
RUN apt-get update && \
    apt-get install -y libssl3 && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy compiled classes
COPY --from=build /app/target/classes ./classes

# Copy Maven dependencies
COPY --from=build /app/target/dependency ./libs

# Copy Feig SDK JARs
COPY libs/*.jar ./libs/

# Copy native libraries
COPY native/linux.x64 ./native

# Set library path for Feig SDK native libraries
ENV LD_LIBRARY_PATH=/app/native

# Set default config file path (can be overridden)
ENV CONFIG_FILE_PATH=/app/config/config.yaml

# Update this with your actual main class
ENTRYPOINT ["java", "-cp", "classes:libs/*", "de.bookwaves.Main"]