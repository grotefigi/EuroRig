import copy
import math
import unittest
from check_corridors import check
from benchmarks.hermes.regressions.eta_is_physical import assert_eta_at_least_bound


class CorridorTests(unittest.TestCase):
    def result(self):
        return {"input": {"corridors": [{"id": "road", "expected": "route"}, {"id": "gap", "expected": "no_route"}],
                          "modes": ["DEFAULT", "SHORTEST"], "truck": {"top_speed": 80, "avoid_ferries": True}},
                "results": [{"id": name, "mode": mode, "status": "no_route", "error": "No nearby road"} if name == "gap"
                            else {"id": name, "mode": mode, "status": "route", "km": 80 if mode == "DEFAULT" else 79,
                                  "min": 65, "shape_points": 10, "origin_snap_metres": 20, "destination_snap_metres": 30,
                                  "snapped_origin": [45, 27], "snapped_destination": [46, 28]}
                            for name in ("road", "gap") for mode in ("DEFAULT", "SHORTEST")]}

    def test_actual_outcomes_and_mutations(self):
        self.assertIn("2 native routes", check(self.result()))
        for field, value in (("min", 10), ("min", math.nan), ("origin_snap_metres", 300), ("km", 81),
                             ("snapped_origin", [math.nan, 27]), ("status", "no_route")):
            bad = copy.deepcopy(self.result())
            bad["results"][1][field] = value
            with self.assertRaises((AssertionError, ValueError), msg=field):
                check(bad)
        bad = self.result()
        bad["results"].pop()
        with self.assertRaises(AssertionError):
            check(bad)

    def test_eta_bound_preserves_real_delays_and_rejects_bad_values(self):
        assert_eta_at_least_bound(70, [(80000, 80)])
        with self.assertRaises(AssertionError):
            assert_eta_at_least_bound(50, [(80000, 80)])
        for eta, legs in ((math.nan, [(1, 80)]), (math.inf, [(1, 80)]), (1, [(1, 0)]), (1, [(-1, 80)])):
            with self.assertRaises(ValueError):
                assert_eta_at_least_bound(eta, legs)
