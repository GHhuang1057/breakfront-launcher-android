#!/usr/bin/env python3
"""Stage the BREAKFRONT assets bundled into the Android (FCL) launcher APK.

Downloads the breakfront client-mods zip from the public bfupdate mirror,
drops server-only mods, writes the client jars into the APK asset directory,
and writes a preset ``servers.dat`` (play.geekhonize.top) that the launcher
copies into the game directory on first launch.

Stdlib only.

Usage:
    python3 pack/build_android_assets.py \
        --mods-dir FCL/src/main/assets/breakfront/clientmods \
        --server-dat FCL/src/main/assets/breakfront/servers.dat
"""
from __future__ import annotations

import argparse
import io
import json
import os
import struct
import urllib.request
import zipfile
from pathlib import Path

UPDATE_BASE = os.environ.get("BF_UPDATE_BASE", "https://bfupdate.geekhonize.top")
SERVER_IP = "play.geekhonize.top:25565"
SERVER_NAME = "BREAKFRONT 破阵前线"
UA = {"User-Agent": "breakfront-launcher-android-builder/1.0"}


def http_bytes(url: str) -> bytes:
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=180) as resp:
        return resp.read()


# ---------------------------------------------------------------- NBT (servers.dat)

_TAG_BYTE, _TAG_STRING, _TAG_LIST, _TAG_COMPOUND = 1, 8, 9, 10


def _named(tag: int, name: str, payload: bytes) -> bytes:
    nb = name.encode("utf-8")
    return struct.pack(">BH", tag, len(nb)) + nb + payload


def _nbt_string(name: str, value: str) -> bytes:
    b = value.encode("utf-8")
    return _named(_TAG_STRING, name, struct.pack(">H", len(b)) + b)


def _nbt_byte(name: str, value: int) -> bytes:
    return _named(_TAG_BYTE, name, struct.pack(">b", value))


def _compound_body(entries: bytes) -> bytes:
    return entries + b"\x00"


def build_servers_dat() -> bytes:
    entry = _compound_body(
        _nbt_string("name", SERVER_NAME)
        + _nbt_string("ip", SERVER_IP)
        + _nbt_byte("acceptTextures", 0)
    )
    servers = _named(_TAG_LIST, "servers", struct.pack(">Bi", _TAG_COMPOUND, 1) + entry)
    return _named(_TAG_COMPOUND, "", _compound_body(servers))


# ---------------------------------------------------------------- staging


def fetch_client_mods() -> tuple[str, list[tuple[str, bytes]]]:
    root = json.loads(http_bytes(UPDATE_BASE + "/"))
    release = root.get("release", "")
    assets = root.get("assets", []) or []
    mods_asset = next(
        (a for a in assets if a.startswith("breakfront-dev-client-mods-") and a.endswith(".zip")),
        None,
    )
    if not mods_asset:
        raise SystemExit(f"[err] no client-mods zip in bfupdate assets: {assets!r}")

    print(f"[mods] {mods_asset} ({release})")
    blob = http_bytes(f"{UPDATE_BASE}/breakfront/files/{mods_asset}")

    jars: list[tuple[str, bytes]] = []
    skipped: list[str] = []
    with zipfile.ZipFile(io.BytesIO(blob)) as zf:
        try:
            manifest = json.loads(zf.read("manifest.json"))
        except Exception:
            manifest = {}
        env_by_path = {
            f["file"]: f.get("env")
            for f in manifest.get("files", [])
            if isinstance(f, dict) and f.get("file")
        }
        for name in zf.namelist():
            if not (name.startswith("mods/") and name.endswith(".jar")):
                continue
            base = os.path.basename(name)
            if env_by_path.get(name) == "server":
                skipped.append(base)
                continue
            jars.append((base, zf.read(name)))

    if not jars:
        raise SystemExit(f"[err] no client mods/*.jar inside {mods_asset}")
    if skipped:
        print(f"[mods] dropped {len(skipped)} server-only: {', '.join(skipped)}")
    return release, jars


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mods-dir", required=True, help="APK asset dir for client mods")
    ap.add_argument("--server-dat", required=True, help="output path for the preset servers.dat")
    args = ap.parse_args()

    release, jars = fetch_client_mods()

    mods_dir = Path(args.mods_dir)
    mods_dir.mkdir(parents=True, exist_ok=True)
    for old in mods_dir.glob("*.jar"):
        old.unlink()
    for name, data in jars:
        (mods_dir / name).write_bytes(data)
        print("     +", name)

    server_dat = Path(args.server_dat)
    server_dat.parent.mkdir(parents=True, exist_ok=True)
    server_dat.write_bytes(build_servers_dat())

    print(f"[ok] {len(jars)} client mods -> {mods_dir}  |  servers.dat -> {server_dat}  ({release})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
