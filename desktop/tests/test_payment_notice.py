"""Message generation only: no customer data, network calls or actual delivery."""
import unittest
from types import SimpleNamespace

from namufarm_desktop.commerce import new_draft, payment_account_text
from namufarm_desktop.workspace import QuoteSmsDialog, QuoteEmailDialog


class PaymentNoticeTest(unittest.TestCase):
    def setUp(self):
        self.draft = new_draft()
        self.draft.update(customerName='가상 고객', customerPhone='01012345678',
                          items=[dict(species='가상 묘목', specification='', quantity=2, unitPrice=2000, unitCost=0)])

    def messages(self):
        context = SimpleNamespace(draft=self.draft, quote_number=lambda: 'TEST-QUOTE')
        return [cls.default_message(context) for cls in (QuoteSmsDialog, QuoteEmailDialog)]

    def test_default_sms_and_email_include_same_account_and_holder_as_official_quote(self):
        self.assertEqual('농협 328-12-035671 오진웅', payment_account_text(self.draft))
        for message in self.messages():
            self.assertIn('입금계좌 : 농협 328-12-035671\n예금주 : 오진웅', message)
            self.assertEqual(1, message.count('328-12-035671'))
            self.assertEqual(1, message.count('예금주 : 오진웅'))
            self.assertIn('총금액 : 4,000원', message)

    def test_custom_quote_account_and_holder_are_preserved_without_default_account(self):
        custom = '가상은행 000-000-000\n예금주 : 테스트농원'
        self.draft['paymentAccount'] = custom
        self.assertEqual(custom, payment_account_text(self.draft))
        for message in self.messages():
            self.assertIn(custom, message)
            self.assertNotIn('328-12-035671', message)
            self.assertNotIn('오진웅', message)

    def test_whitespace_uses_default_and_prepaid_total_remains_correct(self):
        self.draft.update(paymentAccount=' \n ', isPrepaid=True, shippingFee=6000)
        for message in self.messages():
            self.assertIn('예금주 : 오진웅', message)
            self.assertIn('총금액 : 10,000원', message)
            self.assertIn('택배비 : 선불 6,000원 (총금액 포함)', message)

    def test_email_html_contains_account_lines_and_escapes_custom_instructions(self):
        self.draft['paymentAccount'] = '가상은행 000-000\n예금주 : 농원 <테스트>&'
        plain = self.messages()[1]
        context = SimpleNamespace(plain_body=lambda: plain)
        html = QuoteEmailDialog.html_body(context)
        self.assertIn('가상은행 000-000<br>예금주 : 농원 &lt;테스트&gt;&amp;', html)
        self.assertNotIn('농원 <테스트>', html)


if __name__ == '__main__':
    unittest.main()
