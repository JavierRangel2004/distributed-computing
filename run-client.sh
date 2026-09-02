#!/usr/bin/env bash
# ==============================================================================
# Script de inicio para la Capa 1: Cliente Pesado (GUI Desktop)
# ==============================================================================
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

if [ -d "/opt/homebrew/opt/openjdk/bin" ]; then
    export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"
fi

JAR="target/distributed-computing-1.0.0-jar-with-dependencies.jar"
if [ ! -f "$JAR" ]; then
    echo "[BUILD] Generando archivo JAR..."
    mvn clean package -DskipTests
fi

java -cp "$JAR" mx.edu.up.computing.client.ClientMain "$@"
