"""Shared local roadmap: separate writer lanes, evidence-based totals, read-only dashboard."""
import argparse
import copy
import json
import os
import tempfile
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

DATA = Path(__file__).resolve().parents[1] / 'docs/planning/progress'
STATUSES = {'pending', 'in_progress', 'blocked', 'partial', 'submitted', 'complete'}


def read(path):
    with Path(path).open(encoding='utf-8') as stream:
        return json.load(stream)


def atomic_write(path, data):
    path = Path(path)
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + '.', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write('\n')
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def lane_ids(plan, lane):
    return {item['id'] for item in plan['packages' if lane == 'codex' else 'batches']}


def validate_lane(plan, lane, state):
    if state.get('lane') != lane or not isinstance(state.get('items'), dict):
        raise ValueError('Invalid lane state')
    ids = lane_ids(plan, lane)
    if set(state['items']) != ids:
        raise ValueError('Progress IDs do not match the plan')
    datetime.fromisoformat(state['updated_at'])
    for identifier, item in state['items'].items():
        status = item.get('status')
        if status not in STATUSES or (lane == 'deepseek' and status == 'complete') or (lane == 'codex' and status == 'submitted'):
            raise ValueError('Invalid status for ' + lane + ': ' + str(status))
        if status in ('complete', 'submitted') and not item.get('evidence'):
            raise ValueError('Evidence is required for ' + identifier)
        if status == 'blocked' and not item.get('blocker'):
            raise ValueError('A concrete blocker is required for ' + identifier)


def snapshot(root=DATA):
    root = Path(root)
    plan = read(root / 'plan.json')
    for collection in ('packages', 'batches'):
        ids = [item['id'] for item in plan[collection]]
        if not ids or len(ids) != len(set(ids)):
            raise ValueError('Plan IDs must be nonempty and unique')
    states = {lane: read(root / (lane + '.json')) for lane in ('codex', 'deepseek')}
    for lane, state in states.items():
        validate_lane(plan, lane, state)
    packages = plan['packages']
    accepted = sum(item['status'] == 'complete' for item in states['codex']['items'].values())
    submitted = sum(item['status'] == 'submitted' for item in states['deepseek']['items'].values())
    return {
        'plan': plan,
        'lanes': states,
        'overall': {'accepted': accepted, 'total': len(packages),
                    'percent': round(100 * accepted / len(packages), 1) if packages else 0,
                    'method': 'Equal-weight accepted roadmap packages; partial work and worker drafts earn no product-completion credit. This is not effort or time remaining.'},
        'deepseek': {'submitted': submitted, 'total': len(plan['batches']),
                     'percent': round(100 * submitted / len(plan['batches']), 1) if plan['batches'] else 0},
    }


def update(root, lane, identifier=None, status=None, current=None, evidence=None, blocker=None, expected_updated_at=None):
    if lane not in ('codex', 'deepseek'):
        raise ValueError('Unknown lane')
    root = Path(root)
    plan = read(root / 'plan.json')
    path = root / (lane + '.json')
    state = read(path)
    validate_lane(plan, lane, state)
    if expected_updated_at is not None and state['updated_at'] != expected_updated_at:
        raise ValueError('Lane changed since it was read; reread before updating')
    next_state = copy.deepcopy(state)
    if identifier is not None:
        if identifier not in state['items'] or status is None:
            raise ValueError('A valid item and status are required')
        item = next_state['items'][identifier]
        if status in ('complete', 'submitted') and not evidence:
            raise ValueError('Supply current evidence when completing or submitting')
        item.update(status=status, blocker=blocker if status == 'blocked' else None)
        if evidence is not None:
            item['evidence'] = evidence
    elif status is not None or evidence is not None or blocker is not None:
        raise ValueError('Item is required for status/evidence/blocker updates')
    if current is not None:
        next_state['current'] = current
    if identifier is None and current is None:
        raise ValueError('Nothing to update')
    next_state['updated_at'] = datetime.now(timezone.utc).isoformat()
    next_state['history'] = (next_state.get('history', []) + [{
        'at': next_state['updated_at'], 'item': identifier, 'status': status,
        'summary': current or identifier, 'evidence': evidence, 'blocker': blocker,
    }])[-50:]
    validate_lane(plan, lane, next_state)
    atomic_write(path, next_state)
    return next_state


def handler_for(root):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            path = urlsplit(self.path).path
            if path == '/':
                body = Path(__file__).with_name('roadmap_dashboard.html').read_bytes()
                kind, code = 'text/html; charset=utf-8', 200
            elif path == '/api/progress':
                try:
                    body = json.dumps(snapshot(root), ensure_ascii=False, allow_nan=False).encode('utf-8')
                    kind, code = 'application/json; charset=utf-8', 200
                except (ValueError, KeyError, OSError) as error:
                    body = json.dumps({'error': str(error)}).encode('utf-8')
                    kind, code = 'application/json; charset=utf-8', 503
            else:
                body, kind, code = b'Not found', 'text/plain', 404
            self.send_response(code)
            self.send_header('Content-Type', kind)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-dir', type=Path, default=DATA)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('show')
    serve = commands.add_parser('serve')
    serve.add_argument('--port', type=int, default=8766)
    change = commands.add_parser('update')
    change.add_argument('--lane', required=True, choices=('codex', 'deepseek'))
    change.add_argument('--item')
    change.add_argument('--status', choices=sorted(STATUSES))
    change.add_argument('--current')
    change.add_argument('--evidence')
    change.add_argument('--blocker')
    change.add_argument('--expect-updated-at')
    args = parser.parse_args()
    if args.command == 'show':
        print(json.dumps(snapshot(args.data_dir), ensure_ascii=False, indent=2))
    elif args.command == 'update':
        update(args.data_dir, args.lane, args.item, args.status, args.current, args.evidence, args.blocker, args.expect_updated_at)
        print('PROGRESS_UPDATED ' + args.lane)
    else:
        server = ThreadingHTTPServer(('127.0.0.1', args.port), handler_for(args.data_dir))
        print('Roadmap: http://127.0.0.1:' + str(server.server_port), flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()


if __name__ == '__main__':
    main()
