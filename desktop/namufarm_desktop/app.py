"""Qt desktop manager. Network, cryptography and SQLite run on one worker at a time."""
import sys
from datetime import datetime
from pathlib import Path

from PySide6.QtCore import QDate, QStandardPaths, Qt, QThread, QTimer, Signal
from PySide6.QtGui import QCloseEvent, QRegularExpressionValidator
from PySide6.QtCore import QRegularExpression
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QComboBox, QDialog, QDialogButtonBox, QFileDialog,
    QFormLayout, QGroupBox, QHBoxLayout, QHeaderView, QLabel, QLineEdit, QMainWindow,
    QMessageBox, QPlainTextEdit, QPushButton, QScrollArea, QSplitter, QTabWidget,
    QTableWidget, QTableWidgetItem, QVBoxLayout, QWidget, QDateEdit, QDoubleSpinBox,
)

from .api import DEFAULT_URL, ScriptApi, validate_url
from .crypto import SellerKey
from .domain import EDITABLE, NEXT, SEOUL, STATES, MAX_SAFE, profit, require
from .errors import ConflictError, UserError
from .service import Manager
from .store import Vault


def money(value):
    return f"{value:,}원" if value is not None else "원가 미확인"


def timestamp(value):
    if not value:
        return "—"
    if isinstance(value, str):
        date = datetime.fromisoformat(value.replace("Z", "+00:00"))
    else:
        date = datetime.fromtimestamp(value / 1000, SEOUL)
    return date.astimezone(SEOUL).strftime("%Y-%m-%d %H:%M")


def table(headers):
    result = QTableWidget(0, len(headers))
    result.setHorizontalHeaderLabels(headers)
    result.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
    result.setSelectionMode(QTableWidget.SelectionMode.SingleSelection)
    result.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
    result.setAlternatingRowColors(True)
    result.verticalHeader().hide()
    result.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.ResizeToContents)
    result.horizontalHeader().setStretchLastSection(True)
    result.setWordWrap(False)
    return result


def fill_table(widget, rows):
    widget.setRowCount(len(rows))
    for index, values in enumerate(rows):
        for column, value in enumerate(values):
            widget.setItem(index, column, QTableWidgetItem(str(value)))


class Task(QThread):
    completed = Signal(object, str, bool)

    def __init__(self, work, refresh_on_error=None, parent=None):
        super().__init__(parent)
        self.work, self.refresh_on_error = work, refresh_on_error

    def run(self):
        try:
            self.completed.emit(self.work(), "", False)
        except Exception as error:
            state = None
            if self.refresh_on_error:
                try:
                    state = self.refresh_on_error()
                except Exception:
                    pass
            message = str(error) if isinstance(error, UserError) else "처리를 완료하지 못했습니다. 기존 기록과 전송 대기는 유지됩니다."
            self.completed.emit(state, message, isinstance(error, ConflictError))
        finally:
            self.work = self.refresh_on_error = None


class UnlockDialog(QDialog):
    def __init__(self, vault_path, parent=None):
        super().__init__(parent)
        self.setWindowTitle("나무팜 PC 보관함 잠금 해제")
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("PC 보관함 비밀번호를 입력하세요. 고객정보는 잠금 해제 후 표시됩니다."))
        self.password = QLineEdit()
        self.password.setEchoMode(QLineEdit.EchoMode.Password)
        self.password.setPlaceholderText("PC 보관함 비밀번호 · 12자 이상")
        layout.addWidget(self.password)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)


class SetupDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("나무팜 PC 처음 연결")
        self.resize(580, 360)
        layout = QVBoxLayout(self)
        intro = QLabel("휴대폰 나무팜 앱에서 저장한 보안 백업을 선택하세요.\n기존 고객정보를 열기 위해 휴대폰과 같은 보안키를 사용합니다.")
        layout.addWidget(intro)
        form = QFormLayout()
        self.backup = QLineEdit()
        browse = QPushButton("파일 선택")
        browse.clicked.connect(self.choose_backup)
        backup_row = QHBoxLayout()
        backup_row.addWidget(self.backup)
        backup_row.addWidget(browse)
        form.addRow("앱 보안 백업", backup_row)
        self.backup_password, self.password, self.confirm = QLineEdit(), QLineEdit(), QLineEdit()
        for field in (self.backup_password, self.password, self.confirm):
            field.setEchoMode(QLineEdit.EchoMode.Password)
        form.addRow("앱 백업 비밀번호", self.backup_password)
        form.addRow("새 PC 보관함 비밀번호", self.password)
        form.addRow("PC 비밀번호 확인", self.confirm)
        self.url = QLineEdit(DEFAULT_URL)
        form.addRow("Apps Script 웹 앱 주소", self.url)
        layout.addLayout(form)
        layout.addWidget(QLabel("PC 비밀번호는 12자 이상입니다. 연결 코드는 보관함 생성 후 입력합니다."))
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept_checked)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def choose_backup(self):
        path, _ = QFileDialog.getOpenFileName(self, "앱 보안 백업 선택", "", "보안 백업 (*.json);;모든 파일 (*)")
        if path:
            self.backup.setText(path)

    def accept_checked(self):
        try:
            require(Path(self.backup.text()).is_file(), "앱 보안 백업 파일을 선택해주세요.")
            require(len(self.password.text()) >= 12 and self.password.text() == self.confirm.text(), "PC 비밀번호를 12자 이상으로 입력하고 확인해주세요.")
            require(len(self.backup_password.text()) >= 12, "앱 백업 비밀번호를 확인해주세요.")
            validate_url(self.url.text().strip())
            self.accept()
        except UserError as error:
            QMessageBox.warning(self, "입력 확인", str(error))

    def clear_sensitive(self):
        for field in (self.backup_password, self.password, self.confirm):
            field.clear()


class MainWindow(QMainWindow):
    def __init__(self, vault_path=None):
        super().__init__()
        self.setWindowTitle("나무팜 PC 관리 · 견적 요청과 판매")
        self.resize(1360, 860)
        self.vault_path = Path(vault_path) if vault_path else Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.AppLocalDataLocation)) / "namufarm-vault.db"
        self.manager = None
        self.task = None
        self.data = {"requests": [], "sales": [], "pending": [], "paired": False}
        self.sale_rows = []
        self.selected_sale = None
        self.dirty = False
        self.loading = False
        self.conflict = False
        root = QWidget()
        self.setCentralWidget(root)
        layout = QVBoxLayout(root)
        top = QHBoxLayout()
        title = QLabel("나무팜  |  PC 관리")
        title.setStyleSheet("font-size:24px;font-weight:700;color:#205844")
        top.addWidget(title)
        top.addStretch()
        self.summary = QLabel("보관함 잠금 상태")
        top.addWidget(self.summary)
        self.sync_button = QPushButton("지금 동기화")
        self.sync_button.clicked.connect(self.manual_sync)
        top.addWidget(self.sync_button)
        self.lock_button = QPushButton("잠금")
        self.lock_button.clicked.connect(self.lock)
        top.addWidget(self.lock_button)
        layout.addLayout(top)
        self.message = QLabel("PC를 연결하면 구글시트에 저장된 요청·판매 기록을 조회합니다.")
        self.message.setWordWrap(True)
        layout.addWidget(self.message)
        self.tabs = QTabWidget()
        layout.addWidget(self.tabs)
        self.build_requests()
        self.build_sales()
        self.build_reports()
        self.build_connection()
        self.timer = QTimer(self)
        self.timer.setInterval(60000)
        self.timer.timeout.connect(self.auto_sync)
        self.timer.start()
        self.update_controls()

    def run_job(self, title, work, on_success=None, refresh_on_error=True):
        if self.task:
            return
        self.message.setStyleSheet("")
        self.message.setText(title)
        refresh = refresh_on_error if callable(refresh_on_error) else (self.manager.view if self.manager and refresh_on_error else None)
        task = Task(work, refresh, self)
        self.task = task
        task.completed.connect(lambda value, error, conflict: self.job_finished(task, value, error, conflict, on_success))
        self.update_controls()
        task.start()

    def job_finished(self, task, value, error, conflict, on_success):
        task.wait()
        self.task = None
        task.deleteLater()
        if error:
            if isinstance(value, dict) and "requests" in value:
                self.apply_view(value)
            self.conflict = conflict or self.conflict
            self.message.setText(error)
            self.message.setStyleSheet("color:#a33827;font-weight:600")
        else:
            if on_success:
                on_success(value)
            elif isinstance(value, dict) and "requests" in value:
                self.apply_view(value)
                self.conflict = False
                self.message.setText("서버 동기화 완료 · " + timestamp(datetime.now(SEOUL).timestamp() * 1000))
        self.update_controls()

    def open_vault(self):
        if self.manager or self.task:
            return
        if self.vault_path.exists():
            dialog = UnlockDialog(self.vault_path, self)
            if dialog.exec() != QDialog.DialogCode.Accepted:
                return
            password = dialog.password.text()
            dialog.password.clear()
            self.run_job("보관함 잠금 해제 중…", lambda: self.unlocked(Vault.unlock(self.vault_path, password)), self.accept_manager, False)
        else:
            dialog = SetupDialog(self)
            if dialog.exec() != QDialog.DialogCode.Accepted:
                return
            path, backup_password, password, url = dialog.backup.text(), dialog.backup_password.text(), dialog.password.text(), dialog.url.text().strip()
            dialog.clear_sensitive()
            def create():
                try:
                    with Path(path).open("rb") as source:
                        raw = source.read(20001)
                    require(len(raw) < 20000, "앱 보안 백업 파일이 너무 큽니다.")
                    seller = SellerKey.from_backup(raw.decode("utf-8"), backup_password)
                except (OSError, UnicodeError):
                    raise UserError("앱 보안 백업 파일을 읽을 수 없습니다.") from None
                vault = Vault.create(self.vault_path, password, seller)
                credentials = vault.credentials()
                credentials["url"] = url
                vault.save_credentials(credentials)
                return self.unlocked(vault)
            self.run_job("앱 보안 백업으로 PC 보관함 생성 중…", create, self.accept_manager, False)

    @staticmethod
    def unlocked(vault):
        manager = Manager(vault)
        return manager, manager.view()

    def accept_manager(self, result):
        self.manager, data = result
        self.url.setText(self.manager.api.url)
        self.apply_view(data)
        self.message.setText("보관함을 열었습니다. " + ("지금 동기화해 최신 기록을 가져오세요." if data["paired"] else "PC용 일회용 연결 코드를 입력하세요."))

    def update_controls(self):
        ready = self.manager is not None and self.task is None
        paired = ready and self.data["paired"]
        self.tabs.setEnabled(self.task is None)
        self.sync_button.setEnabled(paired)
        self.lock_button.setEnabled(ready)
        self.open_button.setEnabled(self.manager is None and self.task is None)
        self.pair_button.setEnabled(ready and not self.data["paired"])
        self.url.setEnabled(ready and not self.data["paired"])
        self.invite.setEnabled(ready and not self.data["paired"])
        pending = bool(self.data["pending"])
        self.delete_button.setEnabled(paired and not pending and self.request_table.currentRow() >= 0)
        editable = paired and not pending and self.selected_sale is not None and self.selected_sale["metadata"]["status"] not in ("판매완료", "주문취소")
        self.sale_editor.setEnabled(editable)
        self.save_button.setEnabled(editable and self.dirty)
        self.next_button.setEnabled(editable and self.selected_sale["metadata"]["status"] in NEXT)
        self.report_button.setEnabled(paired)
        self.resolve_button.setEnabled(paired and pending and self.conflict)
        self.summary.setText(f"문의 {len(self.data['requests'])}건 · 판매 {len(self.data['sales'])}건 · 전송 대기 {len(self.data['pending'])}건" if self.manager else "보관함 잠금 상태")
        self.connection_status.setText("PC 연결 완료" if self.data["paired"] else "PC 연결 전")
        self.pending_status.setText("전송 대기는 암호화해 보관합니다. '지금 동기화'로 재시도하세요." if pending else "전송 대기 없음")

    def allow_discard(self):
        if not self.dirty:
            return True
        return QMessageBox.question(self, "미저장 수정", "입력한 수정내용을 버리고 계속할까요?", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) == QMessageBox.StandardButton.Yes

    def manual_sync(self):
        if self.manager and self.allow_discard():
            self.run_job("구글시트와 동기화 중…", self.manager.sync)

    def auto_sync(self):
        if self.manager and self.data["paired"] and self.auto_check.isChecked() and not self.task and not self.dirty and not self.conflict:
            self.run_job("자동 동기화 중…", self.manager.sync)

    def apply_view(self, value):
        self.data = value
        self.dirty = False
        self.refresh_requests()
        self.refresh_sales()
        self.update_controls()

    def build_requests(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        self.request_search = QLineEdit()
        self.request_search.setPlaceholderText("고객명 · 전화번호 · 묘목 · 지역 검색")
        self.request_search.textChanged.connect(self.refresh_requests)
        layout.addWidget(self.request_search)
        split = QSplitter(Qt.Orientation.Vertical)
        self.request_table = table(["접수일", "고객명", "전화번호", "희망 묘목", "지역", "견적서"])
        self.request_table.itemSelectionChanged.connect(self.select_request)
        split.addWidget(self.request_table)
        self.request_detail = QPlainTextEdit()
        self.request_detail.setReadOnly(True)
        split.addWidget(self.request_detail)
        split.setSizes([450, 170])
        layout.addWidget(split)
        row = QHBoxLayout()
        row.addWidget(QLabel("홈페이지 문의 · 구매 확정과 재고 차감은 휴대폰 앱에서 처리합니다."))
        row.addStretch()
        self.delete_button = QPushButton("선택 문의 삭제")
        self.delete_button.clicked.connect(self.delete_request)
        row.addWidget(self.delete_button)
        layout.addLayout(row)
        self.tabs.addTab(page, "견적 요청")

    def refresh_requests(self):
        query = self.request_search.text().casefold()
        self.request_rows = [r for r in self.data["requests"] if query in str(r["personal"]).casefold()]
        self.request_table.blockSignals(True)
        fill_table(self.request_table, [(timestamp(r["createdAt"]), r["personal"]["customerName"], r["personal"]["customerPhone"],
                   ", ".join(f"{i['species']} {i['quantity']}주" for i in r["personal"]["items"]), r["personal"]["region"],
                   "요청" if r["personal"].get("formalQuoteRequested") else "—") for r in self.request_rows])
        self.request_table.clearSelection()
        self.request_table.setCurrentCell(-1, -1)
        self.request_table.blockSignals(False)
        self.request_detail.clear()
        self.update_controls()

    def select_request(self):
        index = self.request_table.currentRow()
        if 0 <= index < len(self.request_rows):
            row, person = self.request_rows[index], self.request_rows[index]["personal"]
            text = [f"고객: {person['customerName']} / {person['customerPhone']}", f"이메일: {person.get('customerEmail') or '없음'}",
                    f"배송지역: {person['region']}", "희망 묘목:"]
            text.extend(f"  · {i['species']} {i.get('specification', '')} / {i['quantity']:,}주" for i in person["items"])
            text.extend([f"요청사항: {person.get('message', '')}", f"공식 견적 수신인: {person.get('recipientName', '')}",
                         f"수신 방법: {dict(sms='문자', email='이메일', both='문자와 이메일').get(person.get('replyChannel', 'sms'))}",
                         f"보관 종료: {timestamp(row['expiresAt'])}"])
            self.request_detail.setPlainText("\n".join(text))
        self.update_controls()

    def delete_request(self):
        index = self.request_table.currentRow()
        if not 0 <= index < len(self.request_rows):
            return
        if QMessageBox.question(self, "문의 삭제", "선택 문의와 연결된 미구매 견적 사본을 삭제할까요?\n휴대폰은 다음 요청함 동기화 때 반영합니다.", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
            return
        request_id = self.request_rows[index]["requestId"]
        self.run_job("문의 삭제 저장 중…", lambda: self.manager.close_request(request_id))

    def build_sales(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        bar = QHBoxLayout()
        self.sale_search = QLineEdit()
        self.sale_search.setPlaceholderText("주문번호 · 고객명 · 전화번호 검색")
        self.sale_search.textChanged.connect(self.filter_sales)
        self.state_filter = QComboBox()
        self.state_filter.addItems(["전체 상태", *STATES])
        self.state_filter.currentIndexChanged.connect(self.filter_sales)
        bar.addWidget(self.sale_search)
        bar.addWidget(self.state_filter)
        layout.addLayout(bar)
        split = QSplitter()
        self.sale_table = table(["주문번호", "고객명", "판매 상태", "매출", "매출이익"])
        self.sale_table.itemSelectionChanged.connect(self.select_sale)
        split.addWidget(self.sale_table)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        editor_page = QWidget()
        editor_layout = QVBoxLayout(editor_page)
        self.sale_info = QLabel("판매 기록을 선택하세요.")
        self.sale_info.setWordWrap(True)
        editor_layout.addWidget(self.sale_info)
        self.sale_editor = QGroupBox("고객·배송 정보와 원가")
        form = QFormLayout(self.sale_editor)
        self.fields = {}
        labels = {"customerName": "고객명", "customerPhone": "전화번호", "customerEmail": "이메일", "receiverName": "배송 수령인", "zipNo": "우편번호", "roadAddress": "도로명 주소", "detailAddress": "상세 주소", "note": "배송 메모"}
        for name in EDITABLE:
            field = QPlainTextEdit() if name == "note" else QLineEdit()
            if name == "note":
                field.setMaximumHeight(85)
            else:
                field.setMaxLength(EDITABLE[name])
            field.textChanged.connect(self.mark_dirty)
            self.fields[name] = field
            form.addRow(labels[name], field)
        self.profit_mode = QComboBox()
        self.profit_mode.addItem("매입 후 판매", "resale")
        self.profit_mode.addItem("중개 수수료", "commission")
        self.profit_mode.currentIndexChanged.connect(self.mark_dirty)
        form.addRow("수익 방식", self.profit_mode)
        self.commission = QDoubleSpinBox()
        self.commission.setRange(0, 100)
        self.commission.setDecimals(2)
        self.commission.setSuffix(" %")
        self.commission.valueChanged.connect(self.mark_dirty)
        form.addRow("수수료율", self.commission)
        for name, label in (("costTotal", "원가 합계 (원)"), ("expenseTotal", "기타 비용 (원)")):
            field = QLineEdit()
            field.setValidator(QRegularExpressionValidator(QRegularExpression("[0-9]{0,16}")))
            field.textChanged.connect(self.mark_dirty)
            self.fields[name] = field
            form.addRow(label, field)
        self.cost_confirmed = QCheckBox("원가와 비용을 확인했습니다")
        self.cost_confirmed.toggled.connect(self.mark_dirty)
        form.addRow(self.cost_confirmed)
        editor_layout.addWidget(self.sale_editor)
        buttons = QHBoxLayout()
        self.save_button = QPushButton("수정 저장")
        self.save_button.clicked.connect(lambda: self.save_sale())
        self.next_button = QPushButton("다음 단계 확인")
        self.next_button.clicked.connect(self.advance_sale)
        buttons.addWidget(self.save_button)
        buttons.addWidget(self.next_button)
        editor_layout.addLayout(buttons)
        self.sale_items = QPlainTextEdit()
        self.sale_items.setReadOnly(True)
        self.sale_items.setMaximumHeight(110)
        editor_layout.addWidget(self.sale_items)
        editor_layout.addStretch()
        scroll.setWidget(editor_page)
        split.addWidget(scroll)
        split.setSizes([710, 560])
        layout.addWidget(split)
        self.tabs.addTab(page, "판매 관리")

    def mark_dirty(self, *args):
        if not self.loading and self.selected_sale:
            self.dirty = True
            self.update_controls()

    def filter_sales(self):
        # Keep an edited record visible until it is saved or explicitly discarded.
        if self.dirty:
            return
        self.refresh_sales()

    def refresh_sales(self):
        selected = self.selected_sale["metadata"]["orderNumber"] if self.selected_sale else None
        query, state = self.sale_search.text().casefold(), self.state_filter.currentText()
        self.sale_rows = [r for r in self.data["sales"] if (state == "전체 상태" or r["metadata"]["status"] == state)
                          and query in str(r["private"]["order"]).casefold()]
        self.sale_table.blockSignals(True)
        fill_table(self.sale_table, [(r["metadata"]["orderNumber"], r["private"]["order"].get("customerName", ""), r["metadata"]["status"],
                                     money(r["metadata"]["totalPrice"]), money(profit(r["metadata"]))) for r in self.sale_rows])
        self.sale_table.clearSelection()
        self.sale_table.setCurrentCell(-1, -1)
        self.sale_table.blockSignals(False)
        self.selected_sale = None
        self.load_sale(None)
        if selected:
            index = next((i for i, r in enumerate(self.sale_rows) if r["metadata"]["orderNumber"] == selected), None)
            if index is not None:
                self.sale_table.selectRow(index)

    def select_sale(self):
        index = self.sale_table.currentRow()
        row = self.sale_rows[index] if 0 <= index < len(self.sale_rows) else None
        if self.dirty and not self.allow_discard():
            old = next((i for i, r in enumerate(self.sale_rows) if r is self.selected_sale), None)
            self.sale_table.blockSignals(True)
            if old is not None:
                self.sale_table.selectRow(old)
            self.sale_table.blockSignals(False)
            return
        self.load_sale(row)

    def load_sale(self, row):
        self.loading = True
        self.selected_sale, self.dirty = row, False
        order = row["private"]["order"] if row else {}
        for name, field in self.fields.items():
            value = str(order.get(name, ""))
            field.setPlainText(value) if isinstance(field, QPlainTextEdit) else field.setText(value)
        self.profit_mode.setCurrentIndex(1 if order.get("profitMode") == "commission" else 0)
        self.commission.setValue(order.get("commissionBps", 3000) / 100)
        self.cost_confirmed.setChecked(order.get("costConfirmed", False))
        if row:
            meta = row["metadata"]
            self.sale_info.setText(f"{meta['orderNumber']} · {meta['status']} · revision {meta['revision']}\n매출 {money(meta['totalPrice'])} / 세액 {money(meta['taxAmount'])}\n입금 {timestamp(meta['paidAt'])} · 배송 {timestamp(meta['deliveredAt'])}")
            self.sale_items.setPlainText("\n".join(f"{i['species']} · {i['quantity']:,}주 × {money(i['unitPrice'])}" for i in meta["items"]))
            self.next_button.setText({"구매확정": "입금 확인", "입금확인": "배송 확인", "배송확인": "판매 완료"}.get(meta["status"], "처리 종료"))
            self.fields["customerPhone"].setReadOnly(bool(row["private"].get("quoteDocumentJson")))
        else:
            self.sale_info.setText("판매 기록을 선택하세요.")
            self.sale_items.clear()
            self.next_button.setText("다음 단계 확인")
        self.loading = False
        self.update_controls()

    def collect_fields(self):
        result = {}
        for name, field in self.fields.items():
            text = field.toPlainText() if isinstance(field, QPlainTextEdit) else field.text()
            if name in ("costTotal", "expenseTotal"):
                require(text.isascii() and text.isdigit() and int(text) <= MAX_SAFE, "원가와 비용을 숫자로 입력해주세요.")
                result[name] = int(text)
            else:
                result[name] = text
        result.update(profitMode=self.profit_mode.currentData(), commissionBps=round(self.commission.value() * 100), costConfirmed=self.cost_confirmed.isChecked())
        return result

    def save_sale(self, target=None):
        if not self.selected_sale or not self.manager:
            return
        try:
            fields = self.collect_fields()
        except UserError as error:
            QMessageBox.warning(self, "입력 확인", str(error))
            return
        meta = self.selected_sale["metadata"]
        self.run_job("판매 변경 저장 중…", lambda: self.manager.save_sale(meta["orderNumber"], meta["revision"], fields, target),
                     refresh_on_error=lambda: self.manager.view() if self.manager.vault.pending() else None)

    def advance_sale(self):
        if not self.selected_sale:
            return
        target = NEXT.get(self.selected_sale["metadata"]["status"])
        if not target:
            return
        prompts = {"입금확인": "실제 입금을 확인했나요?", "배송확인": "실제 배송을 확인했나요?", "판매완료": "배송과 원가·비용 확인을 마치고 판매를 완료할까요?"}
        if QMessageBox.question(self, target, prompts[target], QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) == QMessageBox.StandardButton.Yes:
            self.save_sale(target)

    def build_reports(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        bar = QHBoxLayout()
        self.unit = QComboBox()
        for label, key in (("일별", "day"), ("월별", "month"), ("연별", "year")):
            self.unit.addItem(label, key)
        today = QDate.currentDate()
        self.start_date, self.end_date = QDateEdit(QDate(today.year(), 1, 1)), QDateEdit(today)
        for field in (self.start_date, self.end_date):
            field.setCalendarPopup(True)
            field.setDisplayFormat("yyyy-MM-dd")
        self.report_button = QPushButton("통계 조회")
        self.report_button.clicked.connect(self.fetch_report)
        for field in (self.unit, self.start_date, QLabel("~"), self.end_date, self.report_button):
            bar.addWidget(field)
        bar.addStretch()
        layout.addLayout(bar)
        layout.addWidget(QLabel("판매완료일의 한국 시간 기준 · 매출은 세액 제외 · 확인한 원가를 기준으로 매출이익 계산"))
        self.report_table = table(["기간", "판매 건수", "수량", "매출", "세액", "원가", "비용", "확인 매출이익", "원가 미확인"])
        layout.addWidget(self.report_table)
        self.report_summary = QLabel("기간을 선택하고 통계를 조회하세요.")
        layout.addWidget(self.report_summary)
        self.tabs.addTab(page, "일·월·년 통계")

    def fetch_report(self):
        unit, start, end = self.unit.currentData(), self.start_date.date().toString("yyyy-MM-dd"), self.end_date.date().toString("yyyy-MM-dd")
        def work():
            report = self.manager.report(unit, start, end)
            return report, self.manager.vault.pending()
        self.run_job("판매 통계 조회 중…", work, self.apply_report, False)

    def apply_report(self, result):
        report, pending = result
        self.data["pending"] = pending
        keys = ("period", "orders", "quantity", "sales", "tax", "cost", "expenses", "knownProfit", "unknownProfitOrders")
        fill_table(self.report_table, [[money(b[k]) if k in ("sales", "tax", "cost", "expenses", "knownProfit") else b[k] for k in keys] for b in report["buckets"]])
        buckets = report["buckets"]
        self.report_summary.setText(f"총 {sum(b['orders'] for b in buckets):,}건 · 매출 {money(sum(b['sales'] for b in buckets))} · 확인 매출이익 {money(sum(b['knownProfit'] for b in buckets))}")
        self.message.setText("판매 통계 조회 완료")

    def build_connection(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        self.open_button = QPushButton("보관함 열기 / 처음 설정")
        self.open_button.clicked.connect(self.open_vault)
        layout.addWidget(self.open_button)
        form = QFormLayout()
        self.connection_status = QLabel("PC 연결 전")
        form.addRow("연결 상태", self.connection_status)
        self.url = QLineEdit(DEFAULT_URL)
        form.addRow("Apps Script 웹 앱 주소", self.url)
        self.invite = QLineEdit()
        self.invite.setEchoMode(QLineEdit.EchoMode.Password)
        self.invite.setMaxLength(64)
        self.invite.setPlaceholderText("Apps Script createDeviceInvite()로 발급한 PC용 일회용 코드")
        form.addRow("PC 연결 코드", self.invite)
        layout.addLayout(form)
        self.pair_button = QPushButton("PC 연결하고 처음 동기화")
        self.pair_button.clicked.connect(self.pair)
        layout.addWidget(self.pair_button)
        self.auto_check = QCheckBox("60초마다 자동 동기화 · 수정 중에는 입력내용을 유지합니다")
        self.auto_check.setChecked(True)
        layout.addWidget(self.auto_check)
        self.pending_status = QLabel("전송 대기 없음")
        layout.addWidget(self.pending_status)
        self.resolve_button = QPushButton("수정 충돌 확인")
        self.resolve_button.clicked.connect(self.resolve_conflict)
        layout.addWidget(self.resolve_button)
        note = QLabel("휴대폰과 PC는 구글시트를 통해 동기화합니다.\n저장 실패 시 전송 대기를 유지하며, 서버 저장 확인 뒤에만 지웁니다.\n같은 판매 기록을 동시에 수정하면 충돌 내용을 확인할 수 있습니다.\n보관함을 백업하려면 프로그램을 닫고 namufarm-vault.db를 복사하세요.\n보관함 위치: " + str(self.vault_path))
        note.setWordWrap(True)
        note.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        layout.addWidget(note)
        layout.addStretch()
        self.tabs.addTab(page, "연결·설정")

    def pair(self):
        try:
            url = validate_url(self.url.text().strip())
        except UserError as error:
            QMessageBox.warning(self, "서버 주소 확인", str(error))
            return
        invite = self.invite.text().strip()
        self.invite.clear()
        def work():
            self.manager.api = ScriptApi(url, self.manager.credentials["deviceToken"])
            return self.manager.pair(invite)
        self.run_job("PC 연결 중…", work)

    def resolve_conflict(self):
        self.run_job("서버 최신 판매 기록 확인 중…", self.manager.conflict_preview, self.confirm_conflict)

    def confirm_conflict(self, preview):
        dialog = QDialog(self)
        dialog.setWindowTitle("동시 수정 충돌")
        dialog.resize(800, 500)
        layout = QVBoxLayout(dialog)
        layout.addWidget(QLabel("PC 수정은 아직 서버에 반영되지 않았습니다. 서버 내용을 확인하고 선택하세요."))
        comparison = table(["항목", "PC 전송 대기", "서버 최신"])
        local_order = self.manager.seller.open(preview["operation"]["payload"]["encryptedPersonal"], "order", preview["operation"]["payload"]["eventId"])["order"]
        server_order = self.manager.decode_sale(preview["remote"])["order"]
        names = ["revision", "status", *EDITABLE, "costTotal", "expenseTotal", "costConfirmed", "profitMode", "commissionBps"]
        fill_table(comparison, [(name, local_order.get(name, ""), server_order.get(name, "")) for name in names if local_order.get(name) != server_order.get(name)])
        layout.addWidget(comparison)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Cancel)
        use_remote = buttons.addButton("PC 수정 버리고 서버 기록 사용", QDialogButtonBox.ButtonRole.AcceptRole)
        use_remote.clicked.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            self.run_job("서버 기록 반영 중…", lambda: self.manager.use_server_version(preview))

    def lock(self):
        if self.task or not self.allow_discard():
            return
        self.manager = None
        self.conflict = False
        self.selected_sale = None
        self.apply_view({"requests": [], "sales": [], "pending": [], "paired": False})
        self.invite.clear()
        self.report_table.setRowCount(0)
        self.report_summary.setText("기간을 선택하고 통계를 조회하세요.")
        self.message.setText("보관함을 잠갔습니다.")
        self.tabs.setCurrentIndex(3)

    def closeEvent(self, event: QCloseEvent):
        if self.task:
            self.message.setText("현재 처리가 끝난 뒤 프로그램을 닫아주세요.")
            event.ignore()
        elif self.allow_discard():
            event.accept()
        else:
            event.ignore()


def main():
    app = QApplication.instance() or QApplication(sys.argv)
    app.setApplicationName("NamuFarmDesktop")
    app.setOrganizationName("NamuFarm")
    app.setStyle("Fusion")
    app.setStyleSheet("QPushButton {padding:7px 12px;} QLineEdit,QComboBox,QDateEdit {padding:5px;} QTabBar::tab {padding:10px 20px;} QGroupBox {font-weight:600;}")
    window = MainWindow()
    window.show()
    if "--self-check" in sys.argv:
        app.processEvents()
        require(window.tabs.count() == 4, "PC 화면 초기화에 실패했습니다.")
        window.close()
        return 0
    QTimer.singleShot(0, window.open_vault)
    return app.exec()
