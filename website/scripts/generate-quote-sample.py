"""Generate the public sample from scratch; never embed the recipient's source image.

Usage: python scripts/generate-quote-sample.py /path/to/NanumGothic-Regular.ttf
Requires reportlab. Render at 144 dpi and convert the page to quote-sample.webp
for the on-page preview. The supplied font is embedded as a subset in the PDF.
"""
import sys
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.colors import Color, HexColor

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "public" / "quote-sample.pdf"
pdfmetrics.registerFont(TTFont("Korean", sys.argv[1]))
c = canvas.Canvas(str(OUT), pagesize=(595, 842), pageCompression=1, invariant=1)
c.setTitle("나무팜농원 공식 견적서 SAMPLE")
c.setAuthor("나무팜농원")
c.setSubject("블루베리·감나무 양식 견본. 수신 업체 정보 비공개. 실제 견적 아님.")
c.setLineWidth(.65)

def line(x1, y1, x2, y2):
    c.line(x1, 842 - y1, x2, 842 - y2)

def text(value, x, y, size=10, align="left", color="#222222"):
    c.setFont("Korean", size)
    c.setFillColor(HexColor(color))
    draw = {"left": c.drawString, "center": c.drawCentredString, "right": c.drawRightString}[align]
    draw(x, 842 - y, value)

def rect(x, y, w, h):
    c.setStrokeColor(HexColor("#333333"))
    c.rect(x, 842 - y - h, w, h, stroke=1, fill=0)

text("NO. SAMPLE", 25, 29, 9)
text("견   적   서", 297.5, 57, 25, "center")
line(207, 64, 388, 64)
text("2026년 10월 5일", 48, 99, 11)
line(25, 106, 252, 106)
text("수신 업체명 비공개", 242, 125, 11, "right", "#707070")
text("수신 연락처 비공개 · 귀하", 242, 144, 10, "right", "#707070")
line(25, 154, 252, 154)
text("아래와 같이 견적합니다.", 25, 177, 11)

# Supplier cells mirror the uploaded paper quotation's arrangement.
rect(279, 90, 291, 96)
line(299, 90, 299, 186)
line(369, 90, 369, 186)
for y in (106, 122, 138, 154, 170):
    line(299, y, 570, y)
for char, y in zip("공급자", (124, 140, 156)):
    text(char, 289, y, 10, "center")
text("등록번호", 334, 102, 10, "center")
text("213-94-24363", 468, 102, 11, "center")
text("상호", 334, 118, 10, "center")
text("나무팜농원", 418, 118, 10, "center")
line(475, 106, 475, 122); line(522, 106, 522, 122)
text("성명", 498, 118, 10, "center"); text("오진웅", 546, 118, 10, "center")
text("사업장주소", 334, 134, 10, "center")
text("충북 옥천군 동이면 금강로 185", 469, 134, 9, "center")
text("업태", 334, 150, 10, "center")
text("농업, 도소매", 418, 150, 10, "center")
line(475, 138, 475, 154); line(509, 138, 509, 154)
text("종목", 492, 150, 9, "center"); text("묘목, 화훼", 539, 150, 9, "center")
text("연락처", 334, 166, 10, "center")
text("T: (043)732-0980  F: 732-0981", 469, 166, 9, "center")
text("문서 구분", 334, 182, 10, "center")
text("SAMPLE · 실제 견적 아님", 469, 182, 9, "center", "#555555")

rect(25, 186, 545, 32)
text("합계금액 (공급금액 + 세액)", 30, 207, 12)
text("₩ 350,000원", 312, 207, 15)
columns = [25, 152, 208, 240, 315, 394, 471, 570]
header, bottom = 218, 686
rect(25, header, 545, bottom-header)
for x in columns[1:-1]:
    line(x, header, x, bottom)
for y in range(242, 667, 24):
    line(25, y, 570, y)
line(25, 666, 570, 666)
for i, label in enumerate(["품명", "규격", "수량", "단가", "공급금액", "세액", "비고"]):
    text(label, (columns[i]+columns[i+1])/2, 235, 11, "center")
rows = [
    ["블루베리", "2년생", "20", "10,000", "200,000", "0", "예시"],
    ["감나무", "2년생", "10", "15,000", "150,000", "0", "예시"],
]
for n, row in enumerate(rows):
    for i, value in enumerate(row):
        x = columns[i]+4 if i==0 else (columns[i]+columns[i+1])/2
        text(value, x, 258+n*24, 10, "left" if i==0 else "center")
text("계", 88, 680, 10, "center")
text("350,000", 354, 680, 10, "center")
text("0", 432, 680, 10, "center")

rect(25, 686, 545, 105)
line(152, 686, 152, 791)
text("중요사항", 88, 743, 11, "center")
for y, value in [
    (706, "1. 본 문서는 공식 견적서 양식 확인용 SAMPLE입니다."),
    (726, "2. 수신 업체와 연락처는 공개하지 않았습니다."),
    (746, "3. 품목·수량·단가는 예시이며 실제 판매 견적이 아닙니다."),
    (766, "4. 거래·입금·세금계산서 발행에 사용할 수 없습니다."),
]:
    text(value, 157, y, 9)

# A visible watermark covers the middle of the paper while keeping cells readable.
c.saveState()
c.translate(297.5, 842-463)
c.rotate(27)
c.setFillColor(Color(.55, .55, .55, alpha=.25))
c.setFont("Helvetica-Bold", 82)
c.drawCentredString(0, 0, "SAMPLE")
c.restoreState()
text("나무팜농원 · 공개용 견본 · 실제 견적 아님", 297.5, 819, 9, "center", "#777777")
c.showPage(); c.save()
print(OUT)
