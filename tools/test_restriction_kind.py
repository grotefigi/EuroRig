import unittest

from restriction_kind import (ACCESS, BACKWARD, FORWARD, HARD, PREFERENCE, UNCERTAIN, classify, has,
                              one_way, parse_measure, split_direction)


class ClassificationTest(unittest.TestCase):
    def test_numeric_limit_is_hard(self):
        kind, evidence = classify({"maxweight": "3.5"}, "residential")
        self.assertTrue(has(kind, HARD))
        self.assertIn("maxweight=3.5", evidence)

    def test_unmapped_measure_is_uncertain_not_hard(self):
        kind, _ = classify({"maxheight": "signposted"}, "primary")
        self.assertTrue(has(kind, UNCERTAIN))
        self.assertFalse(has(kind, HARD))

    def test_missing_value_is_not_a_restriction(self):
        for value in ("none", "default", "", "no"):
            kind, _ = classify({"maxweight": value}, "primary")
            self.assertEqual(0, kind, value)

    def test_conditional_is_uncertain_never_enforced(self):
        kind, evidence = classify({"maxweight:conditional": "3.5 @ (22:00-06:00)"}, "primary")
        self.assertTrue(has(kind, UNCERTAIN))
        self.assertIn("maxweight:conditional", evidence)

    def test_legal_access_is_its_own_class(self):
        kind, _ = classify({"hgv": "destination"}, "residential")
        self.assertTrue(has(kind, ACCESS))
        self.assertFalse(has(kind, HARD))

    def test_unpaved_is_preference_not_impossibility(self):
        kind, _ = classify({}, "track")
        self.assertTrue(has(kind, PREFERENCE))
        self.assertFalse(has(kind, HARD))

    def test_classes_combine(self):
        kind, evidence = classify({"maxweight": "3.5", "hgv": "no"}, "service")
        self.assertTrue(has(kind, HARD) and has(kind, ACCESS))
        self.assertIn("hgv=no", evidence)

    def test_direction_scoped_limit_keeps_its_direction(self):
        # Real RO-MD case: Strada Rahovei carries maxweight:backward=3.5.
        kind, evidence = classify({"maxspeed": "RO:urban", "maxweight:backward": "3.5"}, "primary")
        self.assertTrue(has(kind, HARD))
        self.assertTrue(has(kind, BACKWARD))
        self.assertFalse(has(kind, FORWARD))
        self.assertIn("maxweight:backward=3.5", evidence)

    def test_forward_height_limit_is_directional(self):
        kind, _ = classify({"maxheight:forward": "3.5"}, "secondary")
        self.assertTrue(has(kind, HARD) and has(kind, FORWARD))
        self.assertFalse(has(kind, BACKWARD))

    def test_both_ways_suffix_is_not_directional(self):
        kind, _ = classify({"maxweight:both_ways": "7.5"}, "primary")
        self.assertTrue(has(kind, HARD))
        self.assertFalse(has(kind, FORWARD) or has(kind, BACKWARD))

    def test_directional_access_keeps_its_direction(self):
        kind, evidence = classify({"hgv:forward": "destination"}, "residential")
        self.assertTrue(has(kind, ACCESS) and has(kind, FORWARD))
        self.assertIn("hgv:forward=destination", evidence)

    def test_directional_unparsable_value_stays_uncertain(self):
        kind, _ = classify({"maxweight:backward": "signposted"}, "primary")
        self.assertTrue(has(kind, UNCERTAIN) and has(kind, BACKWARD))
        self.assertFalse(has(kind, HARD))

    def test_split_direction(self):
        self.assertEqual(("maxheight", FORWARD), split_direction("maxheight:forward"))
        self.assertEqual(("maxheight", BACKWARD), split_direction("maxheight:backward"))
        self.assertEqual(("maxheight", 0), split_direction("maxheight"))

    def test_mixed_direction_evidence_stays_both_ways(self):
        # Plain and directional limits on the same way collapse into one scalar per dimension,
        # so the record must not claim to face a single direction.
        kind, _ = classify({"maxweight": "7.5", "maxheight:forward": "3.5"}, "primary")
        self.assertTrue(has(kind, HARD))
        self.assertFalse(has(kind, FORWARD) or has(kind, BACKWARD))

    def test_opposing_directions_stay_both_ways(self):
        kind, _ = classify({"maxheight:forward": "3.5", "maxheight:backward": "4.5"}, "primary")
        self.assertTrue(has(kind, HARD))
        self.assertFalse(has(kind, FORWARD) or has(kind, BACKWARD))

    def test_units_are_normalised(self):
        self.assertAlmostEqual(3.5, parse_measure("3.5 t", {"t": 1.0}))
        self.assertAlmostEqual(3.5, parse_measure("3500 kg", {"kg": 0.001}))
        self.assertAlmostEqual(4.2, parse_measure("4.2m", {"m": 1.0}))
        self.assertIsNone(parse_measure("until 2027", {"t": 1.0}))

    def test_oneway_yes_blocks_backward_travel(self):
        kind, evidence = classify({"oneway": "yes"}, "residential")
        self.assertTrue(has(kind, HARD))
        self.assertTrue(has(kind, BACKWARD))
        self.assertFalse(has(kind, FORWARD))
        self.assertIn("oneway=yes", evidence)

    def test_oneway_minus_one_blocks_forward_travel(self):
        kind, _ = classify({"oneway": "-1"}, "residential")
        self.assertTrue(has(kind, HARD))
        self.assertTrue(has(kind, FORWARD))
        self.assertFalse(has(kind, BACKWARD))

    def test_oneway_no_is_not_a_prohibition(self):
        kind, _ = classify({"oneway": "no"}, "residential")
        self.assertFalse(has(kind, HARD))
        self.assertFalse(has(kind, FORWARD) or has(kind, BACKWARD))

    def test_one_way_helper_matches_the_enforced_values(self):
        self.assertTrue(one_way({"oneway": "yes"}))
        self.assertTrue(one_way({"oneway": "-1"}))
        self.assertFalse(one_way({"oneway": "no"}))
        self.assertFalse(one_way({"highway": "residential"}))

    def test_unknown_oneway_value_is_uncertain_not_enforced(self):
        kind, _ = classify({"oneway": "alternating"}, "residential")
        self.assertTrue(has(kind, UNCERTAIN))
        self.assertFalse(has(kind, HARD))


if __name__ == "__main__":
    unittest.main()
