#!/usr/bin/env bash
# Dogfood the CVE tooling: scan OUR OWN dependencies with Trivy (HIGH/CRITICAL).
# Maven-native gate (OWASP dependency-check): ./mvnw -Powasp verify
set -euo pipefail
cd "$(dirname "$0")/.."
exec trivy fs --scanners vuln --severity HIGH,CRITICAL --skip-dirs target --format table .
