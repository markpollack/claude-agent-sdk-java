#!/usr/bin/env bash
#
# Standalone-consumer gate for claude-code-sdk.
#
# Why this exists
# ---------------
# The parent POM's <dependencyManagement> — including its Jackson BOM imports — does
# NOT survive POM flattening (flattenMode=ossrh). An ordinary consumer that depends on
# claude-code-sdk without this project's parent and without any BOM therefore resolves
# whatever the flattened POM declares plus whatever transitives Maven picks. Released
# 1.4.0 shipped a flattened POM that let such a consumer land on Jackson 2.21.2 and
# Jackson 3.0.3, both vulnerable, even though the reactor itself was clean.
#
# This gate reproduces that consumer exactly and fails if the published shape regresses.
#
# Properties
# ----------
#   * Version-derived: reads the Maven version from the checkout, so it keeps working
#     across the 1.5.0 release and the 1.6.0-SNAPSHOT bump with no edit.
#   * Hermetic: installs into a throwaway local repository, never ~/.m2.
#   * Deterministic: no Trivy, no NVD/OWASP database, no network vulnerability feed,
#     no credentials, no Claude CLI invocation, no paid model calls.
#
# Usage: scripts/standalone-consumer-gate.sh [--keep]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVNW="$REPO_ROOT/mvnw"

GROUP_ID="io.github.markpollack"
ARTIFACT_ID="claude-code-sdk"

# Required floors. Both are consumer-visible: Jackson 2 is declared directly by the SDK,
# Jackson 3 arrives through mcp -> mcp-json-jackson3 and is declared directly so the
# floor travels in the flattened POM.
JACKSON2_FLOOR="2.21.6"
JACKSON3_FLOOR="3.1.6"

# Expected Java shape: class-file major 65 == Java 21.
EXPECTED_CLASSFILE_MAJOR="65"
EXPECTED_JAVA_FEATURE="21"

KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/claude-code-sdk-consumer-gate.XXXXXX")"
cleanup() { [ "$KEEP" -eq 1 ] || rm -rf "$WORKDIR"; }
trap cleanup EXIT
M2="$WORKDIR/m2"
CONSUMER="$WORKDIR/consumer"
CLOSURE="$CONSUMER/closure"
mkdir -p "$M2" "$CONSUMER"

FAILURES=0
pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# Returns success when $1 >= $2 under version ordering.
version_ge() {
  [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n1)" = "$2" ]
}

echo "== claude-code-sdk standalone-consumer gate =="
echo "   repository: $REPO_ROOT"
echo "   scratch:    $WORKDIR"
echo

# ---------------------------------------------------------------------------
# 1. Derive the version from Maven rather than hard-coding it.
# ---------------------------------------------------------------------------
VERSION="$("$MVNW" -q -B -N help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -n1 | tr -d '[:space:]')"
if [ -z "$VERSION" ]; then
  echo "FATAL: could not derive project.version from the checkout" >&2
  exit 1
fi
echo "-- derived version: $VERSION"

# ---------------------------------------------------------------------------
# 2. Install release-shaped artifacts into an isolated repository. The flattened
#    POM is what gets installed, so it is the consumer metadata under test.
# ---------------------------------------------------------------------------
echo "-- installing into isolated repository"
"$MVNW" -B -Dmaven.repo.local="$M2" -DskipTests -DskipITs install > "$WORKDIR/install.log" 2>&1 || {
  echo "FATAL: isolated install failed; see $WORKDIR/install.log" >&2
  tail -n 40 "$WORKDIR/install.log" >&2
  exit 1
}

FLAT_POM="$M2/${GROUP_ID//.//}/$ARTIFACT_ID/$VERSION/$ARTIFACT_ID-$VERSION.pom"
[ -f "$FLAT_POM" ] || { echo "FATAL: installed POM not found at $FLAT_POM" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 3. A consumer with no parent, no AgentWorks BOM, no dependencyManagement.
# ---------------------------------------------------------------------------
cat > "$CONSUMER/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>gate.consumer</groupId>
  <artifactId>claude-code-sdk-standalone-consumer</artifactId>
  <version>1</version>
  <packaging>jar</packaging>
  <dependencies>
    <dependency>
      <groupId>$GROUP_ID</groupId>
      <artifactId>$ARTIFACT_ID</artifactId>
      <version>$VERSION</version>
    </dependency>
  </dependencies>
</project>
EOF

echo "-- resolving the standalone consumer runtime closure"
( cd "$CONSUMER" && "$MVNW" -B -q -Dmaven.repo.local="$M2" \
    dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=closure ) \
  > "$WORKDIR/resolve.log" 2>&1 || {
  echo "FATAL: consumer resolution failed; see $WORKDIR/resolve.log" >&2
  tail -n 40 "$WORKDIR/resolve.log" >&2
  exit 1
}

JAR_COUNT="$(find "$CLOSURE" -name '*.jar' | wc -l | tr -d ' ')"
echo "   closure: $JAR_COUNT jars"
echo

# ---------------------------------------------------------------------------
# 4. Published POM shape.
# ---------------------------------------------------------------------------
echo "[published POM]"
grep -q '<repositories>' "$FLAT_POM" \
  && fail "flattened POM declares <repositories>; consumers must resolve from Central only" \
  || pass "flattened POM declares no <repositories> (Central-only resolution)"
grep -q '<parent>' "$FLAT_POM" \
  && fail "flattened POM still references a <parent>" \
  || pass "flattened POM is parentless"
grep -q 'Apache License 2.0' "$FLAT_POM" \
  && pass "flattened POM declares Apache License 2.0" \
  || fail "flattened POM does not declare Apache License 2.0"
echo

# ---------------------------------------------------------------------------
# 5. Dependency floors, measured on the resolved closure.
# ---------------------------------------------------------------------------
resolved_version() { # $1 = jar basename prefix
  find "$CLOSURE" -name "$1-*.jar" -printf '%f\n' 2>/dev/null \
    | sed -E "s/^$1-(.+)\.jar$/\1/" | head -n1
}

echo "[dependency floors]"
check_floor() { # $1 label, $2 jar prefix, $3 floor, $4 expected-groupId marker file
  local label="$1" prefix="$2" floor="$3" actual
  actual="$(resolved_version "$prefix")"
  if [ -z "$actual" ]; then
    fail "$label: absent from the consumer runtime closure"
    return
  fi
  if version_ge "$actual" "$floor"; then
    pass "$label resolved $actual (floor $floor)"
  else
    fail "$label resolved $actual, below the required floor $floor"
  fi
}

# Jackson 2 and Jackson 3 both publish a jackson-core/jackson-databind pair. Disambiguate
# by version line rather than by file name.
for prefix in jackson-core jackson-databind; do
  for jar in "$CLOSURE/$prefix"-*.jar; do
    [ -e "$jar" ] || continue
    v="$(basename "$jar" | sed -E "s/^$prefix-(.+)\.jar$/\1/")"
    case "$v" in
      2.*) if version_ge "$v" "$JACKSON2_FLOOR"; then
             pass "com.fasterxml.jackson.core:$prefix resolved $v (floor $JACKSON2_FLOOR)"
           else
             fail "com.fasterxml.jackson.core:$prefix resolved $v, below floor $JACKSON2_FLOOR"
           fi ;;
      3.*) if version_ge "$v" "$JACKSON3_FLOOR"; then
             pass "tools.jackson.core:$prefix resolved $v (floor $JACKSON3_FLOOR)"
           else
             fail "tools.jackson.core:$prefix resolved $v, below floor $JACKSON3_FLOOR"
           fi ;;
      *)   fail "$prefix resolved an unexpected version line: $v" ;;
    esac
  done
done
check_floor "tools.jackson.dataformat:jackson-dataformat-yaml" jackson-dataformat-yaml "$JACKSON3_FLOOR"

# Every Jackson 3 artifact must sit on one minor; skew across tools.jackson modules is a
# runtime hazard, not a cosmetic difference.
J3_MINORS="$(find "$CLOSURE" -name '*.jar' -printf '%f\n' \
  | sed -nE 's/^(jackson-core|jackson-databind|jackson-dataformat-yaml)-(3\.[0-9]+)\..*\.jar$/\2/p' \
  | sort -u | tr '\n' ' ')"
if [ "$(echo "$J3_MINORS" | wc -w)" -le 1 ]; then
  pass "Jackson 3 modules aligned on a single minor (${J3_MINORS:-none present})"
else
  fail "Jackson 3 modules are split across minors: $J3_MINORS"
fi
echo

# ---------------------------------------------------------------------------
# 6. Java shape of the published artifact.
# ---------------------------------------------------------------------------
echo "[java shape]"
SDK_JAR="$CLOSURE/$ARTIFACT_ID-$VERSION.jar"
if [ ! -f "$SDK_JAR" ]; then
  fail "$ARTIFACT_ID-$VERSION.jar missing from the consumer closure"
else
  # Read each class individually; a concatenated stream only yields one header.
  TMPX="$WORKDIR/x"; rm -rf "$TMPX"; mkdir -p "$TMPX"
  unzip -q -o "$SDK_JAR" -d "$TMPX" '*.class'
  MAJORS="$(find "$TMPX" -name '*.class' -exec od -An -tu1 -j6 -N2 -v {} \; \
    | awk '{printf "%d\n", $1*256 + $2}' | sort -u | tr '\n' ' ')"
  MAJORS="$(echo "$MAJORS" | xargs)"
  if [ "$MAJORS" = "$EXPECTED_CLASSFILE_MAJOR" ]; then
    pass "every class in $ARTIFACT_ID-$VERSION.jar is class-file major $MAJORS (Java $EXPECTED_JAVA_FEATURE)"
  else
    fail "unexpected class-file major version(s) in the published jar: $MAJORS (expected $EXPECTED_CLASSFILE_MAJOR)"
  fi
  unzip -l "$SDK_JAR" | grep -q 'META-INF/LICENSE' \
    && pass "published jar embeds META-INF/LICENSE" \
    || fail "published jar does not embed META-INF/LICENSE"
fi
echo

# ---------------------------------------------------------------------------
# 7. Offline runtime smoke against the resolved closure.
#
#    This links the SDK's Jackson 2 parsing path and the Jackson 3 stack that mcp
#    supplies, on exactly the versions a consumer receives. It spawns no Claude CLI
#    process, opens no network connection, and uses no credentials.
# ---------------------------------------------------------------------------
echo "[runtime smoke]"
CP="$(find "$CLOSURE" -name '*.jar' | sort | tr '\n' ':')"
SMOKE="$WORKDIR/smoke"
mkdir -p "$SMOKE"
cat > "$SMOKE/ConsumerSmoke.java" <<'JAVA'
import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;
import io.github.markpollack.claude.agent.sdk.types.Message;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;

import java.util.Map;
import java.util.ServiceLoader;

/** Offline consumer smoke: no CLI process, no network, no credentials. */
public class ConsumerSmoke {

    public static void main(String[] args) throws Exception {
        int feature = Runtime.version().feature();
        if (feature < 21) {
            throw new IllegalStateException("expected a Java 21+ runtime, got " + feature);
        }

        // Jackson 2 path: the SDK's own message parsing.
        String assistant = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_1\",\"model\":\"m\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}}";
        Message parsed = new MessageParser().parseMessage(assistant);
        if (parsed == null) {
            throw new IllegalStateException("MessageParser returned null");
        }

        // Option construction for an existing option, exercised as a consumer would.
        CLIOptions options = CLIOptions.builder().model("claude-sonnet-5").maxTurns(1).build();
        if (options == null) {
            throw new IllegalStateException("CLIOptions.builder() returned null");
        }

        // Jackson 3 path: the JSON mapper mcp actually binds at runtime, loaded through
        // its ServiceLoader SPI so core/databind must genuinely link.
        McpJsonMapperSupplier supplier = ServiceLoader.load(McpJsonMapperSupplier.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no McpJsonMapperSupplier on the consumer classpath"));
        McpJsonMapper mapper = supplier.get();
        String json = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "tools/list"));
        Map<?, ?> roundTripped = mapper.readValue(json, Map.class);
        if (!"tools/list".equals(roundTripped.get("method"))) {
            throw new IllegalStateException("Jackson 3 round-trip lost data: " + roundTripped);
        }

        // The YAML dataformat travels with json-schema-validator; loading it proves the
        // tools.jackson modules are mutually compatible at the resolved versions.
        Class.forName("tools.jackson.dataformat.yaml.YAMLMapper");

        System.out.println("consumer smoke OK (java " + feature
                + ", parsed=" + parsed.getClass().getSimpleName()
                + ", mcp mapper=" + mapper.getClass().getName() + ")");
    }
}
JAVA

if javac -nowarn -cp "$CP" -d "$SMOKE" "$SMOKE/ConsumerSmoke.java" > "$WORKDIR/smoke-compile.log" 2>&1; then
  pass "consumer smoke compiles against the resolved closure"
  if SMOKE_OUT="$(java -cp "$CP:$SMOKE" ConsumerSmoke 2>&1)"; then
    pass "consumer smoke ran: $SMOKE_OUT"
  else
    fail "consumer smoke failed at runtime: $SMOKE_OUT"
  fi
else
  fail "consumer smoke failed to compile; see $WORKDIR/smoke-compile.log"
  tail -n 20 "$WORKDIR/smoke-compile.log"
fi
echo

# ---------------------------------------------------------------------------
echo "== summary =="
echo "   version:  $VERSION"
echo "   closure:  $JAR_COUNT jars"
echo "   failures: $FAILURES"
if [ "$FAILURES" -ne 0 ]; then
  echo "GATE FAILED"
  exit 1
fi
echo "GATE PASSED"
