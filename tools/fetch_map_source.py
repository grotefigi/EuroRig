#!/usr/bin/env python3
"""Fetch a pinned map source: a small integrity-and-space wrapper around curl.

curl implements the transport (Range continuation, redirects, retries); this adds everything curl will not
do for you, and it is deliberately arranged so that nothing harmful can be written before the source has
been identified:

  1. every fetch probes first - one byte at the offset held (byte 0 for a fresh fetch) - and the probe's
     validators (ETag, Last-Modified, the declared total) are persisted to <name>.part.json BEFORE any
     byte is streamed, so even an interrupted first attempt is resumable
  2. the probe must answer 206 with a Content-Range whose start, end and total all agree with what is held
     and expected. A changed ETag (or Last-Modified when there is no ETag), a non-206 answer, an
     inconsistent total, or a 416 offset all make the tool REFUSE and leave the existing prefix intact
  3. a resume is pinned to the probed validator with `If-Range`. If the source changes between the probe
     and the transfer, or the transfer's Content-Range is missing or different, the .part is truncated
     back to exactly the bytes that were there before, so a wrong-source or wrong-range body is never kept
  4. only the FINAL HTTP header block is parsed (the block resets at each status line), so a redirect's
     headers cannot leak into what the tool believes it downloaded
  5. a .part that is already the full length is verified and promoted without any HTTP request; if its
     hash does not match it is refused explicitly and kept, never promoted
  6. free space is checked before the probe (the resume need) and again before a restart (the whole file
     plus reserve); destination, .part, validator metadata, probe headers, transfer headers and receipt
     paths must all be distinct

    # plan only: no network request, prints what it would do and whether there is room
    python fetch_map_source.py --url https://download.geofabrik.de/europe-261006.osm.pbf \\
        --bytes 35145100444 --md5 cdc42a828731c185fed7b352908bd087 --dir D:/sources

    # do it
    python fetch_map_source.py --url ... --bytes ... --md5 ... --dir D:/sources --fetch
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path

DEFAULT_RESERVE = 2 * 1024 ** 3
BLOCK = 1 << 22
LOOPBACK = ('127.0.0.1', 'localhost', '::1')
SAFE_NAME = re.compile(r'^[A-Za-z0-9._-]+$')
HTTP_STATUS = re.compile(r'^HTTP/\d(?:\.\d)?\s+(\d{3})')
CONTENT_RANGE = re.compile(r'^bytes\s+(\d+)-(\d+)/(\d+|\*)$')
WINDOWS_DEVICES = {'CON', 'PRN', 'AUX', 'NUL', *(f'COM{i}' for i in range(1, 10)),
                   *(f'LPT{i}' for i in range(1, 10))}
META_SUFFIXES = ('.part', '.part.json')


class Refused(Exception):
    """A precondition failed. Raised only BEFORE anything is written or fetched."""


def check_url(url):
    parsed = urllib.parse.urlparse(url)
    if not parsed.hostname:
        raise Refused(f'url has no host: {url!r}')
    if parsed.username or parsed.password:
        raise Refused('refusing a url with embedded credentials: pass no user:password@')
    if parsed.scheme == 'https':
        return url
    if parsed.scheme == 'http' and parsed.hostname in LOOPBACK:
        return url
    raise Refused(f'refusing {parsed.scheme or "scheme-less"} url {url!r}: use https '
                  '(http is allowed only for a loopback host, for tests)')


def check_md5(value):
    text = (value or '').strip().lower()
    if len(text) != 32 or any(c not in '0123456789abcdef' for c in text):
        raise Refused(f'expected md5 must be 32 hex characters, got {value!r}')
    return text


def check_name(name):
    if not name or not SAFE_NAME.match(name):
        raise Refused(f'unsafe output name {name!r}: letters, digits, dot, dash and underscore only')
    if name in ('.', '..') or name.startswith('-'):
        raise Refused(f'unsafe output name {name!r}')
    if name.split('.')[0].upper() in WINDOWS_DEVICES:
        raise Refused(f'{name!r} is a reserved device name on Windows')
    if name.endswith(META_SUFFIXES):
        raise Refused(f'{name!r} would shadow the .part or validator metadata file; pick another name')
    return name


def siblings(destination):
    """Every transient file the tool writes lives beside the destination and is named after it."""
    return {'partial': destination.with_name(destination.name + '.part'),
            'meta': destination.with_name(destination.name + '.part.json'),
            'probe headers': destination.with_name(destination.name + '.probe.headers'),
            'transfer headers': destination.with_name(destination.name + '.transfer.headers')}


def check_distinct(paths):
    seen = {}
    for label, path in paths.items():
        resolved = Path(os.path.abspath(path))
        if resolved in seen:
            raise Refused(f'{label} and {seen[resolved]} are the same path: {resolved}')
        seen[resolved] = label


def curl_path():
    found = shutil.which('curl')
    if not found:
        raise Refused('curl is not on PATH; this tool deliberately does not reimplement HTTP transport')
    return found


def load_meta(destination):
    path = siblings(destination)['meta']
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return {}


def save_meta(destination, meta):
    siblings(destination)['meta'].write_text(json.dumps(meta, indent=1) + '\n', encoding='utf-8')


def digest(path, algorithm):
    hasher = hashlib.new(algorithm)
    with path.open('rb') as handle:
        while chunk := handle.read(BLOCK):
            hasher.update(chunk)
    return hasher.hexdigest()


def free_bytes(path):
    probe = Path(os.path.abspath(path))
    while not probe.exists() and probe.parent != probe:
        probe = probe.parent
    return shutil.disk_usage(probe).free


def parse_final_headers(text):
    """(status, headers) from the LAST header block of a curl header dump.

    A redirect chain writes several blocks. Only the final one describes the response being acted on, and
    the block restarts at every status line so an earlier block's Content-Length or stale ETag cannot leak
    into what the tool believes it downloaded.
    """
    status, block = None, {}
    for line in text.splitlines():
        match = HTTP_STATUS.match(line.strip())
        if match:
            status, block = match.group(1), {}
            continue
        if ':' in line:
            key, _, value = line.partition(':')
            block[key.strip().lower()] = value.strip()
    return status, block


def content_range(value, expected_total=None):
    """Parse `bytes start-end/total` completely; anything else is an error."""
    match = CONTENT_RANGE.match((value or '').strip())
    if not match:
        raise ValueError(f'no usable Content-Range: {value!r}')
    start, end, total = int(match.group(1)), int(match.group(2)), match.group(3)
    if total == '*':
        raise ValueError(f'Content-Range has no total: {value!r}')
    if end < start:
        raise ValueError(f'Content-Range end before start: {value!r}')
    if expected_total is not None and int(total) != expected_total:
        raise ValueError(f'Content-Range total {total} != expected {expected_total}')
    return start, end, int(total)


def curl_argv(curl, url, extra, retries=5, allow_http=False):
    """HTTPS is pinned for the request AND for every redirect, and --fail keeps an error page from being
    written into the .part as if it were source data. allow_http is set only when the url is already known
    to be loopback http (check_url refuses everything else), which is what lets the test server be
    exercised without weakening production."""
    proto = '=http,https' if allow_http else '=https'
    return [curl, '--location', '--max-redirs', '5', '--proto', proto, '--proto-redir', proto,
            '--fail', '--silent', '--show-error', '--retry', str(retries), '--retry-delay', '5',
            '--retry-connrefused', '--user-agent', 'EuroRig map source fetcher/1.0', *extra, url]


def run_curl(curl, url, extra, timeout, retries=5, allow_http=False):
    return subprocess.run(curl_argv(curl, url, extra, retries, allow_http), capture_output=True,
                          text=True, timeout=timeout * 60)


def probe(destination, curl, url, offset, retries, timeout):
    """Read one byte at `offset` and report the FINAL response's status and headers.

    This runs before every transfer, including a fresh one at offset 0, because it is the only way to
    learn the validators and the real size before writing anything.
    """
    headers = siblings(destination)['probe headers']
    allow_http = urllib.parse.urlparse(url).scheme == 'http'
    try:
        result = run_curl(curl, url, ['--range', f'{offset}-{offset}', '--output', os.devnull,
                                      '--dump-header', str(headers), '--write-out', '%{http_code}'], 2,
                          retries=retries, allow_http=allow_http)
    except subprocess.TimeoutExpired:
        headers.unlink(missing_ok=True)
        return {'status': '', 'fields': {}, 'curl_exit': None, 'stderr': 'the range probe timed out'}
    text = headers.read_text(encoding='utf-8', errors='replace') if headers.is_file() else ''
    headers.unlink(missing_ok=True)
    status, fields = parse_final_headers(text)
    return {'status': status or '', 'fields': fields, 'curl_exit': result.returncode,
            'http_code': (result.stdout or '').strip(), 'stderr': (result.stderr or '').strip()}


def if_range_value(etag, last_modified):
    """The strongest validator the source gave us, in the form If-Range wants."""
    if etag and not etag.startswith('W/'):
        return etag
    return last_modified or None


def fetch(url, expected_bytes, expected_md5, destination, reserve, timeout=60, restart=False,
          retries=5, log=print):
    """Returns a receipt. Refused is raised only before the first write; after that, every problem is
    reported inside the receipt, because by then the .part is evidence."""
    paths = siblings(destination)
    partial, meta_file = paths['partial'], paths['meta']
    curl = curl_path()
    allow_http = urllib.parse.urlparse(url).scheme == 'http'
    have = partial.stat().st_size if partial.is_file() else 0
    meta = load_meta(destination)
    receipt = {'url': url, 'destination': str(destination), 'expected_bytes': expected_bytes,
               'expected_md5': expected_md5, 'started_at': time.strftime('%Y-%m-%dT%H:%M:%S'),
               'started': False, 'events': [], 'curl': os.path.basename(curl)}
    if meta and (meta.get('expected_bytes') not in (None, expected_bytes)
                 or (meta.get('expected_md5') and meta['expected_md5'] != expected_md5)
                 or (meta.get('url') and meta['url'] != url)):
        receipt['events'].append('validator metadata describes a different source: discarding it')
        meta = {}

    # (5) A .part that is already the full length needs no request: verify it and promote, or refuse.
    if have == expected_bytes and not restart:
        receipt['events'].append('the .part is already the full length: verifying it without a request')
        actual = digest(partial, 'md5')
        if actual == expected_md5:
            receipt['md5'] = actual
            receipt['sha256'] = digest(partial, 'sha256')
            os.replace(partial, destination)
            meta_file.unlink(missing_ok=True)
            receipt['outcome'] = 'ok'
            receipt['bytes'] = destination.stat().st_size
            receipt['promoted_without_request'] = True
            return receipt
        raise Refused(f'the .part is {have:,} B, exactly the expected size, but its md5 is {actual}, '
                      f'not {expected_md5}. It is kept and never promoted: delete it, or pass --restart '
                      f'to fetch the file again (needs {expected_bytes + reserve:,} B)')
    if have > expected_bytes:
        if not restart:
            raise Refused(f'the .part ({have:,} B) is longer than the expected size '
                          f'({expected_bytes:,} B); prefix kept. Pass --restart to replace it')

    # (6) Space is checked before the probe, on the need a resume would have.
    if free_bytes(destination.parent) < (expected_bytes - have) + reserve:
        raise Refused(f'needs {expected_bytes - have + reserve:,} B (remaining {expected_bytes - have:,} B '
                      f'+ {reserve:,} B reserve), free {free_bytes(destination.parent):,} B on '
                      f'{destination.parent.anchor}')
    if restart and have:
        if free_bytes(destination.parent) < expected_bytes + reserve:
            raise Refused(f'a restart needs the whole {expected_bytes + reserve:,} B, free '
                          f'{free_bytes(destination.parent):,} B on {destination.parent.anchor}')
        receipt['events'].append(f'discarding the {have:,} B prefix as requested (--restart)')
        partial.unlink(missing_ok=True)
        have = 0

    # (1) Always probe, so validators exist for an interrupted FIRST fetch too.
    state = probe(destination, curl, url, have, retries, timeout)
    receipt['probe'] = {'status': state['status'], 'http_code': state.get('http_code'),
                        'content_range': state['fields'].get('content-range'),
                        'content_length': state['fields'].get('content-length'),
                        'etag': state['fields'].get('etag'),
                        'last_modified': state['fields'].get('last-modified')}
    live_etag = state['fields'].get('etag')
    live_modified = state['fields'].get('last-modified')
    if state['status'] == '416':
        raise Refused(f'the source answered 416 for byte {have:,}: the .part is at least as long as the '
                      f'source. Verify it or pass --restart')
    if have:
        if state['status'] != '206':
            raise Refused(f'the source does not support ranged resume: probe answered '
                          f'{state["status"] or state["stderr"] or "no status"}. Pass --restart to discard '
                          f'the {have:,} B prefix and fetch the whole file '
                          f'(needs {expected_bytes + reserve:,} B)')
        try:
            start, end, total = content_range(state['fields'].get('content-range'), expected_bytes)
        except ValueError as error:
            raise Refused(f'the source sent an unusable ranged response: {error}')
        if start != have or end != have:
            raise Refused(f'the source answered a range it was not asked for (asked {have}-{have}, got '
                          f'{start}-{end}); the {have:,} B prefix is untouched')
        stored = (meta.get('etag'), meta.get('last_modified'))
        if stored != (None, None):
            if stored[0] and live_etag != stored[0]:
                raise Refused(f'the source changed since the partial download (etag {stored[0]!r} -> '
                              f'{live_etag!r}); the prefix is untouched. Pass --restart to replace it')
            if not stored[0] and (live_modified or None) != (stored[1] or None):
                raise Refused(f'the source changed since the partial download (last-modified '
                              f'{stored[1]!r} -> {live_modified!r}); the prefix is untouched. '
                              f'Pass --restart to replace it')
    else:
        # A fresh fetch: a 206 probe is ideal, a 200 is acceptable, but the declared size must match.
        declared = state['fields'].get('content-length')
        total = None
        if state['fields'].get('content-range'):
            try:
                _, _, total = content_range(state['fields'].get('content-range'))
            except ValueError as error:
                raise Refused(f'the source sent an unusable ranged response: {error}')
        live_total = total if total is not None else (int(declared) if declared else None)
        if live_total is not None and live_total != expected_bytes:
            raise Refused(f'the source now declares {live_total:,} B but {expected_bytes:,} B was '
                          'expected: the pin has moved. Nothing was written')
        if state['status'] not in ('200', '206'):
            raise Refused(f'the probe answered {state["status"] or state["stderr"] or "no status"}; '
                          'nothing was written')

    # (2) Validators are persisted BEFORE streaming, for a fresh fetch as much as a resume.
    save_meta(destination, {'url': url, 'expected_bytes': expected_bytes, 'expected_md5': expected_md5,
                            'etag': live_etag or meta.get('etag'),
                            'last_modified': live_modified or meta.get('last_modified'),
                            'have': have, 'reserve': reserve,
                            'updated': time.strftime('%Y-%m-%dT%H:%M:%S')})

    headers = siblings(destination)['transfer headers']
    extra = ['--output', str(partial), '--dump-header', str(headers),
             '--max-filesize', str(expected_bytes + 1),   # a declared oversize must fail before writing
             '--write-out', '%{http_code} %{size_download} %{url_effective}']
    validator = if_range_value(live_etag, live_modified)
    if have:
        extra[:0] = ['--continue-at', '-']
        receipt['resumed_from'] = have
        if validator:
            extra[:0] = ['--header', f'If-Range: {validator}']       # (3) pin the transfer to the probe
            receipt['if_range'] = validator
        receipt['events'].append(f'resuming at byte {have:,}')
    else:
        receipt['events'].append('fetching the whole file')
    receipt['started'] = True
    log(f'  {"resuming" if have else "fetching"} {url}')
    try:
        completed = run_curl(curl, url, extra, timeout, retries, allow_http)
    except subprocess.TimeoutExpired:
        completed = None
        receipt['events'].append('curl timed out')
    written = partial.stat().st_size if partial.is_file() else 0
    receipt['received_bytes'] = written
    receipt['curl_exit'] = None if completed is None else completed.returncode
    if completed is not None:
        receipt['curl_stdout'] = (completed.stdout or '').strip()
        parts = receipt['curl_stdout'].split()
        if parts:
            receipt['http_code'] = parts[0]
            if len(parts) > 2:
                receipt['final_url'] = parts[2]
        if completed.stderr.strip():
            receipt['curl_stderr'] = completed.stderr.strip()
    text = headers.read_text(encoding='utf-8', errors='replace') if headers.is_file() else ''
    headers.unlink(missing_ok=True)
    status, fields = parse_final_headers(text)                      # (4) final block only
    receipt['transfer'] = {'status': status, 'http_code': receipt.get('http_code'),
                           'content_range': fields.get('content-range'),
                           'content_length': fields.get('content-length'),
                           'etag': fields.get('etag'), 'last_modified': fields.get('last-modified')}

    def roll_back(reason):
        """Restore the prefix exactly. A wrong-source or wrong-range body must never be kept."""
        try:
            with partial.open('r+b') as handle:
                handle.truncate(have)
        except OSError as error:
            receipt['rollback_failed'] = str(error)
        receipt['outcome'] = 'failed'
        receipt['error'] = f'rolled the .part back to {have:,} B: {reason}'
        receipt['rolled_back_to'] = have
        return receipt

    # (3) A resume must have been answered as the continuation it was pinned to.
    if have:
        if status != '206':
            return roll_back(f'the transfer answered {status or "no status"} instead of 206'
                             + (' (the source changed between the probe and the transfer)'
                                if status == '200' else ''))
        try:
            start, end, total = content_range(fields.get('content-range'), expected_bytes)
        except ValueError as error:
            return roll_back(f'the transfer sent no usable Content-Range: {error}')
        if start != have or end != expected_bytes - 1:
            return roll_back(f'the transfer Content-Range {start}-{end} is not the asked '
                             f'{have}-{expected_bytes - 1}')
        if live_etag and fields.get('etag') and fields['etag'] != live_etag:
            return roll_back(f'the transfer etag {fields["etag"]!r} differs from the probed {live_etag!r}')
        if not live_etag and live_modified and fields.get('last-modified') != live_modified:
            return roll_back('the transfer Last-Modified differs from the probed source')
    if completed is None or completed.returncode != 0:
        receipt['outcome'] = 'failed'
        receipt['error'] = (f'curl exited {receipt["curl_exit"]}; {written:,} B on disk'
                            + (f' ({written - have:,} B gained)' if written != have else ''))
        return receipt
    if written != expected_bytes:
        receipt['outcome'] = 'failed'
        receipt['error'] = f'size mismatch: {written:,} B on disk, expected {expected_bytes:,} B'
        return receipt
    log('  hashing the completed download')
    actual_md5 = digest(partial, 'md5')
    receipt['md5'] = actual_md5
    receipt['sha256'] = digest(partial, 'sha256')
    if actual_md5 != expected_md5:
        receipt['outcome'] = 'failed'
        receipt['error'] = (f'md5 mismatch: got {actual_md5}, expected {expected_md5}; '
                            'the .part is kept as evidence')
        return receipt
    os.replace(partial, destination)
    meta_file.unlink(missing_ok=True)
    receipt['outcome'] = 'ok'
    receipt['bytes'] = destination.stat().st_size
    return receipt


def write_receipt(receipt, path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(receipt, indent=1) + '\n', encoding='utf-8')
    return path


def plan(url, expected_bytes, expected_md5, destination, reserve):
    partial = siblings(destination)['partial']
    have = partial.stat().st_size if partial.is_file() else 0
    if have > expected_bytes:
        have = 0
    free = free_bytes(destination.parent)
    return {'url': url, 'destination': str(destination), 'expected_bytes': expected_bytes,
            'expected_md5': expected_md5, 'partial_bytes': have,
            'remaining_bytes': expected_bytes - have, 'reserve_bytes': reserve,
            'partial_is_complete': have == expected_bytes and have > 0,
            'free_bytes': free, 'resume_needs_bytes': (expected_bytes - have) + reserve,
            'restart_needs_bytes': expected_bytes + reserve,
            'resume_room': free >= (expected_bytes - have) + reserve,
            'restart_room': free >= expected_bytes + reserve,
            'final_exists': destination.is_file(),
            'final_matches': (destination.is_file() and destination.stat().st_size == expected_bytes
                              and digest(destination, 'md5') == expected_md5)
                             if destination.is_file() else False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--url', required=True, help='the pinned HTTPS source (loopback http for tests)')
    parser.add_argument('--bytes', required=True, type=int, help='expected size in bytes')
    parser.add_argument('--md5', required=True, help="expected md5 (from the source's sidecar manifest)")
    parser.add_argument('--dir', default='.', help='directory to fetch into')
    parser.add_argument('--name', default=None, help='file name (default: the url basename)')
    parser.add_argument('--reserve', type=int, default=DEFAULT_RESERVE,
                        help='free space to leave on the volume, on top of the download')
    parser.add_argument('--timeout', type=int, default=60, help='minutes allowed for the transfer')
    parser.add_argument('--retries', type=int, default=5, help='curl --retry count for transient failures')
    parser.add_argument('--receipt', default=None, help='receipt path (default: <name>.fetch.json)')
    parser.add_argument('--restart', action='store_true',
                        help='discard an existing .part deliberately (changed source, or no range support)')
    parser.add_argument('--fetch', action='store_true',
                        help='actually download; without it the tool only plans and makes no request')
    args = parser.parse_args(argv)

    try:
        url = check_url(args.url)
        expected_md5 = check_md5(args.md5)
        if args.bytes <= 0:
            raise Refused('--bytes must be positive')
        if args.reserve < 0:
            raise Refused('--reserve must not be negative')
        directory = Path(args.dir)
        directory.mkdir(parents=True, exist_ok=True)
        name = check_name(args.name or Path(urllib.parse.urlparse(url).path).name)
        destination = directory / name
        receipt_path = (Path(args.receipt) if args.receipt
                        else destination.with_name(destination.name + '.fetch.json'))
        check_distinct({'destination': destination, **siblings(destination),
                        'receipt': receipt_path})
        curl_path()
        outlook = plan(url, args.bytes, expected_md5, destination, args.reserve)
    except Refused as refused:
        print(f'refused: {refused}', file=sys.stderr)
        print('nothing was fetched or written', file=sys.stderr)
        return 2

    print(f'source      {url}')
    print(f'expected    {args.bytes:,} B, md5 {expected_md5}')
    print(f'destination {destination}')
    print(f'partial     {outlook["partial_bytes"]:,} B held'
          + (' (complete: it will be verified and promoted without a request)'
             if outlook['partial_is_complete'] else
             f' ({outlook["remaining_bytes"]:,} B remaining)' if outlook['partial_bytes'] else ''))
    print(f'room        resume needs {outlook["resume_needs_bytes"]:,} B, restart needs '
          f'{outlook["restart_needs_bytes"]:,} B, free {outlook["free_bytes"]:,} B -> '
          f'{"ok" if outlook["resume_room"] else "NOT ENOUGH"}')
    if outlook['final_exists']:
        print(f'note        the file already exists and '
              f'{"matches" if outlook["final_matches"] else "does NOT match"} the expected size and md5')

    if not args.fetch:
        write_receipt(dict(outlook, mode='plan', outcome='plan', started=False,
                           note='plan only: no network request was made'), receipt_path)
        print(f'\nplan only - pass --fetch to download. receipt: {receipt_path}')
        return 0
    if outlook['final_exists'] and outlook['final_matches']:
        write_receipt(dict(outlook, mode='fetch', outcome='ok', started=False,
                           note='already present and verified; nothing fetched'), receipt_path)
        print(f'\nalready present and verified; nothing fetched. receipt: {receipt_path}')
        return 0

    try:
        receipt = fetch(url, args.bytes, expected_md5, destination, args.reserve, args.timeout,
                        restart=args.restart, retries=args.retries)
    except Refused as refused:
        print(f'refused: {refused}', file=sys.stderr)
        write_receipt({'url': url, 'destination': str(destination), 'expected_bytes': args.bytes,
                       'expected_md5': expected_md5, 'mode': 'fetch', 'outcome': 'refused',
                       'started': False, 'error': str(refused),
                       'validator_metadata_kept': siblings(destination)['meta'].is_file(),
                       'note': 'refused before any write: no data was fetched'},
                      receipt_path)
        print(f'receipt: {receipt_path} (nothing started)', file=sys.stderr)
        return 2
    receipt['mode'] = 'fetch'
    receipt.setdefault('finished_at', time.strftime('%Y-%m-%dT%H:%M:%S'))
    write_receipt(receipt, receipt_path)
    for event in receipt['events']:
        print(f'  {event}')
    if receipt['outcome'] == 'ok':
        note = ' (promoted without a request)' if receipt.get('promoted_without_request') else ''
        print(f'\nok  {destination}  {receipt["bytes"]:,} B  md5 {receipt["md5"]}  '
              f'sha256 {receipt["sha256"]}{note}')
        print(f'receipt: {receipt_path}')
        return 0
    print(f'\nFAILED: {receipt.get("error")}', file=sys.stderr)
    if receipt.get('received_bytes'):
        print(f'data was written: {receipt["received_bytes"]:,} B in '
              f'{siblings(destination)["partial"]} (kept as evidence)', file=sys.stderr)
    print(f'receipt: {receipt_path}', file=sys.stderr)
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
