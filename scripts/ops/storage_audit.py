#!/usr/bin/env python3
"""Read-only storage inventory. Never follows links or suppresses unreadable paths.

Use --root for the checkout or its workspace parent. Keep the JSON outside the
scanned tree. Logical bytes describe content; allocated bytes describe filesystem
allocation (NTFS file allocation on Windows, st_blocks on Unix). Directory/NTFS
metadata and external Docker storage are outside this measurement.
"""
import argparse
import ctypes
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import stat


def allocation(path, info):
    if os.name != 'nt':
        return info.st_blocks * 512 if hasattr(info, 'st_blocks') else None
    from ctypes import wintypes
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                  ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    kernel.CreateFileW.restype = wintypes.HANDLE
    kernel.GetFileInformationByHandleEx.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]

    class Standard(ctypes.Structure):
        _fields_ = [('allocation', ctypes.c_longlong), ('length', ctypes.c_longlong),
                    ('links', wintypes.DWORD), ('delete_pending', ctypes.c_ubyte), ('directory', ctypes.c_ubyte)]

    handle = kernel.CreateFileW(str(path), 0x80, 7, None, 3, 0x02200000, None)
    if handle == ctypes.c_void_p(-1).value:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        result = Standard()
        if not kernel.GetFileInformationByHandleEx(handle, 1, ctypes.byref(result), ctypes.sizeof(result)):
            raise ctypes.WinError(ctypes.get_last_error())
        return result.allocation
    finally:
        kernel.CloseHandle(handle)


def linked(info):
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, 'st_file_attributes', 0) & 0x400)


def inventory(root, excluded=()):
    root = Path(root).absolute()
    if linked(root.lstat()):
        raise ValueError('Inventory root must not be a link or junction')
    excluded = {Path(p).absolute() for p in excluded}
    files, links, errors, directories, identities = [], [], [], {}, set()
    unique_allocated = 0

    def visit(folder):
        nonlocal unique_allocated
        logical = physical = count = 0
        try:
            with os.scandir(folder) as scan:
                entries = sorted(scan, key=lambda entry: entry.name)
        except OSError as error:
            errors.append({'path': folder.relative_to(root).as_posix(), 'error': str(error)})
            return logical, physical, count
        for entry in entries:
            path = Path(entry.path)
            if path in excluded:
                continue
            relative = path.relative_to(root).as_posix()
            try:
                # DirEntry's cached find-data on Windows has st_ino == 0.
                info = path.lstat()
                if linked(info):
                    links.append(relative)
                elif stat.S_ISDIR(info.st_mode):
                    size, used, number = visit(path)
                    logical += size; physical += used; count += number
                elif stat.S_ISREG(info.st_mode):
                    used = allocation(path, info)
                    files.append({'path': relative, 'logical_bytes': info.st_size, 'allocated_bytes': used})
                    logical += info.st_size; physical += used or 0; count += 1
                    identity = (info.st_dev, info.st_ino) if info.st_ino else relative
                    if identity not in identities:
                        identities.add(identity); unique_allocated += used or 0
                    if used is None:
                        errors.append({'path': relative, 'error': 'Filesystem allocation unavailable'})
            except OSError as error:
                errors.append({'path': relative, 'error': str(error)})
        directories[folder.relative_to(root).as_posix()] = {
            'logical_bytes': logical, 'allocated_bytes': physical, 'files': count}
        return logical, physical, count

    logical, physical, count = visit(root)
    return {'root': str(root), 'utc': datetime.now(timezone.utc).isoformat(),
            'complete': not errors, 'excluded': sorted(str(p) for p in excluded),
            'totals': {'logical_bytes_per_path': logical, 'allocated_bytes_per_path': physical,
                       'unique_allocated_bytes': unique_allocated, 'files': count},
            'directories': directories, 'files': files, 'links_not_followed': links, 'errors': errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--exclude', type=Path, action='append', default=[])
    args = parser.parse_args()
    result = inventory(args.root, [args.out, *args.exclude])
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: result[key] for key in ('complete', 'totals', 'errors')}, indent=2))
    return 0 if result['complete'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
