"""Act on the per-file probe results:
  - importable (no rejection) -> POST manualImport command, then delete
  - "Not an upgrade" -> delete (better copy is already in library)
  - everything else -> keep, log
"""
import json, subprocess, urllib.parse, os, time

API_KEY = '9956d391003b41929c91b3d700684d19'
BASE_URL = 'http://localhost:8989/sonarr/api/v3'
ORPHAN_DIR = r'K:/containers_data/torrents/series'

PLAN = json.load(open(r'C:/Users/cyrille/AppData/Local/Temp/cleanup/risky_plan.json', encoding='utf-8'))
ORPHANS = PLAN['qbit/series']['truly_orphan']

def sonarr_get(path):
    cmd = ['docker', 'exec', 'sonarr', 'sh', '-c',
           f'wget -qO- --header="X-Api-Key: {API_KEY}" "{BASE_URL}{path}"']
    out = subprocess.check_output(cmd, timeout=20, encoding='utf-8', errors='replace')
    return json.loads(out) if out.strip() else None

def sonarr_post(path, body):
    payload = json.dumps(body).replace('"', '\\"')
    cmd = ['docker', 'exec', 'sonarr', 'sh', '-c',
           f'wget -qO- --header="X-Api-Key: {API_KEY}" --header="Content-Type: application/json" '
           f'--post-data="{payload}" "{BASE_URL}{path}"']
    out = subprocess.check_output(cmd, timeout=30, encoding='utf-8', errors='replace')
    return json.loads(out) if out.strip() else None

def probe(name):
    container_path = f"/storage/torrents/series/{name}"
    quoted = urllib.parse.quote(container_path, safe='')
    return sonarr_get(f"/manualimport?folder={quoted}&filterExistingFiles=false")

imported = []
deleted_not_upgrade = []
kept = []
errors = []

for i, name in enumerate(ORPHANS):
    items = probe(name) or []
    if not items:
        kept.append((name, 'no_match'))
        continue
    item = items[0]
    rej = item.get('rejections') or []
    if not rej:
        # Importable — trigger ManualImport command
        try:
            file_payload = {
                'path': item['path'],
                'folderName': item.get('folderName', ''),
                'seriesId': item['series']['id'],
                'episodeIds': [e['id'] for e in item['episodes']],
                'quality': item['quality'],
                'languages': item['languages'],
                'releaseGroup': item.get('releaseGroup', ''),
                # No tracked download id — this is a manual orphan import
            }
            resp = sonarr_post('/command', {
                'name': 'ManualImport',
                'files': [file_payload],
                'importMode': 'auto',  # let Sonarr decide hardlink vs move
            })
            ep_str = ', '.join(f"S{e['seasonNumber']:02d}E{e['episodeNumber']:02d}" for e in item['episodes'][:3])
            imported.append((name, item['series']['title'], ep_str, resp.get('id') if resp else None))
            # Don't delete the orphan file here — Sonarr's import will hardlink/move it.
            # If hardlink: file goes into library, original stays (link count >1) -> next reaper pass deletes.
            # If move: original disappears immediately.
        except Exception as e:
            errors.append((name, str(e)[:100]))
    else:
        # Inspect rejections
        not_upgrade = any('Not an upgrade' in r.get('reason', '') or 'Not a quality revision upgrade' in r.get('reason', '')
                          for r in rej)
        if not_upgrade:
            full = os.path.join(ORPHAN_DIR, name)
            try:
                if os.path.isdir(full):
                    import shutil; shutil.rmtree(full)
                else:
                    os.remove(full)
                deleted_not_upgrade.append((name, '; '.join(r['reason'] for r in rej)[:80]))
            except OSError as e:
                errors.append((name, f'delete failed: {e}'))
        else:
            kept.append((name, '; '.join(r['reason'] for r in rej)[:80]))
    if (i + 1) % 10 == 0:
        print(f"  {i+1}/{len(ORPHANS)} processed")

print(f"\n=== summary ===")
print(f"imported (Sonarr ManualImport queued): {len(imported)}")
print(f"deleted (not an upgrade):              {len(deleted_not_upgrade)}")
print(f"kept (manual review needed):           {len(kept)}")
print(f"errors:                                {len(errors)}")

print(f"\n=== imported ===")
for n, s, eps, cmd_id in imported[:10]:
    print(f"  cmd#{cmd_id}: {s} {eps}  <- {n[:60]}")

print(f"\n=== deleted (not an upgrade) ===")
for n, r in deleted_not_upgrade:
    print(f"  {n[:80]}  ({r[:60]})")

print(f"\n=== kept (review) ===")
for n, r in kept:
    print(f"  {n[:80]}  ({r[:60]})")

if errors:
    print(f"\n=== errors ===")
    for n, e in errors:
        print(f"  {n[:60]}: {e}")
