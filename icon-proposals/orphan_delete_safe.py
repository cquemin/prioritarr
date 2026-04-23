"""Delete the orphan entries the scan classified as SAFE
(hardlink count > 1 → twin survives in library). Only acts on
qbit/series since that's the only path with safe candidates.
Reports per-file outcome + total freed.
"""
import json, os, sys, shutil
PLAN = r'C:/Users/cyrille/AppData/Local/Temp/cleanup/plan.json'
plan = json.load(open(PLAN, encoding='utf-8'))

removed_files = removed_dirs = 0
errors = 0
for label, info in plan.items():
    safe = info['safe']
    if not safe: continue
    path = info['path']
    print(f"\n=== {label} ({path}) — {len(safe)} entries to delete ===")
    for name in safe:
        full = os.path.join(path, name)
        try:
            if os.path.isdir(full):
                shutil.rmtree(full)
                removed_dirs += 1
            else:
                os.remove(full)
                removed_files += 1
        except OSError as e:
            errors += 1
            print(f"  FAIL: {name}: {e}")
print(f"\nremoved files: {removed_files}, dirs: {removed_dirs}, errors: {errors}")
