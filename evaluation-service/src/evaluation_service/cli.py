from __future__ import annotations

import argparse
import json

from .config import load_settings
from .evaluator import run_dataset_sync, write_run


def main() -> None:
    parser = argparse.ArgumentParser(prog="rag-eval")
    subparsers = parser.add_subparsers(dest="command", required=True)

    run_parser = subparsers.add_parser("run", help="Run a JSONL evaluation dataset against agent-service.")
    run_parser.add_argument("--dataset", required=True, help="Path to the JSONL evaluation dataset.")
    run_parser.add_argument("--agent-base-url", help="agent-service base URL.")
    run_parser.add_argument("--output", help="Path for the JSON result.")
    run_parser.add_argument("--ragas", action="store_true", help="Enable optional RAGAS metrics.")

    args = parser.parse_args()
    settings = load_settings()

    if args.command == "run":
        run = run_dataset_sync(
            args.dataset,
            agent_base_url=(args.agent_base_url or settings.agent_base_url),
            timeout_seconds=settings.timeout_seconds,
            run_ragas=args.ragas,
        )
        if args.output:
            write_run(run, args.output)
        print(json.dumps(run.model_dump(mode="json"), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

