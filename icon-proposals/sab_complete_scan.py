"""Scan SAB's /usenet/complete subdirs (depth-2). Each child is a
completed download job; cross-reference against the library to see
which still have a copy."""
import os, json
from pathlib import Path

LIB_INDEX_FILES = set()
LIB_INDEX_DIRS = set()
for root in [r'J:/containers_data/media/video/movies',
             r'J:/containers_data/media/video/french_movies',
             r'K:/containers_data/media/video/series',
             r'K:/containers_data/media/video/anime',
             r'K:/containers_data/media/video/french_series',
             r'K:/containers_data/media/video/fitness']:
    if not os.path.exists(root): continue
    for dirpath, dirs, files in os.walk(root):
        for f in files: LIB_INDEX_FILES.add(f)
        for d in dirs: LIB_INDEX_DIRS.add(d)

print(f"library: {len(LIB_INDEX_FILES)} files, {len(LIB_INDEX_DIRS)} dirs indexed")

def hr(b):
    for u in ['B','KB','MB','GB','TB']:
        if b < 1024: return f"{b:.1f}{u}"
        b /= 1024
    return f"{b:.1f}PB"

result = {}
for cat in ['series', 'movies']:
    base = rf'K:/containers_data/usenet/complete/{cat}'
    if not os.path.exists(base): continue
    entries = os.listdir(base)
    in_lib = []
    truly_orphan = []
    for name in entries:
        full = os.path.join(base, name)
        if not os.path.isdir(full): continue
        # Walk the job dir, find a video/media file, check if same name in lib
        found = False
        size = 0
        for r, _, files in os.walk(full):
            for f in files:
                try:
                    st = os.stat(os.path.join(r, f))
                    size += st.st_size
                except OSError: pass
                if not found and f in LIB_INDEX_FILES:
                    found = True
        if found: in_lib.append((name, size))
        else: truly_orphan.append((name, size))
    in_lib_b = sum(s for _, s in in_lib)
    truly_b = sum(s for _, s in truly_orphan)
    print(f"\nsab/complete/{cat} ({len(entries)} subdirs)")
    print(f"  has lib copy by file name (orphan-with-twin):  {len(in_lib)}  -> {hr(in_lib_b)}")
    print(f"  no lib copy (truly orphan, needs judgement):   {len(truly_orphan)}  -> {hr(truly_b)}")
    for n, s in in_lib[:3]: print(f"    has-copy ex: {hr(s):>8}  {n[:80]}")
    for n, s in truly_orphan[:3]: print(f"    truly-orphan ex: {hr(s):>8}  {n[:80]}")
    result[cat] = {
        'has_lib_copy': [n for n, _ in in_lib],
        'truly_orphan': [n for n, _ in truly_orphan],
    }
with open(r'C:/Users/cyrille/AppData/Local/Temp/cleanup/sab_complete_plan.json', 'w', encoding='utf-8') as fh:
    json.dump(result, fh, indent=2)
print("\nplan written")
