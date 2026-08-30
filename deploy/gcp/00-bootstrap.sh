#!/usr/bin/env bash
# Phase 12 — GCP foundation bootstrap.
#
# Reproduces the one-time GCP setup this project depends on. Idempotent where gcloud
# allows it; safe to re-read as documentation even if you never re-run it.
#
# Prerequisites (done manually, not scripted):
#   - gcloud CLI installed and authenticated (`gcloud auth login`)
#   - Billing account linked to the project
#   - Budget alert created in the console at 50/90/100% thresholds — gcloud has no
#     stable, simple CLI surface for budgets; do this once by hand and confirm it fired
#     before creating any resource.

set -euo pipefail

PROJECT_ID="inventorymanagement-507107"
REGION="us-central1"
ZONE="us-central1-a"
REPO_NAME="order-platform-repo"

echo "== Setting active project and region/zone =="
gcloud config set project "${PROJECT_ID}"
gcloud config set compute/region "${REGION}"
gcloud config set compute/zone "${ZONE}"

echo "== Enabling required APIs =="
gcloud services enable \
  container.googleapis.com \
  compute.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  cloudbuild.googleapis.com \
  iam.googleapis.com

echo "== Creating Artifact Registry Docker repository =="
gcloud artifacts repositories create "${REPO_NAME}" \
  --repository-format=docker \
  --location="${REGION}" \
  --description="Order Platform service images" \
  || echo "Repository ${REPO_NAME} already exists, skipping create."

echo "== Configuring Docker auth for Artifact Registry =="
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet

echo "== Done. Push a smoke-test image with: =="
echo "  docker tag order-platform/order-service:latest ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/order-service:latest"
echo "  docker push ${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/order-service:latest"
