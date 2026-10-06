"""Exercise real on-device Valhalla and GPS on a dedicated emulator (muted).

Test route coordinates come from the native engine, not fictional roads.
"""
import argparse
import time
import re
import smoke_android as s

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device',default='emulator-5554')
parser.add_argument('--prefix',default='android8')
parser.add_argument('--country',help='Country package to exercise instead of the tiny test fixture')
args=parser.parse_args();s.device=args.device
if not s.device.startswith("emulator-"):
    raise SystemExit("This smoke test clears EuroRig data. Use a dedicated emulator only.")
for i in range(60):
    if s.run('shell','getprop','sys.boot_completed',check=False).strip()==b'1':break
    time.sleep(2)
else:raise RuntimeError('Emulator did not boot')
s.run('install','-r',s.ROOT/'app/build/outputs/apk/debug/app-debug.apk')
s.run('install','-r',s.ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
s.run('shell','pm','clear','org.eurorig.app')
extra=[]
if args.country:
    s.run('push',args.country,'/data/local/tmp/test-country.eurorig',timeout=180)
    s.run('shell','run-as','org.eurorig.app','mkdir','-p','files')
    s.run('shell','run-as','org.eurorig.app','cp','/data/local/tmp/test-country.eurorig','files/test-country.eurorig')
    s.run('shell','rm','/data/local/tmp/test-country.eurorig')
    extra=['-e','packagePath','/data/user/0/org.eurorig.app/files/test-country.eurorig']
try:
    output=s.run('shell','am','instrument','-w',*extra,'org.eurorig.app.test/org.eurorig.app.NativeSmokeInstrumentation',timeout=240).decode()
except Exception:
    print(s.run('logcat','-d','-t','300','-s','AndroidRuntime','libc','ActivityManager').decode(errors='replace'),flush=True)
    raise
print(output,flush=True)
(s.ROOT/'dist').mkdir(exist_ok=True)
(s.ROOT/'dist'/f'{args.prefix}-native.txt').write_text(output)
assert 'PASS:' in output,'Native instrumentation failed'
s.run('shell','am','start','-n','org.eurorig.app/.MainActivity')
s.wait_text('Offline ·');s.tap('Plan route');s.wait_text('Route ready for your truck')
s.screenshot(args.prefix+'-native-route');s.tap('Route');s.wait_text('Route instructions')
s.screenshot(args.prefix+'-native-maneuvers');s.tap('Close')
# Loading after process death recreates the engine and reuses the installed tiles.
s.run('shell','am','force-stop','org.eurorig.app')
s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('Offline ·')
s.tap('More')
if any(n.attrib.get('text')=='Mute voice' for n in s.ui().iter('node')):s.tap('Mute voice')
else:s.run('shell','input','keyevent','BACK')
for permission in ['ACCESS_FINE_LOCATION','ACCESS_COARSE_LOCATION']:
    s.run('shell','pm','grant','org.eurorig.app','android.permission.'+permission)
if int(s.run('shell','getprop','ro.build.version.sdk').strip())>=33:
    s.run('shell','pm','grant','org.eurorig.app','android.permission.POST_NOTIFICATIONS')
fixes=[tuple(map(float,line.split(','))) for line in s.run('shell','run-as','org.eurorig.app','cat','files/native-test-fixes.txt').decode().splitlines()]
s.tap('More');s.tap('Start at GPS');lat,lon=fixes[0];s.run('emu','geo','fix',lon,lat)
s.wait_text('Plan route');s.tap('Plan route');s.wait_text('Route ready for your truck')
# Refresh the injected fix after UI inspections so the origin passes freshness checks.
s.run('emu','geo','fix',lon,lat);s.tap('Start guidance');s.tap('Start')
s.wait_text('GPS GUIDANCE');s.run('emu','geo','fix',fixes[1][1],fixes[1][0]);s.wait_text('In')
assert 'isForeground=true' in s.run('shell','dumpsys','activity','services','org.eurorig.app').decode()
s.run('emu','geo','fix',fixes[2][1],fixes[2][0]);s.wait_text('Following')
s.screenshot(args.prefix+'-native-gps')
# Panning suspends following; overview keeps a driver's chosen view until recenter.
map_node=next(n for n in s.ui().iter('node') if n.attrib.get('content-desc','').startswith('Offline road map.'))
x1,y1,x2,y2=map(int,re.findall(r'\d+',map_node.attrib['bounds']))
x=x1+(x2-x1)//3;y=(y1+y2)//2
s.run('shell','input','swipe',x,y,x+60,y+40,500);s.wait_text('Recenter')
s.run('emu','geo','fix',fixes[2][1],fixes[2][0]);s.tap('Recenter');s.wait_text('Following')
s.tap('Overview');s.wait_text('Recenter')
s.run('emu','geo','fix',fixes[2][1],fixes[2][0]);s.wait_text('Recenter')
s.tap('Recenter');s.wait_text('Following')
s.tap('Stop');s.wait_text('Route ready for your truck')
assert 'isForeground=true' not in s.run('shell','dumpsys','activity','services','org.eurorig.app').decode()
s.tap('Maps');s.wait_text('Offline maps');s.screenshot(args.prefix+'-country-maps');s.tap('Close')
print('PASS: native route UI, maneuvers, cold restart, real muted GPS foreground guidance, pan/overview/recenter GPS follow, stop and country map controls',flush=True)
