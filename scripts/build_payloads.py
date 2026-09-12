#!/usr/bin/env python3
"""
Build script for Pixel exploit payloads.
Runs inside GitHub Actions with Android NDK available.
"""
import os
import sys
import subprocess
import glob

NDK_HOME  = os.environ.get("ANDROID_NDK_HOME", "")
KSU_ROOT  = "ksu_root"
CVE_DIR   = f"{KSU_ROOT}/cves/cve-2026-43499-ghostlock"
BUILD_DIR = f"{KSU_ROOT}/build/payloads"
ASSET_DIR = "app/src/main/assets/exploits"

PAYLOADS = [
    ("pixel6-8-series.so", "bluejay-CP2A.260705.006"),
    ("pixel9-series.so",   "komodo-CP2A.260705.006"),
    ("pixel8a.so",         "akita-CP2A.260805.005"),
    ("pixel6a-cp1a.so",    "bluejay-CP1A.260405.005"),
    ("pixel10-series.so",  "blazer-CP2A.260705.006"),
]

SRCS = [
    f"{CVE_DIR}/61/main.c",
    f"{CVE_DIR}/61/util.c",
    f"{KSU_ROOT}/cves/kaslr/slide_tracefs.c",
    f"{CVE_DIR}/61/fops.c",
    f"{CVE_DIR}/61/pipe.c",
    f"{CVE_DIR}/61/root.c",
    f"{CVE_DIR}/61/preload.c",
]

def find_clang():
    ndk_bin = os.path.join(NDK_HOME, "toolchains/llvm/prebuilt/linux-x86_64/bin")
    print(f"Looking for clang in: {ndk_bin}")
    for api in [35, 34, 33, 32, 31]:
        clang = os.path.join(ndk_bin, f"aarch64-linux-android{api}-clang")
        if os.path.isfile(clang):
            print(f"Found: {clang}")
            return clang, ndk_bin
    # fallback: find any aarch64 clang
    for f in sorted(glob.glob(os.path.join(ndk_bin, "aarch64-linux-android*-clang"))):
        if not f.endswith("++"):
            print(f"Found fallback: {f}")
            return f, ndk_bin
    raise RuntimeError("No aarch64 clang found in NDK")

def setup_wallpaper():
    os.makedirs(f"{KSU_ROOT}/cves/assets", exist_ok=True)
    webp_src = f"{ASSET_DIR}/wallpaper.webp"
    png_src  = f"{ASSET_DIR}/wallpaper.png"
    dst      = f"{KSU_ROOT}/cves/assets/wallpaper.webp"

    if os.path.exists(webp_src):
        import shutil
        shutil.copy(webp_src, dst)
        print("Using wallpaper.webp from repo")
        return True
    elif os.path.exists(png_src):
        from PIL import Image
        img = Image.open(png_src).resize((921, 2048), Image.LANCZOS)
        img.save(dst, "WEBP", quality=85)
        print(f"Converted PNG to WebP: {os.path.getsize(dst)} bytes")
        return True
    else:
        print("No wallpaper found in repo")
        return False

def patch_wallpaper():
    # Write wallpaper_blob.S
    blob_asm = (
        ".section .rodata\n"
        ".global embedded_wallpaper_start\n"
        ".global embedded_wallpaper_end\n"
        ".balign 16\n"
        "embedded_wallpaper_start:\n"
        '.incbin "assets/wallpaper.webp"\n'
        "embedded_wallpaper_end:\n"
        ".balign 16\n"
    )
    with open(f"{KSU_ROOT}/cves/wallpaper_blob.S", "w") as f:
        f.write(blob_asm)
    print("wallpaper_blob.S written")

    # Patch install_embedded_wallpaper in preload.c
    preload_path = f"{CVE_DIR}/61/preload.c"
    src = open(preload_path).read()

    impl = r"""
#define WALLPAPER_PRIMARY "/sdcard/wallpaper.webp"
#define WALLPAPER_DATA    "/data/system/users/0/wallpaper"
#define WALLPAPER_ORIG    "/data/system/users/0/wallpaper_orig"

extern const unsigned char embedded_wallpaper_start[];
extern const unsigned char embedded_wallpaper_end[];

static int _wpwf(int fd, const void *buf, size_t n) {
  const unsigned char *p = buf;
  while (n > 0) {
    ssize_t r = write(fd, p, n);
    if (r < 0) { if (errno == EINTR) continue; return 0; }
    p += r; n -= r;
  }
  return 1;
}
static int _wpf(const char *path, mode_t m) {
  size_t sz = (size_t)(embedded_wallpaper_end - embedded_wallpaper_start);
  int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, m);
  if (fd < 0) return 0;
  int ok = _wpwf(fd, embedded_wallpaper_start, sz);
  close(fd); return ok;
}
int install_embedded_wallpaper(void) {
  _wpf(WALLPAPER_PRIMARY, 0644);
  int ok = _wpf(WALLPAPER_DATA, 0600) && _wpf(WALLPAPER_ORIG, 0600);
  if (ok) {
    pid_t p = fork();
    if (p == 0) {
      execl("/system/bin/restorecon", "restorecon",
            WALLPAPER_DATA, WALLPAPER_ORIG, (char *)NULL);
      _exit(1);
    }
    if (p > 0) waitpid(p, NULL, 0);
  }
  return ok;
}
"""
    # Try multiple stub formats from different repo versions
    stubs = [
        "__attribute__((weak))\nint install_embedded_wallpaper(void) {\n  errno = ENOSYS;\n  return 0;\n}",
        "__attribute__((weak)) int install_embedded_wallpaper(void) { errno = ENOSYS; return 0; }",
        "int install_embedded_wallpaper(void)",
    ]
    patched = False
    for stub in stubs:
        if stub in src:
            src = src.replace(stub, impl, 1)
            patched = True
            break

    if patched:
        if "#include <sys/wait.h>" not in src:
            src = src.replace("#include <sys/stat.h>",
                              "#include <sys/stat.h>\n#include <sys/wait.h>", 1)
        open(preload_path, "w").write(src)
        print("Wallpaper implementation patched into preload.c")
    else:
        # If stub not found, append the implementation at the end
        src += "\n" + impl
        if "#include <sys/wait.h>" not in src:
            src = src.replace("#include <sys/stat.h>",
                              "#include <sys/stat.h>\n#include <sys/wait.h>", 1)
        open(preload_path, "w").write(src)
        print("Wallpaper implementation appended to preload.c")

def assemble_wallpaper_blob(ndk_bin, clang):
    blob_s = f"{KSU_ROOT}/cves/wallpaper_blob.S"
    blob_o = f"{BUILD_DIR}/wallpaper.o"

    # Try llvm-mc first, then fall back to clang -c
    llvm_mc = os.path.join(ndk_bin, "llvm-mc")
    if os.path.isfile(llvm_mc):
        result = subprocess.run(
            [llvm_mc, "--triple=aarch64-linux-android",
             "--filetype=obj", blob_s, "-o", blob_o],
            cwd=f"{KSU_ROOT}/cves",
            capture_output=True
        )
    else:
        # Fallback: use clang to assemble
        result = subprocess.run(
            [clang, "-c", blob_s, "-o", blob_o],
            cwd=f"{KSU_ROOT}/cves",
            capture_output=True
        )

    if result.returncode == 0 and os.path.exists(blob_o):
        print(f"Wallpaper blob assembled: {os.path.getsize(blob_o)} bytes")
        return blob_o
    print(f"Wallpaper blob assembly failed: {result.stderr.decode()[:200]}")
    return None

def build_payload(clang, out_name, target, wp_obj=None):
    target_dir = f"{KSU_ROOT}/cves/targets/{target}"
    out_path   = f"{BUILD_DIR}/{out_name}"

    cmd = [
        clang, "-shared", "-fPIC", "-O2",
        "-DAPP_PAYLOAD=1",
        f"-DTARGET_CONFIG_H=<targets/{target}/target.h>",
        "-DPSELECT_ROUTE_NFDS=320",
        "-DPSELECT_MAP_FIRST_WORD=2",
        "-DPSELECT_MAP_LOCK_WORD=9",
        "-DKNOB_PSELECT_SHIFT_MIN=-2",
        "-DKNOB_PSELECT_SHIFT_MAX=6",
        f"-I{CVE_DIR}/61",
        f"-I{CVE_DIR}",
        f"-I{KSU_ROOT}/cves",
        f"-I{target_dir}",
    ] + SRCS

    if wp_obj:
        cmd.append(wp_obj)

    cmd += ["-o", out_path]

    print(f"Building {out_name}...")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode == 0 and os.path.exists(out_path):
        size = os.path.getsize(out_path)
        print(f"  OK {out_name} ({size // 1024}KB)")
        return True
    else:
        print(f"  FAILED {out_name}")
        if result.stderr:
            print(result.stderr[:500])
        return False

def main():
    os.makedirs(BUILD_DIR, exist_ok=True)

    clang, ndk_bin = find_clang()

    has_wallpaper = setup_wallpaper()
    wp_obj = None
    if has_wallpaper:
        patch_wallpaper()
        wp_obj = assemble_wallpaper_blob(ndk_bin, clang)

    results = []
    for out_name, target in PAYLOADS:
        ok = build_payload(clang, out_name, target, wp_obj)
        results.append((out_name, ok))

    print("\n=== Build Summary ===")
    success = 0
    for name, ok in results:
        status = "OK" if ok else "FAILED"
        print(f"  {status:6s}  {name}")
        if ok:
            success += 1

    print(f"\n{success}/{len(PAYLOADS)} payloads built successfully")
    print(f"Wallpaper embedded: {wp_obj is not None}")

    if success == 0:
        sys.exit(1)

if __name__ == "__main__":
    main()
