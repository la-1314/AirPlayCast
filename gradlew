#!/bin/sh
#
# Gradle start up script
# This is a simplified wrapper. Use the official gradle wrapper generation for production.
# Generate by running: gradle wrapper
#

DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
