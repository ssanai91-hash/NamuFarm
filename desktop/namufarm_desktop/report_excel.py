"""Export the exact displayed sales totals, retaining numeric cells and filters."""
import os
import re
import tempfile
from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZipFile, ZIP_DEFLATED


def write_sales_report(path, report):
    rows = [
        ["나무팜 매출 집계"],
        ["조회 기간", report["period"]],
        ["집계 단위", report["unit"]],
        ["검색어", report["query"] or "전체"],
        ["집계 기준", "입금확인일 기준 · 선불 택배비 포함 / 후불 제외 · 취소 제외"],
        [], ["날짜 / 기간", "판매 건수", "판매금액 합계"],
        *report["rows"],
        ["합계", report["count"], report["amount"]],
    ]

    def cell(value, position, style):
        if type(value) is int:
            return f'<c r="{position}" s="{style}"><v>{value}</v></c>'
        text = escape(re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", str(value)))
        return f'<c r="{position}" s="{style}" t="inlineStr"><is><t xml:space="preserve">{text}</t></is></c>'

    xml_rows = []
    for number, values in enumerate(rows, 1):
        cells = []
        for column, value in enumerate(values):
            style = 1 if number in (1, 7, len(rows)) else 0
            if number > 7 and column in (1, 2):
                style = (4 if column == 1 else 5) if number == len(rows) else (2 if column == 1 else 3)
            cells.append(cell(value, f"{'ABC'[column]}{number}", style))
        xml_rows.append(f'<row r="{number}">' + "".join(cells) + '</row>')
    ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    rel = "http://schemas.openxmlformats.org/package/2006/relationships"
    office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    last_data_row = 7 + len(report["rows"])
    auto_filter = f'<autoFilter ref="A7:C{last_data_row}"/>' if report["rows"] else ''
    sheet = (f'<worksheet xmlns="{ns}"><sheetViews><sheetView workbookViewId="0">'
             '<pane ySplit="7" topLeftCell="A8" activePane="bottomLeft" state="frozen"/>'
             '</sheetView></sheetViews><cols><col min="1" max="1" width="42" customWidth="1"/>'
             '<col min="2" max="2" width="20" customWidth="1"/><col min="3" max="3" width="25" customWidth="1"/>'
             '</cols><sheetData>' + ''.join(xml_rows) + '</sheetData>' + auto_filter + '</worksheet>')
    styles = (f'<styleSheet xmlns="{ns}"><numFmts count="2">'
              '<numFmt numFmtId="164" formatCode="#,##0&quot;건&quot;"/>'
              '<numFmt numFmtId="165" formatCode="#,##0&quot;원&quot;"/></numFmts>'
              '<fonts count="2"><font><sz val="11"/><name val="맑은 고딕"/></font>'
              '<font><b/><sz val="11"/><name val="맑은 고딕"/></font></fonts>'
              '<fills count="3"><fill><patternFill patternType="none"/></fill>'
              '<fill><patternFill patternType="gray125"/></fill>'
              '<fill><patternFill patternType="solid"><fgColor rgb="FFEAF1EC"/><bgColor indexed="64"/></patternFill></fill></fills>'
              '<borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs>'
              '<cellXfs count="6"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
              '<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>'
              '<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
              '<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
              '<xf numFmtId="164" fontId="1" fillId="2" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1"/>'
              '<xf numFmtId="165" fontId="1" fillId="2" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1"/>'
              '</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>')
    path = Path(path)
    descriptor, temporary = tempfile.mkstemp(prefix=".sales-report-", suffix=".xlsx", dir=path.parent)
    os.close(descriptor)
    try:
        with ZipFile(temporary, "w", ZIP_DEFLATED) as book:
            book.writestr('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>')
            book.writestr('_rels/.rels', f'<Relationships xmlns="{rel}"><Relationship Id="rId1" Type="{office}/officeDocument" Target="xl/workbook.xml"/></Relationships>')
            book.writestr('xl/workbook.xml', f'<workbook xmlns="{ns}" xmlns:r="{office}"><sheets><sheet name="매출집계" sheetId="1" r:id="rId1"/></sheets></workbook>')
            book.writestr('xl/_rels/workbook.xml.rels', f'<Relationships xmlns="{rel}"><Relationship Id="rId1" Type="{office}/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="{office}/styles" Target="styles.xml"/></Relationships>')
            book.writestr('xl/styles.xml', styles)
            book.writestr('xl/worksheets/sheet1.xml', sheet)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return path.read_bytes()
