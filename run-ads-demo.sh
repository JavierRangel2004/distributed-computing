#!/usr/bin/env bash
# Lanzador minimo de DemoMain (mx.edu.up.computing.ads). Solo foreground.
set -euo pipefail
cd "$(dirname "$0")"

command -v java >/dev/null 2>&1 || { echo "[ERROR] Java no esta en el PATH. Instala JDK 21+ (Temurin)." >&2; exit 1; }
java -version

JAR="target/distributed-computing-1.0.0-jar-with-dependencies.jar"
[ -f "$JAR" ] || { echo "[ERROR] No se encontro $JAR. Ejecuta 'mvn package' primero." >&2; exit 1; }

exec java -cp "$JAR" mx.edu.up.computing.ads.DemoMain "$@"
