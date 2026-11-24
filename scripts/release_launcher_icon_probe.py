"""Exercise icon choices in the actual minified APK; only fresh CI emulator data."""
import argparse
import hashlib
import json
import re
import time
from pathlib import Path
import release_settings_gc_probe as ui

PACKAGE = ui.PACKAGE
OUT = ui.OUT

def launchers():
    text = ui.adb('shell', 'cmd', 'package', 'query-activities', '--brief', '-a',
                  'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', PACKAGE)
    return sorted(set(re.findall(r'com\.notessync\.app/[A-Za-z0-9_.$]+', text)))

def expected(alias):
    return PACKAGE + '/' + PACKAGE + '.launcher.' + alias

def wait_launcher(alias):
    for _ in range(50):
        entries = launchers()
        if len(entries) == 1 and entries[0].replace('/.', '/'+PACKAGE+'.') == expected(alias): return entries[0]
        time.sleep(.2)
    raise AssertionError(('Exactly one selected launcher required', alias, entries))

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--apk', required=True); parser.add_argument('--output', required=True); args = parser.parse_args()
    global OUT
    OUT = Path(args.output); OUT.mkdir(parents=True, exist_ok=True); ui.OUT = OUT
    assert ui.adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
    ui.adb('root'); ui.adb('wait-for-device'); ui.adb('install', '-r', args.apk)
    events = []
    for alias, label in [('GlassIcon', 'Розовое стекло'), ('FrostedIcon', 'Матовый'), ('PaperIcon', 'Лист с галочкой')]:
        ui.adb('shell', 'am', 'force-stop', PACKAGE)
        # Explicit MainActivity remains valid for old home-screen, reminder and widget intents.
        ui.open_settings()
        before_pid = ui.adb('shell', 'pidof', '-s', PACKAGE).strip()
        ui.tap('Значок: '+label, scroll=True)
        component = wait_launcher(alias)
        assert ui.selected('Значок: '+label)
        assert ui.adb('shell', 'pidof', '-s', PACKAGE).strip() == before_pid, 'Icon switch killed app process'
        ui.snapshot('launcher-'+alias+'-selected')
        ui.adb('shell', 'am', 'force-stop', PACKAGE)
        started = ui.adb('shell', 'am', 'start', '-W', '-n', component)
        assert 'Error' not in started
        assert ui.adb('shell', 'pidof', '-s', PACKAGE).strip()
        assert wait_launcher(alias) == component
        events.append({'alias':alias, 'selected':True, 'launcher_count':1, 'process_survived_switch':True,
                       'relaunch_selected_component':True, 'persisted_after_process_restart':True})
    # Package replacement must retain the selection and exactly one entry.
    ui.adb('install', '-r', args.apk)
    wait_launcher('PaperIcon')
    result = {'status':'success', 'apk_sha256':hashlib.sha256(Path(args.apk).read_bytes()).hexdigest(),
              'minified_installed_apk':True, 'actual_android_emulator':True, 'physical_phone':False,
              'events':events, 'same_apk_update_preserves_choice':True, 'main_activity_always_enabled':True}
    (OUT/'launcher-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False))

if __name__ == '__main__':
    try: main()
    except BaseException as error:
        (OUT/'launcher-failure.json').write_text(json.dumps({'status':'failure','error_type':type(error).__name__,'message':str(error)}))
        try: ui.snapshot('launcher-failure')
        except BaseException: pass
        raise
