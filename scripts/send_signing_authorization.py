#!/usr/bin/env python3
"""Deliver only opaque ciphertext to the owner's trusted one-time Actions receiver."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
from signing_setup_channel import seal, REPO


def command(args, data=None, env=None):
    result = subprocess.run(args, input=data, env=env, capture_output=True)
    if result.returncode:
        raise SystemExit('Owner authorization delivery failed; no credential values are logged')
    return result.stdout


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--public-zip', type=Path, required=True)
    p.add_argument('--zip-sha256', required=True)
    p.add_argument('--run-id', required=True)
    p.add_argument('--attempt', required=True)
    p.add_argument('--source-sha', required=True)
    p.add_argument('--owner-config', required=True)
    args = p.parse_args()
    if hashlib.sha256(args.public_zip.read_bytes()).hexdigest() != args.zip_sha256.removeprefix('sha256:'):
        raise SystemExit('Public receiver artifact digest mismatch')
    with zipfile.ZipFile(args.public_zip) as z:
        if set(z.namelist()) != {'public.pem', 'request.json'}:
            raise SystemExit('Unexpected receiver artifact contents')
        request = json.loads(z.read('request.json'))
        public = z.read('public.pem')
    if (request['repository'], request['run_id'], request['attempt'], request['source_sha']) != (
            REPO, args.run_id, args.attempt, args.source_sha):
        raise SystemExit('Receiver does not match the requested trusted workflow run')
    owner = {k: v for k, v in os.environ.items() if k not in {'GH_TOKEN', 'GITHUB_TOKEN'}}
    owner['GH_CONFIG_DIR'] = args.owner_config
    token = command(['gh', 'auth', 'token', '--hostname', 'github.com'], env=owner).decode().strip()
    with tempfile.TemporaryDirectory(prefix='notels-public-receiver-') as temp:
        path = Path(temp) / 'public.pem'; path.write_bytes(public)
        payload = json.dumps({'ciphertext': seal(token, request, path)}).encode()
    # This isolated orphan branch contains no source changes and no plaintext credentials.
    blob = command(['git', 'hash-object', '-w', '--stdin'], payload).decode().strip()
    tree = command(['git', 'mktree'], f'100644 blob {blob}\tpayload.json\n'.encode()).decode().strip()
    commit = command(['git', 'commit-tree', tree], b'Encrypted owner authorization for one-time signing setup [skip ci]\n').decode().strip()
    branch = f"notels-setup/{args.run_id}-{args.attempt}"
    command(['git', 'push', 'origin', f'{commit}:refs/heads/{branch}'])
    print(json.dumps({'branch': branch, 'ciphertext_sha256': hashlib.sha256(payload).hexdigest(),
                      'receiver_public_key_sha256': request['public_key_sha256']}))


if __name__ == '__main__':
    main()
