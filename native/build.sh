#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
NDK=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK}
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
OUT="$ROOT/assets/native/arm64-v8a"
mkdir -p "$OUT"
"$CC" -std=c11 -O2 -Wall -Wextra -Werror -fPIC -fno-builtin -fvisibility=hidden -mbranch-protection=bti -mno-outline-atomics \
  -shared -Wl,-z,relro,-z,now -o "$OUT/libqpollhook.so" "$ROOT/native/qpoll_hook.c" -ldl
"$CC" -std=c11 -O2 -Wall -Wextra -Werror -mbranch-protection=bti \
  -Wl,-z,relro,-z,now -o "$OUT/qpoll-inject" "$ROOT/native/qpoll_inject.c" -ldl
sha256sum "$OUT/libqpollhook.so" "$OUT/qpoll-inject"
cd "$ROOT"
sha256sum native/build.sh native/nci_rewrite.h native/hal_discovery.h native/hal_name.h native/hook_debug.h native/owned_hook.h native/qpoll_hook.c native/qpoll_inject.c \
  assets/native/arm64-v8a/libqpollhook.so assets/native/arm64-v8a/qpoll-inject > assets/native/build.sha256
