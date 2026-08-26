from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path

from .authoring_client import AuthoringClient
from .authoring_evaluator import evaluate_authoring_dataset, write_authoring_run
from .config import load_settings


def main() -> None:
    parser = argparse.ArgumentParser(prog="authoring-eval")
    subparsers = parser.add_subparsers(dest="command", required=True)
    dataset = subparsers.add_parser("dataset", help="Evaluate captured Authoring Coach review pairs.")
    dataset.add_argument("--dataset", required=True)
    dataset.add_argument("--output")
    dataset.add_argument("--ragas", action="store_true")
    live = subparsers.add_parser("live", help="Create and await one asynchronous review run.")
    live.add_argument("--revision-id", required=True)
    live.add_argument("--authoring-base-url")
    live.add_argument("--access-token-env", default="AUTHORING_EVAL_ACCESS_TOKEN")
    live.add_argument("--output")
    args = parser.parse_args()

    if args.command == "dataset":
        run = evaluate_authoring_dataset(args.dataset, run_ragas=args.ragas)
        if args.output:
            write_authoring_run(run, args.output)
    else:
        settings = load_settings()
        token = os.getenv(args.access_token_env, "")
        if not token:
            parser.error(f"{args.access_token_env} must contain a student access token")
        run = asyncio.run(AuthoringClient(
            args.authoring_base_url or settings.authoring_base_url,
            token,
            settings.timeout_seconds,
        ).run_revision(args.revision_id))
        if args.output:
            Path(args.output).write_text(run.model_dump_json(indent=2), encoding="utf-8")
    print(json.dumps(run.model_dump(mode="json"), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
