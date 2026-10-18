import unittest

from restriction_kind import ACCESS, HARD, PREFERENCE, UNCERTAIN, classify, has, parse_measure


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

    def test_units_are_normalised(self):
        self.assertAlmostEqual(3.5, parse_measure("3.5 t", {"t": 1.0}))
        self.assertAlmostEqual(3.5, parse_measure("3500 kg", {"kg": 0.001}))
        self.assertAlmostEqual(4.2, parse_measure("4.2m", {"m": 1.0}))
        self.assertIsNone(parse_measure("until 2027", {"t": 1.0}))


if __name__ == "__main__":
    unittest.main()
