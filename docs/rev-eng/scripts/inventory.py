import pathlib, re, collections, json
root = pathlib.Path(__file__).parent / 'decompiled/sources'
hits = collections.defaultdict(set)
markers = []
files = list(root.rglob('*.java'))
for p in files:
    s = p.read_text(encoding='utf-8')
    for host in re.findall(r'https?://([A-Za-z0-9.-]+)', s):
        hits[host].add(p.relative_to(root).as_posix())
    if re.search(r'yt-dlp|youtube-dl|youtubedl|chaquopy', s, re.I):
        markers.append(p.relative_to(root).as_posix())
out = {'java_files': len(files), 'engine_markers': markers, 'literal_domains': {k:sorted(v) for k,v in sorted(hits.items())}}
(root.parents[1] / 'domains.json').write_text(json.dumps(out, indent=2), encoding='utf-8')
print('java_files', len(files), 'engine_markers', markers)
print('\n'.join(k for k in sorted(hits) if not any(x in k for x in ['google','android','w3.org','apache','github','kotlin','xml','facebook','mozilla','iana','ietf'])))
