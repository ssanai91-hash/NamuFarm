"""Qt desktop manager. Network, cryptography and SQLite run on one worker at a time."""
import sys
from datetime import datetime
from pathlib import Path

from PySide6.QtCore import QDate, QStandardPaths, Qt, QThread, QTimer, Signal, QUrl
from PySide6.QtGui import QCloseEvent, QRegularExpressionValidator, QDesktopServices
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
    result.setShowGrid(True)
    result.setGridStyle(Qt.PenStyle.SolidLine)
    result.verticalHeader().hide()

    header = result.horizontalHeader()
    header.setSectionResizeMode(QHeaderView.ResizeMode.ResizeToContents)
    header.setStretchLastSection(True)
    header.setDefaultAlignment(Qt.AlignmentFlag.AlignCenter)
    header.setHighlightSections(False)

    result.setStyleSheet("""
        QTableWidget {
            background: #f6faf6;
            alternate-background-color: #edf4ed;
            border: 1px solid #8ea48e;
            border-radius: 6px;
            gridline-color: #b7c8b7;
            selection-background-color: #dbe9d8;
            selection-color: #17311d;
        }
        QTableCornerButton::section {
            background-color: #2f5d3a;
            border: 1px solid #24472d;
        }
        QHeaderView::section {
            background-color: #2f5d3a;
            color: #ffffff;
            font-weight: 700;
            padding: 8px 10px;
            border-top: 1px solid #24472d;
            border-bottom: 1px solid #24472d;
            border-right: 1px solid #dbe7db;
        }
        QTableWidget::item {
            padding: 7px 8px;
            border-right: 1px solid #d7e1d7;
            border-bottom: 1px solid #d7e1d7;
        }
        QTableWidget::item:selected {
            background: #dbe9d8;
            color: #17311d;
        }
    """)

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
        layout.addWidget(QLabel("기존 PC 비밀번호를 한 번 입력해주세요. 다음 실행부터 자동으로 열립니다."))
        self.password = QLineEdit()
        self.password.setEchoMode(QLineEdit.EchoMode.Password)
        self.password.setPlaceholderText("PC 보관함 비밀번호 · 12자 이상")
        layout.addWidget(self.password)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)


class LegacyImportDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("이전 자료 복원")
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("이전 버전 자료가 있다는 안내를 받은 경우에만 사용합니다."))
        self.backup, self.password = QLineEdit(), QLineEdit()
        self.password.setEchoMode(QLineEdit.EchoMode.Password)
        self.password.setPlaceholderText("이전 백업 비밀번호")
        browse = QPushButton("이전 백업 선택")
        browse.clicked.connect(self.choose_backup)
        layout.addWidget(self.backup)
        layout.addWidget(browse)
        layout.addWidget(self.password)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def choose_backup(self):
        path, _ = QFileDialog.getOpenFileName(self, "이전 백업 선택", "", "이전 백업 (*.json)")
        if path:
            self.backup.setText(path)


class MainWindow(QMainWindow):
    def __init__(self, vault_path=None):
        super().__init__()
        self.setWindowTitle("나무팜 PC 관리 · 견적 요청과 판매")
        self.resize(1360, 900)
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
            if self.message.objectName() == "serverLogText":
                self.message.setStyleSheet("")
            else:
                self.message.setStyleSheet("color:#a33827;font-weight:600")
        else:
            if on_success:
                on_success(value)
            elif isinstance(value, dict) and "requests" in value:
                self.apply_view(value)
                self.conflict = False
                self.message.setText("SYNC OK  >  서버 동기화 완료 · " + timestamp(datetime.now(SEOUL).timestamp() * 1000))
        if isinstance(value, dict) and value.get("copyError"):
            self.message.setText(value["copyError"])
        self.update_controls()

    def open_vault(self):
        if self.manager or self.task:
            return
        try:
            if not self.vault_path.exists():
                self.run_job("PC 연결 준비 중…", lambda: self.unlocked(Vault.create_device(self.vault_path)), self.accept_manager, False)
            elif Vault.device_managed(self.vault_path):
                self.run_job("PC 자료 여는 중…", lambda: self.unlocked(Vault.open_device(self.vault_path)), self.accept_manager, False)
            else:
                dialog = UnlockDialog(self.vault_path, self)
                if dialog.exec() != QDialog.DialogCode.Accepted:
                    return
                password = dialog.password.text()
                dialog.password.clear()
                def migrate():
                    vault = Vault.unlock(self.vault_path, password)
                    vault.use_device_protection()
                    return self.unlocked(vault)
                self.run_job("기존 자료를 자동 연결 방식으로 전환 중…", migrate, self.accept_manager, False)
        except Exception:
            QMessageBox.warning(self, "PC 자료 확인", "기존 보관함을 열지 못했습니다. 파일과 Windows 계정을 확인해주세요.")

    def restore_legacy(self):
        if not self.manager or self.task:
            return
        dialog = LegacyImportDialog(self)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        path, password = dialog.backup.text(), dialog.password.text()
        dialog.password.clear()
        def restore():
            try:
                with Path(path).open("rb") as source:
                    raw = source.read(20001)
                require(len(raw) < 20000, "이전 백업 파일이 너무 큽니다.")
                return self.manager.restore_legacy(raw.decode("utf-8"), password)
            except (OSError, UnicodeError):
                raise UserError("이전 백업 파일을 읽을 수 없습니다.") from None
        self.run_job("이전 자료 확인 중…", restore, self.apply_view)

    @staticmethod
    def unlocked(vault):
        manager = Manager(vault)
        return manager, manager.view()

    def accept_manager(self, result):
        self.manager, data = result
        self.url.setText(self.manager.api.url)
        self.apply_view(data)
        self.message.setStyleSheet("")
        self.message.setText(
            "READY  >  연결 정보 복원됨 · ‘서버 동기화’를 눌러 최신 견적요청을 가져오세요."
            if data["paired"] else
            "처음 한 번만 연결파일을 불러오면 다음 실행부터 연결 정보가 자동으로 복원됩니다."
        )

    def update_controls(self):
        ready = self.manager is not None and self.task is None
        paired = ready and self.data["paired"]
        self.tabs.setEnabled(self.task is None)
        self.sync_button.setEnabled(paired)
        self.open_button.setEnabled(self.manager is None and self.task is None)
        self.legacy_button.setEnabled(ready and self.data["paired"])
        self.connection_file_button.setEnabled(ready)
        self.copy_button.setEnabled(ready)
        self.pair_button.setEnabled(ready and not self.data["paired"])
        self.url.setEnabled(ready and not self.data["paired"])
        self.invite.setEnabled(ready and not self.data["paired"])
        pending = bool(self.data["pending"])
        self.delete_button.setEnabled(paired and not pending and self.request_table.currentRow() >= 0)
        editable = paired and not pending and self.selected_sale is not None and self.selected_sale["metadata"]["status"] not in ("배송완료", "구매취소", "판매완료", "주문취소")
        self.sale_editor.setEnabled(editable)
        self.save_button.setEnabled(editable and self.dirty)
        self.next_button.setEnabled(editable and self.selected_sale["metadata"]["status"] in NEXT)
        self.cancel_sale_button.setEnabled(editable and self.selected_sale["metadata"]["status"] in ("입금확인", "구매확정"))
        self.report_button.setEnabled(paired)
        self.resolve_button.setEnabled(paired and pending and self.conflict)
        self.summary.setText(f"문의 {len(self.data['requests'])}건 · 판매 {len(self.data['sales'])}건 · 전송 대기 {len(self.data['pending'])}건" if self.manager else "PC 자료 준비 중")
        self.connection_status.setText("연결 정보 자동 복원됨" if self.data["paired"] else "연결파일 필요")
        self.pending_status.setText("전송 대기는 암호화해 보관합니다. '지금 동기화'로 재시도하세요." if pending else "전송 대기 없음")

    def allow_discard(self):
        if not self.dirty:
            return True
        return QMessageBox.question(self, "미저장 수정", "입력한 수정내용을 버리고 계속할까요?", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) == QMessageBox.StandardButton.Yes

    def manual_sync(self):
        if self.manager and self.allow_discard():
            self.run_job("SYNC  >  구글시트 서버 동기화 중…", self.manager.sync)

    def auto_sync(self):
        if self.manager and self.data["paired"] and self.auto_check.isChecked() and not self.task and not self.dirty and not self.conflict:
            self.run_job("AUTO SYNC  >  서버 동기화 중…", self.manager.sync)

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
        self.request_search.setPlaceholderText("접수번호 · 고객명 · 전화번호 · 메일주소 · 묘목 · 지역 · 요청사항 검색")
        self.request_search.textChanged.connect(self.refresh_requests)
        layout.addWidget(self.request_search)
        self.request_table = table(["접수번호", "접수일", "상태", "고객명", "전화번호", "메일주소", "지역", "견적서요청", "희망 묘목", "요청사항"])
        self.request_table.setMinimumHeight(400)
        header = self.request_table.horizontalHeader()
        header.setStretchLastSection(False)
        header.setSectionResizeMode(8, QHeaderView.ResizeMode.Stretch)
        header.setSectionResizeMode(9, QHeaderView.ResizeMode.Stretch)
        # Long seedling/request text should stay visible inside the row instead of being clipped.
        # Columns 0-7 keep their content-sized widths, while the two stretch columns wrap.
        self.request_table.setWordWrap(True)
        self.request_table.setTextElideMode(Qt.TextElideMode.ElideNone)
        self.request_table.verticalHeader().setSectionResizeMode(QHeaderView.ResizeMode.ResizeToContents)
        self.request_table.itemSelectionChanged.connect(self.update_controls)
        layout.addWidget(self.request_table)
        self.request_actions = QHBoxLayout()
        self.request_actions.addWidget(QLabel("접수번호는 홈페이지·휴대폰과 동일합니다. 수동 견적·문자·PDF 발송은 휴대폰에서 처리합니다."))
        self.request_actions.addStretch()
        self.delete_button = QPushButton("견적요청 삭제")
        self.delete_button.clicked.connect(self.delete_request)
        self.request_actions.addWidget(self.delete_button)
        layout.addLayout(self.request_actions)
        self.tabs.addTab(page, "온라인 견적 요청함")

    def refresh_requests(self):
        query = self.request_search.text().casefold()
        self.request_rows = [r for r in self.data["requests"] if r.get("status", "신규") == "신규"
                             and query in (r["requestId"] + r.get("receiptNumber", "") + str(r["personal"])).casefold()]
        self.request_table.blockSignals(True)
        fill_table(self.request_table, [(r.get("receiptNumber") or r["requestId"], timestamp(r["createdAt"]), r.get("status", "신규"), r["personal"]["customerName"], r["personal"]["customerPhone"],
                   r["personal"].get("customerEmail") or "—", r["personal"]["region"],
                   "요청" if r["personal"].get("formalQuoteRequested") else "—",
                   ", ".join(f"{i['species']} {i['quantity']}주" for i in r["personal"]["items"]),
                   r["personal"].get("message") or "—") for r in self.request_rows])
        self.request_table.clearSelection()
        self.request_table.setCurrentCell(-1, -1)
        self.request_table.blockSignals(False)
        self.update_controls()

    def delete_request(self):
        index = self.request_table.currentRow()
        if not 0 <= index < len(self.request_rows):
            return
        if QMessageBox.question(self, "문의 삭제", "선택 문의와 연결된 미구매 견적 사본을 삭제할까요?\n휴대폰은 다음 요청함 동기화 때 반영합니다.", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
            return
        request_id = self.request_rows[index]["requestId"]
        self.run_job("문의 삭제 서버 확인 중…", lambda: self.manager.close_request(request_id), self.request_deleted)

    def request_deleted(self, value):
        self.apply_view(value)
        self.message.setText("문의 삭제를 서버에서 확인했습니다. 휴대폰은 다음 요청함 동기화 때 반영합니다.")

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
        self.next_button = QPushButton("배송 완료")
        self.next_button.clicked.connect(self.advance_sale)
        self.cancel_sale_button = QPushButton("구매 취소")
        self.cancel_sale_button.clicked.connect(self.cancel_sale)
        buttons.addWidget(self.save_button)
        buttons.addWidget(self.next_button)
        buttons.addWidget(self.cancel_sale_button)
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
            self.sale_info.setText(f"{meta['orderNumber']} · {meta['status']}\n매출 {money(meta['totalPrice'])} / 세액 {money(meta['taxAmount'])}\n입금 {timestamp(meta['paidAt'])} · 배송 {timestamp(meta['deliveredAt'])}")
            self.sale_items.setPlainText("\n".join(f"{i['species']} · {i['quantity']:,}주 × {money(i['unitPrice'])}" for i in meta["items"]))
            self.next_button.setText({"구매확정": "배송 완료", "입금확인": "배송 완료", "배송확인": "배송 완료"}.get(meta["status"], "처리 종료"))
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
        if target != "배송완료":
            return
        if QMessageBox.question(self, "배송완료", "실제 배송과 원가·비용 확인을 마쳤나요?", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) == QMessageBox.StandardButton.Yes:
            self.save_sale(target)

    def cancel_sale(self):
        if not self.selected_sale:
            return
        if self.selected_sale["metadata"]["status"] not in ("입금확인", "구매확정"):
            return
        if QMessageBox.question(self, "구매취소", "이 판매를 구매취소로 처리할까요?", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) == QMessageBox.StandardButton.Yes:
            self.save_sale("구매취소")

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
        layout.addWidget(QLabel("배송완료일의 한국 시간 기준 · 매출은 세액 제외 · 확인한 원가를 기준으로 매출이익 계산"))
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
        self.open_button = QPushButton("PC 자료 다시 열기")
        self.open_button.clicked.connect(self.open_vault)
        layout.addWidget(self.open_button)
        self.connection_status = QLabel("연결파일 필요")
        layout.addWidget(self.connection_status)
        self.connection_file_button = QPushButton("연결파일 불러오기 (최초 1회)")
        self.connection_file_button.clicked.connect(self.connect_file)
        layout.addWidget(self.connection_file_button)
        self.copy_button = QPushButton("로컬 엑셀 사본 열기")
        self.copy_button.clicked.connect(self.open_request_copy)
        layout.addWidget(self.copy_button)
        self.advanced_connection = QWidget()
        advanced_layout = QVBoxLayout(self.advanced_connection)
        toggle = QPushButton("이전 연결코드 방식 표시")
        toggle.clicked.connect(lambda: self.advanced_connection.setVisible(not self.advanced_connection.isVisible()))
        layout.addWidget(toggle)
        form = QFormLayout()
        self.url = QLineEdit(DEFAULT_URL)
        form.addRow("Apps Script 웹 앱 주소", self.url)
        self.invite = QLineEdit()
        self.invite.setEchoMode(QLineEdit.EchoMode.Password)
        self.invite.setMaxLength(64)
        self.invite.setPlaceholderText("관리자에게 받은 PC 연결 코드를 입력하세요")
        form.addRow("PC 연결 코드", self.invite)
        advanced_layout.addLayout(form)
        self.pair_button = QPushButton("PC 연결하고 처음 동기화")
        self.pair_button.clicked.connect(self.pair)
        advanced_layout.addWidget(self.pair_button)
        layout.addWidget(self.advanced_connection)
        self.advanced_connection.hide()
        self.auto_check = QCheckBox("60초마다 자동 동기화 · 수정 중에는 입력내용을 유지합니다")
        self.auto_check.setChecked(False)
        layout.addWidget(self.auto_check)
        self.pending_status = QLabel("전송 대기 없음")
        layout.addWidget(self.pending_status)
        self.resolve_button = QPushButton("수정 충돌 확인")
        self.resolve_button.clicked.connect(self.resolve_conflict)
        layout.addWidget(self.resolve_button)
        self.legacy_button = QPushButton("이전 자료 복원 (필요한 경우)")
        self.legacy_button.clicked.connect(self.restore_legacy)
        layout.addWidget(self.legacy_button)
        note = QLabel("연결파일은 이 PC에서 최초 한 번만 불러옵니다. 연결 정보는 Windows 계정으로 보호해 저장하며 다음 실행부터 자동 복원합니다.\n‘지금 동기화’를 누르면 Google Sheet의 최신 견적요청을 내려받고 로컬 조회용 엑셀 사본도 함께 갱신합니다.\n로컬 엑셀 사본은 조회용이며 직접 수정한 내용은 서버에 올리지 않습니다.\n엑셀 사본 위치: " + str(self.vault_path.with_name("나무팜-견적요청.xlsx")))
        note.setWordWrap(True)
        note.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        layout.addWidget(note)
        layout.addStretch()
        self.tabs.addTab(page, "연결·설정")

    def connect_file(self):
        path, _ = QFileDialog.getOpenFileName(self, "나무팜 연결파일", "", "연결파일 (*.json);;모든 파일 (*)")
        if not path:
            return
        def work():
            try:
                with Path(path).open("rb") as source:
                    raw = source.read(8193)
            except OSError:
                raise UserError("연결파일을 읽을 수 없습니다.") from None
            return self.manager.connect_file(raw)
        def connected(value):
            self.url.setText(self.manager.api.url)
            self.apply_view(value)
            self.message.setText("연결과 동기화를 마쳤습니다. 저장된 자료는 오프라인에서도 볼 수 있습니다.")
        self.run_job("연결파일로 연결하고 자료 가져오는 중…", work, connected)

    def open_request_copy(self):
        def opened(value):
            self.apply_view(value)
            if not value.get("copyError"):
                QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.manager.request_copy_path)))
                self.message.setText("로컬 엑셀 사본을 열었습니다. 수정한 내용은 시트에 반영되지 않습니다.")
        self.run_job("로컬 엑셀 사본 준비 중…", self.manager.local_copy_view, opened)

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
    app.setStyleSheet("""
        QPushButton {
            padding:7px 12px 8px 12px;
            border:1px solid #c9d6cb;
            border-bottom:3px solid #aebfb2;
            border-radius:7px;
            font-weight:600;
        }
        QPushButton:hover {background:#eaf1e7;border-color:#94ad99;border-bottom-color:#78967f;}
        QPushButton:pressed {
            background:#d8e6d6;
            border:1px solid #8da392;
            border-top:3px solid #708c76;
            border-bottom:1px solid #a8b8aa;
            padding:9px 12px 6px 12px;
        }
        QPushButton:disabled {color:#9aa69d;border-color:#e1e6df;border-bottom:2px solid #d9dfd9;}
        QLineEdit,QComboBox,QDateEdit {padding:5px;}
        QTabBar::tab {padding:10px 20px;}
        QGroupBox {font-weight:600;}
    """)
    from .workspace import SellerWindow
    window = SellerWindow()
    # SellerWindow already uses a 1920x1080 (FHD) default size. Open it as a normal window
    # instead of forcing it to the monitor's maximum size.
    window.show()
    if "--self-check" in sys.argv:
        app.processEvents()
        require(window.stack.count() == 7, "PC 화면 초기화에 실패했습니다.")
        from .shipping_excel import check_excel_runtime
        check_excel_runtime()
        window.close()
        return 0
    if "--demo" in sys.argv:
        from .demo import show_demo
        show_demo(window)
        return app.exec()
    QTimer.singleShot(0, window.open_vault)
    return app.exec()

