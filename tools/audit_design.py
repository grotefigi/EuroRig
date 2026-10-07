"""Check real Android touch bounds and paired colour contrast on a dedicated emulator.

Uses the existing UI driver; restores display, font and motion settings after the run.
Screenshots require a separate visual review, especially for text wrapping.
"""
import argparse
import json
import re
import smoke_android as s


def palette_contrast():
    source=(s.ROOT/'app/src/main/java/org/eurorig/app/AppPalette.java').read_text()
    palettes=[]
    for dark in (False,True):
        colors={}
        for name,a,b in re.findall(r'(\w+)=dark\?0xff([\da-f]{6}):0xff([\da-f]{6});',source):
            colors[name]=a if dark else b
        colors.update(re.findall(r'(\w+)=0xff([\da-f]{6});',source))
        def luminance(value):
            channels=[int(value[i:i+2],16)/255 for i in (0,2,4)]
            linear=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in channels]
            return sum(a*b for a,b in zip(linear,(.2126,.7152,.0722)))
        def ratio(a,b):
            values=sorted((luminance(colors[a]),luminance(colors[b])))
            return (values[1]+.05)/(values[0]+.05)
        measured={}
        for foreground in ('text','muted','accent'):
            for background in ('background','surface','secondary'):
                value=ratio(foreground,background)
                assert value>=4.5,(dark,foreground,background,value)
                measured[foreground+'/'+background]=round(value,2)
        for foreground,background,minimum in [('onAccent','accent',4.5),('label','land',4.5),('border','surface',3),('border','secondary',3)]:
            value=ratio(foreground,background)
            assert value>=minimum,(dark,foreground,background,value)
            measured[foreground+'/'+background]=round(value,2)
        palettes.append({'dark':dark,'ratios':measured})
    return palettes


def dialog_targets(title, classes, scrolls=1, density=1):
    s.wait_text(title);reached={}
    for attempt in range(scrolls):
        tree=s.ui()
        for node in tree.iter('node'):
            a=node.attrib
            if a.get('class') not in classes or a.get('package')!='org.eurorig.app':continue
            x1,y1,x2,y2=map(int,re.findall(r'\d+',a['bounds']))
            if x2-x1>=48*density-1 and y2-y1>=48*density-1:
                reached[a.get('content-desc') or a.get('text')]=[x1,y1,x2,y2]
        if attempt+1<scrolls:s.run('shell','input','swipe',round(180*density),round(620*density),round(180*density),round(250*density),400)
    return reached


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device',required=True)
    parser.add_argument('--prefix',required=True)
    args=parser.parse_args();s.device=args.device
    if not s.device.startswith('emulator-'):raise SystemExit('Dedicated emulator required')
    settings=[('system','font_scale'),('global','window_animation_scale'),('global','transition_animation_scale'),('global','animator_duration_scale')]
    previous={item:s.run('shell','settings','get',*item).decode().strip() for item in settings}
    previous_size=s.run('shell','wm','size').decode()
    override=re.search(r'Override size: (\d+x\d+)',previous_size)
    receipt={'contrast':palette_contrast(),'layouts':[],'dialogs':[]}
    try:
        for namespace,key in settings[1:]:s.run('shell','settings','put',namespace,key,'0')
        density=int(re.findall(r'\d+',s.run('shell','wm','density').decode())[-1])/160
        for width,height,font in [(375,812,1),(375,812,2),(812,375,1),(1280,800,1),(800,1280,2)]:
            s.run('shell','wm','size',f'{round(width*density)}x{round(height*density)}')
            s.run('shell','settings','put','system','font_scale',font)
            s.run('shell','am','force-stop','org.eurorig.app')
            s.run('shell','am','start','-n','org.eurorig.app/.MainActivity')
            tree=s.wait_text('Plan route')
            reached={};snapshots=[]
            # A short landscape rail scrolls. Prove each control is reachable at its full size;
            # a clipped fragment at the scroll boundary is not its complete touch target.
            for attempt in range(3 if width>height else 1):
                if attempt:tree=s.ui()
                controls=[]
                for node in tree.iter('node'):
                    a=node.attrib
                    if a.get('clickable')!='true' or a.get('package')!='org.eurorig.app':continue
                    x1,y1,x2,y2=map(int,re.findall(r'\d+',a['bounds']))
                    label=a.get('content-desc') or a.get('text')
                    assert x1>=0 and y1>=0 and x2<=round(width*density) and y2<=round(height*density),(width,height,font,'outside',a)
                    if x2-x1<48*density-1 or y2-y1<48*density-1:
                        assert width>height,(width,height,font,'undersized',a)
                        continue
                    controls.append({'label':label,'bounds':[x1,y1,x2,y2]});reached[label]=controls[-1]
                for i,a in enumerate(controls):
                    for b in controls[i+1:]:
                        ax,ay,ar,ab=a['bounds'];bx,by,br,bb=b['bounds']
                        assert min(ar,br)<=max(ax,bx) or min(ab,bb)<=max(ay,by),(width,height,font,'overlap',a,b)
                s.screenshot(f'{args.prefix}-{width}x{height}-font{font}-scroll{attempt}')
                snapshots.append(controls)
                if width>height:s.run('shell','input','swipe',round(100*density),round(height*.78*density),round(100*density),round(height*.25*density),400)
            required={'Truck','Maps','Route','More','Plan route','Start guidance','Zoom in','Zoom out','Overview','Edit starting point and destination','Edit vehicle dimensions and ADR profile','Search destination or coordinates','Use current GPS location as starting point'}
            assert required<=set(reached),(width,height,font,'unreachable',required-set(reached))
            assert any(label.startswith('Route preferences:') for label in reached),(width,height,font,'mode unreachable')
            receipt['layouts'].append({'width_dp':width,'height_dp':height,'font_scale':font,'snapshots':snapshots})
            print(f'PASS: {width}x{height} dp, font {font}, {len(reached)} reachable controls',flush=True)
        s.run('shell','wm','size',f'{round(480*density)}x{round(800*density)}')
        for font in (1,2):
            s.run('shell','settings','put','system','font_scale',font)
            s.run('shell','am','force-stop','org.eurorig.app');s.run('shell','am','start','-n','org.eurorig.app/.MainActivity');s.wait_text('Plan route')
            s.tap('Maps')
            targets=dialog_targets('Offline maps',{'android.widget.Button'},density=density)
            assert {'Download status','Close'}<=set(targets),('map dialog targets',font,targets)
            s.screenshot(f'{args.prefix}-maps-font{font}');s.tap('Close')
            receipt['dialogs'].append({'name':'Maps','font_scale':font,'targets':targets})
            if font==1:
                s.tap('Truck');targets=dialog_targets('Your truck',{'android.widget.EditText','android.widget.CheckBox','android.widget.Spinner'},6,density)
                expected={'Height (m)','Width (m)','Length (m)','Loaded gross weight (t)','Maximum loaded axle weight (t)','Total truck and trailer axles','Maximum speed (km/h)','General hazardous material','Avoid toll roads','Avoid ferries','Avoid unpaved roads','Load harmful to water','Explosive load','ADR tunnel restriction code'}
                assert expected<=set(targets),('truck targets below 48 dp or unreachable',expected-set(targets))
                receipt['dialogs'].append({'name':'Truck','font_scale':font,'targets':targets});s.tap('Cancel')
                tree=s.ui();mode=next(n for n in tree.iter('node') if n.attrib.get('content-desc','').startswith('Route preferences:'));x1,y1,x2,y2=map(int,re.findall(r'\d+',mode.attrib['bounds']));s.run('shell','input','tap',(x1+x2)//2,(y1+y2)//2)
                targets=dialog_targets('Route preferences',{'android.widget.RadioButton','android.widget.CheckBox'},3,density)
                assert len(targets)==4,('route preference targets below 48 dp or unreachable',targets)
                receipt['dialogs'].append({'name':'Route preferences','font_scale':font,'targets':targets});s.tap('Cancel')
            print(f'PASS: dialog touch targets and map action labels, font {font}',flush=True)
    finally:
        s.run('shell','wm','size',override.group(1) if override else 'reset')
        for (namespace,key),value in previous.items():
            if value=='null':s.run('shell','settings','delete',namespace,key)
            else:s.run('shell','settings','put',namespace,key,value)
        (s.ROOT/'dist'/f'{args.prefix}-design-audit.json').write_text(json.dumps(receipt,indent=2))
