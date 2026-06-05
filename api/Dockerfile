# api/Dockerfile
# We use a lightweight Alpine Linux image with Java 21 JRE installed
FROM eclipse-temurin:21-jre-alpine

# Create a directory for our app
WORKDIR /app

# Copy the compiled JAR from the target folder into the container
# Note: This assumes you ran `mvn clean package` first!
COPY target/api-1.0.0.jar app.jar

# Expose the API port
EXPOSE 8080

# The command to run when the container starts
ENTRYPOINT ["java", "-jar", "app.jar"]