"""Check failed cold opening retains its saved map selection; dedicated emulator only."""
import argparse, hashlib, json, re, subprocess, xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
import smoke_android as s
import measure_corridors as m

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device', required=True)
parser.add_argument('--prior', action='store_true', help='Verify the historical loss of native selection mode')
args = parser.parse_args()
s.device = args.device
def require(value, message):
    if not value: raise RuntimeError(message)
require(s.device.startswith('emulator-'), 'Dedicated emulator required')
require(s.run('shell', 'getprop', 'ro.hardware').decode().strip() == 'ranchu', 'Dedicated emulator required')
services = s.run('shell', 'dumpsys', 'activity', 'services', 'org.eurorig.app').decode()
require(not any(name in services for name in ('NavigationService', 'MapDownloadService')), 'Stop guidance/downloads before checking recovery')
original = {name: m.read_pref(name + '.xml') for name in ('settings', 'maps', 'truck', 'endpoints')}
selection = re.search(b'<string name="region">([0-9a-f-]{36})</string>', original['settings'] or b'')
require(selection is not None and b'<boolean name="native" value="true"' in original['settings'], 'A selected native map is required')
region = 'files/regions/' + selection.group(1).decode()
descriptor = region + '/manifest.json'
manifest = s.run('shell', 'run-as', 'org.eurorig.app', 'cat', descriptor)
folders = s.run('shell', 'run-as', 'org.eurorig.app', 'ls', 'files/regions')
metadata = json.loads(manifest)
require(type(metadata.get('format')) is int and metadata['format'] in (1, 2, 3, 4), 'Recognized installed map required')
private = root / 'analysis/private/saved-map-recovery'
private.mkdir(parents=True, exist_ok=True)
for name, payload in original.items():
    if payload is not None: (private / (name + '-before.xml')).write_bytes(payload)
(private / 'manifest-before.json').write_bytes(manifest)
(private / 'restore-checkpoint.json').write_text(json.dumps({'region': region, 'missing_preferences': [name for name, payload in original.items() if payload is None]}, indent=2))
def write(path, payload):
    require(path == descriptor or re.fullmatch(r'shared_prefs/(settings|maps|truck|endpoints)\.xml', path), 'Unexpected recovery write path')
    subprocess.run([str(s.ADB), '-s', s.device, 'shell', '-T', 'run-as', 'org.eurorig.app', 'sh', '-c', "'cat > " + path + "'"], input=payload, capture_output=True, check=True, timeout=30)
case = 'prior' if args.prior else 'current'
receipt = {'checked_at': datetime.now(timezone.utc).isoformat(), 'passed': False, 'physical_device_access': False}
try:
    s.run('shell', 'am', 'force-stop', 'org.eurorig.app')
    metadata['format'] = True
    write(descriptor, json.dumps(metadata).encode())
    s.run('shell', 'am', 'start', '-n', 'org.eurorig.app/.MainActivity')
    tree = s.wait_text('Saved map could not load', seconds=40)
    observed = m.read_pref('settings.xml')
    native = any(node.attrib.get('name') == 'native' and node.attrib.get('value') == 'true' for node in ET.fromstring(observed))
    saved = next((node.text for node in ET.fromstring(observed) if node.attrib.get('name') == 'region'), None)
    require(saved == selection.group(1).decode(), 'Failed opening changed selected map identity')
    require(native == (not args.prior), 'Unexpected saved map mode after failed opening')
    require(s.run('shell', 'run-as', 'org.eurorig.app', 'ls', 'files/regions') == folders, 'Failed opening changed installed map directories')
    receipt.update(native_selection_retained=native, selected_region=saved, visible_error=[node.attrib.get('text') for node in tree.iter('node') if 'Saved map could not load' in node.attrib.get('text', '')], apk_sha256=hashlib.sha256((root / 'app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest())
finally:
    s.run('shell', 'am', 'force-stop', 'org.eurorig.app')
    write(descriptor, manifest)
    for name, payload in original.items():
        if payload is None: s.run('shell', 'run-as', 'org.eurorig.app', 'rm', '-f', 'shared_prefs/' + name + '.xml')
        else: write('shared_prefs/' + name + '.xml', payload)
    require(s.run('shell', 'run-as', 'org.eurorig.app', 'cat', descriptor) == manifest, 'Original map descriptor was not restored')
    require(s.run('shell', 'run-as', 'org.eurorig.app', 'ls', 'files/regions') == folders, 'Original installed map directories changed')
    require(all(m.read_pref(name + '.xml') == payload for name, payload in original.items()), 'Original preferences were not restored')
try:
    s.run('shell', 'am', 'start', '-n', 'org.eurorig.app/.MainActivity')
    s.wait_text('Offline · ', seconds=60)
    require(all(m.read_pref(name + '.xml') == payload for name, payload in original.items()), 'Healthy reopening changed original preferences')
finally:
    s.run('shell', 'am', 'force-stop', 'org.eurorig.app')
    for name, payload in original.items():
        if payload is None: s.run('shell', 'run-as', 'org.eurorig.app', 'rm', '-f', 'shared_prefs/' + name + '.xml')
        else: write('shared_prefs/' + name + '.xml', payload)
require(all(m.read_pref(name + '.xml') == payload for name, payload in original.items()), 'Final preferences were not restored')
receipt.update(passed=True, original_map_and_preferences_restored=True, healthy_reopening_verified=True)
(private / (case + '.json')).write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt), flush=True)
