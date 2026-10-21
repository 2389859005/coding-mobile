import hashlib
import re
import shutil
import tempfile
import zipfile
from pathlib import Path
from build_common import run, EXPECTED_APK as EXPECTED, ROOT

NATIVE_BASE_SHA256='369081cee5169a9b9d10aa6d856f96f65f20404815de67b720b4dc53730915a5'


def build_in(work, source=None):
    source=Path(source) if source else ROOT/'CodexMobileAssistant-Android-1.0.19.apk'
    source_hash=hashlib.sha256(source.read_bytes()).hexdigest()
    assert source_hash in (EXPECTED,NATIVE_BASE_SHA256), 'Unrecognized APK rebuild source'
    decoded=work/'decoded';classes=work/'classes';dex=work/'dex';classes.mkdir();dex.mkdir()
    java=Path(shutil.which('java'));sdk=Path(r'D:\android-sdk');bt=sdk/'build-tools/35.0.0';android=sdk/'platforms/android-36/android.jar'
    run(java,'-jar',ROOT/'.local/build-tools/apktool.jar','d',source,'-o',decoded)
    if source_hash==NATIVE_BASE_SHA256:
        old_native=decoded/'smali_classes4'
        assert old_native.resolve().is_relative_to(work.resolve())
        assert all(str(path.relative_to(old_native)).replace('\\','/').startswith('io/github/abyxs/codingremote/') for path in old_native.rglob('*.smali'))
        shutil.rmtree(old_native)
    capture=decoded/'smali/com/journeyapps/barcodescanner/CaptureManager.smali'
    text=capture.read_text('utf-8')
    text=text.replace('invoke-static {v0, v1}, Landroidx/core/content/ContextCompat;->checkSelfPermission(Landroid/content/Context;Ljava/lang/String;)I','invoke-virtual {v0, v1}, Landroid/app/Activity;->checkSelfPermission(Ljava/lang/String;)I')
    text=text.replace('invoke-static {v0, v3, v1}, Landroidx/core/app/ActivityCompat;->requestPermissions(Landroid/app/Activity;[Ljava/lang/String;I)V','invoke-virtual {v0, v3, v1}, Landroid/app/Activity;->requestPermissions([Ljava/lang/String;I)V')
    assert 'Landroidx/core/' not in text;capture.write_text(text,encoding='utf-8')
    appdir=decoded/'smali_classes3/io/github/abyxs/codexmobile'
    for path in appdir.glob('*.smali'):
        # Only obsolete app screens/services in this freshly decoded build tree.
        if path.name.startswith(('MainActivity','TaskNotificationService')):
            assert path.resolve().is_relative_to(decoded.resolve());path.unlink()
    manifest='''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.abyxs.codingremote">
<uses-permission android:name="android.permission.INTERNET"/><uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/><uses-permission android:name="android.permission.CAMERA"/>
<uses-permission android:name="android.permission.VIBRATE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/><uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/><uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/><uses-permission android:name="android.permission.FOREGROUND_SERVICE"/><uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-feature android:name="android.hardware.camera" android:required="false"/>
<application android:name="io.github.abyxs.codingremote.AppVisibility" android:extractNativeLibs="true" android:allowBackup="false" android:debuggable="false" android:label="coding远程" android:icon="@drawable/product_icon" android:theme="@style/AppTheme" android:usesCleartextTraffic="true">
<activity android:name="io.github.abyxs.codingremote.MainActivity" android:exported="true" android:launchMode="singleTop" android:windowSoftInputMode="stateAlwaysHidden|adjustResize" android:configChanges="keyboardHidden|orientation|screenSize"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
<service android:name="io.github.abyxs.codingremote.TaskNotificationService" android:exported="false" android:foregroundServiceType="dataSync"/>
<service android:name="io.github.abyxs.codingremote.TaskCheckJob" android:permission="android.permission.BIND_JOB_SERVICE" android:exported="true"/>
<provider android:name="io.github.abyxs.codingremote.PreviewProvider" android:authorities="io.github.abyxs.codingremote.previews" android:exported="false" android:grantUriPermissions="true"/>
<activity android:name="io.github.abyxs.codexmobile.QrScannerActivity" android:exported="false" android:screenOrientation="fullSensor" android:theme="@style/AppTheme" android:configChanges="keyboardHidden|orientation|screenSize"/>
</application></manifest>'''
    (decoded/'AndroidManifest.xml').write_text(manifest,encoding='utf-8')
    for abi in ('arm64-v8a','x86_64'):
        native=ROOT/'.local/tailnet'/abi/'libtailnet.so';assert native.is_file(), 'Build embedded tailnet first'
        target=decoded/'lib'/abi/'libtailnet.so';target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(native,target)
    drawable=decoded/'res/drawable-nodpi';drawable.mkdir(exist_ok=True)
    shutil.copyfile(ROOT/'packaging/product/icon.png',drawable/'product_icon.png')
    for asset in (ROOT/'packaging/android/assets').glob('*.png'):shutil.copyfile(asset,drawable/asset.name)
    (decoded/'res/drawable/notification_task.xml').write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#ffffff" android:pathData="M4,3h16v15H9l-5,4z"/><path android:fillColor="#00000000" android:strokeColor="#ff000000" android:strokeWidth="2" android:pathData="M8,10l3,3 5,-6"/></vector>''',encoding='utf-8')
    animations=decoded/'res/anim';animations.mkdir(exist_ok=True)
    for name,enter,sheet in [('dialog_enter',True,False),('dialog_exit',False,False),('sheet_enter',True,True),('sheet_exit',False,True)]:
        motion=(f'<translate android:fromYDelta="{"12%" if enter else "0%"}" android:toYDelta="{"0%" if enter else "8%"}"/>' if sheet else f'<scale android:fromXScale="{.96 if enter else 1}" android:toXScale="{1 if enter else .98}" android:fromYScale="{.96 if enter else 1}" android:toYScale="{1 if enter else .98}" android:pivotX="50%" android:pivotY="50%"/>')
        (animations/(name+'.xml')).write_text(f'<set xmlns:android="http://schemas.android.com/apk/res/android" android:duration="{200 if enter else 140}" android:interpolator="@android:interpolator/fast_out_slow_in"><alpha android:fromAlpha="{0 if enter else 1}" android:toAlpha="{1 if enter else 0}"/>{motion}</set>',encoding='utf-8')
    (decoded/'res/values/styles.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
  <style name="AppTheme" parent="@android:style/Theme.Material.Light.NoActionBar">
    <item name="android:windowBackground">#f6f7f2</item>
    <item name="android:windowActionModeOverlay">true</item>
    <item name="android:fontFamily">sans</item>
    <item name="android:colorAccent">#27634d</item>
    <item name="android:statusBarColor">#f6f7f2</item>
    <item name="android:navigationBarColor">#f6f7f2</item>
    <item name="android:windowLightStatusBar">true</item>
    <item name="android:windowLightNavigationBar">true</item>
    <item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item>
  </style>
  <style name="DialogMotion"><item name="android:windowEnterAnimation">@anim/dialog_enter</item><item name="android:windowExitAnimation">@anim/dialog_exit</item></style>
  <style name="SheetMotion"><item name="android:windowEnterAnimation">@anim/sheet_enter</item><item name="android:windowExitAnimation">@anim/sheet_exit</item></style>
  <style name="LaunchTheme" parent="@style/AppTheme"><item name="android:windowBackground">@drawable/launch_background</item></style>
  <style name="zxing_CaptureTheme" parent="@android:style/Theme.Holo.NoActionBar.Fullscreen"/>
</resources>''',encoding='utf-8')
    (decoded/'res/layout/activity_main.xml').write_text('<?xml version="1.0" encoding="utf-8"?><FrameLayout xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent"/>',encoding='utf-8')
    yml=decoded/'apktool.yml';metadata=yml.read_text('utf-8')
    metadata=re.sub(r'(?m)^(\s*versionCode:).*$',r'\g<1> 21010000',metadata)
    metadata=re.sub(r'(?m)^(\s*versionName:).*$',r'\g<1> 2.1.1',metadata)
    yml.write_text(metadata,encoding='utf-8')
    run(java,'-jar',ROOT/'.local/build-tools/apktool.jar','b',decoded,'-o',work/'base.apk')
    run(java.with_name('javac.exe'),'-encoding','UTF-8','-source','8','-target','8','-classpath',android,'-d',classes,*sorted((ROOT/'packaging/android/native').glob('*.java')))
    # Invoke Java directly: cmd.exe's batch argument limit is shorter than the class list.
    run(java,'-Xmx1024M','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','26','--lib',android,'--output',dex,*sorted(classes.rglob('*.class')))
    with zipfile.ZipFile(work/'base.apk') as src,zipfile.ZipFile(work/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            if not info.filename.startswith('META-INF/') and info.filename not in ('classes4.dex','assets/CODING_REMOTE_NOTICE.txt','assets/PRODUCT_ICON_NOTICE.txt'):dst.writestr(info,src.read(info.filename))
        dst.write(dex/'classes.dex','classes4.dex')
        dst.write(ROOT/'packaging/LOCAL_ADAPTER_NOTICE.txt','assets/CODING_REMOTE_NOTICE.txt')
        dst.write(ROOT/'packaging/tailnet/THIRD_PARTY_NOTICES.txt','assets/TAILNET_NOTICES.txt')
        dst.write(ROOT/'packaging/android/assets/NOTICE.md','assets/PRODUCT_ICON_NOTICE.txt')
    run(bt/'zipalign.exe','-f','-p','4',work/'unsigned.apk',work/'aligned.apk')
    target=work/'CodingRemoteAssistant-Android-2.1.1.apk'
    run(bt/'apksigner.bat','sign','--v4-signing-enabled','false','--ks',ROOT/'.local/signing/claude-adapter.p12','--ks-key-alias','claude-adapter','--ks-pass','file:'+str(ROOT/'.local/signing/password.txt'),'--out',target,work/'aligned.apk')
    run(bt/'apksigner.bat','verify','--verbose',target);run(bt/'zipalign.exe','-c','-p','4',target)
    output=ROOT/'dist'/target.name;output.parent.mkdir(exist_ok=True);target.replace(output)
    output.with_suffix('.apk.idsig').unlink(missing_ok=True)
    print(output,hashlib.sha256(output.read_bytes()).hexdigest())


def build(source=None):
    if source is None and not (ROOT/'CodexMobileAssistant-Android-1.0.19.apk').exists():
        base=ROOT/'.local/native-base-2.0.0.apk'
        if not base.exists():
            candidate=ROOT/'dist/CodingRemoteAssistant-Android-2.0.11.apk'
            assert hashlib.sha256(candidate.read_bytes()).hexdigest()==NATIVE_BASE_SHA256
            shutil.copyfile(candidate,base)
        source=base
    with tempfile.TemporaryDirectory(prefix='native-apk-',dir=ROOT/'.local') as work:
        build_in(Path(work),source)


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('--source',help='Path to the verified original Android 1.0.19 APK')
    build(parser.parse_args().source)
