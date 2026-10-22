"""Exercise real Android GPS-provider guidance on the dedicated EuroRig emulator."""
import argparse,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--device',required=True);a=p.parse_args()
if not a.device.startswith('emulator-'):p.error('Only a dedicated emulator is accepted.')
def adb(*args,check=True):return subprocess.run(['adb','-s',a.device,*map(str,args)],capture_output=True,check=check,timeout=30)
for permission in ['ACCESS_COARSE_LOCATION','ACCESS_FINE_LOCATION','POST_NOTIFICATIONS']:
    adb('shell','pm','grant','org.eurorig.app.dev','android.permission.'+permission)
adb('shell','cmd','location','set-location-enabled','true')
ready='/sdcard/Android/data/org.eurorig.app.dev/files/gps-check-points.txt'
adb('shell','rm','-f',ready)
out=root/'work'/'interface-evidence';out.mkdir(exist_ok=True,parents=True)
with (out/'native-gps.txt').open('wb') as f:
    proc=subprocess.Popen(['adb','-s',a.device,'shell','am','instrument','-w','-e','gpsOnly','true','org.eurorig.app.dev.test/org.eurorig.app.NativeSmokeInstrumentation'],stdout=f,stderr=subprocess.STDOUT)
    deadline=time.monotonic()+90;points=[]
    while time.monotonic()<deadline and proc.poll() is None:
        data=adb('shell','cat',ready,check=False)
        if data.returncode==0:
            points=[tuple(map(float,line.split(','))) for line in data.stdout.decode().splitlines()];break
        time.sleep(.5)
    if not points:raise RuntimeError('GPS test did not produce a real route.')
    # Let the activity enter the foreground, then feed its actual Android GPS provider.
    time.sleep(3)
    for lat,lon in points:
        for _ in range(3):
            if proc.poll() is not None:break
            adb('emu','geo','fix',lon,lat);time.sleep(1)
    proc.wait(timeout=60)
result=(out/'native-gps.txt').read_text(encoding='utf8',errors='replace');print(result)
if 'PASS:' not in result:raise SystemExit('Native GPS check failed.')
adb('pull','/sdcard/Android/data/org.eurorig.app.dev/files/interface-checks/real-gps-guidance.png',out)
