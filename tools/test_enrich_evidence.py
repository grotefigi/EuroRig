"""The display summary must keep the tag that made a restriction UNCERTAIN.

compile_map flags trailer/minspeed/hgv:trailer/... as UNCERTAIN; the app can only keep that rule if the
stored evidence still names the reason. A narrower display filter drops it, the app sees only an unrelated
tag (oneway=yes) and clears the uncertain rule. These controls are deterministic and need no OSM build:
they exercise `summary()` directly, show the old filter losing the reason, and guard against the two
lists drifting apart again.
"""
import json
import unittest

from compile_map import EVIDENCE_PREFIXES, UNCERTAIN, BLOCKED, UNPAVED, restrictions
from enrich_display import summary

# The display filter as it was before the fix: it decided for itself which tags mattered.
OLD_DISPLAY_PREFIXES = ('max', 'access', 'hgv', 'vehicle', 'motor_vehicle', 'motorcar', 'hazmat',
                        'tunnel', 'barrier', 'oneway', 'bridge')

# (label, reason key, tags, kind) - each reason is a real UNCERTAIN cause from compile_map.restrictions.
REASONS = (
    ('way minspeed', 'minspeed', {'highway': 'residential', 'minspeed': '30', 'oneway': 'yes'},
     'residential'),
    ('way trailer', 'trailer', {'highway': 'secondary', 'trailer': 'no', 'oneway': 'yes'}, 'secondary'),
    ('way hgv:trailer', 'hgv:trailer', {'highway': 'primary', 'hgv:trailer': 'yes', 'oneway': 'yes'},
     'primary'),
    ('way maxweightrating', 'maxweightrating',
     {'highway': 'tertiary', 'maxweightrating': '7.5', 'oneway': 'yes'}, 'tertiary'),
    ('way maxaxles', 'maxaxles', {'highway': 'service', 'maxaxles': '3', 'oneway': 'yes'}, 'service'),
    ('way maxbogieweight', 'maxbogieweight',
     {'highway': 'unclassified', 'maxbogieweight': '10', 'oneway': 'yes'}, 'unclassified'),
    ('way conditional', 'minspeed:conditional',
     {'highway': 'residential', 'minspeed:conditional': '30 @ (22:00-06:00)', 'oneway': 'yes'},
     'residential'),
    # A node that also carries a supported physical limit, so the node is stored and the reason matters.
    ('node minspeed', 'minspeed', {'maxheight': '3.5', 'minspeed': '30', 'oneway': 'yes'}, None),
    ('node trailer', 'trailer', {'maxweight': '7.5', 'trailer': 'no', 'oneway': 'yes'}, None),
)


class EvidenceTests(unittest.TestCase):
    def stored(self, tags, kind=None):
        values = summary(tags, kind)
        return values[:5], values[5], json.loads(values[6])

    def test_the_reason_survives_alongside_an_unrelated_tag(self):
        """After-pass: the stored evidence still names why the restriction is uncertain."""
        for label, key, tags, kind in REASONS:
            with self.subTest(label):
                _, flags, kept = self.stored(tags, kind)
                self.assertTrue(flags & UNCERTAIN, f'{label}: expected UNCERTAIN')
                self.assertIn(key, kept, f'{label}: the reason {key!r} was dropped from the evidence')

    def test_the_old_filter_would_have_lost_the_reason(self):
        """Before-fail: with the narrower filter the reason is gone and only unrelated tags remain."""
        lost = {}
        for label, key, tags, kind in REASONS:
            with self.subTest(label):
                _, flags, kept = self.stored(tags, kind)
                before = {k: v for k, v in tags.items() if k.startswith(OLD_DISPLAY_PREFIXES)}
                self.assertIn(key, kept, 'after-pass: the new filter keeps the reason')
                if key not in before:
                    lost[label] = before
        self.assertTrue(lost, 'the control must show at least one reason the old filter dropped')
        for label, before in lost.items():
            with self.subTest(label + ' (old filter)'):
                self.assertTrue(before, f'{label}: the old filter left only unrelated tags')
        for label in ('way minspeed', 'way trailer', 'way conditional', 'node minspeed', 'node trailer'):
            self.assertIn(label, lost, f'{label} should be a reason the old filter dropped')

    def test_an_unrelated_tag_alone_does_not_flag_uncertain(self):
        """The reason, not oneway=yes, is what makes the rule uncertain."""
        for label, key, tags, kind in REASONS:
            with self.subTest(label):
                without = {k: v for k, v in tags.items() if k != key}
                _, flags, kept = self.stored(without, kind)
                self.assertFalse(flags & UNCERTAIN, f'{label}: {key!r} is not what flagged UNCERTAIN')
                self.assertNotIn(key, kept)

    def test_every_key_that_flags_uncertain_is_kept_by_the_display(self):
        """Drift guard: a new reason prefix in compile_map must also match EVIDENCE_PREFIXES."""
        reasons = ('maxheight:conditional', 'maxheight:unknownsuffix', 'maxwidth:lanes',
                   'maxweightrating', 'maxaxles', 'maxbogieweight', 'hgv:trailer', 'trailer',
                   'trailer:conditional', 'minspeed', 'minspeed:conditional', 'maxspeed:conditional',
                   'access:conditional', 'oneway:conditional', 'hazmat:conditional', 'toll:conditional',
                   'vehicle:conditional', 'motor_vehicle:conditional', 'motorcar:conditional')
        for key in reasons:
            with self.subTest(key):
                _, flags = restrictions({key: 'value'}, 'forward')
                self.assertTrue(flags & UNCERTAIN, f'{key} no longer flags UNCERTAIN')
                self.assertTrue(key.startswith(EVIDENCE_PREFIXES),
                                f'{key} flags UNCERTAIN but the display would drop it')

    def test_non_evidence_tags_are_not_stored(self):
        """The fix widens evidence, it does not dump every tag."""
        _, _, kept = self.stored({'highway': 'residential', 'name': 'Somewhere', 'surface': 'gravel',
                                  'minspeed': '30'}, 'residential')
        self.assertIn('minspeed', kept)
        for key in ('highway', 'name', 'surface'):
            self.assertNotIn(key, kept)

    def test_physical_limits_and_flags_are_unchanged(self):
        """The fix must not alter what a road restricts: limits, access, surface and unpaved stay."""
        limits, flags, kept = self.stored({'highway': 'primary', 'maxheight': '3.5', 'maxweight': '7.5',
                                           'access': 'no', 'surface': 'gravel'}, 'primary')
        self.assertEqual((limits[0], limits[3]), (3.5, 7.5))  # index 0 maxheight, 3 maxweight
        self.assertTrue(flags & BLOCKED)
        self.assertTrue(flags & UNPAVED)
        self.assertEqual((limits[1], limits[2], limits[4]), (0, 0, 0))
        self.assertEqual(kept, {'maxheight': '3.5', 'maxweight': '7.5', 'access': 'no'})

    def test_a_way_without_any_reason_is_unchanged(self):
        limits, flags, kept = self.stored({'highway': 'residential', 'oneway': 'yes'}, 'residential')
        self.assertEqual((limits, flags), ((0, 0, 0, 0, 0), 0))
        self.assertEqual(kept, {'oneway': 'yes'})


if __name__ == '__main__':
    unittest.main()
