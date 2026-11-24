# Komik — aplikasi pembaca komik (Android, native)

## Di mana mengisi link situs?
Di **dalam aplikasi**, bukan di kode:
1. Buka aplikasi. Pertama kali dibuka, layar **Pengaturan** langsung muncul.
   (Nanti bisa dibuka lagi lewat menu **⋮ → Pengaturan (link situs)**.)
2. Tempel link situs komik, cukup halaman utamanya, mis. `https://namasitus.com`.
   Kalau yang ditempel link halaman komik, aplikasi otomatis mengambil alamat utamanya.
3. Tekan **Simpan & Cek**. Kalau muncul "✔ Berhasil", tekan kembali dan mulai membaca.

Cukup **sekali**. Link tersimpan permanen di aplikasi.
Isi ulang hanya kalau situsnya pindah domain atau ingin ganti situs.

Kalau muncul pesan Cloudflare/verifikasi: tekan **Verifikasi di browser**, tunggu situs tampil normal, lalu tekan **Selesai**.

## Situs yang didukung
Situs komik yang memakai tema WordPress **MangaThemesia** atau **Madara**
(sebagian besar situs komik/manhwa berbahasa Indonesia). Tema terdeteksi otomatis.
Juga mendukung **nhentai.net** lewat API-nya (isi link `https://nhentai.net`); satu galeri dibuka sebagai satu chapter.
Kalau situsnya tidak dikenali, parser khusus perlu dibuat untuk situs itu.

## Fitur
- Daftar komik terbaru (scroll terus untuk memuat lagi) dan pencarian
- Halaman detail: sinopsis, daftar chapter, ★ Favorit, "Lanjutkan" dari chapter terakhir
- Pembaca scroll vertikal, tombol chapter sebelumnya/berikutnya, ketuk layar untuk sembunyikan bar
- Riwayat chapter terakhir dibaca per komik

## Build APK (GitHub Actions)
1. Buat repo baru di GitHub (mis. `Dartz801/komik`) lalu push isi folder ini.
2. Buka tab **Actions** → workflow **Build APK Komik** berjalan otomatis setiap push.
3. Setelah selesai, unduh artifact **Komik-apk**, ekstrak, lalu instal APK-nya di HP.

APK ditandatangani dengan keystore tetap (`app/komik-debug.jks`), jadi versi baru bisa langsung menimpa versi lama.

Catatan: untuk pemakaian pribadi. Jangan disebarkan/diunggah ke Play Store karena kontennya milik situs lain.
