import copy
import json
import subprocess
from pathlib import Path

from namufarm_desktop.api import DEFAULT_URL
from namufarm_desktop.crypto import SellerKey
from namufarm_desktop.errors import ConflictError, NetworkError, UserError
from namufarm_desktop.service import Manager
from namufarm_desktop.store import Vault

PASSWORD = 'test-password-12345'


class Bridge:
    def __init__(self):
        self.process = subprocess.Popen(['node', str(Path(__file__).with_name('server_bridge.cjs'))],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8')
        self.initial = self.send(control='init')

    def send(self, **payload):
        self.process.stdin.write(json.dumps(payload, ensure_ascii=False) + '\n')
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            raise RuntimeError('Fixture stopped')
        return json.loads(line)

    def close(self):
        self.process.stdin.close()
        self.process.wait(timeout=15)
        self.process.stdout.close()
        self.process.stderr.close()


class TestApi:
    def __init__(self, bridge):
        self.bridge = bridge
        self.url, self.token = DEFAULT_URL, ''
        self.offline = False
        self.lose_ack = False
        self.bad_ack = False
        self.calls = []
        self.info_calls = 0
        self.page_filter = None
        self.info_filter = None

    def info(self):
        self.info_calls += 1
        if self.offline:
            raise NetworkError('offline')
        result = self.bridge.send(control='info')
        return self.info_filter(result) if self.info_filter else result

    def call(self, action, **fields):
        self.calls.append(action)
        if self.offline:
            raise NetworkError('offline')
        result = self.bridge.send(action=action, deviceToken=self.token, **fields)
        if result.get('ok') is not True:
            if result.get('code') == 'CONFLICT' or (result.get('code') == 'INVALID' and action == 'save_sale'):
                raise ConflictError('conflict')
            raise UserError('server failed')
        if action in ('save_sale', 'close_request') and self.lose_ack:
            self.lose_ack = False
            raise NetworkError('lost response')
        if action in ('save_sale', 'close_request') and self.bad_ack:
            self.bad_ack = False
            if action == 'save_sale':
                result['fingerprint'] = '0' * 64
            else:
                result['requestId'] = 'wrong-id'
        if self.page_filter and action in ('list_requests', 'list_sales'):
            result = self.page_filter(action, copy.deepcopy(result))
        return result


def manager_at(path, bridge):
    seller = SellerKey.from_backup(bridge.initial['backup'], PASSWORD)
    vault = Vault.create(path, PASSWORD, seller)
    api = TestApi(bridge)
    api.token = vault.credentials()['deviceToken']
    manager = Manager(vault, api)
    manager.pair(bridge.send(control='invite')['invite'])
    return manager
