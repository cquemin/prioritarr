"""Delete truly-orphaned entries from SAB folders.

Truly orphaned = folder/file in /usenet/incomplete or /usenet/complete
that doesn't match any SAB queue slot filename or history name.
These are failed/aborted jobs that SAB never cleaned up.
"""
import json, os, shutil
from pathlib import Path

BASE = r'C:/Users/cyrille/AppData/Local/Temp/cleanup'
sab_queue = json.load(open(f'{BASE}/sab-queue.json', encoding='utf-8'))['queue']['slots']
sab_history = json.load(open(f'{BASE}/sab-history.json', encoding='utf-8'))['history']['slots']

active = set()
for s in sab_queue:
    if s.get('filename'): active.add(s['filename'])
    if s.get('nzo_id'): active.add(s['nzo_id'])
hist = set()
for s in sab_history:
    if s.get('name'): hist.add(s['name'])
    if s.get('storage'): hist.add(Path(s['storage']).name)
tracked = active | hist

PATHS = [
    r'K:/containers_data/usenet/incomplete',
    r'K:/containers_data/usenet/complete/series',
    r'K:/containers_data/usenet/complete/movies',
]

def hr(b):
    for u in ['B','KB','MB','GB','TB']:
        if b < 1024: return f"{b:.1f}{u}"
        b /= 1024
    return f"{b:.1f}PB"

total_deleted = 0
total_bytes = 0
for p in PATHS:
    if not os.path.exists(p):
        print(f"{p}: path missing"); continue
    entries = os.listdir(p)
    orphans = [e for e in entries if e not in tracked]
    print(f"\n{p}")
    print(f"  total: {len(entries)}  tracked: {len(entries) - len(orphans)}  orphans: {len(orphans)}")
    if not orphans: continue
    # Size the batch
    batch_bytes = 0
    for name in orphans:
        full = os.path.join(p, name)
        size = 0
        try:
            if os.path.isdir(full):
                for r, _, files in os.walk(full):
                    for f in files:
                        try: size += os.path.getsize(os.path.join(r, f))
                        except OSError: pass
            else:
                size = os.path.getsize(full)
        except OSError: pass
        batch_bytes += size
    print(f"  to free: {hr(batch_bytes)}")

    # Delete
    deleted = 0
    errors = 0
    freed = 0
    for name in orphans:
        full = os.path.join(p, name)
        try:
            if os.path.isdir(full):
                for r, _, files in os.walk(full):
                    for f in files:
                        try: freed += os.path.getsize(os.path.join(r, f))
                        except OSError: pass
                shutil.rmtree(full)
            else:
                freed += os.path.getsize(full)
                os.remove(full)
            deleted += 1
        except OSError as e:
            errors += 1
            if errors <= 3: print(f"  fail: {name[:60]}: {e}")
    print(f"  deleted: {deleted}, freed: {hr(freed)}, errors: {errors}")
    total_deleted += deleted
    total_bytes += freed

print(f"\n=== TOTAL: deleted {total_deleted} entries, freed {hr(total_bytes)} ===")
