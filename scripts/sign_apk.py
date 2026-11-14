#!/usr/bin/env python3
"""Sign a release with an existing private key and a repository-pinned public certificate."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def certificate_digest(output):
    values = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', output, re.M)
    if len(values) != 1:
        raise ValueError('Expected exactly one signing certificate')
    return values[0].lower()


def sign(apk, metadata, certificate, output, tools):
    expected = certificate.read_text().strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise ValueError('Missing or invalid pinned signing certificate')
    encoded = os.environ.get('NOTELS_KEYSTORE_BASE64', '')
    password = os.environ.get('NOTELS_SIGNING_PASSWORD', '')
    if not encoded or not password:
        raise ValueError('Signing Secrets are required; no fallback key is generated')
    try:
        key = base64.b64decode(encoded, validate=True)
    except ValueError:
        raise ValueError('Invalid keystore encoding') from None
    if not key or len(key) > 65536:
        raise ValueError('Invalid keystore size')
    info = json.loads(metadata.read_text())['elements'][0]
    version, code = info['versionName'], info['versionCode']
    if not re.fullmatch(r'\d+(?:\.\d+)*', version) or not isinstance(code, int):
        raise ValueError('Invalid APK version metadata')
    badging = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
    if ("name='com.notessync.app'" not in badging or
            f"versionName='{version}'" not in badging or f"versionCode='{code}'" not in badging or
            'application-debuggable' in badging):
        raise ValueError('Expected a non-debuggable NoteLS release with matching metadata')
    # Do not expose a wrongly signed APK or private key in the upload directory.
    with tempfile.TemporaryDirectory(prefix='notels-sign-') as temp:
        root = Path(temp)
        keyfile, signed = root / 'signing.p12', root / 'signed.apk'
        keyfile.touch(mode=0o600)
        keyfile.write_bytes(key)
        signer = str(tools / 'apksigner')
        env = {**os.environ, 'NOTELS_SIGNING_PASSWORD': password}
        # Capture stderr too: provider exceptions must not leak a credential into CI logs.
        command = [signer, 'sign', '--ks', str(keyfile), '--ks-key-alias', 'notels-2026',
                   '--ks-pass', 'env:NOTELS_SIGNING_PASSWORD', '--key-pass', 'env:NOTELS_SIGNING_PASSWORD',
                   '--out', str(signed), str(apk)]
        result = subprocess.run(command, env=env, capture_output=True, text=True)
        if result.returncode:
            raise ValueError('APK signing failed; check the configured signing Secrets')
        verified = subprocess.run([signer, 'verify', '--verbose', '--print-certs', str(signed)],
                                  capture_output=True, text=True)
        if verified.returncode or certificate_digest(verified.stdout) != expected:
            raise ValueError('APK certificate does not match the pinned permanent certificate')
        output.mkdir(parents=True, exist_ok=True)
        if any(output.iterdir()):
            raise ValueError('Output directory must be empty')
        name = f'NoteLS-{version}-code{code}-permanent-signature.apk'
        target = output / name
        target.write_bytes(signed.read_bytes())
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        (output / 'SHA256SUMS').write_text(f'{digest}  {name}\n')
        (output / 'README.txt').write_text(
            'NoteLS: новая постоянная подпись с версии1.10.37/code60.\n'
            'Первая установка требует удаления прежней версии с другой подписью.\n'
            'Перед удалением экспортируйте зашифрованную копию в Загрузки/другую папку вне приложения и сохраните её пароль.\n'
            'Биометрия прежней установки не заменяет переносимый пароль копии.\n'
            'Будущие обновления должны иметь этот же сертификат.\n'
            f'Источник: {os.environ.get("NOTELS_SOURCE_SHA", "local")}\n'
            f'Сертификат SHA256: {expected}\n', encoding='utf-8')
    return {'version': version, 'code': code, 'certificate_sha256': expected, 'apk_sha256': digest}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--metadata', type=Path, required=True)
    parser.add_argument('--certificate', type=Path, default=Path('.github/signing-certificate.sha256'))
    parser.add_argument('--output', type=Path, default=Path('signed-release-apk'))
    parser.add_argument('--tools', type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(sign(args.apk, args.metadata, args.certificate, args.output, args.tools)))
    except (ValueError, OSError, KeyError, subprocess.SubprocessError) as error:
        raise SystemExit(f'Release not published: {error}') from None
