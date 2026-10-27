"""Presentation contract only. Do not execute during this change request."""
from namufarm_desktop.ui_theme import TOKENS, status_badge, workspace_style
from qt_support import QtTestCase

class DesignTokensTest(QtTestCase):
    def test_legacy_stage_names_keep_display_aliases_without_changing_source(self):
        self.assertEqual('입금확인', status_badge('구매확정')['text'])
        self.assertEqual('배송완료', status_badge('판매완료')['text'])
        self.assertEqual('배송확인', status_badge('배송확인')['text'])
        self.assertEqual(TOKENS['done-bg'], status_badge('배송확인')['background'])
    def test_combo_has_explicit_subcontrols_and_compact_keeps_readable_type(self):
        css = workspace_style(False, True)
        self.assertIn('QComboBox::drop-down', css)
        self.assertIn('QComboBox::down-arrow', css)
        self.assertIn('font-size:15px', css)
        self.assertIn('font-size:13px', css)
        self.assertNotIn('@', css)
        self.assertIn('font-size:17px', workspace_style(True, True))
