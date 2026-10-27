#!/usr/bin/env python3
"""Copy your own photos to the launcher's photo frame on the TV.

Give it what Google Photos downloads (an album's "Download all" zip, or a Google Takeout zip), folders, or
single images. Photos are turned upright, shrunk to frame size and sent as one bundle; videos and other files
are skipped. Replaces the photos already on the TV, so the album you give it is what the frame shows.

Usage:
    tools/venv/bin/pip install -r tools/requirements-photos.txt     # once
    TV_HOST=192.168.1.50 tools/venv/bin/python -I tools/push_photos.py ~/Downloads/Album.zip [more.zip ...]
"""
import argparse
import io
import os
import random
import subprocess
import sys
import tarfile
import tempfile
import zipfile

from PIL import Image, ImageOps

try:
    from pillow_heif import register_heif_opener  # iPhone photos come down as HEIC
    register_heif_opener()
except ImportError:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
REMOTE_DIR = "/sdcard/Android/data/com.gathin.tvhome/files/photos"   # PhotoFrame.folder()
REMOTE_TAR = "/data/local/tmp/tvhome-photos.tar"
EXTS = (".jpg", ".jpeg", ".png", ".heic", ".heif", ".webp")
SIZE = 960   # longest side in px; the frame is 600 x 380 px on a 1080p screen


def sources(paths):
    """Yields (name, bytes) for every image in the given zips, folders and files."""
    for path in paths:
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as z:
                for info in z.infolist():
                    if not info.is_dir() and info.filename.lower().endswith(EXTS):
                        yield info.filename, z.read(info)
        elif os.path.isdir(path):
            for dirpath, _, files in os.walk(path):
                for f in sorted(files):
                    if f.lower().endswith(EXTS):
                        with open(os.path.join(dirpath, f), "rb") as fh:
                            yield f, fh.read()
        elif path.lower().endswith(EXTS):
            with open(path, "rb") as fh:
                yield os.path.basename(path), fh.read()
        else:
            print(f"skipped {path}: not a zip, folder or image")


def shrink(data):
    img = ImageOps.exif_transpose(Image.open(io.BytesIO(data)))
    img = img.convert("RGB")
    img.thumbnail((SIZE, SIZE), Image.LANCZOS)
    out = io.BytesIO()
    img.save(out, "JPEG", quality=85, optimize=True)
    return out.getvalue()


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("paths", nargs="+", help="Google Photos zip(s), folders or images")
    ap.add_argument("--max", type=int, default=300, help="most photos to send, picked at random (default 300)")
    args = ap.parse_args()

    photos = []
    for name, data in sources(args.paths):
        try:
            photos.append(shrink(data))
        except Exception as e:  # unreadable or unsupported image: skip it, keep going
            print(f"skipped {name}: {e}")
    if not photos:
        sys.exit("No photos found. Pass the .zip that Google Photos downloaded, or a folder of photos.")
    if len(photos) > args.max:
        print(f"{len(photos)} photos found, sending a random {args.max} (change with --max)")
        photos = random.sample(photos, args.max)

    with tempfile.TemporaryDirectory() as tmp:
        tar_path = os.path.join(tmp, "photos.tar")
        with tarfile.open(tar_path, "w") as tar:
            for i, data in enumerate(photos, 1):
                info = tarfile.TarInfo(f"photo_{i:04d}.jpg")
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        print(f"sending {len(photos)} photos ({os.path.getsize(tar_path) // 1024} KB)")
        subprocess.run([sys.executable, "-I", os.path.join(HERE, "b64push.py"), tar_path, REMOTE_TAR], check=True)

    cmds = [
        f"mkdir -p {REMOTE_DIR}",
        f"rm -f {REMOTE_DIR}/*.jpg",
        f"tar -xf {REMOTE_TAR} -C {REMOTE_DIR}",
        f"rm -f {REMOTE_TAR}",
        f"ls {REMOTE_DIR} | wc -l",
        "input keyevent KEYCODE_HOME",   # the frame rescans its folder when the home screen comes back
    ]
    subprocess.run([sys.executable, "-I", os.path.join(HERE, "tv.py"), "--batch"],
                   input="\n".join(cmds) + "\n", text=True, check=True)
    print("Done. The photo frame on the home screen now shows these photos.")


if __name__ == "__main__":
    main()
