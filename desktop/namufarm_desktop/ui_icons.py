"""Local line icons and vendored Material Symbols; no network/font dependency."""
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


# Google Material Symbols Rounded (Apache-2.0), vendored unmodified.
# Upstream: https://github.com/google/material-design-icons/tree/master/symbols/web
# License: docs/licenses/material-design-icons-LICENSE.txt
MATERIAL_SVGS = {
    'person': '<svg xmlns="http://www.w3.org/2000/svg" height="24" viewBox="0 -960 960 960" width="24"><path d="M480-480q-66 0-113-47t-47-113q0-66 47-113t113-47q66 0 113 47t47 113q0 66-47 113t-113 47ZM160-240v-32q0-34 17.5-62.5T224-378q62-31 126-46.5T480-440q66 0 130 15.5T736-378q29 15 46.5 43.5T800-272v32q0 33-23.5 56.5T720-160H240q-33 0-56.5-23.5T160-240Z"/></svg>',
    'leaf': '<svg xmlns="http://www.w3.org/2000/svg" height="24" viewBox="0 -960 960 960" width="24"><path d="M450-80q-33 0-66.5-7.5T315-109q12-121 70-226t149-185q-110 56-190.5 148T231-162q-4-3-7.5-6.5L216-176q-47-47-71.5-105T120-402q0-68 27-130t75-110q70-70 173.5-98T675-760q26 1 48 11.5t39 27.5q17 17 27 39.5t12 48.5q6 180-21 281t-96 170q-49 49-109.5 75.5T450-80Z"/></svg>',
    'calculator': '<svg xmlns="http://www.w3.org/2000/svg" height="24" viewBox="0 -960 960 960" width="24"><path d="M320-320v50q0 13 8.5 21.5T350-240q13 0 21.5-8.5T380-270v-50h50q13 0 21.5-8.5T460-350q0-13-8.5-21.5T430-380h-50v-50q0-13-8.5-21.5T350-460q-13 0-21.5 8.5T320-430v50h-50q-13 0-21.5 8.5T240-350q0 13 8.5 21.5T270-320h50Zm230 50h140q13 0 21.5-8.5T720-300q0-13-8.5-21.5T690-330H550q-13 0-21.5 8.5T520-300q0 13 8.5 21.5T550-270Zm0-100h140q13 0 21.5-8.5T720-400q0-13-8.5-21.5T690-430H550q-13 0-21.5 8.5T520-400q0 13 8.5 21.5T550-370Zm70-208 35 35q9 9 21 9t21-9q8-8 8.5-20.5T698-585l-36-37 35-35q9-9 9-21t-9-21q-9-9-21-9t-21 9l-35 35-35-35q-9-9-21-9t-21 9q-9 9-9 21t9 21l35 35-36 37q-8 9-8 21t9 21q9 9 21 9t21-9l35-35Zm-340-14h140q13 0 21.5-8.5T450-622q0-13-8.5-21.5T420-652H280q-13 0-21.5 8.5T250-622q0 13 8.5 21.5T280-592Zm-80 472q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h560q33 0 56.5 23.5T840-760v560q0 33-23.5 56.5T760-120H200Z"/></svg>',
    'park': '<svg xmlns="http://www.w3.org/2000/svg" height="24" viewBox="0 -960 960 960" width="24"><path d="M402-240H195q-24 0-36-21t2-41l119-178h-3q-24 0-35.5-21.5T244-543l203-290q6-8 15-12.5t18-4.5q9 0 18 4.5t15 12.5l203 290q14 20 2.5 41.5T683-480h-3l119 178q14 20 2 41t-36 21H558v120q0 17-11.5 28.5T518-80h-76q-17 0-28.5-11.5T402-120v-120Z"/></svg>',
    'schedule': '<svg xmlns="http://www.w3.org/2000/svg" height="24" viewBox="0 -960 960 960" width="24"><path d="M520-496v-144q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640v159q0 8 3 15.5t9 13.5l132 132q11 11 28 11t28-11q11-11 11-28t-11-28L520-496ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Z"/></svg>',
}


@lru_cache(maxsize=96)
def ui_icon(name, color="#24313A"):
    if name in MATERIAL_SVGS:
        svg = MATERIAL_SVGS[name].replace('<svg ', f'<svg fill="{color}" ', 1)
    else:
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
               f'viewBox="0 0 24 24" fill="none" stroke="{color}" stroke-width="1.7" '
               f'stroke-linecap="round" stroke-linejoin="round">{PATHS[name]}</svg>')
    renderer = QSvgRenderer(QByteArray(svg.encode()))
    icon = QIcon()
    sizes = (18, 20, 24, 36, 40, 48, 96, 144, 192) if name == "park" else (18, 20, 24, 36, 40, 48)
    for size in sizes:
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
