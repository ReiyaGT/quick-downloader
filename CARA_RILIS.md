# Cara Merilis Versi Baru (SemVer)

Format: `vMAJOR.MINOR.PATCH`
- PATCH: perbaikan bug kecil (v1.0.0 -> v1.0.1)
- MINOR: fitur baru yang aman (v1.0.1 -> v1.1.0)
- MAJOR: perubahan besar (v1.1.0 -> v2.0.0)

versionCode dihitung otomatis: `MAJOR*10000 + MINOR*100 + PATCH` (v1.2.3 = 10203).

## Langkah merilis
    git add .
    git commit -m "Deskripsi perubahan"
    git push
    git tag v1.0.0
    git push origin v1.0.0

GitHub Actions akan membangun APK dan membuat Release `v1.0.0` berisi `QuickDownloader.apk`.
Push biasa ke `main` hanya membangun APK (artifact), tidak membuat Release.

## Catatan
- Tag harus berformat `vX.Y.Z`. Tag dengan akhiran (mis. `v1.1.0-beta.1`) dianggap pre-release
  dan tidak dipakai tombol "Cek Update Aplikasi".
- Nomor versi tidak boleh turun, dan tag yang sama tidak bisa dipakai dua kali.
- Pastikan secret keystore sudah diisi (lihat PANDUAN_KEYSTORE.md) agar update bisa menimpa tanpa uninstall.
- Pengguna build lama (versi `build-N`) tidak akan melihat rilis SemVer lewat tombol update;
  mereka perlu memasang v1.0.0 secara manual sekali.
