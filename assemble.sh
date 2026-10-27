#!/bin/sh
# Assemble "Protégé 5.6.9 + owlapi-array + the OWL-array reasoners" from the stock platform-independent
# zip, so that no build is needed:
#
#   ./assemble.sh [target-directory]          # default ./Protege-5.6.9-owl-array
#
# It downloads the stock zip from protegeproject/protege-distribution's release (and checks it against the
# SHA-256 below), unpacks it, replaces bundles/owlapi-osgidistribution.jar with owlapi-array's bundle and
# adds this repository's plugin to plugins/. The two jars come from this repository's release unless
# OWLAPI_BUNDLE and PLUGIN name local files (as after building owlapi-array's osgidistribution module and
# this repository with mvn package). WALKTHROUGH=<jar> also adds the walk-through harness (walkthrough/).
# Start the result with its run.sh (macOS: run.command), with Java 11 or newer.
set -eu

VERSION=5.6.9
ZIP=Protege-$VERSION-platform-independent.zip
ZIP_URL=https://github.com/protegeproject/protege-distribution/releases/download/protege-$VERSION/$ZIP
ZIP_SHA256=89aa0ae7530112228d8f2c552d593474d14ca619472f5cfd185f1e861728aa98
RELEASE=https://github.com/rdf-array/protege-owl-array/releases/download/v0.1.0
OWLAPI_BUNDLE=${OWLAPI_BUNDLE:-$RELEASE/owlapi-osgidistribution-4.5.29.jar}
PLUGIN=${PLUGIN:-$RELEASE/protege-owl-array-0.1.0.jar}
CACHE=${CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/protege-owl-array}
TARGET=${1:-Protege-$VERSION-owl-array}

fetch() { # url-or-path destination
    case $1 in
        http*) curl -fsSL -o "$2" "$1" ;;
        *) cp "$1" "$2" ;;
    esac
}

mkdir -p "$CACHE"
if [ ! -f "$CACHE/$ZIP" ]; then
    echo "downloading $ZIP_URL"
    curl -fsSL -o "$CACHE/$ZIP.part" "$ZIP_URL"
    mv "$CACHE/$ZIP.part" "$CACHE/$ZIP"
fi
sum=$(shasum -a 256 "$CACHE/$ZIP" | cut -d' ' -f1)
if [ "$sum" != "$ZIP_SHA256" ]; then
    echo "$CACHE/$ZIP: SHA-256 $sum, expected $ZIP_SHA256" >&2
    exit 1
fi

if [ -e "$TARGET" ]; then
    echo "$TARGET exists; remove it or name another directory" >&2
    exit 1
fi
work=$(mktemp -d)
unzip -q "$CACHE/$ZIP" -d "$work"
mv "$work/Protege-$VERSION" "$TARGET"
rmdir "$work"

fetch "$OWLAPI_BUNDLE" "$TARGET/bundles/owlapi-osgidistribution.jar"
fetch "$PLUGIN" "$TARGET/plugins/protege-owl-array.jar"
if [ -n "${WALKTHROUGH:-}" ]; then
    fetch "$WALKTHROUGH" "$TARGET/plugins/protege-owl-array-walkthrough.jar"
fi
echo "$TARGET: Protégé $VERSION with owlapi-array and the OWL-array reasoners; start it with $TARGET/run.sh"
