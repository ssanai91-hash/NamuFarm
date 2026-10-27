"""Catalog drift and category compatibility tests. Written only; not run for this change."""
from pathlib import Path
import re
import unittest

from namufarm_desktop.species_purposes import (PURPOSES, PURPOSE_LABELS, category_error,
    format_category, matches_purpose, normalize_species_name, parse_category, suggest_purpose_labels)


class SpeciesPurposesTest(unittest.TestCase):
    def test_python_and_android_catalogs_match_website_ids_labels_and_names(self):
        root = Path(__file__).resolve().parents[2]
        website = (root / 'website/lib/species-purposes.ts').read_text(encoding='utf-8')
        expected = re.findall(r'\{ id: "([^"]+)", label: "([^"]+)", title: "[^"]+", names: "([^"]+)" \}', website)
        self.assertEqual(6, len(expected))
        self.assertEqual(expected, [(p.id, p.label, p.names) for p in PURPOSES])
        android = (root / 'app/src/main/java/com/example/namufarm/data/SpeciesPurposes.kt').read_text(encoding='utf-8')
        self.assertEqual(expected, re.findall(r'SpeciesPurpose\("([^"]+)", "([^"]+)", "([^"]+)"\)', android))
        self.assertEqual(('열매', '울타리', '꽃', '그늘', '정원', '잔디'), PURPOSE_LABELS)

    def test_name_matching_ignores_spaces_underscores_and_one_tree_suffix(self):
        for left, right in [('에메랄드_그린', '에메랄드 그린'), ('홍가시', '홍가시나무'), ('석류나무', '석류')]:
            with self.subTest(name=left):
                self.assertEqual(normalize_species_name(left), normalize_species_name(right))
                self.assertEqual(suggest_purpose_labels(left), suggest_purpose_labels(right))
        self.assertEqual(('울타리', '정원'), suggest_purpose_labels(' 에메랄드그린 '))
        self.assertEqual((), suggest_purpose_labels('알 수 없는 묘목'))

    def test_storage_uses_fixed_order_and_retains_all_unknown_tokens(self):
        parsed = parse_category(' 정원, 생울타리 , 울타리, 유실수, 정원, 생울타리 ')
        self.assertEqual(('울타리', '정원'), parsed.selected_labels)
        self.assertEqual(('생울타리', '유실수', '생울타리'), parsed.legacy_values)
        self.assertEqual('울타리, 정원, 생울타리, 유실수, 생울타리',
                         format_category(reversed(parsed.selected_labels), parsed.legacy_values))

    def test_no_selection_is_empty_and_explicit_removal_only_removes_legacy(self):
        self.assertEqual('', format_category((), ()))
        self.assertEqual('꽃, 유실수', format_category(('꽃',), parse_category('생울타리, 유실수').legacy_values[1:]))

    def test_limit_matches_server_utf16_length_including_non_bmp_legacy_text(self):
        self.assertIsNone(category_error('가' * 100))
        self.assertEqual('분류가 너무 깁니다. 기존 값을 정리해주세요.', category_error('가' * 101))
        self.assertIsNone(category_error('\U00020000' * 50))
        self.assertIsNotNone(category_error('\U00020000' * 51))

    def test_newlines_are_not_saved(self):
        self.assertIsNotNone(category_error('유실수\n기타'))
        self.assertIsNotNone(category_error('유실수\r기타'))

    def test_filters_use_saved_labels_only(self):
        self.assertTrue(matches_purpose('정원, 울타리, 생울타리', '울타리'))
        self.assertFalse(matches_purpose('생울타리', '울타리'))
        self.assertFalse(matches_purpose('', '정원'))
        self.assertTrue(matches_purpose('생울타리', ''))
