"""Verify actual rendered themes, persistence and guidance preservation on an emulator."""
import argparse,re,time,xml.etree.ElementTree as ET
from collections import Counter
from PIL import Image
import smoke_android as s
p=argparse.ArgumentParser();p.add_argument('--device',required=True);p.add_argument('--prefix',required=True);p.add_argument('--guidance',action='store_true');args=p.parse_args();s.device=args.device
if not s.device.startswith('emulator-'):raise SystemExit('Dedicated emulator required')
def set_theme(dark):
 s.tap('More');s.tap('Appearance');tree=s.wait_text('Appearance');toggle=next(n for n in tree.iter('node') if n.attrib.get('class')=='android.widget.Switch')
 if (toggle.attrib.get('checked')=='true')!=dark:
  a,b,c,d=map(int,re.findall(r'\d+',toggle.attrib['bounds']));s.run('shell','input','tap',(a+c)//2,(b+d)//2)
 else:s.tap('Close')
 time.sleep(1);s.screenshot(args.prefix+('-dark' if dark else '-light'))
 colors=Counter(Image.open(s.ROOT/'dist/screenshots'/((args.prefix+('-dark' if dark else '-light'))+'.png')).convert('RGB').get_flattened_data())
 assert colors[(22,34,37) if dark else (233,236,228)]>1000,'Map theme did not change'
 assert colors[(16,25,28) if dark else (247,249,252)]>1000,'Controls theme did not change'
 settings=ET.fromstring(s.run('shell','run-as','org.eurorig.app','cat','shared_prefs/settings.xml'))
 assert next(n.attrib['value'] for n in settings if n.attrib['name']=='dark_mode')==str(dark).lower()
 s.tap('Truck') if not args.guidance else None
 if not args.guidance:s.wait_text('Your truck');s.tap('Cancel')
 if args.guidance:
  s.wait_text('GPS GUIDANCE');assert 'isForeground=true' in s.run('shell','dumpsys','activity','services','org.eurorig.app').decode();s.wait_text('Following')
 print('PASS: '+('dark' if dark else 'light')+' map, controls, dialog and saved preference'+(' preserve guidance' if args.guidance else ''),flush=True)
if not args.guidance:s.run('shell','am','force-stop','org.eurorig.app');s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('Offline ·')
set_theme(True);set_theme(False)
if not args.guidance:
 s.run('shell','am','force-stop','org.eurorig.app');s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('Offline ·');s.tap('More');s.tap('Appearance');assert next(n for n in s.ui().iter('node') if n.attrib.get('class')=='android.widget.Switch').attrib['checked']=='false';s.tap('Close');print('PASS: light-mode switch survives restart',flush=True)
