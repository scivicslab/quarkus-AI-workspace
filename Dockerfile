FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# All 5 uber-jars are copied in by the CI workflow before docker build
COPY service-portal.jar        service-portal.jar
COPY quarkus-mcp-gateway.jar   quarkus-mcp-gateway.jar
COPY quarkus-chat-ui.jar       quarkus-chat-ui.jar
COPY turing-workflow-editor.jar turing-workflow-editor.jar
COPY html-saurus.jar           html-saurus.jar

EXPOSE 8080

CMD ["java", "-jar", "service-portal.jar"]
