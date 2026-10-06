"""First-launch country download from the public HTTPS catalogue, dedicated emulator."""
import argparse
import re
import time
import smoke_android as s

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device',default='emulator-5556');parser.add_argument('--prefix',default='android17-download')
args=parser.parse_args();s.device=args.device
for i in range(90):
    if s.run('shell','getprop','sys.boot_completed',check=False).strip()==b'1':break
    time.sleep(2)
else:raise RuntimeError('Emulator boot timed out')
s.run('install','-r',s.ROOT/'app/build/outputs/apk/debug/app-debug.apk')
s.run('shell','pm','clear','org.eurorig.app')
s.run('shell','am','start','-n','org.eurorig.app/.MainActivity')
s.wait_text('No maps installed');s.screenshot(args.prefix+'-empty')
assert not any('Romania' in n.attrib.get('text','') and 'OFFLINE' in n.attrib.get('text','') for n in s.ui().iter('node'))
if int(s.run('shell','getprop','ro.build.version.sdk').strip())>=33:
    s.run('shell','pm','grant','org.eurorig.app','android.permission.POST_NOTIFICATIONS')
s.tap('Maps');s.wait_text('Download all Europe');s.tap('Download Romania')
deadline=time.monotonic()+600;foreground=False
while time.monotonic()<deadline:
    foreground|='isForeground=true' in s.run('shell','dumpsys','activity','services','org.eurorig.app').decode()
    tree=s.ui();visible=[n.attrib.get('text','') for n in tree.iter('node')]
    if any('OFFLINE' in text and 'Romania' in text for text in visible):break
    time.sleep(2)
else:
    s.tap('Maps');s.tap('Download status');raise AssertionError('Country download did not install: '+str([n.attrib.get('text') for n in s.ui().iter('node')]))
assert foreground,'Download did not run in foreground'
s.screenshot(args.prefix+'-installed')
# Disable network after downloading: route calculation must still succeed.
s.run('shell','svc','wifi','disable');s.run('shell','svc','data','disable')
s.tap('Plan route');s.wait_text('Route ready for your truck');s.screenshot(args.prefix+'-offline-route')
s.run('shell','am','force-stop','org.eurorig.app')
s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('Valhalla truck')
s.tap('Plan route');s.wait_text('Route ready for your truck')
s.run('shell','svc','wifi','enable')
s.tap('Maps');s.tap('Downloaded countries');s.wait_text('Romania');s.screenshot(args.prefix+'-countries');s.tap('Close')
s.tap('Maps');s.tap('Download all Europe');time.sleep(2);s.tap('Maps');s.tap('Download status');s.wait_text('All-Europe maps are not published yet')
print('PASS: empty install, public HTTPS Romania download, foreground progress, checksum/install, offline routing/cold restart, country selection, honest Europe availability',flush=True)
