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
# Direction bits. A tag scoped to :forward applies to travel along the OSM way's node order,
# :backward against it. Neither bit set means the record applies to both directions. Direction
# bits are written by the pack, never inferred by the renderer, so the audit and the overlay
# cannot disagree about which way a limit faces.
FORWARD, BACKWARD = 16, 32
DIRECTION_SUFFIXES = ((":forward", FORWARD), (":backward", BACKWARD), (":both_ways", 0))
# One-way: the value names the permitted travel direction, so the prohibition faces the other.
# "yes"/"1" means traffic runs with node order, hence the record blocks backward travel.
ONEWAY_ALLOWED = {
    "yes": BACKWARD, "true": BACKWARD, "1": BACKWARD,
    "-1": FORWARD, "reverse": FORWARD,
}

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


def split_direction(key):
    """Return (base key, direction bit) for :forward/:backward/:both_ways scoped tags."""
    for suffix, bit in DIRECTION_SUFFIXES:
        if key.endswith(suffix):
            return key[: -len(suffix)], bit
    return key, 0


def one_way(tags):
    """True when the tags state a one-way restriction the audit can enforce."""
    for key, value in tags.items():
        if key == "oneway" or key.startswith("oneway:"):
            if str(value).strip().lower() in ONEWAY_ALLOWED:
                return True
    return False


def classify(tags, highway=None):
    """Return (kind bitmask, evidence) for one element's tags."""
    kind = 0
    evidence = []
    directions = set()
    undirected = False
    for key, value in tags.items():
        base, direction = split_direction(key)
        if base in NUMERIC:
            if str(value).strip().lower() in NO_VALUE:
                continue
            unit_table = _MASS if base in ("maxweight", "maxaxleload", "maxbogie") else _LEN
            if parse_measure(value, unit_table) is None:
                kind |= UNCERTAIN
            else:
                kind |= HARD
            evidence.append(f"{key}={value}")
        elif key.endswith(":conditional") or base.startswith(UNSUPPORTED):
            # Present in the data, but not enforced: reported, never silently trusted.
            kind |= UNCERTAIN
            evidence.append(f"{key}={value}")
        elif key == "oneway" or key.startswith("oneway:"):
            # The recorded value states which direction traffic may run; the prohibition therefore
            # faces the other one. BACKWARD bit = the way is one-way in node order.
            stated = str(value).strip().lower()
            if stated in ONEWAY_ALLOWED:
                kind |= HARD | ONEWAY_ALLOWED[stated]
                evidence.append(f"{key}={value}")
            elif stated not in ("no", "false", "0"):
                kind |= UNCERTAIN
                evidence.append(f"{key}={value}")
        elif base in ACCESS_KEYS and str(value).lower() in ACCESS_DENY:
            kind |= ACCESS
            evidence.append(f"{key}={value}")
        else:
            continue
        if direction:
            directions.add(direction)
        else:
            undirected = True
    # Direction bits are only written when every recorded limit on the way faces one single
    # direction. The numeric columns hold one scalar per dimension (the smallest of both
    # directions), so mixed evidence cannot be represented exactly; those records stay
    # conservative and apply both ways instead of silently waiving one of them.
    if directions and not undirected and len(directions) == 1:
        kind |= directions.pop()
    if highway is not None and highway in UNSUITABLE:
        kind |= PREFERENCE
        evidence.append(f"highway={highway}")
    return kind, ",".join(evidence)


def has(kind, flag):
    return bool(kind & flag)
