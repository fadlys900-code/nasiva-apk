# 📱 NASIVA STORAGE - NATIVE ANDROID APK (REAL-TIME BACKGROUND NOTIF & ULTRA-LIGHT)

Proyek ini adalah source code lengkap aplikasi Android Native (**`NasivaStorage-Realtime-v21.apk`**) yang dirancang khusus untuk terhubung secara otomatis dengan website & database Firebase **Nasiva Storage Bekasi & Cibitung**.

---

## ✨ KEUNGGULAN APLIKASI APK INI DIBANDINGKAN BROWSER BIASA:
1. **Notifikasi Real-Time 24 Jam (Tetap Bunyi Walau Aplikasi Ditutup / Di-Swipe Close):**
   - Dilengkapi **`NasivaRealtimeService` (Android Foreground Service)** yang memantau langsung **Firebase Realtime Database (`nasivastorage-3a604`)** dan jalur **Cloud Push (`ntfy.sh`)** di latar belakang.
   - **Mode Pelanggan:** Otomatis memunculkan notifikasi pop-up di layar HP (lengkap dengan suara & getar) saat Admin mencentang **DIISI**, **TES**, **100% SELESAI**, atau menulis **Catatan Admin**.
   - **Mode Admin:** Otomatis memunculkan notifikasi pop-up di layar HP Admin setiap kali ada pelanggan baru yang menekan tombol **SINKRONKAN** beserta jumlah gamenya.
   - **Deep-Link Otomatis:** Saat notifikasi di layar HP diketuk, aplikasi langsung membuka pop-up Keranjang untuk nomor WA tersebut.
2. **Jauh Lebih Ringan & Cepat (Ultra-Light Native WebView):**
   - Otomatis mengaktifkan **Mode Ultra-Ringan (`nasiva-in-apk`)** yang mematikan efek berat browser dan menyimpan cache katalog 80 TB secara lokal di memori aplikasi.
   - Dilengkapi fitur **Anti-Kill Baterai** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) agar sistem Android tidak mematikan pemantau notifikasi di latar belakang.

---

## 🚀 CARA PALING MUDAH MEMBUAT FILE `.APK` (TANPA INSTAL ANDROID STUDIO DI LAPTOP!)
Karena merakit file `.apk` di laptop membutuhkan Android SDK yang berukuran besar (~4 GB), proyek ini sudah dilengkapi **Robot Pembuat APK Otomatis di Cloud (GitHub Actions)**. Anda bisa menghasilkan file `.apk` hanya dari browser dalam waktu **±2 Menit**:

1. Buat akun gratis di [https://github.com](https://github.com) lalu buat **New Repository** (misalnya beri nama `nasiva-apk`).
2. Ekstrak file **`NasivaStorage-Android-APK-Project-v21.zip`** ini di laptop/HP Anda.
3. Upload seluruh isi folder ini ke repository GitHub tersebut (klik **Add file** -> **Upload files** -> tarik semua file & folder termasuk folder `.github` dan `app` -> klik **Commit changes**).
4. Klik menu tab **Actions** di bagian atas repository GitHub Anda.
5. Anda akan melihat proses **"Build Nasiva Storage Android APK"** berjalan otomatis. Tunggu sekitar **1,5 – 2 menit** hingga muncul tanda centang hijau (✅).
6. Klik proses yang sudah centang hijau tersebut, gulir ke bagian bawah (**Artifacts**), lalu klik **`NasivaStorage-Realtime-v21-APK`** untuk mengunduh file **`app-debug.apk`** yang siap langsung di-install di HP Android Anda maupun pelanggan!

---

## 💻 CARA KEDUA: BUILD LOKAL MENGGUNAKAN ANDROID STUDIO (OPSIONAL)
Jika Anda memiliki **Android Studio** di PC/Laptop:
1. Buka **Android Studio** -> pilih **Open** -> arahkan ke folder hasil ekstrak ZIP ini.
2. Tunggu proses *Gradle Sync* selesai.
3. Klik menu **Build** -> **Build Bundle(s) / APK(s)** -> **Build APK(s)**.
4. File `.apk` siap diambil di folder `app/build/outputs/apk/debug/app-debug.apk`.

---

## ⚙️ KONFIGURASI URL WEBSITE (JIKA MENGGUNAKAN CUSTOM DOMAIN)
Secara bawaan, aplikasi mengarah ke `https://jasaisigamesbekasi.blogspot.com`.
- Anda juga dapat mengubah URL website kapan saja langsung dari dalam aplikasi dengan menekan tombol **Pengaturan Service Real-Time APK** (atau menekan tombol titik tiga di pojok kanan atas aplikasi).
