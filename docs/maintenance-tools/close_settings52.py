"""Write release evidence only after independent CI and signed-APK verification."""
import base64
import datetime
import hashlib
import json
from pathlib import Path
import sys
from repo_api import api, commit

root = Path(sys.argv[1])
# This closure is specific to the confirmed settings/R8 regression.
# Ordinary debug CI alone cannot establish that release listener ownership survives GC.
release_gc = json.loads((root/'docs/settings52-release-gc-verification.json').read_text())
fixed_native = json.loads((root/'docs/settings52-native-fixed-three-hours.json').read_text())
assert release_gc['result']['status'] == 'success' and release_gc['result']['mode'] == 'require-live'
assert release_gc['result']['art_gc_confirmed'] is True
assert len(release_gc['result']['events']) == 3
assert all(event['ui_selected'] for event in release_gc['result']['events'])
assert fixed_native['tests'] == 1 and fixed_native['failures'] == fixed_native['ignored'] == 0
assert fixed_native['progress']['status'] == 'success'
assert fixed_native['progress']['active_ms'] >= 10800000
assert fixed_native['progress']['target_ms'] == 10800000
details = json.loads(Path(sys.argv[2]).read_text())
release = json.loads(Path(sys.argv[3]).read_text())
checks = json.loads(Path(sys.argv[4]).read_text())
sign_metadata = json.loads(Path(sys.argv[5]).read_text())
source = release['source']
assert release_gc['input']['source'] == source
assert release_gc['input']['apk_sha256'] == release['apk_sha256']
assert release_gc['result']['apk_sha256'] == release['apk_sha256']
assert release_gc['input']['signed_run_id'] == str(release['workflow_run']['id'])
assert fixed_native['source'] == 'ccd0d58d07e6004022439fe8b66b1c2235ea062b'
comparison = api('compare/'+source+'...'+fixed_native['source'])
assert comparison['behind_by'] == 0
assert {f['filename'] for f in comparison['files']} == {
    'app/src/test/java/com/notessync/app/audit/UiFreezeProbeTest.kt', '.github/workflows/android.yml'}
for diagnostic in [release_gc, fixed_native]:
    checked_run = api(f"actions/runs/{diagnostic['run_id']}")
    assert checked_run['status'] == 'completed' and checked_run['conclusion'] == 'success'
    assert checked_run['head_sha'] == diagnostic['source']
    checked_artifact = api(f"actions/artifacts/{diagnostic['artifact_id']}")
    assert checked_artifact['digest'] == 'sha256:'+diagnostic['zip_sha256']
    assert not checked_artifact['expired']
main_head = api('git/ref/heads/main')['object']['sha']
main_comparison = api('compare/'+source+'...'+main_head)
assert main_comparison['behind_by'] == 0
assert all(f['filename'].startswith('docs/') or f['filename']=='agent.md' for f in main_comparison['files'])
assert checks['workflow_run']['head_sha'] == source
assert checks['failures'] == 0 and checks['ignored'] == 0
assert checks['lint']['Error'] == 0 and checks['lint']['Fatal'] == 0
for key in ['version', 'code', 'apk_sha256', 'certificate_sha256']:
    assert sign_metadata[key] == release[key], key
ci_id = checks['workflow_run']['id']
signed_id = release['workflow_run']['id']
ci_run = api(f'actions/runs/{ci_id}')
signed_run = api(f'actions/runs/{signed_id}')
for run in [ci_run, signed_run]:
    assert run['status'] == 'completed' and run['conclusion'] == 'success'
    assert run['head_sha'] == source
assert signed_run['head_branch'] == 'main'
assert api('git/ref/heads/main')['object']['sha'] == main_head
schema, = (root/'app/schemas').rglob('18.json')
schema_hash = hashlib.sha256(schema.read_bytes()).hexdigest()
assert schema_hash == 'f6f988c94c5342796142e29df703f6d6e27bd0fc664a76b8ac8939dd006c1973'
ci_jobs = api(f'actions/runs/{ci_id}/jobs')['jobs']
signed_jobs = api(f'actions/runs/{signed_id}/jobs')['jobs']
assert all(j['status'] == 'completed' and j['conclusion'] == 'success' for j in ci_jobs + signed_jobs)
def seconds(step):
    return int((datetime.datetime.fromisoformat(step['completed_at'].replace('Z','+00:00')) -
                datetime.datetime.fromisoformat(step['started_at'].replace('Z','+00:00'))).total_seconds())
def summarize(jobs):
    return [{'id': j['id'], 'name': j['name'], 'status': j['status'], 'conclusion': j['conclusion'],
             'steps': [{'name': s['name'], 'conclusion': s['conclusion'], 'seconds': seconds(s)}
                       for s in j['steps'] if s.get('completed_at') and s.get('started_at')]} for j in jobs]
artifact_url = f"https://github.com/grigorywork/NoteLS/actions/runs/{signed_id}/artifacts/{release['artifact_id']}"
proof = {**release, 'stage': details['stage'], 'artifact_url': artifact_url,
         'minified_release_gc': release_gc, 'fixed_source_native_three_hours': fixed_native,
         'changes': details['changes_en'], 'known_limitations': details['limitations_en'],
         'audit_history': details.get('audit_history', []),
         'ci': {**checks, 'event': ci_run['event'], 'jobs': summarize(ci_jobs)},
         'signed_release': {'run_id': signed_id, 'event': signed_run['event'],
                            'jobs': summarize(signed_jobs), 'ci_metadata_output': sign_metadata},
         'room_schema': 18, 'room_schema_sha256': schema_hash, 'data_format': 2, 'security_format': 3,
         'data_or_security_format_changes': False, 'real_paid_api_requests_made': False,
         'main': {'fast_forward_from': details['main_from'], 'fast_forward_to': source,
                  'method': 'Git refs REST API, force=false'}}
slug = details['slug']
proof_path = f'docs/{slug}-verification.json'
report_path = f'docs/{slug}.md'
(root/proof_path).write_text(json.dumps(proof, ensure_ascii=False, indent=2)+'\n')
now = datetime.datetime.now(datetime.timezone.utc).isoformat()
summary = (f"Этап{details['stage']} закрыт: NoteLS{release['version']}/code{release['code']}, Room18/data2/security3. "
           f"Source `{source}`. Полный CI[{ci_id}](https://github.com/grigorywork/NoteLS/actions/runs/{ci_id}) "
           f"=**{checks['tests']} тестов/0 отказов/0 пропусков**, lint0errors/"
           f"{checks['lint']['Warning']}warnings/{checks['lint']['Information']}info. "
           f"Signed[{signed_id}](https://github.com/grigorywork/NoteLS/actions/runs/{signed_id}) SUCCESS. "
           "Независимо сверены ZIP/API digest/состав/SHA256SUMS/APK/source/package/version/nondebug/"
           "настоящий apksigner v2/v3/один signer/постоянный сертификат/DEX/Room18. "
           "Физическая установка не выполнялась.")
changes = '\n'.join('- '+x for x in details['changes_ru'])
limitations = '\n'.join('- '+x for x in details['limitations_ru'])
report = (f"# {details['title']} — NoteLS{release['version']}/code{release['code']}\n\n"
          + summary + f"\n\n## Изменения\n\n{changes}\n\n## Ограничения\n\n{limitations}\n\n"
          + details.get('audit_ru', '') + '\n\n'
          + f"APK SHA256 `{release['apk_sha256']}`. ZIP SHA256 `{release['archive_sha256']}`.\n\n"
          + f"[Машиночитаемые доказательства]({slug}-verification.json).\n\n{details['next_ru']}\n\n"
          + f"[Проверенный установочный APK в ZIP]({artifact_url})\n")
(root/report_path).write_text(report)
def remote_text(path):
    value = api('contents/'+path+'?ref='+main_head)
    return base64.b64decode(value['content']).decode()
next_chat = (f"## {details['stage']} закрыт — проверенный NoteLS{release['version']}/code{release['code']}\n\n"
             + summary + f"\n\n{changes}\n\n{details['next_ru']}\n\n"
             + f"[Отчёт]({slug}.md), [доказательства]({slug}-verification.json).\n\n"
             + f"APK: {artifact_url}\n\n")
(root/'docs/NEXT-CHAT.md').write_text(next_chat + remote_text('docs/NEXT-CHAT.md'))
(root/'agent.md').write_text(remote_text('agent.md') + f"\n\n{now}: {summary} " + details['next_ru']
                           + f" Proof{proof_path}. APK {artifact_url}\n")
sha = commit('main', main_head, str(root), f"[skip ci] Document verified stage {details['stage']} NoteLS {release['version']}",
             [report_path, proof_path, 'docs/NEXT-CHAT.md', 'agent.md'])
print(json.dumps({'stage': details['stage'], 'main_docs_commit': sha, 'apk': artifact_url}))
