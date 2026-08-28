#!/usr/bin/env bash
# Creates the certificate workspace-watcher serves HTTPS from.
#
# Two routes, and the difference matters more than it looks:
#
#   mkcert   installs a certificate authority into the system keychain and issues from it, so the
#            browser trusts the result completely. This is what makes Safari treat the dashboard as
#            a secure origin, which is what it requires before it will grant notifications.
#
#   openssl  issues a self-signed certificate. It encrypts exactly as well, but no browser trusts
#            it: you get a warning every time, and Safari still refuses notifications because an
#            untrusted certificate is not a secure context. Use it if you want HTTPS without
#            installing anything, not if you want the Safari behaviour.
#
# The watcher serves HTTPS as soon as this file exists, and plain HTTP when it does not - deleting
# it is how you go back.

set -euo pipefail

KEYSTORE="${WORKSPACE_WATCHER_KEYSTORE:-$HOME/.claude/workspace-watcher/keystore.p12}"
PASSWORD="${WORKSPACE_WATCHER_KEYSTORE_PASSWORD:-workspace-watcher}"
HOSTS=(localhost 127.0.0.1 ::1)
TRUST_PENDING=""

mkdir -p "$(dirname "$KEYSTORE")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if command -v mkcert >/dev/null 2>&1; then
  echo "Using mkcert."
  # Installing the CA needs sudo with a terminal, which a script run from a tool does not have.
  # Failing that is not a reason to stop: the certificate is still issued from the local CA and
  # becomes trusted the moment "mkcert -install" is run by hand, because trust attaches to the CA
  # rather than to this certificate.
  if ! mkcert -install >/dev/null 2>&1; then
    TRUST_PENDING=1
  fi
  mkcert -cert-file "$WORK/cert.pem" -key-file "$WORK/key.pem" "${HOSTS[@]}" >/dev/null 2>&1
else
  echo "mkcert not found, falling back to a self-signed certificate."
  echo "Your browser will warn about it, and Safari will still refuse notifications."
  echo "For a certificate the system trusts:  brew install mkcert && $0"
  openssl req -x509 -newkey rsa:2048 -sha256 -days 825 -nodes \
    -keyout "$WORK/key.pem" -out "$WORK/cert.pem" \
    -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:::1" 2>/dev/null
fi

# Java reads PKCS12 directly, so no keytool import step is needed.
openssl pkcs12 -export \
  -in "$WORK/cert.pem" -inkey "$WORK/key.pem" \
  -out "$KEYSTORE" -name workspace-watcher \
  -passout "pass:$PASSWORD"

chmod 600 "$KEYSTORE"
echo
echo "Wrote $KEYSTORE"
if [ -n "${TRUST_PENDING:-}" ]; then
  echo
  echo "The local CA is not trusted yet - that step needs an interactive password:"
  echo
  echo "    mkcert -install"
  echo
  echo "Until you run it, browsers will warn and Safari will still refuse notifications."
fi
echo "Restart the watcher and open https://localhost:8080"
