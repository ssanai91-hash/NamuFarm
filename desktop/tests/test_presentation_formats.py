"""Display-only formatting with fixed Seoul clock and unchanged input records."""
from datetime import datetime
import unittest
from namufarm_desktop.domain import SEOUL
from namufarm_desktop.presentation import phone_text, date_text, phones_in_text, time_value

class PresentationFormatsTest(unittest.TestCase):
    def test_phone_formats_do_not_change_stored_values(self):
        raw = '01012345678'
        self.assertEqual('010-1234-5678', phone_text(raw))
        self.assertEqual('01012345678', raw)
        self.assertEqual('이름 · 010-1234-5678', phones_in_text('이름 · 01012345678'))
        self.assertEqual('02-1234-5678', phone_text('0212345678'))
    def test_relative_dates_keep_full_original_available_to_caller(self):
        now = datetime(2026, 10, 27, 15, tzinfo=SEOUL)
        for raw, expected in [('2026-10-27T10:25:00+09:00', '오늘 10:25'),
                              ('2026-10-26T10:25:00+09:00', '어제 10:25'),
                              ('2026-10-24T10:25:00+09:00', '10.24'),
                              ('2025-12-30T10:25:00+09:00', '25.12.30')]:
            self.assertEqual(expected, date_text(raw, now=now))
            self.assertEqual(raw, time_value(None, 0, raw))
