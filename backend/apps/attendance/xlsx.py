"""
A dependency-free .xlsx writer for attendance exports.

The project ships no spreadsheet library, and a roster export is a single
sturdy sheet: a header row and one row per student. Rather than add a package
(and a supply-chain surface) for that, the workbook is assembled from the
handful of XML parts the format requires. Strings are written inline, so no
shared-string table is needed.

The output is a real .xlsx -- not a renamed CSV and not SpreadsheetML -- so
Excel, LibreOffice and Google Sheets all open it without a warning.
"""

import io
import zipfile
from xml.sax.saxutils import escape

_CONTENT_HEADER = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'

_CONTENT_TYPES = _CONTENT_HEADER + (
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/xl/workbook.xml" '
    'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
    '<Override PartName="/xl/worksheets/sheet1.xml" '
    'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
    '</Types>'
)

_ROOT_RELS = _CONTENT_HEADER + (
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" '
    'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
    'Target="xl/workbook.xml"/>'
    '</Relationships>'
)

_WORKBOOK_RELS = _CONTENT_HEADER + (
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" '
    'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
    'Target="worksheets/sheet1.xml"/>'
    '</Relationships>'
)


def _column_name(index: int) -> str:
    """0 -> A, 25 -> Z, 26 -> AA."""
    name = ""
    value = index + 1
    while value:
        value, remainder = divmod(value - 1, 26)
        name = chr(65 + remainder) + name
    return name


def _cell(reference: str, value) -> str:
    if value is None or value == "":
        return ""
    if isinstance(value, bool):
        # Excel reads <v>1</v> as TRUE for a boolean cell.
        return f'<c r="{reference}" t="b"><v>{1 if value else 0}</v></c>'
    if isinstance(value, (int, float)):
        return f'<c r="{reference}"><v>{value}</v></c>'
    return (
        f'<c r="{reference}" t="inlineStr"><is>'
        f'<t xml:space="preserve">{escape(str(value))}</t>'
        f'</is></c>'
    )


def _sheet_name(raw: str) -> str:
    """Excel rejects []:*?/\\ in sheet names and caps them at 31 characters."""
    cleaned = "".join(ch for ch in (raw or "Attendance") if ch not in "[]:*?/\\").strip()
    return (cleaned or "Attendance")[:31]


def build_workbook(headers, rows, sheet_name: str = "Attendance") -> bytes:
    """
    Build a one-sheet workbook.

    ``headers`` is a sequence of column titles and ``rows`` a sequence of
    sequences in the same order. Numbers stay numeric so Excel can sum and sort
    them; everything else is text.
    """
    widths = []
    for index, header in enumerate(headers):
        longest = len(str(header))
        for row in rows:
            if index < len(row) and row[index] is not None:
                longest = max(longest, len(str(row[index])))
        widths.append(
            f'<col min="{index + 1}" max="{index + 1}" width="{min(max(longest + 2, 10), 55)}" customWidth="1"/>'
        )

    sheet_rows = []
    all_rows = [list(headers)] + [list(row) for row in rows]
    for row_index, row in enumerate(all_rows, start=1):
        cells = "".join(
            _cell(f"{_column_name(col)}{row_index}", value)
            for col, value in enumerate(row)
        )
        sheet_rows.append(f'<row r="{row_index}">{cells}</row>')

    worksheet = _CONTENT_HEADER + (
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<sheetViews><sheetView workbookViewId="0">'
        '<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>'
        '</sheetView></sheetViews>'
        f'<cols>{"".join(widths)}</cols>'
        f'<sheetData>{"".join(sheet_rows)}</sheetData>'
        '</worksheet>'
    )

    workbook = _CONTENT_HEADER + (
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        f'<sheets><sheet name="{escape(_sheet_name(sheet_name))}" sheetId="1" r:id="rId1"/></sheets>'
        '</workbook>'
    )

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", _CONTENT_TYPES)
        archive.writestr("_rels/.rels", _ROOT_RELS)
        archive.writestr("xl/workbook.xml", workbook)
        archive.writestr("xl/_rels/workbook.xml.rels", _WORKBOOK_RELS)
        archive.writestr("xl/worksheets/sheet1.xml", worksheet)
    return buffer.getvalue()
