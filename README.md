# ISO to CSO/CHD — Android Offline

Aplikasi Android standalone untuk mengompres image game PSP berformat ISO menjadi CSO v1 atau CHD tanpa internet dan tanpa PPSSPP.

## Fitur

- Android Storage Access Framework: tidak meminta izin akses seluruh penyimpanan.
- Kompresi CSO v1 kompatibel dengan emulator PSP.
- CHD `createdvd` untuk PPSSPP 1.17+ menggunakan engine MAME CHDMan.
- Level kompresi 1–9, progres, ukuran input/output, rasio, kecepatan, dan tombol batal.
- Validasi tabel indeks dan ukuran output setelah kompresi.
- Menulis ke lokasi yang dipilih pengguna dan menghapus output parsial saat gagal/dibatalkan jika memungkinkan.
- UI native Java ringan; tidak memakai WebView atau library UI tambahan.

## Build

Persyaratan: JDK 17 dan Android SDK. Jalankan:

```bash
gradle :app:assembleDebug
```

APK berada di `app/build/outputs/apk/debug/app-debug.apk`.

Android Studio dapat langsung membuka proyek. Alternatifnya, pasang Gradle 8.10.2 atau jalankan workflow GitHub Actions yang sudah disertakan; APK debug akan tersedia sebagai artifact.

## Catatan format

CSO menggunakan blok 2048 byte. Blok disimpan sebagai raw DEFLATE hanya jika hasilnya lebih kecil; jika tidak, blok disalin tanpa kompresi dan ditandai pada tabel indeks. File ISO asli tidak diubah.

CHD hanya ditujukan untuk PPSSPP 1.17 atau lebih baru dan tidak kompatibel dengan PSP fisik. Engine CHD berasal dari MAME/CHDMan dan mengikuti lisensi GPL-2.0; source terkait ditautkan sebagai submodule.
