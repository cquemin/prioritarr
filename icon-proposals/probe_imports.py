"""Probe Sonarr's manualimport one orphan at a time and collect the
rejection reasons. Per-file calls finish in ~1s vs the whole-folder
scan that hangs."""
import json, subprocess, urllib.parse
import os

PLAN = json.load(open(r'C:/Users/cyrille/AppData/Local/Temp/cleanup/risky_plan.json', encoding='utf-8'))
ORPHANS = PLAN['qbit/series']['truly_orphan']
print(f"probing {len(ORPHANS)} orphans against Sonarr manualimport")

API_KEY = '9956d391003b41929c91b3d700684d19'
BASE = 'http://localhost:8989/sonarr/api/v3/manualimport'

def probe(name):
    """Returns list[dict] from Sonarr — empty list = couldn't even
    parse the file as a recognizable episode."""
    container_path = f"/storage/torrents/series/{name}"
    quoted = urllib.parse.quote(container_path, safe='')
    url = f"{BASE}?folder={quoted}&filterExistingFiles=false"
    cmd = ['docker', 'exec', 'sonarr', 'sh', '-c',
           f'wget -qO- --timeout=10 --header="X-Api-Key: {API_KEY}" "{url}"']
    try:
        out = subprocess.check_output(cmd, timeout=15, encoding='utf-8', errors='replace')
        return json.loads(out) if out.strip() else []
    except Exception as e:
        return [{'error': str(e)[:80]}]

# Bucket each orphan by outcome
results = {'no_match': [], 'matched_no_rejection': [], 'rejected': [], 'error': []}
reasons = {}

for i, name in enumerate(ORPHANS):
    items = probe(name)
    if not items:
        results['no_match'].append(name)
    elif items and 'error' in items[0]:
        results['error'].append((name, items[0]['error']))
    else:
        item = items[0]
        rej = item.get('rejections') or []
        if not rej:
            results['matched_no_rejection'].append((name, item.get('series', {}).get('title', '?'), item.get('episodes', [])))
        else:
            for r in rej:
                reason = r.get('reason', '?')
                reasons.setdefault(reason, []).append(name)
            series = item.get('series', {}).get('title', '?')
            results['rejected'].append((name, series, [r['reason'] for r in rej]))
    if (i + 1) % 10 == 0:
        print(f"  {i+1}/{len(ORPHANS)} done")

print(f"\n=== summary ===")
print(f"no_match (Sonarr can't parse):     {len(results['no_match'])}")
print(f"matched_no_rejection (importable!): {len(results['matched_no_rejection'])}")
print(f"rejected (matched but blocked):     {len(results['rejected'])}")
print(f"error (probe failed):               {len(results['error'])}")

print(f"\n=== top rejection reasons ===")
for reason, names in sorted(reasons.items(), key=lambda kv: -len(kv[1]))[:10]:
    print(f"  [{len(names):>3}] {reason[:120]}")
    for n in names[:2]:
        print(f"         {n[:100]}")

print(f"\n=== first 5 importable (no rejection) ===")
for n, s, eps in results['matched_no_rejection'][:5]:
    ep_str = ', '.join(f"S{e['seasonNumber']:02d}E{e['episodeNumber']:02d}" for e in eps[:3])
    print(f"  {n[:80]}  ->  {s} ({ep_str})")

print(f"\n=== first 5 no_match ===")
for n in results['no_match'][:5]:
    print(f"  {n[:100]}")

# Persist for the OrphanReaper redesign
with open(r'C:/Users/cyrille/AppData/Local/Temp/cleanup/import_probe.json', 'w', encoding='utf-8') as fh:
    json.dump({'results': {k: [list(v) if isinstance(v, tuple) else v for v in vs] for k, vs in results.items()}, 'reasons': {k: v for k, v in reasons.items()}}, fh, indent=2, default=str)
print("\nresults saved")
