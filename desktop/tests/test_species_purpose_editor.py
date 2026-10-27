"""UI behavior tests authored without executing the app or any tests."""
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')

from namufarm_desktop.nursery_ui import SpeciesPurposeEditor
from qt_support import QtTestCase


class SpeciesPurposeEditorTest(QtTestCase):
    def test_blank_category_has_unsaved_suggestions_and_notice(self):
        editor = SpeciesPurposeEditor('', '에메랄드_그린')
        self.assertEqual('울타리, 정원', editor.value())
        self.assertFalse(editor.hint.isHidden())
        self.assertEqual(6, len(editor.buttons))

    def test_existing_value_does_not_get_name_based_defaults(self):
        editor = SpeciesPurposeEditor('생울타리, 유실수', '에메랄드그린')
        self.assertEqual('생울타리, 유실수', editor.value())
        self.assertTrue(editor.hint.isHidden())
        editor.buttons['정원'].click()
        self.assertEqual('정원, 생울타리, 유실수', editor.value())
        editor.clear_legacy.click()
        self.assertEqual('정원', editor.value())

    def test_new_name_recalculates_until_first_manual_choice_even_if_all_cleared(self):
        editor = SpeciesPurposeEditor(new_species=True)
        editor.update_name('홍가시나무')
        self.assertEqual('울타리, 정원', editor.value())
        editor.update_name('석류')
        self.assertEqual('열매', editor.value())
        editor.buttons['열매'].click()
        editor.update_name('꽃잔디')
        self.assertEqual('', editor.value())
        self.assertTrue(editor.hint.isHidden())

    def test_existing_blank_category_is_only_suggested_when_opened(self):
        editor = SpeciesPurposeEditor('', '석류')
        editor.update_name('꽃잔디')
        self.assertEqual('열매', editor.value())

    def test_overlong_legacy_value_is_preserved_and_blocked_until_removed(self):
        editor = SpeciesPurposeEditor('가' * 100, '석류')
        editor.buttons['열매'].click()
        self.assertEqual(['가' * 100], editor.legacy_values)
        self.assertEqual('분류가 너무 깁니다. 기존 값을 정리해주세요.', editor.error())
        with self.assertRaises(ValueError):
            editor.value()
        editor.clear_legacy.click()
        self.assertEqual('열매', editor.value())
