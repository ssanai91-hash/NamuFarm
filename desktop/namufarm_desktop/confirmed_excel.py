"""App-managed confirmed quote workbook, with a readable item register and lossless data.

Only this PC's acknowledged quotes belong here. Reading never contacts the server.
The hidden data sheet preserves costs, addresses and document settings across restarts.
"""
import json
import os
import re
import tempfile
from datetime import datetime
from pathlib import Path
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape
from zipfile import BadZipFile, ZipFile, ZIP_DEFLATED

from .commerce import draft_amounts, validate_draft
from .domain import SEOUL, require
from .errors import UserError
from .request_excel import _column

NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL = "http://schemas.openxmlformats.org/package/2006/relationships"
OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
STATES = ("견적확정", "견적발송완료", "입금확인")


def validate_quotes(quotes):
    require(isinstance(quotes, list) and len(quotes) <= 10000, "확정 견적 엑셀 자료를 확인해주세요.")
    ids, sources = set(), set()
    for draft in quotes:
        validate_draft(draft)
        require(draft.get("quoteStatus") in STATES, "확정 견적 상태를 확인해주세요.")
        require(draft["id"] not in ids, "확정 견적 식별자가 중복되었습니다.")
        ids.add(draft["id"])
        source = draft.get("sourceRequestId", "")
        require(not source or source not in sources, "같은 요청의 확정 견적이 중복되었습니다.")
        if source:
            sources.add(source)
    return quotes


def _sheet(rows, columns):
    def cell(value, position):
        if type(value) is int:
            return f'<c r="{position}"><v>{value}</v></c>'
        text = escape(re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", str(value)))
        return f'<c r="{position}" t="inlineStr"><is><t xml:space="preserve">{text}</t></is></c>'
    data = "".join(f'<row r="{n}">' + "".join(cell(v, f"{_column(i)}{n}") for i, v in enumerate(row, 1)) + '</row>'
                   for n, row in enumerate(rows, 1))
    return (f'<worksheet xmlns="{NS}"><sheetViews><sheetView workbookViewId="0">'
            '<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>'
            f'</sheetView></sheetViews><cols><col min="1" max="{columns}" width="22" customWidth="1"/>'
            f'</cols><sheetData>{data}</sheetData><autoFilter ref="A1:{_column(columns)}{len(rows)}"/></worksheet>')


def write_confirmed_quotes(path, quotes):
    """Atomic replacement: an open/locked workbook leaves the old file intact."""
    validate_quotes(quotes)
    path = Path(path)
    rows = [["견적접수번호", "상태", "작성시각(한국)", "고객명", "전화번호", "이메일", "묘목", "규격", "수량",
             "판매단가", "품목금액", "배송비", "선불여부", "세액", "견적총액", "요청사항", "견적ID", "요청ID"]]
    for d in quotes:
        created = datetime.fromtimestamp(d["createdAt"] / 1000, SEOUL).strftime("%Y-%m-%d %H:%M:%S")
        for item in d["items"]:
            rows.append([d.get("requestReceiptNumber") or d["id"], d["quoteStatus"], created,
                         d["customerName"], d["customerPhone"], d.get("customerEmail", ""), item["species"],
                         item.get("specification", ""), item["quantity"], item["unitPrice"],
                         item["quantity"] * item["unitPrice"], d["shippingFee"], "선불" if d["isPrepaid"] else "착불",
                         d["taxAmount"], draft_amounts(d)["total"], d.get("note", ""), d["id"], d["sourceRequestId"]])
    # Excel cells are limited to 32,767 characters; never truncate a large quote.
    raw = json.dumps({"version": 1, "quotes": quotes}, ensure_ascii=True, separators=(",", ":"))
    payload = [["앱 보관자료 v1 · 직접 수정하지 마세요"]] + [[raw[i:i + 30000]] for i in range(0, len(raw), 30000)]
    descriptor, temporary = tempfile.mkstemp(prefix=".confirmed-quotes-", suffix=".xlsx", dir=path.parent)
    os.close(descriptor)
    try:
        with ZipFile(temporary, "w", ZIP_DEFLATED) as book:
            book.writestr('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
            book.writestr('_rels/.rels', f'<Relationships xmlns="{REL}"><Relationship Id="rId1" Type="{OFFICE}/officeDocument" Target="xl/workbook.xml"/></Relationships>')
            book.writestr('xl/workbook.xml', f'<workbook xmlns="{NS}" xmlns:r="{OFFICE}"><sheets><sheet name="견적확정" sheetId="1" r:id="rId1"/><sheet name="앱보관자료" sheetId="2" state="hidden" r:id="rId2"/></sheets></workbook>')
            book.writestr('xl/_rels/workbook.xml.rels', f'<Relationships xmlns="{REL}"><Relationship Id="rId1" Type="{OFFICE}/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="{OFFICE}/worksheet" Target="worksheets/sheet2.xml"/></Relationships>')
            book.writestr('xl/worksheets/sheet1.xml', _sheet(rows, 18))
            book.writestr('xl/worksheets/sheet2.xml', _sheet(payload, 1))
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_confirmed_quotes(path):
    """Supports inline and shared strings (including an Excel open/save cycle)."""
    try:
        with ZipFile(path) as book:
            require(sum(f.file_size for f in book.infolist()) <= 100_000_000)
            workbook = ET.fromstring(book.read('xl/workbook.xml'))
            sheet = next(s for s in workbook.findall(f'{{{NS}}}sheets/{{{NS}}}sheet') if s.get('name') == '앱보관자료')
            links = ET.fromstring(book.read('xl/_rels/workbook.xml.rels'))
            target = next(r.get('Target') for r in links if r.get('Id') == sheet.get(f'{{{OFFICE}}}id'))
            require(target.startswith('worksheets/') or target.startswith('/xl/worksheets/'))
            target = target.lstrip('/') if target.startswith('/') else 'xl/' + target
            strings = []
            if 'xl/sharedStrings.xml' in book.namelist():
                root = ET.fromstring(book.read('xl/sharedStrings.xml'))
                strings = [''.join(e.itertext()) for e in root.findall(f'{{{NS}}}si')]
            root = ET.fromstring(book.read(target))
            chunks = []
            for row in root.findall(f'{{{NS}}}sheetData/{{{NS}}}row')[1:]:
                cells = row.findall(f'{{{NS}}}c')
                require(len(cells) == 1 and cells[0].find(f'{{{NS}}}f') is None)
                cell = cells[0]
                if cell.get('t') == 's':
                    chunks.append(strings[int(cell.findtext(f'{{{NS}}}v'))])
                else:
                    chunks.append(''.join(cell.find(f'{{{NS}}}is').itertext()))
            data = json.loads(''.join(chunks))
            require(type(data.get('version')) is int and data['version'] == 1)
            return validate_quotes(data['quotes'])
    except (OSError, BadZipFile, ET.ParseError, ValueError, KeyError, TypeError, AttributeError, IndexError, StopIteration, UserError):
        raise UserError("확정 견적 엑셀을 읽을 수 없습니다. 파일과 앱보관자료 시트를 확인해주세요. 기존 파일은 유지합니다.") from None
