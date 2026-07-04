#!/bin/sh
# k8s-pups pod entrypoint for the AI Workspace (service-portal).
#
# The pod mounts the user's persistent NFS at $HOME/works (HOME=/home/devteam, set in the image).
# The tool uber-jars are baked into the image at /app but the app discovers tools in $HOME/works, so
# on first launch (empty NFS ~/works) we seed the bundled tool jars there. Seeding is skip-if-present,
# so a user's own Build Snapshot / Download Latest updates in the persistent ~/works are never clobbered.
set -e

# The JVM derives user.home from /etc/passwd (getpwuid), NOT the HOME env var, so we pin it explicitly
# with -Duser.home below. Keep this path in sync with that flag and with the pod's NFS mount point
# (ServicePortalPlugin.userDataMountPath = /home/devteam/works).
WORKS=/home/devteam/works
mkdir -p "$WORKS"

for tool in quarkus-chat-ui quarkus-chat-ui3 turing-workflow-editor html-saurus code-raptor; do
    if [ ! -e "$WORKS/$tool.jar" ] && [ -f "/app/$tool.jar" ]; then
        cp "/app/$tool.jar" "$WORKS/$tool.jar"
        echo "pod-entrypoint: seeded $tool.jar into $WORKS"
    fi
done

# Chain to the base image's CA-cert entrypoint, then run the portal (jar lives at /app).
# -Duser.home makes PluginLoader (user.home/works) and the file browser use the NFS-mounted ~/works.
exec /__cacert_entrypoint.sh java -Duser.home=/home/devteam \
    -Dservice.portal.port-range=28000-28019 -jar /app/service-portal.jar
