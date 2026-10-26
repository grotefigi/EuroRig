"""Exercise map selection, saved places, route editing and delivery controls on an emulator."""
import argparse,re,time,xml.etree.ElementTree as ET
import smoke_android as s
p=argparse.ArgumentParser();p.add_argument('--device',required=True);args=p.parse_args();s.device=args.device
if not s.device.startswith('emulator-'):raise SystemExit('Dedicated emulator required')
def bounds(n):return list(map(int,re.findall(r'\d+',n.attrib['bounds'])))
def node(predicate):return next(n for n in s.ui().iter('node') if predicate(n.attrib))
def click(n):
 a,b,c,d=bounds(n);s.run('shell','input','tap',(a+c)//2,(b+d)//2)
def coordinate(value,label):
 s.tap('Search destination or coordinates');click(node(lambda a:a.get('class')=='android.widget.EditText'));s.run('shell','input','text',value);s.wait_text('Select this coordinate');s.tap(label);time.sleep(1)
def pick():
 tree=s.ui();top=next(n for n in tree.iter('node') if n.attrib.get('text')=='Choose a destination');end=next(n for n in tree.iter('node') if n.attrib.get('content-desc')=='Edit starting point and destination');road=next(n for n in tree.iter('node') if n.attrib.get('content-desc','').startswith('Offline road map.'))
 a,b,c,d=bounds(road);s.run('shell','input','tap',(a+c)//2,(bounds(top)[3]+bounds(end)[1])//2);s.wait_text('Set destination')
s.run('shell','am','force-stop','org.eurorig.app');s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('Offline ·')
coordinate('45.43500,28.04900','45.43500, 28.04900');pick();s.tap('Save favourite');s.tap('More');s.tap('Favourites');s.wait_text('Local favourites');saved=node(lambda a:a.get('resource-id')=='android:id/text1').attrib['text'];s.tap(saved);s.wait_text('B '+saved);print('PASS: road-segment tap, saved favourite and favourite destination selection',flush=True)
pick();s.tap('Set starting point');s.wait_text('Choose a destination');pick();s.tap('Set destination');print('PASS: both map endpoint actions',flush=True)
click(node(lambda a:a.get('content-desc')=='Edit starting point and destination'));s.wait_text('Route planner');s.tap('Swap start and destination');print('PASS: route planner and endpoint swap',flush=True)
click(node(lambda a:a.get('content-desc','').startswith('Route preferences:')));n=node(lambda a:a.get('class')=='android.widget.CheckBox');click(n);s.tap('Save');s.wait_text('Delivery');print('PASS: permission checkbox enables trip-scoped delivery option',flush=True)
s.run('shell','am','force-stop','org.eurorig.app');s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('Offline ·');assert not any(n.attrib.get('text')=='Delivery' for n in s.ui().iter('node')),'Delivery permission must not persist into a new session'
s.tap('More');s.tap('Favourites');s.wait_text(saved);s.tap('Close');print('PASS: favourites persist; delivery permission resets after process death',flush=True)
s.tap('More');s.tap('Fit map');s.tap('Maps');s.tap('Choose another country');s.wait_text('Download a country');s.wait_text('Romania');s.tap('Close');print('PASS: map fit and live public country catalogue',flush=True)
s.tap('More');s.tap('About and limitations');s.tap('Licences');s.tap('ODbL-1.0.txt');s.wait_text('Open Database License');s.tap('Close');print('PASS: actual attribution licence content opens',flush=True)
