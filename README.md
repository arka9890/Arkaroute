# OmniRoute Core

Gateway AI ringan yang mengekspos endpoint kompatibel OpenAI (`/v1/chat/completions`) dan Anthropic (`/v1/messages`) di atas satu atau lebih provider upstream, dengan combo fallback, autentikasi API key, statistik kuota, dan dashboard tersemat. Ditulis dalam Rust (crate `omniroute-core`), dijalankan dengan `cargo run`, dan kelak disematkan ke aplikasi Android.

## Quickstart

```bash
source $HOME/.cargo/env
cargo run --manifest-path core/Cargo.toml
```

Gateway mendengarkan di `http://127.0.0.1:20128` secara default. Konfigurasi disimpan di `./omniroute.json` (dibuat otomatis dengan nilai default bila belum ada).

Opsi CLI:

```bash
cargo run --manifest-path core/Cargo.toml -- --bind 0.0.0.0 --port 3000 --config /tmp/omniroute.json
```

Cek kesehatan:

```bash
curl -s http://127.0.0.1:20128/health
```

## Contoh `omniroute.json`

```json
{
  "bind": "127.0.0.1",
  "port": 20128,
  "lan_enabled": false,
  "gateway_keys": [],
  "default_combo": null,
  "providers": [
    {
      "id": "openai",
      "kind": "OpenAiCompatible",
      "base_url": "https://api.openai.com/v1",
      "api_key": "sk-...",
      "models": ["gpt-4o"]
    },
    {
      "id": "anthropic",
      "kind": "Anthropic",
      "base_url": "https://api.anthropic.com/v1",
      "api_key": "sk-ant-...",
      "models": ["claude-3-5-sonnet-20241022"]
    }
  ],
  "combos": [
    {
      "name": "free-stack",
      "steps": [
        { "provider": "openai", "model": "gpt-4o" },
        { "provider": "anthropic", "model": "claude-3-5-sonnet-20241022" }
      ]
    }
  ],
  "request_timeout_ms": 600000,
  "stream_idle_timeout_ms": 60000
}
```

## Contoh pemakaian

OpenAI:

```bash
curl -s http://127.0.0.1:20128/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"free-stack","messages":[{"role":"user","content":"hi"}],"stream":false}'
```

Anthropic:

```bash
curl -s http://127.0.0.1:20128/v1/messages \
  -H "Content-Type: application/json" \
  -d '{"model":"free-stack","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}'
```

## Aturan autentikasi

- Jika `gateway_keys` tidak kosong, setiap request ke `/v1/*` dan `/health` (serta `/dashboard/*` dari peer non-loopback) wajib membawa `Authorization: Bearer <key>` yang valid, terlepas dari peer.
- Jika `gateway_keys` kosong, route tersebut hanya diizinkan dari peer loopback; selain itu `403`.
- `/dashboard/*` selalu diizinkan dari loopback; dari peer non-loopback mengikuti aturan yang sama.
- `GatewayConfig::validate()` menolak konfigurasi dengan `lan_enabled = true` tetapi `gateway_keys` kosong.

## Spesifikasi

Rancangan lengkap: `docs/superpowers/specs/2026-11-04-omniroute-android-design.md`.

## Android (APK)

Aplikasi Android OmniRoute menjalankan gateway (crate Rust `omniroute-core`, via JNI `libomniroute_bridge.so`) langsung di perangkat, menghadap endpoint `/v1/chat/completions`, `/v1/messages`, dan `/health` — tanpa server terpisah. Di Android, gateway berjalan sebagai foreground service (`GatewayService`) yang menampilkan notifikasi "gateway running" secara permanen; service dimulai ulang secara otomatis saat reboot (`BootReceiver`).

**Mengunduh APK.** APK di-build oleh CI (`.github/workflows/apk.yml`) dan tersedia sebagai artifact Actions dari workflow **apk** pada branch `dev`:

- `app-debug` — APK debug (cocok untuk pengembangan lokal).
- `omniroute-release` — APK release (ditandatangani agar bisa di-update in-place; kunci stable disimpan sebagai repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`).

Unduh dari halaman Actions repository → pilih run `apk` terbaru pada branch `dev` → bagian **Artifacts** → `omniroute-release` (atau `app-debug`).

**Pemasangan.** Perangkat **arm64-only** (`arm64-v8a`). Pasang APK dengan mengizinkan "install from unknown sources" pada browser/file manager, lalu buka aplikasi. Foreground notification menandakan gateway sedang berjalan; daemon tidak dikontrol dari panel sistem.

**First-run.** Layanan otomatis dimulai saat aplikasi pertama dibuka. Konfigurasi disimpan terenkripsi di `config.enc` (di `filesDir` aplikasi, kunci di Android Keystore via `KeystoreCryptoEngine`/`CryptoEngine`). Jika `config.enc` hilang atau rusak, aplikasi memakai konfigurasi default (listen di loopback port 20128) dan memindahkan konfigurasi rusak ke `config.corrupt`. Untuk akses dari LAN, aktifkan LAN dan atur API key terlebih dahulu melalui UI aplikasi — gateway menolak konfigurasi dengan LAN aktif tetapi tanpa key.

**Cara CI meng-build.** Workflow `apk` punya dua job:

1. `build` — setiap push/PR: unit tests + `assembleDebug`; memverifikasi simbol JNI dan bahwa `.so` terkemas di APK.
2. `release` — hanya saat push ke `dev`: `assembleRelease` dengan signing secrets; mengunggah artifact `omniroute-release`. Jika secrets signing belum tersedia, job ini menghasilkan APK release tanpa tanda tangan (unsigned) — tetap bisa dipasang, tetapi update in-place tidak tersedia sampai signing dikonfigurasi.
