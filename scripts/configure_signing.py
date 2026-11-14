#!/usr/bin/env python3
"""Owner-authorized one-time setup on GitHub's runner, not the managed connection."""
import argparse
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from signing_material import SECRET, fingerprint
from signing_setup_channel import REPO, prepare, unseal


def api(path, method='GET', data=None, missing=False):
    args = ['gh', 'api', '--method', method, path]
    if data is not None:
        args += ['--input', '-']
    result = subprocess.run(args, input=None if data is None else json.dumps(data).encode(), capture_output=True)
    if result.returncode:
        if missing and b'HTTP 404' in result.stderr:
            return None
        raise RuntimeError('Repository setup request failed; credential values are not logged')
    return json.loads(result.stdout) if result.stdout.strip() else {}


def output(name, value):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as f:
        f.write(f'{name}={value}\n')


def root():
    return Path(os.environ['RUNNER_TEMP']) / 'notels-owner-setup'


def prepare_receiver():
    material = os.environ.get(SECRET, '')
    pin = Path('.github/signing-certificate.sha256')
    if material:
        digest = fingerprint(material, Path(os.environ['JAVA_HOME']) / 'bin/keytool')
        if pin.exists() and pin.read_text().strip() != digest:
            raise ValueError('Existing permanent identity differs; never replace it')
        pin.write_text(digest + '\n')
        output('ready', 'false')
        return
    if pin.exists():
        raise ValueError('The pinned key is missing from Secrets; restore it instead of generating another key')
    prepare(root(), os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT'], os.environ['GITHUB_SHA'])
    output('ready', 'true')


def complete_receiver():
    folder = root()
    request = json.loads((folder / 'public/request.json').read_text())
    branch = f"notels-setup/{request['run_id']}-{request['attempt']}"
    limit = time.monotonic() + 900
    try:
        while time.monotonic() < limit:
            remote = api(f'repos/{REPO}/contents/payload.json?ref={branch}', missing=True)
            if remote is None:
                time.sleep(5)
                continue
            if remote.get('size', 999999) > 2048:
                raise ValueError('Invalid encrypted authorization size')
            document = json.loads(base64.b64decode(remote['content']))
            if set(document) != {'ciphertext'}:
                raise ValueError('Invalid encrypted authorization envelope')
            token = unseal(document['ciphertext'], request, folder / 'receiver-private.pem')
            env = {**os.environ, 'GH_TOKEN': token}
            env.pop('GITHUB_TOKEN', None)
            env.pop(SECRET, None)
            result = subprocess.run([sys.executable, 'scripts/bootstrap_signing.py', '--initialize-new-key',
                                     '--private-dir', str(folder / 'permanent-private'), '--owner-workflow',
                                     '--keytool', str(Path(os.environ['JAVA_HOME']) / 'bin/keytool')],
                                    env=env, capture_output=True)
            if result.returncode:
                raise RuntimeError('Owner signing setup failed before publication; no replacement is generated')
            token = None
            print(json.dumps({'certificate_sha256': Path('.github/signing-certificate.sha256').read_text().strip(),
                              'material_saved': True}))
            print('Permanent signing material saved; public certificate ready. No private files will be published.')
            return
        raise TimeoutError('Owner authorization was not delivered in time; no permanent key was generated')
    finally:
        try:
            api(f'repos/{REPO}/git/refs/heads/{branch}', method='DELETE', missing=True)
        finally:
            shutil.rmtree(folder, ignore_errors=True)


def publish_pin():
    digest = Path('.github/signing-certificate.sha256').read_text().strip()
    if len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
        raise ValueError('Invalid public certificate')
    path = f'repos/{REPO}/contents/.github/signing-certificate.sha256'
    existing = api(path + '?ref=main', missing=True)
    if existing is not None:
        if base64.b64decode(existing['content']).decode().strip() != digest:
            raise ValueError('A different permanent certificate is already pinned')
        print('The permanent certificate is already pinned.')
        return
    result = api(path, method='PUT', data={'message': 'Pin owner-authorized permanent signing certificate [skip ci]',
                 'branch': 'main', 'content': base64.b64encode((digest + '\n').encode()).decode()})
    print(json.dumps({'certificate_sha256': digest, 'public_pin_commit': result['commit']['sha']}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'complete', 'publish'])
    args = parser.parse_args()
    if (os.environ.get('GITHUB_WORKFLOW') != 'Configure permanent signing' or
            os.environ.get('GITHUB_EVENT_NAME') != 'workflow_dispatch' or
            os.environ.get('GITHUB_REF') != 'refs/heads/main' or os.environ.get('GITHUB_REPOSITORY') != REPO):
        raise SystemExit('This setup is restricted to an explicit trusted main workflow dispatch')
    try:
        {'prepare': prepare_receiver, 'complete': complete_receiver, 'publish': publish_pin}[args.action]()
    except Exception:
        raise SystemExit('Signing setup stopped safely; no credential values are logged') from None
