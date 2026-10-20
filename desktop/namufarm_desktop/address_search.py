"""Kakao postcode lookup in the system browser with a one-use loopback callback.

API reference: https://postcode.map.kakao.com/guide
No embedded-browser runtime or API key is required.
"""
import json
import queue
import re
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from PySide6.QtCore import QTimer, QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import QDialog, QVBoxLayout, QLabel, QPushButton, QHBoxLayout

PAGE = """<!doctype html><html lang="ko"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>나무팜 주소찾기</title><style>
body{margin:0;font:18px sans-serif;color:#193c32}h1,p{margin:16px}h1{font-size:24px}
#search{width:100%;height:calc(100vh - 120px);min-height:450px}</style>
<h1>나무팜 주소찾기</h1><p id="status">지번·도로명·건물명으로 검색하고 주소를 선택하세요.</p>
<div id="search"></div>
<script src="https://t1.kakaocdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js"
onerror="document.getElementById('status').textContent='주소검색에 연결하지 못했습니다. 인터넷 연결을 확인하고 다시 열어주세요.'"></script>
<script>
if(window.kakao && kakao.Postcode) new kakao.Postcode({width:'100%',height:'100%',
 oncomplete:async function(d){
  const status=document.getElementById('status');
  try{
   const response=await fetch('/selected'+location.search,{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({zipNo:d.zonecode,address:d.roadAddress||d.jibunAddress||d.address})});
   if(!response.ok)throw new Error();
   status.textContent='주소를 나피에 입력했습니다. 나피에서 상세주소를 입력하세요. 이 창은 닫아도 됩니다.';
  }catch(e){status.textContent='나피와 연결이 끊겼습니다. 나피에서 주소찾기를 다시 열어주세요.';}
 }}).embed(document.getElementById('search'),{autoClose:false});
</script></html>"""


class AddressSearchDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("주소찾기")
        self.resize(480, 180)
        self.address = None
        self.results = queue.Queue(maxsize=1)
        self.token = secrets.token_urlsafe(32)
        token, results = self.token, self.results

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def authorized(self):
                query = parse_qs(urlsplit(self.path).query)
                return (query.get('token') == [token] and self.headers.get('Host') ==
                        '127.0.0.1:%s' % self.server.server_port)

            def reply(self, status, data, kind='text/plain; charset=utf-8'):
                raw = data.encode('utf-8')
                self.send_response(status)
                self.send_header('Content-Type', kind)
                self.send_header('Content-Length', str(len(raw)))
                self.send_header('Cache-Control', 'no-store')
                self.send_header('Referrer-Policy', 'no-referrer')
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self):
                if not self.authorized() or urlsplit(self.path).path != '/':
                    self.reply(404, '')
                    return
                self.reply(200, PAGE, 'text/html; charset=utf-8')

            def do_POST(self):
                if not self.authorized() or urlsplit(self.path).path != '/selected':
                    self.reply(404, '')
                    return
                try:
                    length = int(self.headers.get('Content-Length', '0'))
                    if not 0 < length <= 4096:
                        raise ValueError()
                    data = json.loads(self.rfile.read(length))
                    if (not isinstance(data, dict) or not re.fullmatch(r'\d{5}', data.get('zipNo', '')) or
                            not isinstance(data.get('address'), str) or not 1 <= len(data['address'].strip()) <= 300):
                        raise ValueError()
                    results.put_nowait(data)
                    self.reply(200, 'OK')
                except (ValueError, TypeError, queue.Full):
                    self.reply(400, '')

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = QUrl(f'http://127.0.0.1:{self.server.server_port}/?token={self.token}')
        layout = QVBoxLayout(self)
        self.status = QLabel("브라우저에서 주소를 검색해 선택하세요.\n선택하면 우편번호와 주소가 나피에 자동 입력됩니다.")
        self.status.setWordWrap(True)
        layout.addWidget(self.status)
        actions = QHBoxLayout()
        reopen = QPushButton("주소검색 창 다시 열기")
        reopen.clicked.connect(self.open_browser)
        cancel = QPushButton("취소")
        cancel.clicked.connect(self.reject)
        actions.addWidget(reopen)
        actions.addWidget(cancel)
        layout.addLayout(actions)
        self.timer = QTimer(self)
        self.timer.setInterval(150)
        self.timer.timeout.connect(self.receive_address)
        self.timer.start()
        QTimer.singleShot(0, self.open_browser)

    def open_browser(self):
        if not QDesktopServices.openUrl(self.url):
            self.status.setText("브라우저를 열지 못했습니다. 기본 브라우저 설정을 확인하고 다시 열어주세요.")

    def receive_address(self):
        try:
            self.address = self.results.get_nowait()
        except queue.Empty:
            return
        self.accept()
        if self.parentWidget():
            self.parentWidget().activateWindow()

    def done(self, result):
        self.timer.stop()
        server, self.server = self.server, None
        if server is not None:
            def close():
                server.shutdown()
                server.server_close()
            threading.Thread(target=close, daemon=True).start()
        super().done(result)
