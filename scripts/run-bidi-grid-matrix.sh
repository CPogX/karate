#!/usr/bin/env bash
set -euo pipefail

browsers=("${@:-chrome firefox MicrosoftEdge}")
for browser in ${browsers[@]}; do
  case "$browser" in
    chrome) image=selenium/standalone-chrome:latest; port=4544 ;;
    firefox) image=selenium/standalone-firefox:latest; port=4545 ;;
    MicrosoftEdge) image=selenium/standalone-edge:latest; port=4546 ;;
    *) echo "unsupported browser: $browser" >&2; exit 2 ;;
  esac
  name="karate-bidi-${browser,,}"
  docker run -d --rm --name "$name" --shm-size=2g -p "$port:4444" \
    -e SE_NODE_MAX_SESSIONS=2 -e SE_NODE_OVERRIDE_MAX_SESSIONS=true "$image" >/dev/null
  trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
  for _ in {1..120}; do
    curl -fsS "http://localhost:$port/status" | grep -q '"ready": true' && break
    sleep 1
  done
  mvn -Dmaven.repo.local=.m2repo -pl karate-core -am \
    -Dtest=BidiGridE2eTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dkarate.bidi.gridUrl="http://localhost:$port" \
    -Dkarate.bidi.browserName="$browser" test
  docker rm -f "$name" >/dev/null
  trap - EXIT
done
