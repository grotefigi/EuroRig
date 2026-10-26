"""First-launch country download from the public HTTPS catalogue, dedicated emulator."""
import argparse
import re
import time
import smoke_android as s

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--installed',action='store_true',help='Resume the offline checks after a completed download');parser.add_argument('--device',default='emulator-5556');parser.add_argument('--prefix',default='android17-download')
args=parser.parse_args();s.device=args.device
if not s.device.startswith('emulator-'):
    raise SystemExit('This smoke test clears EuroRig data. Use a dedicated emulator only.')
for i in range(90):
    if s.run('shell','getprop','sys.boot_completed',check=False).strip()==b'1':break
    time.sleep(2)
else:raise RuntimeError('Emulator boot timed out')
if not args.installed:
    s.run('install','-r',s.ROOT/'app/build/outputs/apk/debug/app-debug.apk')
    s.run('shell','pm','clear','org.eurorig.app')
    s.run('shell','am','start','-n','org.eurorig.app/.MainActivity')
    s.wait_text('No maps installed');s.screenshot(args.prefix+'-empty')
    assert not any('Romania' in n.attrib.get('text','') and 'Offline' in n.attrib.get('text','') for n in s.ui().iter('node'))
    if int(s.run('shell','getprop','ro.build.version.sdk').strip())>=33:
        s.run('shell','pm','grant','org.eurorig.app','android.permission.POST_NOTIFICATIONS')
    s.tap('Maps');s.wait_text('Download all Europe');s.tap('Download Romania')
    deadline=time.monotonic()+600;foreground=False
    while time.monotonic()<deadline:
        foreground|='isForeground=true' in s.run('shell','dumpsys','activity','services','org.eurorig.app').decode()
        tree=s.ui();visible=[n.attrib.get('text','') for n in tree.iter('node')]
        if any('Offline' in text and 'Romania' in text for text in visible):break
        time.sleep(2)
    else:
        s.tap('Maps');s.tap('Download status');raise AssertionError('Country download did not install: '+str([n.attrib.get('text') for n in s.ui().iter('node')]))
    assert foreground,'Download did not run in foreground'
    s.screenshot(args.prefix+'-installed')
# The API 26 image's shell UID cannot change Wi-Fi; root is confined to the emulator guard above.
if int(s.run('shell','getprop','ro.build.version.sdk').strip())<=28:
    s.run('root');s.run('wait-for-device')
# Disable network after downloading: route calculation must still succeed.
s.run('shell','svc','wifi','disable');s.run('shell','svc','data','disable')
# New installs require explicitly selected endpoints, rather than hidden sample points.
for starting,value,label in [(True,'44.52570,26.07340','44.52570, 26.07340'),(False,'44.60080,26.05110','44.60080, 26.05110')]:
    if starting:s.tap('More');s.tap('Change starting point')
    else:s.tap('Search destination or coordinates')
    field=next(n for n in s.ui().iter('node') if n.attrib.get('class')=='android.widget.EditText')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',field.attrib['bounds']));s.run('shell','input','tap',(x1+x2)//2,(y1+y2)//2);s.run('shell','input','text',value);s.wait_text('Select this coordinate');s.tap(label)
s.tap('Plan route');s.wait_text('Route ready for your truck');s.screenshot(args.prefix+'-offline-route')
s.run('shell','am','force-stop','org.eurorig.app')
s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('Offline ·')
s.tap('Plan route');s.wait_text('Route ready for your truck')
s.run('shell','svc','wifi','enable')
s.tap('Maps');s.tap('Downloaded countries');s.wait_text('Romania');s.screenshot(args.prefix+'-countries');s.tap('Close')
s.tap('Maps');s.tap('Download all Europe');time.sleep(2);s.tap('Maps');s.tap('Download status');s.wait_text('All-Europe maps are not published yet')
print('PASS: empty install, public HTTPS Romania download, foreground progress, checksum/install, offline routing/cold restart, country selection, honest Europe availability',flush=True)
