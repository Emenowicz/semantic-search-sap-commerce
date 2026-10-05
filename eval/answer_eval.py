"""Eval of the RAG answer endpoint (/products/semantic-search/answer) on the local server.

For each query in queries.tsv plus three products the catalog doesn't have: did the answer survive validation,
did it cite an expected product, and did it refuse (cite nothing) when nothing fits.

    python3 answer_eval.py <label, e.g. the model name> [seconds between queries]

The endpoint embeds each query with Voyage; on the free tier (3 requests a minute) keep the 21s pause for the
first run. Query embeddings are cached in hybris, so a rerun against another answer model can use 0.
"""
import csv
import json
import ssl
import statistics
import sys
import time
import urllib.parse
import urllib.request

ENDPOINT = "https://localhost:9002/occ/v2/semanticsearch/products/semantic-search/answer"
NOT_IN_CATALOG = [
    ("n01", "en", "absent", "cordless lawn mower", ""),
    ("n02", "de", "absent", "Kaffeemaschine mit Mahlwerk", ""),
    ("n03", "fr", "absent", "aspirateur robot", ""),
]


def ask(query, lang):
    url = ENDPOINT + "?" + urllib.parse.urlencode({"query": query, "lang": lang})
    context = ssl._create_unverified_context()  # local self-signed certificate
    started = time.time()
    with urllib.request.urlopen(url, context=context, timeout=180) as response:
        body = json.load(response)
    return body, time.time() - started


def main():
    label = sys.argv[1]
    pause = float(sys.argv[2]) if len(sys.argv) > 2 else 21
    with open("queries.tsv", encoding="utf-8") as f:
        queries = [(r["id"], r["lang"], r["kind"], r["query"], r["expected"]) for r in csv.DictReader(f, delimiter="\t")]
    queries += NOT_IN_CATALOG

    rows = []
    for i, (qid, lang, kind, query, expected) in enumerate(queries):
        if i:
            time.sleep(pause)
        body, seconds = ask(query, lang)
        status, reason = body.get("status"), body.get("reason") or ""
        expected_codes = set(filter(None, expected.split("|")))
        cited = body.get("citedCodes") or []
        answered = status == "ANSWERED"
        if expected_codes:
            correct = answered and bool(expected_codes & set(cited))
        else:
            correct = answered and not cited  # nothing fits: the right answer cites nothing
        rows.append({
            "label": label, "query": qid, "lang": lang, "kind": kind, "status": status,
            "correct": int(correct), "cited": "|".join(cited), "expected": expected,
            "seconds": f"{seconds:.1f}", "reason": reason,
            "answer": (body.get("answer") or "").replace("\n", " "),
        })
        print(f"{qid} {status:8} correct={int(correct)} {seconds:5.1f}s cited={cited} {reason}")

    with open(f"results/answer-results-{label.replace(':', '-')}.tsv", "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=rows[0].keys(), delimiter="\t")
        writer.writeheader()
        writer.writerows(rows)

    def share(selected):
        return sum(r["correct"] for r in selected) / len(selected) if selected else float("nan")

    # SKIPPED (codes, short queries) get the list by design, so the answer quality is measured without them
    skipped = [r for r in rows if r["status"] == "SKIPPED"]
    in_catalog = [r for r in rows if r["kind"] != "absent" and r["status"] != "SKIPPED"]
    absent = [r for r in rows if r["kind"] == "absent"]
    print(f"\n{label}: answered {sum(r['status'] == 'ANSWERED' for r in rows)}/{len(rows)}, skipped {len(skipped)}, "
          f"cites an expected product {share(in_catalog):.2f} ({len(in_catalog)} queries), "
          f"refuses absent products {share(absent):.2f} ({len(absent)}), "
          f"median {statistics.median(float(r['seconds']) for r in rows):.1f}s")


if __name__ == "__main__":
    main()
