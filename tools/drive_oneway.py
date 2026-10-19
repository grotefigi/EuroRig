"""Drive one route calculation on the fixture, proving both endpoints were set.

Flow measured on the device: the endpoints row opens the planner; tapping From:/To: opens an address
sheet; typing coordinates and tapping the coordinate row applies it and returns straight to the map.
So each endpoint is set from the map, and the map row is read back to prove the value took.
"""
import re
import subprocess
import sys
import time

SERIAL = 'R52X10ASRAF'


def sh(*a):
    return subprocess.run(['adb', '-s', SERIAL, *a], capture_output=True, text=True).stdout


def dump():
    sh('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
    return sh('shell', 'cat', '/sdcard/ui.xml')


def locate(xml, label, prefix=False, content_desc=False):
    attr = 'content-desc' if content_desc else 'text'
    body = r'%s[^"]*' % re.escape(label) if prefix else re.escape(label)
    m = re.search(r'%s="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % (attr, body), xml)
    if not m:
        return None
    a, b, c, d = map(int, m.groups())
    return (a + c) // 2, (b + d) // 2


def tap_at(p):
    sh('shell', 'input', 'tap', str(p[0]), str(p[1]))
    time.sleep(1.5)


def tap(label, prefix=False, content_desc=False):
    p = locate(dump(), label, prefix, content_desc)
    if p is None:
        print('MISSING control:', label)
        return False
    tap_at(p)
    return True


def reset_to_map():
    for _ in range(5):
        xml = dump()
        if locate(xml, 'Edit starting point and destination', content_desc=True) is not None:
            return True
        if locate(xml, 'OK') is not None:
            tap('OK'); continue
        if locate(xml, 'Cancel') is not None:
            tap('Cancel'); continue
        if locate(xml, 'CLOSE') is not None:
            tap('CLOSE'); continue
        sh('shell', 'input', 'keyevent', '4')
        time.sleep(1)
    return locate(dump(), 'Edit starting point and destination', content_desc=True) is not None


def endpoints():
    """{ 'A': '45.00100, 25.00100', 'B': ... } from the map's endpoints row."""
    xml = dump()
    m = re.search(r'text="A ([^&"]+)&#10;B ([^"]+)"', xml)
    if not m:
        return {}
    return {'A': m.group(1).strip(), 'B': m.group(2).strip()}


def set_endpoint(slot, lat, lon):
    want = '%s, %s' % (lat, lon)
    if not tap('Edit starting point and destination', content_desc=True):
        return False
    time.sleep(1)
    if locate(dump(), 'Route planner') is None:
        print('MISSING planner')
        return False
    if not tap('%s: ' % ('From' if slot == 'A' else 'To'), prefix=True):
        return False
    time.sleep(1)
    field = locate(dump(), 'Place, street or latitude, longitude')
    if field is None:
        print('MISSING search field')
        return False
    tap_at(field)
    sh('shell', 'input', 'text', '%s,%s' % (lat, lon))
    time.sleep(4)
    if not tap(want):
        print('no coordinate row for', want)
        return False
    time.sleep(2)
    got = endpoints().get(slot, '')
    if want not in got:
        print('READ-BACK MISMATCH', slot, 'got', repr(got), 'want', want)
        return False
    return True


def main():
    if len(sys.argv) < 5:
        print(__doc__)
        return 2
    flat, flon, tlat, tlon = sys.argv[1:5]
    expected = sys.argv[5].upper() if len(sys.argv) > 5 else ""
    if (flat, flon) == (tlat, tlon):
        print('ABORT: identical endpoints requested')
        return 1
    if not reset_to_map():
        print('ABORT: could not return to the map')
        return 1
    if not set_endpoint('A', flat, flon):
        print('ABORT: origin not set'); return 1
    if not set_endpoint('B', tlat, tlon):
        print('ABORT: destination not set'); return 1
    pairs = endpoints()
    # A scenario must assert the direction it intends to test, otherwise a run can silently repeat
    # the opposite direction and still look like a pass.
    heading = ('EAST' if float(tlon) > float(flon) else 'WEST') if flon != tlon else               ('NORTH' if float(tlat) > float(flat) else 'SOUTH')
    print('endpoints:', pairs, 'direction:', heading, 'expected:', expected or '(unchecked)')
    if expected and heading != expected:
        print('ABORT: direction', heading, 'does not match the expected', expected)
        return 1
    if pairs.get('A') == pairs.get('B'):
        print('ABORT: endpoints are identical'); return 1
    plan = locate(dump(), 'Plan route')
    if plan is None:
        print('ABORT: no Plan route control'); return 1
    sh('logcat', '-c')
    tap_at(plan)
    time.sleep(18)
    for line in sh('logcat', '-d').splitlines():
        if any(k in line for k in ('RouteRun', 'Audit', 'Direction', 'Snap', 'Route   ')):
            print(line)
    return 0


if __name__ == '__main__':
    sys.exit(main())
