#!/usr/bin/env bash
#
# Local, offline vulnerability scan for claude-code-sdk.
#
# This is a maintainer procedure, not a CI gate. Vulnerability-database acquisition is
# deliberately kept out of hosted CI: it is slow, it is shared infrastructure rather
# than per-repository work, and a cold download failure is an infrastructure problem
# that should never be reported as a failed security gate. CI enforces deterministic
# dependency resolution through scripts/standalone-consumer-gate.sh instead.
#
# Two surfaces are scanned, in the order that matters:
#
#   1. the actual runtime JAR closure a standalone consumer resolves — the primary
#      evidence, because it is the dependency graph an ordinary user really receives;
#   2. the aggregate CycloneDX SBOM — supporting inventory, which additionally shows
#      declared and provided components that no JAR closure contains.
#
# Usage:
#   scripts/security-scan.sh                       # use whatever database Trivy has
#   TRIVY_CACHE_DIR=/path/to/frozen scripts/security-scan.sh
#
# When TRIVY_CACHE_DIR names a validated, frozen snapshot, database updates are
# disabled and the scan is fully offline and reproducible. Record the database hashes
# before and after any scan whose result you intend to rely on.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVNW="$REPO_ROOT/mvnw"

command -v trivy >/dev/null 2>&1 || {
  echo "FATAL: trivy is not on PATH. See https://trivy.dev/ for installation." >&2
  exit 1
}

# --cache-dir is a global flag and belongs before the subcommand; --disable-telemetry
# is a scan-subcommand flag and belongs after it. Passing the latter globally makes
# every invocation fail with "unknown flag: --disable-telemetry".
TRIVY_ARGS=()
SCAN_ARGS=(--disable-telemetry)
if [ -n "${TRIVY_CACHE_DIR:-}" ]; then
  [ -d "$TRIVY_CACHE_DIR" ] || { echo "FATAL: TRIVY_CACHE_DIR does not exist: $TRIVY_CACHE_DIR" >&2; exit 1; }
  TRIVY_ARGS+=(--cache-dir "$TRIVY_CACHE_DIR")
  SCAN_ARGS+=(--skip-db-update --skip-java-db-update --offline-scan)
  echo "== using frozen database: $TRIVY_CACHE_DIR (updates disabled, offline)"
  for f in db/trivy.db db/metadata.json java-db/trivy-java.db java-db/metadata.json; do
    [ -f "$TRIVY_CACHE_DIR/$f" ] && printf '   %-24s %s\n' "$f" "$(sha256sum "$TRIVY_CACHE_DIR/$f" | cut -d' ' -f1)"
  done
else
  echo "== using Trivy's own database (it may update over the network)"
fi
echo

OUT="$(mktemp -d "${TMPDIR:-/tmp}/claude-code-sdk-scan.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
M2="$OUT/m2"
CONSUMER="$OUT/consumer"
mkdir -p "$M2" "$CONSUMER"

VERSION="$("$MVNW" -q -B -N help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -n1 | tr -d '[:space:]')"
echo "== version under scan: $VERSION"

echo "== building release-shaped artifacts and the aggregate SBOM into an isolated repository"
"$MVNW" -B -Dmaven.repo.local="$M2" -DskipTests -DskipITs install > "$OUT/install.log" 2>&1 || {
  echo "FATAL: build failed; see $OUT/install.log" >&2; tail -n 30 "$OUT/install.log" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Primary: the standalone consumer runtime JAR closure.
# ---------------------------------------------------------------------------
cat > "$CONSUMER/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>scan.consumer</groupId>
  <artifactId>claude-code-sdk-scan-consumer</artifactId>
  <version>1</version>
  <dependencies>
    <dependency>
      <groupId>io.github.markpollack</groupId>
      <artifactId>claude-code-sdk</artifactId>
      <version>$VERSION</version>
    </dependency>
  </dependencies>
</project>
EOF
( cd "$CONSUMER" && "$MVNW" -B -q -Dmaven.repo.local="$M2" \
    dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=closure ) > "$OUT/resolve.log" 2>&1

echo
echo "=============================================================="
echo " 1. standalone-consumer runtime JAR closure ($(find "$CONSUMER/closure" -name '*.jar' | wc -l | tr -d ' ') jars)"
echo "=============================================================="
trivy "${TRIVY_ARGS[@]}" rootfs "${SCAN_ARGS[@]}" --scanners vuln --format table "$CONSUMER/closure"

# ---------------------------------------------------------------------------
# 2. Supporting: the aggregate CycloneDX SBOM.
# ---------------------------------------------------------------------------
SBOM="$REPO_ROOT/target/claude-agent-sdk-parent-$VERSION-cyclonedx.json"
echo
echo "=============================================================="
echo " 2. aggregate CycloneDX SBOM (supporting inventory)"
echo "=============================================================="
if [ -f "$SBOM" ]; then
  echo "    $SBOM"
  echo "    sha256: $(sha256sum "$SBOM" | cut -d' ' -f1)"
  trivy "${TRIVY_ARGS[@]}" sbom "${SCAN_ARGS[@]}" --scanners vuln --format table "$SBOM"
else
  echo "    SBOM not found at $SBOM — run ./mvnw package first" >&2
fi

if [ -n "${TRIVY_CACHE_DIR:-}" ]; then
  echo
  echo "== database hashes after the scan (must match the pre-scan values above)"
  for f in db/trivy.db db/metadata.json java-db/trivy-java.db java-db/metadata.json; do
    [ -f "$TRIVY_CACHE_DIR/$f" ] && printf '   %-24s %s\n' "$f" "$(sha256sum "$TRIVY_CACHE_DIR/$f" | cut -d' ' -f1)"
  done
fi
