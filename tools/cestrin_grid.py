"""Grid-aware table reader for the observed CESTRIN Word-HTML exports.

Word emits position-independent markup: merged cells are expressed with colspan/rowspan
and blank spacer rows precede the real header. A flat per-<tr> cell list therefore loses
which physical column a value belongs to. This reader expands every row into a fixed
column grid using the merge attributes, so callers can bind a header column by index.

Deliberately strict: overlapping cells, ragged rows and missing merge attributes fail
visibly instead of being silently repaired.
"""
from html.parser import HTMLParser


class GridRows(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.rows = []
        self.cells = None        # list of (colspan, rowspan, text parts) for the open row
        self.cell = None
        self.span = (1, 1)
        self.table_count = 0

    # -- markup ---------------------------------------------------------
    def handle_starttag(self, tag, attrs):
        if tag == 'table':
            self.table_count += 1
            if self.table_count > 1:
                raise ValueError('Expected one source table; multiple or nested tables are unsupported')
        elif tag == 'tr':
            if self.cells is not None:
                raise ValueError('Nested table rows are not supported')
            self.cells = []
        elif tag in ('td', 'th') and self.cells is not None:
            if self.cell is not None:
                raise ValueError('Overlapping table cells')
            attrs = dict(attrs)
            try:
                self.span = (int(attrs.get('colspan', 1)), int(attrs.get('rowspan', 1)))
            except (TypeError, ValueError):
                raise ValueError('Non-numeric merge attribute')
            if min(self.span) < 1 or self.span[0] > 256:
                raise ValueError('Invalid merge attribute range')
            self.cell = []
        elif tag in ('br', 'p') and self.cell is not None:
            self.cell.append(' ')

    def handle_data(self, data):
        if self.cell is not None:
            self.cell.append(data)

    def handle_endtag(self, tag):
        if tag in ('td', 'th') and self.cell is not None:
            self.cells.append((self.span[0], self.span[1], ' '.join(''.join(self.cell).split())))
            self.cell = None
        elif tag == 'tr' and self.cells is not None:
            if self.cell is not None:
                raise ValueError('Unclosed table cell')
            self.rows.append(self.cells)
            self.cells = None

    def close(self):
        super().close()
        if self.cells is not None or self.cell is not None:
            raise ValueError('Truncated table row or cell')


def grid(rows, width=None):
    """Expand raw rows into a rectangular text grid. Returns (matrix, width)."""
    matrix, pending = [], {}
    for index, row in enumerate(rows):
        cells = {column: value[0] for column, value in pending.items()}
        pending = {column: (text, left - 1) for column, (text, left) in pending.items() if left > 1}
        column = 0
        for colspan, rowspan, text in row:
            if colspan < 1 or rowspan < 1 or colspan > 256 or index + rowspan > len(rows):
                raise ValueError('Invalid merge extent')
            while column in cells:
                column += 1
            if column + colspan > 256 or any(column + offset in cells for offset in range(colspan)):
                raise ValueError('Overlapping or oversized table cells')
            for offset in range(colspan):
                cells[column + offset] = text
                if rowspan > 1:
                    pending[column + offset] = (text, rowspan - 1)
            column += colspan
        width = max(width or 0, max(cells) + 1 if cells else 0)
        matrix.append(cells)
    width = width or max((max(r) + 1 for r in matrix if r), default=0)
    if any(any(cells.values()) and set(cells) != set(range(width)) for cells in matrix):
        raise ValueError('Ragged nonempty table row')
    return [{c: cells.get(c, '') for c in range(width)} for cells in matrix], width
