"""Edit and persist every truck profile control using the actual emulator UI."""
import argparse,re,time,xml.etree.ElementTree as ET
import smoke_android as s
p=argparse.ArgumentParser();p.add_argument('--device',required=True);args=p.parse_args();s.device=args.device
if not s.device.startswith('emulator-'):raise SystemExit('Dedicated emulator required')
def click(n):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']));s.run('shell','input','tap',(x1+x2)//2,(y1+y2)//2)
def find(value):
 for attempt in range(10):
  tree=s.ui();n=next((n for n in tree.iter('node') if n.attrib.get('content-desc')==value),None)
  if n is None:n=next((n for n in tree.iter('node') if n.attrib.get('text')==value),None)
  if n is not None:return n
  width,height=map(int,re.findall(r'\d+',s.run('shell','wm','size').decode())[-2:]);s.run('shell','input','swipe',width//2,int(height*.7),width//2,int(height*.3),400)
 raise AssertionError('Control missing: '+value)
def set_field(label,value):click(find(label));s.run('shell','input','text',value);s.run('shell','input','keyevent','BACK')
def check(label,value):
 n=find(label)
 if (n.attrib.get('checked')=='true')!=value:click(n)
def open_profile():s.tap('Truck');s.wait_text('Your truck')
def prefs():return {n.attrib['name']:n.attrib.get('value',n.text) for n in ET.fromstring(s.run('shell','run-as','org.eurorig.app','cat','shared_prefs/truck.xml')).iter() if 'name' in n.attrib}
s.run('shell','am','force-stop','org.eurorig.app');s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('Offline ·');open_profile()
labels=['Height (m)','Width (m)','Length (m)','Loaded gross weight (t)','Maximum loaded axle weight (t)','Total truck and trailer axles','Maximum speed (km/h)']
measurements=['3.89','2.405','12.125','28','9.005','6','70.125']
for label,value in zip(labels,measurements):set_field(label,value)
options=['General hazardous material','Avoid toll roads','Avoid ferries','Avoid unpaved roads','Load harmful to water','Explosive load']
for label in options:check(label,True)
click(find('ADR tunnel restriction code'));s.tap('E · Exclude E');s.tap('Save');s.wait_text('Choose a destination');values=prefs()
for key,value in zip(['height','width','length','weight','axle','axles','top_speed'],measurements):assert float(values[key])==float(value),(key,values)
for key in ['tolls','ferries','unpaved']:assert values[key]=='true',values
assert values['hazardous_load']=='7' and values['tunnel_code']=='5',values
print('PASS: all seven numeric fields, six checkboxes and ADR E persist',flush=True)
s.run('shell','am','force-stop','org.eurorig.app');s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('28.0 t');open_profile()
for label,value in zip(labels,measurements):assert float(find(label).attrib['text'])==float(value),'Reopened profile rounded '+label
print('PASS: all measurement precision survives cold restart and reopening',flush=True)
s.tap('Cancel');open_profile();set_field(labels[0],'0');s.tap('Save');s.wait_text('EuroRig');s.tap('OK');assert prefs()==values,'Invalid height must not overwrite profile';s.tap('Cancel');print('PASS: invalid profile rejected without overwriting saved settings',flush=True)
open_profile()
for label,value in zip(labels,['4','2.55','16.5','40','11.5','5','80']):set_field(label,value)
for label,value in zip(options,[False,False,True,True,False,False]):check(label,value)
click(find('ADR tunnel restriction code'));s.tap('No tunnel code');s.tap('Save');s.wait_text('40.0 t');print('PASS: standard truck restored',flush=True)
