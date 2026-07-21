from __future__ import annotations

import json
from pathlib import Path

from .schemas import EvalCase


def load_jsonl_dataset(path: str | Path, max_cases: int | None = None) -> list[EvalCase]:
    dataset_path = Path(path)
    if not dataset_path.exists():
        raise FileNotFoundError(f"Dataset not found: {dataset_path}")

    cases: list[EvalCase] = []
    with dataset_path.open("r", encoding="utf-8-sig") as handle:
        for line_number, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            try:
                payload = json.loads(stripped)
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSON at {dataset_path}:{line_number}: {exc}") from exc
            cases.append(EvalCase.model_validate(payload))
            if max_cases is not None and len(cases) > max_cases:
                raise ValueError(f"Dataset exceeds the maximum allowed case count of {max_cases}")

    if not cases:
        raise ValueError(f"Dataset is empty: {dataset_path}")
    return cases
