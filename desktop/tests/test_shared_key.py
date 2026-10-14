"""Connection unit tests use fake responses only; no Google, order, SMS or mail calls."""
import copy
import io
import json
import unittest
from namufarm_desktop.api import ScriptApi, DEFAULT_URL
from namufarm_desktop.errors import UserError
from namufarm_desktop.service import Manager
from namufarm_desktop.connection import connection_input

KEY='b'*64
TOKEN='d'*64

class FakeOpener:
    def __init__(self): self.requests=[]
    def open(self, request, timeout):
        self.requests.append(json.loads(request.data))
        return io.BytesIO(b'{"ok":true}')

class FakeVault:
    def __init__(self):
        self.value={'deviceToken':TOKEN,'paired':True,'url':DEFAULT_URL}
        self.records={'customers':['existing-customer'],'quotes':['existing-quote'],'orders':['existing-order']}
        self.preferences={'drafts':['existing-draft']}
        self.fail_save=False
    def rows(self, name): return copy.deepcopy(self.records.get(name, []))
    def preference(self, name, default=None): return copy.deepcopy(self.preferences.get(name, default))
    def pending(self): return []
    def credentials(self): return copy.deepcopy(self.value)
    def save_credentials(self,value):
        if self.fail_save: raise UserError('local write failed')
        self.value=copy.deepcopy(value)

class FakeApi:
    def __init__(self,url,token,mode='shared-key-v1'):
        self.url=url; self.token=token; self.connection_key=''; self.mode=mode; self.calls=[]
    def info(self):
        return {'version':5,'connectionMode':'device-auth','customerDataReady':True,
                'requestDeletionSync':True,'connectionFile':True}
    def call(self,action,**fields):
        self.calls.append((action,fields))
        if fields.get('connectionKey')!=KEY: raise UserError('wrong key')
        return {'paired':True, **({'authMode':self.mode} if self.mode else {})}

class SharedKeyTest(unittest.TestCase):
    def test_manual_credentials_accept_paste_and_reject_files_and_unsafe_urls(self):
        self.assertEqual(connection_input(' '+DEFAULT_URL+' ', ' '+KEY+'\n'), (DEFAULT_URL, KEY))
        for password in ['', 'a'*63, 'A'*64, '{"connectionKey":"'+KEY+'"}', KEY.encode()]:
            with self.assertRaises(UserError): connection_input(DEFAULT_URL, password)
        for url in ['http://script.google.com/macros/s/AKunsafeBackend01234567890/exec',
                    'https://example.com/exec', DEFAULT_URL+'?key=secret', DEFAULT_URL+'#secret', None]:
            with self.assertRaises(UserError): connection_input(url, KEY)

    def test_all_authenticated_actions_use_common_key_without_device_registration(self):
        opener=FakeOpener(); api=ScriptApi(token='',connection_key=KEY,opener=opener)
        for action in ['snapshot_requests','save_quote_pricing','update_request_status','list_sales',
                       'save_sale','hide_sale','sales_report','email_profile','sms_profile','register_push']:
            api.call(action,deviceToken=TOKEN,connectionKey='wrong',eventId='unchanged-id')
            payload=opener.requests[-1]
            self.assertEqual(payload['connectionKey'],KEY)
            self.assertNotIn('deviceToken',payload)
            self.assertEqual(payload['eventId'],'unchanged-id')

    def test_common_key_cannot_be_sent_to_a_changed_url(self):
        opener=FakeOpener(); api=ScriptApi(connection_key=KEY,opener=opener)
        api.url='https://script.google.com/macros/s/AKotherBackend01234567890/exec'
        with self.assertRaises(UserError): api.call('list_sales')
        self.assertEqual(opener.requests,[])

    def test_legacy_token_calls_remain_compatible(self):
        opener=FakeOpener(); ScriptApi(token=TOKEN,opener=opener).call('list_requests')
        self.assertEqual(opener.requests[0]['deviceToken'],TOKEN)
        self.assertNotIn('connectionKey',opener.requests[0])

    def test_confirmed_mode_is_saved_and_reopens_without_changing_local_records(self):
        vault=FakeVault(); before=(copy.deepcopy(vault.records),copy.deepcopy(vault.preferences))
        manager=Manager(vault); manager.sync=lambda: {'local':'unchanged'}
        self.assertEqual(manager.connect_key(KEY,api_factory=FakeApi),{'local':'unchanged'})
        self.assertEqual(vault.value['connectionKey'],KEY)
        self.assertEqual(vault.value['deviceToken'],TOKEN)
        self.assertEqual(Manager(vault).api.connection_key,KEY)
        self.assertEqual((vault.records,vault.preferences),before)

    def test_unconfirmed_auth_mode_preserves_previous_credentials_and_api(self):
        vault=FakeVault(); manager=Manager(vault); manager.sync=lambda: None
        before=vault.credentials(); previous=manager.api
        manager.request_snapshot=True
        for mode in [None, 'unknown-mode']:
            with self.assertRaises(UserError):
                manager.connect_key(KEY,api_factory=lambda u,t: FakeApi(u,t,mode))
            self.assertEqual(vault.credentials(),before)
            self.assertIs(manager.api,previous)
            self.assertTrue(manager.request_snapshot)

    def test_reopen_cannot_move_the_shared_key_to_another_server(self):
        vault=FakeVault()
        vault.value.update(connectionKey=KEY,authMode='shared-key-v1',
                           connectionKeyUrl='https://script.google.com/macros/s/AKdifferentBackend01234567890/exec')
        self.assertEqual(Manager(vault).api.connection_key,'')

    def test_bad_key_or_failed_local_save_preserves_previous_connection(self):
        for bad_key,fail_save in [(True,False),(False,True)]:
            vault=FakeVault(); vault.fail_save=fail_save; before=vault.credentials()
            manager=Manager(vault); previous=manager.api
            with self.assertRaises(UserError):
                manager.connect_key('a'*64 if bad_key else KEY,api_factory=FakeApi)
            self.assertEqual(vault.credentials(),before)
            self.assertIs(manager.api,previous)

    def test_failed_download_keeps_a_successfully_saved_connection(self):
        vault=FakeVault(); manager=Manager(vault)
        def failed_sync(): raise UserError('download failed')
        manager.sync=failed_sync
        with self.assertRaises(UserError): manager.connect_key(KEY,api_factory=FakeApi)
        self.assertEqual(vault.value['connectionKey'],KEY)
        self.assertEqual(Manager(vault).api.connection_key,KEY)

    def test_existing_records_prevent_switching_servers_before_network_access(self):
        vault=FakeVault(); before=vault.credentials(); manager=Manager(vault)
        calls=[]
        def factory(url,token): calls.append(url); return FakeApi(url,token)
        with self.assertRaises(UserError):
            manager.connect_key(KEY,'https://script.google.com/macros/s/AKotherBackend01234567890/exec',factory)
        self.assertEqual(calls,[])
        self.assertEqual(vault.credentials(),before)

    def test_saved_legacy_connection_still_opens_without_reentering_a_password(self):
        vault=FakeVault(); before=vault.credentials()
        manager=Manager(vault)
        self.assertTrue(manager.credentials['paired'])
        self.assertEqual(manager.api.token,TOKEN)
        self.assertEqual(vault.credentials(),before)

if __name__=='__main__': unittest.main()
