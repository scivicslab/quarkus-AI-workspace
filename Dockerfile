FROM eclipse-temurin:21-jre-noble

WORKDIR /app

# The image tag, baked in at build time so the running portal can show its exact build in the header.
# Pass with: docker build --build-arg IMAGE_TAG=<tag> ...
ARG IMAGE_TAG=dev
ENV SERVICE_PORTAL_IMAGE_TAG=${IMAGE_TAG}

# Tool uber-jars bundled in the image
COPY service-portal.jar         service-portal.jar
COPY quarkus-chat-ui.jar        quarkus-chat-ui.jar
COPY quarkus-chat-ui3.jar       quarkus-chat-ui3.jar
COPY turing-workflow-editor.jar turing-workflow-editor.jar
COPY html-saurus.jar            html-saurus.jar
COPY code-raptor.jar            code-raptor.jar

EXPOSE 28000

CMD ["java", "-Dservice.portal.port-range=28000-28019", "-jar", "service-portal.jar"]
