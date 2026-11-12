"""Build an ephemeral Android instrumentation APK against the installed native app."""
import shutil
import argparse
import subprocess
import tempfile
import zipfile
from pathlib import Path
from build_common import run, ROOT


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',help='ADB device/emulator serial; automatically selected if exactly one is connected')
    parser.add_argument('--suite',choices=['MobileUpdateRegression','GatewayConnectivityProbe','BackgroundRealtimeRegression','AppearanceRegression','ImageReadRegression','OverlayStabilityRegression','RedesignRegression','ScanSettingsRegression','UiRegression','FeatureRegression','ConnectionRegression','ReconnectRegression','FollowupRegression','MonitorRegression','InteractionRegression','QueueRegression','AttentionSettingsRegression','WorkflowRegression','PluginRegression','ExperienceRegression','LiveWorkspaceRegression','RealtimeRegression','GatewayRegression','StatusLayoutRegression','FilePreviewRegression'],default='UiRegression')
    parser.add_argument('--live-origin',help='Fixture server, or HTTPS origin for a read-only gateway probe')
    parser.add_argument('--theme',choices=['mist','slate','plum','teal','amber','graphite','light','dark','green','ocean','lavender','sand','midnight'],default='light')
    parser.add_argument('--screenshots',type=Path,default=ROOT/'.local/ui-captures')
    parser.add_argument('--style',choices=['classic','paper','compact'],default='classic')
    parser.add_argument('--color-style',choices=['catppuccin','tokyo','everforest'],default='catppuccin')
    parser.add_argument('--font-family',choices=['sans-serif','serif','sans-serif-condensed','monospace'],default='sans-serif')
    args=parser.parse_args()
    sdk=Path(r'D:\android-sdk');bt=sdk/'build-tools/35.0.0';android=sdk/'platforms/android-36/android.jar'
    java=Path(shutil.which('java'));adb=sdk/'platform-tools/adb.exe'
    devices=[line.split()[0] for line in subprocess.check_output([str(adb),'devices'],text=True).splitlines()[1:] if len(line.split())>=2 and line.split()[1]=='device']
    serial=args.serial or (devices[0] if len(devices)==1 else None)
    if not serial or serial not in devices:raise RuntimeError('Connect a device or pass --serial for an available device')
    with tempfile.TemporaryDirectory(prefix='ui-regression-',dir=ROOT/'.local') as folder:
        work=Path(folder);classes=work/'classes';classes.mkdir();dex=work/'dex';dex.mkdir()
        manifest=work/'AndroidManifest.xml'
        manifest.write_text(('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.abyxs.codingremote.tests"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="coding UI regression"/><instrumentation android:name=".SUITE" android:targetPackage="io.github.abyxs.codingremote" android:functionalTest="true"/></manifest>''').replace('.SUITE','.'+args.suite),encoding='utf-8')
        run(java.with_name('javac.exe'),'-encoding','UTF-8','-source','8','-target','8','-cp',android,'-d',classes,ROOT/('tests/android/'+args.suite+'.java'))
        run(bt/'d8.bat','--min-api','26','--lib',android,'--output',dex,*classes.rglob('*.class'))
        run(bt/'aapt2.exe','link','-o',work/'base.apk','--manifest',manifest,'-I',android)
        with zipfile.ZipFile(work/'base.apk','a') as archive:archive.write(dex/'classes.dex','classes.dex')
        run(bt/'zipalign.exe','-f','4',work/'base.apk',work/'aligned.apk')
        run(bt/'apksigner.bat','sign','--v4-signing-enabled','false','--ks',ROOT/'.local/signing/claude-adapter.p12','--ks-key-alias','claude-adapter','--ks-pass','file:'+str(ROOT/'.local/signing/password.txt'),'--out',work/'test.apk',work/'aligned.apk')
        run(adb,'-s',serial,'install','-r',work/'test.apk')
        try:
            command=[str(adb),'-s',serial,'shell','am','instrument','-w','-e','theme',args.theme,'-e','visualStyle',args.style,'-e','colorStyle',args.color_style,'-e','fontFamily',args.font_family]
            if args.live_origin:command+=['-e','liveOrigin',args.live_origin]
            result=subprocess.run(command+['io.github.abyxs.codingremote.tests/.'+args.suite],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
            print(result.stdout)
            if args.suite!='GatewayConnectivityProbe':
                args.screenshots.mkdir(parents=True,exist_ok=True)
                run(adb,'-s',serial,'pull','/sdcard/Android/data/io.github.abyxs.codingremote/files/ui-captures/.',args.screenshots)
            if 'PASS:' not in result.stdout:raise RuntimeError('Native UI regression failed')
        finally:run(adb,'-s',serial,'uninstall','io.github.abyxs.codingremote.tests')


if __name__=='__main__':main()
