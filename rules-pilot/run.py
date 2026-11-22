"""Run the two bounded rules properties from the repository root under mise exec."""
import argparse
import base64
import json
import secrets
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("jazzer", type=Path, help="Jazzer 0.30.0 standalone launcher")
parser.add_argument("results", type=Path, help="new directory for both working corpora and logs")
parser.add_argument("--seconds", type=int, choices=range(1, 301), default=15, metavar="1..300")
parser.add_argument("--seed", type=int, help="repeat a recorded exploration seed")
args = parser.parse_args()
seed = args.seed if args.seed is not None else secrets.randbelow(2**31 - 1) + 1
if not 1 <= seed < 2**31:
    parser.error("seed must be between 1 and 2147483647")
root = Path(__file__).resolve().parents[1]
launcher = args.jazzer.resolve(strict=True)
results = args.results.resolve()
results.mkdir(parents=True, exist_ok=False)

revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
metadata = {"revision": revision, "seed": seed, "seconds_per_target": args.seconds}
(results / "run.json").write_text(json.dumps(metadata, indent=2) + "\n")
print(json.dumps(metadata), flush=True)

with (results / "build.log").open("w") as log:
    subprocess.run(["sbt", "rulesPilot/writePilotClasspath"], cwd=root, stdout=log,
                   stderr=subprocess.STDOUT, timeout=600, check=True)
classpath = (root / "rules-pilot/target/classpath.txt").read_text()

for target, limit in [("DfenRoundTrip", 256), ("GeneratedMove", 9)]:
    corpus = results / target
    corpus.mkdir()
    encoded = (root / "rules-pilot/corpus" / (target + ".b64")).read_text().splitlines()
    for index, line in enumerate(encoded):
        (corpus / str(index)).write_bytes(base64.b64decode(line, validate=True))
    cmd = [
        str(launcher), "--cp=" + classpath, "--target_class=dicechess.engine.pilot." + target,
        "--instrumentation_includes=dicechess.engine.domain.**:dicechess.engine.movegen.**",
        "--jvm_args=-Xmx512m", f"-max_len={limit}", "-timeout=5", "-rss_limit_mb=1536",
        f"-seed={seed}", "-print_final_stats=1",
    ]
    for phase, inputs, timeout in [
        ("replay", list(map(str, sorted(corpus.iterdir()))), 90),
        ("explore", [f"-max_total_time={args.seconds}", str(corpus)], args.seconds + 60),
    ]:
        log_path = results / f"{target}-{phase}.log"
        print(f"{target} {phase}", flush=True)
        with log_path.open("w") as log:
            subprocess.run(cmd + inputs, cwd=results, stdout=log, stderr=subprocess.STDOUT,
                           timeout=timeout, check=True)
