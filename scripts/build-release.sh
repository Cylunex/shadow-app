#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
secrets_host=${SHADOW_SECRETS_HOST:-nas}
remote_secrets_dir=${SHADOW_REMOTE_SECRETS_DIR:-/data/project/.secrets/shadow-app}
expected_fingerprint=77E332BC57EE7540026DA37D0BCD74BBB25D438DF356FB28DE278CAD227A14EB
temp_root=${TMPDIR:-/tmp}
signing_dir=$(mktemp -d "$temp_root/shadow-signing.XXXXXX")

cleanup() {
    case "$signing_dir" in
        "$temp_root"/shadow-signing.*) rm -rf -- "$signing_dir" ;;
    esac
}
trap cleanup EXIT HUP INT TERM

umask 077
scp -q "$secrets_host:$remote_secrets_dir/shadow-release.jks" \
    "$signing_dir/shadow-release.jks"
scp -q "$secrets_host:$remote_secrets_dir/shadow-release.password" \
    "$signing_dir/shadow-release.password"

test -s "$signing_dir/shadow-release.jks"
test -s "$signing_dir/shadow-release.password"

SHADOW_SIGN_PASSWORD=$(tr -d '\n' < "$signing_dir/shadow-release.password")
export SHADOW_SIGN_PASSWORD
actual_fingerprint=$(keytool -exportcert -alias shadow \
    -keystore "$signing_dir/shadow-release.jks" \
    -storepass:env SHADOW_SIGN_PASSWORD 2>/dev/null \
    | openssl dgst -sha256 | awk '{print toupper($NF)}')
unset SHADOW_SIGN_PASSWORD
if [ "$actual_fingerprint" != "$expected_fingerprint" ]; then
    echo "Shadow 发布签名指纹不匹配，已拒绝构建。" >&2
    exit 1
fi
if [ "${1:-}" = "--verify-signing" ]; then
    echo "Shadow 发布签名验证通过。"
    exit 0
fi

export SHADOW_KEYSTORE_PATH="$signing_dir/shadow-release.jks"
export SHADOW_KEYSTORE_PASSWORD_FILE="$signing_dir/shadow-release.password"

cd "$repo_dir"
if [ "$#" -eq 0 ]; then
    set -- assembleRelease
fi
./gradlew --no-daemon "$@"
