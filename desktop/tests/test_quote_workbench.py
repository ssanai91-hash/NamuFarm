"""Quotation workbench layout and price-entry checks with synthetic data only."""
import copy
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from unittest.mock import Mock, patch

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

    def dialog(self, status='신규', size=(1280, 900), manager=None):
        dialog = QuoteDialog(self.draft, request_status=status, manager=manager)
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
        self.assertAlmostEqual(.33, dialog.customer_scroll.width() / dialog.width(), delta=.02)
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
        self.assertEqual(6000, result['shippingFee'])
        self.assertFalse(result['isPrepaid'])

    def test_customer_header_icons_alignment_and_bold_receipt_match_selected_design(self):
        dialog = self.dialog()
        heading = dialog.workbench_customer_heading
        banner = dialog.workbench_formal_banner
        self.assertEqual(heading.geometry().center().y(), banner.geometry().center().y())
        self.assertGreater(banner.x(), heading.geometry().right())
        self.assertLessEqual(banner.geometry().right(), dialog.customer_scroll.viewport().width())
        value_columns = {value.x() for value in dialog.customer_values.values()}
        self.assertEqual(1, len(value_columns))
        self.assertTrue(dialog.customer_values['견적번호'].font().bold())
        self.assertTrue(dialog.findChild(type(dialog.customer_captions['고객명']), 'workbenchReference').font().bold())
        names = [icon.property('iconName') for icon in dialog.workbench_icons]
        self.assertIn('person', names)
        self.assertIn('leaf', names)
        self.assertIn('calculator', names)
        self.assertIn('document', names)
        self.assertTrue(all(not icon.pixmap().isNull() for icon in dialog.workbench_icons))

    def test_long_customer_values_wrap_without_changing_their_copyable_text(self):
        self.app.setProperty('namufarmLargeFont', True)
        self.app.setStyleSheet(workspace_style(True))
        dialog = self.dialog(size=(1100, 680))
        for key in ('이메일', '견적번호'):
            value = dialog.customer_values[key]
            self.assertGreater(value.height(), value.fontMetrics().height())
            self.assertEqual(self.draft['customerEmail' if key == '이메일' else 'requestReceiptNumber'], value.text())
            cursor = value.textCursor()
            cursor.select(cursor.SelectionType.Document)
            self.assertEqual(value.text(), cursor.selectedText())
            self.assertLessEqual(value.geometry().right(), dialog.customer_scroll.viewport().width())

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

    def test_integrated_shipping_pdf_and_price_edits_use_one_current_quote(self):
        manager = Mock()
        manager.quote_delivery_state.return_value = {}
        dialog = self.dialog(size=(1280, 988), manager=manager)
        self.assertEqual(0, dialog.workbench_scroll.verticalScrollBar().maximum())
        dialog.shipping_prepaid.setChecked(True)
        self.assertEqual('60,000원', dialog.total.text())
        dialog.generate_quote_button.click()
        path = dialog.generated_quote_path
        self.addCleanup(dialog.cleanup_generated_quote)
        self.assertTrue(path.is_file())
        self.assertTrue(path.read_bytes().startswith(b'%PDF'))
        self.assertEqual(path.name, dialog.quote_file_label.text())
        self.assertEqual(6000, dialog.draft['shippingFee'])
        self.assertTrue(dialog.draft['isPrepaid'])
        manager.confirm_quote.assert_not_called()
        manager.save_draft.assert_not_called()
        dialog.set_unit_price(0, 2500)
        self.assertEqual('65,000원', dialog.total.text())
        self.assertFalse(path.exists())
        self.assertFalse(dialog.preview_quote_button.isEnabled())
        dialog.generate_quote_button.click()
        path = dialog.generated_quote_path
        dialog.shipping_fee.setText('7000')
        self.assertFalse(path.exists())
        self.assertEqual('66,000원', dialog.total.text())
        dialog.shipping_cod.setChecked(True)
        self.assertEqual('59,000원', dialog.total.text())

    def test_integrated_channel_dialogs_receive_current_inputs_and_pdf_attachment(self):
        manager = Mock()
        manager.quote_delivery_state.return_value = {}
        dialog = self.dialog(manager=manager)
        self.addCleanup(dialog.cleanup_generated_quote)
        dialog.shipping_prepaid.setChecked(True)
        dialog.shipping_fee.setText('7000')
        sms = Mock(delivery_state={'smsState': 'pending'})
        sms.exec.return_value = QDialog.DialogCode.Rejected
        with patch('namufarm_desktop.workspace.QuoteSmsDialog', return_value=sms) as factory:
            dialog.sms_button.click()
        sent = factory.call_args.args[0]
        self.assertEqual(7000, sent['shippingFee'])
        self.assertTrue(sent['isPrepaid'])
        self.assertEqual('문자 결과확인', dialog.sms_status_badge.text())
        dialog.generate_quote_button.click()
        attachment = dialog.generated_quote_path
        mail = Mock(delivery_state={'emailSentAt': 1})
        mail.exec.return_value = QDialog.DialogCode.Accepted
        with patch('namufarm_desktop.workspace.QMessageBox.question', return_value=QMessageBox.StandardButton.Yes), \
             patch('namufarm_desktop.workspace.QuoteEmailDialog', return_value=mail) as factory:
            dialog.email_button.click()
        self.assertEqual(attachment, factory.call_args.kwargs['attachment_path'])
        self.assertEqual(7000, factory.call_args.args[0]['shippingFee'])
        self.assertEqual('', dialog.mode)
        manager.confirm_quote.assert_not_called()
        with patch('namufarm_desktop.workspace.QMessageBox.question', return_value=QMessageBox.StandardButton.Yes):
            dialog.confirm_button.click()
        self.assertEqual('finalize', dialog.mode)
        self.assertEqual(7000, dialog.draft['shippingFee'])
        self.assertTrue(dialog.draft['isPrepaid'])
        self.assertFalse(attachment.exists())
