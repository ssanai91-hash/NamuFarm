import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from PySide6.QtWidgets import QApplication, QWidget, QLabel
from namufarm_desktop.inquiry_ui import InquiryPage, InquiryDialog
from namufarm_desktop.inquiries import InquiryStore
from test_inquiries import InquiryFixtureTest, IDENTIFIER

class InquiryUiTests(InquiryFixtureTest):
    # Reuse only the fixture, not the service test cases.
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def test_ui_local_draft_filters_and_pending_send(self):
        self.service.sync()
        owner = QWidget()
        owner.manager, owner.task, owner.demo_mode = self.manager, None, False
        owner.message = QLabel()
        owner.PAGE_INQUIRIES = 7
        owner.set_page_count = lambda *args: None
        page = InquiryPage(owner); page.refresh_local()
        self.assertEqual(len(page.rows), 1)
        dialog = InquiryDialog(page, IDENTIFIER)
        dialog.reply.setPlainText('화면에서 작성한 답변')
        self.assertTrue(dialog.remember())
        self.assertEqual(self.service.draft(self.service.row(IDENTIFIER))['text'], '화면에서 작성한 답변')
        self.assertTrue(dialog.sms.isEnabled()); self.assertTrue(dialog.email.isEnabled())
        row = self.service.row(IDENTIFIER)
        row['delivery']={'id':'00000002-0000-4000-8000-000000000001','channel':'sms','state':'pending','messageId':'mock'}
        self.service.store.merge([row]); dialog.refresh()
        self.assertFalse(dialog.sms.isEnabled()); self.assertFalse(dialog.email.isEnabled())
        self.assertTrue(dialog.check_button.isEnabled())
        dialog.reject()
        page.filter.setCurrentIndex(1)
        self.assertEqual(len(page.rows), 0)
        owner.deleteLater(); dialog.deleteLater(); self.app.processEvents()

    def test_expired_dialog_can_close_without_private_fields(self):
        self.service.sync()
        owner = QWidget(); owner.manager=self.manager; owner.task=None; owner.demo_mode=False
        owner.message=QLabel(); owner.PAGE_INQUIRIES=7; owner.set_page_count=lambda *args:None
        page=InquiryPage(owner)
        dialog=InquiryDialog(page, IDENTIFIER)
        row=self.service.row(IDENTIFIER); row.update(expired=True, revision=2, sequence=2)
        self.service.store.merge([row]); dialog.refresh()
        self.assertFalse(dialog.sms.isEnabled()); self.assertEqual(dialog.original.toPlainText(),'')
        dialog.reject(); self.assertEqual(dialog.result(), 0)
        owner.deleteLater(); dialog.deleteLater(); self.app.processEvents()

