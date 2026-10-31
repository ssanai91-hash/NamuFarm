"""Nursery pages read the PC cache; only an explicit sync uses the server."""
from PySide6.QtCore import QSize, Qt, Signal
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (QButtonGroup, QCheckBox, QComboBox, QDialog, QDialogButtonBox,
    QFormLayout, QGridLayout, QHBoxLayout, QLabel, QLCDNumber, QLineEdit, QListWidget, QListWidgetItem, QMessageBox,
    QPushButton, QSizePolicy, QSpinBox, QTabWidget, QVBoxLayout, QWidget)

from .app import table, fill_table
from .errors import UserError
from .nursery import INITIALS, NurseryStore, initial
from .species_purposes import (PURPOSES, category_error, format_category, matches_purpose, parse_category,
                               suggest_purpose_labels)
from .ui_theme import TOKENS
from .visuals import CONCEPTS, concept_icon, farm_avatar, species_icon

# English initials follow how the letter is read in Korean (JB → 제이 → ㅈ), as in the Android app.
_LATIN_INITIALS = dict(zip("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "ㅇㅂㅅㄷㅇㅇㅈㅇㅇㅈㅋㅇㅇㅇㅇㅍㅋㅇㅇㅌㅇㅂㄷㅇㅇㅈ"))


def stock_initial(name):
    first = initial(name or "")
    if first in INITIALS:
        return first
    return _LATIN_INITIALS.get((name or " ").strip()[:1].upper(), "ㅇ")


class DigitalClock(QLCDNumber):
    def __init__(self):
        super().__init__(8)
        self.setObjectName('sidebarTime')
        self.setSegmentStyle(QLCDNumber.SegmentStyle.Flat)
        self.setFrameShape(QLCDNumber.Shape.NoFrame)
        self.setMinimumHeight(48)
        self.setStyleSheet('color:#ffdc78;background:#153b2b;border-radius:6px;')
        self.setText('00:00:00')

    def setText(self, text):
        self._text = text
        self.display(text)
        self.setAccessibleName('현재 시각 ' + text)

    def text(self):
        return self._text


class SpeciesPurposeEditor(QWidget):
    """Defaults and legacy edits stay in the dialog until its Save action."""
    changed = Signal()

    def __init__(self, category='', name='', new_species=False, parent=None):
        super().__init__(parent)
        category = category or ''
        parts = parse_category(category)
        self.blank_category = not category.strip()
        self.new_species = new_species
        self.manually_changed = False
        self.selected_labels = set(suggest_purpose_labels(name) if self.blank_category else parts.selected_labels)
        self.legacy_values = list(parts.legacy_values)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(6)
        self.hint = QLabel('홈페이지 기준으로 미리 골라 두었어요. 저장해야 반영돼요.')
        self.hint.setObjectName('infoCaption')
        self.hint.setWordWrap(True)
        layout.addWidget(self.hint)
        choices = QHBoxLayout()
        choices.setSpacing(6)
        self.buttons = {}
        for purpose in PURPOSES:
            button = QPushButton(purpose.label)
            button.setCheckable(True)
            button.setObjectName('filterTab')
            button.setAccessibleName(purpose.label + ' 용도')
            button.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            button.setStyleSheet('QPushButton{min-height:36px;max-height:36px;padding:0 4px;}')
            button.clicked.connect(lambda checked=False, label=purpose.label: self.choose(label, checked))
            self.buttons[purpose.label] = button
            choices.addWidget(button, 1)
        layout.addLayout(choices)
        legacy = QHBoxLayout()
        self.legacy_label = QLabel()
        self.legacy_label.setObjectName('infoCaption')
        self.legacy_label.setTextFormat(Qt.TextFormat.PlainText)
        self.legacy_label.setWordWrap(True)
        legacy.addWidget(self.legacy_label, 1)
        self.clear_legacy = QPushButton('지우기')
        self.clear_legacy.setAccessibleName('기존 분류 값 지우기')
        self.clear_legacy.setStyleSheet('QPushButton{min-height:36px;max-height:36px;padding:0 8px;}')
        self.clear_legacy.clicked.connect(self.remove_legacy)
        legacy.addWidget(self.clear_legacy)
        layout.addLayout(legacy)
        self.error_label = QLabel()
        self.error_label.setWordWrap(True)
        self.error_label.setStyleSheet('color:' + TOKENS['danger'] + ';')
        layout.addWidget(self.error_label)
        self.refresh()

    def update_name(self, name):
        if self.new_species and not self.manually_changed:
            self.selected_labels = set(suggest_purpose_labels(name))
            self.refresh()

    def choose(self, label, checked):
        self.manually_changed = True
        if checked:
            self.selected_labels.add(label)
        else:
            self.selected_labels.discard(label)
        self.refresh()

    def remove_legacy(self):
        self.legacy_values.clear()
        self.refresh()

    def error(self):
        return category_error(format_category(self.selected_labels, self.legacy_values))

    def value(self):
        value = format_category(self.selected_labels, self.legacy_values)
        if error := category_error(value):
            raise ValueError(error)
        return value

    def refresh(self):
        for label, button in self.buttons.items():
            button.setChecked(label in self.selected_labels)
        self.hint.setVisible(self.blank_category and not self.manually_changed and bool(self.selected_labels))
        self.legacy_label.setText('기존 값: ' + ', '.join(self.legacy_values))
        self.legacy_label.setVisible(bool(self.legacy_values))
        self.clear_legacy.setVisible(bool(self.legacy_values))
        error = self.error()
        self.error_label.setText(error or '')
        self.error_label.setVisible(bool(error))
        self.changed.emit()


class NurseryPage(QWidget):
    def __init__(self, owner, kind):
        super().__init__()
        self.owner, self.kind = owner, kind
        self.rows = []
        self.stock_rows = []
        self.state = None
        self.selected_initial = INITIALS[0] if kind == 'species' else ''
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        self.status = QLabel('PC 자료를 열면 관리 목록이 표시됩니다.')
        self.status.setWordWrap(True)
        layout.addWidget(self.status)
        self.status.hide()
        tools = QHBoxLayout()
        self.search = QLineEdit()
        self.search.setPlaceholderText('농원명 · 전화번호 검색' if kind == 'farms' else '묘목명 검색')
        self.search.textChanged.connect(self.refresh_local)
        tools.addWidget(self.search, 1)
        self.add_button = QPushButton('농원 추가' if kind == 'farms' else '묘목 추가')
        self.add_button.setIcon(concept_icon('농원' if kind == 'farms' else '묘목', 22))
        self.edit_button = QPushButton('수정')
        self.delete_button = QPushButton('농원 삭제' if kind == 'farms' else '사용 중지')
        self.sync_button = QPushButton('동기화')
        self.sync_button.setObjectName('primary')
        self.conflict_button = QPushButton('충돌 확인')
        self.actions = [self.add_button, self.edit_button, self.delete_button,
                        self.sync_button, self.conflict_button]
        for button in self.actions:
            tools.addWidget(button)
        layout.addLayout(tools)
        self.add_button.clicked.connect(lambda: self.edit())
        self.edit_button.clicked.connect(lambda: self.edit(self.selected()))
        self.delete_button.clicked.connect(self.remove)
        self.sync_button.clicked.connect(self.sync)
        self.conflict_button.clicked.connect(self.resolve)
        if kind == 'species':
            initials = QHBoxLayout()
            initials.setSpacing(4)
            self.initial_buttons = {}
            self.initial_group = QButtonGroup(self)
            self.initial_group.setExclusive(True)
            for title in INITIALS:
                btn = QPushButton(title)
                btn.setCheckable(True)
                btn.setChecked(title == self.selected_initial)
                btn.setObjectName('filterTab')
                btn.setStyleSheet('QPushButton{padding-left:4px;padding-right:4px;}')
                btn.clicked.connect(lambda checked=False, v=title: self.choose_initial(v))
                self.initial_group.addButton(btn)
                self.initial_buttons[title] = btn
                initials.addWidget(btn, 1)
            layout.addLayout(initials)
        self.table = table(['순번', '농원명', '주소', '전화번호', '메모', '상태'] if kind == 'farms' else
                           ['순번', '묘목명', '초성', '농원사용명', '분류', '사용', '메모', '상태'])
        self.table.setIconSize(QSize(26, 26))
        self.table.itemSelectionChanged.connect(self.update_controls)
        self.table.itemDoubleClicked.connect(lambda _: self.edit(self.selected()))
        if kind == 'species':
            self.tabs = QTabWidget()
            species_list = QWidget()
            species_layout = QVBoxLayout(species_list)
            species_layout.setContentsMargins(0, 0, 0, 0)
            purposes = QHBoxLayout()
            purposes.addWidget(QLabel('용도'))
            self.purpose_filter = QComboBox()
            self.purpose_filter.setAccessibleName('묘목 용도 필터')
            self.purpose_filter.addItem('전체', '')
            for purpose in PURPOSES:
                self.purpose_filter.addItem(purpose.label, purpose.label)
            self.purpose_filter.currentIndexChanged.connect(self.refresh_local)
            purposes.addWidget(self.purpose_filter)
            purposes.addStretch()
            species_layout.addLayout(purposes)
            species_layout.addWidget(self.table, 1)
            self.tabs.addTab(species_list, concept_icon('묘목', 22), '묘목 목록')
            self.tabs.addTab(self.build_stock(), concept_icon('재고', 22), '재고 (농원별·묘목별)')
            layout.addWidget(self.tabs, 1)
        else:
            layout.addWidget(self.table, 1)
        self.refresh_local()

    def store(self):
        if not self.owner.manager or not hasattr(self.owner.manager, 'vault'):
            raise UserError('PC 자료를 먼저 열어주세요.')
        return NurseryStore(self.owner.manager)

    def selected(self):
        index = self.table.currentRow()
        return self.rows[index] if 0 <= index < len(self.rows) else None

    def choose_initial(self, value):
        self.selected_initial = value
        self.table.clearSelection()
        self.table.setCurrentCell(-1, -1)
        self.refresh_local()

    def refresh_local(self, *_):
        previous = self.selected() if hasattr(self, 'table') else None
        try:
            self.state = self.store().read() if self.owner.manager else None
            self.status.hide()
        except UserError as error:
            self.state = None
            self.status.setText(str(error))
            self.status.show()
        if not hasattr(self, 'table'):
            return
        query = self.search.text().strip().casefold()
        purpose = self.purpose_filter.currentData() if self.kind == 'species' else ''
        rendered = (self.state, query, self.selected_initial, purpose)
        previous_rendered = getattr(self, '_rendered', None)
        if previous_rendered == rendered:
            # Navigation and unrelated jobs must preserve the existing table/selection.
            self.update_controls()
            return
        values = (self.state or {}).get(self.kind, [])
        self.rows = sorted([r for r in values if not r.get('_deleted') and
            (not query or query in ' '.join(str(v) for k, v in r.items() if not k.startswith('_')).casefold()) and
            (self.kind != 'species' or not self.selected_initial or
             initial(r['speciesName']) == self.selected_initial) and
            (self.kind != 'species' or matches_purpose(r.get('category', ''), purpose))],
            key=lambda r: r.get('farmName' if self.kind == 'farms' else 'speciesName', ''))
        pending = {op['key'] for op in (self.state or {}).get('pending', [])}
        display = []
        for i, row in enumerate(self.rows, 1):
            status = '전송 대기' if row['_key'] in pending else '동기화됨'
            if self.kind == 'farms':
                display.append((i, row['farmName'], row['address'], row['phone'], row['memo'], status))
            else:
                display.append((i, row['speciesName'], initial(row['speciesName']), row['farmName'],
                    row['category'], '사용' if row['isActive'] else '중지', row['memo'], status))
        self.table.blockSignals(True)
        self.table.setUpdatesEnabled(False)
        try:
            fill_table(self.table, display)
            # 이름 칸 색 아이콘: 농원은 첫 글자 동그라미, 묘목은 용도 아이콘.
            for index, row in enumerate(self.rows):
                cell = self.table.item(index, 1)
                if cell is not None:
                    cell.setIcon(farm_avatar(row['farmName'], 26) if self.kind == 'farms' else
                                 species_icon(row['speciesName'], row.get('category', ''), 26))
        finally:
            self.table.setUpdatesEnabled(True)
        self.table.clearSelection()
        self.table.setCurrentCell(-1, -1)
        if previous:
            index = next((i for i, r in enumerate(self.rows) if r['_key'] == previous['_key']), None)
            if index is not None:
                self.table.selectRow(index)
        self.table.blockSignals(False)
        if hasattr(self, 'initial_buttons'):
            for title, btn in self.initial_buttons.items():
                btn.setChecked(title == self.selected_initial)
            # Purpose choices affect this table only; preserve inventory selection.
            if previous_rendered is None or previous_rendered[:3] != rendered[:3]:
                self.refresh_stock()
        self._rendered = rendered
        self.update_controls()

    def update_controls(self):
        ready = self.state is not None and not self.owner.task and not getattr(self.owner, 'demo_mode', False)
        for item in self.actions:
            item.setEnabled(ready)
        self.edit_button.setEnabled(ready and self.selected() is not None)
        self.delete_button.setEnabled(ready and self.selected() is not None)
        self.conflict_button.setEnabled(ready and bool((self.state or {}).get('conflict')))
        if hasattr(self, 'stock_add'):
            self.stock_add.setEnabled(bool(ready and self.stock_key))
            selected = 0 <= self.stock_table.currentRow() < len(self.stock_rows)
            self.stock_edit.setEnabled(ready and selected)
            self.stock_zero.setEnabled(ready and selected)

    def changed(self):
        self.owner.refresh_nursery()

    def edit(self, row=None):
        if self.owner.task or self.state is None:
            return
        dialog = QDialog(self)
        dialog.setWindowTitle(('농원' if self.kind == 'farms' else '묘목') + (' 수정' if row else ' 추가'))
        dialog.setMinimumWidth(600 if self.kind == 'species' else 460)
        form = QFormLayout(dialog)
        labels = [('farmName', '농원명'), ('address', '주소'), ('phone', '전화번호'), ('memo', '메모')] if self.kind == 'farms' else [
            ('speciesName', '묘목명'), ('chosung', '초성'), ('farmName', '농원사용명'), ('category', '분류'), ('memo', '메모')]
        inputs = {}
        purpose_editor = None
        for name, title in labels:
            if self.kind == 'species' and name == 'category':
                purpose_editor = SpeciesPurposeEditor((row or {}).get('category', ''),
                    (row or {}).get('speciesName', ''), new_species=row is None)
                form.addRow('용도 (여러 개 선택)', purpose_editor)
                continue
            entry = QLineEdit(str((row or {}).get(name, '')))
            inputs[name] = entry
            form.addRow(title, entry)
        if self.kind == 'species':
            inputs['chosung'].setReadOnly(True)
            inputs['chosung'].setText(initial(inputs['speciesName'].text()))
            inputs['speciesName'].textChanged.connect(lambda text: inputs['chosung'].setText(initial(text)))
            inputs['speciesName'].textChanged.connect(purpose_editor.update_name)
        active = QCheckBox('사용')
        active.setChecked(bool((row or {}).get('isActive', 1)))
        if self.kind == 'species':
            form.addRow('사용 여부', active)
        message = QLabel('')
        message.setWordWrap(True)
        form.addRow(message)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel)
        form.addRow(buttons)
        if purpose_editor is not None:
            def update_category_validation():
                buttons.button(QDialogButtonBox.StandardButton.Save).setEnabled(purpose_editor.error() is None)
            purpose_editor.changed.connect(update_category_validation)
            update_category_validation()
        def save():
            try:
                values = {k: v.text() for k, v in inputs.items()}
                if self.kind == 'species':
                    values['category'] = purpose_editor.value()
                    values.update(isActive=int(active.isChecked()))
                    values['chosung'] = initial(values['speciesName'])
                self.store().save(self.kind, values, row['_key'] if row else None)
            except (UserError, ValueError) as error:
                message.setText(str(error))
                return
            dialog.accept()
            self.changed()
        buttons.accepted.connect(save)
        buttons.rejected.connect(dialog.reject)
        dialog.exec()

    def remove(self):
        row = self.selected()
        if not row or self.owner.task:
            return
        title = '농원 삭제' if self.kind == 'farms' else '묘목 사용 중지'
        if QMessageBox.question(self, title, title + '할까요?\nPC에 먼저 저장하고 동기화할 때 서버에 반영합니다.',
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
            return
        try:
            if self.kind == 'farms':
                self.store().save(self.kind, row, row['_key'], delete=True)
            else:
                self.store().save(self.kind, {**row, 'isActive': 0}, row['_key'])
            self.changed()
        except UserError as error:
            QMessageBox.warning(self, title, str(error))

    def sync(self):
        if self.owner.task or self.state is None:
            return
        def done(count):
            self.changed()
            self.owner.message.setText('농원·묘목 동기화 완료 · PC 변경 %s건 반영' % count)
        self.owner.run_job('농원·묘목 동기화 중…', lambda: self.store().sync(), done,
                           refresh_on_error=lambda: self.store().read())

    def resolve(self):
        def show(preview):
            names = {'farmName': '농원명', 'address': '주소', 'phone': '전화번호', 'memo': '메모',
                'speciesName': '묘목명', 'chosung': '초성', 'category': '분류', 'isActive': '사용 여부',
                'quality': '등급', 'age': '연생', 'size': '규격', 'plantType': '재배형태',
                'quantity': '수량', 'wholesalePrice': '도매단가', 'retailPrice': '판매단가'}
            def summary(row):
                if row is None:
                    return '서버에 항목이 없습니다.'
                return '\n'.join('%s: %s' % (title, row[key]) for key, title in names.items() if key in row)
            text = 'PC 수정과 서버 내용이 다릅니다.\n\nPC\n%s\n\n서버\n%s' % (
                summary(preview['operation']['after']), summary(preview['remote']))
            box = QMessageBox(self)
            box.setWindowTitle('농원·묘목 충돌 확인')
            box.setText(text)
            use = box.addButton('서버 내용 사용', QMessageBox.ButtonRole.AcceptRole)
            keep = box.addButton('PC 자료 유지', QMessageBox.ButtonRole.RejectRole)
            box.setDefaultButton(keep)
            box.exec()
            if box.clickedButton() == use:
                try:
                    self.store().use_server(preview)
                    self.changed()
                except UserError as error:
                    QMessageBox.warning(self, '충돌 확인', str(error))
        if not self.owner.task:
            self.owner.run_job('충돌 내용 확인 중…', lambda: self.store().conflict_preview(), show, False)

    def build_stock(self):
        """재고: [농원별 | 묘목별] + 초성 7×2(기본 ㄱ) 왼쪽 목록, 오른쪽은 기존 재고 표 (2026-10-31 확정, Android와 같은 방식)."""
        self.stock_mode, self.stock_initial, self.stock_key, self.stock_show_all = 'farm', INITIALS[0], None, False
        self.stock_entries = []
        page = QWidget()
        outer = QHBoxLayout(page)
        left_box = QWidget()
        left_box.setMaximumWidth(380)
        left = QVBoxLayout(left_box)
        left.setContentsMargins(0, 0, 8, 0)
        modes = QHBoxLayout()
        mode_group = QButtonGroup(page)
        mode_group.setExclusive(True)
        self.stock_mode_buttons = {}
        for value, title, word in (('farm', '농원별', '농원'), ('species', '묘목별', '묘목')):
            background, ink, _ = CONCEPTS[word]
            btn = QPushButton(title)
            btn.setCheckable(True)
            btn.setChecked(value == self.stock_mode)
            btn.setIcon(concept_icon(word, 24))
            btn.setIconSize(QSize(24, 24))
            btn.setStyleSheet('QPushButton{min-height:40px;font-weight:700;border:1px solid #C9D4CE;border-radius:10px;'
                              f'background:#FFFFFF;}}QPushButton:checked{{background:{background};color:{ink};border:2px solid {ink};}}')
            btn.clicked.connect(lambda checked=False, v=value: self.choose_stock_mode(v))
            mode_group.addButton(btn)
            modes.addWidget(btn, 1)
            self.stock_mode_buttons[value] = btn
        left.addLayout(modes)
        grid = QGridLayout()
        grid.setSpacing(5)
        initial_group = QButtonGroup(page)
        initial_group.setExclusive(True)
        self.stock_initial_buttons = {}
        for index, title in enumerate(INITIALS):
            btn = QPushButton(title)
            btn.setCheckable(True)
            btn.setChecked(title == self.stock_initial)
            btn.setObjectName('filterTab')
            btn.setStyleSheet('QPushButton{padding-left:2px;padding-right:2px;}')
            btn.clicked.connect(lambda checked=False, v=title: self.choose_stock_initial(v))
            initial_group.addButton(btn)
            grid.addWidget(btn, index // 7, index % 7)
            self.stock_initial_buttons[title] = btn
        left.addLayout(grid)
        # 20주 이하 남은 재고 (전체 농원 기준). 누르면 목록, 고르면 그 농원 재고로 이동.
        self.stock_low = QPushButton('')
        self.stock_low.setStyleSheet('QPushButton{color:#9A2A22;font-weight:700;background:#FBE9E7;'
                                     'border:1px solid #E7B4AE;border-radius:8px;min-height:32px;}')
        self.stock_low.clicked.connect(self.show_low_stock)
        self.stock_low.hide()
        left.addWidget(self.stock_low)
        self.stock_list = QListWidget()
        self.stock_list.setIconSize(QSize(32, 32))
        self.stock_list.setSpacing(2)
        self.stock_list.setAccessibleName('재고 목록')
        self.stock_list.currentRowChanged.connect(self.choose_stock_entry)
        left.addWidget(self.stock_list, 1)
        self.stock_more = QPushButton('')
        self.stock_more.clicked.connect(self.toggle_stock_all)
        left.addWidget(self.stock_more)
        outer.addWidget(left_box, 0)
        right = QVBoxLayout()
        heading = QHBoxLayout()
        self.stock_title_icon = QLabel()
        self.stock_title = QLabel('왼쪽에서 농원이나 묘목을 골라주세요.')
        self.stock_title.setStyleSheet('font-size:18px;font-weight:700;')
        heading.addWidget(self.stock_title_icon)
        heading.addWidget(self.stock_title, 1)
        heading.addWidget(QLabel('정렬'))
        self.stock_sort = QComboBox()
        self.stock_sort.addItems(['수량 많은 순', '도매가 싼 순', '이름순'])
        self.stock_sort.currentIndexChanged.connect(self.filter_stock)
        heading.addWidget(self.stock_sort)
        right.addLayout(heading)
        self.stock_summary = QLabel('')
        right.addWidget(self.stock_summary)
        self.stock_table = table(['농원', '묘목', '등급', '연생', '규격', '재배형태', '수량', '도매단가', '판매단가', '상태'])
        self.stock_table.setIconSize(QSize(24, 24))
        self.stock_table.itemSelectionChanged.connect(self.update_controls)
        self.stock_table.itemDoubleClicked.connect(lambda _: self.edit_stock(True))
        right.addWidget(self.stock_table, 1)
        actions = QHBoxLayout()
        self.stock_add, self.stock_edit, self.stock_zero = QPushButton('재고 추가'), QPushButton('수량·단가 수정'), QPushButton('재고 삭제')
        self.stock_add.setIcon(concept_icon('재고', 22))
        self.stock_add.clicked.connect(lambda: self.edit_stock(False))
        self.stock_edit.clicked.connect(lambda: self.edit_stock(True))
        self.stock_zero.clicked.connect(self.zero_stock)
        for btn in (self.stock_add, self.stock_edit, self.stock_zero):
            actions.addWidget(btn)
        actions.addStretch()
        right.addLayout(actions)
        outer.addLayout(right, 1)
        return page

    def _stock_maps(self):
        state = self.state or {}
        farms = {r['_key']: r for r in state.get('farms', []) if not r.get('_deleted')}
        species = {r['_key']: r for r in state.get('species', []) if not r.get('_deleted')}
        rows = [r for r in state.get('inventory', []) if r['quantity'] > 0]
        return farms, species, rows

    def _low_stock(self):
        farms, species, rows = self._stock_maps()
        totals = {}
        for r in rows:
            totals[(r['farmKey'], r['speciesKey'])] = totals.get((r['farmKey'], r['speciesKey']), 0) + r['quantity']
        items = [(farms.get(f, {}).get('farmName', '농원 없음'), species.get(sp, {}).get('speciesName', '묘목 없음'), total, f)
                 for (f, sp), total in totals.items() if total <= 20]
        return sorted(items, key=lambda i: (i[2], i[0]))

    def show_low_stock(self):
        items = self._low_stock()
        if not items:
            return
        dialog = QDialog(self)
        dialog.setWindowTitle(f'20주 이하 남은 재고 {len(items)}건')
        dialog.setMinimumWidth(520)
        box = QVBoxLayout(dialog)
        box.addWidget(QLabel('농원·묘목별 합계 수량 기준입니다. 두 번 누르면 그 농원 재고로 이동합니다.'))
        grid = table(['농원', '묘목', '남은 수량'])
        grid.setIconSize(QSize(24, 24))
        fill_table(grid, [(farm, plant, f'{total:,}주') for farm, plant, total, _ in items])
        for index, (farm, plant, total, _) in enumerate(items):
            grid.item(index, 0).setIcon(farm_avatar(farm, 24))
            grid.item(index, 2).setForeground(QColor('#9A2A22'))
        box.addWidget(grid, 1)
        def open_farm(*_):
            index = grid.currentRow()
            if 0 <= index < len(items):
                farm_name, farm_key = items[index][0], items[index][3]
                self.stock_mode, self.stock_initial, self.stock_key, self.stock_show_all = 'farm', stock_initial(farm_name), farm_key, False
                dialog.accept()
                self.refresh_stock()
        grid.itemDoubleClicked.connect(open_farm)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Close)
        buttons.rejected.connect(dialog.reject)
        box.addWidget(buttons)
        dialog.exec()

    def choose_stock_mode(self, value):
        if value != self.stock_mode:
            self.stock_mode, self.stock_initial, self.stock_key, self.stock_show_all = value, INITIALS[0], None, False
        self.refresh_stock()

    def choose_stock_initial(self, value):
        self.stock_initial, self.stock_key, self.stock_show_all = value, None, False
        self.refresh_stock()

    def toggle_stock_all(self):
        self.stock_show_all = not self.stock_show_all
        self.refresh_stock()

    def choose_stock_entry(self, index):
        if getattr(self, '_stock_refreshing', False):
            return
        self.stock_key = self.stock_entries[index][0] if 0 <= index < len(self.stock_entries) else None
        self.filter_stock()

    def refresh_stock(self):
        """Left list: entries under the initial; stocked ones only until '…도 보기'."""
        if not hasattr(self, 'stock_list'):
            return
        farms, species, rows = self._stock_maps()
        by_farm = self.stock_mode == 'farm'
        source = farms if by_farm else {k: r for k, r in species.items() if r.get('isActive', 1)}
        name_key = 'farmName' if by_farm else 'speciesName'
        entries = []
        for key, record in source.items():
            name = record[name_key]
            if stock_initial(name) != self.stock_initial:
                continue
            mine = [r for r in rows if r['farmKey' if by_farm else 'speciesKey'] == key]
            others = {r['speciesKey' if by_farm else 'farmKey'] for r in mine}
            entries.append((key, name, len(others), sum(r['quantity'] for r in mine), record))
        entries.sort(key=lambda e: (e[2] == 0, e[1]))
        stocked = [e for e in entries if e[2]]
        only_stock = not self.stock_show_all  # stocked only (농원별·묘목별 같음); the bottom button opens the rest
        self.stock_entries = stocked if only_stock else entries
        self._stock_refreshing = True
        try:
            self.stock_list.clear()
            current = -1
            for index, (key, name, count, total, record) in enumerate(self.stock_entries):
                low = count and total <= 20
                unit = '종' if by_farm else '곳'
                detail = f"{count}{unit} · {total:,}주" + (' 남음' if low else '') if count else '재고 없음 · 재고 추가 가능'
                item = QListWidgetItem(f"{name}\n{detail}")
                item.setIcon(farm_avatar(name, 32) if by_farm else species_icon(name, record.get('category', ''), 32))
                if low:
                    item.setForeground(QColor('#9A2A22'))
                elif not count:
                    item.setForeground(QColor('#7B8A83'))
                self.stock_list.addItem(item)
                if key == self.stock_key:
                    current = index
            if not self.stock_entries and entries:
                hint = QListWidgetItem('이 초성에는 재고가 없어요')
                hint.setFlags(Qt.ItemFlag.NoItemFlags)
                hint.setForeground(QColor('#7B8A83'))
                self.stock_list.addItem(hint)
            if current < 0:
                self.stock_key = None
            self.stock_list.setCurrentRow(current)
        finally:
            self._stock_refreshing = False
        hidden = len(entries) - len(self.stock_entries)
        if only_stock and hidden:
            self.stock_more.setText(f"재고 없는 농원도 보기 · {hidden}곳" if by_farm else f"다른 묘목도 보기 · {hidden}종")
            self.stock_more.show()
        elif not only_stock and stocked:
            self.stock_more.setText('재고 있는 농원만 보기' if by_farm else '재고 있는 묘목만 보기')
            self.stock_more.show()
        else:
            self.stock_more.hide()
        low = len(self._low_stock())
        self.stock_low.setText(f'20주 이하 {low}건 ›')
        self.stock_low.setVisible(low > 0)
        for value, btn in self.stock_mode_buttons.items():
            btn.setChecked(value == self.stock_mode)
        stocked_keys = {r['farmKey' if by_farm else 'speciesKey'] for r in rows}
        for title, btn in self.stock_initial_buttons.items():
            btn.setChecked(title == self.stock_initial)
            btn.setEnabled(any(stock_initial(r[name_key]) == title for r in source.values()))
            # Only initials with stock look active; the others stay clickable (first entry) but faint.
            has_stock = any(k in stocked_keys and stock_initial(r[name_key]) == title for k, r in source.items())
            btn.setStyleSheet('QPushButton{padding-left:2px;padding-right:2px;}' +
                              ('' if has_stock or title == self.stock_initial else 'QPushButton{color:#B3BCB7;}'))
        self.filter_stock()

    def filter_stock(self, *_):
        if not hasattr(self, 'stock_table'):
            return
        farms, species, rows = self._stock_maps()
        by_farm = self.stock_mode == 'farm'
        key = self.stock_key
        self.stock_rows = [r for r in rows if key and r['farmKey' if by_farm else 'speciesKey'] == key]
        order = self.stock_sort.currentIndex()
        name_of = (lambda r: species.get(r['speciesKey'], {}).get('speciesName', '')) if by_farm else \
            (lambda r: farms.get(r['farmKey'], {}).get('farmName', ''))
        if order == 0:
            self.stock_rows.sort(key=lambda r: (-r['quantity'], r['wholesalePrice']))
        elif order == 1:
            self.stock_rows.sort(key=lambda r: (r['wholesalePrice'], -r['quantity']))
        else:
            self.stock_rows.sort(key=lambda r: (name_of(r), -r['quantity']))
        total = sum(r['quantity'] for r in self.stock_rows)
        if not key:
            self.stock_title.setText('왼쪽에서 농원이나 묘목을 골라주세요.')
            self.stock_title_icon.clear()
            self.stock_summary.setText('')
        elif by_farm:
            farm = farms.get(key, {})
            self.stock_title.setText(farm.get('farmName', '') + (f"   ☎ {farm['phone']}" if farm.get('phone') else ''))
            self.stock_title_icon.setPixmap(farm_avatar(farm.get('farmName', ''), 36).pixmap(36, 36))
            self.stock_summary.setText(f"묘목 {len({r['speciesKey'] for r in self.stock_rows})}종 · 재고 {total:,}주")
        else:
            item = species.get(key, {})
            self.stock_title.setText(item.get('speciesName', ''))
            self.stock_title_icon.setPixmap(species_icon(item.get('speciesName', ''), item.get('category', ''), 36).pixmap(36, 36))
            self.stock_summary.setText(f"농원 {len({r['farmKey'] for r in self.stock_rows})}곳 · 재고 {total:,}주")
        pending = {r['key'] for r in (self.state or {}).get('pending', [])}
        fill_table(self.stock_table, [(farms.get(r['farmKey'], {}).get('farmName', '농원 없음'),
            species.get(r['speciesKey'], {}).get('speciesName', '묘목 없음'),
            r['quality'], r['age'], r['size'], r['plantType'], f"{r['quantity']:,}", f"{r['wholesalePrice']:,}",
            f"{r['retailPrice']:,}", '전송 대기' if r['_key'] in pending else '동기화됨') for r in self.stock_rows])
        for index, r in enumerate(self.stock_rows):
            farm_name = farms.get(r['farmKey'], {}).get('farmName', '')
            plant = species.get(r['speciesKey'], {})
            if self.stock_table.item(index, 0):
                self.stock_table.item(index, 0).setIcon(farm_avatar(farm_name, 24))
            if self.stock_table.item(index, 1):
                self.stock_table.item(index, 1).setIcon(species_icon(plant.get('speciesName', ''), plant.get('category', ''), 24))
            if r['quantity'] <= 20 and self.stock_table.item(index, 6):
                self.stock_table.item(index, 6).setForeground(QColor('#9A2A22'))
        self.stock_table.clearSelection()
        self.stock_table.setCurrentCell(-1, -1)
        self.update_controls()

    def edit_stock(self, existing):
        index = self.stock_table.currentRow()
        if existing and not 0 <= index < len(self.stock_rows):
            return
        row = self.stock_rows[index] if existing else {}
        fk = row.get('farmKey') or (self.stock_key if self.stock_mode == 'farm' else None)
        sk = row.get('speciesKey') or (self.stock_key if self.stock_mode == 'species' else None)
        if not (fk or sk) or self.owner.task:
            return
        dialog = QDialog(self)
        dialog.setWindowTitle('재고 수정' if existing else '재고 추가')
        form = QFormLayout(dialog)
        other = None
        if not fk or not sk:
            # 농원별에서는 묘목을, 묘목별에서는 농원을 골라 새 재고를 넣는다.
            kind, name_key = ('farms', 'farmName') if not fk else ('species', 'speciesName')
            other = QComboBox()
            other.addItem('선택해주세요', None)
            for record in sorted((self.state or {}).get(kind, []), key=lambda r: r[name_key]):
                if not record.get('_deleted') and record.get('isActive', 1):
                    other.addItem(farm_avatar(record[name_key], 22) if kind == 'farms' else
                                  species_icon(record[name_key], record.get('category', ''), 22), record[name_key], record['_key'])
            form.addRow('농원' if not fk else '묘목', other)
        inputs = {}
        for key, title, default in (('quality', '등급', '상묘'), ('age', '연생', '1년생'), ('size', '규격', ''), ('plantType', '재배형태', '실생')):
            item = QLineEdit(row.get(key, default))
            item.setEnabled(not existing)
            inputs[key] = item
            form.addRow(title, item)
        for key, title in (('quantity', '수량'), ('wholesalePrice', '도매단가'), ('retailPrice', '판매단가')):
            item = QSpinBox()
            item.setRange(0, 2147483647)
            item.setGroupSeparatorShown(True)
            item.setValue(row.get(key, 0))
            inputs[key] = item
            form.addRow(title, item)
        message = QLabel('')
        message.setWordWrap(True)
        form.addRow(message)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel)
        form.addRow(buttons)
        def save():
            try:
                values = {k: v.value() if isinstance(v, QSpinBox) else v.text() for k, v in inputs.items()}
                chosen = other.currentData() if other is not None else None
                if other is not None and not chosen:
                    raise UserError('농원을 골라주세요.' if not fk else '묘목을 골라주세요.')
                self.store().save('inventory', {**values, 'farmKey': fk or chosen, 'speciesKey': sk or chosen}, row.get('_key'))
            except UserError as error:
                message.setText(str(error))
                return
            dialog.accept()
            self.changed()
        buttons.accepted.connect(save)
        buttons.rejected.connect(dialog.reject)
        dialog.exec()

    def zero_stock(self):
        index = self.stock_table.currentRow()
        if not 0 <= index < len(self.stock_rows) or self.owner.task:
            return
        if QMessageBox.question(self, '재고 삭제', '선택 재고를 0주로 저장할까요?',
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
            return
        try:
            row = self.stock_rows[index]
            self.store().save('inventory', {**row, 'quantity': 0}, row['_key'])
            self.changed()
        except UserError as error:
            QMessageBox.warning(self, '재고 삭제', str(error))

