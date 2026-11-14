"""An atomic GitHub Secret: private key and its password must never be split."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

SECRET = 'NOTELS_SIGNING_MATERIAL'


def credentials(raw=None):
    raw = os.environ.get(SECRET, '') if raw is None else raw
    if not raw:
        raise ValueError('Signing material Secret is required; no fallback key is generated')
    try:
        obj = json.loads(raw)
        if (not isinstance(obj, dict) or set(obj) != {'keystore_base64', 'password'} or
                not all(isinstance(v, str) and v for v in obj.values()) or len(obj['password']) > 256):
            raise ValueError()
    except (ValueError, TypeError):
        raise ValueError('Invalid signing material') from None
    try:
        key = base64.b64decode(obj['keystore_base64'], validate=True)
    except ValueError:
        raise ValueError('Invalid keystore encoding') from None
    if not key or len(key) > 49152:
        raise ValueError('Invalid keystore size')
    return key, obj['password']


def fingerprint(raw, keytool):
    key, password = credentials(raw)
    with tempfile.TemporaryDirectory(prefix='notels-existing-key-') as temp:
        path = Path(temp) / 'key.p12'
        path.touch(mode=0o600); path.write_bytes(key)
        result = subprocess.run([str(keytool), '-exportcert', '-alias', 'notels-2026',
                                 '-keystore', str(path), '-storepass:env', 'NOTELS_SETUP_PASSWORD'],
                                env={**os.environ, 'NOTELS_SETUP_PASSWORD': password}, capture_output=True)
        if result.returncode:
            raise ValueError('Existing signing material cannot be opened; do not replace it')
        return hashlib.sha256(result.stdout).hexdigest()
