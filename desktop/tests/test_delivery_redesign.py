"""Confirmed quotations are immutable local results, not another delivery editor."""
import copy
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
import uuid
from unittest.mock import Mock, patch

from PySide6.QtCore import QPoint, QRect, Qt
from PySide6.QtWidgets import QDialog, QLineEdit, QPushButton, QRadioButton, QWidget
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
                          customerEmail='sample@example.com', sourceRequestId=str(uuid.uuid4()),
                          requestReceiptNumber='NAMU-20261017-0001', formalQuoteRequested=True,
                          shippingFee=7000, isPrepaid=True, note='저장된 고객 요청사항',
                          items=[dict(species='가상 묘목', specification='', quantity=10, unitPrice=3000, unitCost=1000)])

    def settle(self):
        for _ in range(8):
            self.app.processEvents()

    def dialog(self, status='견적확정', manager=None, state=None):
        dialog = QuoteDeliveryDialog(self.draft, status, manager=manager, delivery_state=state)
        dialog.resize(1100, 660)
        dialog.show()
        self.settle()
        return dialog

    def test_only_close_action_remains_and_table_cannot_be_edited(self):
        dialog = self.dialog()
        self.assertEqual('확정견적 확인', dialog.windowTitle())
        self.assertEqual(['닫기'], [button.text() for button in dialog.findChildren(QPushButton)])
        self.assertEqual([], dialog.findChildren(QLineEdit))
        self.assertEqual([], dialog.findChildren(QRadioButton))
        self.assertEqual(dialog.items.EditTrigger.NoEditTriggers, dialog.items.editTriggers())
        self.assertEqual(['1', '가상 묘목', '10주', '3,000원', '30,000원'],
                         [dialog.items.item(0, column).text() for column in range(5)])
        self.assertTrue(all(dialog.items.item(0, column).textAlignment() == Qt.AlignmentFlag.AlignCenter
                            for column in range(5)))
        for old_action in ('generate_official_quote', 'open_sms', 'open_email', 'persist_shipping',
                           'mark_sent', 'confirm_payment', 'delivery_text'):
            self.assertFalse(hasattr(dialog, old_action))

    def test_open_and_close_never_save_send_or_change_the_snapshot(self):
        original = copy.deepcopy(self.draft)
        manager = Mock()
        dialog = self.dialog(manager=manager)
        self.assertEqual('37,000원', dialog.total_value.text())
        self.assertEqual('선불 7,000원 · 합계 포함', dialog.result_values['shipping'].text())
        self.assertEqual(self.draft['note'], dialog.result_values['note'].text())
        dialog.close_button.click()
        self.assertEqual(QDialog.DialogCode.Rejected, dialog.result())
        self.assertEqual(original, self.draft)
        self.assertEqual(original, dialog.draft)
        self.assertEqual([], manager.mock_calls)

    def test_zero_shipping_is_never_replaced_by_an_editing_default(self):
        for prepaid in (False, True):
            with self.subTest(prepaid=prepaid):
                self.draft.update(shippingFee=0, isPrepaid=prepaid)
                dialog = self.dialog()
                self.assertEqual(0, dialog.draft['shippingFee'])
                self.assertEqual('30,000원', dialog.total_value.text())
                self.assertEqual('선불 0원 · 합계 포함' if prepaid else '후불 · 합계 미포함',
                                 dialog.result_values['shipping'].text())

    def test_delivery_outcomes_and_actual_recipients_are_displayed_without_polling(self):
        manager = Mock()
        state = {'smsState': 'success', 'smsSentAt': 1792200000000, 'smsTo': '01099998888',
                 'emailSentAt': 1792200001000, 'emailTo': 'sent@example.com'}
        dialog = self.dialog(manager=manager, state=state)
        self.assertEqual('전송완료', dialog.result_values['smsStatus'].text())
        self.assertEqual('전송완료', dialog.result_values['emailStatus'].text())
        self.assertEqual('01099998888', dialog.result_values['smsTo'].text())
        self.assertEqual('sent@example.com', dialog.result_values['emailTo'].text())
        self.assertIn('2026-', dialog.result_values['smsTime'].text())
        self.assertEqual([], manager.mock_calls)
        dialog.close_button.click()
        self.assertEqual([], manager.mock_calls)
        for state, expected in (({}, '전송 기록 없음'), ({'smsMessageId': 'legacy'}, '결과확인 중'),
                                ({'smsState': 'pending'}, '결과확인 중'),
                                ({'smsState': 'failed', 'smsStatusMessage': '수신번호 확인'}, '전송실패')):
            pending = self.dialog(state=state)
            self.assertEqual(expected, pending.result_values['smsStatus'].text())
            self.assertEqual('전송 기록 없음', pending.result_values['emailStatus'].text())

    def test_initial_height_uses_parent_monitor_work_area(self):
        parent = QWidget()
        for area in (QRect(1920, 0, 1920, 1040), QRect(0, 0, 1366, 728)):
            screen = Mock()
            screen.availableGeometry.return_value = area
            with patch.object(parent, 'screen', return_value=screen):
                dialog = QuoteDeliveryDialog(self.draft, '견적확정', parent=parent)
                self.assertEqual(min(int(area.height() * .95), area.height() - 48), dialog.height())
                self.assertEqual(min(1140, area.width() - 40), dialog.width())
                dialog.deleteLater()

    def test_long_list_grows_and_small_windows_keep_close_visible(self):
        self.draft['items'] *= 20
        for large in (False, True):
            self.app.setProperty('namufarmLargeFont', large)
            self.app.setStyleSheet(workspace_style(large))
            dialog = self.dialog()
            short_height = dialog.items.height()
            self.assertGreater(dialog.items.verticalScrollBar().maximum(), 0)
            dialog.resize(1140, 988)
            self.settle()
            self.assertGreater(dialog.items.height(), short_height)
            dialog.resize(900, 600)
            self.settle()
            self.assertEqual(0, dialog.items.horizontalScrollBar().maximum())
            self.assertLess(dialog.close_button.mapTo(dialog, QPoint()).y() + dialog.close_button.height(), dialog.height())
            self.assertEqual(0, dialog.delivery_scroll.horizontalScrollBar().maximum())

    def test_long_email_values_wrap_without_losing_copyable_text(self):
        self.draft['customerEmail'] = 'long.customer.address.for.readonly.results@example.com'
        self.app.setProperty('namufarmLargeFont', True)
        dialog = self.dialog(state={'emailSentAt': 1792200000000, 'emailTo': self.draft['customerEmail']})
        dialog.resize(900, 600)
        self.settle()
        for value in (dialog.result_text_values[1], dialog.result_values['emailTo']):
            self.assertEqual(self.draft['customerEmail'], value.text())
            self.assertGreater(value.height(), value.fontMetrics().height())
            self.assertEqual(0, value.horizontalScrollBar().maximum())
            self.assertEqual(0, value.verticalScrollBar().maximum())
