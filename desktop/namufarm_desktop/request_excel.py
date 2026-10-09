"""Replaceable local Excel copy. All customer strings are literal Excel text."""
import os
import re
import tempfile
from datetime import datetime
from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZipFile, ZIP_DEFLATED

from .domain import SEOUL


def _column(index):
    result = ""
    while index:
        index, remainder = divmod(index - 1, 26)
        result = chr(65 + remainder) + result
    return result


def write_request_copy(path, requests):
    """Atomic replacement leaves the previous workbook intact if Excel has it open."""
    path = Path(path)
    rows = [["견적접수번호", "접수시각(한국)", "성함", "연락처", "배송지역", "묘목", "규격", "수량",
             "요청사항", "이메일", "공식 견적서 요청", "견적서 수신인", "회신 방법"]]
    for row in requests:
        customer = row["personal"]
        created = datetime.fromisoformat(row["createdAt"].replace("Z", "+00:00")).astimezone(SEOUL).strftime("%Y-%m-%d %H:%M:%S")
        for item in customer["items"]:
            rows.append([row.get("receiptNumber") or row["requestId"], created, customer["customerName"],
                         customer["customerPhone"], customer["region"], item["species"], item["specification"], item["quantity"],
                         customer["message"], customer.get("customerEmail", ""),
                         "요청" if customer.get("formalQuoteRequested") else "", customer.get("recipientName", ""),
                         {"sms":"문자", "email":"이메일", "both":"문자·이메일"}.get(customer.get("replyChannel", "sms"), "")])
    def cell(value, position):
        if type(value) is int:
            return f'<c r="{position}"><v>{value}</v></c>'
        value = escape(re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", str(value)))
        return f'<c r="{position}" t="inlineStr"><is><t xml:space="preserve">{value}</t></is></c>'
    data = "".join(f'<row r="{n}">' + "".join(cell(v, f"{_column(i)}{n}") for i,v in enumerate(row,1)) + '</row>'
                   for n,row in enumerate(rows,1))
    ns = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
    rel = 'http://schemas.openxmlformats.org/package/2006/relationships'
    office = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
    fd, temporary = tempfile.mkstemp(prefix=".request-copy-", suffix=".xlsx", dir=path.parent)
    os.close(fd)
    try:
        with ZipFile(temporary, "w", ZIP_DEFLATED) as book:
            book.writestr('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
            book.writestr('_rels/.rels', f'<Relationships xmlns="{rel}"><Relationship Id="rId1" Type="{office}/officeDocument" Target="xl/workbook.xml"/></Relationships>')
            book.writestr('xl/workbook.xml', f'<workbook xmlns="{ns}" xmlns:r="{office}"><sheets><sheet name="견적요청" sheetId="1" r:id="rId1"/></sheets></workbook>')
            book.writestr('xl/_rels/workbook.xml.rels', f'<Relationships xmlns="{rel}"><Relationship Id="rId1" Type="{office}/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
            book.writestr('xl/worksheets/sheet1.xml', f'<worksheet xmlns="{ns}"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="13" width="22" customWidth="1"/></cols><sheetData>{data}</sheetData><autoFilter ref="A1:M{len(rows)}"/></worksheet>')
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
