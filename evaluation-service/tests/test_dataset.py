from __future__ import annotations

from pathlib import Path

from evaluation_service.dataset import load_jsonl_dataset


def test_load_jsonl_dataset(tmp_path: Path) -> None:
    dataset = tmp_path / "dataset.jsonl"
    dataset.write_text(
        '{"id":"case-1","question":"Q","reference":"A","expectedChunkIds":["c1"]}\n',
        encoding="utf-8",
    )

    cases = load_jsonl_dataset(dataset)

    assert len(cases) == 1
    assert cases[0].id == "case-1"
    assert cases[0].expectedChunkIds == ["c1"]

