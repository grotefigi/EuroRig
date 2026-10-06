"""Exercise the installed Android UI through adb and save genuine screenshots.

Run only against a dedicated emulator. This does not install or operate Eurowag.
"""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ADB=ROOT/'.toolchain/sdk/platform-tools/adb.exe'


def run(*args, check=True, timeout=30):
    return subprocess.run([str(ADB),'-s',device,*map(str,args)],capture_output=True,check=check,timeout=timeout).stdout


def ui(recover_system_ui=True):
    for attempt in range(3):
        try:
            run('shell','uiautomator','dump','/sdcard/eurorig-smoke.xml')
            break
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            if attempt==2:raise
            time.sleep(.5)
    tree=ET.fromstring(run('shell','cat','/sdcard/eurorig-smoke.xml'))
    # Cold-booting these isolated images occasionally leaves a System UI ANR.
    # Recover only that OS dialog; never dismiss an EuroRig crash/failure.
    if recover_system_ui and any(n.attrib.get('text')=="System UI isn't responding" for n in tree.iter('node')):
        close=next(n for n in tree.iter('node') if n.attrib.get('text')=='Close app')
        x1,y1,x2,y2=map(int,re.findall(r'\d+',close.attrib['bounds']))
        run('shell','input','tap',(x1+x2)//2,(y1+y2)//2)
        print('Recovered emulator System UI cold-boot ANR',flush=True)
        return ui(False)
    return tree


def tap(text):
    tree=ui()
    node=next((n for n in tree.iter('node') if text.casefold() in
               (n.attrib.get('text','').casefold(),n.attrib.get('content-desc','').casefold())),None)
    if node is None:
        raise AssertionError(f'UI text not found: {text}. Visible: {[n.attrib.get("text") for n in tree.iter("node")]}')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.attrib['bounds']))
    run('shell','input','tap',(x1+x2)//2,(y1+y2)//2)


def wait_text(fragment, seconds=20):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        tree=ui()
        if any(fragment in n.attrib.get('text','') for n in tree.iter('node')):
            return tree
        time.sleep(.5)
    raise AssertionError('UI did not display: '+fragment+'. Visible: '+str([n.attrib.get('text') for n in tree.iter('node')]))


def screenshot(name):
    folder=ROOT/'dist/screenshots';folder.mkdir(parents=True,exist_ok=True)
    path=folder/(name+'.png');path.write_bytes(run('exec-out','screencap','-p'))
    print('Screenshot: '+str(path),flush=True)


if __name__=='__main__':
    import runpy
    runpy.run_path(str(ROOT/'tools/smoke_native.py'),run_name='__main__')
