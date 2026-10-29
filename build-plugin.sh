#!/bin/bash
# Builds the plugin zip. Usage: ./build-plugin.sh [version]
set -e
cd "$(dirname "$0")"

VERSION=${1#v}
if [[ -n $VERSION ]]; then
  echo "Building rr plugin $VERSION..."
  ./gradlew clean buildPlugin -PpluginVersion="$VERSION" --no-configuration-cache
else
  echo "Building rr plugin..."
  ./gradlew clean buildPlugin --no-configuration-cache
fi
ls -lh build/distributions/
