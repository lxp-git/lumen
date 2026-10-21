#!/usr/bin/env bash
# Check out skydoves/Pokedex at a pinned commit and add Lumen to it.
#
#   examples/pokedex/setup.sh              # build against this checkout (scripts/publish-local.sh)
#   examples/pokedex/setup.sh --released   # released plugin from the Gradle Plugin Portal
#
# Pokedex has minSdk 23; Lumen 0.3.0 requires 24, so --released needs a later release.
#
# The checkout lands in examples/pokedex/build/Pokedex (git-ignored); override with
# POKEDEX_DIR. Re-running resets that checkout to the pinned commit.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"
dest="${POKEDEX_DIR:-$here/build/Pokedex}"
commit="ea106d5cd8f8f78fa0e5077a60a88ab5b5877694"  # skydoves/Pokedex main, 2026-10-20

local_build=1
case "${1:-}" in
  --released) local_build=0 ;;
  --local | "") ;;
  *) echo "usage: $0 [--released]" >&2; exit 2 ;;
esac

if [[ ! -d "$dest/.git" ]]; then
  mkdir -p "$dest"
  git -C "$dest" init -q
  git -C "$dest" remote add origin https://github.com/skydoves/Pokedex.git
fi
for attempt in 1 2 3; do
  git -C "$dest" fetch -q --depth 1 origin "$commit" && break
  [[ "$attempt" -eq 3 ]] && exit 1
  sleep 2
done
git -C "$dest" checkout -q --force --detach FETCH_HEAD
git -C "$dest" clean -q -fdx -e local.properties

# Not Lumen: upstream main currently fails its own CI because Glide 5 / OkHttp 5.5
# need compileSdk 37. Remove once skydoves/Pokedex fixes it.
git -C "$dest" apply "$here/upstream-compile-sdk.patch"

# The whole integration: one plugin alias in the version catalog and app module...
git -C "$dest" apply "$here/lumen.patch"
# ...plus optional debug-only mock rules.
mkdir -p "$dest/app/src/debug/assets/lumen-mocks"
cp "$here"/lumen-mocks/*.json "$dest/app/src/debug/assets/lumen-mocks/"

if [[ "$local_build" -eq 1 ]]; then
  version="$(sed -n 's/^LUMEN_VERSION=//p' "$repo_root/gradle.properties")-local"
  "$repo_root/scripts/publish-local.sh" "$version"
  python3 - "$dest" "$repo_root/build/maven-repo" "$version" <<'PY'
import re, sys
dest, repo, version = sys.argv[1:]
settings = f"{dest}/settings.gradle.kts"
s = open(settings).read()
line = f'    maven(url = "file://{repo}")  // Lumen from scripts/publish-local.sh\n'
for block in ("pluginManagement {\n  repositories {\n",
              "dependencyResolutionManagement {\n  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n  repositories {\n"):
    assert block in s, block
    s = s.replace(block, block + line)
open(settings, "w").write(s)
catalog = f"{dest}/gradle/libs.versions.toml"
c = open(catalog).read()
open(catalog, "w").write(re.sub(r'^lumen = "[^"]*"', f'lumen = "{version}"', c, count=1, flags=re.M))
PY
fi

cat <<MSG
Pokedex is ready in $dest

  cd "$dest"
  ./gradlew :app:installDebug          # needs JDK 17+ and an Android SDK (local.properties)
  adb shell monkey -p com.skydoves.pokedex -c android.intent.category.LAUNCHER 1

Then open chrome://inspect, or from this repo:

  python3 plugins/lumen/skills/lumen/scripts/lumen.py -p com.skydoves.pokedex net
MSG
