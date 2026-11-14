#!/usr/bin/env python3
"""One-time owner setup; regular CI may never initialize a permanent identity."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
from signing_material import SECRET


def run(command, data=None, env=None):
    result = subprocess.run(command, input=data, capture_output=True, env=env)
    if result.returncode:
        # Do not print subprocess output: credentials must never become a CI/chat log.
        raise SystemExit('Signing setup failed. Retain the private setup directory and resume; never generate a replacement.')
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--initialize-new-key', action='store_true', required=True)
    parser.add_argument('--private-dir', type=Path, required=True)
    parser.add_argument('--keytool', default='keytool')
    parser.add_argument('--owner-workflow', action='store_true')
    args = parser.parse_args()
    dedicated_owner_run = (args.owner_workflow and os.environ.get('GITHUB_WORKFLOW') == 'Configure permanent signing' and
                           os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and
                           os.environ.get('GITHUB_REPOSITORY') == 'grigorywork/NoteLS' and
                           os.environ.get('GITHUB_REF') == 'refs/heads/main')
    if os.environ.get('GITHUB_ACTIONS') == 'true' and not dedicated_owner_run:
        raise SystemExit('Key generation in CI is forbidden')
    repo = 'grigorywork/NoteLS'
    pin = Path('.github/signing-certificate.sha256')
    if pin.exists():
        raise SystemExit('A certificate is already pinned. Do not replace the permanent identity.')
    # Check actual access before creating any permanent material.
    existing = json.loads(run(['gh', 'api', f'repos/{repo}/actions/secrets']))
    run(['gh', 'api', f'repos/{repo}/actions/secrets/public-key'])
    if dedicated_owner_run and json.loads(run(['gh', 'api', 'user']))['login'] != 'grigorywork':
        raise SystemExit('Only the repository owner can initialize this identity')
    if args.private_dir.is_symlink():
        raise SystemExit('Private directory must not be a symlink')
    root = args.private_dir.resolve()
    if root.is_relative_to(Path.cwd().resolve()):
        raise SystemExit('Keep private files outside the repository')
    if root in {Path('/'), Path.home().resolve()}:
        raise SystemExit('Use a dedicated private directory')
    if root.exists() and any(root.iterdir()) and not (root / 'setup-state.json').is_file():
        raise SystemExit('Private directory is not empty; inspect it instead of generating another key')
    if root.exists() and any(p.is_symlink() for p in root.iterdir()):
        raise SystemExit('Private material must not be a symlink')
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    root.chmod(0o700)
    key, password_file, state = root / 'notels-permanent-2026.p12', root / 'password', root / 'setup-state.json'
    secret_names = {s['name'] for s in existing.get('secrets', [])}
    if not state.exists() and SECRET in secret_names:
        raise SystemExit('Signing Secrets already exist. Refusing to overwrite their identity.')
    if not state.exists():
        if any(root.iterdir()):
            raise SystemExit('Private directory is not empty; inspect it instead of generating another key')
        # Only one initializer may reserve credentials, even if two owner commands race.
        try:
            descriptor = os.open(password_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            raise SystemExit('Another setup reserved this identity; inspect or resume it instead of replacing it')
        with os.fdopen(descriptor, 'w') as password_output:
            password_output.write(secrets.token_urlsafe(48))
        env = {**os.environ, 'NOTELS_SETUP_PASSWORD': password_file.read_text()}
        run([args.keytool, '-genkeypair', '-alias', 'notels-2026', '-keyalg', 'RSA', '-keysize', '3072',
             '-sigalg', 'SHA256withRSA', '-validity', '10000', '-dname', 'CN=NoteLS', '-storetype', 'PKCS12',
             '-keystore', str(key), '-storepass:env', 'NOTELS_SETUP_PASSWORD',
             '-keypass:env', 'NOTELS_SETUP_PASSWORD'], env=env)
        key.chmod(0o600)
        state.touch(mode=0o600)
        state.write_text(json.dumps({'key_created': True}))
    if not key.is_file() or not password_file.is_file():
        raise SystemExit('Incomplete setup: recover the existing private material, do not generate a replacement')
    env = {**os.environ, 'NOTELS_SETUP_PASSWORD': password_file.read_text()}
    cert = run([args.keytool, '-exportcert', '-alias', 'notels-2026', '-keystore', str(key),
                '-storepass:env', 'NOTELS_SETUP_PASSWORD'], env=env)
    fingerprint = hashlib.sha256(cert).hexdigest()
    material = json.dumps({'keystore_base64': base64.b64encode(key.read_bytes()).decode(),
                           'password': password_file.read_text()}, separators=(',', ':')).encode()
    run(['gh', 'secret', 'set', SECRET, '--repo', repo], data=material)
    names = {s['name'] for s in json.loads(run(['gh', 'api', f'repos/{repo}/actions/secrets']))['secrets']}
    if SECRET not in names:
        raise SystemExit('Secrets were not confirmed; retain private material and resume this setup')
    pin.write_text(fingerprint + '\n')
    state.write_text(json.dumps({'key_created': True, 'secrets_saved': True, 'certificate_sha256': fingerprint}))
    print(json.dumps({'repository': repo, 'certificate_sha256': fingerprint,
                      'secrets_saved': True, 'ci_signature_verification_pending': True}))


if __name__ == '__main__':
    main()
