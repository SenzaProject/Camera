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
