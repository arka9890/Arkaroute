# OmniRoute Android — Design Doc

**Tanggal:** 2026-11-04
**Status:** Disetujui (1A, 2A, B1, tema claymorphism, build di GitHub)
**Repo:** https://github.com/arka9890/Arkaroute

---

## Intent & Why

Satu APK yang berdiri sendiri di HP: **menjalankan gateway AI bergaya OmniRoute**
di `127.0.0.1:20128`, sehingga coding agent mana pun (Claude Code, OpenCode,
Cursor, SDK OpenAI-compatible) bisa nunjuk ke HP ini sebagai endpoint — dengan
antarmuka claymorphism sesuai mockup di
`/root/workspace/stitch_omniroute_android_glass_dashboard/`.

Referensi: https://github.com/ChrisCompton/omniroute (gateway AI multi-provider,
kontrak API OpenAI-compatible). Kita mengambil **peran dan kontrak API**-nya,
bukan kodenya (kode asli TypeScript/Next.js tidak bisa jalan di APK).

## Goals

1. **API gateway nyata**: `POST /v1/chat/completions` (streaming SSE),
   `GET /v1/models`, `GET /health` (+ `/v1/embeddings` bila murah).
   Kontrak sama dengan OmniRoute → klien OpenAI-compatible bisa dipasang ke
   `http://<ip>:20128/v1` dengan API key apa pun.
2. **Routing & fallback**: urutan combo (rantai prioritas model), fallback
   otomatis saat 429/5xx/timeout, API key gateway untuk melindungi akses.
3. **6 layar UI** persis mockup claymorphism, bahasa Indonesia.
4. **Server tetap hidup** sebagai foreground service (notifikasi persisten)
   walau app di-background.
5. **Build di GitHub Actions** → artifact APK; lokal hanya unit-test Rust.

## Non-goals / Anti-goals

- Bukan port MCP server, A2A, Electron, atau dashboard Next.js asli.
- Bukan Termux / bukan membungkus Node.js.
- **v1 API-key only** — login OAuth (Google / AWS Builder ID / Qoder) ditunda
  ke tahap berikutnya, meski mockup menampilkannya.
- Tidak ada iap, analytics, atau telemetri pihak ketiga.
- Belum Play Store (dirilis sebagai artifact CI dulu).

## Constraints

- Mesin dev: **Alpine Linux ARM64** — build Android lokal tidak layak
  (toolchain Google hanya x86_64) → **semua build di CI**.
- `git` sempat rusak karena `/etc/ld-musl-aarch64.path` hanya berisi
  `/opt/arkacode/lib` (path bawaan musl tertimpa). **Sudah diperbaiki** dengan
  menambah `/lib`, `/usr/lib`, `/usr/local/lib`.
- Kredensial GitHub sudah ada via `credential.helper=store`
  (`~/.git-credentials`), git identity `arkacode <arkacode@local>`.
- Toolchain yang masih perlu dipasang: Rust (rustup, untuk unit-test lokal).
  JDK 17 + Android SDK/NDK hanya dipakai di CI.
- Target: minSdk 26, target 35, package `com.omniroute.app`.

## Approaches (pertimbangan)

| | Jalur | Keputusan |
|---|---|---|
| A | Node.js asli dibungkus APK (nodejs-mobile) | ❌ modul native `better-sqlite3`/`@swc` harus di-cross-compile, APK 200–400 MB, rawan gagal |
| **B1** | **Core gateway Rust + UI Jetpack Compose** | ✅ **dipilih** — APK ringan, foreground service andal, kontrol penuh atas claymorphism |
| B2 | Tauri 2 (semua Rust, UI dari HTML mockup) | ❌ Android-nya masih muda, pertahanan server di background rumit |
| C | App = UI, server di Termux | ❌ tidak "langsung HP", user harus maintain Termux |

## Decisions

- **1A — Navigasi**: 5 tab **Service · WebView · Providers · Combos · Routes**;
  **Settings** dibuka lewat ikon avatar di pojok kanan atas (mengikat semua layar).
- **2A — Semua layar dipertahankan persis desain**, tapi angka yang menyesatkan
  (WireGuard, tun0, "Mesh Peer", throughput Mbps jaringan) **diganti metrik
  gateway yang nyata**: uptime daemon, token/menit, latensi per provider,
  antrean request, log live dari core Rust.
- **Tema: claymorphism** (folder `*_claymorphism`) sebagai satu-satunya gaya;
  toggle gelap/terang tetap ada sesuai layar Settings.
- **1A — Keamanan & jaringan**: bind default `127.0.0.1`; toggle **"Akses Jaringan
  Lokal (LAN)"** di Settings (default MATI) mengubah bind ke `0.0.0.0` — dan
  **menyalakan LAN dipaksa membuat gateway API key dulu** (tanpa key valid → 401).
  Tanpa LAN, endpoint tetap minta key (kecuali loopback + mode dev). Tidak ada
  skenario port terbuka tanpa autentikasi.
- **2C — Dua protokol di v1**: `POST /v1/chat/completions` (OpenAI) **dan**
  `POST /v1/messages` (Anthropic) keduanya berfungsi, lengkap dengan
  penerjemahan request **dan** streaming SSE dua arah. Ini inti nilai OmniRoute:
  satu combo bisa berisi provider Anthropic → OpenAI-compat → dst tanpa klien
  merasakan perbedaan protokol.
- Tab WebView memuat **dashboard HTML lokal** yang disajikan server kita
  (mockup claymorphism), dan bisa diganti URL bebas oleh user.
- Penyimpanan: Room + SQLCipher, kunci di Android Keystore (AES-256-GCM,
  sesuai catatan pada mockup); API key provider terenkripsi.
- Port default 20128 (mengikuti OmniRoute), bisa diubah di Settings.

## Architecture

```
core/        crate Rust murni (diuji unit-test lokal)
             • axum: /v1/chat/completions, /v1/models, /health, /dashboard/*.html
             • mesin routing: combo → fallback chain → retry policy → quota tracker
             • adapter: OpenAI-compatible generik + Anthropic (SSE diteruskan apa adanya)
             • konfigurasi di app sandbox (JSON), bridge UniFFI/JNI ke Kotlin
app/         Kotlin + Jetpack Compose (Material3)
             • tema claymorphism: kartu sudut membulat, bayangan inner, gradien cyan/indigo
             • 5 tab + Settings via avatar; WebView (androidx.webkit) muat dashboard lokal
             • ForegroundService menyalakan core Rust; opsi "jalankan saat boot"
             • Room + SQLCipher, API key provider terenkripsi di Keystore
dashboard/   HTML/Tailwind claymorphism (dari mockup) disajikan core → tab WebView
.github/     build.yml: dtolnay/rust-toolchain → cross-compile aarch64-linux-android
             → Gradle assembleRelease → upload artifact APK
```

## Scope layar (adaptasi 2A)

| Layar | Sumber data |
|---|---|
| Service | status server, uptime, request/menit, token terpakai, log live dari core |
| WebView | dashboard HTML lokal (bisa ganti URL) |
| Providers | daftar provider API-key + kuota/terpakai, tombol connect/uji |
| Combos | rantai fallback model, API key gateway, simulator kirim uji |
| Routes | urutan rute fallback nyata: provider tujuan, status sehat, latensi, log request |
| Settings | tema, port, boot start, timeout, backup/restore config JSON |

## Adapter provider (v1)

- **OpenAI-compatible generik** — mencakup OpenAI, DeepSeek, Groq, OpenRouter,
  Ollama, dll (base URL + API key sudah cukup).
- **Anthropic** (`/v1/messages`) — format request/seluruhnya diterjemahkan,
  SSE diteruskan apa adanya.
- Adapter lain menyusul setelah dua ini jalan end-to-end.

## Resolusi builder-check (keputusan teknis)

1. **Pemilihan model** — `model: "<nama-combo>"` (atau `combo/<nama>`) menjalankan
   rantai fallback; `model: "provider/model-id"` langsung ke provider itu;
   nama tidak dikenal → 404 (atau jatuh ke default combo bila diaktifkan di
   config). `GET /v1/models` menampilkan **keduanya**.
2. **Sumber data kuota Providers** — akumulasi token dari field `usage` pada
   response + limit yang diisi manual user; header ratelimit provider dipakai
   bila tersedia. Provider yang tidak melaporkan apa pun → tampil **"—"**
   (bukan angka karangan). Mockup "5 jam rolling" hanya tampil bila datanya nyata.
3. **Layar Routes** — tidak ada mockup claymorphism (`omniroute_rute_node` hanya
   versi glass). Layout diambil dari situ, **di-styling ulang ke claymorphism**,
   isinya metrik gateway nyata (2A). Tidak menunggu aset desain baru.
4. **Toggle v1 vs "segera"** — fungsional: tema (gelap/terang/OLED + slider
   kedalaman clay), LAN bind, boot start, ubah port, backup/restore JSON,
   ping provider, rotasi sesi, hapus cache. **Nonaktif berlabel "segera"**:
   Cloudflare Quick Tunnel, "Periksa Update OmniRoute", Hibernate. Sakelar yang
   belum berfungsi tidak pernah tampil menyala seolah jalan.
5. **Dashboard tab WebView** — aset (Tailwind/font) **dibundel lokal, tanpa
   CDN**, angka utama diambil dari `/health` + endpoint metrik sungguhan.
   Log live stream ditampilkan di layar Service (native), bukan di dashboard.
6. **Signing rilis** — keystore dibuat sekali, disimpan sebagai GitHub secret
   (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`), `assembleRelease`
   ditandatangani CI. Tanda tangan stabil agar update tanpa uninstall.
7. **Client API Keys** — daftar beberapa key (label + rahasia) di DB terenkripsi;
   validasi `Authorization: Bearer` pada semua endpoint `/v1`.

## What's left to the builder

1. Instal toolchain lokal (Rust via rustup).
2. Scaffold proyek (Gradle + wrapper, modul `core` Rust, modul `app` Compose).
3. Implement bertahap: core → bridge → UI → foreground service → CI.
4. Rencana urutan pengerjaan ditulis terpisah di
   `docs/superpowers/plans/`.
