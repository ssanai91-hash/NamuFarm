"""Customer inquiry inbox using the seller workspace's cards, filters and tables."""
from PySide6.QtCore import Qt, QUrl, QTimer
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (QWidget, QDialog, QVBoxLayout, QHBoxLayout, QLabel, QPushButton,
    QLineEdit, QComboBox, QFrame, QPlainTextEdit, QInputDialog, QMessageBox, QHeaderView)

from .app import table, fill_table, Task
from .errors import UserError
from .inquiries import InquiryService, DELIVERY_LABELS, local_time, sms_blocked
from .ui_theme import fit_window, scroll_dialog
from .ui_theme import align_table, StatusDelegate
from .presentation import phone_text, date_text
from .ui_icons import set_button_icon


def text_label(text='', name='', wrap=False):
    w = QLabel(text)
    w.setTextFormat(Qt.TextFormat.PlainText)
    w.setObjectName(name)
    w.setWordWrap(wrap)
    w.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
    return w


class InquiryPage(QWidget):
    def __init__(self, owner):
        super().__init__()
        self.owner, self.rows, self.limit = owner, [], 50
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        tools = QHBoxLayout()
        self.search = QLineEdit()
        self.search.setPlaceholderText('이름 · 전화 · 문의번호 · 내용 검색')
        self.search.setFixedWidth(340)
        self.search.setClearButtonEnabled(True)
        tools.addWidget(self.search, 1)
        self.filter = QComboBox()
        self.filter.addItems(['open · 미완료', 'closed · 완료', '전체'])
        self.filter.setMinimumWidth(160)
        tools.addWidget(self.filter)
        tools.addStretch()
        self.excel = QPushButton('엑셀 열기')
        self.export_button = QPushButton('엑셀 저장')
        self.load = QPushButton('불러오기')
        self.load.setObjectName('primary')
        set_button_icon(self.load, 'refresh')
        for w in (self.excel, self.export_button, self.load):
            tools.addWidget(w)
        root.addLayout(tools)
        self.summary = text_label('PC 자료를 열어주세요.', 'selectionHint', True)
        root.addWidget(self.summary)
        self.table = table(['접수번호', '접수일', '이름 · 연락처', '문의 종류', '문의 내용', '상태', '발송'])
        self.table.setWordWrap(False)
        self.table.setTextElideMode(Qt.TextElideMode.ElideRight)
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(4, QHeaderView.ResizeMode.Stretch)
        self.table.horizontalHeader().setSectionResizeMode(2, QHeaderView.ResizeMode.Interactive)
        self.table.setColumnWidth(2, 180)
        align_table(self.table)
        root.addWidget(self.table, 1)
        bottom = QHBoxLayout()
        self.hint = text_label('문의 선택 후 답변하기 · 미완료 문의는 모든 연도를 표시합니다.', 'selectionHint', True)
        bottom.addWidget(self.hint, 1)
        self.more = QPushButton('50건 더 보기')
        self.open_button = QPushButton('답변하기')
        self.open_button.setObjectName('primary')
        bottom.addWidget(self.more); bottom.addWidget(self.open_button)
        root.addLayout(bottom)
        self.search.textChanged.connect(self.reset_filter)
        self.filter.currentIndexChanged.connect(self.reset_filter)
        self.load.clicked.connect(self.sync)
        self.excel.clicked.connect(self.open_excel)
        self.export_button.clicked.connect(self.export)
        self.more.clicked.connect(self.load_more)
        self.open_button.clicked.connect(self.open_selected)
        self.table.itemDoubleClicked.connect(self.open_selected)
        self.table.itemSelectionChanged.connect(self.update_controls)

    def service(self):
        if getattr(self.owner, 'demo_mode', False):
            raise UserError('미리보기 모드에서는 고객문의를 불러오지 않습니다')
        if not self.owner.manager:
            raise UserError('PC 자료를 먼저 열어주세요.')
        return InquiryService(self.owner.manager)

    def reset_filter(self, *_):
        self.limit = 50
        self.refresh_local()

    def load_more(self):
        self.limit += 50
        self.refresh_local()

    def selected(self):
        i = self.table.currentRow()
        return self.rows[i] if 0 <= i < len(self.rows) else None

    def refresh_local(self):
        if getattr(self.owner, 'demo_mode', False):
            self.rows = []
            self._rendered = None
            self.table.setRowCount(0)
            message = '미리보기 모드에서는 고객문의를 불러오지 않습니다'
            self.table.set_empty_message(message)
            self.summary.setText(message)
            self.hint.setText(message)
            self.more.hide()
            if hasattr(self.owner, 'set_page_count'):
                self.owner.set_page_count(self.owner.PAGE_INQUIRIES, 0)
            self.update_controls()
            return
        previous = self.selected()
        query = self.search.text().strip().casefold()
        try:
            values = self.service().store.rows() if self.owner.manager else []
        except UserError as e:
            self.summary.setText(str(e)); return
        active = [r for r in values if not r.get('expired')]
        target = ('open', 'closed', None)[self.filter.currentIndex()]
        matched = [r for r in active if (not target or r['status'] == target) and (not query or query in
            ' '.join([r['inquiryNumber'], *[str(v) for v in r['inquiry'].values()]]).casefold())]
        matched.sort(key=lambda r: r['createdAt'], reverse=True)
        rendered = (matched[:self.limit], query, target, self.limit)
        if rendered == getattr(self, '_rendered', None):
            self.update_controls(); return
        self._rendered = rendered
        if self.owner.manager and not self.owner.task:
            service = self.service()
            if (service.store.read('meta', 'export_due') or {}).get('value'):
                self.copy_error = service.export()['copyError']
        if hasattr(self.owner, 'nav_badges') and self.owner.PAGE_INQUIRIES in self.owner.nav_badges:
            self.owner.nav_badges[self.owner.PAGE_INQUIRIES].setText(str(sum(r['status'] == 'open' for r in active)))
        self.rows = matched[:self.limit]
        self.table.blockSignals(True)
        fill_table(self.table, [(r['inquiryNumber'], date_text(r['createdAt']),
            r['inquiry']['customerName'] + ' · ' + phone_text(r['inquiry']['customerPhone']), r['inquiry']['category'],
            r['inquiry']['message'].replace('\n', ' '), r['status'],
            DELIVERY_LABELS.get((r.get('delivery') or {}).get('state'), '미발송')) for r in self.rows])
        for index, row in enumerate(self.rows):
            self.table.item(index, 1).setToolTip(local_time(row["createdAt"]))
        self.table.setCurrentCell(-1, -1)
        if previous:
            index = next((i for i, r in enumerate(self.rows) if r['requestId'] == previous['requestId']), None)
            if index is not None:
                self.table.selectRow(index)
        self.table.blockSignals(False)
        self.more.setVisible(len(matched) > len(self.rows))
        self.summary.setText(f"미완료 {sum(r['status']=='open' for r in active):,}건 · 완료 {sum(r['status']=='closed' for r in active):,}건 · 현재 {len(matched):,}건")
        if hasattr(self.owner, 'set_page_count'):
            self.owner.set_page_count(self.owner.PAGE_INQUIRIES, len(matched))
        self.hint.setText(getattr(self, 'copy_error', '') or '문의 선택 후 답변하기 · 미완료 문의는 모든 연도를 표시합니다.')
        self.update_controls()

    def update_controls(self):
        ready = bool(self.owner.manager and not self.owner.task and not getattr(self.owner, 'demo_mode', False))
        for w in (self.load, self.excel, self.export_button):
            w.setEnabled(ready)
        self.open_button.setEnabled(ready and self.selected() is not None)

    def finished(self, result):
        self.copy_error = result.get('copyError', '')
        self.refresh_local()
        self.owner.message.setText(result.get('copyError') or f"고객문의 {result['count']:,}건 · 로컬 DB·엑셀 저장 완료")

    def sync(self):
        if self.owner.manager and not self.owner.task and not getattr(self.owner, 'demo_mode', False):
            self.owner.run_job('고객문의 불러오는 중…', self.service().sync, self.finished,
                               refresh_on_error=False)

    def export(self):
        if self.owner.manager and not self.owner.task:
            self.owner.run_job('고객문의 엑셀 저장 중…', self.service().export, self.finished, refresh_on_error=False)

    def open_excel(self):
        try:
            result = self.service().export()
            if result['copyError']:
                self.owner.message.setText(result['copyError']); return
            # Open the year of the selected inquiry, otherwise the latest workbook.
            selected = self.selected()
            year = local_time(selected['createdAt'])[:4] if selected else ''
            path = next((p for p in result['paths'] if f'-{year}.xlsx' in p), result['paths'][-1])
            QDesktopServices.openUrl(QUrl.fromLocalFile(path))
        except UserError as e:
            self.owner.message.setText(str(e))

    def open_selected(self, *_):
        row = self.selected()
        if row and not self.owner.task:
            dialog = InquiryDialog(self, row['requestId'])
            dialog.exec()
            self.refresh_local()


class InquiryDialog(QDialog):
    def __init__(self, page, identifier):
        super().__init__(page.owner)
        self.page, self.service, self.identifier = page, page.service(), identifier
        self.task, self.loading = None, False
        self.setWindowTitle('고객문의 · 답변하기')
        self.setMinimumSize(730, 580)
        fit_window(self, 920, 850)
        root = QVBoxLayout(self)
        root.setContentsMargins(18, 16, 18, 16)
        root.setSpacing(10)
        heading = QHBoxLayout()
        self.title = text_label('', 'sectionTitle')
        self.badge = text_label('', 'selectionHint')
        heading.addWidget(self.title, 1); heading.addWidget(self.badge)
        root.addLayout(heading)
        card = QFrame(); card.setObjectName('customerInfoCard')
        contact = QVBoxLayout(card); contact.setContentsMargins(14, 10, 14, 10)
        self.contact = text_label('', '', True); contact.addWidget(self.contact)
        self.original = QPlainTextEdit(); self.original.setReadOnly(True)
        self.original.setAccessibleName('고객 문의 원문')
        self.original.setMinimumHeight(75); self.original.setMaximumHeight(130)
        contact.addWidget(self.original)
        root.addWidget(card)
        line = QHBoxLayout()
        line.addWidget(text_label('답변 작성', 'sectionTitle'))
        line.addStretch()
        self.counter = text_label('', 'selectionHint'); line.addWidget(self.counter)
        root.addLayout(line)
        self.reply = QPlainTextEdit()
        self.reply.setPlaceholderText('고객에게 안내할 답변을 입력해주세요.')
        self.reply.setAccessibleName('고객문의 답변')
        self.reply.setMinimumHeight(120)
        root.addWidget(self.reply, 1)
        self.info = text_label('', 'storageStatus', True); root.addWidget(self.info)
        self.history_toggle = QPushButton('서버 답변·처리 이력 보기')
        self.history_toggle.setCheckable(True)
        root.addWidget(self.history_toggle)
        self.history = QPlainTextEdit(); self.history.setReadOnly(True)
        self.history.setMaximumHeight(120); self.history.hide(); root.addWidget(self.history)
        self.history_toggle.toggled.connect(self.history.setVisible)
        self.message = text_label('', 'selectionHint', True); root.addWidget(self.message)
        actions = QHBoxLayout()
        self.save = QPushButton('초안 저장')
        self.sms = QPushButton('문자 보내기'); self.sms.setObjectName('primary')
        self.email = QPushButton('메일 보내기'); self.email.setObjectName('primary')
        self.check_button = QPushButton('결과 확인')
        for w in (self.save, self.sms, self.email, self.check_button): actions.addWidget(w)
        root.addLayout(actions)
        footer = QHBoxLayout()
        self.rebase = QPushButton('최신 기준 저장')
        self.status = QPushButton('전화 상담 완료')
        self.close_button = QPushButton('닫기')
        footer.addWidget(self.rebase); footer.addWidget(self.status); footer.addStretch(); footer.addWidget(self.close_button)
        root.addLayout(footer)
        self.timer = QTimer(self); self.timer.setSingleShot(True); self.timer.setInterval(350)
        self.timer.timeout.connect(self.remember)
        self.reply.textChanged.connect(self.edited)
        self.save.clicked.connect(lambda: self.work('답변 초안 저장 중…', lambda: self.service.save_reply(identifier)))
        self.sms.clicked.connect(lambda: self.send('sms'))
        self.email.clicked.connect(lambda: self.send('email'))
        self.check_button.clicked.connect(lambda: self.work('이전 작업 결과 확인 중…', lambda: self.service.check(identifier)))
        self.rebase.clicked.connect(self.save_latest)
        self.status.clicked.connect(self.change_status)
        self.close_button.clicked.connect(self.reject)
        self.refresh()
        scroll_dialog(self, root)

    def edited(self):
        if not self.loading:
            if len(self.reply.toPlainText()) > 2000:
                cursor = self.reply.textCursor()
                position = cursor.position()
                self.loading = True
                self.reply.setPlainText(self.reply.toPlainText()[:2000])
                cursor = self.reply.textCursor(); cursor.setPosition(min(position, 2000)); self.reply.setTextCursor(cursor)
                self.loading = False
            self.counter.setText(f'{len(self.reply.toPlainText()):,} / 2,000자 · 문자 900자')
            self.timer.start()

    def remember(self):
        self.timer.stop()
        try:
            self.service.remember(self.identifier, self.reply.toPlainText())
            return True
        except UserError as e:
            self.message.setText(str(e)); return False

    def refresh(self):
        try:
            r = self.service.row(self.identifier)
        except UserError as e:
            self.expired = True
            self.reply.clear(); self.original.clear(); self.contact.clear(); self.history.clear()
            self.message.setText(str(e))
            for w in (self.save, self.sms, self.email, self.check_button, self.status, self.rebase, self.reply): w.setEnabled(False)
            return
        self.row = r
        q, d = r['inquiry'], r.get('delivery') or {}
        draft = self.service.draft(r)
        self.loading = True
        self.title.setText(r['inquiryNumber'])
        self.badge.setText(r['status'] + ' · ' + q['category'])
        self.contact.setText(f"{q['customerName']} · {q['customerPhone']} · {q['customerEmail'] or '이메일 없음'}\n접수 {local_time(r['createdAt'])}")
        self.original.setPlainText(q['message'])
        self.reply.setPlainText(draft['text'])
        self.loading = False
        self.counter.setText(f"{len(draft['text']):,} / 2,000자 · 문자 900자")
        delivery = DELIVERY_LABELS.get(d.get('state'), '아직 발송하지 않았습니다.')
        if d.get('statusMessage'):
            delivery += ' · ' + d['statusMessage']
        blocked = sms_blocked(r)
        if blocked:
            delivery += ' · 수신거부 번호라 문자는 보낼 수 없어요. 메일·전화로 안내해주세요.'
        conflict = draft['baseRevision'] != r['revision']
        self.info.setText(delivery + (' · 다른 기기 변경 있음. 최신 답변과 내 초안을 확인해주세요.' if conflict else ' · 입력 내용은 이 PC에 자동 보관됩니다.'))
        history = ['현재 서버 답변\n' + (r['reply']['text'] or '(없음)')]
        for h in reversed(r.get('history', [])):
            if h.get('kind') == 'reply':
                history.append(f"{local_time(h.get('at'))} · {'문자' if h.get('channel')=='sms' else '메일'} · {DELIVERY_LABELS.get(h.get('state'), '')}\n{h.get('text','')}")
            else:
                history.append(f"{local_time(h.get('at'))} · {h.get('status','')} · {h.get('note','')}")
        self.history.setPlainText('\n\n'.join(history))
        self.status.setText('다시 열기' if r['status'] == 'closed' else '전화 상담 완료')
        pending = self.service.store.read('operations', self.identifier)
        uncertain = d.get('state') in ('sending', 'pending', 'unknown')
        ready = not self.task
        for w in (self.reply, self.close_button, self.history_toggle): w.setEnabled(ready)
        self.save.setEnabled(ready and not uncertain and not conflict and (not pending or pending['action']=='save_inquiry_reply'))
        can_send = ready and not uncertain and not pending and not conflict
        self.sms.setEnabled(can_send and not blocked)
        self.sms.setToolTip('수신거부 번호라 문자를 보낼 수 없습니다.' if blocked else '')
        self.email.setEnabled(can_send and bool(q['customerEmail']))
        self.check_button.setEnabled(ready and bool(pending or uncertain))
        self.rebase.setVisible(conflict and (not pending or pending['action']=='save_inquiry_reply'))
        self.rebase.setEnabled(ready and not uncertain)
        self.status.setEnabled(ready and not pending and d.get('state') not in ('sending', 'pending'))

    def work(self, title, fn):
        if self.task or not self.remember(): return
        self.message.setText(title)
        self.task = Task(fn, None, self)
        self.task.completed.connect(self.completed)
        self.refresh()
        self.task.start()

    def completed(self, value, error, conflict):
        task = self.task; task.wait(); self.task = None; task.deleteLater()
        self.refresh()
        self.message.setText(error or (value or {}).get('copyError') or '저장했습니다. 발송 상태를 확인해주세요.')

    def send(self, channel):
        if not self.remember(): return
        row = self.service.row(self.identifier)
        recipient = row['inquiry']['customerPhone' if channel=='sms' else 'customerEmail']
        label = '문자' if channel=='sms' else '메일'
        if QMessageBox.question(self, label + ' 답변 보내기', f"{recipient}\n작성한 답변을 {label}로 보낼까요?") != QMessageBox.StandardButton.Yes:
            return
        self.work(label + ' 발송 중…', lambda: self.service.send(self.identifier, channel))

    def save_latest(self):
        if QMessageBox.question(self, '최신 기준으로 저장', '서버에 저장된 답변과 이력을 확인했나요?\n현재 내 초안을 최신 문의 기준으로 저장합니다.') == QMessageBox.StandardButton.Yes:
            self.work('최신 기준으로 초안 저장 중…', lambda: self.service.save_reply(self.identifier, rebase=True))

    def change_status(self):
        target = 'open' if self.row['status']=='closed' else 'closed'
        note, ok = QInputDialog.getText(self, '다시 열기' if target=='open' else '전화 상담 완료', '처리 메모를 입력해주세요.')
        if ok and note.strip():
            self.work('처리 상태 저장 중…', lambda: self.service.set_status(self.identifier, target, note))

    def reject(self):
        if self.task:
            self.message.setText('작업 결과 확인 후 닫을 수 있습니다.'); return
        if getattr(self, "expired", False):
            super().reject(); return
        if self.remember():
            # Also mirror the last local draft to Excel, without contacting the server.
            result = self.service.export()
            if result['copyError']: self.page.owner.message.setText(result['copyError'])
            super().reject()

    def closeEvent(self, event):
        if self.task or (not getattr(self, "expired", False) and not self.remember()): event.ignore()
        else:
            self.reject(); event.accept()

