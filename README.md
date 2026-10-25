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

## 1.0 Beta
- Zoom hingga 12x (zoom HAL, sisanya digital; video dibatasi zoom HAL)
- Slider cahaya (exposure) di samping cincin fokus
- Video Ganda (depan atas, belakang bawah / gambar-dalam-gambar), target 1080p 60 fps, turun otomatis bila ditolak
- Tombol peredam guncangan di kanan atas mode video + EIS Plus (stabilizer gyroscope di renderer GL)
- Deteksi wajah (mode di Lainnya + toggle), preset beauty foto dan video, mode MANUAL dengan white balance
- UI kaca cair: blur latar nyata dari pratinjau (bisa dimatikan di Pengaturan)
