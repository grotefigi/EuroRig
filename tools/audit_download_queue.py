"""Exercise real map service against a tiny original fixture catalogue; dedicated emulator only."""
import argparse, hashlib, json, re, subprocess, sys, threading, time, uuid, xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
root = Path(__file__).resolve().parents[1]

def require(condition, message):
    if not condition:
        raise RuntimeError(message)
sys.path.insert(0, str(root / 'tools'))
import smoke_android as s
import measure_corridors as m
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device', required=True)
parser.add_argument('--fixtures', type=Path, required=True)
parser.add_argument('--prior', action='store_true', help='Verify the historical first-country-only failure on an older installed APK')
parser.add_argument('--case', choices=('complete', 'mismatched', 'regional'), default='complete')
args = parser.parse_args()
s.device = args.device
if not s.device.startswith('emulator-'):
    raise RuntimeError('Dedicated emulator required')
private = root / 'analysis/private/download-queue'
private.mkdir(parents=True, exist_ok=True)
require(s.run('shell', 'getprop', 'ro.hardware').decode().strip() == 'ranchu', 'Download queue safety or result check failed')
require(not any((name in s.run('shell', 'dumpsys', 'activity', 'services', 'org.eurorig.app').decode() for name in ('NavigationService', 'MapDownloadService'))), 'Download queue safety or result check failed')

def raw(name):
    return None if m.read_pref(name + '.xml') is None else s.run('exec-out', 'run-as', 'org.eurorig.app', 'cat', 'shared_prefs/' + name + '.xml')

def prefs(name, payload):
    if payload is None:
        s.run('shell', 'run-as', 'org.eurorig.app', 'rm', '-f', 'shared_prefs/' + name + '.xml')
    else:
        subprocess.run([str(s.ADB), '-s', s.device, 'shell', '-T', 'run-as', 'org.eurorig.app', 'sh', '-c', "'cat > shared_prefs/" + name + ".xml'"], input=payload, capture_output=True, check=True, timeout=30)
original = {name: raw(name) for name in ('settings', 'maps', 'truck', 'endpoints')}
selected = re.search(b'<string name="region">([0-9a-f-]{36})</string>', original['settings'] or b'')
require(selected is not None, 'Install a single Romania map before running this audit')
selection = selected.group(1).decode()
region = 'files/regions/' + selection
before = s.run('exec-out', 'run-as', 'org.eurorig.app', 'cat', region + '/manifest.json')
metadata = json.loads(before)
if metadata.get('format') not in (1, 2, 3) or metadata.get('country', metadata.get('name', '')) not in ('RO', 'Romania'):
    raise RuntimeError('Start this gate from a single Romania country map')
require(s.run('shell', 'run-as', 'org.eurorig.app', 'ls', 'files/regions').decode().split() == [selection], 'Download queue safety or result check failed')
try:
    s.run('shell', 'run-as', 'org.eurorig.app', 'ls', 'files/downloads')
except subprocess.CalledProcessError:
    pass
else:
    raise RuntimeError('Queue gate requires absent download directory; preserve pre-existing archives')
size = int(s.run('shell', 'run-as', 'org.eurorig.app', 'du', '-k', region).decode().splitlines()[-1].split()[0]) * 1024
free = int(s.run('shell', 'df', '-k', '/data').decode().splitlines()[-1].split()[3]) * 1024
require(free > size + 200 * 1024 * 1024, 'Download queue safety or result check failed')
owned = 'files/eurorig-queue-owned-' + uuid.uuid4().hex
ready = False
created = False
requests = []
for name, payload in original.items():
    if payload is not None:
        (private / (name + '-before.xml')).write_bytes(payload)
(private / 'restore-checkpoint.json').write_text(json.dumps({'owned': owned, 'selection': selection, 'missing_preferences': [name for name, payload in original.items() if payload is None]}, indent=2))
(private / 'manifest-before.json').write_bytes(before)
packages = {name: (args.fixtures / f'{name}.eurorig').read_bytes() for name in ('ro', 'hu')}
if args.case == 'mismatched':
    packages['hu'] = (args.fixtures / 'stale-hu.eurorig').read_bytes()
catalog = {'format': 1, 'native_version': '0.6.3', 'europe_complete': True, 'maps': [{'id': 'queue-qa-' + name, 'name': label, 'country': name.upper(), 'bytes': len(packages[name]), 'sha256': hashlib.sha256(packages[name]).hexdigest(), 'url': name + '.eurorig'} for name, label in [('ro', 'Original RO queue fixture'), ('hu', 'Original HU queue fixture')]]}
if args.case == 'regional':
    for entry in catalog['maps']:
        entry.update(country='RO', region_name='Original region fixture')

class Handler(BaseHTTPRequestHandler):

    def do_GET(self):
        requests.append(self.path)
        if self.path == '/catalog.json':
            body = json.dumps(catalog).encode()
        elif self.path in ('/ro.eurorig', '/hu.eurorig'):
            body = packages[self.path[1:3]]
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()

    def log_message(self, *unused):
        pass
server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
reverse=f'tcp:{server.server_port}'
s.run('reverse','--no-rebind',reverse,reverse)
try:
    s.run('shell', 'am', 'force-stop', 'org.eurorig.app')
    s.run('shell', 'run-as', 'org.eurorig.app', 'mkdir', owned)
    created = True
    s.run('shell', 'run-as', 'org.eurorig.app', 'cp', '-R', region, owned + '/selected-region', timeout=120)
    ready = True
    tree = ET.fromstring(original['maps'] or b'<map/>')
    for node in list(tree):
        if node.attrib.get('name') == 'catalog':
            tree.remove(node)
    ET.SubElement(tree, 'string', name='catalog').text = f'http://127.0.0.1:{server.server_port}/catalog.json'
    prefs('maps', ET.tostring(tree, encoding='utf-8'))
    s.run('shell', 'am', 'start', '-n', 'org.eurorig.app/.MainActivity')
    s.wait_text('Offline', seconds=40)
    s.tap('Maps')
    s.tap('Download all Europe')
    s.tap('Maps')
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        view = s.ui()
        texts = [n.attrib.get('text', '') for n in view.iter('node')]
        terminal = [text for text in texts if text.startswith('Map download:') or 'Original HU queue fixture ready for offline navigation' in text or 'Original HU queue fixture installed for offline navigation' in text]
        if terminal:
            break
        time.sleep(0.5)
    else:
        raise AssertionError('Real service did not report terminal queue status')
    settings = raw('settings')
    current = re.search(b'<string name="region">([0-9a-f-]{36})</string>', settings).group(1).decode()
    selected_manifest = json.loads(s.run('exec-out', 'run-as', 'org.eurorig.app', 'cat', 'files/regions/' + current + '/manifest.json'))
    members = sorted([entry['country'] for entry in selected_manifest['countries']]) if selected_manifest['format'] == 4 else [selected_manifest['country']]
    try:
        archives = s.run('shell', 'run-as', 'org.eurorig.app', 'ls', 'files/downloads').decode().split()
    except subprocess.CalledProcessError as error:
        if b'No such file or directory' not in error.stderr:
            raise
        archives = []
    status = terminal
    proof = {'checked_at': datetime.now(timezone.utc).isoformat(), 'original_artificial_fixture': True, 'actual_service': True, 'requests': requests, 'installed_members': members, 'retained_downloads': archives, 'visible_status': status, 'apk_sha256': s.run('shell', 'sha256sum', s.run('shell', 'pm', 'path', 'org.eurorig.app').decode().strip().removeprefix('package:')).decode().split()[0], 'physical_device_access': False}
    proof['passed'] = False
    (private / ('prior.json' if args.prior else args.case + '.json')).write_text(json.dumps(proof, indent=2) + '\n')
    print(json.dumps(proof), flush=True)
    if not args.prior and args.case == 'complete':
        s.tap('Downloaded countries')
        s.wait_text('No downloaded packages await installation.')
    if args.prior:
        require(members == ['RO'] and len(archives) == 2, 'Expected original first-only bug was not reproduced')
    elif args.case == 'complete':
        require(members == ['HU', 'RO'] and (not archives), 'All downloaded countries must be installed without retained redundant archives')
    elif args.case == 'mismatched':
        entry = catalog['maps'][1]
        expected = entry['id'] + '-' + entry['sha256'] + '.eurorig'
        require(members == ['RO'] and archives == [expected] and any(('different generations' in text for text in status)), 'Rejected second package must preserve first country and verified retry archive')
    else:
        require(current == selection and (not archives) and (requests == ['/catalog.json']) and any(('Regional packages' in text for text in status)), 'Unsupported regions must refuse before downloading or replacing maps')
finally:
    s.run('shell', 'am', 'force-stop', 'org.eurorig.app')
    server.shutdown()
    server.server_close()
    s.run('reverse','--remove',reverse)
    if ready:
        base = s.run('shell', 'run-as', 'org.eurorig.app', 'pwd').decode().strip()
        require(base in ('/data/data/org.eurorig.app', '/data/user/0/org.eurorig.app'), 'Download queue safety or result check failed')
        for folder in s.run('shell', 'run-as', 'org.eurorig.app', 'ls', 'files/regions').decode().split():
            if folder == selection:
                continue
            require(re.fullmatch('[0-9a-f-]{36}', folder), 'Download queue safety or result check failed')
            directory = 'files/regions/' + folder
            require(s.run('shell', 'run-as', 'org.eurorig.app', 'realpath', directory).decode().strip() == base + '/' + directory, 'Download queue safety or result check failed')
            marker = json.loads(s.run('exec-out', 'run-as', 'org.eurorig.app', 'cat', directory + '/manifest.json'))
            require(marker['generation_id'] == '0000000000000001', 'Download queue safety or result check failed')
            s.run('shell', 'run-as', 'org.eurorig.app', 'rm', '-rf', directory)
        try:
            s.run('shell', 'run-as', 'org.eurorig.app', 'ls', '-d', region)
        except subprocess.CalledProcessError:
            s.run('shell', 'run-as', 'org.eurorig.app', 'mv', owned + '/selected-region', region)
        for name, payload in original.items():
            prefs(name, payload)
            require(raw(name) == payload, name)
        require(s.run('exec-out', 'run-as', 'org.eurorig.app', 'cat', region + '/manifest.json') == before, 'Download queue safety or result check failed')
        require(s.run('shell', 'run-as', 'org.eurorig.app', 'realpath', owned).decode().strip() == base + '/' + owned, 'Download queue safety or result check failed')
        s.run('shell', 'run-as', 'org.eurorig.app', 'rm', '-rf', owned)
        for entry in catalog['maps']:
            target = 'files/downloads/' + entry['id'] + '-' + entry['sha256'] + '.eurorig'
            s.run('shell', 'run-as', 'org.eurorig.app', 'rm', '-f', target, target + '.part')
        s.run('shell', 'run-as', 'org.eurorig.app', 'rmdir', 'files/downloads', check=False)
        try:
            s.run('shell', 'run-as', 'org.eurorig.app', 'ls', 'files/downloads')
        except subprocess.CalledProcessError as error:
            if b'No such file or directory' not in error.stderr:
                raise
        else:
            raise AssertionError('Owned download directory was not reclaimed')
        print('RESTORED original selected map and all4 preference files; removed only owned fixture data', flush=True)
    elif created:
        base = s.run('shell', 'run-as', 'org.eurorig.app', 'pwd').decode().strip()
        require(base in ('/data/data/org.eurorig.app', '/data/user/0/org.eurorig.app'), 'Download queue safety or result check failed')
        require(s.run('shell', 'run-as', 'org.eurorig.app', 'realpath', owned).decode().strip() == base + '/' + owned, 'Download queue safety or result check failed')
        s.run('shell', 'run-as', 'org.eurorig.app', 'rm', '-rf', owned)
proof['passed'] = True
proof['original_map_and_preferences_restored'] = True
(private / ('prior.json' if args.prior else args.case + '.json')).write_text(json.dumps(proof, indent=2) + '\n')
print('PASS real download queue gate', flush=True)
