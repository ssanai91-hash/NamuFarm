"""Qt desktop manager. Network, cryptography and SQLite run on one worker at a time."""
import sys
import copy
import re
from datetime import datetime, timedelta
from pathlib import Path
from .list_filters import ListPeriodFilter, first_date

from PySide6.QtCore import QSettings, QStandardPaths, Qt, QThread, QTimer, Signal, QUrl, Slot
from PySide6.QtGui import QCloseEvent, QRegularExpressionValidator, QDesktopServices
from PySide6.QtCore import QRegularExpression
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QComboBox, QDialog, QDialogButtonBox, QFileDialog,
    QFormLayout, QFrame, QGroupBox, QHBoxLayout, QHeaderView, QLabel, QLineEdit, QMainWindow,
    QMessageBox, QPlainTextEdit, QPushButton, QScrollArea, QSplitter, QTabWidget,
    QTableWidget, QTableWidgetItem, QVBoxLayout, QWidget, QDoubleSpinBox,
)

from .api import DEFAULT_URL
from .commerce import sale_number
from .crypto import SellerKey
from .domain import EDITABLE, NEXT, SEOUL, STATES, MAX_SAFE, profit, require
from .errors import ConflictError, UserError
from .delivery_recovery import DeliveryRecoveryTools
from .service import Manager
from .store import Vault
from .ui_theme import DataTable, StatusMessage, align_table, workspace_style
from .ui_icons import set_button_icon
from .report_excel import write_sales_report


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
    result = DataTable(0, len(headers))
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

    result.setWordWrap(False)
    return result

def fill_table(widget, rows):
    widget.setRowCount(len(rows))
    for index, values in enumerate(rows):
        for column, value in enumerate(values):
            widget.setItem(index, column, QTableWidgetItem(str(value)))
    align_table(widget)


class Task(QThread):
    completed = Signal(object, str, bool)
    progress = Signal(str)

    def __init__(self, work, refresh_on_error=None, parent=None, *, progress_source=None):
        super().__init__(parent)
        self.work, self.refresh_on_error = work, refresh_on_error
        self.progress_source = progress_source

    def run(self):
        source = self.progress_source
        previous = getattr(source, "progress_callback", None)
        if source is not None:
            source.progress_callback = self.progress.emit
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
            if source is not None:
                source.progress_callback = previous
            self.work = self.refresh_on_error = None
            self.progress_source = None


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


class ReportEmailDialog(DeliveryRecoveryTools, QDialog):
    """The export is already saved; mail delivery has its own recoverable ID."""

    def __init__(self, path, recipient, parent=None, *, manager=None, data=b"", subject="", body=""):
        super().__init__(parent)
        self.manager, self.path, self.data = manager, path, data
        self.report_subject, self.report_body = subject, body
        self.profile_task = self.send_task = self.status_task = None
        self.delivery_state = {}
        self.success_confirmed = False
        self.setWindowTitle("엑셀 저장 완료 · 매출 메일")
        self.setMinimumWidth(560)
        layout = QFormLayout(self)
        layout.addRow(QLabel("매출 엑셀은 저장되었습니다. 판매자 메일로 첨부해서 보낼 수 있습니다."))
        layout.addRow("첨부파일", QLabel(path.name))
        self.recipient = QLineEdit(recipient)
        self.recipient.setMaxLength(254)
        self.recipient.setPlaceholderText("판매자 이메일 주소")
        layout.addRow("받는 메일", self.recipient)
        self.message = QPlainTextEdit(body)
        self.message.setReadOnly(True)
        layout.addRow(self.message)
        self.status = QLabel("메일 전송 준비")
        self.status.setWordWrap(True)
        layout.addRow(self.status)
        actions = QHBoxLayout()
        self.send_button = QPushButton("메일 전송")
        self.send_button.clicked.connect(self.send_report)
        self.cancel_button = QPushButton("저장만 · 닫기")
        self.cancel_button.clicked.connect(self.reject)
        actions.addWidget(self.cancel_button)
        actions.addWidget(self.send_button)
        self.setup_delivery_recovery(actions, "report_email")
        layout.addRow(actions)
        self.recipient.textChanged.connect(self.update_recipient)
        self.update_recipient()

    def update_recipient(self):
        pending = self.delivery_state.get("pendingDelivery")
        self.send_button.setEnabled(bool(self.manager and not pending and not self.send_task and not self.status_task and
                                    not self.success_confirmed and re.fullmatch(r"[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+", self.recipient.text().strip())))

    def send_report(self):
        if not self.manager or self.send_task or self.status_task or self.success_confirmed or self.restore_uncertain_delivery():
            return
        recipient = self.recipient.text().strip()
        self.send_button.setEnabled(False)
        self.check_button.setEnabled(False)
        self.recipient.setReadOnly(True)
        self.cancel_button.setEnabled(False)
        self.status.setText("엑셀 저장 완료 · 메일 전송 중")
        task = Task(lambda: self.manager.send_sales_report_email(recipient, self.path.name, self.data,
                                                               self.report_subject, self.report_body), None, self)
        self.send_task = task
        task.completed.connect(self.report_sent)
        task.start()

    @Slot(object, str, bool)
    def report_sent(self, value, error, conflict):
        task = self.send_task
        if task is not None:
            task.wait()
            self.send_task = None
            task.deleteLater()
        self.cancel_button.setEnabled(True)
        if self.restore_uncertain_delivery():
            self.status.setText("엑셀 저장 완료 · 결과 불명 · 결과 확인 필요")
            return
        if error or not isinstance(value, dict):
            self.recipient.setReadOnly(False)
            self.update_recipient()
            self.status.setText("엑셀 저장 완료 · 메일 전송 실패 · " + (error or "응답을 확인하지 못했습니다."))
            return
        self.success_confirmed = True
        self.send_button.setEnabled(False)
        self.status.setText("엑셀 저장 완료 · 메일 전송 완료")

    def delivery_recovered(self, state):
        self.delivery_state = dict(state)
        self.check_button.setEnabled(False)
        self.success_confirmed = state.get("deliveryResolution") == "sent"
        self.recipient.setReadOnly(self.success_confirmed)
        self.message.setEnabled(True)
        self.update_recipient()
        self.status.setText("엑셀 저장 완료 · 이전 메일 발송 완료 확인" if self.success_confirmed else "엑셀 저장 완료 · 발송 미완료 · 다시 보내기 가능")

    def reject(self):
        if self.profile_task or self.send_task or self.status_task:
            self.status.setText("현재 처리가 끝난 뒤 창을 닫아주세요. 엑셀은 저장되었습니다.")
            return
        super().reject()


class MainWindow(QMainWindow):
    def __init__(self, vault_path=None):
        super().__init__()
        self.setWindowTitle("나무팜 PC 관리 · 견적 요청과 판매")
        self.resize(1360, 900)
        from .data_path import portable_vault_path
        self.vault_path = Path(vault_path) if vault_path else portable_vault_path(
            QStandardPaths.writableLocation(QStandardPaths.StandardLocation.AppLocalDataLocation))
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
        self.sync_button = QPushButton("가져오기")
        set_button_icon(self.sync_button, "refresh")
        self.sync_button.clicked.connect(self.manual_sync)
        top.addWidget(self.sync_button)
        layout.addLayout(top)
        self.message = StatusMessage("PC를 연결하면 서버에 저장된 요청·판매 기록을 조회합니다.")
        self.message.setWordWrap(True)
        layout.addWidget(self.message)
        self.tabs = QTabWidget()
        layout.addWidget(self.tabs)
        self.build_requests()
        self.build_sales()
        self.build_reports()
        self.build_connection()
        self.timer = QTimer(self)
        self.timer.setInterval(10 * 60 * 1000)
        self.timer.timeout.connect(self.auto_sync)
        self.timer.start()
        self.update_controls()

    def run_job(self, title, work, on_success=None, refresh_on_error=True):
        if self.task:
            return
        self.message.setStyleSheet("")
        self.message.setText(title)
        refresh = refresh_on_error if callable(refresh_on_error) else (self.manager.view if self.manager and refresh_on_error else None)
        task = Task(work, refresh, self, progress_source=self.manager)
        task.operation_title = title
        self.task = task
        task.progress.connect(lambda message: self.message.setText(message) if self.task is task else None)
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
                self.conflict = bool(value.get("salesSyncConflict"))
                self.message.setText(value.get("transferNotice") or "SYNC OK  >  서버 동기화 완료 · " + timestamp(datetime.now(SEOUL).timestamp() * 1000))
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
            "READY  >  연결 정보 복원됨 · ‘가져오기’를 눌러 최신 견적요청을 확인하세요."
            if data["paired"] else
            "서버 연결 또는 지금 동기화를 누르면 암호 없이 연결됩니다."
        )

    def update_controls(self):
        ready = self.manager is not None and self.task is None
        paired = ready and self.data["paired"]
        self.tabs.setEnabled(self.task is None)
        self.sync_button.setEnabled(ready)
        self.open_button.setEnabled(self.manager is None and self.task is None)
        self.legacy_button.setEnabled(ready and self.data["paired"])
        self.connect_button.setEnabled(ready)
        self.copy_button.setEnabled(ready)
        pending = bool(self.data["pending"])
        self.delete_button.setEnabled(paired and self.request_table.currentRow() >= 0
                                      and not getattr(self, "demo_mode", False))
        self.delete_button.setToolTip(
            "처리가 끝난 뒤 삭제할 수 있습니다." if self.task else
            "가져오기로 서버 연결을 확인해주세요." if not paired else
            "삭제할 견적요청을 선택하세요." if self.request_table.currentRow() < 0 else
            "선택한 견적요청 삭제 · 서버 확인 후 목록에 반영")
        editable = ready and self.selected_sale is not None and self.selected_sale["metadata"]["status"] not in ("배송완료", "구매취소", "판매완료", "주문취소")
        self.sale_editor.setEnabled(editable)
        self.save_button.setEnabled(editable and self.dirty)
        self.next_button.setEnabled(editable and self.selected_sale["metadata"]["status"] in NEXT)
        self.cancel_sale_button.setEnabled(editable and self.selected_sale["metadata"]["status"] in ("입금확인", "구매확정"))
        self.report_button.setEnabled(ready)
        self.report_export_button.setEnabled(self.task is None)
        self.resolve_button.setEnabled(paired and pending and self.conflict)
        self.connection_status.setText("연결 정보 저장됨" if self.data["paired"] else "서버 연결 필요")
        self.pending_status.setText(f"전송 대기 {len(self.data['pending']):,}건 · 자동으로 서버에 반영합니다(실패 시 ‘서버에 올리기’)" if pending else "전송 대기 0건")

    def allow_discard(self):
        if not self.dirty:
            return True
        return QMessageBox.question(self, "미저장 수정", "입력한 수정내용을 버리고 계속할까요?", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) == QMessageBox.StandardButton.Yes

    def manual_sync(self):
        if self.manager and self.allow_discard():
            self.run_job("SYNC  >  서버 동기화 중…",
                         self.manager.connect)

    def auto_sync(self):
        if self.manager and self.data["paired"] and self.auto_check.isChecked() and not self.task and not self.dirty and not self.conflict:
            self.run_job("AUTO SYNC  >  서버 동기화 중…", self.manager.auto_sync)

    def apply_view(self, value):
        self.data = value
        self.conflict = self.conflict or bool(value.get("salesSyncConflict"))
        self.dirty = False
        self.refresh_requests()
        self.refresh_sales()
        self.update_controls()
        self.refresh_payment_summary()

    def build_requests(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(8)
        self.request_search = QLineEdit()
        self.request_search.setPlaceholderText("고객명 · 접수번호 · 연락처 · 묘목 검색")
        self.request_search.setToolTip("접수번호·고객명·연락처·메일주소·묘목·지역·요청사항을 검색합니다.")
        self.request_search.textChanged.connect(self.refresh_requests)
        layout.addWidget(self.request_search)
        self.request_table = table(["접수번호", "접수일", "상태", "고객명", "전화번호", "메일주소", "지역", "공식견적서", "희망 묘목", "요청사항"])
        self.request_table.setMinimumHeight(180)
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
        layout.addWidget(self.request_table, 1)
        self.request_actions = QHBoxLayout()
        self.delete_button = QPushButton("삭제")
        self.delete_button.setObjectName("requestDelete")
        self.delete_button.setMinimumWidth(140)
        set_button_icon(self.delete_button, "trash", "#9c3c2e")
        self.delete_button.clicked.connect(self.delete_request)
        self.request_actions.addWidget(self.delete_button)
        self.request_actions.addStretch()
        layout.addLayout(self.request_actions)
        self.tabs.addTab(page, "온라인 견적 요청함")

    def refresh_requests(self):
        query = self.request_search.text().casefold()
        self.request_rows = [r for r in self.data["requests"] if r.get("status", "신규") == "신규"
                             and query in (r["requestId"] + r.get("receiptNumber", "") + str(r["personal"])).casefold()]
        self.request_table.set_empty_message(
            "검색 조건에 맞는 견적요청이 없습니다.\n검색어를 지우거나 가져오기를 확인하세요." if query else
            "표시할 견적요청이 없습니다.\n가져오기로 최신 요청을 확인하세요.")
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
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(10)
        bar = QHBoxLayout()
        self.report_search = QLineEdit()
        self.report_search.setPlaceholderText("고객명 · 전화번호 · 판매번호 검색")
        self.report_search.setClearButtonEnabled(True)
        self.report_search.setMinimumWidth(150)
        self.report_search.setMaximumWidth(400)
        bar.addWidget(self.report_search, 2)
        bar.addStretch(1)
        self.report_period = ListPeriodFilter("month")
        bar.addWidget(self.report_period)
        self.unit = QComboBox()
        self.unit.setAccessibleName("판매금액 집계 단위")
        for title, key in (("일별", "day"), ("주별", "week"), ("월별", "month")):
            self.unit.addItem(title, key)
        bar.addWidget(self.unit)
        self.report_button = QPushButton("새로고침")
        self.report_button.clicked.connect(self.fetch_report)
        set_button_icon(self.report_button, "refresh")
        bar.addWidget(self.report_button)
        reset = QPushButton("초기화")
        reset.clicked.connect(self.reset_report_filters)
        bar.addWidget(reset)
        self.report_export_button = QPushButton("엑셀 저장")
        self.report_export_button.clicked.connect(self.export_sales_report)
        set_button_icon(self.report_export_button, "document")
        bar.addWidget(self.report_export_button)
        layout.addLayout(bar)
        self.report_summary = QLabel()
        self.report_summary.setObjectName("muted")
        self.report_summary.setWordWrap(True)
        layout.addWidget(self.report_summary)
        summary = QFrame()
        summary.setObjectName("card")
        summary_layout = QHBoxLayout(summary)
        summary_layout.setContentsMargins(16, 12, 16, 12)
        title = QLabel("총 판매금액")
        title.setObjectName("sectionTitle")
        summary_layout.addWidget(title)
        self.payment_summary = QLabel("0건")
        self.payment_summary.setObjectName("muted")
        summary_layout.addWidget(self.payment_summary, 1)
        self.report_total = QLabel("0원")
        self.report_total.setObjectName("sectionTitle")
        self.report_total.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        summary_layout.addWidget(self.report_total)
        layout.addWidget(summary)
        self.report_table = table(["날짜 / 기간", "판매 건수", "판매금액 합계"])
        self.report_table.set_column_layout(((0, 2, 230), (1, 1, 120), (2, 2, 180)))
        self.report_table.set_empty_message("선택한 조건에 맞는 판매 기록이 없습니다.")
        layout.addWidget(self.report_table, 1)
        self.report_search.textChanged.connect(self.refresh_payment_summary)
        self.report_period.changed.connect(self.report_period_changed)
        self.unit.currentIndexChanged.connect(self.refresh_payment_summary)
        self.tabs.addTab(page, "매출")
        self.refresh_payment_summary()

    def reset_report_filters(self):
        self.report_search.blockSignals(True)
        self.unit.blockSignals(True)
        self.report_search.clear()
        self.unit.setCurrentIndex(0)
        self.report_search.blockSignals(False)
        self.unit.blockSignals(False)
        self.report_period.reset()

    def report_period_changed(self):
        self.refresh_payment_summary()

    def refresh_payment_summary(self):
        if not hasattr(self, "report_total"):
            return
        start, end = self.report_period.bounds()
        query = self.report_search.text().strip().casefold()
        phone_query = query.replace("-", "").replace(" ", "")
        unit = self.unit.currentData()
        buckets = {}
        count = total = 0
        for row in self.data.get("sales", []):
            meta = row["metadata"]
            if meta.get("status") not in ("입금확인", "구매확정", "배송확인", "배송완료", "판매완료"):
                continue
            day = first_date(meta.get("paidAt"), meta.get("purchasedAt"), meta.get("orderDate"))
            if day is None or (start is not None and not start <= day <= end):
                continue
            order = row["private"]["order"]
            searchable = " ".join((sale_number(row), meta["orderNumber"],
                                   order.get("customerName", ""), order.get("receiverName", ""),
                                   order.get("customerPhone", ""), order.get("sourceQuoteNumber", ""))).casefold()
            if query and query not in searchable and not (phone_query.isdigit() and phone_query in
                    order.get("customerPhone", "").replace("-", "").replace(" ", "")):
                continue
            period = day - timedelta(days=day.weekday()) if unit == "week" else day.replace(day=1) if unit == "month" else day
            bucket = buckets.setdefault(period, [0, 0])
            amount = meta["totalPrice"] + meta.get("taxAmount", 0)
            bucket[0] += 1
            bucket[1] += amount
            count += 1
            total += amount
        rows = []
        export_rows = []
        for period, (orders, amount) in sorted(buckets.items(), reverse=True):
            title = (f"{period:%Y.%m.%d} ~ {period + timedelta(days=6):%Y.%m.%d}" if unit == "week" else
                     f"{period:%Y.%m}" if unit == "month" else f"{period:%Y.%m.%d}")
            rows.append((title, f"{orders:,}건", money(amount)))
            export_rows.append((title, orders, amount))
        fill_table(self.report_table, rows)
        for row in range(self.report_table.rowCount()):
            for column in range(3):
                self.report_table.item(row, column).setTextAlignment(Qt.AlignmentFlag.AlignCenter)
        self.payment_summary.setText(f"{count:,}건")
        self.report_total.setText(money(total))
        self.report_export_data = {"period": self.report_period.description(), "unit": self.unit.currentText(),
                                   "query": self.report_search.text().strip(), "rows": export_rows,
                                   "count": count, "amount": total}
        self.report_summary.setText(
            f"{self.report_period.description()} · 입금확인일 기준(이전 자료는 주문일) · 취소 제외\n"
            "주문 총금액 합계 · 선불 택배비 포함 / 후불 제외 · 주별은 월요일~일요일")
        if hasattr(self, "set_page_count") and getattr(self, "workspace_ready", False):
            self.set_page_count(self.PAGE_REPORTS, count)

    def fetch_report(self):
        # Display the local DB immediately; this action never writes to the server.
        self.refresh_payment_summary()

    def export_sales_report(self):
        if self.task:
            return
        self.refresh_payment_summary()
        report = copy.deepcopy(self.report_export_data)
        settings = QSettings("NamuFarm", "Desktop")
        folder = str(settings.value("salesReportExportFolder", "") or
                     QStandardPaths.writableLocation(QStandardPaths.StandardLocation.DocumentsLocation) or Path.cwd())
        name = f"나무팜_매출_{report['unit']}_{datetime.now(SEOUL):%Y%m%d-%H%M%S}.xlsx"
        filename, _ = QFileDialog.getSaveFileName(self, "매출 엑셀 저장", str(Path(folder) / name), "엑셀 파일 (*.xlsx)")
        if not filename:
            return
        path = Path(filename)
        if path.suffix.lower() != ".xlsx":
            path = Path(str(path) + ".xlsx")
            if path.exists() and QMessageBox.question(self, "파일 덮어쓰기", f"{path.name} 파일을 덮어쓸까요?",
                    QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                    QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
        self.run_job("매출 엑셀 저장 중…", lambda: write_sales_report(path, report),
                     lambda data: self.sales_report_exported(path, report, data), False)

    def sales_report_exported(self, path, report, data):
        settings = QSettings("NamuFarm", "Desktop")
        settings.setValue("salesReportExportFolder", str(path.parent))
        self.message.setText(f"매출 엑셀 저장 완료 · {path}")
        QTimer.singleShot(0, lambda: self.offer_sales_report_email(path, report, data))

    def offer_sales_report_email(self, path, report, data):
        settings = QSettings("NamuFarm", "Desktop")
        subject = f"[나무팜] {report['unit']} 매출 집계 · {report['period']}"
        body = (f"나무팜 매출 집계 엑셀을 첨부합니다.\n\n조회 기간: {report['period']}\n집계 단위: {report['unit']}\n"
                f"검색어: {report['query'] or '전체'}\n판매 건수: {report['count']:,}건\n판매금액 합계: {money(report['amount'])}\n\n"
                "입금확인일 기준 · 선불 택배비 포함 / 후불 제외 · 취소 제외")
        dialog = ReportEmailDialog(path, str(settings.value("salesReportSellerEmail", "") or ""), self,
                                   manager=self.manager, data=data, subject=subject, body=body)
        was_active = self.timer.isActive()
        self.timer.stop()
        try:
            dialog.exec()
            settings.setValue("salesReportSellerEmail", dialog.recipient.text().strip())
            if dialog.success_confirmed:
                self.message.setText("매출 엑셀 저장 · 메일 전송 완료")
        finally:
            dialog.deleteLater()
            if was_active:
                self.timer.start()

    def sales_report_email_sent(self, path, result):
        self.message.setText(f"매출 엑셀 저장 · 메일 전송 완료 · {result['recipientEmail']}")
        QMessageBox.information(self, "매출 엑셀 메일 전송 완료", f"받는 메일: {result['recipientEmail']}\n첨부파일: {path.name}")

    def build_connection(self):
        page = QWidget()
        outer = QVBoxLayout(page)
        outer.setContentsMargins(0, 0, 0, 0)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        content = QWidget()
        content.setMaximumWidth(920)
        scroll.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignTop)
        layout = QVBoxLayout(content)
        layout.setContentsMargins(0, 0, 12, 0)
        layout.setSpacing(14)

        status = QGroupBox("서버 연결 상태")
        status_layout = QVBoxLayout(status)
        status_layout.setSpacing(8)
        self.connection_status = QLabel("서버 연결 필요")
        self.last_sync_label = QLabel("이번 실행에서 확인 전")
        self.last_sync_label.setObjectName("muted")
        self.last_sync_label.setWordWrap(True)
        self.pending_status = QLabel("전송 대기 없음")
        self.pending_status.setWordWrap(True)
        summary = QFormLayout()
        summary.setVerticalSpacing(8)
        summary.addRow("연결", self.connection_status)
        summary.addRow("최근 가져오기", self.last_sync_label)
        summary.addRow("변경사항", self.pending_status)
        status_layout.addLayout(summary)
        status_actions = QHBoxLayout()
        status_actions.setSpacing(12)
        self.connect_button = QPushButton("연결 확인 · 가져오기")
        self.connect_button.setObjectName("primary")
        self.connect_button.clicked.connect(self.connect_server)
        set_button_icon(self.connect_button, "refresh", "#ffffff")
        self.connect_button.setToolTip("연결을 확인하고 최신 자료와 전송 대기를 함께 처리합니다.")
        self.open_button = QPushButton("PC 자료 다시 열기")
        self.open_button.clicked.connect(self.open_vault)
        status_actions.addWidget(self.connect_button)
        status_actions.addWidget(self.open_button)
        status_actions.addStretch()
        status_layout.addLayout(status_actions)
        layout.addWidget(status)

        local = QGroupBox("화면과 로컬 자료")
        local_layout = QVBoxLayout(local)
        local_layout.setSpacing(12)
        self.copy_button = QPushButton("로컬 엑셀 사본 열기")
        self.copy_button.clicked.connect(self.open_request_copy)
        set_button_icon(self.copy_button, "folder")
        local_layout.addWidget(self.copy_button, 0, Qt.AlignmentFlag.AlignLeft)
        self.auto_check = QCheckBox("10분마다 견적요청 가져오기 · 판매자료 서버 반영")
        self.auto_check.setToolTip("최신 자료를 가져오고 전송 대기 중인 변경사항을 서버에 반영합니다.")
        self.auto_check.setChecked(True)
        self.large_font_check = QCheckBox("큰 글씨로 보기")
        local_layout.addWidget(self.auto_check)
        local_layout.addWidget(self.large_font_check)
        note = QLabel("로컬 엑셀은 조회용 사본입니다. 엑셀에서 수정한 내용은 서버에 올라가지 않습니다.")
        note.setObjectName("muted")
        note.setWordWrap(True)
        note.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        local_layout.addWidget(note)
        layout.addWidget(local)

        toggle = QPushButton("서버 주소 · 고급 설정 펼치기")
        toggle.setCheckable(True)
        set_button_icon(toggle, "settings")
        layout.addWidget(toggle, 0, Qt.AlignmentFlag.AlignLeft)
        self.advanced_connection = QGroupBox("서버 주소와 자료 복구")
        advanced = QVBoxLayout(self.advanced_connection)
        advanced.setSpacing(12)
        form = QFormLayout()
        self.url = QLineEdit(DEFAULT_URL)
        self.url.setReadOnly(True)
        self.url.setAccessibleName("현재 연결된 서버 주소")
        form.addRow("서버 주소", self.url)
        advanced.addLayout(form)
        copy_path = QLabel("엑셀 사본 위치: " + str(self.vault_path.with_name("나무팜-견적요청.xlsx")))
        copy_path.setObjectName("muted")
        copy_path.setWordWrap(True)
        copy_path.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        advanced.addWidget(copy_path)
        self.resolve_button = QPushButton("수정 충돌 확인")
        self.resolve_button.clicked.connect(self.resolve_conflict)
        self.legacy_button = QPushButton("이전 자료 복원")
        self.legacy_button.clicked.connect(self.restore_legacy)
        advanced.addWidget(self.resolve_button, 0, Qt.AlignmentFlag.AlignLeft)
        advanced.addWidget(self.legacy_button, 0, Qt.AlignmentFlag.AlignLeft)
        help_text = QLabel("연결 실패 시 서버 주소를 확인해주세요. 이전 자료 복원은 복원이 필요한 경우에만 사용합니다.")
        help_text.setWordWrap(True)
        help_text.setObjectName("muted")
        advanced.addWidget(help_text)
        toggle.toggled.connect(self.advanced_connection.setVisible)
        toggle.toggled.connect(lambda opened: toggle.setText("서버 주소 · 고급 설정 접기" if opened else "서버 주소 · 고급 설정 펼치기"))
        layout.addWidget(self.advanced_connection)
        self.advanced_connection.hide()
        layout.addStretch()
        scroll.setWidget(content)
        outer.addWidget(scroll)
        self.tabs.addTab(page, "연결·설정")

    def connect_server(self):
        if not self.manager:
            return
        def connected(value):
            self.url.setText(self.manager.api.url)
            self.apply_view(value)
            self.message.setText("연결과 동기화를 마쳤습니다. 저장된 자료는 오프라인에서도 볼 수 있습니다.")
        self.run_job("서버 연결 및 동기화 중…", self.manager.connect, connected)

    def open_request_copy(self):
        def opened(value):
            self.apply_view(value)
            if not value.get("copyError"):
                QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.manager.request_copy_path)))
                self.message.setText("로컬 엑셀 사본을 열었습니다. 수정한 내용은 시트에 반영되지 않습니다.")
        self.run_job("로컬 엑셀 사본 준비 중…", self.manager.local_copy_view, opened)

    def resolve_conflict(self):
        self.run_job("서버 최신 판매 기록 확인 중…", self.manager.conflict_preview, self.confirm_conflict)

    def confirm_conflict(self, preview):
        dialog = QDialog(self)
        dialog.setWindowTitle("동시 수정 충돌")
        dialog.resize(800, 500)
        layout = QVBoxLayout(dialog)
        layout.addWidget(QLabel("PC 수정은 아직 서버에 반영되지 않았습니다. 서버 내용을 확인하고 선택하세요."))
        comparison = table(["항목", "PC 전송 대기", "서버 최신"])
        local_order = self.manager.decode_sale(preview["operation"]["payload"])["order"]
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
    if QApplication.instance() is None:
        QApplication.setAttribute(Qt.ApplicationAttribute.AA_ShareOpenGLContexts)
    app = QApplication.instance() or QApplication(sys.argv)
    app.setApplicationName("NamuFarmDesktop")
    app.setOrganizationName("NamuFarm")
    app.setStyle("Fusion")
    app.setStyleSheet(workspace_style())
    from .workspace import SellerWindow
    window = SellerWindow()
    # Fit the normal window to the available desktop, including display scaling.
    window.show()
    if "--self-check" in sys.argv:
        app.processEvents()
        require(window.stack.count() == len(window.PAGES), "PC 화면 초기화에 실패했습니다.")
        from importlib.resources import files
        stamp = files("namufarm_desktop").joinpath("assets", "quote_stamp.png").read_bytes()
        require(stamp.startswith(b"\x89PNG\r\n\x1a\n"), "공식 견적서 직인 이미지를 확인해주세요.")
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





