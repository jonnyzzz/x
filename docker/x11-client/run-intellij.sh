#!/usr/bin/env sh
set -eu

: "${DISPLAY:=host.docker.internal:0}"
: "${IDEA_HOME:=/opt/idea}"
: "${IDEA_CONFIG:=/tmp/idea-config}"
: "${IDEA_SYSTEM:=/tmp/idea-system}"
: "${IDEA_LOG:=/tmp/idea-log}"
: "${IDEA_PROJECT:=}"
: "${IDEA_TRUST_PROJECT:=true}"
: "${IDEA_TRUST_ALL_PROJECTS:=}"
: "${IDEA_CONFIRM_CONSENTS:=false}"
: "${IDEA_ACCEPT_EUA:=true}"
: "${IDEA_EUA_VERSION:=1.0}"
: "${IDEA_REGISTER_JBR_SDK:=true}"
: "${IDEA_DISABLE_ONBOARDING:=true}"
: "${IDEA_X11_DEBUG:=false}"
: "${IDEA_X11_DEBUG_CATEGORIES:=#sun.awt.X11,#sun.awt.X11.XToolkit,#sun.awt.X11.XComponentPeer,#sun.awt.X11.XFramePeer,#sun.awt.X11.XDecoratedPeer,#sun.awt.X11.XErrorHandlerUtil,#sun.awt.X11.XNETProtocol,#sun.awt.X11.XWINProtocol,#sun.awt.X11.xembed,#com.intellij.ui.jcef,#com.intellij.platform.compose,#com.intellij.ide.ui,#com.intellij.openapi.wm.impl.FocusManagerImpl}"
: "${IDEA_X11_TRACE_CATEGORIES:=#java.awt.KeyboardFocusManager,#sun.awt.X11.event.XToolkit,#sun.awt.X11.focus.XComponentPeer,#sun.awt.X11.focus.XDecoratedPeer}"
: "${IDEA_X11_SEPARATE_LOG_CATEGORIES:=#com.intellij.ui.jcef,#sun.awt.X11,#java.awt.KeyboardFocusManager}"

if [ -z "$IDEA_TRUST_ALL_PROJECTS" ]; then
  IDEA_TRUST_ALL_PROJECTS="$IDEA_TRUST_PROJECT"
fi

if [ -z "$IDEA_PROJECT" ]; then
  if [ -d /workspace/jonnyzzz-x ]; then
    IDEA_PROJECT=/workspace/jonnyzzz-x
  else
    IDEA_PROJECT=/tmp/demo-project
  fi
fi

if [ -z "${IDEA_URL:-}" ]; then
  case "$(uname -m)" in
    aarch64|arm64)
      IDEA_URL=https://github.com/JetBrains/intellij-community/releases/download/idea/2026.1.3/idea-2026.1.3-aarch64.tar.gz
      ;;
    *)
      IDEA_URL=https://github.com/JetBrains/intellij-community/releases/download/idea/2026.1.3/idea-2026.1.3.tar.gz
      ;;
  esac
fi

mkdir -p "$IDEA_HOME" "$IDEA_CONFIG" "$IDEA_SYSTEM" "$IDEA_LOG" "$IDEA_PROJECT"

if [ ! -x "$IDEA_HOME/bin/idea.sh" ]; then
  curl -L "$IDEA_URL" -o /tmp/idea.tar.gz
  tar -xzf /tmp/idea.tar.gz -C "$IDEA_HOME" --strip-components=1
fi

if [ "$IDEA_REGISTER_JBR_SDK" = "true" ] && [ -x "$IDEA_HOME/jbr/bin/java" ]; then
  mkdir -p /usr/lib/jvm "$IDEA_CONFIG/options"
  ln -sfn "$IDEA_HOME/jbr" /usr/lib/jvm/jbr-25
  : "${JAVA_HOME:=/usr/lib/jvm/jbr-25}"
  : "${JDK_HOME:=$JAVA_HOME}"
  export JAVA_HOME JDK_HOME
  if [ ! -f "$IDEA_CONFIG/options/jdk.table.xml" ]; then
    cat > "$IDEA_CONFIG/options/jdk.table.xml" <<EOF
<application>
  <component name="ProjectJdkTable">
    <jdk version="2">
      <name value="jbr-25" />
      <type value="JavaSDK" />
      <version value="JBR 25" />
      <homePath value="/usr/lib/jvm/jbr-25" />
      <roots>
        <annotationsPath>
          <root type="composite">
            <root url="jar://\$APPLICATION_HOME_DIR\$/plugins/java/lib/resources/jdkAnnotations.jar!/" type="simple" />
          </root>
        </annotationsPath>
        <classPath>
          <root type="composite">
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.base" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.compiler" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.desktop" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.logging" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.management" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.naming" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.net.http" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.prefs" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.sql" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.xml" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/jdk.compiler" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/jdk.jartool" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/jdk.jdi" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/jdk.unsupported" type="simple" />
          </root>
        </classPath>
        <javadocPath>
          <root type="composite" />
        </javadocPath>
        <sourcePath>
          <root type="composite" />
        </sourcePath>
      </roots>
    </jdk>
  </component>
</application>
EOF
  fi
fi

if [ "$IDEA_ACCEPT_EUA" = "true" ]; then
  cat > /tmp/SeedIdeaFirstRun.java <<'EOF'
import java.util.prefs.Preferences;

public class SeedIdeaFirstRun {
  public static void main(String[] args) throws Exception {
    String version = args.length == 0 ? "1.0" : args[0];
    Preferences privacyPolicy = Preferences.userRoot()
        .node("jetbrains")
        .node("privacy_policy");
    privacyPolicy.put("euacommunity_accepted_version", version);
    privacyPolicy.put("eua_accepted_version", version);
    privacyPolicy.put("accepted_version", version);
    privacyPolicy.flush();
  }
}
EOF
  "$IDEA_HOME/jbr/bin/javac" /tmp/SeedIdeaFirstRun.java
  "$IDEA_HOME/jbr/bin/java" -cp /tmp SeedIdeaFirstRun "$IDEA_EUA_VERSION"
fi

cat > /tmp/idea.properties <<EOF
idea.config.path=$IDEA_CONFIG
idea.system.path=$IDEA_SYSTEM
idea.log.path=$IDEA_LOG
EOF

if [ ! -f "$IDEA_PROJECT/README.md" ]; then
  cat > "$IDEA_PROJECT/README.md" <<EOF
# X Server Demo

Running IntelliJ inside jonnyzzz/x X server.
EOF
fi

xml_escape() {
  printf '%s' "$1" \
    | sed \
      -e 's/&/\&amp;/g' \
      -e 's/</\&lt;/g' \
      -e 's/>/\&gt;/g' \
      -e 's/"/\&quot;/g'
}

if [ "$IDEA_TRUST_PROJECT" = "true" ]; then
  mkdir -p "$IDEA_CONFIG/options"
  trusted_project=$(xml_escape "$IDEA_PROJECT")
  cat > "$IDEA_CONFIG/options/trusted-paths.xml" <<EOF
<application>
  <component name="Trusted.Paths">
    <option name="trustedPaths">
      <map>
        <entry key="$trusted_project" value="true" />
      </map>
    </option>
  </component>
  <component name="Trusted.Paths.Settings">
    <option name="trustedPaths">
      <list>
        <option value="$trusted_project" />
      </list>
    </option>
  </component>
</application>
EOF
fi

if [ "$IDEA_DISABLE_ONBOARDING" = "true" ]; then
  mkdir -p "$IDEA_CONFIG/options"
  if [ ! -f "$IDEA_CONFIG/options/other.xml" ]; then
    cat > "$IDEA_CONFIG/options/other.xml" <<'EOF'
<application>
  <component name="PropertyService"><![CDATA[{
  "keyToString": {
    "WelcomeFeature.SHORT_WELCOME_GUIDE_SHOWN": "true",
    "defaultJdkConfigured": "true",
    "experimental.ui.on.first.startup": "true",
    "experimental.ui.onboarding.proposed.version": "999999"
  }
}]]></component>
</application>
EOF
  fi
  if [ ! -f "$IDEA_CONFIG/options/ide.general.xml" ]; then
    cat > "$IDEA_CONFIG/options/ide.general.xml" <<'EOF'
<application>
  <component name="GeneralSettings">
    <option name="confirmOpenNewProject2" value="1" />
  </component>
  <component name="Registry">
    <entry key="ide.experimental.ui.onboarding" value="false" source="SYSTEM" />
    <entry key="ide.newUsersOnboarding" value="false" source="SYSTEM" />
  </component>
</application>
EOF
  fi
fi

export DISPLAY
export IDEA_PROPERTIES=/tmp/idea.properties

idea_extra_vmoptions=/tmp/idea-extra.vmoptions
idea_extra_initialized=false
append_idea_vm_option() {
  if [ "$idea_extra_initialized" = "false" ]; then
    : > "$idea_extra_vmoptions"
    if [ -n "${IDEA_VM_OPTIONS:-}" ] && [ -f "$IDEA_VM_OPTIONS" ]; then
      cat "$IDEA_VM_OPTIONS" >> "$idea_extra_vmoptions"
      printf '\n' >> "$idea_extra_vmoptions"
    fi
    idea_extra_initialized=true
  fi
  printf '%s\n' "$1" >> "$idea_extra_vmoptions"
  export IDEA_VM_OPTIONS="$idea_extra_vmoptions"
}

if [ "$IDEA_CONFIRM_CONSENTS" = "false" ]; then
  append_idea_vm_option "-Djb.consents.confirmation.enabled=false"
  if [ "$IDEA_TRUST_ALL_PROJECTS" = "true" ]; then
    append_idea_vm_option "-Didea.trust.all.projects=true"
  fi
fi

if [ "$IDEA_X11_DEBUG" = "true" ]; then
  mkdir -p "$IDEA_LOG"
  append_idea_vm_option "-Dsun.java2d.xrender=True"
  append_idea_vm_option "-Dsun.java2d.opengl=false"
  append_idea_vm_option "-Dsun.awt.x11.trace=log,timestamp,stats,out:$IDEA_LOG/xawt-trace.log,td=1"
  append_idea_vm_option "-Didea.log.debug.categories=$IDEA_X11_DEBUG_CATEGORIES"
  append_idea_vm_option "-Didea.log.trace.categories=$IDEA_X11_TRACE_CATEGORIES"
  append_idea_vm_option "-Didea.log.separate.file.categories=$IDEA_X11_SEPARATE_LOG_CATEGORIES"
  append_idea_vm_option "-Dide.browser.jcef.log.level=verbose"
  append_idea_vm_option "-Dide.browser.jcef.log.path=$IDEA_LOG/jcef.log"
  append_idea_vm_option "-Dide.browser.jcef.log_chromium.path=$IDEA_LOG/jcef_chromium.log"
  append_idea_vm_option "-Dide.browser.jcef.log.extended=true"
fi

if [ -z "${XDG_RUNTIME_DIR:-}" ]; then
  export XDG_RUNTIME_DIR=/tmp/runtime-root
  mkdir -p "$XDG_RUNTIME_DIR"
  chmod 700 "$XDG_RUNTIME_DIR"
fi
export _JAVA_AWT_WM_NONREPARENTING=1

exec "$IDEA_HOME/bin/idea.sh" nosplash "$IDEA_PROJECT"
