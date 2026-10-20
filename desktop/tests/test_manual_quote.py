"""Direct seller quotations use synthetic customers and a local GAS fixture only."""
import copy
import os
import tempfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from PySide6.QtCore import QPoint
from PySide6.QtWidgets import QDialog, QMessageBox

from namufarm_desktop.commerce import new_manual_draft, quote_delivery_fingerprint
from namufarm_desktop.demo import demo_data
from namufarm_desktop.service import Manager
from namufarm_desktop.store import Vault
from namufarm_desktop.ui_theme import workspace_style
from namufarm_desktop.workspace import QuoteDialog, QuoteEmailDialog, QuoteSmsDialog, SellerWindow
from qt_support import QtTestCase
from support import Bridge, manager_at, PASSWORD


class ManualQuoteTest(QtTestCase):
    def setUp(self):
        super().setUp()
        self.app.setProperty('namufarmLargeFont', False)
        self.app.setStyleSheet(workspace_style())
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def dialog(self, filled=True, manager=None):
        dialog = QuoteDialog(new_manual_draft(), manager=manager)
        self.addCleanup(dialog.cleanup_generated_quote)
        dialog.resize(1280, 900)
        dialog.show()
        self.app.processEvents()
        if filled:
            for key, value in dict(customerName='가상 수기 고객', customerPhone='010-0000-0000',
                                   customerEmail='manual@example.com', requestRegion='옥천').items():
                dialog.fields[key].setText(value)
            dialog.items.item(0, 0).setText('왕모과')
            dialog.items.item(0, 1).setText('12')
            dialog.set_unit_price(0, 2000)
            dialog.items.item(0, 5).setText('3년생 / 키 80cm')
            dialog.note.setPlainText('화요일 오전에 발송합니다.')
        return dialog

    def window(self):
        window = SellerWindow(Path(self.tmp.name) / 'never-open.db')
        window.timer.stop()
        data = demo_data()
        data['requests'] = []
        window.apply_view(data)
        window.manager = Mock()
        window.update_controls()
        return window

    def test_add_quote_works_without_any_selected_request_and_selected_action_stays_distinct(self):
        window = self.window()
        self.assertEqual(0, window.request_table.rowCount())
        self.assertTrue(window.manual_quote_button.isEnabled())
        self.assertFalse(window.request_quote_button.isEnabled())
        with patch.object(window, 'edit_quote') as edit:
            window.manual_quote_button.click()
        draft = edit.call_args.kwargs['draft']
        self.assertEqual('', draft['sourceRequestId'])
        self.assertTrue(draft['requestReceiptNumber'].startswith('M-'))
        self.assertEqual('견적확정', draft['manualStatus'])
        self.assertEqual([], draft['items'])
        window.task = object()
        window.update_controls()
        self.assertFalse(window.manual_quote_button.isEnabled())
        window.task = None
        window.demo_mode = True
        window.update_controls()
        self.assertFalse(window.manual_quote_button.isEnabled())

    def test_selected_request_keeps_its_customer_and_source(self):
        window = self.window()
        data = demo_data()
        window.request_rows = copy.deepcopy(data['requests'])
        # Rebuild using the existing request renderer without a production manager.
        window.data['requests'] = data['requests']
        window.refresh_requests()
        window.request_table.selectRow(0)
        window.update_controls()
        self.assertTrue(window.request_quote_button.isEnabled())
        with patch.object(window, 'edit_quote') as edit:
            window.request_quote_button.click()
        draft = edit.call_args.kwargs['draft']
        request = window.request_rows[0]
        self.assertEqual(request['requestId'], draft['sourceRequestId'])
        self.assertEqual(request['personal']['customerName'], draft['customerName'])
        self.assertNotIn('manualStatus', draft)

    def test_customer_inputs_enable_channels_and_edits_invalidate_the_created_pdf(self):
        dialog = self.dialog(filled=False)
        self.assertTrue(dialog.manual_quote)
        self.assertFalse(dialog.simple_request)
        self.assertFalse(dialog.sms_button.isEnabled())
        self.assertFalse(dialog.email_button.isEnabled())
        dialog.fields['customerPhone'].setText('010-0000-0000')
        self.assertTrue(dialog.sms_button.isEnabled())
        dialog.fields['customerEmail'].setText('manual@example.com')
        self.assertTrue(dialog.email_button.isEnabled())
        dialog.fields['customerName'].setText('가상 고객')
        dialog.items.item(0, 0).setText('왕모과')
        dialog.set_unit_price(0, 2000)
        dialog.generate_quote_button.click()
        pdf = dialog.generated_quote_path
        self.assertTrue(pdf.is_file())
        self.assertTrue(pdf.read_bytes().startswith(b'%PDF'))
        self.assertEqual('01000000000', dialog.collect()['customerPhone'])
        dialog.fields['customerName'].setText('수정된 수신인')
        self.assertFalse(pdf.exists())
        self.assertFalse(dialog.preview_quote_button.isEnabled())
        dialog.fields['customerEmail'].clear()
        self.assertFalse(dialog.email_button.isEnabled())

    def test_generated_pdf_and_current_recipients_reach_the_mail_and_sms_popups(self):
        dialog = self.dialog()
        dialog.generate_quote_button.click()
        pdf = dialog.generated_quote_path
        email = Mock()
        email.exec.return_value = QDialog.DialogCode.Accepted
        email.delivery_state = {}
        with patch('namufarm_desktop.workspace.QMessageBox.question', return_value=QMessageBox.StandardButton.Yes), \
             patch('namufarm_desktop.workspace.QuoteEmailDialog', return_value=email) as factory:
            dialog.email_button.click()
        sent_draft = factory.call_args.args[0]
        self.assertEqual('manual@example.com', sent_draft['customerEmail'])
        self.assertEqual('3년생 / 키 80cm', sent_draft['items'][0]['remarks'])
        self.assertEqual(pdf, factory.call_args.kwargs['attachment_path'])
        sms = Mock()
        sms.delivery_state = {}
        with patch('namufarm_desktop.workspace.QuoteSmsDialog', return_value=sms) as factory:
            dialog.sms_button.click()
        self.assertEqual('01000000000', factory.call_args.args[0]['customerPhone'])
        self.assertEqual('', factory.call_args.args[0]['sourceRequestId'])
        current = SimpleNamespace(draft=sent_draft, quote_number=lambda: sent_draft['requestReceiptNumber'])
        for body in (QuoteSmsDialog.default_message(current), QuoteEmailDialog.default_message(current)):
            for value in ('왕모과 12주', '24,000원', '3년생 / 키 80cm', '화요일 오전에 발송합니다.'):
                self.assertIn(value, body)

    def test_save_uses_current_delivery_fingerprint_and_does_not_register_a_sale(self):
        dialog = self.dialog()
        old = quote_delivery_fingerprint(dialog.collect())
        dialog.delivery_state = {'emailSentAt': 1, 'emailQuoteFingerprint': old}
        dialog.note.setPlainText('배송 일정 변경')
        dialog.confirm_button.click()
        self.assertEqual('save_manual', dialog.mode)
        self.assertEqual('견적확정', dialog.draft['manualStatus'])
        self.assertEqual(QDialog.DialogCode.Accepted, dialog.result())
        another = self.dialog()
        another.delivery_state = {'emailSentAt': 1, 'emailQuoteFingerprint': quote_delivery_fingerprint(another.collect())}
        another.confirm_button.click()
        self.assertEqual('견적발송완료', another.draft['manualStatus'])

    def test_manual_save_dispatch_keeps_paused_refresh_timer_paused(self):
        window = self.window()
        draft = self.dialog().collect()
        modal = SimpleNamespace(exec=lambda: QDialog.DialogCode.Accepted,
                                draft=draft, mode='save_manual', workbench=True)
        window.manager.save_draft.return_value = {'saved': True}
        window.run_job = Mock()
        with patch('namufarm_desktop.workspace.QuoteDialog', return_value=modal):
            window.edit_quote(draft=draft)
        self.assertFalse(window.timer.isActive())
        work = window.run_job.call_args.args[1]
        self.assertEqual({'saved': True}, work())
        window.manager.save_draft.assert_called_once_with(draft)
        window.manager.confirm_draft.assert_not_called()
        window.manager.confirm_quote.assert_not_called()

    def test_small_large_font_manual_popup_keeps_inputs_and_actions_accessible(self):
        self.app.setProperty('namufarmLargeFont', True)
        self.app.setStyleSheet(workspace_style(True))
        dialog = self.dialog()
        dialog.resize(1000, 620)
        for _ in range(8):
            self.app.processEvents()
        self.assertEqual(0, dialog.items.horizontalScrollBar().maximum())
        for field in dialog.fields.values():
            self.assertGreaterEqual(field.height(), 44)
            self.assertGreaterEqual(field.width(), 100)
        for _ in range(8):
            self.app.processEvents()
        for action in (dialog.sms_button, dialog.email_button, dialog.confirm_button, dialog.close_button):
            self.assertTrue(dialog.workbench_footer.isAncestorOf(action))
            self.assertGreaterEqual(action.height(), 44)
            self.assertLess(action.mapTo(dialog, QPoint()).y() + action.height(), dialog.height())

    def test_manual_pdf_mail_sms_and_excel_restart_roundtrip_use_existing_server_contract(self):
        bridge = Bridge()
        self.addCleanup(bridge.close)
        path = Path(self.tmp.name) / 'manual-vault.db'
        manager = manager_at(path, bridge)
        bridge.send(control='configure_sms')
        dialog = self.dialog(manager=manager)
        dialog.generate_quote_button.click()
        draft = dialog.collect()
        self.assertEqual([], manager.view()['requests'])
        mail = manager.send_quote_email(draft, '수기 견적', '묘목 견적 안내', '<p>묘목 견적 안내</p>',
                                        attachment_path=dialog.generated_quote_path)
        self.assertEqual(dialog.generated_quote_path.name, mail['attachmentName'])
        sms = manager.send_quote_sms(draft, '왕모과 12주 24,000원 / 비고: 3년생')
        self.assertEqual('01000000000', sms['delivery']['smsTo'])
        self.assertEqual('success', manager.refresh_quote_sms_status(draft['id'])['smsState'])
        dialog.delivery_state = manager.quote_delivery_state(draft['id'])
        dialog.confirm_button.click()
        manager.save_draft(dialog.draft)
        manager = Manager(Vault.unlock(path, PASSWORD), manager.api)
        restored = manager.view()['confirmedQuotes'][0]
        self.assertEqual('견적발송완료', restored['quoteStatus'])
        self.assertEqual(draft['items'], restored['items'])
        self.assertEqual(draft['note'], restored['note'])
        self.assertEqual('manual@example.com', restored['customerEmail'])
        self.assertEqual([], manager.view()['requests'])
        self.assertEqual([], manager.view()['sales'])
        self.assertNotIn('save_quote_pricing', manager.api.calls)

