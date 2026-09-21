#!/usr/bin/env bash
#
# Downloads the static archives for a tag into build/static-libs-dist/, where the
# staticLibsJar tasks pick them up.
#
# They are built by .github/workflows/static-libs.yml on our own runners and attached to
# the release, rather than compiled here: JitPack builds on a machine we do not control,
# with no logs to hand and no retries. This keeps the JitPack build doing what it already
# does - packaging binaries someone else produced.
#
# Usage: fetch-static-libs.sh <tag>

set -euo pipefail

tag="${1:-}"
if [ -z "$tag" ]; then
    echo "usage: $0 <tag>" >&2
    exit 2
fi

url="https://github.com/qodana/tree-sitter-ng/releases/download/$tag/tree-sitter-ng-static-$tag.zip"
dest="build/static-libs-dist"

rm -rf "$dest"
mkdir -p "$dest"

# -f so a 404 fails the build. A release whose asset is missing must not publish jars with
# no archives in them: chatter would then fail at link time with nothing pointing here.
curl -fsSL "$url" -o "$dest/static-libs.zip"
unzip -q "$dest/static-libs.zip" -d "$dest"
rm "$dest/static-libs.zip"

echo "Unpacked static archives for $tag:"
find "$dest" \( -name '*.a' -o -name '*.lib' \) | sort
