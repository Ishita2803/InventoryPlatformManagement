#!/usr/bin/env bash
# Phase 18 -- bring-up. Reverses down.sh: restarts the data VM, scales the node pool back
# up, re-enables autoscaling, and waits for all six pods to be Ready before handing back a
# public URL. Run this before a demo/interview.
#
# Assumes the cluster and VM still exist (down.sh never deletes either) -- if either was
# actually deleted, this script does not recreate it; see Phase 13/15 in plan.md for that.

set -euo pipefail

PROJECT_ID="inventorymanagement-507107"
ZONE="us-central1-a"
CLUSTER="order-platform-cluster"
NODE_POOL="default-pool"
DATA_VM="order-platform-data-vm"
# Matches the node count Phase 15 found necessary for all 6 pods to schedule immediately
# (GKE's own addons eat most of an e2-medium's allocatable CPU -- see Agent.md Trap #{CPU}).
# The autoscaler can still scale down to 1 later if idle, and up to 4 under load.
STARTUP_NODE_COUNT=4

gcloud config set project "${PROJECT_ID}" >/dev/null

echo "== Starting ${DATA_VM} =="
gcloud compute instances start "${DATA_VM}" --zone "${ZONE}" --quiet

echo "== Resizing ${CLUSTER}/${NODE_POOL} to ${STARTUP_NODE_COUNT} nodes =="
gcloud container clusters resize "${CLUSTER}" \
  --node-pool "${NODE_POOL}" \
  --num-nodes "${STARTUP_NODE_COUNT}" \
  --zone "${ZONE}" \
  --quiet

echo "== Re-enabling autoscaling (min 1, max 4) =="
gcloud container clusters update "${CLUSTER}" \
  --node-pool "${NODE_POOL}" \
  --enable-autoscaling \
  --min-nodes 1 \
  --max-nodes 4 \
  --zone "${ZONE}" \
  --quiet

echo "== Fetching cluster credentials =="
gcloud container clusters get-credentials "${CLUSTER}" --zone "${ZONE}"

echo "== Waiting for MySQL/Kafka on the data VM to accept connections (up to 90s) =="
DATA_VM_IP=$(gcloud compute instances describe "${DATA_VM}" --zone "${ZONE}" \
  --format='get(networkInterfaces[0].networkIP)')
for i in $(seq 1 18); do
  if kubectl run tcp-check-$$ --rm -i --restart=Never --image=busybox --quiet \
      --command -- sh -c "nc -z -w2 ${DATA_VM_IP} 3306" 2>/dev/null; then
    echo "MySQL reachable at ${DATA_VM_IP}:3306"
    break
  fi
  sleep 5
done

echo "== Waiting for all six deployments to report Ready (up to 5 min) =="
for dep in config-service api-gateway-service order-service inventory-service \
           notification-service payment-service; do
  kubectl rollout status "deployment/${dep}" --timeout=300s
done

echo "== Fetching the public gateway IP =="
GATEWAY_IP=$(kubectl get svc api-gateway-service \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')

echo "== Done. Verify with: =="
echo "  curl http://${GATEWAY_IP}/api/orders"
