/* Minimal stubs for Win32 functions called by Raritan AKC core.dll
 * Compiled as shared lib, mapped via Mono dllmap.
 * Goal: make P/Invokes succeed with benign return values so the
 * GUI constructor doesn't crash. The actual networking and input
 * paths are handled by Mono's managed System.* implementations.
 */

#include <stddef.h>
#include <string.h>

/* HRESULT S_OK = 0; we always return success and zero out outputs. */

/* urlmon.dll */
int UrlMkSetSessionOption(int dwOption, const char* pBuffer, int dwBufferLength, int dwReserved) {
    (void)dwOption; (void)pBuffer; (void)dwBufferLength; (void)dwReserved;
    return 0;  /* S_OK */
}

int UrlMkGetSessionOption(int dwOption, void* pBuffer, int dwBufferLength, int* pdwBufferLengthOut, int dwReserved) {
    (void)dwOption; (void)dwBufferLength; (void)dwReserved;
    if (pBuffer && dwBufferLength > 0) {
        /* StringBuilder marshaled as char buffer — zero it */
        ((char*)pBuffer)[0] = '\0';
    }
    if (pdwBufferLengthOut) *pdwBufferLengthOut = 0;
    return 0;
}

/* wininet.dll */
int InternetQueryOption(void* hInternet, int dwOption, void* lpBuffer, int* lpdwBufferLength) {
    (void)hInternet; (void)dwOption; (void)lpBuffer;
    if (lpdwBufferLength) *lpdwBufferLength = 0;
    return 0;  /* BOOL FALSE = "not set" */
}

/* shell32.dll */
int ExtractIconExA(const char* lpszFile, int nIconIndex, void* phiconLarge, void* phiconSmall, int nIcons) {
    (void)lpszFile; (void)nIconIndex; (void)phiconLarge; (void)phiconSmall; (void)nIcons;
    return 0;  /* no icons extracted */
}
int ExtractIconEx(const char* lpszFile, int nIconIndex, void* phiconLarge, void* phiconSmall, int nIcons) {
    return ExtractIconExA(lpszFile, nIconIndex, phiconLarge, phiconSmall, nIcons);
}
int ExtractIconExW(const void* lpszFile, int nIconIndex, void* phiconLarge, void* phiconSmall, int nIcons) {
    (void)lpszFile; (void)nIconIndex; (void)phiconLarge; (void)phiconSmall; (void)nIcons;
    return 0;
}

/* kernel32.dll — keyboard layout init + global hooks (used by RcCore.Impl.Keyboard) */
void* GetModuleHandleA(const char* lpModuleName) {
    (void)lpModuleName;
    /* Return non-zero pseudo-handle so callers think the module is loaded */
    return (void*)1;
}
void* GetModuleHandle(const char* lpModuleName) { return GetModuleHandleA(lpModuleName); }
void* GetModuleHandleW(const void* lpModuleName) { (void)lpModuleName; return (void*)1; }

void* SetWindowsHookExA(int idHook, void* lpfn, void* hMod, unsigned int dwThreadId) {
    (void)idHook; (void)lpfn; (void)hMod; (void)dwThreadId;
    /* Pretend the hook installed — RcCore checks for non-null */
    return (void*)1;
}
void* SetWindowsHookEx(int idHook, void* lpfn, void* hMod, unsigned int dwThreadId) {
    return SetWindowsHookExA(idHook, lpfn, hMod, dwThreadId);
}
void* SetWindowsHookExW(int idHook, void* lpfn, void* hMod, unsigned int dwThreadId) {
    return SetWindowsHookExA(idHook, lpfn, hMod, dwThreadId);
}
int UnhookWindowsHookEx(void* hhk) { (void)hhk; return 1; }
long CallNextHookEx(void* hhk, int nCode, unsigned long wParam, long lParam) {
    (void)hhk; (void)nCode; (void)wParam; (void)lParam;
    return 0;
}
unsigned int GetCurrentThreadId(void) { return 1; }

short GetKeyState(int nVirtKey) { (void)nVirtKey; return 0; }
short GetAsyncKeyState(int nVirtKey) { (void)nVirtKey; return 0; }
int GetKeyboardState(unsigned char* lpKeyState) {
    if (lpKeyState) memset(lpKeyState, 0, 256);
    return 1;
}
void* GetKeyboardLayout(unsigned int idThread) { (void)idThread; return (void*)0x04090409; }
int GetKeyboardLayoutNameA(char* pwszKLID) { if (pwszKLID) strcpy(pwszKLID, "00000409"); return 1; }
int GetKeyboardLayoutNameW(void* pwszKLID) { (void)pwszKLID; return 1; }
int GetKeyboardLayoutName(char* pwszKLID) { return GetKeyboardLayoutNameA(pwszKLID); }

/* Virtual-key ↔ scan-code mapping. RcCore.Impl.Keyboard.f maps VK → scancode at startup
 * for every key. Returning 0 means "no mapping" which makes RcCore mark the key as
 * inactive; KVM keyboard input would be broken but the bridge can still connect. */
unsigned int MapVirtualKeyA(unsigned int uCode, unsigned int uMapType) {
    (void)uCode; (void)uMapType;
    return 0;
}
unsigned int MapVirtualKey(unsigned int uCode, unsigned int uMapType) { return MapVirtualKeyA(uCode, uMapType); }
unsigned int MapVirtualKeyW(unsigned int uCode, unsigned int uMapType) { return MapVirtualKeyA(uCode, uMapType); }
unsigned int MapVirtualKeyExA(unsigned int uCode, unsigned int uMapType, void* dwhkl) {
    (void)uCode; (void)uMapType; (void)dwhkl;
    return 0;
}
unsigned int MapVirtualKeyEx(unsigned int uCode, unsigned int uMapType, void* dwhkl) { return MapVirtualKeyExA(uCode, uMapType, dwhkl); }
unsigned int MapVirtualKeyExW(unsigned int uCode, unsigned int uMapType, void* dwhkl) { return MapVirtualKeyExA(uCode, uMapType, dwhkl); }

short VkKeyScanA(char ch) { (void)ch; return -1; }
short VkKeyScan(char ch) { return VkKeyScanA(ch); }
short VkKeyScanW(unsigned short ch) { (void)ch; return -1; }
short VkKeyScanExA(char ch, void* dwhkl) { (void)ch; (void)dwhkl; return -1; }
short VkKeyScanEx(char ch, void* dwhkl) { return VkKeyScanExA(ch, dwhkl); }
short VkKeyScanExW(unsigned short ch, void* dwhkl) { (void)ch; (void)dwhkl; return -1; }

int ToAscii(unsigned int uVirtKey, unsigned int uScanCode, const unsigned char* lpKeyState, void* lpChar, unsigned int uFlags) {
    (void)uVirtKey; (void)uScanCode; (void)lpKeyState; (void)lpChar; (void)uFlags;
    return 0;
}
int ToUnicode(unsigned int wVirtKey, unsigned int wScanCode, const unsigned char* lpKeyState, void* pwszBuff, int cchBuff, unsigned int wFlags) {
    (void)wVirtKey; (void)wScanCode; (void)lpKeyState; (void)pwszBuff; (void)cchBuff; (void)wFlags;
    return 0;
}
int ToUnicodeEx(unsigned int wVirtKey, unsigned int wScanCode, const unsigned char* lpKeyState, void* pwszBuff, int cchBuff, unsigned int wFlags, void* dwhkl) {
    return ToUnicode(wVirtKey, wScanCode, lpKeyState, pwszBuff, cchBuff, wFlags);
}

/* Icon lifecycle */
int DestroyIcon(void* hIcon) { (void)hIcon; return 1; }
void* CopyIcon(void* hIcon) { (void)hIcon; return (void*)1; }
void* LoadIconA(void* hInstance, const char* lpIconName) { (void)hInstance; (void)lpIconName; return (void*)1; }
void* LoadIcon(void* hInstance, const char* lpIconName) { return LoadIconA(hInstance, lpIconName); }
void* LoadIconW(void* hInstance, const void* lpIconName) { (void)hInstance; (void)lpIconName; return (void*)1; }

/* Misc commonly-used */
int GetSystemMetrics(int nIndex) { (void)nIndex; return 0; }
void* GetActiveWindow(void) { return NULL; }
void* GetForegroundWindow(void) { return NULL; }
unsigned int GetTickCount(void) { return 0; }
void* GetDesktopWindow(void) { return NULL; }
int GetClientRect(void* hWnd, void* lpRect) { (void)hWnd; (void)lpRect; return 1; }
int GetWindowRect(void* hWnd, void* lpRect) { (void)hWnd; (void)lpRect; return 1; }

/* SendInput / keybd_event / mouse_event — for synthetic input. KVM does not need these
 * locally; the RFB stream is sent directly. No-op them. */
unsigned int SendInput(unsigned int cInputs, void* pInputs, int cbSize) {
    (void)cInputs; (void)pInputs; (void)cbSize;
    return 0;
}
void keybd_event(unsigned char bVk, unsigned char bScan, unsigned int dwFlags, void* dwExtraInfo) {
    (void)bVk; (void)bScan; (void)dwFlags; (void)dwExtraInfo;
}
void mouse_event(unsigned int dwFlags, unsigned int dx, unsigned int dy, unsigned int dwData, void* dwExtraInfo) {
    (void)dwFlags; (void)dx; (void)dy; (void)dwData; (void)dwExtraInfo;
}

/* === Additional user32 stubs identified from full P/Invoke inventory === */
long SendMessageA(void* hWnd, unsigned int Msg, unsigned long wParam, long lParam) {
    (void)hWnd; (void)Msg; (void)wParam; (void)lParam; return 0;
}
long SendMessage(void* hWnd, unsigned int Msg, unsigned long wParam, long lParam) { return SendMessageA(hWnd, Msg, wParam, lParam); }
long SendMessageW(void* hWnd, unsigned int Msg, unsigned long wParam, long lParam) { return SendMessageA(hWnd, Msg, wParam, lParam); }

int GetClassNameA(void* hWnd, char* lpClassName, int nMaxCount) {
    (void)hWnd;
    if (lpClassName && nMaxCount > 0) lpClassName[0] = '\0';
    return 0;
}
int GetClassName(void* hWnd, char* lpClassName, int nMaxCount) { return GetClassNameA(hWnd, lpClassName, nMaxCount); }
int GetClassNameW(void* hWnd, void* lpClassName, int nMaxCount) { (void)hWnd; (void)lpClassName; (void)nMaxCount; return 0; }

void* SetCapture(void* hWnd) { (void)hWnd; return NULL; }
int ReleaseCapture(void) { return 1; }

void* OpenDesktopA(const char* lpszDesktop, unsigned int dwFlags, int fInherit, unsigned int dwDesiredAccess) {
    (void)lpszDesktop; (void)dwFlags; (void)fInherit; (void)dwDesiredAccess;
    return (void*)1;
}
void* OpenDesktop(const char* lpszDesktop, unsigned int dwFlags, int fInherit, unsigned int dwDesiredAccess) { return OpenDesktopA(lpszDesktop, dwFlags, fInherit, dwDesiredAccess); }
void* OpenDesktopW(const void* lpszDesktop, unsigned int dwFlags, int fInherit, unsigned int dwDesiredAccess) { (void)lpszDesktop; (void)dwFlags; (void)fInherit; (void)dwDesiredAccess; return (void*)1; }
int CloseDesktop(void* hDesktop) { (void)hDesktop; return 1; }
int SwitchDesktop(void* hDesktop) { (void)hDesktop; return 1; }

int GetWindowPlacement(void* hWnd, void* lpwndpl) { (void)hWnd; (void)lpwndpl; return 0; }
int SetWindowPlacement(void* hWnd, const void* lpwndpl) { (void)hWnd; (void)lpwndpl; return 1; }
int SetWindowPos(void* hWnd, void* hWndInsertAfter, int X, int Y, int cx, int cy, unsigned int uFlags) {
    (void)hWnd; (void)hWndInsertAfter; (void)X; (void)Y; (void)cx; (void)cy; (void)uFlags; return 1;
}

int GetComboBoxInfo(void* hwndCombo, void* pcbi) { (void)hwndCombo; (void)pcbi; return 0; }

long CallWindowProcA(void* lpPrevWndFunc, void* hWnd, unsigned int Msg, unsigned long wParam, long lParam) {
    (void)lpPrevWndFunc; (void)hWnd; (void)Msg; (void)wParam; (void)lParam; return 0;
}
long CallWindowProc(void* a, void* b, unsigned int c, unsigned long d, long e) { return CallWindowProcA(a, b, c, d, e); }
long CallWindowProcW(void* a, void* b, unsigned int c, unsigned long d, long e) { return CallWindowProcA(a, b, c, d, e); }

int DispatchMessageA(const void* lpMsg) { (void)lpMsg; return 0; }
int DispatchMessage(const void* m) { return DispatchMessageA(m); }
int DispatchMessageW(const void* m) { return DispatchMessageA(m); }
int TranslateMessage(const void* lpMsg) { (void)lpMsg; return 0; }
int GetMessageA(void* lpMsg, void* hWnd, unsigned int wMsgFilterMin, unsigned int wMsgFilterMax) {
    (void)lpMsg; (void)hWnd; (void)wMsgFilterMin; (void)wMsgFilterMax; return 0;
}
int GetMessage(void* a, void* b, unsigned int c, unsigned int d) { return GetMessageA(a, b, c, d); }
int GetMessageW(void* a, void* b, unsigned int c, unsigned int d) { return GetMessageA(a, b, c, d); }
int PeekMessageA(void* lpMsg, void* hWnd, unsigned int min, unsigned int max, unsigned int wRemove) {
    (void)lpMsg; (void)hWnd; (void)min; (void)max; (void)wRemove; return 0;
}
int PeekMessage(void* a, void* b, unsigned int c, unsigned int d, unsigned int e) { return PeekMessageA(a, b, c, d, e); }
int PeekMessageW(void* a, void* b, unsigned int c, unsigned int d, unsigned int e) { return PeekMessageA(a, b, c, d, e); }

long GetWindowLongA(void* hWnd, int nIndex) { (void)hWnd; (void)nIndex; return 0; }
long GetWindowLong(void* a, int b) { return GetWindowLongA(a, b); }
long GetWindowLongW(void* a, int b) { return GetWindowLongA(a, b); }
long GetWindowLongPtrA(void* hWnd, int nIndex) { (void)hWnd; (void)nIndex; return 0; }
long GetWindowLongPtr(void* a, int b) { return GetWindowLongPtrA(a, b); }
long GetWindowLongPtrW(void* a, int b) { return GetWindowLongPtrA(a, b); }
long SetWindowLongA(void* hWnd, int nIndex, long dwNewLong) { (void)hWnd; (void)nIndex; (void)dwNewLong; return 0; }
long SetWindowLong(void* a, int b, long c) { return SetWindowLongA(a, b, c); }
long SetWindowLongW(void* a, int b, long c) { return SetWindowLongA(a, b, c); }
long SetWindowLongPtrA(void* hWnd, int nIndex, long dwNewLong) { (void)hWnd; (void)nIndex; (void)dwNewLong; return 0; }
long SetWindowLongPtr(void* a, int b, long c) { return SetWindowLongPtrA(a, b, c); }
long SetWindowLongPtrW(void* a, int b, long c) { return SetWindowLongPtrA(a, b, c); }

/* === kernel32 additions === */
int QueryPerformanceCounter(long long* lpPerformanceCount) {
    if (lpPerformanceCount) *lpPerformanceCount = 0;
    return 1;
}
int QueryPerformanceFrequency(long long* lpFrequency) {
    if (lpFrequency) *lpFrequency = 1000000;  /* 1 MHz */
    return 1;
}
void* GetProcAddress(void* hModule, const char* lpProcName) {
    (void)hModule; (void)lpProcName; return NULL;  /* "not found" — safe */
}
void* LoadLibraryA(const char* lpLibFileName) { (void)lpLibFileName; return NULL; }
void* LoadLibrary(const char* a) { return LoadLibraryA(a); }
void* LoadLibraryW(const void* a) { (void)a; return NULL; }
int FreeLibrary(void* hLibModule) { (void)hLibModule; return 1; }

/* === winmm.dll — Audio. We don't need audio; return "no devices". === */
unsigned int waveOutGetNumDevs(void) { return 0; }
unsigned int waveInGetNumDevs(void) { return 0; }
unsigned int waveOutGetDevCapsA(unsigned int uDeviceID, void* pwoc, unsigned int cbwoc) { (void)uDeviceID; (void)pwoc; (void)cbwoc; return 0; }
unsigned int waveOutGetDevCaps(unsigned int a, void* b, unsigned int c) { return waveOutGetDevCapsA(a, b, c); }
unsigned int waveInGetDevCapsA(unsigned int uDeviceID, void* pwic, unsigned int cbwic) { (void)uDeviceID; (void)pwic; (void)cbwic; return 0; }
unsigned int waveInGetDevCaps(unsigned int a, void* b, unsigned int c) { return waveInGetDevCapsA(a, b, c); }
unsigned int waveOutOpen(void* phwo, unsigned int uDeviceID, const void* pwfx, void* dwCallback, void* dwInstance, unsigned int fdwOpen) {
    (void)phwo; (void)uDeviceID; (void)pwfx; (void)dwCallback; (void)dwInstance; (void)fdwOpen;
    return 1;  /* MMSYSERR_ERROR */
}
unsigned int waveInOpen(void* phwi, unsigned int uDeviceID, const void* pwfx, void* dwCallback, void* dwInstance, unsigned int fdwOpen) {
    (void)phwi; (void)uDeviceID; (void)pwfx; (void)dwCallback; (void)dwInstance; (void)fdwOpen;
    return 1;
}
unsigned int waveOutClose(void* hwo) { (void)hwo; return 0; }
unsigned int waveOutPause(void* hwo) { (void)hwo; return 0; }
unsigned int waveOutReset(void* hwo) { (void)hwo; return 0; }
unsigned int waveOutRestart(void* hwo) { (void)hwo; return 0; }
unsigned int waveOutPrepareHeader(void* hwo, void* pwh, unsigned int cbwh) { (void)hwo; (void)pwh; (void)cbwh; return 0; }
unsigned int waveOutUnprepareHeader(void* hwo, void* pwh, unsigned int cbwh) { (void)hwo; (void)pwh; (void)cbwh; return 0; }
unsigned int waveOutWrite(void* hwo, void* pwh, unsigned int cbwh) { (void)hwo; (void)pwh; (void)cbwh; return 0; }

/* === WINSCARD.DLL — SmartCard. We don't have one. Return "no readers". === */
int SCardEstablishContext(unsigned int dwScope, const void* pvReserved1, const void* pvReserved2, void* phContext) {
    (void)dwScope; (void)pvReserved1; (void)pvReserved2;
    if (phContext) *(void**)phContext = NULL;
    return 0x8010001D;  /* SCARD_E_NO_SERVICE */
}
int SCardReleaseContext(void* hContext) { (void)hContext; return 0; }
int SCardListReadersA(void* h, const char* g, char* m, unsigned int* l) { (void)h; (void)g; (void)m; if (l) *l = 0; return 0x8010002E; }  /* NO_READERS_AVAILABLE */
int SCardListReaders(void* h, const char* g, char* m, unsigned int* l) { return SCardListReadersA(h, g, m, l); }
int SCardConnectA(void* h, const char* r, unsigned int m, unsigned int p, void* ph, unsigned int* a) {
    (void)h; (void)r; (void)m; (void)p; (void)ph; (void)a; return 0x80100069;
}
int SCardConnect(void* h, const char* r, unsigned int m, unsigned int p, void* ph, unsigned int* a) { return SCardConnectA(h, r, m, p, ph, a); }
int SCardDisconnect(void* hCard, unsigned int dwDisposition) { (void)hCard; (void)dwDisposition; return 0; }
int SCardReconnect(void* hCard, unsigned int dwShareMode, unsigned int dwPreferredProtocols, unsigned int dwInitialization, unsigned int* pdwActiveProtocol) {
    (void)hCard; (void)dwShareMode; (void)dwPreferredProtocols; (void)dwInitialization;
    if (pdwActiveProtocol) *pdwActiveProtocol = 0;
    return 0x80100069;
}
int SCardStatusA(void* h, char* r, unsigned int* rl, unsigned int* s, unsigned int* p, unsigned char* a, unsigned int* al) {
    (void)h; (void)r; (void)rl; (void)s; (void)p; (void)a; (void)al; return 0x80100069;
}
int SCardStatus(void* h, char* r, unsigned int* rl, unsigned int* s, unsigned int* p, unsigned char* a, unsigned int* al) { return SCardStatusA(h, r, rl, s, p, a, al); }
int SCardBeginTransaction(void* hCard) { (void)hCard; return 0x80100069; }
int SCardEndTransaction(void* hCard, unsigned int dwDisposition) { (void)hCard; (void)dwDisposition; return 0x80100069; }
int SCardTransmit(void* hCard, const void* pioSendPci, const unsigned char* pbSendBuffer, unsigned int cbSendLength, void* pioRecvPci, unsigned char* pbRecvBuffer, unsigned int* pcbRecvLength) {
    (void)hCard; (void)pioSendPci; (void)pbSendBuffer; (void)cbSendLength; (void)pioRecvPci; (void)pbRecvBuffer; if (pcbRecvLength) *pcbRecvLength = 0; return 0x80100069;
}
int SCardCancel(void* hContext) { (void)hContext; return 0; }
int SCardFreeMemory(void* hContext, const void* pvMem) { (void)hContext; (void)pvMem; return 0; }
int SCardGetStatusChangeA(void* hContext, unsigned int dwTimeout, void* rgReaderStates, unsigned int cReaders) {
    (void)hContext; (void)dwTimeout; (void)rgReaderStates; (void)cReaders; return 0x8010000A;  /* TIMEOUT */
}
int SCardGetStatusChange(void* h, unsigned int t, void* r, unsigned int c) { return SCardGetStatusChangeA(h, t, r, c); }

/* === ole32 — only Audio uses CoCreateInstance. Return error. === */
int CoCreateInstance(const void* rclsid, void* pUnkOuter, unsigned int dwClsContext, const void* riid, void** ppv) {
    (void)rclsid; (void)pUnkOuter; (void)dwClsContext; (void)riid;
    if (ppv) *ppv = NULL;
    return 0x80004005;  /* E_FAIL */
}
