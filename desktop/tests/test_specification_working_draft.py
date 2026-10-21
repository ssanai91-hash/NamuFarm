"""Local unfinished quote persistence and specification-to-output regression checks."""
import copy
import os
import tempfile
from pathlib import Path
from unittest.mock import Mock, patch
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from PySide6.QtWidgets import QMessageBox
from namufarm_desktop.commerce import (new_draft, new_manual_draft, validate_draft,
    validate_working_draft, quote_document, payment_confirmation_message)
from namufarm_desktop.errors import UserError
from namufarm_desktop.service import Manager
from namufarm_desktop.store import Vault
from namufarm_desktop.workspace import (QuoteDialog, SellerWindow, QuoteSmsDialog,
    QuoteEmailDialog, official_quote_page_plan)
from namufarm_desktop.demo import show_demo
from namufarm_desktop.ui_theme import workspace_style
from test_simple_connection import TestAccount
from test_delivery_polish import ImmediateTask
from qt_support import QtTestCase


class SpecificationWorkingDraftTest(QtTestCase):
    def setUp(self):
        super().setUp()
        self.app.setStyleSheet(workspace_style())
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.account = TestAccount()
        self.path = Path(self.tmp.name) / 'test.db'
        self.vault = Vault.create_device(self.path, self.account)
        self.api = Mock()
        self.api.url = 'https://example.invalid'
        self.manager = Manager(self.vault, self.api)
        self.draft = new_draft()
        self.draft.update(customerName='가상 고객', customerPhone='01000000000',
            sourceRequestId='00000000-0000-4000-8000-000000000001',
            requestReceiptNumber='NAMU-20261021-0001',
            items=[dict(species='가상 묘목', specification='', quantity=10, unitPrice=2000, unitCost=0)])

    def settle(self):
        for _ in range(8): self.app.processEvents()

    def dialog(self, draft=None):
        dialog = QuoteDialog(draft or self.draft, manager=self.manager, request_status='신규')
        dialog.resize(1280, 800); dialog.show(); self.settle()
        return dialog

    def test_specification_editor_is_between_name_and_quantity_and_reaches_outputs(self):
        dialog = self.dialog()
        header = dialog.items.horizontalHeader()
        self.assertEqual([4, 0, 5, 1, 3, 2], [header.logicalIndex(i) for i in range(6)])
        self.assertEqual('규격', dialog.items.horizontalHeaderItem(5).text())
        dialog.items.item(0, 5).setText('2년생 / H 1.5m')
        result = dialog.collect()
        self.assertEqual('2년생 / H 1.5m', result['items'][0]['specification'])
        self.assertEqual('', result['items'][0]['remarks'])
        self.assertEqual('2년생 / H 1.5m', official_quote_page_plan(result)[0]['rows'][0][0][1])
        self.assertEqual('2년생 / H 1.5m', quote_document(result)['lines'][0]['specification'])
        for cls in (QuoteSmsDialog, QuoteEmailDialog):
            msg = cls(result)
            self.assertIn('규격 : 2년생 / H 1.5m', msg.message.toPlainText())
        self.assertIn('규격 : 2년생 / H 1.5m', payment_confirmation_message(result))

    def test_legacy_note_is_offered_as_specification_and_archived_on_save(self):
        self.draft['items'][0]['remarks'] = '2년생'
        original = copy.deepcopy(self.draft)
        dialog = self.dialog()
        self.assertEqual('2년생', dialog.items.item(0, 5).text())
        item = dialog.collect()['items'][0]
        self.assertEqual('2년생', item['specification'])
        self.assertEqual('2년생', item['legacyRemarks'])
        self.assertEqual(original, self.draft)

    def test_working_save_survives_reopen_without_delivery_or_confirmation(self):
        dialog = self.dialog()
        dialog.items.item(0, 5).setText('H 1.5m')
        with patch('namufarm_desktop.workspace.Task', ImmediateTask):
            dialog.save_working_button.click()
        self.assertTrue(dialog.isVisible())
        self.assertEqual(dialog.initial, dialog.raw_state())
        reopened = Manager(Vault.open_device(self.path, self.account), self.api)
        view = reopened.view()
        self.assertEqual([], view['confirmedQuotes'])
        self.assertEqual('H 1.5m', view['drafts'][0]['items'][0]['specification'])
        self.assertTrue(view['drafts'][0]['workingCopy'])
        self.assertEqual([], self.api.mock_calls)
        restored = self.dialog(view['drafts'][0])
        self.assertEqual('H 1.5m', restored.items.item(0, 5).text())
        restored.items.item(0, 5).setText('H 2m')
        with patch('namufarm_desktop.workspace.Task', ImmediateTask):
            restored.save_working_button.click()
        self.assertEqual('H 2m', self.manager.view()['drafts'][0]['items'][0]['specification'])

    def test_incomplete_manual_inputs_can_be_saved_but_cannot_be_sent_as_a_valid_quote(self):
        draft = new_manual_draft()
        draft.update(customerName='', customerPhone='010', customerEmail='not-finished', items=[])
        self.assertEqual(draft, validate_working_draft(draft))
        view = self.manager.save_working_draft(draft)
        self.assertEqual([], view['confirmedQuotes'])
        saved = view['drafts'][0]
        self.assertEqual('작성중', saved['manualStatus'])
        self.assertEqual('010', saved['customerPhone'])
        with self.assertRaises(UserError): validate_draft(saved)
        dialog = self.dialog(saved)
        dialog.items.item(0, 1).setText('')
        dialog.unit_price_editor(0).input.clear()
        with patch('namufarm_desktop.workspace.Task', ImmediateTask):
            dialog.save_working_button.click()
        saved = self.manager.view()['drafts'][0]
        self.assertEqual(0, saved['items'][0]['quantity'])
        self.assertEqual('', saved['items'][0]['species'])
        self.assertEqual([], self.api.mock_calls)

    def test_working_list_opens_editor_not_confirmed_review(self):
        view = self.manager.save_working_draft(self.draft)
        window = SellerWindow(vault_path=Path(self.tmp.name) / 'unused.db')
        show_demo(window)
        window.apply_view(view)
        self.assertEqual(['작성중'], window.draft_statuses)
        window.draft_table.selectRow(0)
        window.edit_quote = Mock()
        window.open_delivery_quote = Mock()
        window.open_draft()
        window.edit_quote.assert_called_once()
        window.open_delivery_quote.assert_not_called()
        self.manager.delete_draft(self.draft['id'])
        self.assertEqual([], self.manager.view()['drafts'])
        self.assertEqual([], self.api.mock_calls)

    def test_failed_save_does_not_mark_unsaved_inputs_as_saved(self):
        dialog = self.dialog()
        initial = copy.deepcopy(dialog.initial)
        dialog.items.item(0, 5).setText('H 2m')
        dialog._working_snapshot = dialog.collect(False, working=True)
        dialog.working_draft_saved(None, '가상 저장 실패', False)
        self.assertEqual(initial, dialog.initial)
        self.assertNotEqual(initial, dialog.raw_state())
        self.assertIn('저장 실패', dialog.working_save_status.text())

    def test_manual_completion_still_requires_delivery_and_clears_working_copy_after_confirmation(self):
        draft = new_manual_draft()
        draft.update(customerName='가상 고객', customerPhone='01000000000',items=copy.deepcopy(self.draft['items']))
        saved = self.manager.save_working_draft(draft)['drafts'][0]
        dialog = self.dialog(saved)
        with patch.object(QMessageBox, 'warning') as warning:
            dialog.finish('save_manual')
        warning.assert_called_once()
        self.assertTrue(dialog.isVisible())
        self.manager._store_local_quote(saved, '견적확정')
        view = self.manager.view()
        self.assertEqual([], view['drafts'])
        self.assertFalse(view['confirmedQuotes'][0].get('workingCopy'))
