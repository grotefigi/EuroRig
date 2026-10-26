"""Run native interface checks on a dedicated emulator with Romania installed."""
import argparse
from pathlib import Path
import subprocess

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device',required=True)
parser.add_argument('--map',type=Path,help='Import this local package transactionally before testing; existing installed maps are retained.')
parser.add_argument('--output',type=Path,default=root/'work'/'interface-evidence')
args=parser.parse_args()
if not args.device.startswith('emulator-'):
    parser.error('Use a dedicated emulator. Physical devices are not accepted by this test helper.')
args.output.mkdir(parents=True,exist_ok=True)
def adb(*command):
    return subprocess.run(['adb','-s',args.device,*map(str,command)],check=True,capture_output=True,timeout=240)
for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
    adb('install','-r',root/apk)
extra=[]
if args.map:
    if not args.map.is_file():parser.error('Map package does not exist.')
    remote='/data/local/tmp/eurorig-interface-country.eurorig'
    adb('push',args.map,remote)
    adb('shell','run-as','org.eurorig.app.dev','mkdir','-p','files')
    adb('shell','run-as','org.eurorig.app.dev','cp',remote,'files/interface-country.eurorig')
    adb('shell','rm','-f',remote)
    extra=['-e','packagePath','/data/user/0/org.eurorig.app.dev/files/interface-country.eurorig']
result=adb('shell','am','instrument','-w',*extra,'-e','interfaceOnly','true','org.eurorig.app.dev.test/org.eurorig.app.NativeSmokeInstrumentation').stdout.decode('utf8',errors='replace')
(args.output/'native-interface.txt').write_text(result,encoding='utf8')
print(result,flush=True)
if 'PASS:' not in result:raise SystemExit('Native interface test failed. See the saved instrumentation output.')
adb('pull','/sdcard/Android/data/org.eurorig.app.dev/files/interface-checks',args.output)
