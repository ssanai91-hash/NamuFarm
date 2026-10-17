"""Native Qt regression checks with synthetic customers; no server or vault."""
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
import uuid
from unittest.mock import Mock, patch

from PySide6.QtCore import QPoint, Qt
from PySide6.QtWidgets import QDialog, QMessageBox
from namufarm_desktop.commerce import new_draft
from namufarm_desktop.ui_theme import workspace_style
from namufarm_desktop.workspace import QuoteDeliveryDialog
from qt_support import QtTestCase


class DeliveryRedesignTest(QtTestCase):
    def setUp(self):
        super().setUp()
        self.app.setProperty('namufarmLargeFont', False)
        self.app.setStyleSheet(workspace_style())
        self.draft = new_draft()
        self.draft.update(customerName='가상 고객', customerPhone='01012345678',
                          sourceRequestId=str(uuid.uuid4()), requestReceiptNumber='TEST-' + uuid.uuid4().hex,
                          formalQuoteRequested=True, shippingFee=6000,
                          items=[dict(species='가상 묘목', specification='', quantity=10, unitPrice=3000, unitCost=1000)])

    def dialog(self, status='견적확정', manager=None):
        dialog = QuoteDeliveryDialog(self.draft, status, manager=manager)
        dialog.resize(1100, 660)
        dialog.show()
        for _ in range(6):
            self.app.processEvents()
        self.addCleanup(dialog.cleanup_generated_quote)
        return dialog

    def test_pdf_name_is_below_tools_and_shipping_edit_invalidates_actual_file(self):
        dialog = self.dialog()
        dialog.generate_quote_button.click()
        self.app.processEvents()
        path = dialog.generated_quote_path
        self.assertTrue(path.is_file())
        self.assertTrue(path.read_bytes().startswith(b'%PDF'))
        self.assertEqual(path.name, dialog.quote_file_label.text())
        self.assertEqual(path.name, dialog.quote_file_label.toolTip())
        self.assertGreaterEqual(dialog.quote_file_label.mapTo(dialog, QPoint()).y(),
                                dialog.generate_quote_button.mapTo(dialog, QPoint()).y() + dialog.generate_quote_button.height())
        self.assertTrue(dialog.preview_quote_button.isEnabled())
        dialog.shipping_prepaid.setChecked(True)
        dialog.shipping_fee.setText('7000')
        self.assertEqual('37,000원', dialog.total_value.text())
        self.assertFalse(path.exists())
        self.assertIsNone(dialog.generated_quote_path)
        self.assertFalse(dialog.preview_quote_button.isEnabled())
        self.assertEqual('', dialog.quote_file_label.toolTip())

    def test_compact_window_keeps_filename_disclosure_and_footer_visible(self):
        dialog = self.dialog()
        self.assertEqual(0, dialog.delivery_scroll.verticalScrollBar().maximum())
        self.assertEqual(0, dialog.items.horizontalScrollBar().maximum())
        for column in range(4):
            self.assertEqual(Qt.AlignmentFlag.AlignCenter, dialog.items.item(0, column).textAlignment())
        self.assertTrue(dialog.delivery_section_rule.isVisible())
        self.assertLess(dialog.delivery_section_rule.mapTo(dialog, QPoint()).y(),
                        dialog.generate_quote_button.mapTo(dialog, QPoint()).y())
        self.assertLess(dialog.sent_button.mapTo(dialog, QPoint()).y() + dialog.sent_button.height(), dialog.height())
        self.assertFalse(dialog.email_button.isEnabled())
        self.assertFalse(dialog.payment_button.isVisible())

    def test_sms_gets_current_shipping_and_does_not_implicitly_complete_quote(self):
        manager = Mock()
        manager.quote_delivery_state.return_value = {}
        dialog = self.dialog(manager=manager)
        dialog.shipping_prepaid.setChecked(True)
        dialog.shipping_fee.setText('7000')
        modal = Mock(delivery_state={'smsState': 'pending'})
        modal.exec.return_value = QDialog.DialogCode.Rejected
        with patch('namufarm_desktop.workspace.QuoteSmsDialog', return_value=modal) as factory:
            dialog.sms_button.click()
        sent_draft = factory.call_args.args[0]
        self.assertEqual(7000, sent_draft['shippingFee'])
        self.assertTrue(sent_draft['isPrepaid'])
        self.assertEqual('', dialog.mode)
        self.assertEqual('문자 결과확인', dialog.sms_status_badge.text())

    def test_complete_quote_requires_explicit_confirmation(self):
        dialog = self.dialog()
        with patch('namufarm_desktop.workspace.QMessageBox.question', return_value=QMessageBox.StandardButton.No):
            dialog.sent_button.click()
        self.assertEqual('', dialog.mode)
        self.assertTrue(dialog.isVisible())
        with patch('namufarm_desktop.workspace.QMessageBox.question', return_value=QMessageBox.StandardButton.Yes):
            dialog.sent_button.click()
        self.assertEqual('sent', dialog.mode)
        self.assertEqual(QDialog.DialogCode.Accepted, dialog.result())

    def test_sent_quote_keeps_payment_action_and_delivery_outcome_badges(self):
        dialog = self.dialog(status='견적발송완료')
        self.assertTrue(dialog.payment_button.isVisible())
        self.assertFalse(dialog.sent_button.isVisible())
        dialog.delivery_state = {'smsState': 'failed'}
        dialog.update_delivery_badges()
        self.assertEqual('문자 전송실패', dialog.sms_status_badge.text())
        dialog.delivery_state = {'smsState': 'success', 'emailSentAt': 1}
        dialog.update_delivery_badges()
        self.assertEqual('문자 전송완료', dialog.sms_status_badge.text())
        self.assertEqual('메일 전송완료', dialog.email_status_badge.text())
