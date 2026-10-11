import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')

import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QHeaderView, QMessageBox
from namufarm_desktop.app import MainWindow
from support import Bridge, manager_at
from qt_support import QtTestCase


class UiTest(QtTestCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
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
        super().setUp()
        self.window = MainWindow(Path(self.tmp.name) / 'ui.db')
        self.window.accept_manager((self.manager, self.manager.view()))
        self.window.show()
        self.app.processEvents()

    def tearDown(self):
        self.window.dirty = False
        self.window.close()

    def wait_job(self):
        deadline = time.monotonic() + 10
        while self.window.task and time.monotonic() < deadline:
            self.app.processEvents()
            time.sleep(.005)
        self.assertIsNone(self.window.task)

    def test_request_search_selection_and_personal_details(self):
        self.assertEqual(1, self.window.request_table.rowCount())
        headers = [self.window.request_table.horizontalHeaderItem(i).text() for i in range(self.window.request_table.columnCount())]
        self.assertEqual(
            ['접수번호', '접수일', '상태', '고객명', '전화번호', '메일주소', '지역', '견적서요청', '희망 묘목', '요청사항'],
            headers,
        )
        header = self.window.request_table.horizontalHeader()
        self.assertFalse(header.stretchLastSection())
        self.assertEqual(QHeaderView.ResizeMode.Stretch, header.sectionResizeMode(8))
        self.assertEqual(QHeaderView.ResizeMode.Stretch, header.sectionResizeMode(9))
        self.assertTrue(self.window.request_table.wordWrap())
        self.assertEqual(Qt.TextElideMode.ElideNone, self.window.request_table.textElideMode())
        self.assertEqual(
            QHeaderView.ResizeMode.ResizeToContents,
            self.window.request_table.verticalHeader().sectionResizeMode(0),
        )
        self.assertGreaterEqual(self.window.height(), 900)
        self.assertGreaterEqual(self.window.request_table.minimumHeight(), 400)
        self.assertFalse(hasattr(self.window, 'request_detail'))
        person = self.window.request_rows[0]['personal']
        self.assertEqual(person['customerPhone'], self.window.request_table.item(0, 4).text())
        self.assertEqual(person.get('customerEmail') or '—', self.window.request_table.item(0, 5).text())
        self.assertEqual(person['region'], self.window.request_table.item(0, 6).text())
        self.assertEqual('요청' if person.get('formalQuoteRequested') else '—', self.window.request_table.item(0, 7).text())
        expected_items = ', '.join(f"{i['species']} {i['quantity']}주" for i in person['items'])
        self.assertEqual(expected_items, self.window.request_table.item(0, 8).text())
        self.assertEqual(person.get('message') or '—', self.window.request_table.item(0, 9).text())
        self.window.request_table.selectRow(0)
        self.app.processEvents()
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

    def test_connection_screen_has_no_destructive_reset_action(self):
        self.assertFalse(hasattr(self.window, "reset_server_button"))
        self.assertFalse(hasattr(self.window, "reset_connection_button"))

    def test_statistics_job_keeps_open_workspace(self):
        self.window.fetch_report()
        self.wait_job()
        self.assertIn('총', self.window.report_summary.text())
        self.assertIsNotNone(self.window.manager)
        self.assertEqual(0, self.window.request_table.rowCount())
        self.assertEqual('', self.window.fields['customerName'].text())
        self.assertFalse(self.window.sync_button.isEnabled())


if __name__ == '__main__':
    unittest.main()
