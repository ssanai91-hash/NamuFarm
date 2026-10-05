import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')

import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from PySide6.QtWidgets import QApplication, QMessageBox
from namufarm_desktop.app import MainWindow
from support import Bridge, manager_at


class UiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])
        cls.bridge = Bridge()
        cls.tmp = tempfile.TemporaryDirectory()
        cls.manager = manager_at(Path(cls.tmp.name) / 'ui.db', cls.bridge)
        cls.bridge.send(control='seed')
        cls.bridge.send(control='submit')
        cls.manager.sync()

    @classmethod
    def tearDownClass(cls):
        cls.bridge.close()
        cls.tmp.cleanup()

    def setUp(self):
        self.window = MainWindow(Path(self.tmp.name) / 'ui.db')
        self.window.accept_manager((self.manager, self.manager.view()))
        self.window.show()
        self.app.processEvents()

    def tearDown(self):
        self.window.dirty = False
        self.window.close()
        self.window.deleteLater()
        self.app.processEvents()

    def wait_job(self):
        deadline = time.monotonic() + 10
        while self.window.task and time.monotonic() < deadline:
            self.app.processEvents()
            time.sleep(.005)
        self.assertIsNone(self.window.task)

    def test_request_search_selection_and_personal_details(self):
        self.assertEqual(1, self.window.request_table.rowCount())
        self.window.request_table.selectRow(0)
        self.app.processEvents()
        self.assertIn('01012345678', self.window.request_detail.toPlainText())
        self.assertTrue(self.window.delete_button.isEnabled())
        self.window.request_search.setText('없는고객')
        self.assertEqual(0, self.window.request_table.rowCount())
        self.assertFalse(self.window.delete_button.isEnabled())

    def test_inbox_refresh_keeps_unsaved_sale_editor(self):
        self.window.sale_table.selectRow(0)
        self.window.fields['receiverName'].setText('아직 저장하지 않은 수령인')
        self.assertTrue(self.window.dirty)
        self.window.apply_inbox(self.manager.sync_requests())
        self.assertTrue(self.window.dirty)
        self.assertEqual('아직 저장하지 않은 수령인', self.window.fields['receiverName'].text())
        self.assertTrue(self.window.save_button.isEnabled())

    def test_save_button_sends_edited_fields_and_refreshes_revision(self):
        self.window.sale_table.selectRow(0)
        self.window.fields['receiverName'].setText('Qt 화면에서 저장')
        self.assertTrue(self.window.save_button.isEnabled())
        self.window.save_button.click()
        self.wait_job()
        self.assertEqual('Qt 화면에서 저장', self.bridge.send(control='stored')['order']['receiverName'])
        self.assertFalse(self.window.dirty)
        self.assertFalse(self.window.save_button.isEnabled())

    def select_new_request(self):
        request = self.bridge.send(control='submit_new')
        self.window.apply_view(self.manager.sync())
        index = next(i for i, row in enumerate(self.window.request_rows) if row['requestId'] == request['requestId'])
        self.window.request_table.selectRow(index)
        self.app.processEvents()
        return request['requestId']

    def test_delete_button_removes_only_confirmed_request_and_shows_delete_success(self):
        identifier = self.select_new_request()
        with patch.object(QMessageBox, 'question', return_value=QMessageBox.StandardButton.Yes):
            self.window.delete_button.click()
        self.wait_job()
        self.assertNotIn(identifier, [r['requestId'] for r in self.window.request_rows])
        self.assertEqual(1, self.window.request_table.rowCount())
        self.assertIn('문의 삭제를 서버에서 확인', self.window.message.text())
        self.assertFalse(self.window.data['pending'])

    def test_lost_delete_response_keeps_row_until_sync_confirms_delete(self):
        identifier = self.select_new_request()
        self.manager.api.lose_ack = True
        with patch.object(QMessageBox, 'question', return_value=QMessageBox.StandardButton.Yes):
            self.window.delete_button.click()
        self.wait_job()
        self.assertIn(identifier, [r['requestId'] for r in self.window.request_rows])
        self.assertEqual(1, len(self.window.data['pending']))
        self.assertFalse(self.window.delete_button.isEnabled())
        self.window.manual_sync()
        self.wait_job()
        self.assertNotIn(identifier, [r['requestId'] for r in self.window.request_rows])
        self.assertFalse(self.window.data['pending'])

    def test_validation_error_keeps_unsaved_form(self):
        self.window.sale_table.selectRow(0)
        self.window.fields['customerEmail'].setText('잘못된 이메일')
        self.window.save_button.click()
        self.wait_job()
        self.assertEqual('잘못된 이메일', self.window.fields['customerEmail'].text())
        self.assertTrue(self.window.dirty)

    def test_auto_refresh_does_not_discard_form_or_overlap_worker(self):
        self.window.sale_table.selectRow(0)
        self.window.fields['receiverName'].setText('입력 중')
        self.window.auto_sync()
        self.assertIsNone(self.window.task)
        self.assertEqual('입력 중', self.window.fields['receiverName'].text())
        self.window.dirty = False
        self.window.manual_sync()
        task = self.window.task
        self.window.auto_sync()
        self.assertIs(task, self.window.task)
        self.wait_job()

    def test_statistics_job_and_lock_clear_customer_data(self):
        self.window.fetch_report()
        self.wait_job()
        self.assertIn('총', self.window.report_summary.text())
        self.window.sale_table.selectRow(0)
        self.window.request_table.selectRow(0)
        self.window.lock()
        self.assertIsNone(self.window.manager)
        self.assertEqual(0, self.window.sale_table.rowCount())
        self.assertEqual(0, self.window.request_table.rowCount())
        self.assertEqual('', self.window.request_detail.toPlainText())
        self.assertEqual('', self.window.fields['customerName'].text())
        self.assertFalse(self.window.sync_button.isEnabled())


if __name__ == '__main__':
    unittest.main()
