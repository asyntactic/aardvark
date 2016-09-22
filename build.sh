#!/bin/bash

VERSION="0.0.0"

# Build, test
lein do clean, test || return $?

# Package
lein do cljsbuild once, ring uberwar || return $?

# Bundle all the artifacts into a tarball
tar -czf target/aardvark-$VERSION.tgz target/aardvark-$VERSION-standalone.* migrations/*.sql
