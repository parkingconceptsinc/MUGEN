#!/usr/bin/env bash
# Run on the machine that holds the production keystore; never paste secrets in chat.
set +x
set -euo pipefail
umask 077

task_sign_repo='github.com/parkingconceptsinc/MUGEN'
task_sign_create=false
task_sign_replace=false

usage() {
  cat <<'HELP'
Usage: bash scripts/configure-android-signing.sh [--create | --replace]

Validates a production keystore, then saves the four Android Actions secrets
in parkingconceptsinc/MUGEN. Passwords are requested privately in the terminal.

  --create   Generate the FIRST production key at a new path outside this checkout.
             Back it up securely: future Android updates must use this same key.
  --replace  Update existing secret configuration using an EXISTING keystore.
             This does not authorize changing the installed app's signing key.

Requires Java keytool, GitHub CLI authenticated with repository secrets access,
base64, and an interactive terminal. Does not publish or rerun a release.
HELP
}

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }

for task_sign_arg in "$@"; do
  case "$task_sign_arg" in
    --create) task_sign_create=true ;;
    --replace) task_sign_replace=true ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; fail 'Unknown option.' ;;
  esac
done
if "$task_sign_create" && "$task_sign_replace"; then
  fail '--create and --replace cannot be combined. Keep the existing production key.'
fi
[[ -t 0 ]] || fail 'Run this script in an interactive terminal.'
for task_sign_tool in keytool gh base64 git mktemp wc; do
  command -v "$task_sign_tool" >/dev/null || fail "Required command not found: $task_sign_tool"
done
gh auth status --hostname github.com >/dev/null 2>&1 || fail 'Authenticate GitHub CLI on this machine first (gh auth login --hostname github.com).'
task_sign_existing=$(gh secret list --repo "$task_sign_repo" --app actions --json name --jq '.[].name')
task_sign_has_secrets=false
while IFS= read -r task_sign_name; do
  case "$task_sign_name" in
    ANDROID_KEYSTORE_BASE64|ANDROID_KEYSTORE_PASSWORD|ANDROID_KEY_ALIAS|ANDROID_KEY_PASSWORD)
      task_sign_has_secrets=true ;;
  esac
done <<< "$task_sign_existing"
if "$task_sign_has_secrets" && ! "$task_sign_replace"; then
  fail 'Android signing secrets already exist. Use the existing production key and --replace to repair configuration.'
fi

task_sign_root=$(git -C "$(dirname "${BASH_SOURCE[0]}")/.." rev-parse --show-toplevel)
read -r -p 'Absolute keystore path (outside the checkout): ' task_sign_path
[[ "$task_sign_path" = /* ]] || fail 'Use an absolute path.'
[[ -d "$(dirname "$task_sign_path")" ]] || fail 'The destination directory does not exist.'
task_sign_parent=$(cd -P -- "$(dirname "$task_sign_path")" && pwd)
task_sign_path="$task_sign_parent/$(basename "$task_sign_path")"
case "$task_sign_path" in
  "$task_sign_root"|"$task_sign_root"/*) fail 'Store the production key outside the checkout.' ;;
esac
if "$task_sign_create"; then
  [[ ! -e "$task_sign_path" && ! -L "$task_sign_path" ]] || fail 'The destination already exists. Existing keys are never overwritten.'
else
  [[ -f "$task_sign_path" && ! -L "$task_sign_path" ]] || fail 'The existing keystore must be a regular file, not a symbolic link.'
fi
read -r -p 'Key alias: ' task_sign_alias
[[ -n "$task_sign_alias" ]] || fail 'The key alias cannot be empty.'
read -r -s -p 'Keystore password: ' task_sign_store_password
printf '\n'
[[ -n "$task_sign_store_password" ]] || fail 'The keystore password cannot be empty.'
if "$task_sign_create"; then
  [[ ${#task_sign_store_password} -ge 12 ]] || fail 'Use a new password of at least 12 characters.'
  read -r -s -p 'Repeat keystore password: ' task_sign_confirmation
  printf '\n'
  [[ "$task_sign_confirmation" = "$task_sign_store_password" ]] || fail 'Passwords do not match.'
  task_sign_key_password="$task_sign_store_password"
else
  read -r -s -p 'Key password (Enter = keystore password): ' task_sign_key_password
  printf '\n'
  task_sign_key_password="${task_sign_key_password:-$task_sign_store_password}"
fi

task_sign_tmp=$(mktemp -d "${TMPDIR:-/tmp}/mugen-signing.XXXXXX")
trap 'rm -rf -- "$task_sign_tmp"; unset task_sign_store_password task_sign_key_password task_sign_confirmation' EXIT
export MUGEN_SETUP_STORE_PASSWORD="$task_sign_store_password"
export MUGEN_SETUP_KEY_PASSWORD="$task_sign_key_password"
if "$task_sign_create"; then
  keytool -genkeypair -keystore "$task_sign_path" -storetype PKCS12 \
    -alias "$task_sign_alias" -keyalg RSA -keysize 4096 -validity 10000 \
    -dname 'CN=MUGEN Android Release' \
    -storepass:env MUGEN_SETUP_STORE_PASSWORD -keypass:env MUGEN_SETUP_KEY_PASSWORD
  chmod 600 "$task_sign_path"
  printf 'Key created. Back up this file and password securely before continuing.\n'
  read -r -p 'Type BACKED-UP after saving a secure backup: ' task_sign_backup
  [[ "$task_sign_backup" = 'BACKED-UP' ]] || fail 'No secrets uploaded. Keep and back up the generated key; rerun without --create.'
fi
# A certificate request proves that both passwords unlock a signing private key.
# Only the public certificate request is written temporarily; it is removed on exit.
keytool -certreq -alias "$task_sign_alias" -keystore "$task_sign_path" \
  -storepass:env MUGEN_SETUP_STORE_PASSWORD -keypass:env MUGEN_SETUP_KEY_PASSWORD \
  -file "$task_sign_tmp/verification.csr" >/dev/null
keytool -list -keystore "$task_sign_path" -alias "$task_sign_alias" \
  -storepass:env MUGEN_SETUP_STORE_PASSWORD
unset MUGEN_SETUP_STORE_PASSWORD MUGEN_SETUP_KEY_PASSWORD task_sign_confirmation
task_sign_encoded_size=$(base64 < "$task_sign_path" | wc -c)
(( task_sign_encoded_size <= 49152 )) || fail 'The encoded keystore exceeds the GitHub secret limit of 48 KiB. No secrets were changed.'
printf '\nConfirm this is the production certificate for MUGEN before saving configuration.\n'
read -r -p 'Type CONFIGURE to save Actions secrets: ' task_sign_confirm
[[ "$task_sign_confirm" = 'CONFIGURE' ]] || fail 'No secrets uploaded.'

# Secret values enter gh through stdin, never command arguments or source files.
printf '%s' "$task_sign_store_password" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$task_sign_repo" --app actions
printf '%s' "$task_sign_alias" | gh secret set ANDROID_KEY_ALIAS --repo "$task_sign_repo" --app actions
printf '%s' "$task_sign_key_password" | gh secret set ANDROID_KEY_PASSWORD --repo "$task_sign_repo" --app actions
# Write the keystore last so an incomplete initial setup remains blocked.
base64 < "$task_sign_path" | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$task_sign_repo" --app actions
printf '\nSigning secrets saved. Keep the keystore and passwords in your secure backup.\n'
printf 'To publish from the validated main branch:\n  gh workflow run android-release.yml --ref main --repo %s\n' "$task_sign_repo"
