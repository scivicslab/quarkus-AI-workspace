FROM eclipse-temurin:21-jre-noble

WORKDIR /app

# All 5 uber-jars are copied in by the CI workflow before docker build
COPY service-portal.jar        service-portal.jar
COPY quarkus-mcp-gateway.jar   quarkus-mcp-gateway.jar
COPY quarkus-chat-ui.jar       quarkus-chat-ui.jar
COPY turing-workflow-editor.jar turing-workflow-editor.jar
COPY html-saurus.jar           html-saurus.jar

EXPOSE 28000

CMD ["java", "-Dservice.portal.port-range=28000-28019", "-jar", "service-portal.jar"]
