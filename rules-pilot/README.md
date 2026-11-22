# Pre-release rules pilot

The release workflow checks the candidate commit before tagging it. The same check can run independently through **Pre-release rules pilot** in Actions. It reads the selected commit and uploads evidence for 14 days.

Each target first replays its retained corpus. The files under `corpus/` store one Base64-encoded input per line to preserve arbitrary bytes. The pilot then explores the DFEN round-trip property and generated-move transitions for five minutes each. The move target checks up to four legal single-die transitions starting from four public positions.

For a local run, extract the Jazzer 0.30.0 standalone distribution for your platform with its companion JAR and run from the repository root:

```sh
mise exec -- python3 rules-pilot/run.py /path/to/jazzer /path/to/new-results --seconds 300
```

The output directory must be new. It contains the checked-out SHA, seed, phase logs and per-target inputs. A Jazzer finding also writes a reproducer or failing input when available. Use a clean checkout of the recorded SHA when investigating. Supply the recorded seed with `--seed` to repeat the starting conditions. The exact input is the preferred evidence because elapsed-time campaigns can diverge.

If the check fails, identify the target and phase in the Actions artifact. Confirm a rules mismatch against the exact input, add a conventional regression test and check the corrected candidate. For download, compilation or runner failures, correct or retry the failed check on the candidate before continuing the release.
