#!/usr/bin/env bash
# Optional JaCoCo support for the real-Keycloak integration scripts. Enable by setting
# JACOCO_COVERAGE_DIR to an absolute host directory before invoking a script.

JACOCO_DOCKER_ARGS=()

setup_jacoco() {
  [[ -n "${JACOCO_COVERAGE_DIR:-}" ]] || return 0
  JACOCO_AGENT="${JACOCO_AGENT:-$(pwd)/target/jacoco-agent/jacocoagent.jar}"
  [[ -f "${JACOCO_AGENT}" ]] || { echo "ERROR: JaCoCo agent missing: ${JACOCO_AGENT}" >&2; exit 1; }
  mkdir -p "${JACOCO_COVERAGE_DIR}"
  JACOCO_DOCKER_ARGS=(
    -v "${JACOCO_AGENT}:/opt/keycloak/jacocoagent.jar:ro"
    -e 'JAVA_OPTS_APPEND=-javaagent:/opt/keycloak/jacocoagent.jar=destfile=/tmp/jacoco.exec,append=false,includes=io.skycloak.keycloak.magiclink.*'
  )
}

collect_jacoco() {
  [[ -n "${JACOCO_COVERAGE_DIR:-}" ]] || return 0
  # JaCoCo writes its file during JVM shutdown. A force-remove skips that shutdown hook.
  docker stop -t 15 "${KC_NAME}" >/dev/null 2>&1 || true
  docker cp "${KC_NAME}:/tmp/jacoco.exec" "${JACOCO_COVERAGE_DIR}/${KC_NAME}.exec" 2>/dev/null || true
}
