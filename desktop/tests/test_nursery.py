"""Durability, lost replies, and concurrent nursery edits use a temporary real vault."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from namufarm_desktop.errors import ConflictError, NetworkError, UserError
from namufarm_desktop.nursery import NurseryStore, fields, initial, stock_key
from namufarm_desktop.store import Vault


class NurseryApi:
    url = 'https://example.invalid/nursery'

    def __init__(self):
        self.rows = {'farms': [], 'species': [], 'inventory': []}
        self.receipts, self.calls = {}, []
        self.offline = self.lose_reply = self.bad_reply = False
        self.inventory_version = 1
        self.revision = 0

    def call(self, action, **body):
        self.calls.append((action, copy.deepcopy(body)))
        if self.offline:
            raise NetworkError('offline')
        if action.startswith('list_'):
            kind = action[5:]
            return {kind: copy.deepcopy(self.rows[kind]), 'inventoryVersioning': self.inventory_version}
        operation = body.get('operationId')
        if operation in self.receipts:
            return copy.deepcopy(self.receipts[operation])
        if action == 'delete_farm':
            self.rows['farms'] = [r for r in self.rows['farms'] if r['farmId'] != body['farmId']]
            result = {'deleted': True, 'farmId': body['farmId']}
        else:
            kind, field, result_field, number = {
                'save_farm': ('farms', 'farm', 'farm', 'farmId'),
                'save_species': ('species', 'species', 'speciesRecord', 'speciesId'),
                'save_inventory': ('inventory', 'inventory', 'inventoryRecord', None),
            }[action]
            row = copy.deepcopy(body[field])
            old = next((r for r in self.rows[kind] if
                (r[number] == body[number] if number else stock_key(r) == stock_key(row))), None)
            if body['expectedVersion'] != (old or {}).get('version', ''):
                raise ConflictError('changed elsewhere')
            if number:
                row[number] = body[number] or max([r[number] for r in self.rows[kind]] + [0]) + 1
            self.revision += 1
            row.update(updatedAt=str(self.revision), version=hashlib.sha256(str(self.revision).encode()).hexdigest())
            if old:
                self.rows[kind].remove(old)
            self.rows[kind].append(row)
            result = {'saved': True, result_field: row, 'operationId': operation}
        self.receipts[operation] = copy.deepcopy(result)
        if self.lose_reply:
            self.lose_reply = False
            raise NetworkError('reply lost after commit')
        if self.bad_reply:
            result['operationId'] = 'wrong reply'
        return copy.deepcopy(result)


class NurseryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        protector = SimpleNamespace(protect=lambda b: b'test:' + b, unprotect=lambda b: b[5:])
        self.vault = Vault.create_device(Path(self.tmp.name) / 'vault.db', protector)
        self.api = NurseryApi()
        self.manager = SimpleNamespace(vault=self.vault, api=self.api, credentials={'paired': True})
        self.store = NurseryStore(self.manager)

    def parents(self):
        farm = self.store.save('farms', {'farmName': '이원농원', 'phone': '010-1234-5678'})
        species = self.store.save('species', {'speciesName': '능소화'})
        return farm, species

    def inventory(self, farm, species, quantity=5):
        return {'farmKey': farm, 'speciesKey': species, 'quality': '상묘', 'age': '1년생',
                'size': '1m', 'plantType': '실생', 'quantity': quantity,
                'wholesalePrice': 2000, 'retailPrice': 3000}

    def test_offline_save_survives_restart_without_server_calls(self):
        self.api.offline = True
        farm, species = self.parents()
        self.store.save('inventory', self.inventory(farm, species))
        reopened = NurseryStore(SimpleNamespace(vault=Vault(self.vault.path, self.vault.secret),
            api=self.api, credentials={'paired': True})).read()
        self.assertEqual(3, len(reopened['pending']))
        self.assertEqual('이원농원', reopened['farms'][0]['farmName'])
        self.assertEqual([], self.api.calls)
        self.assertNotIn('이원농원'.encode(), self.vault.path.read_bytes())

    def test_parent_ids_are_acknowledged_before_queued_inventory(self):
        farm, species = self.parents()
        self.store.save('inventory', self.inventory(farm, species))
        self.assertEqual(3, self.store.sync())
        state = self.store.read()
        self.assertEqual([], state['pending'])
        stock = state['inventory'][0]
        self.assertEqual((farm, species), (stock['farmKey'], stock['speciesKey']))
        self.assertEqual((1, 1), (stock['farmId'], stock['speciesId']))
        self.assertEqual(['save_farm', 'save_species', 'save_inventory'],
            [a for a, _ in self.api.calls if a.startswith('save_')])

    def test_lost_reply_retries_identical_operation_after_restart(self):
        self.parents()
        self.api.lose_reply = True
        with self.assertRaises(NetworkError):
            self.store.sync()
        sent = copy.deepcopy(self.store.read()['pending'][0]['payload'])
        self.store = NurseryStore(self.manager)
        with self.assertRaises(UserError):
            row = self.store.read()['farms'][0]
            self.store.save('farms', {**row, 'memo': '아직 수정하면 안됨'}, row['_key'])
        self.store.sync()
        calls = [b for a, b in self.api.calls if a == 'save_farm']
        self.assertEqual([sent, sent], calls)
        self.assertEqual(1, len(self.api.rows['farms']))

    def test_conflict_keeps_pc_and_server_then_explicit_server_choice_resolves(self):
        self.parents(); self.store.sync()
        row = self.store.read()['farms'][0]
        self.store.save('farms', {**row, 'memo': 'PC 수정'}, row['_key'])
        self.api.rows['farms'][0].update(memo='휴대폰 수정', version='f' * 64)
        with self.assertRaises(UserError):
            self.store.sync()
        self.assertEqual('PC 수정', self.store.read()['farms'][0]['memo'])
        self.assertEqual('휴대폰 수정', self.api.rows['farms'][0]['memo'])
        preview = self.store.conflict_preview()
        self.store.use_server(preview)
        self.assertEqual('휴대폰 수정', self.store.read()['farms'][0]['memo'])
        self.assertEqual([], self.store.read()['pending'])

    def test_wrong_acknowledgement_keeps_durable_queue(self):
        self.parents(); self.api.bad_reply = True
        with self.assertRaises(UserError):
            self.store.sync()
        self.assertEqual(2, len(self.store.read()['pending']))
        self.assertEqual(0, self.store.read()['farms'][0]['farmId'])

    def test_older_inventory_server_does_not_drop_local_changes(self):
        farm, species = self.parents(); self.store.sync()
        self.store.save('inventory', self.inventory(farm, species))
        self.api.inventory_version = 0
        with self.assertRaisesRegex(UserError, 'InventoryManagement'):
            self.store.sync()
        self.assertEqual(1, len(self.store.read()['pending']))
        self.assertFalse(any(a == 'save_inventory' for a, _ in self.api.calls))

    def test_unsent_farm_can_be_removed_locally(self):
        farm = self.store.save('farms', {'farmName': '임시농원'})
        self.store.save('farms', self.store.read()['farms'][0], farm, delete=True)
        self.assertEqual([], self.store.read()['farms'])
        self.assertEqual([], self.store.read()['pending'])
        self.assertEqual([], self.api.calls)

    def test_farm_delete_waits_for_zero_stock_acknowledgement(self):
        farm, species = self.parents()
        self.store.save('inventory', self.inventory(farm, species)); self.store.sync()
        stock = self.store.read()['inventory'][0]
        self.store.save('inventory', {**stock, 'quantity': 0}, stock['_key'])
        with self.assertRaises(UserError):
            self.store.save('farms', self.store.read()['farms'][0], farm, delete=True)
        self.store.sync()
        self.store.save('farms', self.store.read()['farms'][0], farm, delete=True)
        self.store.sync()
        self.assertEqual([], self.store.read()['farms'])

    def test_incomplete_download_preserves_previous_cache(self):
        self.parents(); self.store.sync()
        previous = self.store.read()
        self.api.rows['species'].append({**self.api.rows['species'][0]})
        with self.assertRaises(UserError):
            self.store.sync()
        self.assertEqual(previous, self.store.read())

    def test_initials_and_numeric_validation(self):
        self.assertEqual(['ㄴ', 'ㄷ', 'ㄱ', 'ㅅ'], [initial(x) for x in ('능소화', '단풍나무', '꽃사과', '쑥')])
        for value in (-1, True, 2147483648):
            with self.assertRaises(UserError):
                fields('inventory', {'quantity': value, 'wholesalePrice': 1, 'retailPrice': 1})

    def test_species_is_not_disabled_while_linked_stock_is_waiting(self):
        farm, species = self.parents()
        self.store.save('inventory', self.inventory(farm, species))
        with self.assertRaises(UserError):
            self.store.save('species', {**self.store.read()['species'][0], 'isActive': 0}, species)
        self.assertEqual(1, self.store.read()['species'][0]['isActive'])
        self.store.sync()
        row = self.store.read()['inventory'][0]
        self.store.save('inventory', {**row, 'quantity': 0}, row['_key'])
        self.store.sync()
        self.store.save('species', {**self.store.read()['species'][0], 'isActive': 0}, species)
        self.store.sync()
        self.assertEqual(0, self.store.read()['species'][0]['isActive'])

    def test_local_failure_after_server_commit_preserves_retry_until_local_ack_commits(self):
        self.store.save('farms', {'farmName': '이원농원'})
        save = self.vault.save_preference
        def fail_ack(name, value):
            if not value['pending'] and self.api.rows['farms']:
                raise OSError('disk unavailable after server acknowledgement')
            return save(name, value)
        with patch.object(self.vault, 'save_preference', side_effect=fail_ack):
            with self.assertRaises(OSError):
                self.store.sync()
        self.assertEqual(1, len(self.store.read()['pending']))
        self.assertEqual(0, self.store.read()['farms'][0]['farmId'])
        self.store.sync()
        self.assertEqual([], self.store.read()['pending'])
        self.assertEqual(1, len(self.api.rows['farms']))
