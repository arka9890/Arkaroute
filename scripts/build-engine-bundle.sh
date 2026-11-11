#!/usr/bin/env bash
# Membangun bundel engine siap-pakai (tanpa npm saat install).
#
# Sumber: engine yang sudah terpasang & berfungsi di perangkat (rootfs Debian 12
# + Node 22 + paket omniroute beserta dependensinya), lalu dipangkas:
#   - cache npm dibuang (tidak dipakai lagi)
#   - binary native untuk platform selain linux-arm64 dibuang
#
# Hasil: app/src/main/assets/omniroute.tar.xz  → dibundel ke dalam APK.
#
# Cara pakai (di mesin build, butuh adb + 7z):
#   adb exec-out run-as com.example.omniroute tar -cf - -C files proot > engine.tar
#   tar -xf engine.tar
#   7z a -t xz -mx=5 -bso0 -bsp0 assets/omniroute.tar.xz \
#       engine/proot/node/lib/node_modules/omniroute
#
# Catatan: file ini sengaja TIDAK di-commit ke git (ukurannya ~1 GB).
set -euo pipefail

ENGINE_TAR="${1:-engine.tar}"
OUT_DIR="${2:-app/src/main/assets}"
WORK="${WORK_DIR:-./engine-build}"

echo "[1/4] Mengekstrak $ENGINE_TAR ..."
mkdir -p "$WORK"
tar -xf "$ENGINE_TAR" -C "$WORK"

echo "[2/4] Memangkas (cache npm + binary non-arm64) ..."
rm -rf "$WORK/proot/rootfs/root/.npm"
rm -rf "$WORK/proot/node/lib/node_modules/omniroute/node_modules/onnxruntime-node/bin/napi-v6/win32"
rm -rf "$WORK/proot/node/lib/node_modules/omniroute/node_modules/onnxruntime-node/bin/napi-v6/darwin"

echo "[3/4] Membuat $OUT_DIR/omniroute.tar.xz ..."
mkdir -p "$OUT_DIR"
( cd "$WORK/proot/node/lib/node_modules" \
  && 7z a -t xz -mx=5 -bso0 -bsp0 "$OLDPWD/$OUT_DIR/omniroute.tar.xz" omniroute )

echo "[4/4] Selesai:"
ls -lh "$OUT_DIR/omniroute.tar.xz"
