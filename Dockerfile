# ---- Build Stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Download dependencies first (cache layer)
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---- Runtime Stage ----
FROM quay.io/keycloak/keycloak:26.0.0

# Copy the plugin JAR into the providers directory
COPY --from=build /app/target/wa2fa-1.1.jar /opt/keycloak/providers/

# Build Keycloak with the new provider
RUN /opt/keycloak/bin/kc.sh build
