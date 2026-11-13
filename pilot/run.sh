#!/usr/bin/env bash
# The pilot: Spring Boot, Micronaut and Quarkus services using resilience4cata from Maven Central, each under 10
# minutes of k6 load with the backend going down and slow, on one AWS machine (c7g.4xlarge: 4 cores for the service,
# 4 for the backend, 8 for k6). Every resource is destroyed at the end, also on failure or Ctrl+C.
#
#   pilot/run.sh aws               # on AWS, about 40 minutes; reads AWS keys from .env like infra/bench.sh
#   MINUTE=6 pilot/run.sh local    # on this machine, each "minute" of the load shortened to 6 s
#
# Results and report.md in pilot/build/<aws|local>/<run>/.
set -euo pipefail
PILOT=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$PILOT/.." && pwd)
MODE=${1:-}
[[ $MODE == aws || $MODE == local ]] || { sed -n '2,10p' "$0"; exit 2; }
RUN_ID=$(date +%Y%m%d-%H%M%S)
WORK="$PILOT/build/$MODE/$RUN_ID"
mkdir -p "$WORK"
log() { echo "$(date +%H:%M:%S) $*"; }

log "building"
"$ROOT/gradlew" -q -p "$PILOT" installDist :quarkus:quarkusBuild
BUNDLE="$WORK/bundle"
mkdir -p "$BUNDLE"
for p in backend spring micronaut; do cp -R "$PILOT/$p/build/install/$p" "$BUNDLE/$p"; done
cp -R "$PILOT/quarkus/build/quarkus-app" "$BUNDLE/quarkus"
cp "$PILOT/load.js" "$PILOT/machine.sh" "$BUNDLE/"

if [ "$MODE" = local ]; then
  MINUTE=${MINUTE:-6} OUT="$WORK/results" "$BUNDLE/machine.sh"
  python3 "$PILOT/report.py" "$WORK/results" "${MINUTE:-6}" | tee "$WORK/report.md"
  exit
fi

if [ -f "$ROOT/.env" ]; then
  set -a; . "$ROOT/.env"; set +a
  export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID:-${AWS_ACCESS_KEY:-}}
fi
export AWS_REGION=${AWS_REGION:-us-east-1} AWS_DEFAULT_REGION=${AWS_REGION:-us-east-1}
tar czf "$WORK/bundle.tar.gz" -C "$BUNDLE" .

TF=(terraform -chdir="$PILOT/infra")
VARS=(-var "run_id=$RUN_ID" -var "region=$AWS_REGION" -var "bundle=$WORK/bundle.tar.gz")
destroy() {
  log "destroying run $RUN_ID"
  for _ in 1 2 3; do
    "${TF[@]}" destroy -auto-approve -input=false "${VARS[@]}" >> "$WORK/destroy.log" 2>&1 && { log "destroyed"; return; }
    sleep 20
  done
  echo "terraform destroy FAILED: see $WORK/destroy.log; remove resources tagged Run=$RUN_ID by hand" >&2
}
trap destroy EXIT

"${TF[@]}" init -input=false > "$WORK/init.log"
log "creating the machine"
for attempt in 1 2 3; do
  "${TF[@]}" apply -auto-approve -input=false "${VARS[@]}" >> "$WORK/apply.log" 2>&1 && break
  [ $attempt -lt 3 ] || { echo "terraform apply failed 3 times, see $WORK/apply.log" >&2; exit 1; }
  log "apply failed (attempt $attempt), retrying"; sleep 15
done
BUCKET=$("${TF[@]}" output -raw bucket)
log "machine $("${TF[@]}" output -raw instance) running; waiting for its results"

deadline=$(( $(date +%s) + 70 * 60 ))
until aws s3 ls "s3://$BUCKET/results/done" > /dev/null 2>&1; do
  [ "$(date +%s)" -lt "$deadline" ] || { echo "timed out waiting for the machine" >&2; break; }
  sleep 30
done
aws s3 cp --recursive --quiet "s3://$BUCKET/results/" "$WORK/results/"
python3 "$PILOT/report.py" "$WORK/results" 60 | tee "$WORK/report.md" || true
log "results in $WORK"
