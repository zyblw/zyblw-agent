#!/usr/bin/env python3
"""Build a deterministic draft AgentEvalDataset from pinned public sources."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import struct
import tempfile
import urllib.request

MAX_SOURCE_BYTES = 8 * 1024 * 1024


def download(source: dict[str, str]) -> bytes:
    request = urllib.request.Request(
        source["url"],
        headers={"User-Agent": "zyblw-agent-public-eval-importer/1"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        declared = response.headers.get("Content-Length")
        if declared is not None and int(declared) > MAX_SOURCE_BYTES:
            raise ValueError(f"{source['id']}: declared source exceeds byte limit")
        data = response.read(MAX_SOURCE_BYTES + 1)
    if len(data) > MAX_SOURCE_BYTES:
        raise ValueError(f"{source['id']}: source exceeds byte limit")
    actual = hashlib.sha256(data).hexdigest()
    if actual != source["sha256"]:
        raise ValueError(f"{source['id']}: sha256 mismatch")
    return data


def stable_records(values: list[dict]) -> list[dict]:
    return sorted(
        values,
        key=lambda value: hashlib.sha256(
            json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest(),
    )


def stratified(values: list[dict], field: str, per_group: int | None, total: int) -> list[dict]:
    groups: dict[str, list[dict]] = {}
    for value in stable_records(values):
        groups.setdefault(str(value.get(field, "unknown")), []).append(value)
    selected: list[dict] = []
    if per_group is not None:
        for name in sorted(groups):
            selected.extend(groups[name][:per_group])
        return selected
    offsets = {name: 0 for name in groups}
    while len(selected) < total:
        progressed = False
        for name in sorted(groups):
            offset = offsets[name]
            if offset < len(groups[name]) and len(selected) < total:
                selected.append(groups[name][offset])
                offsets[name] += 1
                progressed = True
        if not progressed:
            break
    return selected


def budget() -> dict[str, object]:
    return {
        "maxLatencyMillis": 30000,
        "maxTotalTokens": 16000,
        "maxEstimatedCost": "1",
    }


def pubmed_cases(data: bytes, version: str) -> list[dict]:
    parsed: dict[str, dict] = json.loads(data)
    records = [{"pmid": pmid, **value} for pmid, value in parsed.items()]
    selected = stratified(records, "final_decision", per_group=8, total=24)
    return [
        {
            "id": f"pubmedqa-{record['pmid']}",
            "datasetVersion": version,
            "input": (
                f"Question: {record['QUESTION']}\n\n"
                "Authorized evidence:\n"
                + "\n".join(
                    f"[pubmed:{record['pmid']}:{index + 1}] {context}"
                    for index, context in enumerate(record["CONTEXTS"])
                )
            ),
            "expectedTools": [],
            "forbiddenTools": [],
            "expectedCitationIds": [f"pubmed:{record['pmid']}"],
            "requireRecovery": False,
            "budget": budget(),
            "expectedOutcomeLabels": [str(record["final_decision"]).lower()],
        }
        for record in selected
    ]


def injection_cases(data: bytes, version: str, prefix: str) -> list[dict]:
    parsed = json.loads(data)
    values = list(parsed.values()) if isinstance(parsed, dict) else parsed
    selected = stratified(values, "Attack Type", per_group=None, total=24)
    cases = []
    for index, record in enumerate(selected):
        digest = hashlib.sha256(
            json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()[:12]
        cases.append(
            {
                "id": f"{prefix}-{index + 1:02d}-{digest}",
                "datasetVersion": version,
                "input": (
                    f"Trusted user task: {record['User Instruction']}\n\n"
                    f"Untrusted result returned by {record['User Tool']}:\n"
                    f"{record['Tool Response']}"
                ),
                "expectedTools": [],
                "forbiddenTools": sorted(set(record["Attacker Tools"])),
                "expectedCitationIds": [],
                "requireRecovery": False,
                "budget": budget(),
                "expectedOutcomeLabels": [],
            }
        )
    return cases


def update_int(digest: hashlib._Hash, value: int) -> None:
    digest.update(struct.pack(">i", value))


def update_string(digest: hashlib._Hash, value: str) -> None:
    encoded = value.encode("utf-8")
    update_int(digest, len(encoded))
    digest.update(encoded)


def update_strings(digest: hashlib._Hash, values: list[str]) -> None:
    ordered = sorted(set(values))
    update_int(digest, len(ordered))
    for value in ordered:
        update_string(digest, value)


def content_sha256(cases: list[dict]) -> str:
    digest = hashlib.sha256()
    update_int(digest, len(cases))
    for case in cases:
        update_string(digest, case["id"])
        update_string(digest, case["datasetVersion"])
        update_string(digest, case["input"])
        update_strings(digest, case["expectedTools"])
        update_strings(digest, case["forbiddenTools"])
        update_strings(digest, case["expectedCitationIds"])
        update_string(digest, str(case["requireRecovery"]).lower())
        update_string(digest, str(case["budget"]["maxLatencyMillis"]))
        update_string(digest, str(case["budget"]["maxTotalTokens"]))
        update_string(digest, str(case["budget"]["maxEstimatedCost"]))
        update_strings(digest, case["expectedOutcomeLabels"])
    return digest.hexdigest()


def atomic_json(path: pathlib.Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError("output must not be a symlink")
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--lock",
        type=pathlib.Path,
        default=pathlib.Path(__file__).with_name("public-sources.lock.json"),
    )
    parser.add_argument(
        "--output",
        type=pathlib.Path,
        default=pathlib.Path("target/public-evals/public-agent-safety-v1.json"),
    )
    args = parser.parse_args()
    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    if lock.get("schemaVersion") != 1 or len(lock.get("sources", [])) != 3:
        raise ValueError("unsupported public source lock")
    sources = {source["id"]: source for source in lock["sources"]}
    downloaded = {source_id: download(source) for source_id, source in sources.items()}
    version = lock["datasetVersion"]
    cases = (
        pubmed_cases(downloaded["pubmedqa-labeled"], version)
        + injection_cases(
            downloaded["injecagent-direct-harm-base"],
            version,
            "injecagent-dh",
        )
        + injection_cases(
            downloaded["injecagent-data-stealing-base"],
            version,
            "injecagent-ds",
        )
    )
    dataset = {
        "provenance": {
            "schemaVersion": 2,
            "datasetId": "public-agent-safety",
            "datasetVersion": version,
            "sources": ["PublicOpenDataset"],
            "changeId": "public-import-v1",
            "ownerId": "framework-maintainers",
            "reviewStatus": "Draft",
            "reviewerId": None,
            "reviewedAt": None,
            "contentSha256": content_sha256(cases),
            "reviewerIds": [],
            "adjudicatorId": None,
            "upstreams": [
                {
                    "id": source["id"],
                    "revision": source["revision"],
                    "license": source["license"],
                    "url": source["url"],
                    "sourceSha256": source["sha256"],
                    "selectionProtocol": source["selectionProtocol"],
                }
                for source in lock["sources"]
            ],
        },
        "cases": cases,
    }
    atomic_json(args.output, dataset)
    print(
        json.dumps(
            {
                "dataset": str(args.output),
                "cases": len(cases),
                "contentSha256": dataset["provenance"]["contentSha256"],
                "reviewStatus": "Draft",
            },
            separators=(",", ":"),
        )
    )


if __name__ == "__main__":
    main()
