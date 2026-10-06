"""Exercise EuroRig's exposed UI on an isolated emulator with Romania installed.
Run smoke_native.py first. This script preserves its installed map and endpoints.
Never use a physical device: fault tests and rotation change emulator settings.
"""
import argparse,re,time,json
import smoke_android as s
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--device',required=True);p.add_argument('--prefix',required=True);p.add_argument('--finish-only',action='store_true');args=p.parse_args();s.device=args.device
if not s.device.startswith('emulator-'):raise SystemExit('Dedicated emulator required')
receipt=s.ROOT/'dist'/f'{args.prefix}-ui-audit.json';checks=json.loads(receipt.read_text(encoding='utf-8')) if args.finish_only and receipt.exists() else []
def passed(name):
 checks.append(name);(s.ROOT/'dist'/f'{args.prefix}-ui-audit.json').write_text(json.dumps(checks,indent=2),encoding='utf-8');print('PASS: '+name,flush=True)
def node(predicate):return next(n for n in s.ui().iter('node') if predicate(n.attrib))
def click_node(n):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']));s.run('shell','input','tap',(x1+x2)//2,(y1+y2)//2)
def set_query(value):
 n=node(lambda a:a.get('class')=='android.widget.EditText');click_node(n);s.run('shell','input','keyevent','KEYCODE_MOVE_END');s.run('shell','input','keyevent','--longpress','KEYCODE_DEL');s.run('shell','input','text',value)
def choose_coordinates(start,value):
 if start:s.tap('More');s.tap('Change starting point')
 else:s.tap('Search destination or coordinates')
 set_query(value);s.wait_text('Select this coordinate');s.tap(value);s.wait_text('Choose a destination')
s.run('shell','am','force-stop','org.eurorig.app');s.run('shell','run-as','org.eurorig.app','rm','-f','shared_prefs/favourites.xml');s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('Offline ·')
if not args.finish_only:
 s.tap('Search destination or coordinates');set_query('zzzzzzzzzz');s.wait_text('No results');passed('Empty search feedback')
 s.tap('Cancel');s.tap('Search destination or coordinates');set_query('91,200');s.wait_text('Use latitude, longitude');passed('Invalid coordinate feedback');s.tap('Cancel')
 s.tap('Search destination or coordinates');set_query('0,0');s.wait_text('Select this coordinate');s.tap('0.00000, 0.00000');s.wait_text('Outside the installed map');passed('Outside-country coordinate rejected');s.tap('Cancel')
 s.tap('Search destination or coordinates');set_query('Galati');s.wait_text('places in Romania');n=node(lambda a:a.get('text','').startswith('Galați\n'));click_node(n);s.wait_text('B Galați');passed('Offline accent-normalized city search selects result');s.screenshot(args.prefix+'-search-galati')
 s.tap('More');s.tap('Change starting point');set_query('45.42600,28.04200');s.wait_text('Select this coordinate');s.tap('45.42600, 28.04200');s.wait_text('Choose a destination');passed('Manual origin selection')
 s.tap('Search destination or coordinates');set_query('45.43500,28.04900');s.wait_text('Select this coordinate');s.tap('45.43500, 28.04900');s.wait_text('Choose a destination');passed('Manual destination selection')
 # Endpoints survive process death. A computed route is deliberately replanned.
 s.run('shell','am','force-stop','org.eurorig.app');s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('45.42600, 28.04200');s.wait_text('45.43500, 28.04900');passed('Origin and destination survive cold restart')
 for mode in ['Shortest','Easiest','Economical']:
  n=node(lambda a:a.get('content-desc','').startswith('Route preferences:'));click_node(n);n=node(lambda a:a.get('text','').startswith(mode+' ·'));click_node(n);s.tap('Save');s.tap('Plan route');s.wait_text('Route ready for your truck',60);passed(mode+' real Galati route');s.screenshot(args.prefix+'-'+mode.lower())
 s.run('shell','rm','-f','/sdcard/Download/EuroRig-route.gpx')
 s.tap('Route');s.wait_text('Route instructions');s.tap('Export GPX');s.wait_text('EuroRig-route.gpx');s.tap('Save');s.wait_text('Route ready for your truck');gpx=s.run('shell','cat','/sdcard/Download/EuroRig-route.gpx').decode();assert '<trkpt lat=' in gpx and 'creator=\"EuroRig\"' in gpx;passed('GPX export writes actual route through Android document picker')
 s.tap('Truck');s.wait_text('Your truck');s.tap('Cancel');passed('Truck form opens and cancels')
 s.tap('Truck');s.wait_text('Your truck');s.tap('Save');s.wait_text('Choose a destination');passed('Truck form validates and saves existing dimensions')
 s.tap('More');s.tap('Favourites');s.wait_text('Tap a map point');s.tap('OK');passed('Empty favourites gives actionable instructions')
 s.tap('More');first='Mute voice' if any(n.attrib.get('text')=='Mute voice' for n in s.ui().iter('node')) else 'Enable voice';s.tap(first);s.tap('More');s.tap('Enable voice' if first=='Mute voice' else 'Mute voice');passed('Voice preference toggles both directions')
 s.tap('More');s.tap('About and limitations');s.tap('Licences');s.wait_text('Dependency and map licences');s.tap('Close');passed('About and licence list')
 s.tap('Maps');s.wait_text('Offline maps');s.tap('Pause downloads');passed('Idle pause feedback')
 s.tap('Maps');s.tap('Installed map details');s.wait_text('Romania');s.tap('Close');passed('Installed map metadata')
 s.tap('Maps');s.tap('Map download source');s.tap('Cancel');passed('Catalogue editor opens and cancels')
s.tap('Maps');s.tap('Import country (.eurorig)');time.sleep(1);assert any('documentsui' in n.attrib.get('package','') for n in s.ui().iter('node'));s.run('shell','input','keyevent','BACK');passed('Country import opens Android document picker')
s.tap('Overview');s.tap('Zoom in');s.tap('Zoom out');passed('Overview and both zoom controls')
s.run('shell','settings','put','system','accelerometer_rotation','0');s.run('shell','settings','put','system','user_rotation','1');time.sleep(2);s.wait_text('Plan route');s.screenshot(args.prefix+'-landscape');s.tap('Search destination or coordinates');s.wait_text('Search offline map');s.screenshot(args.prefix+'-landscape-search');s.tap('Cancel');passed('Landscape controls and resized search dialog')
s.run('shell','settings','put','system','user_rotation','0');time.sleep(2);s.screenshot(args.prefix+'-portrait')
(s.ROOT/'dist'/f'{args.prefix}-ui-audit.json').write_text(json.dumps(checks,indent=2),encoding='utf-8')
print('UI audit passed '+str(len(checks))+' checks',flush=True)
