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
