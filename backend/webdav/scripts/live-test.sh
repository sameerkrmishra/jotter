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
# lighttpd comes from Debian's snapshot archive at a fixed time, which never drops a package
# build, unlike Alpine's repositories. See backend/webdav/README.md for why not an image.
lighttpd_image=debian@sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4 # trixie-slim
lighttpd_snapshot=20261101T000000Z
lighttpd_version=1.4.79-2
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
    "$lighttpd_image" sh -c "rm -f /etc/apt/sources.list.d/* &&
        echo 'deb [check-valid-until=no] http://snapshot.debian.org/archive/debian/$lighttpd_snapshot trixie main' \
            > /etc/apt/sources.list &&
        apt-get -qq update && DEBIAN_FRONTEND=noninteractive apt-get -qq install --no-install-recommends \
            lighttpd=$lighttpd_version lighttpd-mod-webdav=$lighttpd_version > /dev/null &&
        mkdir -p /srv/dav /var/lib/lighttpd /etc/lighttpd &&
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
# Two apps syncing through lighttpd, end to end (app/shared-ui, JVM only).
case " $targets " in
    *" jvm "*)
        tasks="$tasks :app:shared-ui:jvmTest"
        rm -rf app/shared-ui/build/test-results/jvmTest
        ;;
esac

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
case " $targets " in
    *" jvm "*)
        if ! ls app/shared-ui/build/test-results/jvmTest/*WebDavAppsLiveTest.xml > /dev/null 2>&1; then
            echo "WebDavAppsLiveTest did not run" >&2
            exit 1
        fi
        ;;
esac
