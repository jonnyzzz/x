#!/usr/bin/env sh
set -eu

: "${DISPLAY:=host.docker.internal:0}"
: "${IDEA_URL:=https://github.com/JetBrains/intellij-community/releases/download/idea/2026.1.3/idea-2026.1.3-aarch64.tar.gz}"
: "${IDEA_HOME:=/opt/idea}"
: "${IDEA_PROJECT:=/tmp/demo-project}"

mkdir -p "$IDEA_HOME" /tmp/idea-config /tmp/idea-system /tmp/idea-log "$IDEA_PROJECT"

if [ ! -x "$IDEA_HOME/bin/idea.sh" ]; then
  curl -L "$IDEA_URL" -o /tmp/idea.tar.gz
  tar -xzf /tmp/idea.tar.gz -C "$IDEA_HOME" --strip-components=1
fi

cat > /tmp/idea.properties <<EOF
idea.config.path=/tmp/idea-config
idea.system.path=/tmp/idea-system
idea.log.path=/tmp/idea-log
EOF

if [ ! -f "$IDEA_PROJECT/README.md" ]; then
  cat > "$IDEA_PROJECT/README.md" <<EOF
# X Server Demo

Running IntelliJ inside jonnyzzz/x X server.
EOF
fi

export DISPLAY
export IDEA_PROPERTIES=/tmp/idea.properties
export _JAVA_AWT_WM_NONREPARENTING=1

exec "$IDEA_HOME/bin/idea.sh" nosplash "$IDEA_PROJECT"
