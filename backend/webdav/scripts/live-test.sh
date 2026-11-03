#!/bin/sh
# Runs backend:webdav's tests against real WebDAV servers in containers: lighttpd, Apache httpd and
# rclone (backend/webdav/README.md). Run it from the repository root. Needs Docker, curl and
# python3. Each server listens on 127.0.0.1 only, on a free port, under the container's host
# network, and every container this starts is removed by name when the script ends.
#
# Usage: backend/webdav/scripts/live-test.sh [jvm|linuxX64]...   (default: jvm linuxX64)
set -eu

targets=${*:-jvm linuxX64}
servers=backend/webdav/servers
prefix=jotter-test-webdav-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')
started=""

# Images by digest, so a run tests exactly these servers. Tags as of 2026-11-03.
lighttpd_image=alpine@sha256:2e0b81d60597f449baa56dc25d355367869d2dca4cd57e866f1e2b52af3eb55f # 3.22
lighttpd_version=1.4.85-r0
apache_image=httpd@sha256:c6b7745aa371570cc8460a9f08c39989583d4aa7bc8e3ed8bd77a492803ea704 # 2.4.69
rclone_image=rclone/rclone@sha256:2687085f718d3c628f7fdfb77c52a1d344332aed543110bb88088f2c70d43eb5 # 1.75.2

cleanup() {
    for name in $started; do
        docker logs "$name" > "backend/webdav/build/live-test/$name.log" 2>&1 || true
        docker rm -f "$name" > /dev/null 2>&1 || true
    done
}
trap cleanup EXIT INT TERM
mkdir -p backend/webdav/build/live-test

free_port() {
    python3 -I -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'
}

# start NAME PORT DOCKER-RUN-ARGUMENTS...
start() {
    name=$prefix-$1
    port=$2
    shift 2
    started="$started $name"
    docker run -d --name "$name" --network host -e "PORT=$port" "$@" > /dev/null
}

wait_for() {
    url=$1
    tries=0
    until [ "$(curl -s -o /dev/null -w '%{http_code}' -u jotter:secret -X PROPFIND -H 'Depth: 0' "$url")" = 207 ]; do
        tries=$((tries + 1))
        if [ "$tries" -ge 120 ]; then
            echo "No WebDAV server at $url" >&2
            exit 1
        fi
        sleep 0.5
    done
}

lighttpd_port=$(free_port)
start lighttpd "$lighttpd_port" -v "$PWD/$servers/lighttpd/lighttpd.conf:/conf/lighttpd.conf:ro" \
    "$lighttpd_image" sh -c "apk add --no-cache lighttpd=$lighttpd_version \
        lighttpd-mod_webdav=$lighttpd_version lighttpd-mod_auth=$lighttpd_version > /dev/null &&
        mkdir -p /srv/dav /var/lib/lighttpd && chown lighttpd /srv/dav /var/lib/lighttpd &&
        echo jotter:secret > /etc/lighttpd/users && exec lighttpd -D -f /conf/lighttpd.conf"

apache_port=$(free_port)
start apache "$apache_port" -v "$PWD/$servers/apache/httpd.conf:/conf/httpd.conf:ro" \
    "$apache_image" sh -c 'mkdir -p /srv/dav /usr/local/apache2/var &&
        chown daemon:daemon /srv/dav /usr/local/apache2/var &&
        htpasswd -cbB /usr/local/apache2/conf/htpasswd jotter secret &&
        exec httpd -DFOREGROUND -f /conf/httpd.conf'

rclone_port=$(free_port)
start rclone "$rclone_port" "$rclone_image" serve webdav /data \
    --addr "127.0.0.1:$rclone_port" --user jotter --pass secret

wait_for "http://127.0.0.1:$lighttpd_port/"
wait_for "http://127.0.0.1:$apache_port/"
wait_for "http://127.0.0.1:$rclone_port/"

tasks=""
for target in $targets; do
    tasks="$tasks :backend:webdav:${target}Test"
    rm -rf "backend/webdav/build/test-results/${target}Test"
done

# Apache's If-None-Match is not atomic: concurrent writers of one path can all win
# (docs/adr/0017-webdav-backend.md). That one contract test is known to fail there.
# shellcheck disable=SC2086 # $tasks is a list of task names.
JOTTER_WEBDAV_LIVE=1 \
    JOTTER_WEBDAV_LIGHTTPD_URL="http://127.0.0.1:$lighttpd_port/" \
    JOTTER_WEBDAV_APACHE_URL="http://127.0.0.1:$apache_port/" \
    JOTTER_WEBDAV_RCLONE_URL="http://127.0.0.1:$rclone_port/" \
    JOTTER_WEBDAV_LIVE_EXCLUDE=ApacheContractTest.concurrentWritersOfOnePathHaveExactlyOneWinner \
    ./gradlew $tasks --rerun --continue

# A filter that matched nothing would pass silently, so check that the live tests ran.
for target in $targets; do
    results=backend/webdav/build/test-results/${target}Test
    for class in LighttpdContractTest ApacheContractTest RcloneTest LiveFaultTest; do
        if ! ls "$results"/*"$class".xml > /dev/null 2>&1; then
            echo "$class did not run on $target" >&2
            exit 1
        fi
    done
done
