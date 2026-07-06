from __future__ import annotations

import pytest

from evaluation_service.ragas_runner import RagasUnavailableError, _build_evaluator_llm


class FakeChatOpenAI:
    def __init__(self, **kwargs):
        self.kwargs = kwargs


def test_build_evaluator_llm_reuses_ark_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("RAGAS_EVALUATOR_API_KEY", raising=False)
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.delenv("RAGAS_EVALUATOR_BASE_URL", raising=False)
    monkeypatch.delenv("OPENAI_API_BASE", raising=False)
    monkeypatch.delenv("OPENAI_BASE_URL", raising=False)
    monkeypatch.delenv("ARK_BASE_URL", raising=False)
    monkeypatch.delenv("RAGAS_EVALUATOR_MODEL", raising=False)
    monkeypatch.delenv("ARK_EVALUATOR_MODEL", raising=False)
    monkeypatch.delenv("ARK_MODEL", raising=False)
    monkeypatch.setenv("ARK_API_KEY", "ark-key")

    llm = _build_evaluator_llm(FakeChatOpenAI)

    assert llm.kwargs["api_key"] == "ark-key"
    assert llm.kwargs["base_url"] == "https://ark.cn-beijing.volces.com/api/coding/v3"
    assert llm.kwargs["model"] == "ark-code-latest"
    assert llm.kwargs["temperature"] == 0


def test_build_evaluator_llm_requires_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("RAGAS_EVALUATOR_API_KEY", raising=False)
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.delenv("ARK_API_KEY", raising=False)

    with pytest.raises(RagasUnavailableError):
        _build_evaluator_llm(FakeChatOpenAI)
