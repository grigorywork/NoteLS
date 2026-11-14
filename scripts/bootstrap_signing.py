#!/usr/bin/env python3
"""One-time owner setup. Never run in CI; never publish private files or passwords."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess


def run(command, data=None, env=None):
    result = subprocess.run(command, input=data, capture_output=True, env=env)
    if result.returncode:
        # Do not print subprocess output: credentials must never become a CI/chat log.
        raise SystemExit('Signing setup failed. Credentials remain private; no alternate key was created.')
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--initialize-new-key', action='store_true', required=True)
    parser.add_argument('--private-dir', type=Path, required=True)
    parser.add_argument('--keytool', default='keytool')
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        raise SystemExit('Key generation in CI is forbidden')
    repo = 'grigorywork/NoteLS'
    pin = Path('.github/signing-certificate.sha256')
    if pin.exists():
        raise SystemExit('A certificate is already pinned. Do not replace the permanent identity.')
    # Check actual access before creating any permanent material.
    existing = json.loads(run(['gh', 'api', f'repos/{repo}/actions/secrets']))
    run(['gh', 'api', f'repos/{repo}/actions/secrets/public-key'])
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
    if not state.exists() and secret_names & {'NOTELS_KEYSTORE_BASE64', 'NOTELS_SIGNING_PASSWORD'}:
        raise SystemExit('Signing Secrets already exist. Refusing to overwrite their identity.')
    if not state.exists():
        if any(root.iterdir()):
            raise SystemExit('Private directory is not empty; inspect it instead of generating another key')
        password_file.touch(mode=0o600)
        password_file.write_text(secrets.token_urlsafe(48))
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
    run(['gh', 'secret', 'set', 'NOTELS_KEYSTORE_BASE64', '--repo', repo], data=base64.b64encode(key.read_bytes()))
    run(['gh', 'secret', 'set', 'NOTELS_SIGNING_PASSWORD', '--repo', repo], data=password_file.read_bytes())
    names = {s['name'] for s in json.loads(run(['gh', 'api', f'repos/{repo}/actions/secrets']))['secrets']}
    if not {'NOTELS_KEYSTORE_BASE64', 'NOTELS_SIGNING_PASSWORD'} <= names:
        raise SystemExit('Secrets were not confirmed; retain private material and resume this setup')
    pin.write_text(fingerprint + '\n')
    state.write_text(json.dumps({'key_created': True, 'secrets_saved': True, 'certificate_sha256': fingerprint}))
    print(json.dumps({'repository': repo, 'certificate_sha256': fingerprint,
                      'secrets_saved': True, 'ci_signature_verification_pending': True}))


if __name__ == '__main__':
    main()
