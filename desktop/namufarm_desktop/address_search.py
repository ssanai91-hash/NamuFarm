"""Kakao postcode lookup inside a child browser dialog.

API reference: https://postcode.map.kakao.com/guide
The one-use loopback callback keeps address selection on the local PC.
"""
import json
import queue
import re
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from PySide6.QtCore import QTimer, QUrl, Qt
from PySide6.QtWidgets import QDialog, QVBoxLayout, QLabel, QPushButton, QHBoxLayout
from .errors import UserError

PAGE = """<!doctype html><html lang="ko"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>나무팜 주소찾기</title><style>
html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;font:14px sans-serif;color:#193c32}
#search{position:absolute;inset:0;overflow:hidden}
#search iframe{display:block;border:0}
#status{position:absolute;left:0;right:0;bottom:0;margin:0;padding:10px;box-sizing:border-box;background:#fff3dc;z-index:1}</style>
<p id="status" role="alert" hidden></p>
<div id="search"></div>
<script>
function showStatus(message){const status=document.getElementById('status');status.textContent=message;status.hidden=false;}
</script>
<script src="https://t1.kakaocdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js"
onerror="showStatus('주소검색에 연결하지 못했습니다. 인터넷 연결을 확인하고 다시 검색해주세요.')"></script>
<script>
if(window.kakao && kakao.Postcode) new kakao.Postcode({width:'100%',height:'100%',hideMapBtn:true,hideEngBtn:true,useBannerLink:false,
 oncomplete:async function(d){
  try{
   const response=await fetch('/selected'+location.search,{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({zipNo:d.zonecode,address:d.roadAddress||d.jibunAddress||d.address})});
   if(!response.ok)throw new Error();
  }catch(e){showStatus('나피와 연결이 끊겼습니다. 나피에서 주소찾기를 다시 열어주세요.');}
 }}).embed(document.getElementById('search'),{autoClose:false});
</script></html>"""


# Kakao's search UI lives in a cross-origin frame. Register on subframes too,
# so its overlaid input hint follows focus/IME input just like a Qt placeholder.
PLACEHOLDER_SCRIPT = """(() => {
 if(!['postcode.map.kakao.com','postcode.map.daum.net'].includes(location.hostname))return;
 const fields=new Map(), hints=new Map();
 const isInput=e=>e instanceof HTMLInputElement && ['text','search'].includes(e.type);
 function sync(input){
  if(!isInput(input))return;
  const hide=document.activeElement===input || input.value.length>0;
  if(!fields.has(input))fields.set(input,input.getAttribute('placeholder'));
  const placeholder=fields.get(input);
  if(hide)input.setAttribute('placeholder','');
  else if(placeholder!==null)input.setAttribute('placeholder',placeholder);
  const root=input.closest('form') || input.parentElement;
  const labels=new Set([...(input.labels||[]),...root.querySelectorAll('label,[class*="placeholder"],[class*="hint"],[class*="guide"]')]);
  const box=input.getBoundingClientRect();
  for(const label of labels){
   if(label===input || label.contains(input))continue;
   const rect=label.getBoundingClientRect();
   if(rect.right<=box.left || rect.left>=box.right || rect.bottom<=box.top || rect.top>=box.bottom)continue;
   if(!hints.has(label))hints.set(label,[label.style.getPropertyValue('visibility'),label.style.getPropertyPriority('visibility')]);
   if(hide)label.style.setProperty('visibility','hidden','important');
   else{const [value,priority]=hints.get(label);label.style.setProperty('visibility',value,priority);}
  }
 }
 for(const name of ['focusin','focusout','input','beforeinput','compositionstart','compositionend']){
  document.addEventListener(name,event=>{if(isInput(event.target)){
   if(name==='focusout')setTimeout(()=>sync(event.target),0);else sync(event.target);
  }},true);
 }
 new MutationObserver(()=>sync(document.activeElement)).observe(document.documentElement,{childList:true,subtree:true});
 sync(document.activeElement);
})();"""


class AddressSearchDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        # Loaded only when requested; PyInstaller's Qt hook collects WebEngine.
        try:
            from PySide6.QtWebEngineWidgets import QWebEngineView
            from PySide6.QtWebEngineCore import QWebEngineScript
        except ImportError as error:
            raise UserError("주소검색 브라우저를 불러오지 못했습니다. PySide6를 다시 설치하고 나피를 재빌드해주세요.") from error
        self.setWindowTitle("주소찾기")
        self.setWindowFlags(Qt.WindowType.Dialog | Qt.WindowType.FramelessWindowHint)
        self.setWindowModality(Qt.WindowModality.WindowModal)
        self.setObjectName("addressSearchDialog")
        self.setStyleSheet("QDialog#addressSearchDialog{background:#ffffff;border:2px solid #315c47;}"
                           "QDialog#addressSearchDialog QLabel{color:#193c32;font-size:15px;}"
                           "QDialog#addressSearchDialog QPushButton{min-height:30px;max-height:30px;font-size:14px;padding:0 10px;}")
        self.browser = QWebEngineView(self)
        self.browser.setContextMenuPolicy(Qt.ContextMenuPolicy.NoContextMenu)
        hint_script = QWebEngineScript()
        hint_script.setName("address-input-hint")
        hint_script.setInjectionPoint(QWebEngineScript.InjectionPoint.DocumentReady)
        hint_script.setWorldId(QWebEngineScript.ScriptWorldId.ApplicationWorld)
        hint_script.setRunsOnSubFrames(True)
        hint_script.setSourceCode(PLACEHOLDER_SCRIPT)
        self.browser.page().scripts().insert(hint_script)
        self.resize(min(640, max(320, parent.width() - 40)) if parent else 640,
                    min(700, max(400, parent.height() - 40)) if parent else 700)
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
        layout.setContentsMargins(10, 8, 10, 8)
        layout.setSpacing(6)
        heading = QHBoxLayout()
        heading.addWidget(QLabel("주소찾기"), 1)
        close = QPushButton("닫기")
        close.clicked.connect(self.reject)
        heading.addWidget(close)
        layout.addLayout(heading)
        layout.addWidget(self.browser, 1)
        self.status = QLabel("지번·도로명·건물명으로 검색한 뒤 주소를 선택하세요.")
        self.status.setWordWrap(True)
        layout.addWidget(self.status)
        actions = QHBoxLayout()
        reopen = QPushButton("다시 검색")
        reopen.clicked.connect(self.load_search)
        cancel = QPushButton("취소")
        cancel.clicked.connect(self.reject)
        actions.addWidget(reopen)
        actions.addWidget(cancel)
        layout.addLayout(actions)
        self.timer = QTimer(self)
        self.timer.setInterval(150)
        self.timer.timeout.connect(self.receive_address)
        self.timer.start()
        self.browser.loadFinished.connect(self.search_loaded)
        QTimer.singleShot(0, self.load_search)

    def showEvent(self, event):
        super().showEvent(event)
        if self.parentWidget():
            self.move(self.parentWidget().mapToGlobal(self.parentWidget().rect().center()) - self.rect().center())

    def load_search(self):
        self.status.setText("지번·도로명·건물명으로 검색한 뒤 주소를 선택하세요.")
        self.browser.load(self.url)

    def search_loaded(self, success):
        if not success:
            self.status.setText("검색창을 불러오지 못했습니다. 인터넷 연결을 확인하고 다시 검색해주세요.")

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
        self.browser.stop()
        server, self.server = self.server, None
        if server is not None:
            def close():
                server.shutdown()
                server.server_close()
            threading.Thread(target=close, daemon=True).start()
        super().done(result)
