FROM node:22-alpine AS frontend
WORKDIR /web
COPY yu-ai-agent-frontend/package*.json ./
RUN npm ci
COPY yu-ai-agent-frontend/ ./
RUN npm run build

FROM maven:3.9-amazoncorretto-21 AS backend
WORKDIR /build
COPY recall-server/pom.xml ./pom.xml
COPY recall-server/src ./src
COPY --from=frontend /web/dist ./src/main/resources/static
RUN mvn -B package

FROM amazoncorretto:21-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app && mkdir /app/data && chown -R app:app /app
COPY --from=backend /build/target/recall-agent-0.1.0.jar /app/app.jar
USER app
ENV RECALL_DATA_DIR=/app/data
EXPOSE 8123
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
