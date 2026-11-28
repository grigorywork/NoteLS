"""GHA-only prefetch of the exact Robolectric runtimes, pinned to published SHA512 values.

The configured Robolectric resolver/version/checks remain unchanged. This populates its normal
Maven cache using the Google-hosted Central mirror after observed Central HTTP429 failures.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

SOURCES=('https://maven-central.storage-download.googleapis.com/maven2','https://repo.maven.apache.org/maven2')

class IntegrityError(RuntimeError): pass


def sha512(path):
    digest=hashlib.sha512()
    with path.open('rb') as source:
        for chunk in iter(lambda:source.read(1024*1024),b''):digest.update(chunk)
    return digest.hexdigest()


def materialize(target,relative,expected):
    assert re.fullmatch(r'[0-9a-f]{128}',expected)
    if target.exists():
        if sha512(target)!=expected:raise IntegrityError('Cached artifact differs from approved SHA512: '+relative)
        return {'path':relative,'sha512':expected,'bytes':target.stat().st_size,'origin':'verified-existing-cache'}
    target.parent.mkdir(parents=True,exist_ok=True)
    failures=[]
    for base in SOURCES:
        temporary=None
        try:
            request=urllib.request.Request(base+'/'+relative,headers={'User-Agent':'NoteLS-CI-runtime-prefetch'})
            with urllib.request.urlopen(request,timeout=60) as response:
                with tempfile.NamedTemporaryFile(dir=target.parent,prefix=target.name+'.pending-',delete=False) as sink:
                    temporary=Path(sink.name)
                    while chunk:=response.read(1024*1024):sink.write(chunk)
            if sha512(temporary)!=expected:raise IntegrityError('Downloaded artifact differs from approved SHA512: '+relative)
            size=temporary.stat().st_size
            os.replace(temporary,target);temporary=None
            return {'path':relative,'sha512':expected,'bytes':size,'origin':base}
        except (urllib.error.URLError,TimeoutError,ConnectionError) as error:
            failures.append(type(error).__name__+': '+str(error))
        finally:
            if temporary is not None:temporary.unlink(missing_ok=True)
    raise RuntimeError('Exact runtime unavailable from approved sources: '+relative+'; '+repr(failures))


def prepare(row,repository):
    relative=row['relative'];assert re.fullmatch(r'org/robolectric/android-all-instrumented/[A-Za-z0-9_.-]+/android-all-instrumented-[A-Za-z0-9_.-]+',relative)
    files=[]
    for extension in ['pom','jar']:
        name=relative+'.'+extension;expected=row['sha512'][extension]
        files.append(materialize(repository/name,name,expected))
        # Standard Maven sidecars; pinned to the independently compared published hashes.
        (repository/(name+'.sha512')).write_text(expected+'\n')
    pom=ET.parse(repository/(relative+'.pom')).getroot();ns={'m':'http://maven.apache.org/POM/4.0.0'}
    assert pom.findtext('m:groupId',namespaces=ns)=='org.robolectric'
    assert pom.findtext('m:artifactId',namespaces=ns)=='android-all-instrumented'
    assert pom.findtext('m:version',namespaces=ns)==row['version']
    return {'api':row['api'],'version':row['version'],'files':files}


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--repository',type=Path,default=Path.home()/'.m2/repository')
    parser.add_argument('--pins',type=Path,default=Path(__file__).with_name('robolectric-runtime-pins.json'))
    parser.add_argument('--output',type=Path,default=Path('app/build/reports/robolectric-runtime/runtime.json'))
    args=parser.parse_args()
    assert os.environ.get('GITHUB_ACTIONS')=='true','SDK runtime downloads are restricted to GitHub Actions'
    pins=json.loads(args.pins.read_text())
    assert pins['robolectric_version']=='4.14.1' and pins['instrumentation_version']==7
    build=Path('app/build.gradle.kts').read_text()
    assert 'org.robolectric:robolectric:'+pins['robolectric_version'] in build
    assert pins['all_published_checksums_equal'] is True
    assert {row['api'] for row in pins['artifacts']}=={26,27,28,30,33,35}
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        results=list(pool.map(lambda row:prepare(row,args.repository),pins['artifacts']))
    proof={'status':'success','exact_robolectric_version':pins['robolectric_version'],'instrumentation_version':7,
        'pins_sha256':hashlib.sha256(args.pins.read_bytes()).hexdigest(),'artifacts':results,
        'all_sha512_verified':True,'test_assertions_timeouts_and_versions_unchanged':True,'android_tests_executed_by_this_step':False}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(proof,indent=2)+'\n')
    print(json.dumps(proof))

if __name__=='__main__':main()
