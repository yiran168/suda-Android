# Bundled legacy Office parser

`poishadow-5.5.1-android.jar` is built from
[`centic9/poi-on-android`](https://github.com/centic9/poi-on-android) commit
`a29c7a4cdd94175c2be43678c82afc818b60ff3f`. The build uses Apache POI 5.5.1 and
the Android XML/AWT compatibility shims from that project.

- SHA-256: `D2AAD34BA000B42BEBDD680FDC190E22183C7F1BCA04A94DBC25655150A1FD0E`
- License: Apache License 2.0 (the same license as this repository)
- Scope: legacy OLE Word/WPS (`.doc`, `.wps`) and PowerPoint/WPS (`.ppt`, `.dps`)

Modern `.docx`/`.pptx` files continue to use the app's bounded structured parser so this
large compatibility library is not initialized during normal editing or printing.

`patches/org/apache/poi/poifs/nio/CleanerUtil.java` replaces POI's desktop-only explicit
`MappedByteBuffer` cleaner. The original implementation emits `MethodHandle.invokeExact`, which
Android cannot dex below API 26. The app reads bounded `InputStream`s and does not memory-map these
documents, so reporting unmapping as unsupported is sufficient and keeps API 24/25 working.

`patches/org/apache/poi/poifs/crypt/dsig/services/TimeStampSimpleHttpClient$UnsafeTrustManager.java`
is a binary-compatible security hardening of POI's optional timestamp client. It delegates to the
platform CA store instead of accepting arbitrary TLS certificates. 素打 does not use that network
timestamp feature, but the bundled runtime should still be safe if it is reached accidentally.
