"""Small, original line icons rendered locally; no external icon/font dependency."""
from functools import lru_cache

from PySide6.QtCore import QByteArray, QSize, Qt
from PySide6.QtGui import QIcon, QPainter, QPixmap
from PySide6.QtSvg import QSvgRenderer


PATHS = {
    "requests": '<path d="M6 3h9l4 4v14H5V3h1zm8 0v5h5M8 12h8M8 16h5"/>',
    "confirmed": '<path d="M6 3h9l4 4v14H5V3h1zm8 0v5h5m-11 7 3 3 5-6"/>',
    "orders": '<path d="M4 7h16v14H4zM8 7V5a4 4 0 0 1 8 0v2M8 12h8M8 16h5"/>',
    "shipping": '<path d="M3 5h11v12H3zM14 9h4l3 4v4h-7M18 9v4h3"/><circle cx="7" cy="18" r="2"/><circle cx="18" cy="18" r="2"/>',
    "reports": '<path d="M4 3v18h17M8 17v-5M13 17V7M18 17v-8"/>',
    "settings": '<path d="m9 3-1 3-3 1 1 3-2 2 2 2-1 3 3 1 1 3h6l1-3 3-1-1-3 2-2-2-2 1-3-3-1-1-3z"/><circle cx="12" cy="12" r="3"/>',
    "refresh": '<path d="M20 10a8 8 0 0 0-14-4L3 9m0-6v6h6m-5 5a8 8 0 0 0 14 4l3-3m0 6v-6h-6"/>',
    "trash": '<path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7"/>',
    "plus": '<path d="M12 4v16M4 12h16"/>',
    "open": '<path d="M14 3h7v7M21 3l-11 11M10 5H4v15h15v-6"/>',
    "mail": '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 6 9 7 9-7"/>',
    "message": '<path d="M4 4h16v12H9l-5 4zM8 8h8M8 12h6"/>',
    "document": '<path d="M5 3h10l4 4v14H5zM14 3v5h5M8 12h8M8 16h6"/>',
    "save": '<path d="M4 3h13l3 3v15H4zM8 3v6h8V3M8 21v-8h8v8"/>',
    "check": '<path d="m4 12 5 5L20 6"/>',
    "chevron": '<path d="m6 9 6 6 6-6"/>',
    "folder": '<path d="M3 6h7l2 3h9l-2 11H3zM3 6V4h6l3 3h8v2"/>',
}


@lru_cache(maxsize=96)
def ui_icon(name, color="#24313A"):
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
           f'viewBox="0 0 24 24" fill="none" stroke="{color}" stroke-width="1.7" '
           f'stroke-linecap="round" stroke-linejoin="round">{PATHS[name]}</svg>')
    renderer = QSvgRenderer(QByteArray(svg.encode()))
    icon = QIcon()
    for size in (18, 20, 24, 36, 40, 48):
        pixmap = QPixmap(size, size)
        pixmap.fill(Qt.GlobalColor.transparent)
        painter = QPainter(pixmap)
        renderer.render(painter)
        painter.end()
        icon.addPixmap(pixmap)
    return icon


def set_button_icon(button, name, color="#24313A", size=18):
    button.setIcon(ui_icon(name, color))
    button.setIconSize(QSize(size, size))
