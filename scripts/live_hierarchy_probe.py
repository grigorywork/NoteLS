"""Fresh live accessibility snapshots on disposable CI emulators, without waiting for UI idleness.

A separate, temporary instrumentation package uses only public UiAutomation APIs. It neither
instruments nor re-signs NoteLS. Moving native chronometers are intentionally left running.
"""
import os
import re
import subprocess
import tempfile
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
import release_settings_gc_probe as ui

PACKAGE = 'com.notessync.fixtureprobe'
REMOTE = f'/sdcard/Android/data/{PACKAGE}/files/live.xml'
JAVA = r'''
package com.notessync.fixtureprobe;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import org.xmlpull.v1.XmlSerializer;
import java.io.File;
import java.io.FileOutputStream;

public final class ProbeInstrumentation extends Instrumentation {
    private String nonce;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        nonce = arguments.getString("nonce", "");
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!nonce.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Missing nonce");
            AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            getUiAutomation().setServiceInfo(info);
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root == null) throw new IllegalStateException("No live root");
            File base = getContext().getExternalFilesDir(null);
            if (base == null || (!base.isDirectory() && !base.mkdirs())) throw new IllegalStateException("No owned output directory");
            File output = new File(base, "live.xml");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                XmlSerializer xml = Xml.newSerializer();
                xml.setOutput(stream, "UTF-8");
                xml.startDocument("UTF-8", true);
                xml.startTag(null, "hierarchy");
                xml.attribute(null, "nonce", nonce);
                xml.attribute(null, "captured-uptime", Long.toString(SystemClock.uptimeMillis()));
                writeNode(xml, root, 0);
                xml.endTag(null, "hierarchy");
                xml.endDocument();
            } finally { root.recycle(); }
            result.putString("status", "success");
            result.putString("nonce", nonce);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("status", "failure");
            result.putString("failure", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
    private void writeNode(XmlSerializer xml, AccessibilityNodeInfo node, int index) throws Exception {
        Rect rect = new Rect(); node.getBoundsInScreen(rect);
        int width = getContext().getResources().getDisplayMetrics().widthPixels;
        int height = getContext().getResources().getDisplayMetrics().heightPixels;
        rect.left = Math.max(0, Math.min(width, rect.left));
        rect.right = Math.max(0, Math.min(width, rect.right));
        rect.top = Math.max(0, Math.min(height, rect.top));
        rect.bottom = Math.max(0, Math.min(height, rect.bottom));
        xml.startTag(null, "node");
        xml.attribute(null, "index", Integer.toString(index));
        xml.attribute(null, "text", text(node.getText()));
        xml.attribute(null, "resource-id", text(node.getViewIdResourceName()));
        xml.attribute(null, "class", text(node.getClassName()));
        xml.attribute(null, "package", text(node.getPackageName()));
        xml.attribute(null, "content-desc", text(node.getContentDescription()));
        xml.attribute(null, "checkable", Boolean.toString(node.isCheckable()));
        xml.attribute(null, "checked", Boolean.toString(node.isChecked()));
        xml.attribute(null, "clickable", Boolean.toString(node.isClickable()));
        xml.attribute(null, "enabled", Boolean.toString(node.isEnabled()));
        xml.attribute(null, "focusable", Boolean.toString(node.isFocusable()));
        xml.attribute(null, "focused", Boolean.toString(node.isFocused()));
        xml.attribute(null, "scrollable", Boolean.toString(node.isScrollable()));
        xml.attribute(null, "long-clickable", Boolean.toString(node.isLongClickable()));
        xml.attribute(null, "password", Boolean.toString(node.isPassword()));
        xml.attribute(null, "selected", Boolean.toString(node.isSelected()));
        xml.attribute(null, "bounds", "["+rect.left+","+rect.top+"]["+rect.right+","+rect.bottom+"]");
        for (int childIndex=0; childIndex<node.getChildCount(); childIndex++) {
            AccessibilityNodeInfo child = node.getChild(childIndex);
            if (child != null) {
                try { if (child.isVisibleToUser()) writeNode(xml, child, childIndex); }
                finally { child.recycle(); }
            }
        }
        xml.endTag(null, "node");
    }
}
'''
MANIFEST = f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />
<application android:debuggable="true" android:label="Disposable CI accessibility probe" />
<instrumentation android:name=".ProbeInstrumentation" android:targetPackage="{PACKAGE}" />
</manifest>'''


class LiveHierarchy:
    def __init__(self):
        self.prepared = False
        self.temporary = None

    def prepare(self):
        assert ui.adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'CI emulator only'
        sdk = Path(os.environ['ANDROID_HOME'])
        tools = sdk/'build-tools'/'35.0.0'
        android = sdk/'platforms'/'android-35'/'android.jar'
        if not android.is_file():
            candidates = [path/'android.jar' for path in (sdk/'platforms').glob('android-*') if path.name[8:].isdigit() and int(path.name[8:]) >= 26 and (path/'android.jar').is_file()]
            android = max(candidates, key=lambda path: int(path.parent.name[8:]))
        assert tools.is_dir() and android.is_file()
        self.temporary = tempfile.TemporaryDirectory(prefix='notels-live-accessibility-')
        root = Path(self.temporary.name)
        source = root/'src'/'com'/'notessync'/'fixtureprobe'/'ProbeInstrumentation.java'
        source.parent.mkdir(parents=True); source.write_text(JAVA)
        manifest = root/'AndroidManifest.xml'; manifest.write_text(MANIFEST)
        classes = root/'classes'; classes.mkdir()
        subprocess.run(['javac','--release','8','-cp',str(android),'-d',str(classes),str(source)],check=True)
        dex = root/'dex'; dex.mkdir()
        subprocess.run([str(tools/'d8'),'--min-api','26','--lib',str(android),'--output',str(dex),
                        str(classes/'com/notessync/fixtureprobe/ProbeInstrumentation.class')],check=True)
        unsigned = root/'unsigned.apk'
        subprocess.run([str(tools/'aapt2'),'link','-I',str(android),'--manifest',str(manifest),'-o',str(unsigned)],check=True)
        import zipfile
        with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as archive:
            archive.write(dex/'classes.dex','classes.dex')
        # This disposable key signs ONLY the separate fixture package. NoteLS/signing Secrets are untouched.
        key = root/'fixture.p12'; password = 'temporary-fixture-only'
        subprocess.run(['keytool','-genkeypair','-noprompt','-storetype','PKCS12','-keystore',str(key),
                        '-alias','fixture','-storepass',password,'-keypass',password,'-keyalg','RSA',
                        '-keysize','2048','-validity','1','-dname','CN=Disposable CI Fixture'],check=True)
        aligned = root/'aligned.apk'; helper = root/'helper.apk'
        subprocess.run([str(tools/'zipalign'),'-f','4',str(unsigned),str(aligned)],check=True)
        subprocess.run([str(tools/'apksigner'),'sign','--ks',str(key),'--ks-pass','pass:'+password,
                        '--key-pass','pass:'+password,'--out',str(helper),str(aligned)],check=True)
        # Each probe invocation has its own disposable fixture key; remove ONLY the previous helper.
        installed = subprocess.run(['adb','shell','pm','path',PACKAGE],capture_output=True,text=True,timeout=15)
        if installed.stdout.strip().startswith('package:'):
            assert 'Success' in ui.adb('uninstall',PACKAGE)
        ui.adb('install','-r','-t',helper)
        self.prepared = True

    def tree(self):
        assert self.prepared
        # Match the existing strict5 fresh-capture attempts, without the CLI's idle wait.
        failure = None
        for _ in range(5):
            nonce = uuid.uuid4().hex
            ui.adb('shell','rm','-f',REMOTE)
            try:
                status = ui.adb('shell','am','instrument','-w','-e','nonce',nonce,PACKAGE+'/.ProbeInstrumentation')
                assert 'INSTRUMENTATION_CODE: -1' in status and 'status=success' in status, status
                root = ET.fromstring(ui.adb('shell','cat',REMOTE))
                assert root.tag == 'hierarchy' and len(root) and root.get('nonce') == nonce, 'Stale or empty live hierarchy'
                return root
            except (subprocess.CalledProcessError,ET.ParseError,AssertionError) as error:
                failure = error
        raise AssertionError('No fresh valid non-idle UI hierarchy after5 attempts') from failure
