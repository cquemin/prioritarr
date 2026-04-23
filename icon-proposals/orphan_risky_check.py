"""For each "risky" orphan (no hardlink twin), look for an
independent same-filename copy in the library. Movies are on a
separate drive (J:) so qBit→Radarr is COPY not hardlink — same file
exists by name, not by inode. Series should be all hardlinked
already; risky there means likely never-imported or aborted.
"""
import json, os
from pathlib import Path

PLAN = r'C:/Users/cyrille/AppData/Local/Temp/cleanup/plan.json'
plan = json.load(open(PLAN, encoding='utf-8'))

# Library roots to scan for matching filenames
LIB_ROOTS = {
    'movies':       [r'J:/containers_data/media/video/movies',
                     r'J:/containers_data/media/video/french_movies'],
    'series':       [r'K:/containers_data/media/video/series',
                     r'K:/containers_data/media/video/anime',
                     r'K:/containers_data/media/video/french_series',
                     r'K:/containers_data/media/video/fitness'],
}

# Build filename → path index for each library group (basename only)
print("indexing library...")
indices = {}
for group, roots in LIB_ROOTS.items():
    idx = {}
    for root in roots:
        if not os.path.exists(root): continue
        for dirpath, _, files in os.walk(root):
            for f in files:
                idx.setdefault(f, []).append(os.path.join(dirpath, f))
    indices[group] = idx
    print(f"  {group}: {len(idx)} unique filenames across {sum(len(v) for v in idx.values())} files")

def lib_for(label):
    if 'movies' in label: return indices['movies']
    if 'series' in label: return indices['series']
    return indices.get('series', {})

def find_match(name, idx):
    """Return matching library paths for a torrent entry (file or folder)."""
    full_path = name  # the orphan name as listed
    # If folder: look for any of its file children in lib by name
    # If file: direct lookup
    return idx.get(name, [])

print()
update = {}
for label, info in plan.items():
    if 'qbit' not in label or not info.get('risky'):
        continue
    idx = lib_for(label)
    has_lib_copy = []  # name → list of library paths
    no_lib_copy = []
    for name in info['risky']:
        full = os.path.join(info['path'], name)
        # For folders, look at child files
        candidates = []
        if os.path.isdir(full):
            for root, _, files in os.walk(full):
                for f in files:
                    matches = idx.get(f, [])
                    if matches:
                        candidates.extend(matches)
                        break
                if candidates: break
        else:
            candidates = idx.get(name, [])
        if candidates:
            has_lib_copy.append((name, candidates[0]))
        else:
            no_lib_copy.append(name)
    print(f"\n{label} risky breakdown:")
    print(f"  has lib copy by name (safe-equivalent): {len(has_lib_copy)}")
    print(f"  no lib copy at all (TRULY orphan):       {len(no_lib_copy)}")
    if has_lib_copy[:3]:
        for n, p in has_lib_copy[:3]:
            print(f"    has-copy ex: {n[:50]}  ↔  {p[:60]}")
    if no_lib_copy[:3]:
        for n in no_lib_copy[:3]:
            print(f"    no-copy ex: {n[:80]}")
    update[label] = {
        'has_lib_copy': [n for n, _ in has_lib_copy],
        'truly_orphan': no_lib_copy,
    }

# Persist enriched plan for the next-step deletion
with open(r'C:/Users/cyrille/AppData/Local/Temp/cleanup/risky_plan.json', 'w', encoding='utf-8') as fh:
    json.dump(update, fh, indent=2)
print("\nrisky plan written")
