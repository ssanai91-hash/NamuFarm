"""Focused regressions with synthetic numbers, no network or provider calls."""
import copy
import os
from types import SimpleNamespace
from unittest.mock import Mock, patch
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from PySide6.QtCore import QTimer, Qt
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication, QMessageBox, QScrollArea
from namufarm_desktop.commerce import (new_draft, normalize_sms_recipient,
    order_from_draft, quote_delivery_fingerprint)
from namufarm_desktop.domain import metadata
from namufarm_desktop.errors import UserError
from namufarm_desktop.service import Manager
from namufarm_desktop.ui_theme import workspace_style
from namufarm_desktop.workspace import (QuoteSmsDialog, QuoteEmailDialog,
    QuoteDeliveryDialog, CustomerOrderDialog)
from qt_support import QtTestCase


class ImmediateTask:
    def __init__(self, function, _value, _parent):
        self.function = function
        self.completed = SimpleNamespace(connect=lambda callback: setattr(self, 'callback', callback))
    def start(self):
        self.callback(self.function(), '', False)
    def wait(self, *_args): return True
    def deleteLater(self): pass


class DeliveryPolishTest(QtTestCase):
    def setUp(self):
        super().setUp()
        self.app.setProperty('namufarmLargeFont', False)
        self.app.setStyleSheet(workspace_style())
        self.draft = new_draft()
        self.draft.update(customerName='가상 고객', customerPhone='01000000000',
            customerEmail='example@example.com', sourceRequestId='00000000-0000-4000-8000-000000000001',
            requestReceiptNumber='NAMU-20261021-0001', isPrepaid=True, shippingFee=6000,
            items=[dict(species='가상 묘목', quantity=10, unitPrice=2000, unitCost=0, specification='')])

    def settle(self):
        for _ in range(8): self.app.processEvents()

    def test_phone_validation_accepts_hyphens_but_not_letters_or_unicode_digits(self):
        self.assertEqual('01000000000', normalize_sms_recipient('010-0000-0000'))
        for invalid in ('', '010ABC00000000', '０１０００００００００', '123456789', '010000000000', '010+00000000'):
            with self.subTest(invalid=invalid), self.assertRaises(UserError):
                normalize_sms_recipient(invalid)

    def test_service_records_actual_override_without_rewriting_quote_or_its_fingerprint(self):
        manager = Manager.__new__(Manager)
        manager.api = Mock()
        manager._require_delivery_server = Mock(return_value={"quoteDeliveryIdempotency": 1})
        manager._prepare_quote_delivery = Mock()
        manager._confirmed_source = Mock()
        manager._sold_number_for_draft = Mock()
        manager.quote_delivery_state = Mock(return_value={})
        manager.payment_delivery_state = Mock(return_value={})
        manager._save_quote_delivery_state = lambda _id, state: state
        manager._save_payment_delivery_state = lambda _id, state: state
        manager.api.call.return_value = dict(sent=True, recipientPhone='01011112222', messageId='test-id')
        original = copy.deepcopy(self.draft)
        for send in (manager.send_quote_sms, manager.send_payment_sms):
            result = send(self.draft, '가상 본문', recipient_phone='010-1111-2222')
            self.assertEqual('01011112222', manager.api.call.call_args.kwargs['recipientPhone'])
            self.assertEqual('01011112222', result['delivery']['smsTo'])
            self.assertEqual(quote_delivery_fingerprint(original), result['delivery']['smsQuoteFingerprint'])
            self.assertEqual(original, self.draft)
        before = manager.api.call.call_count
        with self.assertRaises(UserError):
            manager.send_quote_sms(self.draft, '본문', recipient_phone='잘못된 번호')
        self.assertEqual(before, manager.api.call.call_count)

    def test_sms_editor_dispatches_edited_number_and_locks_it_during_pending_send(self):
        manager = Mock()
        manager.quote_delivery_state.return_value = {}
        manager.sms_profile.return_value = dict(configured=True, senderPhone='0430000000')
        manager.send_quote_sms.return_value = {'delivery': {'smsState': 'pending', 'smsMessageId': 'test-id'}}
        with patch('namufarm_desktop.workspace.Task', ImmediateTask):
            dialog = QuoteSmsDialog(self.draft, manager)
            dialog.show(); self.settle()
            self.assertFalse(dialog.recipient_phone.isReadOnly())
            dialog.recipient_phone.setText('010-1111-2222')
            self.assertTrue(dialog.send_button.isEnabled())
            dialog.send_button.click()
            self.assertEqual('01011112222', manager.send_quote_sms.call_args.kwargs['recipient_phone'])
            self.assertEqual('01000000000', dialog.draft['customerPhone'])
            self.assertTrue(dialog.recipient_phone.isReadOnly())
            self.assertFalse(dialog.send_button.isEnabled())
            dialog.status_timer.stop()
            dialog.sms_status_checked({'smsState': 'failed'}, '', False)
            self.assertFalse(dialog.recipient_phone.isReadOnly())
            dialog.recipient_phone.setText('123')
            self.assertFalse(dialog.send_button.isEnabled())

    def test_payment_sms_keeps_explicit_confirmation_after_override_delivery_success(self):
        manager = Mock()
        manager.payment_delivery_state.return_value = {}
        manager.sms_profile.return_value = dict(configured=True, senderPhone='0430000000')
        manager.send_payment_sms.return_value = {'delivery': {'smsState': 'pending', 'smsMessageId': 'test-id'}}
        with patch('namufarm_desktop.workspace.Task', ImmediateTask):
            dialog = QuoteSmsDialog(self.draft, manager, payment=True)
            dialog.show(); self.settle()
            dialog.recipient_phone.setText('010-1111-2222')
            dialog.send_button.click()
            dialog.status_timer.stop()
            self.assertEqual('01011112222', manager.send_payment_sms.call_args.kwargs['recipient_phone'])
            self.assertFalse(dialog.payment_confirm_button.isEnabled())
            dialog.sms_status_checked({'smsState': 'success',
                'smsQuoteFingerprint': quote_delivery_fingerprint(self.draft)}, '', False)
            self.assertTrue(dialog.payment_confirm_button.isEnabled())
            self.assertFalse(dialog.success_confirmed)
            dialog.payment_confirm_button.click()
            self.assertTrue(dialog.success_confirmed)

    def test_message_body_gets_height_and_expanding_log_preserves_its_text(self):
        for cls, size, minimum in ((QuoteSmsDialog, (840, 720), 350), (QuoteEmailDialog, (1000, 720), 300)):
            with self.subTest(channel=cls.__name__):
                dialog = cls(self.draft)
                dialog.resize(*size); dialog.show(); self.settle()
                self.assertGreaterEqual(dialog.message.height(), minimum)
                self.assertEqual([], dialog.findChildren(QScrollArea))
                original = dialog.message.toPlainText()
                log = dialog.status.toPlainText()
                height = dialog.message.height()
                dialog.status_toggle.click(); self.settle()
                self.assertLess(dialog.message.height(), height)
                self.assertEqual(original, dialog.message.toPlainText())
                self.assertEqual(log, dialog.status.toPlainText())
                dialog.status_toggle.click(); self.settle()
                self.assertEqual(height, dialog.message.height())
                dialog.hide()

    def test_confirmed_table_fits_without_horizontal_scroll(self):
        self.draft['items'] *= 8
        dialog = QuoteDeliveryDialog(self.draft, '견적확정')
        dialog.resize(1280, 720); dialog.show(); self.settle()
        self.assertEqual(0, dialog.items.horizontalScrollBar().maximum())
        self.assertEqual(9, dialog.items.rowCount())  # Includes the quoted shipping audit row.

    def test_unsaved_close_escape_and_window_x_keep_input_until_explicit_discard(self):
        order = order_from_draft(self.draft)
        order.update(status='입금확인', paidAt=order['purchasedAt'])
        row = {'metadata': metadata(order), 'private': {'order': order}}
        dialog = CustomerOrderDialog(row)
        dialog.show(); self.settle()
        dialog.customer_fields['detailAddress'].setText('저장 전 주소')
        seen = []
        def respond(discard=False):
            prompt = QApplication.activeModalWidget()
            self.assertIsInstance(prompt, QMessageBox)
            seen.append(prompt.defaultButton().text())
            next(b for b in prompt.buttons() if b.text() == ('저장하지 않고 닫기' if discard else '계속 작성')).click()
        QTimer.singleShot(0, respond)
        dialog.reject()
        self.assertTrue(dialog.isVisible())
        QTimer.singleShot(0, respond)
        QTest.keyClick(dialog, Qt.Key.Key_Escape)
        self.assertTrue(dialog.isVisible())
        QTimer.singleShot(0, respond)
        dialog.close()
        self.assertTrue(dialog.isVisible())
        self.assertEqual('저장 전 주소', dialog.customer_fields['detailAddress'].text())
        self.assertEqual(['계속 작성'] * 3, seen)
        QTimer.singleShot(0, lambda: respond(True))
        dialog.reject()
        self.assertFalse(dialog.isVisible())
        self.assertIsNone(dialog.collected_fields)
        self.assertEqual('', row['private']['order']['detailAddress'])


    def test_uncertain_reopen_locks_send_and_dismiss_requires_explicit_yes(self):
        from namufarm_desktop.errors import UncertainDeliveryError
        manager = Mock()
        pending = {'pendingDelivery': {'id': '00000000-0000-4000-8000-000000000002', 'channel': 'sms', 'recipient': '01000000000'}}
        manager.quote_delivery_state.return_value = pending
        manager.check_quote_delivery.return_value = {**pending, 'deliveryResolution': 'unknown'}
        manager.dismiss_uncertain_delivery.return_value = {'deliveryResolution': 'dismissed'}
        with patch('namufarm_desktop.workspace.Task', ImmediateTask), patch('namufarm_desktop.app.Task', ImmediateTask):
            dialog = QuoteSmsDialog(self.draft, manager)
            dialog.show(); self.settle()
            self.assertFalse(dialog.send_button.isEnabled())
            self.assertTrue(dialog.check_button.isEnabled())
            self.assertIn('결과 불명', dialog.status.toPlainText())
            dialog.check_button.click()
            self.assertFalse(dialog.dismiss_button.isHidden())
            with patch.object(QMessageBox, 'question', return_value=QMessageBox.StandardButton.No):
                dialog.dismiss_button.click()
            manager.dismiss_uncertain_delivery.assert_not_called()
            manager.quote_delivery_state.return_value = {}
            with patch.object(QMessageBox, 'question', return_value=QMessageBox.StandardButton.Yes):
                dialog.dismiss_button.click()
            manager.dismiss_uncertain_delivery.assert_called_once_with(self.draft['id'], 'sms', payment=False)
            dialog.status_timer.stop()
