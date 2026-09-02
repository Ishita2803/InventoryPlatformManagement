#!/usr/bin/env bash
# Phase 18 -- teardown. Leaves nothing billable but disks (Artifact Registry images,
# the cluster's PD, the data VM's PD, Secret Manager). No cluster deletion, no VM deletion --
# both come back in up.sh in under a couple of minutes, and deleting either would mean
# re-running Phase 15/13's one-time setup (Workload Identity, firewall rules, MySQL/Kafka
# data) instead of a cheap resume.
#
# Run this at the end of every session. The always-on cost table in plan.md (~$45/mo) only
# applies if you skip this script.

set -euo pipefail

PROJECT_ID="inventorymanagement-507107"
ZONE="us-central1-a"
CLUSTER="order-platform-cluster"
NODE_POOL="default-pool"
DATA_VM="order-platform-data-vm"

gcloud config set project "${PROJECT_ID}" >/dev/null

echo "== Disabling autoscaling on ${CLUSTER}/${NODE_POOL} (min-nodes=1 would fight a resize to 0) =="
gcloud container clusters update "${CLUSTER}" \
  --node-pool "${NODE_POOL}" \
  --no-enable-autoscaling \
  --zone "${ZONE}" \
  --quiet

echo "== Scaling ${CLUSTER}/${NODE_POOL} to 0 nodes =="
gcloud container clusters resize "${CLUSTER}" \
  --node-pool "${NODE_POOL}" \
  --num-nodes 0 \
  --zone "${ZONE}" \
  --quiet

echo "== Stopping ${DATA_VM} (disk persists; MySQL/Kafka data untouched) =="
gcloud compute instances stop "${DATA_VM}" --zone "${ZONE}" --quiet

echo "== Done. Still billed: disks (cluster PD + data VM PD, ~\$3/mo), reserved static IP"
echo "   while unattached to a running forwarding rule (see gcloud pricing), Artifact"
echo "   Registry storage (<1GB, ~\$0). GKE control-plane fee stays \$0 either way -- the"
echo "   free-tier credit doesn't care whether nodes are scaled to zero."
echo "   Verify nothing unexpected is running: gcloud compute instances list"
