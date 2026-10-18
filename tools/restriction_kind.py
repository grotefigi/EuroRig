"""Classify OSM restriction tags once, at build time, into one normalised record.

The router, the map overlay and the warnings all read this classification from the pack, so they
cannot disagree about whether a road is impossible, legally restricted, merely uncertain or just
undesirable. Nothing here asserts that an untagged road is safe.

Classes are a bitmask because one way can be several at once (a 3.5 t limit under a height sign):
    HARD        a numeric physical/legal limit that a vehicle either meets or exceeds
    ACCESS      a class-based prohibition that may carry a delivery/property exception
    UNCERTAIN   the tags exist but cannot be interpreted as a verified measurement
    PREFERENCE  unsuitable by policy, not physically impossible
"""
HARD, ACCESS, UNCERTAIN, PREFERENCE = 1, 2, 4, 8

NUMERIC = ("maxheight", "maxwidth", "maxlength", "maxweight", "maxaxleload", "maxbogie")
ACCESS_KEYS = ("access", "hgv", "vehicle", "motor_vehicle", "motorcar")
ACCESS_DENY = ("no", "private", "destination", "delivery", "agricultural", "forestry", "customers")
UNSUPPORTED = ("maxweightrating", "hgv:trailer", "trailer", "minspeed")
UNSUITABLE = ("track", "path", "footway", "cycleway", "pedestrian", "bridleway")
# An explicit "there is no limit here" is evidence of nothing, so it produces no class.
NO_VALUE = {"none", "no", "unsigned", "default", "unknown", ""}

_KMH = {"mph": 1.609344, "knots": 1.852}
_LEN = {"m": 1.0, "cm": 0.01, "ft": 0.3048, "in": 0.0254, "'": 0.3048, '"': 0.0254}
_MASS = {"t": 1.0, "kg": 0.001, "lbs": 0.00045359237, "st": 0.00635029}


def parse_measure(text, unit_table):
    """Return the value in SI (m or t) or None when the tag is not a plain measurement."""
    if text is None:
        return None
    value = str(text).strip().lower()
    if not value or value in ("none", "unsigned", "no", "default", "unknown"):
        return None
    number = ""
    while value and (value[0].isdigit() or value[0] == "."):
        number += value[0]
        value = value[1:]
    if not number:
        return None
    try:
        amount = float(number)
    except ValueError:
        return None
    unit = value.strip().rstrip(".")
    if not unit:
        return amount
    if unit in unit_table:
        return amount * unit_table[unit]
    return None


def classify(tags, highway=None):
    """Return (kind bitmask, evidence) for one element's tags."""
    kind = 0
    evidence = []
    for key, value in tags.items():
        if key in NUMERIC:
            if str(value).strip().lower() in NO_VALUE:
                continue
            unit_table = _MASS if key in ("maxweight", "maxaxleload", "maxbogie") else _LEN
            if parse_measure(value, unit_table) is None:
                kind |= UNCERTAIN
            else:
                kind |= HARD
            evidence.append(f"{key}={value}")
        elif key.endswith(":conditional") or key.startswith(UNSUPPORTED):
            # Present in the data, but not enforced: reported, never silently trusted.
            kind |= UNCERTAIN
            evidence.append(f"{key}={value}")
        elif key in ACCESS_KEYS and str(value).lower() in ACCESS_DENY:
            kind |= ACCESS
            evidence.append(f"{key}={value}")
    if highway is not None and highway in UNSUITABLE:
        kind |= PREFERENCE
        evidence.append(f"highway={highway}")
    return kind, ",".join(evidence)


def has(kind, flag):
    return bool(kind & flag)
