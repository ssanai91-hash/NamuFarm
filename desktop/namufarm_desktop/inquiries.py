"""Private inquiry cache, independent cursor/drafts and explicit, retry-safe delivery."""
import copy
import json
import re
import uuid
from datetime import datetime, timezone, timedelta

from .domain import SEOUL, require
from .errors import UserError, AuthenticationError, ENROLLMENT_NOTICE
from .request_excel import _write_copy

UUID = re.compile(r'[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}')
DELIVERY_LABELS = {'sending': '발송 확인 필요', 'pending': '문자 결과 대기', 'unknown': '발송 결과 불명',
                   'sent': '메일 발송 완료', 'success': '문자 전달 완료', 'failed': '문자 전송 실패'}


def iso_date(value):
    require(isinstance(value, str) and len(value) <= 40, '문의 날짜가 올바르지 않습니다.')
    try:
        date = datetime.fromisoformat(value.replace('Z', '+00:00'))
        require(date.tzinfo is not None, '문의 시간대가 없습니다.')
        return date
    except ValueError:
        raise UserError('문의 날짜가 올바르지 않습니다.') from None


def local_time(value):
    return iso_date(value).astimezone(SEOUL).strftime('%Y-%m-%d %H:%M') if value else ''


def validate_row(value):
    require(isinstance(value, dict), '문의 응답을 확인할 수 없습니다.')
    r = copy.deepcopy(value)
    require(r.get('kind') == 'customer_inquiry' and isinstance(r.get('requestId'), str)
            and UUID.fullmatch(r['requestId']) and
            re.fullmatch(r'INQ-20\d{6}-\d{4,}', r.get('inquiryNumber', '')), '문의 번호를 확인할 수 없습니다.')
    for key in ('sequence', 'revision'):
        require(type(r.get(key)) is int and r[key] > 0, '문의 변경 정보를 확인할 수 없습니다.')
    iso_date(r.get('createdAt')); iso_date(r.get('updatedAt'))
    r['status'] = {'답변 대기': 'open', '답변 완료': 'closed', '보관종료': 'closed'}.get(r.get('status'), r.get('status'))
    require(r['status'] in ('open', 'closed'), '문의 상태를 확인할 수 없습니다.')
    if r.get('expired') is True:
        result = {k: r[k] for k in ('kind', 'requestId', 'inquiryNumber', 'sequence', 'revision', 'createdAt', 'updatedAt', 'status', 'expired')}
        if r.get('_localExpiry'):
            result['_localExpiry'] = True
        if r.get('deleted') is True:  # 관리자 삭제 표시(보관 종료와 구분)
            result['deleted'] = True
        return result
    q, reply = r.get('inquiry'), r.get('reply')
    require(isinstance(q, dict) and q.get('requestId') == r['requestId'] and isinstance(reply, dict), '문의 원문을 확인할 수 없습니다.')
    for key, limit in (('customerName', 50), ('customerPhone', 20), ('customerEmail', 254), ('category', 20), ('message', 2000)):
        require(isinstance(q.get(key), str) and len(q[key]) <= limit, '문의 내용이 올바르지 않습니다.')
    require(q['category'] in ('묘목·재배', '구매·배송', '기타') and re.fullmatch(r'0\d{8,10}', q['customerPhone']), '문의 연락처가 올바르지 않습니다.')
    require(isinstance(reply.get('text'), str) and len(reply['text']) <= 2000 and reply.get('channel') in ('sms', 'email'), '답변을 확인할 수 없습니다.')
    d = r.get('delivery')
    require(d is None or isinstance(d, dict) and isinstance(d.get('id'), str) and UUID.fullmatch(d['id'])
            and d.get('channel') in ('sms', 'email') and d.get('state') in DELIVERY_LABELS, '문의 발송 결과를 확인할 수 없습니다.')
    require(isinstance(r.get('history', []), list), '답변 이력을 확인할 수 없습니다.')
    if r.get('answeredAt'):
        iso_date(r['answeredAt'])
    return r


class InquiryStore:
    """Additive encrypted tables; quote snapshot replacement never touches these rows."""
    def __init__(self, vault):
        self.vault = vault
        with vault.mutex, vault._db() as db:
            for name in ('records', 'drafts', 'operations', 'meta'):
                db.execute(f'CREATE TABLE IF NOT EXISTS inquiry_{name} (id TEXT PRIMARY KEY, body BLOB NOT NULL)')

    def _read(self, db, table, identifier):
        key = self.vault._index('inquiry_' + table, identifier)
        row = db.execute(f'SELECT body FROM inquiry_{table} WHERE id=?', (key,)).fetchone()
        return self.vault._open(row[0], 'inquiry_' + table + ':' + key) if row else None

    def _write(self, db, table, identifier, value):
        key = self.vault._index('inquiry_' + table, identifier)
        if value is None:
            db.execute(f'DELETE FROM inquiry_{table} WHERE id=?', (key,))
        else:
            db.execute(f'INSERT OR REPLACE INTO inquiry_{table} VALUES (?,?)',
                       (key, self.vault._seal(value, 'inquiry_' + table + ':' + key)))

    def read(self, table, identifier):
        with self.vault.mutex, self.vault._db() as db:
            return self._read(db, table, identifier)

    def write(self, table, identifier, value):
        require(table in ('drafts', 'operations', 'meta'), '문의 보관 위치가 올바르지 않습니다.')
        with self.vault.mutex, self.vault._db() as db:
            self._write(db, table, identifier, value)

    def rows(self):
        with self.vault.mutex, self.vault._db() as db:
            values = [self.vault._open(blob, 'inquiry_records:' + key)
                      for key, blob in db.execute('SELECT id,body FROM inquiry_records')]
            # Apply the server's retention rule even when this PC is offline.
            for r in values:
                if not r.get('expired') and r['status'] == 'closed' and r.get('answeredAt') and iso_date(r['answeredAt']) + timedelta(days=30) <= datetime.now(timezone.utc):
                    r['expired'] = True
                    r['_localExpiry'] = True
                    r = validate_row(r)
                    self._merge(db, r)
            return [self._read(db, 'records', r['requestId']) for r in values]

    def cursor(self):
        return (self.read('meta', 'cursor') or {}).get('value', 0)

    def _merge(self, db, row):
        identifier = row['requestId']
        previous = self._read(db, 'records', identifier)
        if previous and previous['revision'] > row['revision']:
            return
        if previous and previous.get('expired') and not row.get('expired'):
            # An old snapshot cannot undo expiry. A genuinely newer server reopen
            # may restore an inquiry that was purged only by the offline PC clock.
            if not (previous.get('_localExpiry') and row['revision'] > previous['revision'] and row['status'] == 'open'):
                return
        self._write(db, 'records', identifier, row)
        self._write(db, 'meta', 'export_due', {'value': True})
        if row.get('expired'):
            self._write(db, 'drafts', identifier, None)
            self._write(db, 'operations', identifier, None)

    def merge(self, rows, cursor=None):
        values = [validate_row(r) for r in rows]
        with self.vault.mutex, self.vault._db() as db:
            for r in values:
                self._merge(db, r)
            if cursor is not None:
                previous = (self._read(db, 'meta', 'cursor') or {}).get('value', 0)
                require(type(cursor) is int and cursor >= previous, '문의 순번이 이전 상태입니다.')
                self._write(db, 'meta', 'cursor', {'value': cursor})


class InquiryService:
    def __init__(self, manager):
        self.manager = manager
        self.store = InquiryStore(manager.vault)
        source = self.store.read('meta', 'server')
        require(not source or source['url'] == manager.api.url, '고객문의가 다른 서버에 연결되어 있습니다. 기존 보관함을 유지하고 연결 주소를 확인해주세요.')
        if not source:
            self.store.write('meta', 'server', {'url': manager.api.url})

    def ensure_connection(self):
        info = self.manager.server_info()
        require(info.get('customerInquiry') == 1 and info.get('customerInquiryReady') is True
                and info.get('customerInquiryWorkflow', 0) >= 2,
                '고객문의 서버를 최신 버전으로 재배포하고 setupCustomerInquiries를 실행해주세요.')
        if not self.manager.credentials.get('paired'):
            require(info.get('connectionAuth') == 'auto-v1', '연결 · 설정에서 서버에 먼저 연결해주세요.')
            try:
                result = self.manager.api.call('connect_app', authMode='auto-v1', label='나무팜 PC')
            except AuthenticationError as error:
                raise AuthenticationError(ENROLLMENT_NOTICE, code=error.code) from None
            require(result.get('paired') is True, 'PC 연결을 확인할 수 없습니다.')
            self.manager.credentials.update(paired=True, url=self.manager.api.url)
            self.manager.vault.save_credentials(self.manager.credentials)

    def sync(self):
        self.ensure_connection()
        cursor = self.store.cursor()
        for _ in range(10000):
            result = self.manager.api.call('list_customer_inquiries', afterSequence=cursor, limit=100)
            rows, next_cursor, more = result.get('inquiries'), result.get('nextSequence'), result.get('hasMore')
            require(isinstance(rows, list) and len(rows) <= 100 and type(next_cursor) is int and
                    type(more) is bool and next_cursor >= cursor, '문의 목록 응답을 확인할 수 없습니다.')
            rows = [validate_row(r) for r in rows]
            sequences = [r['sequence'] for r in rows]
            require(sequences == sorted(set(sequences)) and all(n > cursor for n in sequences) and
                    len({r['requestId'] for r in rows}) == len(rows) and
                    next_cursor == (sequences[-1] if rows else cursor) and (not more or bool(rows)),
                    '문의 페이지 순번이 올바르지 않습니다. 기존 자료를 유지합니다.')
            # Only a fully validated, committed page advances the independent cursor.
            self.store.merge(rows, next_cursor)
            cursor = next_cursor
            if not more:
                return self.export()
        raise UserError('문의가 많아 나누어 가져왔습니다. 불러오기를 다시 눌러주세요.')

    def row(self, identifier):
        self.store.rows()  # Enforce expiry before reading or sending.
        row = self.store.read('records', identifier)
        require(row and not row.get('expired'), '문의 보관 기간이 끝났거나 원문이 없습니다.')
        return row

    def draft(self, row):
        return self.store.read('drafts', row['requestId']) or {
            'text': row['reply']['text'], 'channel': row['reply']['channel'], 'baseRevision': row['revision']}

    def remember(self, identifier, text, channel=None):
        row = self.row(identifier)
        previous = self.draft(row)
        value = {**previous, 'text': text, 'channel': channel or previous['channel']}
        require(isinstance(text, str) and len(text) <= 2000 and value['channel'] in ('sms', 'email'), '답변은 2,000자 이내로 입력해주세요.')
        self.store.write('drafts', identifier, value)

    def export(self):
        rows = self.store.rows()
        groups = {}
        for r in rows:
            year = iso_date(r['createdAt']).astimezone(SEOUL).year
            groups.setdefault(year, []).append(r)
        if not groups:
            groups[datetime.now(SEOUL).year] = []
        paths, errors = [], []
        for year, values in sorted(groups.items()):
            path = self.manager.vault.path.with_name(f'나무팜-고객문의-{year}.xlsx')
            content = [['문의접수번호', '접수시각(한국)', '문의종류', '이름', '연락처', '이메일', '문의내용',
                        '상태', '답변내용', '답변방법', '발송상태', '처리시각(한국)', '로컬답변초안', '답변·처리이력']]
            for r in sorted(values, key=lambda x: x['createdAt'], reverse=True):
                if r.get('deleted'):
                    continue  # 관리자 삭제 문의는 엑셀에 남기지 않음(해당 연도 파일은 다시 써서 지워짐)
                q, reply, d = r.get('inquiry', {}), r.get('reply', {}), r.get('delivery') or {}
                draft = self.store.read('drafts', r['requestId']) or {}
                content.append([r['inquiryNumber'], local_time(r['createdAt']), q.get('category', ''), q.get('customerName', ''),
                                q.get('customerPhone', ''), q.get('customerEmail', ''), q.get('message', ''), r['status'],
                                reply.get('text', ''), {'sms': '문자', 'email': '메일'}.get(reply.get('channel'), ''),
                                '보관 종료' if r.get('expired') else DELIVERY_LABELS.get(d.get('state'), ''),
                                local_time(r.get('answeredAt')), draft.get('text', ''),
                                json.dumps(r.get('history', []), ensure_ascii=False) if r.get('history') else ''])
            try:
                self.manager._write_copy_if_changed(path, content,
                    lambda: _write_copy(path, content, '고객문의'))
                paths.append(str(path))
            except OSError:
                errors.append(f'{path.name}: 엑셀을 닫고 엑셀 저장을 다시 눌러주세요. DB와 답변은 저장되어 있습니다.')
        if not errors:
            self.store.write('meta', 'export_due', {'value': False})
        return {'paths': paths, 'copyError': '\n'.join(errors), 'count': sum(not r.get('expired') for r in rows)}

    def _ack(self, identifier, result):
        r = validate_row(result.get('inquiry'))
        require(r['requestId'] == identifier, '다른 문의 응답입니다. 전송 기록을 보존합니다.')
        before = self.store.read('records', identifier)
        draft = self.store.read('drafts', identifier)
        self.store.merge([r])  # Does NOT advance the global delta cursor.
        if not r.get('expired') and before and draft and draft['baseRevision'] == before['revision'] and draft['text'].strip() == r['reply']['text'] and draft['channel'] == r['reply']['channel']:
            self.store.write('drafts', identifier, {**draft, 'text': r['reply']['text'], 'baseRevision': r['revision']})
        return r

    def _execute(self, identifier, operation):
        result = self.manager.api.call(operation['action'], **operation['payload'])
        r = self._ack(identifier, result)
        action, p = operation['action'], operation['payload']
        if action == 'save_inquiry_reply':
            require(r.get('lastEventId') == p['eventId'] and r['reply'] == {'text': p['replyText'], 'channel': p['replyChannel']},
                    '답변 저장을 확인하지 못했습니다. 같은 저장 기록으로 다시 확인해주세요.')
            current = self.store.read('drafts', identifier)
            if current and current['text'] == p['replyText'] and current['channel'] == p['replyChannel']:
                self.store.write('drafts', identifier, {'text': current['text'], 'channel': current['channel'], 'baseRevision': r['revision']})
        elif action == 'send_inquiry_reply':
            require((r.get('delivery') or {}).get('id') == p['deliveryId'] or any(h.get('kind') == 'reply' and h.get('id') == p['deliveryId'] for h in r.get('history', [])), '발송 결과를 확인하지 못했습니다. 같은 전송 기록을 유지합니다.')
        elif action == 'set_inquiry_status':
            require(r['status'] == p['status'] and any(h.get('id') == p['eventId'] for h in r.get('history', [])),
                    '문의 처리 상태를 확인하지 못했습니다.')
        self.store.write('operations', identifier, None)
        return r

    def save_reply(self, identifier, rebase=False):
        self.ensure_connection()
        row, pending = self.row(identifier), self.store.read('operations', identifier)
        draft = self.draft(row)
        if rebase:
            require(not pending or pending['action'] == 'save_inquiry_reply', '미확인 발송 결과를 먼저 확인해주세요.')
            draft['baseRevision'] = row['revision']
            self.store.write('drafts', identifier, draft)
            self.store.write('operations', identifier, None)
            pending = None
        if pending:
            require(pending['action'] == 'save_inquiry_reply', '이전 발송·상태 변경 결과를 먼저 확인해주세요.')
            # Retry the persisted event, never a newly edited payload with the same ID.
            self._execute(identifier, pending)
            row, draft = self.row(identifier), self.draft(self.row(identifier))
        draft['text'] = draft['text'].strip()
        self.store.write('drafts', identifier, draft)
        require(draft['baseRevision'] == row['revision'], '다른 기기에서 수정했습니다. 불러온 원문·답변을 확인 후 최신 기준 저장을 눌러주세요.')
        require(draft['channel'] != 'sms' or len(draft['text']) <= 900, '문자 답변은 900자 이내로 입력해주세요. 긴 답변은 메일로 보내주세요.')
        operation = {'action': 'save_inquiry_reply', 'payload': {'requestId': identifier, 'eventId': str(uuid.uuid4()),
            'revision': row['revision'], 'replyText': draft['text'].strip(), 'replyChannel': draft['channel']}}
        self.store.write('operations', identifier, operation)
        self._execute(identifier, operation)
        return self.export()

    def send(self, identifier, channel):
        row = self.row(identifier)
        pending = self.store.read('operations', identifier)
        require(not pending, '이전 작업의 결과 확인을 먼저 눌러주세요.')
        d = row.get('delivery') or {}
        require(d.get('state') not in ('sending', 'pending', 'unknown'), '발송 결과가 확정되지 않았습니다. 결과 확인을 눌러주세요.')
        draft = self.draft(row)
        require(draft['text'].strip(), '답변을 입력해주세요.')
        require(channel != 'email' or row['inquiry']['customerEmail'], '고객이 이메일을 남기지 않았습니다.')
        self.remember(identifier, draft['text'], channel)
        self.save_reply(identifier)
        row = self.row(identifier)
        operation = {'action': 'send_inquiry_reply', 'payload': {'requestId': identifier, 'revision': row['revision'], 'deliveryId': str(uuid.uuid4())}}
        # Persist BEFORE the external request. Timeout/restart retries exactly this ID.
        self.store.write('operations', identifier, operation)
        self._execute(identifier, operation)
        return self.export()

    def check(self, identifier):
        self.ensure_connection()
        row = self.row(identifier)
        pending = self.store.read('operations', identifier)
        if pending:
            row = self._execute(identifier, pending)
        d = row.get('delivery') or {}
        if d.get('channel') == 'sms' and d.get('messageId') and d.get('state') not in ('success', 'failed'):
            self._ack(identifier, self.manager.api.call('inquiry_sms_status', requestId=identifier))
        return self.export()

    def set_status(self, identifier, status, note):
        require(status in ('open', 'closed') and isinstance(note, str) and 0 < len(note.strip()) <= 500, '처리 메모는 1~500자로 입력해주세요.')
        self.ensure_connection()
        row = self.row(identifier)
        require(not self.store.read('operations', identifier), '이전 작업의 결과를 먼저 확인해주세요.')
        operation = {'action': 'set_inquiry_status', 'payload': {'requestId': identifier, 'revision': row['revision'],
                      'eventId': str(uuid.uuid4()), 'status': status, 'note': note.strip()}}
        self.store.write('operations', identifier, operation)
        self._execute(identifier, operation)
        return self.export()


