"""Connection unit tests use fake responses only; no Google, order, SMS or mail calls."""
import copy
import io
import json
import unittest
from namufarm_desktop.api import ScriptApi, DEFAULT_URL
from namufarm_desktop.errors import UserError
from namufarm_desktop.service import Manager

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

def connection(key=KEY):
    return json.dumps({'format':'namufarm-connection','version':1,'url':DEFAULT_URL,'connectionKey':key}).encode()

class SharedKeyTest(unittest.TestCase):
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
        self.assertEqual(manager.connect_file(connection(),FakeApi),{'local':'unchanged'})
        self.assertEqual(vault.value['connectionKey'],KEY)
        self.assertEqual(vault.value['deviceToken'],TOKEN)
        self.assertEqual(Manager(vault).api.connection_key,KEY)
        self.assertEqual((vault.records,vault.preferences),before)

    def test_old_server_reply_retains_legacy_mode(self):
        vault=FakeVault(); manager=Manager(vault); manager.sync=lambda: None
        manager.connect_file(connection(),lambda u,t: FakeApi(u,t,None))
        self.assertNotIn('connectionKey',vault.value)
        self.assertEqual(manager.api.connection_key,'')
        self.assertEqual(vault.value['deviceToken'],TOKEN)

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
                manager.connect_file(connection('a'*64 if bad_key else KEY),FakeApi)
            self.assertEqual(vault.credentials(),before)
            self.assertIs(manager.api,previous)

if __name__=='__main__': unittest.main()
