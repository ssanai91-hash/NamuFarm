"""Colour icons shared with the Android app (2026-10-31 사용자 확정 시안 2안).

Decoration only: reads names/categories, never stores anything. Text keeps its size and dark colour;
colour lives in small icon tiles. No network, fonts or image files are used.
"""
from functools import lru_cache

from PySide6.QtCore import QByteArray, QRectF, Qt
from PySide6.QtGui import QColor, QFont, QIcon, QPainter, QPixmap
from PySide6.QtSvg import QSvgRenderer

from .species_purposes import parse_category, suggest_purpose_labels

# 묘목 용도(홈페이지·앱과 같은 분류): (배경, 선 색, 24×24 선 그림)
PURPOSE_VISUALS = {
    "열매": ("#FBE5D8", "#C2410C", '<path d="M12 6.5c-1.5-1.2-4.6-1.6-6.2.6-2 2.8-.8 8 1.7 10.6 1.3 1.4 2.8 1.6 4.5.8 1.7.8 3.2.6 4.5-.8 2.5-2.6 3.7-7.8 1.7-10.6-1.6-2.2-4.7-1.8-6.2-.6z"/><path d="M12 6.5c0-1.8.7-3.2 2.3-4"/>'),
    "꽃": ("#FBE3EB", "#BE185D", '<circle cx="12" cy="9" r="2.2"/><path d="M12 6.8c0-2 1-3.3 2.4-3.3S17 5 15.7 6.6M14.2 9c2 0 3.3 1 3.3 2.4s-1.5 2.6-3.1 1.3M9.8 9c-2 0-3.3 1-3.3 2.4s1.5 2.6 3.1 1.3M12 6.8c0-2-1-3.3-2.4-3.3S7 5 8.3 6.6M12 11.2V21"/>'),
    "울타리": ("#E1EFDD", "#2F6B2F", '<path d="M4 21V7l2-3 2 3v14M10 21V7l2-3 2 3v14M16 21V7l2-3 2 3v14M3 11h18M3 17h18"/>'),
    "그늘": ("#DDEDE4", "#1E6B4A", '<path d="M8 19h8M12 19v3M7.5 16a4.5 4.5 0 0 1-1.6-8.7A6 6 0 0 1 17.6 6a4.5 4.5 0 0 1-1.1 10z"/>'),
    "정원": ("#E1ECF5", "#1D4F91", '<path d="M10 10v.2A3 3 0 0 1 8.9 16H5a3 3 0 0 1-1-5.8V10a3 3 0 0 1 6 0zM7 16v6M13 19v3M12 19h8.3a1 1 0 0 0 .7-1.7L18 14h.3a1 1 0 0 0 .7-1.7L16 9h.2a1 1 0 0 0 .8-1.7L13 3l-1.4 1.5"/>'),
    "잔디": ("#ECF3D5", "#4D7C0F", '<path d="M7 20h10M10 20c5.5-2.5.8-6.4 3-10M9.5 9.4c1.1.8 1.8 2.2 2.3 3.7-2 .4-3.5.4-4.8-.3-1.2-.6-2.3-1.9-3-4.2 2.8-.5 4.4 0 5.5.8z"/>'),
}
DEFAULT_PURPOSE = ("#EEF1EE", "#4A5A52", '<path d="M12 3 7 10h3l-4 6h12l-4-6h3zM12 16v5"/>')

# 업무 단어: same colour/shape everywhere (앱과 동일)
CONCEPTS = {
    "견적": ("#FDE7D6", "#B45309", '<path d="M6 3h9l3 3v15H6zM9 9h6M9 13h6M9 17h4"/>'),
    "문의": ("#E0EDF9", "#1D4F91", '<path d="M4 5h16v11H8l-4 4z"/>'),
    "매매": ("#F7E1EC", "#9D174D", '<path d="M3 12l4-4 4 3 3-3 4 4-7 7z"/>'),
    "농원": ("#DDF0EE", "#0B5F56", '<path d="M3 21h18M5 21V10l7-5 7 5v11M9 21v-6h6v6"/>'),
    "묘목": ("#E6F2DF", "#2F6B2F", '<path d="M12 21v-8M12 13c0-4 3-6 7-6 0 4-3 6-7 6zM12 15c0-3-2.5-5-6-5 0 3 2.5 5 6 5z"/>'),
    "재고": ("#FDF1D2", "#8A5300", '<path d="M3 8l9-5 9 5v8l-9 5-9-5zM3 8l9 5 9-5M12 13v8"/>'),
}

AVATAR_COLORS = (("#FDE7D6", "#B45309"), ("#E0EDF9", "#1D4F91"), ("#E6F2DF", "#2F6B2F"),
                 ("#F7E1EC", "#9D174D"), ("#EDE7F8", "#5B3C9E"), ("#DDF0EE", "#0B5F56"))

# Sidebar icon colours on the dark green menu (soft tints of the word colours).
NAV_TINTS = ("#F9C9A5", "#F9C9A5", "#F2B8D0", "#F5D78E", "#A9C8EE", "#9ED9D2", "#B6DF9A", "#A9C8EE", "#FFFFFF")


def purpose_label(name, category=""):
    """Saved category first; otherwise the editor's name suggestions ("감(대봉)" → 감)."""
    labels = parse_category(category or "").selected_labels if category else ()
    if labels:
        return labels[0]
    suggested = suggest_purpose_labels((name or "").split("(")[0].strip())
    return suggested[0] if suggested else ""


def _render(svg, size):
    renderer = QSvgRenderer(QByteArray(svg.encode()))
    icon = QIcon()
    for ratio in (1.0, 1.5, 2.0):
        pixmap = QPixmap(round(size * ratio), round(size * ratio))
        pixmap.setDevicePixelRatio(ratio)
        pixmap.fill(Qt.GlobalColor.transparent)
        painter = QPainter(pixmap)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        renderer.render(painter, QRectF(0, 0, size, size))
        painter.end()
        icon.addPixmap(pixmap)
    return icon


def _tile_svg(background, ink, paths):
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32">'
            f'<rect width="32" height="32" rx="9" fill="{background}"/>'
            f'<g transform="translate(5 5) scale(0.9167)" fill="none" stroke="{ink}" stroke-width="2" '
            f'stroke-linecap="round" stroke-linejoin="round">{paths}</g></svg>')


@lru_cache(maxsize=32)
def purpose_icon(label, size=28):
    background, ink, paths = PURPOSE_VISUALS.get(label, DEFAULT_PURPOSE)
    return _render(_tile_svg(background, ink, paths), size)


def species_icon(name, category="", size=28):
    return purpose_icon(purpose_label(name, category), size)


@lru_cache(maxsize=16)
def concept_icon(word, size=24):
    background, ink, paths = CONCEPTS[word]
    return _render(_tile_svg(background, ink, paths), size)


@lru_cache(maxsize=16)
def concept_line_icon(word, color, size=20):
    """Line-only concept icon in a given colour (e.g. sidebar on dark background)."""
    paths = CONCEPTS[word][2]
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" '
           f'stroke="{color}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">{paths}</svg>')
    return _render(svg, size)


def avatar_colors(name):
    value = 0
    for character in name or "":
        value = (value * 31 + ord(character)) % 1_000_003
    return AVATAR_COLORS[value % len(AVATAR_COLORS)]


@lru_cache(maxsize=512)
def farm_avatar(name, size=28):
    """농원 첫 글자 동그라미; the colour is derived from the name, so it never changes."""
    background, ink = avatar_colors(name)
    icon = QIcon()
    for ratio in (1.0, 1.5, 2.0):
        pixmap = QPixmap(round(size * ratio), round(size * ratio))
        pixmap.setDevicePixelRatio(ratio)
        pixmap.fill(Qt.GlobalColor.transparent)
        painter = QPainter(pixmap)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QColor(background))
        painter.drawEllipse(QRectF(0, 0, size, size))
        font = QFont()
        font.setBold(True)
        font.setPixelSize(max(9, round(size * 0.42)))
        painter.setFont(font)
        painter.setPen(QColor(ink))
        painter.drawText(QRectF(0, 0, size, size), Qt.AlignmentFlag.AlignCenter, (name or "?").strip()[:1])
        painter.end()
        icon.addPixmap(pixmap)
    return icon
