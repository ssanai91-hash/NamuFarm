import copy
import io
import os
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch, Mock

import xlrd
import xlwt
from PySide6.QtWidgets import QDialog
from qt_support import QtTestCase

from namufarm_desktop.errors import UserError
from namufarm_desktop.shipping_excel import (HEADERS, UPLOAD_SHEET, MATCH_SHEET, shipping_xls,
    export_tables, parse_tracking_xls, export_orders)
from namufarm_desktop.workspace import ShippingExportDialog, SellerWindow
from test_workspace import row_for_test


SENDER = {"name": "테스트 발송인", "address": "테스트 출고지 1", "phone": "01012345678", "mobile": ""}


def parcels_for(rows):
    return {r["metadata"]["orderNumber"]: {"boxes": 2, "freight": 9000, "fareCode": "010", "jejuCode": ""} for r in rows}


def result_file(records):
    book = xlwt.Workbook()
    sheet = book.add_sheet(MATCH_SHEET)
    for y, row in enumerate([("주문번호", "송장번호"), *records]):
        for x, value in enumerate(row):
            sheet.write(y, x, value)
    out = io.BytesIO()
    book.save(out)
    return out.getvalue()


class ShippingExcelTest(unittest.TestCase):
    def test_real_biff8_exact_columns_blank_positions_and_literal_text(self):
        rows = [row_for_test(), row_for_test()]
        rows[0]["private"]["order"]["receiverName"] = '=HYPERLINK("test")'
        original = copy.deepcopy(rows)
        raw = shipping_xls(rows, parcels_for(rows), SENDER)
        self.assertEqual(bytes.fromhex("d0cf11e0a1b11ae1"), raw[:8])
        book = xlrd.open_workbook(file_contents=raw)
        self.assertEqual(80, book.biff_version)
        self.assertEqual([UPLOAD_SHEET, MATCH_SHEET], book.sheet_names())
        sheet = book.sheet_by_index(0)
        self.assertEqual(list(HEADERS), sheet.row_values(0))
        self.assertEqual((3, 24), (sheet.nrows, sheet.ncols))
        for x in (9, 11, 12, 13, 14, 15, 16, 17, 22, 23):
            self.assertEqual("", sheet.cell_value(1, x))
        for x, value in ((0, '=HYPERLINK("test")'), (1, '01234'), (3, '01012345678'), (7, '010')):
            self.assertEqual(xlrd.XL_CELL_TEXT, sheet.cell_type(1, x))
            self.assertEqual(value, sheet.cell_value(1, x))
        self.assertEqual(2, sheet.cell_value(1, 5))
        self.assertEqual(9000, sheet.cell_value(1, 6))
        self.assertEqual('서울 테스트로 1 100호', sheet.cell_value(1, 2))
        self.assertEqual(SENDER["name"], sheet.cell_value(1, 18))
        matching = book.sheet_by_name(MATCH_SHEET)
        self.assertEqual(rows[0]["metadata"]["orderNumber"], matching.cell_value(1, 0))
        self.assertEqual("", matching.cell_value(1, 1))
        self.assertEqual(2, matching.cell_value(1, 2))
        self.assertEqual(original, rows)  # Export never advances sale or tracking state.

    def test_boxes_are_independent_of_seedling_quantity_and_fares_not_inferred(self):
        row = row_for_test()
        row["metadata"]["totalQty"] = 100
        row["private"]["order"]["totalQty"] = 100
        parcels = parcels_for([row])
        records, _ = export_tables([row], parcels, SENDER)
        self.assertEqual(2, records[1][5])
        self.assertEqual(9000, records[1][6])
        self.assertEqual('010', records[1][7])
        for key, value in (("boxes", 0), ("boxes", True), ("freight", -1), ("freight", None), ("fareCode", ""), ("fareCode", "선불")):
            bad = copy.deepcopy(parcels)
            bad[row["metadata"]["orderNumber"]][key] = value
            with self.assertRaises(UserError):
                shipping_xls([row], bad, SENDER)

    def test_export_rejects_unpaid_incomplete_duplicate_or_unmatched_orders(self):
        for key, value in (("status", "구매확정"), ("status", "배송확인"), ("status", "판매완료"),
                           ("zipNo", ""), ("customerPhone", ""), ("roadAddress", "")):
            row = row_for_test()
            row["private"]["order"][key] = value
            with self.assertRaises(UserError):
                shipping_xls([row], parcels_for([row]), SENDER)
        row = row_for_test()
        for rows in ([], [row, row], [row] * 501):
            with self.assertRaises(UserError):
                export_orders(rows)
        with self.assertRaises(UserError):
            shipping_xls([row], {}, SENDER)
        with self.assertRaises(UserError):
            shipping_xls([row], parcels_for([row]), {})

    def test_tracking_xls_uses_order_ids_and_shared_all_or_nothing_validation(self):
        rows = [row_for_test(), row_for_test()]
        a, b = [r["metadata"]["orderNumber"] for r in rows]
        valid = [(a, "01234567890"), (b, "12345678901")]
        self.assertEqual(dict(valid), parse_tracking_xls(result_file(valid), rows))
        invalid = [[(a, "01234567890"), (b, "01234567890")], [(a, "01234567890"), (a, "12345678901")],
                   [(a, "01234567890"), ("unknown", "12345678901")], [(a, "")], [(a, 1234567890)], []]
        for records in invalid:
            with self.assertRaises(UserError):
                parse_tracking_xls(result_file(records), rows)
        with self.assertRaises(UserError):
            parse_tracking_xls(b"PK not an XLS", rows)
        with self.assertRaises(UserError):
            parse_tracking_xls(bytes.fromhex("d0cf11e0a1b11ae1") + b"\0" * 100, rows)
        with self.assertRaises(UserError):
            parse_tracking_xls(b"0" * (2 * 1024 * 1024 + 1), rows)


class ShippingDialogTest(QtTestCase):
    def test_review_requires_fare_and_code_and_preserves_box_count(self):
        rows = [row_for_test(), row_for_test()]
        dialog = ShippingExportDialog(rows, SENDER)
        dialog.show()
        self.app.processEvents()
        dialog.validate_export()
        self.assertNotEqual(QDialog.DialogCode.Accepted, dialog.result())
        self.assertTrue(dialog.error_label.text())
        dialog.parcel_inputs[0][0].setValue(3)
        dialog.common_freight.setValue(9000)
        dialog.common_code.setText("010")
        dialog.apply_common()
        self.assertEqual(3, dialog.collect()[1][rows[0]["metadata"]["orderNumber"]]["boxes"])
        self.assertLessEqual(dialog.parcel_table.geometry().right(), dialog.width())
        dialog.validate_export()
        self.assertEqual(QDialog.DialogCode.Accepted, dialog.result())
        self.assertEqual(3, xlrd.open_workbook(file_contents=dialog.xls_data).sheet_by_index(0).cell_value(1, 5))
        dialog.close()

    def test_file_saved_before_history_and_cancel_does_not_record(self):
        rows = [row_for_test()]
        view = {"requests": [], "sales": rows, "drafts": [], "pending": [], "paired": True}
        window = SellerWindow()
        window.manager = Mock()
        window.manager.request_notification_state.return_value = {'seenSequence': 0, 'notifiedSequence': 0}
        window.manager.shipping_export_preferences.return_value = {"sender": SENDER, "history": {}}
        def accept(dialog):
            dialog.common_freight.setValue(4500)
            dialog.common_code.setText("1")
            dialog.apply_common()
            dialog.validate_export()
            return QDialog.DialogCode.Accepted
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / '출고.xls'
            def remember(sender, parcels):
                self.assertTrue(path.read_bytes().startswith(bytes.fromhex('d0cf11e0a1b11ae1')))
            window.manager.remember_shipping_export.side_effect = remember
            with patch.object(ShippingExportDialog, 'exec', accept), patch('namufarm_desktop.workspace.QFileDialog.getSaveFileName', return_value=(str(path), '')):
                window.prepare_shipping_export(view, [rows[0]['metadata']['orderNumber']])
            window.manager.remember_shipping_export.assert_called_once()
            window.manager.remember_shipping_export.reset_mock()
            with patch.object(ShippingExportDialog, 'exec', accept), patch('namufarm_desktop.workspace.QFileDialog.getSaveFileName', return_value=('', '')):
                window.prepare_shipping_export(view, [rows[0]['metadata']['orderNumber']])
            window.manager.remember_shipping_export.assert_not_called()
        window.manager = None
        window.close()

