"""Local-first nursery workspace, isolated from quote/sale cache and outbox.

One encrypted Vault preference commits the local rows and their retry queue
atomically. A sent operation is immutable until its server reply is verified.
"""
import copy
import re
import time
import uuid

from .domain import require
from .errors import ConflictError, UserError

INITIALS = tuple("ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ")
TEXT_FIELDS = {
    "farms": {"farmName": 100, "address": 300, "phone": 40, "memo": 1000},
    "species": {"speciesName": 100, "chosung": 20, "farmName": 100,
                "category": 100, "memo": 1000},
    "inventory": {"quality": 100, "age": 100, "size": 100, "plantType": 100},
}
AMOUNTS = ("quantity", "wholesalePrice", "retailPrice")
ID_FIELD = {"farms": "farmId", "species": "speciesId"}
RESULT_FIELD = {"farms": "farm", "species": "speciesRecord", "inventory": "inventoryRecord"}
ACTION = {"farms": "save_farm", "species": "save_species", "inventory": "save_inventory"}


def initial(name):
    if not name:
        return ""
    code = ord(name[0]) - 0xAC00
    if 0 <= code < 11172:
        letter = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"[code // 588]
        return {"ㄲ": "ㄱ", "ㄸ": "ㄷ", "ㅃ": "ㅂ", "ㅆ": "ㅅ", "ㅉ": "ㅈ"}.get(letter, letter)
    return name[0]


def fields(kind, raw):
    require(kind in TEXT_FIELDS and isinstance(raw, dict), "관리 자료 형식을 확인해주세요.")
    clean = {}
    for name, limit in TEXT_FIELDS[kind].items():
        value = raw.get(name, "")
        require(isinstance(value, str) and len(value.strip()) <= limit,
                "글자 길이 또는 입력 형식을 확인해주세요.")
        value = value.strip()
        require(not re.search(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", value))
        require(name == "memo" or not re.search(r"[\r\n]", value), "이름·규격에 줄바꿈을 넣을 수 없습니다.")
        clean[name] = value
    if kind == "farms":
        require(bool(clean["farmName"]), "농원명을 입력해주세요.")
        require(not clean["phone"] or re.fullmatch(r"[0-9+()\s-]+", clean["phone"]), "전화번호를 확인해주세요.")
    if kind == "species":
        require(bool(clean["speciesName"]), "묘목명을 입력해주세요.")
        active = raw.get("isActive", 1)
        require(type(active) is int and active in (0, 1), "사용 여부를 확인해주세요.")
        clean["isActive"] = active
    if kind == "inventory":
        for name in AMOUNTS:
            value = raw.get(name)
            require(type(value) is int and 0 <= value <= 2147483647, "수량·가격을 0 이상의 정수로 입력해주세요.")
            clean[name] = value
    return clean


def stock_key(row):
    return tuple(row.get(k) for k in ("farmId", "speciesId", "quality", "age", "size", "plantType"))


def remote_record(kind, raw):
    clean = fields(kind, raw)
    ids = (ID_FIELD[kind],) if kind in ID_FIELD else ("farmId", "speciesId")
    for name in ids:
        require(type(raw.get(name)) is int and 0 < raw[name] <= 2147483647, "서버의 농원·묘목 번호를 확인해주세요.")
        clean[name] = raw[name]
    version = raw.get("version", "")
    require(isinstance(version, str) and (re.fullmatch(r"[a-f0-9]{64}", version) or
                                        kind == "inventory" and version == ""), "서버 수정 버전을 확인해주세요.")
    clean.update(version=version, updatedAt=str(raw.get("updatedAt", "")))
    return clean


class NurseryStore:
    NAME = "nursery-v1"

    def __init__(self, manager):
        self.manager = manager
        self.vault = manager.vault

    def read(self):
        state = self.vault.preference(self.NAME)
        if state is None:
            return {"schema": 1, "url": self.manager.api.url, "farms": [], "species": [],
                    "inventory": [], "pending": [], "conflict": None, "syncedAt": 0}
        require(state.get("schema") == 1 and state.get("url") == self.manager.api.url,
                "농원 자료를 저장한 서버와 현재 연결 주소가 다릅니다. 원래 서버로 연결해주세요.")
        return state

    def _write(self, state):
        self.vault.save_preference(self.NAME, state)

    @staticmethod
    def _row(state, kind, key):
        return next((r for r in state[kind] if r["_key"] == key), None)

    def save(self, kind, values, key=None, delete=False):
        """No network work here. New parents can be referenced by queued inventory."""
        with self.vault.mutex:
            state = self.read()
            old = self._row(state, kind, key) if key else None
            require(not key or old is not None, "수정할 자료가 없습니다.")
            clean = fields(kind, values)
            # Re-register a zero-quantity item using the existing composite key.
            if kind == "inventory" and old is None:
                match = next((r for r in state[kind] if all(r.get(k) ==
                    (values.get(k) if k in ("farmKey", "speciesKey") else clean.get(k))
                    for k in ("farmKey", "speciesKey", "quality", "age", "size", "plantType"))), None)
                if match is not None and match["quantity"] == 0:
                    old, key = match, match["_key"]
            row = {**(old or {}), **clean, "_key": key or str(uuid.uuid4())}
            key = row["_key"]
            if kind in ID_FIELD:
                row.setdefault(ID_FIELD[kind], 0)
                name = "farmName" if kind == "farms" else "speciesName"
                require(not any(r["_key"] != key and r[name] == row[name] for r in state[kind]),
                        "같은 이름이 있습니다. 해당 항목을 수정해주세요.")
            else:
                for attr, parent in (("farmKey", "farms"), ("speciesKey", "species")):
                    ref = values.get(attr)
                    parent_row = self._row(state, parent, ref)
                    require(parent_row and not parent_row.get("_deleted") and
                            parent_row.get("isActive", 1) == 1, "사용할 농원과 묘목을 선택해주세요.")
                    row[attr] = ref
                require(not old or all(row[k] == old[k] for k in
                        ("farmKey", "speciesKey", "quality", "age", "size", "plantType")),
                        "기존 재고의 농원·묘목·규격은 유지합니다. 다른 규격은 새로 등록해주세요.")
                require(not any(r["_key"] != key and all(r.get(k) == row.get(k) for k in
                        ("farmKey", "speciesKey", "quality", "age", "size", "plantType")) for r in state[kind]),
                        "같은 규격의 재고가 있습니다. 기존 재고를 수정해주세요.")
            if delete:
                require(kind == "farms" and row.get("farmId", 0) > 0, "농원을 먼저 동기화해주세요.")
                require(not any(r.get("farmKey") == key and r["quantity"] > 0 for r in state["inventory"]),
                        "이 농원에 등록된 묘목 재고를 먼저 삭제해주세요.")
                row["_deleted"] = True
            operation = next((op for op in state["pending"] if op["key"] == key), None)
            require(operation is None or not operation.get("payload"),
                    "서버 확인 대기 중인 항목입니다. 같은 요청으로 동기화한 뒤 수정해주세요.")
            if operation:
                operation.update(after=copy.deepcopy(row), delete=delete)
            else:
                state["pending"].append({"id": str(uuid.uuid4()), "kind": kind, "key": key,
                                         "after": copy.deepcopy(row), "delete": delete,
                                         "baseVersion": (old or {}).get("version", "")})
            state[kind] = [r for r in state[kind] if r["_key"] != key] + [row]
            self._write(state)
            return key

    def _payload(self, state, op):
        kind, row = op["kind"], op["after"]
        payload = {"operationId": op["id"], "expectedVersion": op["baseVersion"]}
        if kind in ID_FIELD:
            payload[ID_FIELD[kind]] = row[ID_FIELD[kind]]
            if not op["delete"]:
                payload["farm" if kind == "farms" else "species"] = fields(kind, row)
        else:
            raw = fields(kind, row)
            for name, collection, ref in (("farmId", "farms", "farmKey"), ("speciesId", "species", "speciesKey")):
                parent = self._row(state, collection, row[ref])
                require(parent and parent.get(name, 0) > 0 and not parent.get("_deleted"),
                        "농원·묘목 등록이 서버에서 확인되어야 재고를 전송할 수 있습니다.")
                raw[name] = parent[name]
            payload["inventory"] = raw
        return payload

    def _download(self):
        remote = {}
        versioned = False
        for kind, action in (("farms", "list_farms"), ("species", "list_species"), ("inventory", "list_inventory")):
            response = self.manager.api.call(action)
            require(isinstance(response.get(kind), list), "서버 관리 목록을 확인하지 못했습니다.")
            remote[kind] = [remote_record(kind, r) for r in response[kind]]
            identities = [r[ID_FIELD[kind]] if kind in ID_FIELD else stock_key(r) for r in remote[kind]]
            require(len(set(identities)) == len(identities), "서버 관리 목록에 중복 번호가 있습니다.")
            if kind in ID_FIELD:
                name = "farmName" if kind == "farms" else "speciesName"
                require(len({r[name] for r in remote[kind]}) == len(remote[kind]), "서버 목록에 이름이 중복되어 있습니다.")
            else:
                versioned = response.get("inventoryVersioning") == 1
        return remote, versioned

    def sync(self):
        # Use the existing connection setup; never swap URLs or clear customer records.
        if not self.manager.credentials.get("paired"):
            self.manager.connect()
        self.read()
        remote, versioned = self._download()
        sent = 0
        while True:
            with self.vault.mutex:
                state = self.read()
                if not state["pending"]:
                    break
                op = state["pending"][0]
                if op["kind"] == "inventory":
                    require(versioned, "운영 서버의 InventoryManagement.gs를 최신 버전으로 배포해주세요. 로컬 재고는 보존됩니다.")
                if not op.get("payload"):
                    op["payload"] = self._payload(state, op)
                    self._write(state)
                op = copy.deepcopy(op)
            action = "delete_farm" if op["delete"] else ACTION[op["kind"]]
            try:
                response = self.manager.api.call(action, **op["payload"])
                record = None
                if op["delete"]:
                    require(response.get("deleted") is True and response.get("farmId") == op["payload"]["farmId"],
                            "농원 삭제 응답을 확인하지 못했습니다.")
                else:
                    record = remote_record(op["kind"], response.get(RESULT_FIELD[op["kind"]]))
                    require(response.get("saved") is True and response.get("operationId") == op["id"],
                            "저장 응답이 일치하지 않습니다. 전송 대기를 유지합니다.")
                    wanted = op["payload"]["farm" if op["kind"] == "farms" else
                                             "species" if op["kind"] == "species" else "inventory"]
                    require(all(record.get(k) == v for k, v in wanted.items()), "저장 내용이 다릅니다. 전송 대기를 유지합니다.")
                    if op["kind"] in ID_FIELD:
                        previous_id = op["payload"][ID_FIELD[op["kind"]]]
                        require(previous_id == 0 or record[ID_FIELD[op["kind"]]] == previous_id)
            except ConflictError as error:
                with self.vault.mutex:
                    state = self.read()
                    state["conflict"] = op["id"]
                    self._write(state)
                raise UserError("다른 기기의 수정과 충돌했습니다. ‘충돌 확인’에서 비교해주세요. PC 수정은 보존됩니다.") from error
            with self.vault.mutex:
                state = self.read()
                require(state["pending"] and state["pending"][0] == op, "PC 전송 대기가 변경되었습니다.")
                rows = [r for r in state[op["kind"]] if r["_key"] != op["key"]]
                if record is not None:
                    record["_key"] = op["key"]
                    if op["kind"] == "inventory":
                        record.update(farmKey=op["after"]["farmKey"], speciesKey=op["after"]["speciesKey"])
                    rows.append(record)
                state[op["kind"]] = rows
                state["pending"].pop(0)
                state["conflict"] = None
                self._write(state)
            sent += 1
        remote, _ = self._download()
        with self.vault.mutex:
            state = self.read()
            require(not state["pending"], "새 PC 수정이 있어 가져온 내용으로 덮어쓰지 않습니다.")
            for kind in ("farms", "species"):
                by_id = {r[ID_FIELD[kind]]: r["_key"] for r in state[kind]}
                for row in remote[kind]:
                    row["_key"] = by_id.get(row[ID_FIELD[kind]], f"{kind}:{row[ID_FIELD[kind]]}")
            farm_keys = {r["farmId"]: r["_key"] for r in remote["farms"]}
            species_keys = {r["speciesId"]: r["_key"] for r in remote["species"]}
            stock_keys = {stock_key(r): r["_key"] for r in state["inventory"] if r.get("farmId")}
            for row in remote["inventory"]:
                require(row["quantity"] == 0 or row["farmId"] in farm_keys and row["speciesId"] in species_keys,
                        "서버 재고의 농원·묘목 연결을 확인해주세요. PC 목록을 유지합니다.")
                row.update(_key=stock_keys.get(stock_key(row), str(uuid.uuid4())),
                           farmKey=farm_keys.get(row["farmId"], ""), speciesKey=species_keys.get(row["speciesId"], ""))
            state.update(remote, syncedAt=int(time.time() * 1000), conflict=None)
            self._write(state)
        return sent

    def conflict_preview(self):
        state = self.read()
        op = next((x for x in state["pending"] if x["id"] == state.get("conflict")), None)
        require(op is not None, "확인할 충돌이 없습니다.")
        remote, _ = self._download()
        kind = op["kind"]
        if kind in ID_FIELD:
            number = op["payload"][ID_FIELD[kind]]
            name = "farmName" if kind == "farms" else "speciesName"
            row = next((r for r in remote[kind] if r[ID_FIELD[kind]] == number or
                        number == 0 and r[name] == op["after"][name]), None)
        else:
            row = next((r for r in remote[kind] if stock_key(r) == stock_key(op["payload"]["inventory"])), None)
        return {"operation": op, "remote": row}

    def use_server(self, preview):
        with self.vault.mutex:
            state = self.read()
            op, remote = preview["operation"], copy.deepcopy(preview["remote"])
            require(op in state["pending"] and state.get("conflict") == op["id"], "전송 대기가 변경되었습니다.")
            require(remote is not None or not any(
                p["kind"] == "inventory" and op["key"] in (p["after"].get("farmKey"), p["after"].get("speciesKey"))
                for p in state["pending"]), "연결된 재고의 전송 대기가 있습니다. PC 자료를 유지합니다.")
            state[op["kind"]] = [r for r in state[op["kind"]] if r["_key"] != op["key"]]
            if remote is not None:
                remote["_key"] = op["key"]
                if op["kind"] == "inventory":
                    remote.update(farmKey=op["after"]["farmKey"], speciesKey=op["after"]["speciesKey"])
                state[op["kind"]].append(remote)
            state["pending"].remove(op)
            state["conflict"] = None
            self._write(state)
