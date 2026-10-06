"""Test GPS acquisition, following, themes, signal loss, recovery and stop on an emulator."""
import argparse,re,time,subprocess,sys
import smoke_android as s
p=argparse.ArgumentParser();p.add_argument('--device',required=True);p.add_argument('--prefix',required=True);args=p.parse_args();s.device=args.device
if not s.device.startswith('emulator-'):raise SystemExit('Dedicated emulator required')
if int(s.run('shell','getprop','ro.build.version.sdk').strip())<31:raise SystemExit('GPS-loss injection needs Android 12/API 31+; use the API 37 emulator')
s.run('shell','am','force-stop','org.eurorig.app');s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('Offline ·')
s.tap('Search destination or coordinates');tree=s.ui();n=next(n for n in tree.iter('node') if n.attrib.get('class')=='android.widget.EditText');a,b,c,d=map(int,re.findall(r'\d+',n.attrib['bounds']));s.run('shell','input','tap',(a+c)//2,(b+d)//2);s.run('shell','input','text','44.60080,26.05110');s.wait_text('Select this coordinate');s.tap('44.60080, 26.05110')
fixes=[tuple(map(float,line.split(','))) for line in s.run('shell','run-as','org.eurorig.app','cat','files/native-test-fixes.txt').decode().splitlines()]
s.tap('More');s.tap('Start at GPS');s.run('emu','geo','fix',fixes[0][1],fixes[0][0]);s.wait_text('Plan route');time.sleep(1);s.tap('Plan route');s.wait_text('Route ready for your truck');s.run('emu','geo','fix',fixes[0][1],fixes[0][0]);s.tap('Start guidance');s.tap('Start');s.wait_text('GPS GUIDANCE');s.run('emu','geo','fix',fixes[1][1],fixes[1][0]);s.wait_text('Following');s.screenshot(args.prefix+'-following')
subprocess.run([sys.executable,str(s.ROOT/'tools/audit_theme.py'),'--device',s.device,'--prefix',args.prefix+'-guidance-theme','--guidance'],check=True)
try:
 s.run('shell','cmd','location','set-location-enabled','false')
 time.sleep(1)
 # Some Google emulator images show a system notice when location is disabled.
 tree=s.ui()
 if any(n.attrib.get('package')=='com.google.android.gms' and n.attrib.get('text')=='No location access' for n in tree.iter('node')):s.tap('Close')
 s.wait_text('GPS signal lost',30);tree=s.ui();assert any('Speed unavailable' in n.attrib.get('content-desc','') for n in tree.iter('node'));print('PASS: stale GPS shows signal loss and unavailable speed',flush=True)
finally:
 s.run('shell','cmd','location','set-location-enabled','true')
s.run('emu','geo','fix',fixes[2][1],fixes[2][0]);s.wait_text('GPS position received');s.wait_text('Following');print('PASS: fresh GPS recovers guidance and camera following',flush=True)
s.tap('Truck');s.wait_text('Stop guidance before');s.tap('OK');assert 'isForeground=true' in s.run('shell','dumpsys','activity','services','org.eurorig.app').decode();print('PASS: profile edits blocked while guidance continues',flush=True)
s.tap('Stop');s.wait_text('Route ready for your truck');assert 'isForeground=true' not in s.run('shell','dumpsys','activity','services','org.eurorig.app').decode();print('PASS: stop terminates foreground guidance',flush=True)
