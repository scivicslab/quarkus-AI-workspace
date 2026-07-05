FROM eclipse-temurin:21-jdk-noble

# git + maven so "Build Snapshot" works inside the pod (SnapshotBuildService clones from GitHub and
# runs mvn install). The JDK base (not JRE) provides javac, which Maven needs to compile tools.
# openssh-client so users can clone/push their own repos over SSH (git@github.com) from the pod.
RUN apt-get update \
 && apt-get install -y --no-install-recommends git maven openssh-client \
 && rm -rf /var/lib/apt/lists/*

# The image tag, baked in at build time so the running portal can show its exact build in the header.
# Pass with: docker build --build-arg IMAGE_TAG=<tag> ...
ARG IMAGE_TAG=dev
ENV SERVICE_PORTAL_IMAGE_TAG=${IMAGE_TAG}

# The AI Workspace discovers tools in $HOME/works and the k8s-pups pod mounts the user's persistent
# NFS at /home/devteam/works, so HOME must be /home/devteam for the two to coincide. Own /home/devteam
# with uid 1000 (the pod runs as uid 1000) so ~/.m2 and other $HOME writes work; the NFS mount overlays
# only the works subdir at runtime.
ENV HOME=/home/devteam
# Persist Maven's dependency cache on the NFS-backed ~/works (the only persistent mount in the pod),
# so Build Snapshot doesn't re-download everything after each Pod re-creation.
ENV AI_WORKSPACE_SNAPSHOT_MAVEN_REPO_LOCAL=/home/devteam/works/.m2/repository
RUN mkdir -p /home/devteam/works && chown -R 1000:1000 /home/devteam

# Tool uber-jars bundled in the image (at /app). pod-entrypoint.sh seeds them into $HOME/works on first
# launch so tools show as acquired (matching a normal host ~/works); the portal jar runs from /app.
COPY service-portal.jar         /app/service-portal.jar
COPY quarkus-chat-ui.jar        /app/quarkus-chat-ui.jar
COPY quarkus-chat-ui3.jar       /app/quarkus-chat-ui3.jar
COPY turing-workflow-editor.jar /app/turing-workflow-editor.jar
COPY html-saurus.jar            /app/html-saurus.jar
COPY code-raptor.jar            /app/code-raptor.jar
COPY pod-entrypoint.sh          /app/pod-entrypoint.sh
RUN chmod +x /app/pod-entrypoint.sh

# user.dir = ~/works so SnapshotBuildService (default works-dir ${user.dir}) installs where the portal
# scans; the CMD/portal jar is referenced by absolute path since CWD is now the works dir.
WORKDIR /home/devteam/works

EXPOSE 28000

ENTRYPOINT ["/app/pod-entrypoint.sh"]
