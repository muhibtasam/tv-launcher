#!/usr/bin/env python3
"""Netflix's Top 10 for Home: the latest week of Netflix's public lists, as rows the app reads (NetflixLogic.top10).

Writes a small TSV (country_name, country_iso2, week, category, weekly_rank, show_title) with each country's latest
week and the global lists' latest week (country "Global", iso2 "ZZ"), top 5 films and top 5 shows each.
Usage: netflix_top10.py OUT.tsv   (run weekly by .github/workflows/pages.yml; no key needed)
"""
import csv
import http.client
import io
import sys
import urllib.request

BASE = "https://www.netflix.com/tudum/top10/data/"
EACH = 5


def fetch(name):
    """The whole file: Netflix's server often cuts a long download, so it is read on from where it stopped (Range)."""
    data = b""
    for attempt in range(40):
        req = urllib.request.Request(BASE + name, headers={"User-Agent": "vyro-top10", "Range": "bytes=%d-" % len(data)})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                if r.status == 200 and data:   # the server ignored the range: start again
                    data = b""
                data += r.read()
            return io.StringIO(data.decode("utf-8"))
        except http.client.IncompleteRead as e:
            data += e.partial
        except Exception:
            if attempt >= 5:
                raise
    raise IOError("gave up on " + name)


def best(rows):
    """rows: (week, kind, rank, title) -> the latest week's top EACH of each kind, best rank first."""
    if not rows:
        return []
    latest = max(r[0] for r in rows)
    out, seen = [], set()
    for kind in ("Films", "TV"):
        picked = sorted((r for r in rows if r[0] == latest and r[1] == kind), key=lambda r: r[2])
        for r in picked:
            if len([o for o in out if o[1] == kind]) >= EACH or (kind, r[3].lower()) in seen:
                continue
            seen.add((kind, r[3].lower()))
            out.append(r)
    return out


def main(path):
    countries = {}
    for row in csv.DictReader(fetch("all-weeks-countries.tsv"), delimiter="\t", quoting=csv.QUOTE_NONE):
        try:
            kind = "Films" if row["category"].startswith("Film") else "TV" if row["category"].startswith("TV") else None
            if kind:
                countries.setdefault((row["country_name"], row["country_iso2"]), []).append(
                    (row["week"], kind, int(row["weekly_rank"]), row["show_title"].strip()))
        except (KeyError, ValueError):
            continue
    glob = []
    try:
        global_rows = list(csv.DictReader(fetch("all-weeks-global.tsv"), delimiter="\t", quoting=csv.QUOTE_NONE))
    except Exception as e:   # only the fallback for countries Netflix doesn't rank
        print("global list skipped:", e)
        global_rows = []
    for row in global_rows:
        try:
            kind = "Films" if row["category"].startswith("Film") else "TV" if row["category"].startswith("TV") else None
            if kind:
                glob.append((row["week"], kind, int(row["weekly_rank"]), row["show_title"].strip()))
        except (KeyError, ValueError):
            continue
    lines = ["country_name\tcountry_iso2\tweek\tcategory\tweekly_rank\tshow_title"]
    for (name, iso), rows in sorted(countries.items()):
        lines += ["\t".join([name, iso, r[0], r[1], str(r[2]), r[3]]) for r in best(rows)]
    lines += ["\t".join(["Global", "ZZ", r[0], r[1], str(r[2]), r[3]]) for r in best(glob)]
    if len(lines) < 50:
        sys.exit("too few rows: Netflix's lists changed?")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote", len(lines) - 1, "rows to", path)


if __name__ == "__main__":
    main(sys.argv[1])
