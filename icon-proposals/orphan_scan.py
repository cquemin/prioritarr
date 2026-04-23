"""One-shot orphan analysis. Cross-references qBit + SAB job state
against download-folder contents, classifies each on-disk entry as
matched / safe-to-delete (hardlink twin in library) / risky (no twin).
"""
import json, os
from pathlib import Path

BASE = r'C:/Users/cyrille/AppData/Local/Temp/cleanup'

qbit = json.load(open(encoding='utf-8', file=f'{BASE}/qbit-all.json'))
sab_queue = json.load(open(encoding='utf-8', file=f'{BASE}/sab-queue.json'))['queue']['slots']
sab_history = json.load(open(encoding='utf-8', file=f'{BASE}/sab-history.json'))['history']['slots']

qbit_names = {t['name'] for t in qbit}
qbit_content = {Path(t['content_path']).name for t in qbit if t.get('content_path')}
tracked_qbit = qbit_names | qbit_content

sab_active = set()
for s in sab_queue:
    if s.get('filename'): sab_active.add(s['filename'])
    if s.get('nzo_id'): sab_active.add(s['nzo_id'])
sab_history_names = set()
for s in sab_history:
    if s.get('name'): sab_history_names.add(s['name'])
    if s.get('storage'): sab_history_names.add(Path(s['storage']).name)

PATHS = {
    'qbit/series':  (r'K:/containers_data/torrents/series',     tracked_qbit),
    'qbit/movies':  (r'K:/containers_data/torrents/movies',     tracked_qbit),
    'sab/incomp':   (r'K:/containers_data/usenet/incomplete',   sab_active | sab_history_names),
    'sab/complete': (r'K:/containers_data/usenet/complete',     sab_active | sab_history_names),
}

def hr(b):
    for u in ['B','KB','MB','GB','TB']:
        if b < 1024: return f"{b:.1f}{u}"
        b /= 1024
    return f"{b:.1f}PB"

# Output a JSON delete plan alongside the human summary so step 2 can
# act on it without re-walking the disk.
plan = {}

for label, (path, tracked) in PATHS.items():
    if not os.path.exists(path):
        print(f"\n{label}: PATH MISSING ({path})"); continue
    entries = os.listdir(path)
    safe, risky, matched = [], [], []
    safe_b = risky_b = 0
    for name in entries:
        full = os.path.join(path, name)
        if name in tracked:
            matched.append(name); continue
        size, max_links = 0, 1
        try:
            if os.path.isdir(full):
                for root, _, files in os.walk(full):
                    for f in files:
                        try:
                            st = os.stat(os.path.join(root, f))
                            size += st.st_size
                            if st.st_nlink > max_links: max_links = st.st_nlink
                        except OSError: pass
            else:
                st = os.stat(full); size = st.st_size; max_links = st.st_nlink
        except OSError: pass
        if max_links > 1: safe.append((name, size)); safe_b += size
        else: risky.append((name, size)); risky_b += size
    print(f"\n{label} ({path})")
    print(f"  total entries: {len(entries)}  matched: {len(matched)}")
    print(f"  SAFE   (linked elsewhere): {len(safe)}  -> {hr(safe_b)}")
    print(f"  RISKY  (no link elsewhere): {len(risky)}  -> {hr(risky_b)}")
    for n, s in risky[:3]:
        print(f"    risky example: {hr(s):>8}  {n[:80]}")
    plan[label] = {
        'path': path,
        'safe': [n for n, _ in safe],
        'risky': [n for n, _ in risky],
        'safe_bytes': safe_b,
        'risky_bytes': risky_b,
    }

with open(f'{BASE}/plan.json', 'w', encoding='utf-8') as fh:
    json.dump(plan, fh, indent=2)
print(f"\nplan written to {BASE}/plan.json")
