#!/usr/bin/env python3
"""A reported ETA must be at least the lower bound implied by the route's own speeds.

NETWORK CORRECTION (Codex, accepted): equality is wrong. Turns, waits, traffic controls, ferries,
border stops and restriction-avoidance detours all ADD real time, so a correct ETA is normally a
little ABOVE the bound. What can never happen is an ETA BELOW it: that is a number the truck cannot
physically achieve on the road it was given.

So the invariant is one-sided:

    reported_time  >=  sum(3600 * length_km / max_permitted_speed_kmh)   [lower bound]
    and reported_time is compared for EQUALITY only against the engine's own per-edge time model,
    which is a different statement entirely (see ../README.md, 'cost is not ETA').

THE DEFECT THIS STILL CATCHES (measured, Galati -> Nadlac, 758 km): reported time was physical time
MULTIPLIED BY class factors below 1.0 (0.95 motorway, 0.80 trunk, 0.70 secondary), giving 478.5 min
against a physical 700.4 min - a 41 t articulated truck 30% faster than physics. Four separate
shipped generations were impossible in this way before the arithmetic was reconciled.

Standard library only.
"""
from __future__ import annotations

import sys
import math

# a plausible truck route: list of (length_m, max permitted speed on that way, km/h)
ROUTE = [(18000, 85), (40000, 80), (30000, 60), (12000, 45)]

# the class factors that caused the defect, as they were applied to the reported time
BAD_FACTORS = [0.95, 0.80, 0.70]


def lower_bound_minutes(legs) -> float:
    """Fastest physically possible time on this route: the floor, not the expectation."""
    if any(not math.isfinite(m) or m < 0 or not math.isfinite(v) or v <= 0 for m, v in legs):
        raise ValueError("Route lengths must be finite and non-negative; speeds must be finite and positive")
    return sum(3600.0 * (m / 1000.0) / v for m, v in legs) / 60.0


def assert_eta_at_least_bound(reported_min: float, legs, tol_min: float = 0.5) -> None:
    """Portable one-sided invariant. Call with any engine's own (route, reported time)."""
    bound = lower_bound_minutes(legs)
    if not math.isfinite(reported_min) or reported_min < 0 or not math.isfinite(tol_min) or tol_min < 0:
        raise ValueError("ETA and tolerance must be finite and non-negative")
    if reported_min < bound - tol_min:
        raise AssertionError(
            f"reported {reported_min:.1f} min is BELOW the {bound:.1f} min physical floor "
            f"({(reported_min / bound - 1) * 100:+.1f}%) - the truck cannot achieve this on this route")


def main() -> int:
    bound = lower_bound_minutes(ROUTE)
    print(f"physical floor for this route: {bound:.1f} min over {sum(m for m, _ in ROUTE) / 1000:.0f} km")
    print("  a slower-but-correct ETA (turns, waits, ferry) is accepted:")
    try:
        assert_eta_at_least_bound(bound * 1.08, ROUTE)
        print(f"    PASS - {bound * 1.08:.1f} min (+8% for turns and waits) is fine")
    except AssertionError as exc:
        print(f"    FAIL - a legal +8% ETA was rejected: {exc}")
        return 1

    bad = sum(3600.0 * (m / 1000.0) / v * BAD_FACTORS[i % 3]
              for i, (m, v) in enumerate(ROUTE)) / 60.0
    print(f"  the historical sub-1.0 factors yield {bad:.1f} min ({(bad / bound - 1) * 100:+.1f}%)")
    try:
        assert_eta_at_least_bound(bad, ROUTE)
    except AssertionError as exc:
        print(f"    PASS - rejected: {exc}")
        return 0
    print("    FAIL - a sub-physical ETA was accepted")
    return 1


if __name__ == "__main__":
    sys.exit(main())
