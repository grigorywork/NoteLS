import base64
import json
import pathlib
import subprocess
import sys

REPO = 'repos/grigorywork/NoteLS'

def api(path, data=None, method=None):
    args = ['gh', 'api', f'{REPO}/{path}']
    if method:
        args += ['--method', method]
    if data is not None:
        args += ['--input', '-']
    result = subprocess.run(args, input=json.dumps(data).encode() if data is not None else None,
                            capture_output=True, check=True)
    return json.loads(result.stdout) if result.stdout else None

def commit(branch, parent, root, message, paths):
    current = api(f'git/ref/heads/{branch}')['object']['sha']
    if current != parent:
        raise RuntimeError(f'Branch moved: expected {parent}, got {current}')
    tree = api(f'git/commits/{parent}')['tree']['sha']
    entries = []
    for path in paths:
        content = pathlib.Path(root, path).read_bytes()
        blob = api('git/blobs', {'content': base64.b64encode(content).decode(), 'encoding': 'base64'})
        entries.append({'path': path, 'mode': '100644', 'type': 'blob', 'sha': blob['sha']})
    new_tree = api('git/trees', {'base_tree': tree, 'tree': entries})['sha']
    sha = api('git/commits', {'message': message, 'tree': new_tree, 'parents': [parent]})['sha']
    if api(f'git/ref/heads/{branch}')['object']['sha'] != parent:
        raise RuntimeError('Branch moved before ref update; commit saved but not applied')
    api(f'git/refs/heads/{branch}', {'sha': sha, 'force': False}, 'PATCH')
    print(json.dumps({'branch': branch, 'parent': parent, 'sha': sha, 'paths': paths}))
    return sha

if __name__ == '__main__':
    if sys.argv[1] == 'commit':
        commit(*sys.argv[2:6], sys.argv[6:])
