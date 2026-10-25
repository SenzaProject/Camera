# Camera — SenzaProject

Kamera Android (Camera2) dengan UI bergaya iOS 27 (liquid glass) dan **Unlock EIS 1080p 60 fps**.

- minSdk 34 · targetSdk 36 · tanpa dependensi AndroidX
- Build: push ke GitHub → Actions "Build APK" → unduh artifact `Camera.apk`
- Keystore tetap (`app/senza.jks`) supaya update APK tidak bentrok tanda tangan

## Push lewat Termux
```
cd Camera
git init && git add . && git commit -m "Camera v1.0"
git branch -M main
git remote add origin https://github.com/SenzaProject/Camera.git
git push -u origin main
```

## v1.1
- Perbaikan sesi kamera (callback sesi lama tidak lagi dianggap gagal; fallback bertahap bila perangkat menolak konfigurasi)
- Watermark nama perangkat (Pengaturan → Watermark, atau ikon di baris opsi di bawah chevron)
- Log kamera di Pengaturan untuk diagnosis

## v1.2
- Akar bug ganti mode: sesi baru dibuat sebelum sesi lama benar-benar tertutup (onConfigureFailed). Sekarang menunggu onClosed dan menangani ganti mode beruntun
- Mode Potret: blur latar berbasis deteksi wajah (diterapkan setelah foto), bukan bokeh kedalaman
- Pesan penjelas tiap mode (apakah fitur didukung perangkat atau tidak)
- Nama watermark tidak lagi ditempeli merek dari Build.MANUFACTURER (ROM bisa memalsukannya)

## v1.3
- Pratinjau hitam setelah ganti mode foto → video (60 fps): HAL menerima sesi tetapi tidak mengirim frame, tanpa error, sehingga tangga fallback tidak pernah jalan. Sekarang ada pemantau frame pertama (3 dtk); bila tidak ada frame, sesi dianggap gagal dan turun ke 30 fps, lalu ke jalur biasa tanpa EIS
- Pembuatan EGL window surface (pratinjau dan perekam GL) kini dicek. Sebelumnya EGL_NO_SURFACE diabaikan sehingga pratinjau/video diam-diam kosong
- Log kamera: `capture gagal reason=1` (REASON_FLUSHED, wajar saat stop rekam) tidak lagi dicatat sebagai kegagalan; kapasitas 40 → 120 baris, cap waktu sampai milidetik
- Log baru: rentang fps yang diminta (+ penanda bila di luar daftar HAL), waktu hasil kamera pertama, dan fps terukur vs diminta
- Deteksi nama perangkat tidak lagi berjalan ganda dari beberapa thread (baris `device:` berulang di log)

## v1.4
- Kamera depan: hasil foto dan video kini ikut dicermin seperti pratinjau (sebelumnya hanya pratinjau yang dicermin sehingga hasilnya tampak seperti kamera belakang). Opsi baru Pengaturan → Tampilan → "Cermin hasil kamera depan" (default aktif)
- Foto: dibalik horizontal sebelum potret/beauty/watermark, jadi teks watermark tetap terbaca normal
- Video: kamera depan memakai jalur GL supaya bisa dicermin; orientasi rekaman saat ponsel miring ikut dikoreksi. Video 4K dan selang waktu belum dicermin
- Pratinjau dan hasil rekaman di renderer GL kini punya pengaturan cermin terpisah

## 1.0 Beta
- Zoom hingga 12x (zoom HAL, sisanya digital; video dibatasi zoom HAL)
- Slider cahaya (exposure) di samping cincin fokus
- Video Ganda (depan atas, belakang bawah / gambar-dalam-gambar), target 1080p 60 fps, turun otomatis bila ditolak
- Tombol peredam guncangan di kanan atas mode video + EIS Plus (stabilizer gyroscope di renderer GL)
- Deteksi wajah (mode di Lainnya + toggle), preset beauty foto dan video, mode MANUAL dengan white balance
- UI kaca cair: blur latar nyata dari pratinjau (bisa dimatikan di Pengaturan)
