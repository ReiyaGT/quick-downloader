# Panduan Keystore Tetap

Tujuan: semua APK hasil build punya tanda tangan yang sama, sehingga user bisa update tanpa uninstall.

## 1. Buat keystore (sekali saja, di komputer Anda)

    keytool -genkeypair -v -keystore release.keystore -alias quickdl \
      -keyalg RSA -keysize 2048 -validity 10000

Isi password saat diminta, lalu **simpan file `release.keystore` dan passwordnya di tempat aman**.
Kalau hilang, user harus uninstall lagi saat ganti kunci. Jangan commit file ini ke repo.

## 2. Ubah ke base64

Linux/macOS:

    base64 -w0 release.keystore

Windows (PowerShell):

    [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore"))

## 3. Isi GitHub Secrets

Repo → Settings → Secrets and variables → Actions → New repository secret:

| Nama secret         | Isi                              |
|---------------------|----------------------------------|
| KEYSTORE_BASE64     | hasil base64 dari langkah 2      |
| KEYSTORE_PASSWORD   | password keystore                |
| KEY_ALIAS           | `quickdl` (sesuai -alias)        |
| KEY_PASSWORD        | password key                     |

## 4. Jalankan build

Push ke `main` atau jalankan workflow manual. versionCode otomatis naik mengikuti nomor run GitHub.

## Catatan
- Pemasangan pertama setelah ganti keystore tetap perlu uninstall sekali (tanda tangan lama berbeda).
- Setelah itu, update berikutnya bisa langsung menimpa.
- Tanpa secret, build tetap jalan memakai debug keystore bawaan (tanda tangan berubah-ubah).
