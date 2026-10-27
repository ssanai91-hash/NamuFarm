"""Display categories never rewrite persisted business states."""
import copy
import unittest
from namufarm_desktop.presentation import order_stage

class OrderStageViewTest(unittest.TestCase):
    def test_terminal_and_unknown_states_are_preserved_as_display_categories(self):
        for state, expected in [('구매확정', '입금확인'), ('판매완료', '배송완료'), ('주문취소', '구매취소'), ('배송확인', '배송확인')]:
            row = {'metadata': {'status': state}, 'private': {'order': {}}}
            before = copy.deepcopy(row)
            self.assertEqual(expected, order_stage(row))
            self.assertEqual(before, row)
