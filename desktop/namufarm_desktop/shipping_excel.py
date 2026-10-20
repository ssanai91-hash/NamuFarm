"""Logen BIFF8 handoff using the user's 24-column XLS layout (2026-10-08).

Only the schema is retained: no source recipients, sample sender, or sample fares.
The upload worksheet has no order-id column; a second worksheet links its rows
to NamuFarm orders for manual reconciliation after Logen has issued waybills.
"""
import io
import re
import struct

import xlrd
import xlwt

from .commerce import customer_note, shipping_problem, parse_tracking_records, sale_number
from .domain import require
from .errors import UserError

UPLOAD_SHEET = "제목없음(주소만있음)"
MATCH_SHEET = "주문대조표"
HEADERS = ("수화주명", "우편", "주소", "전화", "휴대폰", "택배수량", "운임", "선착불", "물품명", "", "배송메세지",
           "", "", "", "", "", "", "", "송하인명", "주소", "전화", "핸드폰번호", "", "제주선착불")
MATCH_HEADERS = ("주문번호", "송장번호", "출고자료행", "수령인", "전화", "박스수")
MAX_BYTES = 2 * 1024 * 1024


def check_excel_runtime():
    """Packaging smoke check; no file, user data, vault or network access."""
    book = xlwt.Workbook()
    book.add_sheet("Check").write(0, 0, "01234")
    data = io.BytesIO()
    book.save(data)
    with xlrd.open_workbook(file_contents=data.getvalue()) as check:
        require(check.biff_version == 80 and check.sheet_by_index(0).cell_value(0, 0) == "01234", "XLS 실행 환경 확인에 실패했습니다.")


def code(value, optional=False):
    require(isinstance(value, str) and ((optional and not value) or re.fullmatch(r"[0-9]{1,3}", value)),
            "로젠 설정에 맞는 선착불 코드를 숫자 1~3자리로 입력해주세요. 제주 코드는 미사용 시 비워두세요.")
    return value


def sender_settings(value):
    result = {}
    for key, title, limit in (("name", "발송인명", 100), ("address", "발송인 주소", 300),
                              ("phone", "발송인 전화", 20), ("mobile", "발송인 휴대폰", 20)):
        text = value.get(key, "")
        require(isinstance(text, str) and len(text) <= limit and not any(ord(c) < 32 for c in text), f"{title}을 확인해주세요.")
        result[key] = text.strip()
        require(key == "mobile" or result[key], f"{title}을 입력해주세요.")
        if key in ("phone", "mobile") and result[key]:
            require(re.fullmatch(r"0[0-9]{8,10}", re.sub(r"[- ]", "", result[key])), f"{title}을 확인해주세요.")
    return result


def export_orders(rows):
    require(1 <= len(rows) <= 500, "내보낼 주문을 1~500건 선택해주세요.")
    seen = set()
    for row in rows:
        order = row["private"]["order"]
        number = order["orderNumber"]
        require(number not in seen, "같은 주문을 두 번 내보낼 수 없습니다.")
        seen.add(number)
        require(sale_number(row).startswith("SOLD-"), "판매관리에서 서버로부터 가져오기로 SOLD 번호를 발급받아주세요.")
        require(order["status"] == "입금확인", "출고자료는 입금 확인된 주문만 내보낼 수 있습니다.")
        from .commerce import shipping_ready
        require(shipping_ready(order), f"{number}: 판매관리에서 배송준비완료를 먼저 눌러주세요.")
        problem = shipping_problem(order)
        require(not problem, f"{number}: {problem}")
    return rows


def export_tables(rows, parcels, sender):
    export_orders(rows)
    require(set(parcels) == {r["private"]["order"]["orderNumber"] for r in rows}, "선택 주문의 포장 정보를 다시 확인해주세요.")
    sender = sender_settings(sender)
    upload, matching = [list(HEADERS)], [list(MATCH_HEADERS)]
    for row in rows:
        order = row["private"]["order"]
        number, values = order["orderNumber"], [""] * len(HEADERS)
        parcel = parcels[number]
        boxes, freight = parcel.get("boxes"), parcel.get("freight")
        require(type(boxes) is int and 1 <= boxes <= 999, f"{number}: 박스 수를 1~999로 입력해주세요.")
        require(type(freight) is int and 0 <= freight <= 99999999, f"{number}: 계약 운임을 입력해주세요. 무료인 경우 0을 입력하세요.")
        fare, jeju = code(parcel.get("fareCode")), code(parcel.get("jejuCode", ""), True)
        # Recipient phone is the confirmed order contact; mobile only if applicable.
        phone = order["customerPhone"]
        goods = ", ".join(f"{i['species']} {i['quantity']}주" for i in row["metadata"]["items"])
        for index, value in {0: order["receiverName"], 1: order["zipNo"],
                2: " ".join(v.strip() for v in (order["roadAddress"], order.get("detailAddress", "")) if v.strip()),
                3: phone, 4: phone if phone.startswith("01") else "", 5: boxes, 6: freight, 7: fare,
                8: goods, 10: customer_note(order), 18: sender["name"], 19: sender["address"],
                20: sender["phone"], 21: sender["mobile"], 23: jeju}.items():
            values[index] = value
        upload.append(values)
        # Blank waybill prevents accidental reimport of an old number on re-export.
        matching.append([sale_number(row), "", len(upload), order["receiverName"], phone, boxes])
    return upload, matching


def shipping_xls(rows, parcels, sender):
    """Real Excel 97-2003 BIFF8 bytes. Text is never interpreted as a formula."""
    upload, matching = export_tables(rows, parcels, sender)
    book = xlwt.Workbook(encoding="utf-8")
    header = xlwt.easyxf("font: name Malgun Gothic, bold on; pattern: pattern solid, fore_colour light_yellow; alignment: wrap on, vert centre")
    text = xlwt.easyxf("font: name Malgun Gothic; alignment: wrap on, vert top", num_format_str="@")
    numeric = xlwt.easyxf("font: name Malgun Gothic; alignment: vert top", num_format_str="0")
    for name, records in ((UPLOAD_SHEET, upload), (MATCH_SHEET, matching)):
        sheet = book.add_sheet(name)
        sheet.panes_frozen, sheet.horz_split_pos = True, 1
        for index in range(len(records[0])):
            width = ({2: 60, 8: 36, 10: 36, 18: 22, 19: 50}.get(index, 16) if name == UPLOAD_SHEET
                     else (48 if index == 0 else 20))
            sheet.col(index).width = width * 256
        for y, record in enumerate(records):
            for x, value in enumerate(record):
                require(not isinstance(value, str) or len(value.encode("utf-16-le")) // 2 <= 32767, "품목이나 메모가 너무 깁니다.")
                sheet.write(y, x, value, header if y == 0 else numeric if type(value) is int else text)
        sheet.row(0).height_mismatch, sheet.row(0).height = True, 520
    output = io.BytesIO()
    book.save(output)
    return output.getvalue()


def parse_tracking_xls(raw, rows):
    require(len(raw) <= MAX_BYTES, "송장 파일은 2MB 이하로 나누어 가져오세요.")
    require(raw.startswith(bytes.fromhex("d0cf11e0a1b11ae1")), "Excel 97-2003 형식(.xls) 파일을 선택해주세요.")
    try:
        book = xlrd.open_workbook(file_contents=raw, on_demand=True)
        try:
            candidates = []
            for sheet in book.sheets():
                if not sheet.nrows:
                    continue
                headers = [str(v).strip() for v in sheet.row_values(0)]
                if {"주문번호", "송장번호"} <= set(headers):
                    require(headers.count("주문번호") == headers.count("송장번호") == 1, "송장 파일의 제목 열이 중복되었습니다.")
                    candidates.append((sheet, headers.index("주문번호"), headers.index("송장번호")))
            require(len(candidates) == 1, "주문번호·송장번호 열이 있는 시트를 하나만 남겨주세요. 내보낸 파일의 주문대조표를 사용할 수 있습니다.")
            sheet, order_col, tracking_col = candidates[0]
            require(sheet.nrows <= 501, "한 번에 최대 500건을 가져오세요.")
            records = []
            for y in range(1, sheet.nrows):
                record = {}
                for title, x in (("주문번호", order_col), ("송장번호", tracking_col)):
                    cell = sheet.cell(y, x)
                    require(cell.ctype in (xlrd.XL_CELL_EMPTY, xlrd.XL_CELL_BLANK, xlrd.XL_CELL_TEXT, xlrd.XL_CELL_NUMBER),
                            f"{y + 1}행: 주문번호·송장번호를 텍스트로 입력해주세요.")
                    value = cell.value
                    if cell.ctype == xlrd.XL_CELL_NUMBER:
                        require(value.is_integer() and 0 <= value <= 99999999999, f"{y + 1}행: 번호 형식을 확인해주세요.")
                        value = str(int(value))
                    record[title] = str(value)
                if any(record.values()):
                    records.append(record)
            return parse_tracking_records(records, rows)
        finally:
            book.release_resources()
    except UserError:
        raise
    except (xlrd.XLRDError, xlrd.compdoc.CompDocError, struct.error, ValueError, IndexError, KeyError, UnicodeError, OSError):
        raise UserError("송장 XLS 파일을 읽지 못했습니다. Excel 97-2003 형식으로 다시 저장해주세요.") from None

