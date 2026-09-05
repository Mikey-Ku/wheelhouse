#!/bin/sh
# Runs Wheelhouse against whatever .env.local points at, or the local H2 file if it is absent.
# Nothing here ever prints the password.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

if [ -f .env.local ]; then
  set -a; . ./.env.local; set +a
  echo "using: ${SPRING_DATASOURCE_URL%%\?*}"
else
  echo "using: local H2 file (no .env.local found)"
fi

exec ./mvnw spring-boot:run
