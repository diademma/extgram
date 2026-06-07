#!/bin/sh
GRADLE_OPTS="${GRADLE_OPTS:-}"
exec gradle "$@"
