"""Synthetic SQLite snapshots only; not executed by the code-change workflow."""
import os
import tempfile
import unittest
from pathlib import Path
from namufarm_desktop.store import Vault
from test_simple_connection import TestAccount


class BackupRetentionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.vault = Vault.create_device(Path(self.tmp.name) / 'local.db', TestAccount())

    def test_thirty_first_snapshot_removes_only_oldest_generated_file(self):
        folder = Path(self.tmp.name) / '백업'
        folder.mkdir()
        generated = []
        for index in range(30):
            path = folder / f'namufarm-20260101-000000-{index:06d}.db'
            path.write_bytes(b'fixture snapshot')
            os.utime(path, (index + 1, index + 1))
            generated.append(path)
        unrelated = folder / 'namufarm-manual.db'
        unrelated.write_bytes(b'keep')
        created = self.vault.backup('delete_sale')
        self.assertTrue(created.exists())
        self.assertFalse(generated[0].exists())
        self.assertTrue(all(path.exists() for path in generated[1:]))
        self.assertEqual(b'keep', unrelated.read_bytes())

    def test_download_within_hour_skips_but_destructive_operations_always_backup(self):
        first = self.vault.backup('download_sales', min_interval=3600)
        self.assertIsNone(self.vault.backup('download_sales', min_interval=3600))
        second = self.vault.backup('import_local')
        self.assertNotEqual(first, second)
        self.assertTrue(first.exists() and second.exists())
