import argparse
import hashlib
import html
import io
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile

TOOLS = Path('/workspace/scratch/notels-api/android-tools')
PIN = 'c94bdfcf4d5a4ce0cfa51809707bf94f9034b1a1a47cd89a2a13e9577845661c'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def archive(path, metadata):
    meta = json.loads(Path(metadata).read_text())
    body = Path(path).read_bytes()
    assert not meta['expired']
    assert len(body) == meta['size_in_bytes']
    assert 'sha256:' + digest(body) == meta['digest']
    z = ZipFile(io.BytesIO(body))
    assert len(z.namelist()) == len(set(z.namelist()))
    return z, {'artifact_id': meta['id'], 'archive_sha256': digest(body),
               'archive_bytes': len(body), 'workflow_run': meta['workflow_run']}

def ci(path, metadata):
    z, proof = archive(path, metadata)
    report = z.read('tests/testDebugUnitTest/index.html').decode()
    def count(name):
        m = re.search(r'<div class="infoBox[^"\n]*" id="' + name + r'">\s*<div class="counter">(\d+)</div>', report)
        assert m, name
        return int(m.group(1))
    proof.update(tests=count('tests'), failures=count('failures'), ignored=count('ignored'))
    assert proof['failures'] == 0 and proof['ignored'] == 0
    issues = ET.fromstring(z.read('lint-results-debug.xml')).findall('issue')
    proof['lint'] = {s: sum(i.get('severity') == s for i in issues) for s in ['Fatal','Error','Warning','Information']}
    assert proof['lint']['Error'] == 0 and proof['lint']['Fatal'] == 0
    proof['screenshots'] = {n: digest(z.read(n)) for n in z.namelist() if n.endswith('.png')}
    return proof

def release(path, metadata, source, version, code):
    z, proof = archive(path, metadata)
    name = f'NoteLS-{version}-code{code}-permanent-signature.apk'
    assert set(z.namelist()) == {name, 'README.txt', 'SHA256SUMS'}
    apk = z.read(name)
    actual = digest(apk)
    assert z.read('SHA256SUMS').decode().strip() == actual + '  ' + name
    readme = z.read('README.txt').decode()
    assert f'Источник: {source}\n' in readme
    assert f'Сертификат SHA256: {PIN}\n' in readme
    incoming = Path('/workspace/scratch/notels-api/verify-incoming')
    incoming.mkdir(exist_ok=True)
    target = incoming / name
    target.write_bytes(apk)
    signed = subprocess.check_output([str(TOOLS/'apksigner'), 'verify', '--verbose', '--print-certs', str(target)], text=True)
    assert re.findall(r'^Signer #\d+ certificate SHA-256 digest: (\w+)$',signed,re.M) == [PIN]
    assert 'Number of signers: 1' in signed
    for v in [2,3]:
        assert re.search(rf'Verified using v{v} scheme .*: true', signed), signed
    badging = subprocess.check_output([str(TOOLS/'aapt2'), 'dump', 'badging', str(target)],text=True)
    assert "package: name='com.notessync.app'" in badging
    assert f"versionCode='{code}'" in badging and f"versionName='{version}'" in badging
    assert 'application-debuggable' not in badging
    assert "minSdkVersion:'26'" in badging and "targetSdkVersion:'35'" in badging
    with ZipFile(io.BytesIO(apk)) as az:
        dex = {n:digest(az.read(n)) for n in az.namelist() if re.fullmatch(r'classes\d*\.dex',n)}
        assert dex
        assert any(b'b1c6c07627d80657a69a8afd3c98f6aa' in az.read(n) for n in dex)
    proof.update(source=source, package='com.notessync.app', version=version, code=code,
                 apk_name=name, apk_bytes=len(apk), apk_sha256=actual,
                 certificate_sha256=PIN, verified_schemes=[2,3], signers=1,
                 debuggable=False, dex_sha256=dex, readme_source_verified=True,
                 min_sdk=26, target_sdk=35, physical_device_tested=False)
    (Path('/workspace/library-files') / name).write_bytes(apk)
    Path(str(path)+'.apksigner.txt').write_text(signed)
    Path(str(path)+'.badging.txt').write_text(badging)
    return proof

if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('kind', choices=['ci','release'])
    parser.add_argument('zip')
    parser.add_argument('metadata')
    parser.add_argument('--source')
    parser.add_argument('--version')
    parser.add_argument('--code',type=int)
    args=parser.parse_args()
    proof=ci(args.zip,args.metadata) if args.kind=='ci' else release(args.zip,args.metadata,args.source,args.version,args.code)
    Path(str(args.zip)+'.verification.json').write_text(json.dumps(proof,indent=2)+'\n')
    print(json.dumps(proof,ensure_ascii=False))
