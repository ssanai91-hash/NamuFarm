"""Portable sales backup: readable register plus a lossless, credential-free payload."""
import json
import os
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile, BadZipFile

from .confirmed_excel import NS, REL, OFFICE, _sheet
from .request_excel import write_sale_copy
from .domain import require
from .errors import UserError
from .commerce import sale_number


def write_sales(path, rows, operations, server_url, decoded):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    # Reuse the existing literal-text sales register, then atomically add restore data.
    import tempfile
    descriptor, name = tempfile.mkstemp(prefix=".sales-export-", suffix=".xlsx", dir=path.parent)
    os.close(descriptor)
    try:
        write_sale_copy(name, decoded)
        payload = {"kind": "namufarm-sales", "version": 1, "serverUrl": server_url,
                   "sales": rows, "operations": [op for op in operations if op["action"] == "save_sale"]}
        payload["soldLabels"] = {r["metadata"]["orderNumber"]: sale_number(r) for r in decoded
                                 if r["metadata"]["orderNumber"].startswith("PC_") and sale_number(r).startswith("SOLD-")}
        raw = json.dumps(payload, ensure_ascii=True, separators=(",", ":"), allow_nan=False)
        chunks = [["나무팜 판매관리 보관자료 v1 · 직접 수정하지 마세요"]]
        chunks += [[raw[i:i + 30000]] for i in range(0, len(raw), 30000)]
        with ZipFile(name, "a") as book:
            # Rebuild to avoid duplicate ZIP members that spreadsheet readers disagree about.
            parts = {info.filename: book.read(info.filename) for info in book.infolist()}
        parts['[Content_Types].xml'] = parts['[Content_Types].xml'].decode().replace('</Types>',
            '<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>').encode()
        parts['xl/workbook.xml'] = (f'<workbook xmlns="{NS}" xmlns:r="{OFFICE}"><sheets>'
            '<sheet name="판매관리" sheetId="1" r:id="rId1"/>'
            '<sheet name="앱보관자료" sheetId="2" state="hidden" r:id="rId2"/></sheets></workbook>').encode()
        parts['xl/_rels/workbook.xml.rels'] = (f'<Relationships xmlns="{REL}">'
            f'<Relationship Id="rId1" Type="{OFFICE}/worksheet" Target="worksheets/sheet1.xml"/>'
            f'<Relationship Id="rId2" Type="{OFFICE}/worksheet" Target="worksheets/sheet2.xml"/></Relationships>').encode()
        parts['xl/worksheets/sheet2.xml'] = _sheet(chunks, 1).encode()
        from zipfile import ZIP_DEFLATED
        with ZipFile(name, "w", ZIP_DEFLATED) as book:
            for member, data in parts.items():
                book.writestr(member, data)
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def read_sales(path):
    """Read the hidden restore sheet, including an Excel open/save cycle."""
    try:
        with ZipFile(path) as book:
            require(len(book.infolist()) <= 1000 and sum(i.file_size for i in book.infolist()) <= 100_000_000)
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
                chunks.append(strings[int(cell.findtext(f'{{{NS}}}v'))] if cell.get('t') == 's'
                              else ''.join(cell.find(f'{{{NS}}}is').itertext()))
            data = json.loads(''.join(chunks))
            require(isinstance(data, dict) and data.get('kind') == 'namufarm-sales'
                    and type(data.get('version')) is int and data['version'] == 1)
            require(isinstance(data.get('sales'), list) and len(data['sales']) <= 10000
                    and isinstance(data.get('operations'), list) and len(data['operations']) <= 100000)
            return data
    except (OSError, BadZipFile, ET.ParseError, ValueError, KeyError, TypeError, AttributeError, IndexError, StopIteration, UserError):
        raise UserError("판매관리 엑셀을 읽을 수 없습니다. 나피의 로컬 내보내기로 만든 파일을 선택해주세요.") from None
