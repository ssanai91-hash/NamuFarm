"""Quotation workbench layout and price-entry checks with synthetic data only."""
import copy
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from unittest.mock import patch

from PySide6.QtCore import QPoint, Qt
from PySide6.QtWidgets import QDialog, QMessageBox
from namufarm_desktop.commerce import new_draft
from namufarm_desktop.ui_theme import workspace_style
from namufarm_desktop.workspace import QuoteDialog
from qt_support import QtTestCase


class QuoteWorkbenchTest(QtTestCase):
    def setUp(self):
        super().setUp()
        self.app.setProperty('namufarmLargeFont', False)
        self.app.setStyleSheet(workspace_style())
        self.draft = new_draft()
        self.draft.update(customerName='가상 고객', customerPhone='01000000000',
                          customerEmail='sample@example.com', requestRegion='옥천군',
                          sourceRequestId='00000000-0000-4000-8000-000000000003',
                          requestReceiptNumber='NAMU-20261017-0003',
                          formalQuoteRequested=True, quoteRecipientName='가상 수신인',
                          quoteReplyChannel='both', note='다음 주 배송 부탁드립니다.',
                          items=[dict(species=n, specification='', quantity=q, unitPrice=p, unitCost=0)
                                 for n, q, p in [('헛개나무', 10, 2000), ('계수나무', 3, 2000), ('팽나무', 7, 4000)]])

    def settle(self):
        for _ in range(8):
            self.app.processEvents()

    def dialog(self, status='신규', size=(1280, 900)):
        dialog = QuoteDialog(self.draft, request_status=status)
        dialog.resize(*size)
        dialog.show()
        self.settle()
        return dialog

    def test_selected_layout_has_no_seedling_icons_and_a_separate_total_rule(self):
        dialog = self.dialog()
        header = dialog.items.horizontalHeader()
        self.assertEqual([0, 1, 3, 2], [header.logicalIndex(i) for i in range(4)])
        self.assertEqual('54,000원', dialog.total.text())
        self.assertEqual(0, dialog.items.verticalScrollBar().maximum())
        self.assertEqual(0, dialog.items.horizontalScrollBar().maximum())
        self.assertAlmostEqual(.3, dialog.customer_scroll.width() / dialog.width(), delta=.02)
        rule_y = dialog.workbench_total_rule.mapTo(dialog, QPoint()).y()
        self.assertGreater(rule_y, dialog.items.mapTo(dialog, QPoint()).y() + dialog.items.height())
        self.assertLess(rule_y + dialog.workbench_total_rule.height(),
                        dialog.workbench_summary.mapTo(dialog, QPoint()).y())
        self.assertEqual(3, dialog.workbench_total_rule.height())
        for row in range(3):
            self.assertTrue(dialog.items.item(row, 0).icon().isNull())
            editor = dialog.unit_price_editor(row)
            self.assertEqual(editor.minus.height(), editor.input.height())
            self.assertEqual(editor.plus.height(), editor.input.height())

    def test_price_changes_keep_customer_metadata_and_original_draft(self):
        original = copy.deepcopy(self.draft)
        dialog = self.dialog()
        editor = dialog.unit_price_editor(0)
        editor.plus.click()
        self.assertEqual('64,000원', dialog.total.text())
        editor.input.setText('1250')
        self.assertEqual('46,500원', dialog.total.text())
        result = dialog.collect()
        self.assertEqual(1250, result['items'][0]['unitPrice'])
        self.assertEqual(original, self.draft)
        for key in ('customerName', 'customerPhone', 'customerEmail', 'quoteRecipientName', 'quoteReplyChannel', 'note'):
            self.assertEqual(original[key], result[key])
        self.assertTrue(dialog.note.isReadOnly())
        self.assertEqual('가상 수신인', dialog.customer_values['견적서 수신인'].text())
        self.assertEqual('문자 + 이메일', dialog.customer_values['수신 방법'].text())
        self.assertEqual(0, result['shippingFee'])
        self.assertFalse(result['isPrepaid'])

    def test_small_large_font_window_keeps_actions_and_price_controls_visible(self):
        self.app.setProperty('namufarmLargeFont', True)
        self.app.setStyleSheet(workspace_style(True))
        self.draft['items'] *= 7
        dialog = self.dialog(size=(1000, 620))
        self.assertGreater(dialog.items.verticalScrollBar().maximum(), 0)
        self.assertEqual(0, dialog.items.horizontalScrollBar().maximum())
        for action in (dialog.close_button, dialog.confirm_button):
            self.assertGreaterEqual(action.height(), 44)
            self.assertLess(action.mapTo(dialog, QPoint()).y() + action.height(), dialog.height())
        editor = dialog.unit_price_editor(0)
        self.assertLessEqual(editor.plus.geometry().right(), dialog.items.columnWidth(3))
        self.assertGreaterEqual(editor.input.width(), 100)

    def test_finalize_still_requires_confirmation_and_existing_status_gate(self):
        dialog = self.dialog()
        with patch('namufarm_desktop.workspace.QMessageBox.question', return_value=QMessageBox.StandardButton.No):
            dialog.confirm_button.click()
        self.assertEqual('', dialog.mode)
        with patch('namufarm_desktop.workspace.QMessageBox.question', return_value=QMessageBox.StandardButton.Yes):
            dialog.confirm_button.click()
        self.assertEqual('finalize', dialog.mode)
        self.assertEqual(QDialog.DialogCode.Accepted, dialog.result())
        completed = self.dialog(status='견적확정')
        self.assertFalse(completed.confirm_button.isEnabled())

    def test_manual_quote_keeps_its_existing_editor_and_calculation(self):
        self.draft['sourceRequestId'] = None
        dialog = self.dialog()
        self.assertEqual('quoteEditor', dialog.objectName())
        self.assertEqual(6, dialog.items.columnCount())
        self.assertEqual('합계 54,000원', dialog.total.text())
        dialog.items.item(0, 2).setText('11')
        self.assertEqual('합계 56,000원', dialog.total.text())
