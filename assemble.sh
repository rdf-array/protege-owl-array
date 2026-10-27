#!/bin/sh
# Assemble "Protégé 5.6.9 + owlapi-array + the OWL-array reasoners" from the stock platform-independent
# zip:
#
#   ./assemble.sh [target-directory]          # default ./Protege-5.6.9-owl-array
#
# It downloads the stock zip from protegeproject/protege-distribution's release (and checks it against the
# SHA-256 below), unpacks it, replaces bundles/owlapi-osgidistribution.jar with owlapi-array's bundle and
# adds this repository's plugin to plugins/. Until this repository has a release, both jars are built
# from source (OWLAPI_BUNDLE=build, PLUGIN=build, the defaults; Git, Maven and a JDK are needed):
# owlapi-array is fetched at OWLAPI_COMMIT from OWLAPI_REPO, its modules are installed into a Maven
# repository of the cache's own (they carry upstream's coordinates) and its osgidistribution module is
# packaged; the plugin is built from the checkout this script is in. The owlapi-array build is cached
# per commit. OWLAPI_BUNDLE and PLUGIN may instead name a URL or a local file. WALKTHROUGH=<jar> also
# adds the walk-through harness (walkthrough/). Start the result with its run.sh (macOS: run.command),
# with JAVA_HOME naming a JDK 11 or newer, since the zip has no jre/.
set -eu

VERSION=5.6.9
ZIP=Protege-$VERSION-platform-independent.zip
ZIP_URL=https://github.com/protegeproject/protege-distribution/releases/download/protege-$VERSION/$ZIP
ZIP_SHA256=89aa0ae7530112228d8f2c552d593474d14ca619472f5cfd185f1e861728aa98
OWLAPI_REPO=${OWLAPI_REPO:-https://github.com/rdf-array/owlapi-array.git}
OWLAPI_COMMIT=${OWLAPI_COMMIT:-1200076f91dc788f11b296066c4c4cda9865fcba}
OWLAPI_VERSION=4.5.29
PLUGIN_VERSION=0.1.0
# Once there is a release, the defaults become its assets:
# RELEASE=https://github.com/rdf-array/protege-owl-array/releases/download/v$PLUGIN_VERSION
# OWLAPI_BUNDLE=$RELEASE/owlapi-osgidistribution-$OWLAPI_VERSION.jar PLUGIN=$RELEASE/protege-owl-array-$PLUGIN_VERSION.jar
OWLAPI_BUNDLE=${OWLAPI_BUNDLE:-build}
PLUGIN=${PLUGIN:-build}
CACHE=${CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/protege-owl-array}
TARGET=${1:-Protege-$VERSION-owl-array}
HERE=$(cd "$(dirname "$0")" && pwd)
M2=$CACHE/m2

mvn_() {
    mvn -B -q -Dmaven.repo.local="$M2" "$@"
}

# Fetch owlapi-array at OWLAPI_COMMIT, install its modules into $M2 and package its OSGi bundle. The
# plugin compiles against these modules, so building it needs this too: the same coordinates on Maven
# Central are upstream's OWL API, which has no org.semanticweb.owlapi.rdfarray.
build_owlapi() {
    src=$CACHE/owlapi-array-$OWLAPI_COMMIT
    OWLAPI_JAR=$src/osgidistribution/target/owlapi-osgidistribution-$OWLAPI_VERSION.jar
    if [ -f "$src/.installed" ] && [ -f "$OWLAPI_JAR" ] \
            && [ -d "$M2/net/sourceforge/owlapi/owlapi-apibinding/$OWLAPI_VERSION" ]; then
        return
    fi
    if [ ! -d "$src" ]; then
        echo "fetching owlapi-array $OWLAPI_COMMIT from $OWLAPI_REPO"
        rm -rf "$src.part"
        git init -q "$src.part"
        git -C "$src.part" fetch -q --depth 1 "$OWLAPI_REPO" "$OWLAPI_COMMIT"
        git -C "$src.part" -c advice.detachedHead=false checkout -q FETCH_HEAD
        mv "$src.part" "$src"
    fi
    echo "building owlapi-array (some minutes; Maven repository $M2)"
    (cd "$src" \
        && mvn_ install -pl '!distribution' -DskipTests -Dmaven.javadoc.skip=true -Dno-javadoc=true -Dgpg.skip \
        && mvn_ -pl osgidistribution package -DskipTests -Dmaven.javadoc.skip=true -Dno-javadoc=true -Dgpg.skip)
    touch "$src/.installed"
}

build_plugin() {
    if [ ! -f "$HERE/pom.xml" ]; then
        echo "PLUGIN=build needs assemble.sh in a checkout of protege-owl-array; or set PLUGIN" >&2
        exit 1
    fi
    build_owlapi
    echo "building the plugin in $HERE"
    (cd "$HERE" && mvn_ package)
    PLUGIN_JAR=$HERE/target/protege-owl-array-$PLUGIN_VERSION.jar
}

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
if [ "$OWLAPI_BUNDLE" = build ]; then
    build_owlapi
    OWLAPI_BUNDLE=$OWLAPI_JAR
fi
if [ "$PLUGIN" = build ]; then
    build_plugin
    PLUGIN=$PLUGIN_JAR
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
echo "$TARGET: Protégé $VERSION with owlapi-array and the OWL-array reasoners; start it with JAVA_HOME=<JDK 11 or newer> $TARGET/run.sh"
