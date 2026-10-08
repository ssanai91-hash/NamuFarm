"""Protect the local cache key with the signed-in Windows account (DPAPI).

No password, exportable customer RSA key, or plaintext fallback is used.
"""
import ctypes
import sys
from ctypes import wintypes

from .errors import UserError


def _transform(data, decrypt=False):
    if sys.platform != "win32":
        raise UserError("자동 연결 보관함은 Windows에서 사용할 수 있습니다.")
    if not isinstance(data, bytes) or not 0 < len(data) <= 65536:
        raise UserError("PC 연결 정보를 확인할 수 없습니다.")

    class Blob(ctypes.Structure):
        _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]

    crypt = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    function = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p if decrypt else wintypes.LPCWSTR,
                         ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                         wintypes.DWORD, ctypes.POINTER(Blob)]
    function.restype = wintypes.BOOL
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    buffer = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
    source, target = Blob(len(data), buffer), Blob()
    try:
        # Current user only. Never CRYPTPROTECT_LOCAL_MACHINE; no prompt UI.
        if not function(ctypes.byref(source), None if decrypt else "NamuFarm cache",
                        None, None, None, 1, ctypes.byref(target)):
            raise UserError("이 Windows 계정에서 PC 연결 정보를 열 수 없습니다. 기존 파일을 보존했습니다.")
        return ctypes.string_at(target.data, target.size)
    finally:
        ctypes.memset(buffer, 0, len(data))
        if target.data:
            ctypes.memset(target.data, 0, target.size)
            kernel.LocalFree(target.data)


def protect(data):
    return _transform(data)


def unprotect(data):
    return _transform(data, decrypt=True)
