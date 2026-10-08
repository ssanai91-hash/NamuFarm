"""Verify a synthetic backup exported by the real Android SellerKeys test."""
import json
import sys
from pathlib import Path

from namufarm_desktop.crypto import SellerKey


if __name__ == '__main__':
    fixture = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
    key = SellerKey.from_backup(fixture['backup'], fixture['password'])
    value = key.open(fixture['encrypted'], 'quote', fixture['reference'])
    assert value == {'customerName': '김나무', 'customerPhone': '01012345678'}
    print('Actual Android backup restored and decrypted by Python desktop code.')
