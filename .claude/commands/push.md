# Push to main and watch CI

Push the current branch to `origin main` and watch CI until it either passes or fails. This command ensures we never leave main in a broken state after a direct push.

## Steps

1. **Push**

   ```bash
   git push origin main
   ```

   If the push is rejected (non-fast-forward), rebase first:

   ```bash
   git pull --rebase origin main && git push origin main
   ```

   Resolve any rebase conflicts, then continue:

   ```bash
   git rebase --continue
   # or
   git rebase --abort   # only to cancel the whole rebase
   ```

2. **Find the CI run just triggered**

   ```bash
   gh run list --branch main --limit 3
   ```

   Note the run ID for the `CI` workflow (not Deploy or Benchmark).

3. **Watch until complete**

   ```bash
   gh run watch <run-id> --exit-status
   ```

   This streams job output live and exits 0 on success, non-zero on failure.

4. **On failure**

   ```bash
   gh run view <run-id> --log-failed
   ```

   Fix the failing test or build error, commit, and re-run `/push`.

5. **On success**

   Report back: "CI passed — `<short-sha>` is green on main."

## Rules

- Never leave this command before CI completes — watch until it finishes.
- If CI fails, diagnose the failure before reporting back. Don't just say "CI failed."
- If the push itself fails for a reason other than non-fast-forward (permissions, protected branch, etc.), surface the exact error to the user and stop.
