#!/usr/bin/env bash
# Publish this checkout (Gradle plugin + lumen-runtime + lumen-okhttp) to
# build/maven-repo so another project can try unreleased changes:
#
#   ./scripts/publish-local.sh                # version <LUMEN_VERSION>-local
#   ./scripts/publish-local.sh 0.4.0-dev      # explicit version
#
# Then, in the consuming project's settings.gradle(.kts), list the repo first in
# both pluginManagement.repositories and dependencyResolutionManagement.repositories:
#
#   maven { url = uri("/path/to/lumen/build/maven-repo") }
#
# and use that version for id("io.github.lxp-git.lumen"). Artifacts are unsigned.
set -euo pipefail
cd "$(dirname "$0")/.."
base=$(sed -n 's/^LUMEN_VERSION=//p' gradle.properties)
version="${1:-${base}-local}"
./gradlew -q --no-configuration-cache "-Plumen.version=${version}" \
  :lumen-gradle-plugin:publishAllPublicationsToLumenRepository \
  :lumen-runtime:publishAllPublicationsToLumenRepository \
  :lumen-okhttp:publishAllPublicationsToLumenRepository
echo "published io.github.lxp-git.lumen ${version} to $(pwd)/build/maven-repo"
