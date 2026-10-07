"""Audit the real country chooser on a dedicated emulator using a local test catalogue.

Synthetic country sizes test presentation only. No map files are downloaded.
Existing map/settings preferences, display size, font and motion settings are restored.
"""
import argparse
import hashlib
import json
import re
import threading
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import smoke_android as s
from audit_design import palette_contrast


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device',required=True)
    parser.add_argument('--resume',action='store_true',help='Reuse passed layouts only for the exact installed APK')
    args=parser.parse_args();s.device=args.device
    if not s.device.startswith('emulator-'):raise SystemExit('Dedicated emulator required')
    folder=s.ROOT/'analysis/private/country-menu';folder.mkdir(parents=True,exist_ok=True)
    catalogue={'format':1,'native_version':'0.6.3','europe_complete':False,'maps':[
        {'id':key,'name':name,'country':code,'bytes':size,'url':'unused.eurorig','sha256':'0'*64,**extra}
        for key,name,code,size,extra in [
            ('albania','Albania','AL',16_200_000,{}),
            ('france-north','France north','FR',220_000_000,{'region_name':'Northern France'}),
            ('france-south','France south','FR',180_000_000,{'region_name':'Southern France'}),
            ('romania','Romania','RO',407_495_153,{}),
            ('russia','Russia','RU',1_000_000,{}),
            ('united-kingdom','United Kingdom','GB',1_080_000_000,{})]]}
    class Catalogue(BaseHTTPRequestHandler):
        def do_GET(self):
            payload=json.dumps(catalogue).encode()
            self.send_response(200);self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
        def log_message(self,*unused):pass
    server=ThreadingHTTPServer(('127.0.0.1',0),Catalogue)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    settings=[('system','font_scale'),('global','window_animation_scale'),('global','transition_animation_scale'),('global','animator_duration_scale')]
    previous={key:s.run('shell','settings','get',*key).decode().strip() for key in settings}
    old_size=s.run('shell','wm','size').decode();override=re.search(r'Override size: (\d+x\d+)',old_size)
    density=int(re.findall(r'\d+',s.run('shell','wm','density').decode())[-1])/160
    backups={}
    package=s.run('shell','pm','path','org.eurorig.app').decode().strip().removeprefix('package:')
    installed_sha=s.run('shell','sha256sum',package).decode().split()[0]
    local_sha=hashlib.sha256((s.ROOT/'app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest()
    if installed_sha!=local_sha:raise SystemExit('Installed APK does not match the current build')
    receipt_path=folder/'receipt.json'
    receipt=json.loads(receipt_path.read_text(encoding='utf-8')) if args.resume else {'synthetic_catalogue':True,'contrast':palette_contrast(),'layouts':[],'checks':[]}
    if args.resume and receipt.get('apk_sha256')!=installed_sha:raise SystemExit('Cannot resume a different or unidentified APK')
    receipt['apk_sha256']=installed_sha
    done={(row['width_dp'],row['height_dp'],row['font_scale'],row['dark']) for row in receipt['layouts']}
    def prefs(name,data):
        host=folder/(name+'.xml');host.write_bytes(data)
        target='/data/local/tmp/eurorig-country-menu-'+name+'.xml'
        s.run('push',host,target);s.run('shell','run-as','org.eurorig.app','mkdir','-p','shared_prefs')
        s.run('shell','run-as','org.eurorig.app','cp',target,'shared_prefs/'+name+'.xml')
    def node(label):
        return next(n for n in s.ui().iter('node') if label in (n.attrib.get('text'),n.attrib.get('content-desc')))
    def open_menu():
        s.tap('Maps');s.wait_text('Offline maps');s.tap('Choose another country');s.wait_text('Download a country')
    try:
        s.run('shell','am','force-stop','org.eurorig.app')
        for name in ('maps','settings'):
            exists=s.run('shell','run-as','org.eurorig.app','ls','shared_prefs/'+name+'.xml',check=False)
            backups[name]=s.run('shell','run-as','org.eurorig.app','cat','shared_prefs/'+name+'.xml') if exists else None
        root=ET.fromstring(backups['maps'] or b'<map/>')
        for item in list(root):
            if item.get('name')=='catalog':root.remove(item)
        ET.SubElement(root,'string',name='catalog').text=f'http://10.0.2.2:{server.server_port}/catalog.json'
        prefs('maps',ET.tostring(root,encoding='utf-8'))
        for namespace,key in settings[1:]:s.run('shell','settings','put',namespace,key,'0')
        for dark in (False,True):
            root=ET.fromstring(backups['settings'] or b'<map/>')
            for item in list(root):
                if item.get('name')=='dark_mode':root.remove(item)
            ET.SubElement(root,'boolean',name='dark_mode',value=str(dark).lower())
            for width,height,font in [(375,812,1),(375,812,2),(812,375,2),(1280,800,1),(800,1280,2)]:
                if (width,height,font,dark) in done:continue
                s.run('shell','am','force-stop','org.eurorig.app');prefs('settings',ET.tostring(root,encoding='utf-8'))
                s.run('shell','wm','size',f'{round(width*density)}x{round(height*density)}')
                s.run('shell','settings','put','system','font_scale',font)
                s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('EuroRig')
                # The landscape control rail may need scrolling to reveal the dock.
                for attempt in range(6):
                    maps=[n for n in s.ui().iter('node') if n.attrib.get('text')=='Maps']
                    if maps:
                        left,top,right,bottom=map(int,re.findall(r'\d+',maps[0].attrib['bounds']))
                        if bottom-top>=48*density-1:break
                    s.run('shell','input','swipe',round(100*density),round(height*.78*density),round(100*density),round(height*.25*density),400)
                open_menu();reached={};labels=set()
                for attempt in range(7):
                    tree=s.ui()
                    for n in tree.iter('node'):
                        a=n.attrib;labels.add(a.get('text',''))
                        if a.get('clickable')!='true' or a.get('package')!='org.eurorig.app':continue
                        bounds=list(map(int,re.findall(r'\d+',a['bounds'])));left,top,right,bottom=bounds
                        assert left>=0 and top>=0 and right<=round(width*density) and bottom<=round(height*density),a
                        if right-left>=48*density-1 and bottom-top>=48*density-1:
                            reached[a.get('content-desc') or a.get('text')]=bounds
                    if attempt==0:(folder/f'{width}x{height}-font{font}-dark{dark}.png').write_bytes(s.run('exec-out','screencap','-p'))
                    if {'Download Albania','Choose regions of France','Download Romania','Download United Kingdom','Close'}<=set(reached):break
                    scroll=next(n for n in tree.iter('node') if n.attrib.get('class')=='android.widget.ScrollView')
                    left,top,right,bottom=map(int,re.findall(r'\d+',scroll.attrib['bounds']))
                    s.run('shell','input','swipe',(left+right)//2,bottom-8,(left+right)//2,top+8,400)
                assert {'Download Albania','Choose regions of France','Download Romania','Download United Kingdom','Close'}<=set(reached),reached
                assert 'Russia' not in labels,labels
                assert '407.5 MB download' in labels or 'Installed · 407.5 MB download' in labels,labels
                assert node('Download all Europe').attrib['enabled']=='false'
                receipt['layouts'].append({'width_dp':width,'height_dp':height,'font_scale':font,'dark':dark,'targets':reached})
                s.tap('Close')
                if font==1 and width==375:
                    open_menu();s.tap('Choose regions of France');s.wait_text('France regions');s.wait_text('Northern France');s.wait_text('Southern France')
                    assert node('Download these regions').attrib['enabled']=='true'
                    for label in ('Download these regions','Back'):
                        left,top,right,bottom=map(int,re.findall(r'\d+',node(label).attrib['bounds']))
                        assert right-left>=48*density-1 and bottom-top>=48*density-1,(label,[left,top,right,bottom])
                    (folder/f'regions-dark{dark}.png').write_bytes(s.run('exec-out','screencap','-p'))
                    s.tap('Back');s.wait_text('Download a country');s.tap('Close')
                    open_menu();s.tap('Choose regions of France');s.wait_text('France regions');s.run('shell','input','keyevent','BACK');s.wait_text('Download a country');s.tap('Close')
                print(f'PASS: country menu {width}x{height} dp, font {font}, dark {dark}',flush=True)
        catalogue['maps']=[];open_menu();s.wait_text('No country packages');assert node('Download all Europe').attrib['enabled']=='false';s.tap('Close')
        item={'id':'romania','name':'Romania','bytes':1,'url':'unused.eurorig','sha256':'0'*64}
        catalogue['maps']=[item,item];s.tap('Maps');s.tap('Choose another country');s.wait_text('Invalid country catalogue');s.tap('OK')
        receipt['checks']=['actual decimal download size','Russia excluded','unpublished Europe disabled','regions aggregate and drill-down','button and system Back return to countries','48 dp action targets reachable','light/dark contrast','200% font and reduced motion']
        receipt['checks']+=['empty catalogue has no enabled download','duplicate package IDs rejected']
    finally:
        s.run('shell','am','force-stop','org.eurorig.app')
        for name,data in backups.items():
            if data is None:s.run('shell','run-as','org.eurorig.app','rm','-f','shared_prefs/'+name+'.xml')
            else:prefs(name,data)
        s.run('shell','wm','size',override.group(1) if override else 'reset')
        for (namespace,key),value in previous.items():
            s.run('shell','settings','delete',namespace,key) if value=='null' else s.run('shell','settings','put',namespace,key,value)
        server.shutdown();server.server_close()
        (folder/'receipt.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
    print('Country menu audit passed',flush=True)


if __name__=='__main__':main()
