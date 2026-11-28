"""Stage92: actual committed footer dates in the signed/minified APK on disposable emulators."""
import argparse
import datetime
import hashlib
import json
import re
import sqlite3
from pathlib import Path
from zoneinfo import ZoneInfo
import release_settings_gc_probe as ui
import release_running_task_probe as running
from release_subtasks_probe import open_parent, replace_database
from live_hierarchy_probe import LiveHierarchy

OUT = Path('release-settings-probe/editor-dates')
DATE = re.compile(r'\d{1,2}\.\d{1,2}\.\d{2,4},?\s+\d{1,2}:\d{2}')


def footer(root):
    values = []
    for label in ['Создано', 'Изменено']:
        path = ui.match_path(root, label)
        assert path, 'Missing actual footer label: '+label
        box = ui.bounds(path[-1]); assert box, 'Footer label has no visible bounds'
        candidates = []
        for node in root.iter():
            text = node.get('text', '')
            bounds = ui.bounds(node)
            if bounds and DATE.fullmatch(text) and bounds[1] >= box[3]-1 and min(bounds[2],box[2]) > max(bounds[0],box[0]):
                candidates.append((bounds[1]-box[3], text, bounds))
        assert candidates, 'No visible date below footer label: '+label
        candidates.sort(key=lambda item:item[0]); values.append(candidates[0][1:])
    created, modified = values
    assert created[1][2] <= modified[1][0], 'Creation and modification columns overlap or are reversed'
    return {'created':created[0], 'modified':modified[0], 'created_bounds':created[1], 'modified_bounds':modified[1]}


def visible_footer():
    root=ui.tree()
    if not all(ui.match_path(root,label) for label in ['Создано','Изменено']): return None
    return footer(root)


def matches(actual, timestamp, zone):
    actual=' '.join(actual.split())
    date = datetime.datetime.fromtimestamp(timestamp/1000, zone)
    # Locale RU SHORT differs by Android/Java date data between two- and four-digit year.
    return actual in {date.strftime('%d.%m.%Y, %H:%M'), date.strftime('%d.%m.%y, %H:%M'),
                      date.strftime('%d.%m.%Y %H:%M'), date.strftime('%d.%m.%y %H:%M')}


def read_note(folder, id):
    connection, _, _ = running.copy_database(folder)
    connection.row_factory = sqlite3.Row
    value = dict(connection.execute('SELECT * FROM notes WHERE id=?', (id,)).fetchone())
    connection.close(); return value


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--apk',required=True); parser.add_argument('--output',required=True)
    parser.add_argument('--schema-json',required=True); args=parser.parse_args()
    global OUT
    OUT=Path(args.output); OUT.mkdir(parents=True,exist_ok=True); ui.OUT=OUT
    assert ui.adb('shell','getprop','ro.kernel.qemu').strip()=='1', 'Never seed a physical or user device'
    schema=json.loads(Path(args.schema_json).read_text())['database']
    helper=LiveHierarchy(); helper.prepare(); ui.tree=helper.tree
    ui.adb('root'); ui.adb('wait-for-device'); ui.adb('install','-r',args.apk)
    sdk=int(ui.adb('shell','getprop','ro.build.version.sdk').strip())
    ui.adb('shell','am','start','-W','-n',ui.PACKAGE+'/.MainActivity')
    ui.adb('shell','am','force-stop',ui.PACKAGE)
    zone=ZoneInfo(ui.adb('shell','getprop','persist.sys.timezone').strip() or 'UTC')
    connection,remote,target=running.copy_database(OUT/'fixture-input')
    assert connection.execute('PRAGMA user_version').fetchone()==(schema['version'],)
    assert connection.execute('SELECT identity_hash FROM room_master_table WHERE id=42').fetchone()==(schema['identityHash'],)
    now=int(ui.adb('shell','date','+%s').strip())*1000
    book=running.insert(connection,'notebooks',{'syncId':f'dates-probe-book-{sdk}','name':'Dates probe notebook'})
    identity=f'dates-probe-note-{sdk}'
    id=running.insert(connection,'notes',{'syncId':identity,'notebookId':book,'content':'Dates body original',
        'createdAt':now-2*86_400_000,'updatedAt':now-86_400_000})
    replace_database(connection,target,remote)
    open_parent(id,identity)
    before=running.wait(visible_footer,'Committed footer did not appear')
    assert matches(before['created'],now-2*86_400_000,zone), before
    assert matches(before['modified'],now-86_400_000,zone), before
    ui.snapshot('dates-before')
    ui.tap('Dates body original'); ui.adb('shell','input','keyevent','KEYCODE_MOVE_END')
    ui.adb('shell','input','text','%sSaved')
    running.wait(lambda:ui.match_path(ui.tree(),'Dates body original Saved'),'Real body edit was not entered')
    def changed_footer():
        value=visible_footer()
        return value if value and value['modified']!=before['modified'] and value['created']==before['created'] else None
    edited=running.wait(changed_footer,'Successful autosave did not update the visible footer')
    ui.snapshot('dates-edited')
    ui.tap('Назад'); running.wait(lambda:ui.match_path(ui.tree(),'Новая заметка'),'Actual editor exit did not complete')
    ui.adb('shell','am','force-stop',ui.PACKAGE)
    saved=read_note(OUT/'fixture-saved',id)
    assert saved['syncId']==identity and saved['notebookId']==book and not saved['isDeleted']
    assert saved['content']=='Dates body original Saved' and saved['createdAt']==now-2*86_400_000
    assert saved['updatedAt']>now-86_400_000 and matches(edited['modified'],saved['updatedAt'],zone)
    open_parent(id,identity)
    restored=running.wait(visible_footer,'Footer not restored after reopen')
    assert restored['created']==edited['created'] and restored['modified']==edited['modified']
    ui.adb('shell','input','keyevent','HOME'); pid=running.process_id(); assert re.fullmatch(r'\d+',pid)
    ui.adb('shell','kill','-9',pid); running.wait(lambda:not running.process_id(),'Fixture process is still running')
    open_parent(id,identity)
    restarted=running.wait(visible_footer,'Footer missing after ordinary process death')
    assert restarted['created']==edited['created'] and restarted['modified']==edited['modified']
    ui.snapshot('dates-restarted')
    ui.tap('Назад'); running.wait(lambda:ui.match_path(ui.tree(),'Новая заметка'),'No-op exit did not complete')
    ui.adb('shell','am','force-stop',ui.PACKAGE)
    final=read_note(OUT/'fixture-final',id)
    assert final['createdAt']==saved['createdAt'] and final['updatedAt']==saved['updatedAt'], 'No-op reopen changed dates'
    assert final['content']==saved['content'] and final['notebookId']==book and final['syncId']==identity
    for folder in OUT.glob('fixture-*'):
        for file in folder.iterdir():file.unlink()
        folder.rmdir()
    result={'status':'success','api':sdk,'apk_sha256':hashlib.sha256(Path(args.apk).read_bytes()).hexdigest(),
        'room_version':schema['version'],'room_identity':schema['identityHash'],'minified_installed_apk':True,
        'actual_android_emulator':True,'actual_footer_left_right':True,'actual_text_edit_autosave':True,
        'visible_date_matches_committed_database':True,'created_preserved':True,'no_op_dates_unchanged':True,
        'reopen_process_death_preserves_dates':True,'notebook_document_identity_preserved':True,'physical_phone':False}
    (OUT/'dates-result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))


if __name__=='__main__':
    try:main()
    except BaseException as error:
        OUT.mkdir(parents=True,exist_ok=True)
        (OUT/'dates-failure.json').write_text(json.dumps({'status':'failure','error_type':type(error).__name__,'message':str(error)}))
        try:ui.snapshot('dates-failure')
        except BaseException:pass
        raise
