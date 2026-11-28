"""Verify native running notifications in the minified APK on a disposable rooted CI emulator only."""
import argparse
import hashlib
import json
import re
import sqlite3
import subprocess
import time
from pathlib import Path
import release_settings_gc_probe as ui
from live_hierarchy_probe import LiveHierarchy

PACKAGE = ui.PACKAGE
OUT = Path('release-settings-probe/running-tasks')


def wait(check, description, seconds=15):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        value = check()
        if value:
            return value
        time.sleep(.2)
    raise AssertionError(description)


def process_id():
    result = subprocess.run(['adb', 'shell', 'pidof', '-s', PACKAGE], capture_output=True, text=True, timeout=15)
    assert result.returncode in (0, 1), 'Cannot inspect fixture process'
    return result.stdout.strip()


def copy_database(folder):
    """Only after process stop: copy main and its WAL, then let host SQLite checkpoint the snapshot."""
    assert not process_id(), 'Database copy requires stopped fixture process'
    folder.mkdir(parents=True, exist_ok=True)
    remote = f'/data/user/0/{PACKAGE}/databases/notessync.db'
    target = folder / 'notessync.db'
    ui.adb('pull', remote, target)
    wal = subprocess.run(['adb', 'shell', 'test', '-s', remote+'-wal'], capture_output=True).returncode == 0
    if wal:
        ui.adb('pull', remote+'-wal', str(target)+'-wal')
    connection = sqlite3.connect(target)
    connection.execute('PRAGMA wal_checkpoint(TRUNCATE)').fetchall()
    return connection, remote, target


def insert(connection, table, values):
    # Reuse every actual schema default; fill only mandatory legacy fields lacking one.
    for _, name, kind, required, default, primary in connection.execute(f'PRAGMA table_info({table})'):
        if name not in values and required and default is None and not primary:
            values[name] = '' if kind == 'TEXT' else 0
    columns = list(values)
    cursor = connection.execute(f'INSERT INTO {table} ({",".join(columns)}) VALUES ({",".join("?" for _ in columns)})',
                                [values[name] for name in columns])
    return cursor.lastrowid


def notification_dump():
    return ui.adb('shell', 'dumpsys', 'notification', '--noredact')


def records():
    # Active records have their own indented header; archive lines do not include the expanded extras/actions.
    text = notification_dump()
    result = {}
    for block in re.split(r'(?m)(?=^[ \t]+NotificationRecord\()', text):
        header = block.splitlines()[0] if block.splitlines() else ''
        match = re.search(r'tag=(notels:running:[^\s,)]+)', header)
        if match and f'pkg={PACKAGE}' in header:
            result[match.group(1)] = block
    return result


def boolean_extra(record, key):
    return bool(re.search(re.escape(key) + r'\s*[=:]\s*(?:Boolean\s*)?\(?true\)?', record, re.I))


def tap_notification_action(title, label):
    ui.adb('shell', 'cmd', 'statusbar', 'expand-notifications')
    other_titles = {'Running probe watch', 'Running probe countdown', 'Защищённая запись'} - {title}
    def action(root):
        path = ui.match_path(root, title)
        assert path, 'Task notification missing from live SystemUI: '+title
        for parent in reversed(path):
            if any(n.get('text') in other_titles for n in parent.iter()): break
            matches = [n for n in parent.iter() if n.get('text') == label]
            if len(matches) == 1:
                local = ui.match_path(parent, label)
                node = next((n for n in reversed(local or ()) if n.get('clickable') == 'true'), None)
                if node is not None: return ui.bounds(node)
            if len(matches) > 1: break
        return None
    root = ui.tree(); box = action(root)
    if box is None:
        path = ui.match_path(root, title); assert path
        expanded = False
        for parent in reversed(path):
            if any(n.get('text') in other_titles for n in parent.iter()): break
            arrows = [n for n in parent.iter() if n.get('resource-id', '').endswith('/expand_button') and n.get('clickable') == 'true']
            if len(arrows) == 1:
                arrow = ui.bounds(arrows[0]); assert arrow
                ui.adb('shell', 'input', 'tap', (arrow[0]+arrow[2])//2, (arrow[1]+arrow[3])//2)
                expanded = True; break
            if len(arrows) > 1: break
        if not expanded:
            row = next((n for n in reversed(path) if n.get('clickable') == 'true'), path[-1])
            area = ui.bounds(row); assert area
            height = int(re.findall(r'\d+', ui.adb('shell','wm','size'))[-1])
            ui.adb('shell', 'input', 'swipe', (area[0]+area[2])//2, area[1]+5,
                   (area[0]+area[2])//2, min(area[3]+220,height-100), 300)
        box = action(ui.tree())
    assert box, 'Unique action missing for '+title+': '+label
    ui.adb('shell', 'input', 'tap', (box[0]+box[2])//2, (box[1]+box[3])//2)


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--apk', required=True); parser.add_argument('--output', required=True)
    args = parser.parse_args()
    global OUT
    OUT = Path(args.output); OUT.mkdir(parents=True, exist_ok=True); ui.OUT = OUT
    assert ui.adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Never inject fixtures into a physical/user device'
    live = LiveHierarchy(); live.prepare()
    ui.tree = live.tree  # Public UiAutomation capture stays fresh while native chronometers keep ticking.
    ui.adb('root'); ui.adb('wait-for-device'); ui.adb('install', '-r', args.apk)
    ui.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/.MainActivity')
    sdk = int(ui.adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    remote = f'/data/user/0/{PACKAGE}/databases/notessync.db'
    wait(lambda: subprocess.run(['adb', 'shell', 'test', '-s', remote], capture_output=True).returncode == 0, 'Room database was not initialized')
    ui.adb('shell', 'am', 'force-stop', PACKAGE)
    connection, remote, target = copy_database(OUT/'fixture-input')
    expected = json.loads(Path('app/schemas/com.notessync.app.data.local.AppDatabase/19.json').read_text())['database']['identityHash']
    assert connection.execute('SELECT identity_hash FROM room_master_table WHERE id=42').fetchone() == (expected,)
    now = int(ui.adb('shell', 'date', '+%s').strip()) * 1000
    book = insert(connection, 'notebooks', {'syncId':f'running-probe-book-{sdk}', 'name':'Running probe notebook', 'createdAt':now, 'updatedAt':now})
    ids = {}
    for name in ['watch', 'countdown', 'private']:
        values = {'syncId':f'running-probe-{sdk}-{name}', 'title':'PRIVATE_RUNNING_FIXTURE' if name == 'private' else 'Running probe '+name,
                  'content':'Preserved fixture text', 'tablesData':'opaque-future-table', 'textFormat':'opaque-format',
                  'notebookId':book, 'createdAt':now-10_000, 'updatedAt':now, 'isTask':1, 'isProtected':int(name=='private'),
                  'taskElapsedMillis':3600_000, 'taskCountdownMillis':900_000,
                  'taskTimerStartedAt':now-5000 if name != 'countdown' else None,
                  'taskCountdownEndAt':now+900_000 if name == 'countdown' else None}
        ids[name] = insert(connection, 'notes', values)
    connection.commit(); connection.execute('PRAGMA wal_checkpoint(TRUNCATE)').fetchall(); connection.close()
    # Preserve device ownership/permissions of the existing file; only disposable emulator fixture DB is replaced.
    ui.adb('push', target, '/data/local/tmp/notels-running-fixture.db')
    ui.adb('shell', f'cat /data/local/tmp/notels-running-fixture.db > {remote}')
    ui.adb('shell', 'rm', '-f', remote+'-wal', remote+'-shm', '/data/local/tmp/notels-running-fixture.db')
    if sdk >= 33: ui.adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
    ui.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/.MainActivity')
    expected_tags = {name:f'notels:running:{id}:{"COUNTDOWN" if name=="countdown" else "STOPWATCH"}' for name,id in ids.items()}
    live = wait(lambda: (value if all(tag in value for tag in expected_tags.values()) else None) if (value:=records()) else None,
                'Native running notifications did not appear')
    for name, tag in expected_tags.items():
        assert boolean_extra(live[tag], 'android.showChronometer'), 'Missing native chronometer: '+name
        if name == 'countdown': assert boolean_extra(live[tag], 'android.chronometerCountDown')
    assert 'PRIVATE_RUNNING_FIXTURE' not in notification_dump(), 'Protected fixture title leaked to SystemUI'
    assert 'Защищённая запись' in live[expected_tags['private']]
    assert 'ServiceRecord{' not in ui.adb('shell', 'dumpsys', 'activity', 'services', PACKAGE), 'Running counter must not require a service'
    (OUT/'native-notifications.txt').write_text('\n'.join(live[tag] for tag in expected_tags.values()))
    ui.adb('shell', 'cmd', 'statusbar', 'expand-notifications'); ui.snapshot('running-native')
    ui.adb('shell', 'input', 'keyevent', 'HOME')
    pid = process_id(); assert re.fullmatch(r'\d+', pid)
    ui.adb('shell', 'kill', '-9', pid)
    wait(lambda: not process_id(), 'Fixture process did not stop')
    time.sleep(1)
    assert not process_id(), 'Application restarted before the native-counter verification'
    assert all(tag in records() for tag in expected_tags.values()), 'System counter did not survive ordinary process death'
    assert not process_id(), 'Counter verification requires the app process to remain absent'
    tap_notification_action('Running probe watch', 'Пауза')
    wait(lambda: expected_tags['watch'] not in records(), 'Notification pause did not remove the selected counter')
    assert expected_tags['countdown'] in records(), 'Pausing stopwatch changed the other task'
    tap_notification_action('Running probe countdown', 'Выполнить')
    wait(lambda: expected_tags['countdown'] not in records(), 'Notification complete did not remove countdown')
    assert expected_tags['private'] in records()
    ui.adb('shell', 'am', 'force-stop', PACKAGE)
    connection, _, _ = copy_database(OUT/'fixture-result')
    connection.row_factory = sqlite3.Row
    saved = {name:dict(connection.execute('SELECT * FROM notes WHERE id=?',(id,)).fetchone()) for name,id in ids.items()}
    connection.close()
    assert saved['watch']['taskTimerStartedAt'] is None and saved['watch']['taskElapsedMillis'] >= 3600_000
    assert saved['watch']['isCompleted'] == 0
    assert saved['countdown']['isCompleted'] == 1 and saved['countdown']['taskCountdownEndAt'] is None
    assert saved['countdown']['taskCompletedAt'] is not None
    assert saved['private']['taskTimerStartedAt'] is not None and saved['private']['isCompleted'] == 0
    for row in saved.values():
        assert row['notebookId'] == book and row['content'] == 'Preserved fixture text'
        assert row['tablesData'] == 'opaque-future-table' and row['textFormat'] == 'opaque-format' and row['createdAt'] == now-10_000
    # Do not archive raw SQLite fixtures; the proof keeps only synthetic bounded metadata.
    for folder in ['fixture-input','fixture-result']:
        for file in (OUT/folder).iterdir(): file.unlink()
        (OUT/folder).rmdir()
    result = {'status':'success','apk_sha256':hashlib.sha256(Path(args.apk).read_bytes()).hexdigest(),
              'minified_installed_apk':True,'actual_android_emulator':True,'api':sdk,'physical_phone':False,'battery_measured':False,
              'native_chronometers':True,'countdown_native':True,'no_app_service':True,'process_death_keeps_system_counters':True,
              'pause_and_complete_actual_system_ui':True,'protected_title_masked':True,'original_document_and_notebook_preserved':True}
    (OUT/'running-result.json').write_text(json.dumps(result,indent=2)); print(json.dumps(result))


if __name__ == '__main__':
    try: main()
    except BaseException as error:
        OUT.mkdir(parents=True,exist_ok=True)
        (OUT/'running-failure.json').write_text(json.dumps({'status':'failure','error_type':type(error).__name__,'message':str(error)}))
        try: ui.snapshot('running-failure')
        except BaseException: pass
        raise
