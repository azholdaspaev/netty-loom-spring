#!/usr/bin/env bash
# Usage: consumer-smoke/run.sh <repository-url> <version>
#
# Resolves, compiles, starts and requests the published starter from a standalone Gradle consumer
# and a standalone Maven consumer, then resolves the sources and javadoc jars of every published
# module. Runs against artifacts that are already published; it publishes nothing. #183
set -euo pipefail

repository=${1:?usage: run.sh <repository-url> <version>}
version=${2:?usage: run.sh <repository-url> <version>}

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(dirname "$here")
boot_version=$(sed -n 's/^spring-boot = "\(.*\)"/\1/p' "$root/gradle/libs.versions.toml")
port=${SMOKE_PORT:-18090}
logs=$(mktemp -d)
app_pid=

modules=(core mvc boot-starter)
classifiers=(sources javadoc)

fail() {
    echo "::error::consumer-smoke: $1 consumer failed at $2 (log: $3)" >&2
    exit 1
}

phase() {
    local consumer=$1 name=$2
    shift 2
    local log="$logs/$consumer-$name.log"
    echo "[$consumer] $name"
    "$@" >"$log" 2>&1 || { cat "$log"; fail "$consumer" "$name" "$log"; }
}

stop_app() {
    if [ -n "$app_pid" ]; then
        kill "$app_pid" 2>/dev/null || true
        wait "$app_pid" 2>/dev/null || true
        app_pid=
    fi
}
trap stop_app EXIT

start_app() {
    local consumer=$1 jar=$2
    java -jar "$jar" --server.port="$port" >"$logs/$consumer-app.log" 2>&1 &
    app_pid=$!
    for _ in $(seq 60); do
        kill -0 "$app_pid" 2>/dev/null || return 1
        curl -sS -o /dev/null "http://localhost:$port/" 2>/dev/null && return 0
        sleep 1
    done
    return 1
}

request_smoke() {
    local body
    body=$(curl -fsS "http://localhost:$port/smoke")
    echo "body: $body"
    [ "$body" = "Netty-Loom /smoke" ]
}

gradle() {
    "$root/gradlew" -p "$here/gradle" \
        -PsmokeRepository="$repository" -PsmokeVersion="$version" -PsmokeBootVersion="$boot_version" "$@"
}

maven() {
    mvn -B -f "$here/maven/pom.xml" \
        -Dsmoke.repository="$repository" -Dsmoke.version="$version" -Dsmoke.boot.version="$boot_version" "$@"
}

gradle_startup() {
    gradle bootJar && start_app gradle "$here/gradle/build/libs/app.jar"
}

maven_startup() {
    maven package && start_app maven "$here/maven/target/app.jar"
}

maven_auxiliary() {
    local module classifier
    for module in "${modules[@]}"; do
        for classifier in "${classifiers[@]}"; do
            maven dependency:get -Dtransitive=false \
                -Dartifact="io.github.azholdaspaev:netty-loom-spring-$module:$version:jar:$classifier"
        done
    done
}

echo "consumer-smoke: $repository, version $version, Spring Boot $boot_version"

phase gradle resolution gradle resolveRuntimeClasspath
phase gradle compilation gradle compileJava
phase gradle startup gradle_startup
phase gradle http request_smoke
stop_app
phase gradle auxiliary gradle resolveAuxiliaryArtifacts

phase maven resolution maven dependency:resolve
phase maven compilation maven compile
phase maven startup maven_startup
phase maven http request_smoke
stop_app
phase maven auxiliary maven_auxiliary

echo "consumer-smoke: OK"
