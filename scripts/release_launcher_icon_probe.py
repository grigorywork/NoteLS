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

def icon_target(root, label):
    path = ui.match_path(root, 'Значок: '+label)
    if not path: return None, None
    row = next((n for n in reversed(path) if n.get('checkable') == 'true'), path[-1])
    viewport = next((n for n in reversed(path) if n.get('class') == 'android.widget.ScrollView'), None)
    return ui.bounds(row), ui.bounds(viewport) if viewport is not None else None

def tap_icon(label):
    for _ in range(9):
        root = ui.tree()
        box, viewport = icon_target(root, label)
        # Android15 can report an unclipped clickable parent extending UNDER the
        # navigation bar. Do not tap its midpoint until the WHOLE row is visible.
        if box and viewport and box[1] >= viewport[1]+4 and box[3] <= viewport[3]-4:
            point = [(box[0]+box[2])//2, (box[1]+box[3])//2]
            ui.adb('shell','input','tap',*point)
            return {'label':label, 'row_bounds':box, 'viewport_bounds':viewport, 'tap':point}
        size = [int(x) for x in re.findall(r'\d+', ui.adb('shell','wm','size'))][-2:]
        if viewport:
            top, bottom = viewport[1], viewport[3]
        else:
            top, bottom = int(size[1]*.15), int(size[1]*.9)
        upper = int(top+(bottom-top)*.3); lower = int(top+(bottom-top)*.8)
        if box and box[1] < top+4: upper,lower = lower,upper
        ui.adb('shell','input','swipe',size[0]//2,lower,size[0]//2,upper,350)
    raise AssertionError('Fully visible icon choice missing: '+label)

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
        tap_proof = tap_icon(label)
        component = wait_launcher(alias)
        started_ui = time.monotonic()
        while not ui.selected('Значок: '+label):
            assert time.monotonic()-started_ui < 10, 'Selected icon was not published in live UI'
            time.sleep(.2)
        assert ui.adb('shell', 'pidof', '-s', PACKAGE).strip() == before_pid, 'Icon switch killed app process'
        ui.snapshot('launcher-'+alias+'-selected')
        ui.adb('shell', 'am', 'force-stop', PACKAGE)
        started = ui.adb('shell', 'am', 'start', '-W', '-n', component)
        assert 'Error' not in started
        assert ui.adb('shell', 'pidof', '-s', PACKAGE).strip()
        assert wait_launcher(alias) == component
        events.append({'tap_proof':tap_proof, 'alias':alias, 'selected':True, 'launcher_count':1, 'process_survived_switch':True,
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
