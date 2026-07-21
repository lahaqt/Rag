from __future__ import annotations

from pathlib import Path

import pytest

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


def test_load_jsonl_dataset_rejects_case_count_over_limit(tmp_path: Path) -> None:
    dataset = tmp_path / "dataset.jsonl"
    dataset.write_text(
        '{"id":"case-1","question":"one"}\n{"id":"case-2","question":"two"}\n',
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="maximum allowed case count"):
        load_jsonl_dataset(dataset, max_cases=1)
