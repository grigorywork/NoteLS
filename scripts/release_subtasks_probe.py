"""Actual signed/minified UI-plus and child-native-controls proof on disposable rooted CI emulators."""
import argparse
import hashlib
import json
import re
import sqlite3
import time
from pathlib import Path
import release_settings_gc_probe as ui
import release_running_task_probe as running
from live_hierarchy_probe import LiveHierarchy

OUT = Path('release-settings-probe/subtasks')
PACKAGE = ui.PACKAGE


def replace_database(connection, target, remote):
    connection.commit(); connection.execute('PRAGMA wal_checkpoint(TRUNCATE)'); connection.close()
    ui.adb('push', target, '/data/local/tmp/notels-subtasks-fixture.db')
    ui.adb('shell', f'cat /data/local/tmp/notels-subtasks-fixture.db > {remote}')
    ui.adb('shell', 'rm', '-f', remote+'-wal', remote+'-shm', '/data/local/tmp/notels-subtasks-fixture.db')


def open_parent(id, sync):
    ui.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/.MainActivity',
           '-a', 'com.notessync.app.widget.OPEN_NOTE', '--el', 'widgetNoteId', id, '--es', 'widgetNoteSyncId', sync)


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--apk', required=True); parser.add_argument('--output', required=True)
    parser.add_argument('--schema-json', required=True); args = parser.parse_args()
    global OUT
    OUT = Path(args.output); OUT.mkdir(parents=True, exist_ok=True); ui.OUT = OUT
    assert ui.adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Never seed a physical/user device'
    profile = json.loads(Path(args.schema_json).read_text())['database']; assert profile['version'] >= 20
    helper = LiveHierarchy(); helper.prepare(); ui.tree = helper.tree
    ui.adb('root'); ui.adb('wait-for-device'); ui.adb('install', '-r', args.apk)
    sdk = int(ui.adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    ui.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/.MainActivity')
    ui.adb('shell', 'am', 'force-stop', PACKAGE)
    connection, remote, target = running.copy_database(OUT/'fixture-ui')
    assert connection.execute('PRAGMA user_version').fetchone() == (profile['version'],)
    assert connection.execute('SELECT identity_hash FROM room_master_table WHERE id=42').fetchone() == (profile['identityHash'],)
    # The preceding stage90 probe already verified its own clocks. Isolate ONLY those old synthetic fixtures
    # before creating this test's clocks, so SystemUI's collapsed preview cannot hide a fourth unrelated row.
    connection.execute("UPDATE notes SET taskTimerStartedAt=NULL,taskCountdownEndAt=NULL WHERE syncId LIKE 'running-probe-%'")
    now = int(ui.adb('shell', 'date', '+%s').strip()) * 1000
    book = running.insert(connection, 'notebooks', {'syncId':f'subtask-probe-book-{sdk}', 'name':'Subtask probe notebook'})
    parent_sync = f'subtask-probe-parent-{sdk}'
    parent = running.insert(connection, 'notes', {'syncId':parent_sync,'title':'Subtask probe parent',
        'content':'Preserved parent text','notebookId':book,'createdAt':now-10_000,'updatedAt':now})
    replace_database(connection, target, remote)
    if sdk >= 33: ui.adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
    open_parent(parent, parent_sync)
    def enabled_inline_plus():
        path = ui.match_path(ui.tree(), 'Добавить подзадачу')
        if path:
            button = next((n for n in reversed(path) if n.get('clickable') == 'true'), None)
            return ui.bounds(button) if button is not None and all(n.get('enabled') != 'false' for n in path) else None
        size = [int(x) for x in re.findall(r'\d+', ui.adb('shell', 'wm', 'size'))][-2:]
        ui.adb('shell', 'input', 'swipe', size[0]//2, int(size[1]*.78), size[0]//2, int(size[1]*.35), 350)
        return None
    plus = running.wait(enabled_inline_plus, 'Inline plus did not become visible and enabled')
    ui.adb('shell', 'input', 'tap', (plus[0]+plus[2])//2, (plus[1]+plus[3])//2)
    def focused_inline_child():
        root = ui.tree()
        focused = [n for n in root.iter() if n.get('focused') == 'true' and n.get('class') == 'android.widget.EditText']
        watch = ui.match_path(root, 'Запустить секундомер подзадачи')
        if len(focused) != 1 or not watch: return None
        field, control = ui.bounds(focused[0]), ui.bounds(watch[-1])
        # Identify the new child row, rather than accepting an old focused document field.
        return focused if field and control and field[2] <= control[0] and max(field[1],control[1]) < min(field[3],control[3]) else None
    focused = running.wait(focused_inline_child, 'Inline plus did not focus its editable subtask field')
    assert len(focused) == 1, 'Inline plus must focus exactly one editable subtask field'
    ui.adb('shell', 'input', 'text', 'Subtask%styped')
    running.wait(lambda: ui.match_path(ui.tree(), 'Subtask typed'), 'Actual subtask text was not entered')
    ui.snapshot('subtask-inline-created')
    ui.tap('Назад')
    running.wait(lambda: ui.match_path(ui.tree(), 'Новая заметка'), 'Editor did not finish its real exit save')
    ui.adb('shell', 'am', 'force-stop', PACKAGE)
    connection, remote, target = running.copy_database(OUT/'fixture-native')
    connection.row_factory = sqlite3.Row
    children = connection.execute('SELECT * FROM note_subtasks WHERE noteId=?',(parent,)).fetchall()
    assert len(children) == 1 and children[0]['text'] == 'Subtask typed' and children[0]['position'] == 0
    assert children[0]['startedAt'] is None and children[0]['countdownEndAt'] is None
    watch_id = children[0]['id']
    original = dict(connection.execute('SELECT * FROM notes WHERE id=?',(parent,)).fetchone())
    assert original['notebookId'] == book and original['content'] == 'Preserved parent text' and original['isTask'] == 0 and original['isDeleted'] == 0
    now = int(ui.adb('shell', 'date', '+%s').strip()) * 1000
    connection.execute('UPDATE note_subtasks SET startedAt=?,elapsedMillis=0 WHERE noteId=? AND id=?',(now-3600_000,parent,watch_id))
    connection.execute('UPDATE notes SET textFormat=?,tablesData=? WHERE id=?',('opaque-format','opaque-future-tables',parent))
    running.insert(connection, 'note_subtasks', {'noteId':parent,'id':'sibling-countdown','position':1,'text':'Subtask countdown',
        'completed':0,'elapsedMillis':0,'countdownMillis':900_000,'countdownEndAt':now+900_000})
    private = running.insert(connection, 'notes', {'syncId':f'subtask-probe-private-{sdk}','title':'PRIVATE_PARENT_FIXTURE',
        'content':'Private parent body','isProtected':1,'createdAt':now,'updatedAt':now})
    running.insert(connection, 'note_subtasks', {'noteId':private,'id':'private-item','position':0,'text':'PRIVATE_SUBTASK_FIXTURE',
        'completed':0,'elapsedMillis':0,'countdownMillis':1800_000,'countdownEndAt':now+1800_000})
    replace_database(connection, target, remote)
    ui.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/.MainActivity')
    tags = {'watch':f'notels:running:{parent}:{watch_id}:STOPWATCH',
            'countdown':f'notels:running:{parent}:sibling-countdown:COUNTDOWN',
            'private':f'notels:running:{private}:private-item:COUNTDOWN'}
    live = running.wait(lambda: (r if all(t in r for t in tags.values()) else None) if (r:=running.records()) else None,
                        'Child native notifications missing')
    for name, tag in tags.items(): assert running.boolean_extra(live[tag], 'android.showChronometer'), name
    assert running.boolean_extra(live[tags['countdown']], 'android.chronometerCountDown')
    assert running.boolean_extra(live[tags['private']], 'android.chronometerCountDown')
    assert 'PRIVATE_SUBTASK_FIXTURE' not in running.notification_dump() and 'PRIVATE_PARENT_FIXTURE' not in running.notification_dump()
    assert 'ServiceRecord{' not in ui.adb('shell','dumpsys','activity','services',PACKAGE)
    alarms = ui.adb('shell','dumpsys','alarm'); (OUT/'native-alarms.txt').write_text(alarms)
    assert alarms.count('tag=*walarm*:com.notessync.app.SUBTASK_DEADLINE') == 1, 'Require exactly one child alarm for two running countdowns'
    (OUT/'native-child-notifications.txt').write_text('\n'.join(live[t] for t in tags.values()))
    ui.adb('shell','cmd','statusbar','expand-notifications');ui.snapshot('subtask-native')
    ui.adb('shell','input','keyevent','HOME');pid=running.process_id();assert re.fullmatch(r'\d+',pid)
    ui.adb('shell','kill','-9',pid);running.wait(lambda:not running.process_id(),'Fixture process did not stop')
    time.sleep(1);assert not running.process_id();assert all(t in running.records() for t in tags.values());assert not running.process_id()
    titles={'Subtask typed','Subtask countdown','Защищённая запись'}
    running.tap_notification_action('Subtask typed','Пауза',titles)
    running.wait(lambda:tags['watch'] not in running.records(),'Child pause did not remove its counter')
    assert tags['countdown'] in running.records()
    running.tap_notification_action('Subtask countdown','Выполнить',titles)
    running.wait(lambda:tags['countdown'] not in running.records(),'Child completion did not remove countdown')
    assert tags['private'] in running.records()
    ui.adb('shell','am','force-stop',PACKAGE)
    connection,_,_=running.copy_database(OUT/'fixture-result');connection.row_factory=sqlite3.Row
    saved=dict(connection.execute('SELECT * FROM notes WHERE id=?',(parent,)).fetchone())
    rows={r['id']:dict(r) for r in connection.execute('SELECT * FROM note_subtasks WHERE noteId=?',(parent,))}
    secret=dict(connection.execute('SELECT * FROM note_subtasks WHERE noteId=?',(private,)).fetchone());connection.close()
    assert saved['syncId']==parent_sync and saved['notebookId']==book and saved['createdAt']==original['createdAt']
    assert saved['content']=='Preserved parent text' and saved['textFormat']=='opaque-format' and saved['tablesData']=='opaque-future-tables'
    assert saved['isTask']==0 and saved['isCompleted']==0 and saved['isDeleted']==0
    assert rows[watch_id]['text']=='Subtask typed' and rows[watch_id]['startedAt'] is None and rows[watch_id]['elapsedMillis']>=3600_000 and rows[watch_id]['completed']==0
    assert rows['sibling-countdown']['completed']==1 and rows['sibling-countdown']['countdownEndAt'] is None
    assert secret['startedAt'] is None and secret['countdownMillis']==1800_000 and secret['countdownEndAt']==now+1800_000 and secret['completed']==0
    for folder in ['fixture-ui','fixture-native','fixture-result']:
        for file in (OUT/folder).iterdir():file.unlink()
        (OUT/folder).rmdir()
    result={'status':'success','api':sdk,'room_schema':profile['version'],'room_identity':profile['identityHash'],
        'apk_sha256':hashlib.sha256(Path(args.apk).read_bytes()).hexdigest(),'minified_installed_apk':True,'actual_android_emulator':True,
        'actual_inline_plus_created_saved':True,'native_child_chronometers':True,'native_countdown':True,'one_platform_alarm_for_two_countdowns':True,
        'no_app_service':True,'process_death_keeps_counters':True,'actual_child_pause_complete':True,'parent_document_notebook_preserved':True,
        'parent_not_completed':True,'sibling_and_private_guards':True,'physical_phone':False,'battery_measured':False}
    (OUT/'subtask-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result))

if __name__=='__main__':
    try:main()
    except BaseException as error:
        (OUT/'subtask-failure.json').write_text(json.dumps({'status':'failure','error_type':type(error).__name__,'message':str(error)}))
        try:ui.snapshot('subtask-failure')
        except BaseException:pass
        raise
