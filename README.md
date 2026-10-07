# MonoRec

Perekam layar Android bergaya iOS: monokrom, glassmorphism, kartu data modular.
Dioptimalkan untuk Infinix HOT 11s NFC (Helio G88, layar 720×1600).

## Fitur
- Rekam layar ke MP4 (H.264) di `Movies/MonoRec`
- Sumber audio: **Senyap**, **HP** (audio internal), **HP + Mic** (dicampur)
- Kualitas: 540p / 720p / 1080p, 30 fps (resolusi tidak dinaikkan melebihi layar asli)
- Tile data langsung: durasi, ukuran file, resolusi, fps, bitrate
- Riwayat rekaman dengan thumbnail, putar, bagikan, hapus
- Tombol Berhenti di notifikasi

## Cara menjalankan (Android Studio)
1. Buka Android Studio → **Open** → pilih folder `MonoRec`.
2. Tunggu Gradle Sync selesai (butuh internet untuk mengunduh dependensi).
   - Jika muncul tawaran *Upgrade/Update Android Gradle Plugin*, setujui saja.
3. Di HP: Pengaturan → Tentang ponsel → ketuk *Nomor build* 7× → aktifkan
   **Opsi pengembang → Debugging USB**.
4. Colok HP via USB, izinkan debugging, pilih HP di toolbar Android Studio, tekan **Run ▶**.

## Push ke GitHub
```bash
cd MonoRec
git init
git add .
git commit -m "MonoRec: versi awal"
git branch -M main
git remote add origin https://github.com/mujib-syarif/MonoRec.git
git push -u origin main
```
Setiap push ke `main` akan membangun APK debug otomatis lewat GitHub Actions
(tab **Actions** → build terbaru → **Artifacts** → `MonoRec-debug-apk`).

## Catatan teknis
- `minSdk 29`: perekaman audio internal (AudioPlaybackCapture) butuh Android 10+.
- Beberapa aplikasi memblokir perekaman audionya (DRM / `allowAudioPlaybackCapture=false`);
  videonya tetap terekam, audionya senyap.
- Mode **HP + Mic**: pakai earphone agar suara speaker tidak masuk dua kali lewat mikrofon.
- Glassmorphism memakai lapisan translusen + gradasi + tepi tipis, bukan blur sungguhan,
  supaya ringan di Android 10/11.
- Rotasi layar saat merekam tidak mengubah ukuran video (tetap sesuai orientasi saat mulai).
- Rekaman yang dibuat sebelum aplikasi di-uninstall tidak akan muncul di riwayat setelah
  di-install ulang (batasan penyimpanan Android), tapi file tetap ada di `Movies/MonoRec`.

## Struktur
```
app/src/main/java/com/monorec/app/
  MainActivity.kt            izin, persetujuan rekam layar, hubungan UI ↔ service
  recorder/
    ScreenRecorderEngine.kt  video + audio, encoder, muxer
    SafeMuxer.kt             muxer aman antar-thread
    RecorderService.kt       foreground service + notifikasi
    RecorderModels.kt        konfigurasi & state bersama
  data/                      riwayat (MediaStore) & preferensi
  ui/                        tema kaca, komponen, layar utama
```
