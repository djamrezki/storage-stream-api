#!/usr/bin/env bash
set -euo pipefail

# run-storage.sh — one-liner wrapper to run Storage API from GHCR image or from source
# Usage:
#   ./run-storage.sh [--ghcr|--source] up|down|restart|logs|pull|ps|rebuild
# Examples:
#   ./run-storage.sh --ghcr up       # run using GHCR image (default)
#   ./run-storage.sh --source up     # build from source and run
#   ./run-storage.sh --ghcr logs     # follow app logs
#   ./run-storage.sh --ghcr down     # stop & remove
#   ./run-storage.sh --source rebuild# force rebuild image, then up

MODE="ghcr"   # default mode
ACTION="${1:-}"
if [[ "${ACTION}" == "--ghcr" || "${ACTION}" == "--source" ]]; then
  MODE="${ACTION/--/}"
  ACTION="${2:-}"
fi

if [[ -z "${ACTION}" ]]; then
  cat >&2 <<EOF
Usage: $0 [--ghcr|--source] up|down|restart|logs|pull|ps|rebuild
EOF
  exit 2
fi

# pick compose file based on mode
COMPOSE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/docker" && pwd)"
if [[ "${MODE}" == "ghcr" ]]; then
  COMPOSE_FILE="${COMPOSE_DIR}/docker-compose-from-ghcr.yml"
else
  COMPOSE_FILE="${COMPOSE_DIR}/docker-compose-from-source.yml"
fi

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "Compose file not found: ${COMPOSE_FILE}" >&2
  exit 1
fi

# prefer "docker compose", fallback to "docker-compose"
compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose -f "${COMPOSE_FILE}" "$@"
  else
    docker-compose -f "${COMPOSE_FILE}" "$@"
  fi
}

# For source mode, ensure the Spring Boot jar exists if the Dockerfile expects build/libs/*
ensure_jar_if_needed() {
  if [[ "${MODE}" == "source" ]]; then
    # If Gradle wrapper exists and no jar in build/libs, build it
    if [[ -x "./gradlew" ]]; then
      if ! ls ./build/libs/*.jar >/dev/null 2>&1; then
        echo "Building Spring Boot jar (./gradlew bootJar)..."
        ./gradlew bootJar
      fi
    else
      echo "Warning: ./gradlew not found. If your Dockerfile needs build/libs/*.jar, the build may fail." >&2
    fi
  fi
}

case "${ACTION}" in
  up)
    ensure_jar_if_needed
    compose pull || true   # pulls mongo & app image (ghcr mode)
    compose up -d
    compose ps
    echo
    echo "Tip: follow logs with: $0 --${MODE} logs"
    echo "App should be on http://localhost:8080"
    ;;
  down)
    compose down -v
    ;;
  restart)
    compose down -v
    ensure_jar_if_needed
    compose up -d
    ;;
  logs)
    compose logs -f --tail=200
    ;;
  pull)
    # ghcr mode: pull images; source mode: pulls base images (mongo/jdk) only
    compose pull
    ;;
  ps)
    compose ps
    ;;
  rebuild)
    # force rebuild for source mode; ghcr mode will just re-pull latest
    if [[ "${MODE}" == "source" ]]; then
      ensure_jar_if_needed
      compose build --no-cache
    else
      compose pull
    fi
    compose up -d
    ;;
  *)
    echo "Unknown action: ${ACTION}" >&2
    exit 2
    ;;
esac
