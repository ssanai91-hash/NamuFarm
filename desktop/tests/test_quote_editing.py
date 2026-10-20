"""Seller edits use synthetic requests, local workbooks and offscreen Qt only."""
import copy
import os
import tempfile
from pathlib import Path

os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from PySide6.QtCore import Qt
from PySide6.QtPdf import QPdfDocument

from namufarm_desktop.commerce import (new_draft, official_quote_html, quote_document,
    quote_document_html, quote_delivery_fingerprint, validate_draft)
from namufarm_desktop.confirmed_excel import read_confirmed_quotes, write_confirmed_quotes
from namufarm_desktop.errors import UserError
from namufarm_desktop.workspace import (QuoteDialog, official_quote_page_plan,
    render_official_quote_image, render_official_quote_images, write_temporary_quote_pdf)
from qt_support import QtTestCase


class QuoteEditingTest(QtTestCase):
    def setUp(self):
        super().setUp()
        self.draft = new_draft()
        self.draft.update(customerName='가상 고객', customerPhone='01000000000',
            sourceRequestId='00000000-0000-4000-8000-000000000003',
            requestReceiptNumber='NAMU-20261020-0003', requestNote='고객이 처음 남긴 요청',
            note='고객이 처음 남긴 요청',
            items=[dict(species='모과', specification='고객 희망 규격', quantity=10, unitPrice=2000, unitCost=500)])

    def dialog(self):
        dialog = QuoteDialog(self.draft, request_status='신규')
        dialog.show()
        self.app.processEvents()
        return dialog

    def test_existing_name_quantity_and_per_item_remarks_are_editable_and_recalculate(self):
        before = copy.deepcopy(self.draft)
        dialog = self.dialog()
        for column in (0, 1, 5):
            self.assertTrue(dialog.items.item(0, column).flags() & Qt.ItemFlag.ItemIsEditable)
        for column in (2, 4):
            self.assertFalse(dialog.items.item(0, column).flags() & Qt.ItemFlag.ItemIsEditable)
        dialog.items.item(0, 0).setText('왕모과')
        dialog.items.item(0, 1).setText('12')
        dialog.items.item(0, 5).setText('3년생 / 키 80cm / 포트묘')
        dialog.note.setPlainText('판매자 안내: 11월 첫째 주 배송')
        result = dialog.collect()
        self.assertEqual('24,000원', dialog.total.text())
        self.assertEqual(dict(species='왕모과', specification='고객 희망 규격', quantity=12,
            unitPrice=2000, unitCost=500, remarks='3년생 / 키 80cm / 포트묘'), result['items'][0])
        self.assertEqual(before, self.draft)
        self.assertEqual(before['requestNote'], result['requestNote'])
        self.assertIn('판매자 안내', result['note'])

    def test_add_remove_and_collect_keep_remarks_attached_to_the_right_seedling(self):
        dialog = self.dialog()
        dialog.add_item_button.click()
        row = 1
        dialog.items.item(row, 0).setText('블루베리')
        dialog.items.item(row, 1).setText('3')
        dialog.set_unit_price(row, 15000)
        dialog.items.item(row, 5).setText('5년생')
        self.assertEqual(2, dialog.items.rowCount())
        dialog.items.setCurrentCell(0, 0)
        dialog.remove_item_button.click()
        result = dialog.collect()
        self.assertEqual(1, len(result['items']))
        self.assertEqual('블루베리', result['items'][0]['species'])
        self.assertEqual('5년생', result['items'][0]['remarks'])
        self.assertEqual('1', dialog.items.item(0, 4).text())
        self.assertEqual('45,000원', dialog.total.text())

    def test_note_and_remarks_edits_invalidate_pdf_and_prior_delivery_confirmation(self):
        dialog = self.dialog()
        self.addCleanup(dialog.cleanup_generated_quote)
        old = quote_delivery_fingerprint(dialog.collect())
        dialog.delivery_state = {'emailSentAt': 1, 'emailQuoteFingerprint': old}
        dialog.generate_quote_button.click()
        first_path = dialog.generated_quote_path
        self.assertTrue(first_path.is_file())
        dialog.note.setPlainText('판매자가 수정한 문구')
        self.assertFalse(first_path.exists())
        self.assertFalse(dialog.preview_quote_button.isEnabled())
        self.assertEqual('재전송', dialog.email_status_badge.text())
        dialog.generate_quote_button.click()
        second_path = dialog.generated_quote_path
        dialog.items.item(0, 5).setText('4년생')
        self.assertFalse(second_path.exists())
        self.assertNotEqual(old, quote_delivery_fingerprint(dialog.collect()))

    def test_confirmed_workbook_and_quotation_outputs_preserve_all_seller_fields(self):
        draft = self.dialog().collect()
        draft['note'] = '판매자 안내\n11월 배송 <확인>'
        draft['items'][0]['remarks'] = '3년생 / H 80cm <포트>'
        draft['quoteStatus'] = '견적확정'
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'confirmed.xlsx'
            write_confirmed_quotes(path, [draft])
            restored = read_confirmed_quotes(path)[0]
        self.assertEqual(draft, restored)
        self.assertEqual('고객이 처음 남긴 요청', restored['requestNote'])
        self.assertIn('3년생 / H 80cm &lt;포트&gt;', official_quote_html(restored))
        self.assertIn('11월 배송 &lt;확인&gt;', official_quote_html(restored))
        doc = quote_document(restored)
        self.assertEqual(restored['items'][0]['remarks'], doc['lines'][0]['remarks'])
        self.assertIn('3년생', quote_document_html(doc))
        self.assertIn('11월 배송', quote_document_html(doc))

    def test_legacy_quotes_with_no_remarks_retain_their_delivery_fingerprint(self):
        legacy = copy.deepcopy(self.draft)
        before = quote_delivery_fingerprint(legacy)
        with_empty = copy.deepcopy(legacy)
        with_empty['items'][0]['remarks'] = ''
        self.assertEqual(before, quote_delivery_fingerprint(with_empty))
        with_empty['items'][0]['remarks'] = '2년생'
        self.assertNotEqual(before, quote_delivery_fingerprint(with_empty))
        with self.assertRaises(UserError):
            validate_draft({**legacy, 'items': [{**legacy['items'][0], 'remarks': '가' * 201}]})

    def test_many_items_and_long_notes_are_paginated_without_losing_any_text_or_rows(self):
        draft = copy.deepcopy(self.draft)
        draft['items'] = [dict(species=f'묘목 {index}', specification='', quantity=1, unitPrice=1000,
            unitCost=0, remarks='3년생 / 키 80cm / 포트묘 ' * 5) for index in range(50)]
        draft.update(note='판매자 요청사항을 빠짐없이 전달합니다. ' * 35, isPrepaid=True, shippingFee=6000)
        plan = official_quote_page_plan(draft)
        rows = [values for page in plan for values, height in page['rows']]
        self.assertEqual([item['species'] for item in draft['items']] + ['운반비'], [row[0] for row in rows])
        self.assertEqual(draft['items'][-1]['remarks'], rows[-2][6])
        foot = plan[0]['note']
        rest = ''.join(page['continuation'] for page in plan)
        self.assertEqual(('요청사항 : ' + draft['note'].strip()).replace('\n', ''), (foot + rest).replace('\n', ''))
        images = render_official_quote_images(draft)
        self.assertGreater(len(images), 1)
        with tempfile.TemporaryDirectory() as directory:
            path = write_temporary_quote_pdf(images, Path(directory) / 'quote.pdf')
            document = QPdfDocument()
            document.load(str(path))
            self.assertEqual(len(images), document.pageCount())
            document.close()

    def test_rendered_remarks_and_request_text_change_their_expected_document_regions(self):
        before = render_official_quote_image(self.draft)
        draft = copy.deepcopy(self.draft)
        draft['items'][0]['remarks'] = '3년생 / 키 80cm'
        draft['note'] = '판매자가 작성한 배송 안내'
        after = render_official_quote_image(draft)
        old_remarks, new_remarks = before.copy(714, 313, 190, 30), after.copy(714, 313, 190, 30)
        old_note, new_note = before.copy(228, 1205, 670, 60), after.copy(228, 1205, 670, 60)
        self.assertNotEqual(bytes(old_remarks.bits()), bytes(new_remarks.bits()))
        self.assertNotEqual(bytes(old_note.bits()), bytes(new_note.bits()))
