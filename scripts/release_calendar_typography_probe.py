"""Stage93 signed/minified runtime: actual saved controls and native month/week RemoteViews."""
import argparse
import datetime
import hashlib
import json
import re
import shutil
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from zoneinfo import ZoneInfo
import release_settings_gc_probe as ui
import release_running_task_probe as running
from release_subtasks_probe import replace_database
from live_hierarchy_probe import LiveHierarchy
from calendar_widget_host_probe import CalendarWidgetHost, PACKAGE as HOST

OUT=Path('release-settings-probe/calendar-typography')
KEYS={'calendar_font','calendar_text_size','calendar_widget_font','calendar_widget_text_size'}


def preferences():
    # Filter before recording; no unrelated preference/credential is emitted.
    root=ET.fromstring(ui.adb('shell','cat',f'/data/user/0/{ui.PACKAGE}/shared_prefs/settings.xml'))
    return {node.get('name'):node.get('value',node.text) for node in root if node.get('name') in KEYS}


def has_text(node,label):
    return label in node.get('text','').splitlines() or node.get('content-desc')==label


def paths(root,label,parents=()):
    result=[]
    for node in root:
        path=(*parents,node)
        if has_text(node,label):result.append(path)
        result.extend(paths(node,label,path))
    return result


def tap(label, anchor=None):
    for attempt in range(24):
        root=ui.tree();candidates=paths(root,label)
        anchor_paths=paths(root,anchor) if anchor else []
        anchor_box=ui.bounds(anchor_paths[-1][-1]) if anchor_paths else None
        choices=[]
        for path in candidates:
            row=next((node for node in reversed(path) if node.get('clickable')=='true'),path[-1])
            box=ui.bounds(row)
            viewport=next((ui.bounds(node) for node in reversed(path) if node.get('scrollable')=='true'),None)
            if not box:continue
            if viewport and (box[1]<viewport[1]+2 or box[3]>viewport[3]-2):continue
            if anchor and (not anchor_box or box[3]>anchor_box[1]):continue
            choices.append((box[1],box))
        if choices:
            box=max(choices)[1] if anchor else choices[0][1]
            ui.adb('shell','input','tap',(box[0]+box[2])//2,(box[1]+box[3])//2)
            return box
        size=[int(value) for value in re.findall(r'\d+',ui.adb('shell','wm','size'))][-2:]
        ui.adb('shell','input','swipe',size[0]//2,int(size[1]*.78),size[0]//2,int(size[1]*.35),350)
    raise AssertionError(('Reachable control missing',label,anchor))


def wait_preferences(expected):
    return running.wait(lambda:(value if all(value.get(key)==item for key,item in expected.items()) else None)
        if (value:=preferences()) is not None else None, 'Typography was not persisted: '+str(expected))


def host_snapshot(kind,family,factor,label):
    nonce=uuid.uuid4().hex
    output=ui.adb('shell','am','start','-W','-n',HOST+'/.HostActivity','--es','kind',kind,'--es','nonce',nonce,
        '--es','family',family,'--ef','factor',factor,'--es','expected','Typography visible')
    assert 'Status: ok' in output and 'Error:' not in output,output
    remote=f'/sdcard/Android/data/{HOST}/files/widget-report.json'
    started=time.monotonic();value=None
    while time.monotonic()-started<20:
        try:value=json.loads(ui.adb('shell','cat',remote))
        except (ValueError,__import__('subprocess').CalledProcessError):time.sleep(.2);continue
        if value.get('nonce')==nonce:break
        time.sleep(.2)
    assert value and value.get('nonce')==nonce,('Fresh host report missing',kind,nonce,value)
    (OUT/(label+'.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2))
    assert value['status']=='success' and value['ready'],value
    assert value['matching_typography'] and value['visible_fixture'] and value['private_text_absent'],value
    assert abs(value['width_px']/value['density']-320)<1,value
    fixture=[node for node in value['nodes'] if 'Typography visible' in node['text']]
    assert fixture and all(node['color_spans']>=1 and node['strike_spans']>=1 for node in fixture),fixture
    if kind=='week':assert all(node['style_spans']>=1 for node in fixture),fixture
    ui.snapshot(label)
    return {'kind':kind,'widget_id':value['widget_id'],'native_host':True,'matching_typography':True,
        'private_text_absent':True,'completed_spans_preserved':True,'font_scale':value['font_scale'],'report':label+'.json'}


def open_widget_settings():
    ui.adb('shell','am','force-stop',ui.PACKAGE)
    output=ui.adb('shell','am','start','-W','-n',ui.PACKAGE+'/.widget.WidgetSettingsActivity')
    assert 'Status: ok' in output and 'Error:' not in output,output
    running.wait(lambda:paths(ui.tree(),'Текст календарных виджетов'),'Widget typography controls missing')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--apk',required=True);parser.add_argument('--output',required=True)
    parser.add_argument('--schema-json',required=True);args=parser.parse_args()
    global OUT
    OUT=Path(args.output);OUT.mkdir(parents=True,exist_ok=True);ui.OUT=OUT
    assert ui.adb('shell','getprop','ro.kernel.qemu').strip()=='1','Disposable emulator required'
    helper=LiveHierarchy();host=CalendarWidgetHost()
    old_scale=ui.adb('shell','settings','get','system','font_scale').strip()
    old_size=ui.adb('shell','wm','size');old_density=ui.adb('shell','wm','density')
    try:
        ui.adb('root');ui.adb('wait-for-device');ui.adb('install','-r',args.apk)
        helper.prepare();ui.tree=helper.tree
        ui.adb('shell','wm','size','960x1920');ui.adb('shell','wm','density','480')
        ui.adb('shell','settings','put','system','font_scale','1.0')
        ui.adb('shell','am','start','-W','-n',ui.PACKAGE+'/.MainActivity');ui.adb('shell','am','force-stop',ui.PACKAGE)
        schema=json.loads(Path(args.schema_json).read_text())['database']
        connection,remote,target=running.copy_database(OUT/'fixture-input')
        assert connection.execute('PRAGMA user_version').fetchone()==(schema['version'],)
        assert connection.execute('SELECT identity_hash FROM room_master_table WHERE id=42').fetchone()==(schema['identityHash'],)
        zone=ZoneInfo(ui.adb('shell','getprop','persist.sys.timezone').strip() or 'UTC')
        now=int(ui.adb('shell','date','+%s').strip())
        day=(datetime.datetime.fromtimestamp(now,zone).date()-datetime.date(1970,1,1)).days
        for title,protected in [('Typography visible',0),('PRIVATE_CALENDAR_SECRET',1)]:
            running.insert(connection,'notes',{'syncId':'calendar-type-'+uuid.uuid4().hex,'title':title,'dayKey':day,
                'isTask':1,'isCompleted':1,'taskColor':'mint','isProtected':protected,'createdAt':now*1000,'updatedAt':now*1000})
        replace_database(connection,target,remote)
        # Select calendar font/size through actual production controls, not preference-file edits.
        ui.open_settings();tap('Как в приложении');tap('PT Sans')
        tap('Крупный',anchor='Сбросить текст календаря')
        calendar=wait_preferences({'calendar_font':'PT_SANS','calendar_text_size':'LARGE'})
        ui.snapshot('calendar-settings-selected-320')
        open_widget_settings();tap('Зелёные и зачёркнутые')
        host.prepare()
        events=[host_snapshot(kind,'default',1.0,'widget-'+kind+'-default') for kind in ['month','week']]
        open_widget_settings();tap('Стандартный');tap('Моноширинный');tap('Крупный',anchor='Сбросить текст виджетов')
        saved=wait_preferences({'calendar_font':'PT_SANS','calendar_text_size':'LARGE',
            'calendar_widget_font':'MONO','calendar_widget_text_size':'LARGE'})
        ui.snapshot('widget-settings-selected-320')
        events.extend(host_snapshot(kind,'monospace',1.15,'widget-'+kind+'-monospace') for kind in ['month','week'])
        # Native process restart must retain independent choices and actual widget IPC output.
        ui.adb('shell','am','force-stop',ui.PACKAGE);open_widget_settings()
        assert wait_preferences(saved)==saved
        events.extend(host_snapshot(kind,'monospace',1.15,'widget-'+kind+'-reopened') for kind in ['month','week'])
        ui.adb('shell','settings','put','system','font_scale','1.6')
        open_widget_settings();tap('Сбросить текст виджетов')
        reset=wait_preferences({'calendar_font':'PT_SANS','calendar_text_size':'LARGE',
            'calendar_widget_font':'DEFAULT','calendar_widget_text_size':'NORMAL'})
        ui.snapshot('widget-settings-reset-large-320')
        events.extend(host_snapshot(kind,'default',1.0,'widget-'+kind+'-reset-large') for kind in ['month','week'])
        assert all(abs(event['font_scale']-1.6)<.01 for event in events[-2:]),events[-2:]
        ui.adb('shell','settings','put','system','font_scale','1.0')
        ui.adb('shell','am','force-stop',ui.PACKAGE);ui.open_settings();tap('Сбросить текст календаря')
        final=wait_preferences({'calendar_text_size':'NORMAL','calendar_widget_font':'DEFAULT','calendar_widget_text_size':'NORMAL'})
        assert 'calendar_font' not in final,final
        ui.snapshot('calendar-settings-reset-320')
        # Remove only synthetic raw fixture copies from successful evidence archives.
        shutil.rmtree(OUT/'fixture-input')
        result={'status':'success','apk_sha256':hashlib.sha256(Path(args.apk).read_bytes()).hexdigest(),
            'actual_android_emulator':True,'minified_installed_apk':True,'physical_phone':False,
            'sdk':int(ui.adb('shell','getprop','ro.build.version.sdk').strip()),'room_version':schema['version'],
            'room_identity':schema['identityHash'],'calendar_selected':calendar,'independent_saved':saved,
            'widget_reset':reset,'calendar_reset':final,'events':events,'native_widget_width_dp':320,
            'real_ui_controls':True,'restart_persistence':True,'large_system_font':1.6}
        (OUT/'calendar-typography-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
        print(json.dumps(result,ensure_ascii=False))
    except BaseException:
        try:
            ui.snapshot('calendar-typography-before-cleanup-failure')
            (OUT/'failure-activity.txt').write_text(ui.adb('shell','dumpsys','activity','activities'))
            (OUT/'failure-crash.txt').write_text(ui.adb('logcat','-b','crash','-d'))
        except BaseException:pass
        raise
    finally:
        host.close()
        size=re.search(r'Override size: (\d+x\d+)',old_size);density=re.search(r'Override density: (\d+)',old_density)
        ui.adb('shell','wm','size',size[1] if size else 'reset');ui.adb('shell','wm','density',density[1] if density else 'reset')
        ui.adb('shell','settings','put','system','font_scale',old_scale if old_scale!='null' else '1.0')
        if helper.temporary:helper.temporary.cleanup()

if __name__=='__main__':
    try:main()
    except BaseException as error:
        OUT.mkdir(parents=True,exist_ok=True)
        (OUT/'calendar-typography-failure.json').write_text(json.dumps({'status':'failure','error_type':type(error).__name__,'message':str(error)}))
        try:ui.snapshot('calendar-typography-failure')
        except BaseException:pass
        raise
