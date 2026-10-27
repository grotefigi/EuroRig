import unittest
from cestrin_grid import GridRows, grid


class GridSafetyTests(unittest.TestCase):
    def test_colspan_cannot_overwrite_a_carried_rowspan(self):
        with self.assertRaisesRegex(ValueError, 'Overlapping'):
            grid([[(1, 1, 'left'), (1, 2, 'carried')], [(2, 1, 'overlap')]])

    def test_regular_rowspan_keeps_its_column(self):
        rows, width = grid([[(1, 1, 'left'), (1, 2, 'carried')], [(1, 1, 'next')]])
        self.assertEqual(2, width)
        self.assertEqual([{0: 'left', 1: 'carried'}, {0: 'next', 1: 'carried'}], rows)

    def test_nonpositive_and_oversized_spans_are_rejected(self):
        for span in ('0', '-1', '257', 'bad'):
            with self.subTest(span=span), self.assertRaises(ValueError):
                parser = GridRows()
                parser.feed('<tr><td colspan="' + span + '">value</td></tr>')
                parser.close()

    def test_rowspan_cannot_extend_past_the_input(self):
        with self.assertRaises(ValueError):
            grid([[(1, 2, 'missing row')]])

    def test_nonempty_ragged_rows_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Ragged'):
            grid([[(2, 1, 'header')], [(1, 1, 'value')]])

    def test_truncated_rows_and_cells_are_rejected(self):
        for text in ('<tr><td>value', '<tr><td>value</td>'):
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, 'Truncated'):
                parser = GridRows()
                parser.feed(text)
                parser.close()

    def test_separate_tables_cannot_share_measurement_headers(self):
        with self.assertRaisesRegex(ValueError, 'one source table'):
            parser = GridRows()
            parser.feed('<table><tr><td>header</td></tr></table><table><tr><td>foreign value</td></tr></table>')
