#!/usr/bin/env bash
# Runs the JMH benchmarks on temporary EC2 spot machines in parallel and compares two git revisions.
# Each benchmark method (at each thread count) runs for both revisions back to back on the same
# machine. The framework benchmarks (benchmark/spring, micronaut, quarkus) are included, each on its own JDK. Every resource is destroyed at the end, also on failure or Ctrl+C.
#
#   infra/bench.sh <base-rev> <new-rev> [include-regex]
#
#   <base-rev>, <new-rev>  revisions of this repository, or <path>@<rev> for another clone
#   include-regex          only Class.method names matching it (default: every benchmark)
#
# Environment: MACHINES (default 19), SPOT_MACHINES (default 3; the rest are on-demand), THREADS (default 1,4),
# JMH_OPTIONS (default "-prof gc"; e.g. add "-jvmArgsAppend -XX:..." to test a JVM flag),
# AWS_REGION (default us-east-1). Credentials: AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY or AWS_PROFILE;
# a .env at the repository root is loaded if present (AWS_ACCESS_KEY is accepted for the key id).
# Results: benchmark/build/aws/<run-id>/ (raw JMH JSON and output per run, summary.md).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
INFRA="$ROOT/infra"
MACHINES=${MACHINES:-19}
SPOT_MACHINES=${SPOT_MACHINES:-3}
JMH_OPTIONS=${JMH_OPTIONS:--prof gc}
THREADS=${THREADS:-1,4}
[ $# -ge 2 ] || { sed -n '2,15p' "$0"; exit 2; }

if [ -f "$ROOT/.env" ]; then
  set -a; . "$ROOT/.env"; set +a
  export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID:-${AWS_ACCESS_KEY:-}}
fi
export AWS_REGION=${AWS_REGION:-us-east-1} AWS_DEFAULT_REGION=${AWS_REGION:-us-east-1}

RUN_ID=$(date +%Y%m%d-%H%M%S)
WORK="$ROOT/benchmark/build/aws/$RUN_ID"
mkdir -p "$WORK"
log() { echo "$(date +%H:%M:%S) $*"; }

# 1. Compile each revision in a fresh clone and package the benchmark's runtime classpath.
#    The machines then only need a JDK: no Gradle, no dependency downloads.
prepare() { # <name> <spec>
  local name=$1 spec=$2 repo=$ROOT rev=$2
  if [[ $spec == *@* ]]; then repo=${spec%@*}; rev=${spec##*@}; fi
  local dir="$WORK/src-$name"
  git clone -q "$repo" "$dir" && git -C "$dir" checkout -q "$rev"
  # Every benchmark project the revision has (:benchmark, and benchmark/<framework> where they exist).
  (cd "$dir" && ./gradlew -q --no-configuration-cache -I "$INFRA/classpath.gradle" r4cClasspath)
  python3 "$INFRA/plan.py" bundle "$dir" "$WORK/$name.tar.gz"
  echo "$name = $repo@$(git -C "$dir" rev-parse --short HEAD)" >> "$WORK/revisions.txt"
}
log "compiling $1 and $2"
prepare base "$1" & base_pid=$!
prepare new "$2" & new_pid=$!
wait $base_pid || { echo "compiling $1 failed" >&2; exit 1; }
wait $new_pid || { echo "compiling $2 failed" >&2; exit 1; }
cat "$WORK/revisions.txt"

python3 "$INFRA/plan.py" plan "$WORK/src-base" "$WORK/src-new" "$MACHINES" "$THREADS" ${3:+"$3"} > "$WORK/plan.json"

TF=(terraform -chdir="$INFRA")
VARS=(-var "run_id=$RUN_ID" -var "region=$AWS_REGION" -var "spot_machines=$SPOT_MACHINES" -var "jmh_options=$JMH_OPTIONS" -var "plan_file=$WORK/plan.json"
      -var "base_bundle=$WORK/base.tar.gz" -var "new_bundle=$WORK/new.tar.gz")
destroy() {
  log "destroying run $RUN_ID"
  for attempt in 1 2 3; do
    "${TF[@]}" destroy -auto-approve -input=false "${VARS[@]}" >> "$WORK/destroy.log" 2>&1 && { log "destroyed"; return; }
    sleep 20
  done
  echo "terraform destroy FAILED: see $WORK/destroy.log; remove resources tagged Run=$RUN_ID by hand" >&2
}
trap destroy EXIT

# 2. Create the machines. Spot capacity can be short in one zone, so a failed apply is retried:
#    Terraform keeps what was created and only retries the rest.
"${TF[@]}" init -input=false > "$WORK/init.log"
log "creating machines"
for attempt in 1 2 3; do
  "${TF[@]}" apply -auto-approve -input=false "${VARS[@]}" >> "$WORK/apply.log" 2>&1 && break
  [ $attempt -lt 3 ] || { echo "terraform apply failed 3 times (see $WORK/apply.log): continuing with the machines that started" >&2; break; }
  log "apply failed (attempt $attempt), retrying"; sleep 15
done
BUCKET=$("${TF[@]}" output -raw bucket) || { echo "no bucket: nothing was created" >&2; exit 1; }
# Only the machines Terraform created and did not taint (a failed one is replaced on the next apply).
NAMES=$("${TF[@]}" output -json instances | python3 -c 'import json,sys; print(" ".join(sorted(json.load(sys.stdin))))')
MISSING=$(python3 -c 'import json,sys; planned={m["name"] for m in json.load(open(sys.argv[1]))}; print(" ".join(sorted(planned - set(sys.argv[2].split()))))' "$WORK/plan.json" "$NAMES")
[ -z "$MISSING" ] || echo "not started, their runs are missing: $MISSING" >&2
log "$(echo $NAMES | wc -w | tr -d ' ') machines running"

# 3. Wait for every machine's done marker (written on exit, also after a failure or the time limit).
deadline=$(( $(date +%s) + 50 * 60 ))
pending=$NAMES
while [ -n "$pending" ] && [ "$(date +%s)" -lt "$deadline" ]; do
  sleep 20
  # Nothing uploaded yet makes `aws s3 ls` fail; that must not end the run (set -e, pipefail).
  done_now=$({ aws s3 ls --recursive "s3://$BUCKET/results/" || true; } | awk '$4 ~ /\/done$/ {split($4, p, "/"); print p[2]}')
  still=""
  for n in $pending; do
    if grep -qx "$n" <<< "$done_now"; then log "$n finished"; else still="$still $n"; fi
  done
  pending=$(echo $still)
done
[ -z "$pending" ] || echo "timed out waiting for: $pending" >&2

# 4. Collect and summarise; the trap destroys everything.
aws s3 cp --recursive --quiet "s3://$BUCKET/results/" "$WORK/results/"
python3 "$INFRA/plan.py" summary "$WORK/results" | tee "$WORK/summary.md"
log "results in $WORK"
