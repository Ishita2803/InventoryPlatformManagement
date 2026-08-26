#!/usr/bin/env python3
"""
Drives synthetic order volume through the platform and measures what actually happens.

The point is to replace guesses with numbers. Every figure quoted in the README and the
interview guide comes from a run of this script, and the raw output is kept in RESULTS.md so
the claims can be checked rather than believed.

What it measures
----------------
  * POST latency          -- how long the API takes to accept an order. Should be fast and
                             flat, because acceptance writes to one database and returns; it
                             does not wait for Kafka, inventory or payment.
  * End-to-end latency    -- POST until the order reaches a terminal status. This is the
                             asynchronous saga: outbox poll + reserve + payment + settle.
  * Throughput            -- accepted orders per second.
  * Outcome distribution  -- CONFIRMED / CANCELLED / INVENTORY_FAILED / still pending.

Usage
-----
    python bench.py --orders 200 --concurrency 20

Prerequisites: `docker compose up -d` and all containers healthy.
"""

import argparse
import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

GATEWAY = "http://localhost:8080"
INVENTORY = "http://localhost:8082"


def post(url, payload, timeout=30):
    body = json.dumps(payload).encode()
    request = urllib.request.Request(
        url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode())


def get(url, timeout=30):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read().decode())


def percentile(values, pct):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(int(len(ordered) * pct / 100.0), len(ordered) - 1)
    return ordered[index]


def seed(stock, products):
    """
    Seed `products` distinct SKUs, each with enough stock that the run measures throughput
    rather than stock-outs.

    Why the count is a parameter: with ONE product every order contends for the same
    inventory row, and the optimistic-lock retry serialises them. Varying this isolates
    "how fast is the platform" from "how fast can one row be updated" -- which are very
    different questions and are easy to conflate.
    """
    stamp = int(time.time())
    ids = []
    for i in range(products):
        product = post("%s/api/products" % GATEWAY,
                       {"sku": "BENCH-%d-%d" % (stamp, i), "name": "Benchmark Widget %d" % i})
        post("%s/api/inventory" % GATEWAY,
             {"productId": product["id"], "warehouseId": "WH-1", "quantity": stock})
        ids.append(product["id"])
    return ids


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--orders", type=int, default=200)
    parser.add_argument("--concurrency", type=int, default=20)
    parser.add_argument("--settle-timeout", type=float, default=120.0,
                        help="seconds to wait for orders to reach a terminal status")
    parser.add_argument("--products", type=int, default=1,
                        help="distinct SKUs to spread orders across; 1 maximises row contention")
    args = parser.parse_args()

    # One unit per order, plus headroom, so nothing fails for lack of stock.
    per_product = args.orders // args.products + 50
    product_ids = seed(per_product, args.products)
    print("seeded %d product(s), %d units each" % (len(product_ids), per_product))
    print("firing %d orders at concurrency %d...\n" % (args.orders, args.concurrency))

    post_latencies = []
    order_ids = []
    accepted_at = {}
    errors = []
    lock = threading.Lock()

    def place(index):
        payload = {
            "customerId": "BENCH",
            "items": [{"productId": product_ids[index % len(product_ids)],
                       "warehouseId": "WH-1", "quantity": 1, "unitPrice": 9.99}],
        }
        started = time.perf_counter()
        try:
            order = post("%s/api/orders" % GATEWAY, payload)
        except (urllib.error.URLError, urllib.error.HTTPError, OSError) as failure:
            with lock:
                errors.append(str(failure))
            return
        elapsed = (time.perf_counter() - started) * 1000.0
        with lock:
            post_latencies.append(elapsed)
            order_ids.append(order["orderId"])
            accepted_at[order["orderId"]] = time.perf_counter()

    wall_start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        list(pool.map(place, range(args.orders)))
    accept_duration = time.perf_counter() - wall_start

    print("accepted %d orders in %.2fs  (%.1f orders/sec)"
          % (len(order_ids), accept_duration,
             len(order_ids) / accept_duration if accept_duration else 0))
    if errors:
        print("  %d POST errors, first: %s" % (len(errors), errors[0]))

    # Now wait for the asynchronous half to finish.
    terminal = {"CONFIRMED", "CANCELLED", "INVENTORY_FAILED"}
    settled = {}
    deadline = time.perf_counter() + args.settle_timeout
    outstanding = set(order_ids)

    while outstanding and time.perf_counter() < deadline:
        for order_id in list(outstanding):
            try:
                status = get("%s/api/orders/%s" % (GATEWAY, order_id))["status"]
            except Exception:
                continue
            if status in terminal:
                settled[order_id] = (status, (time.perf_counter() - accepted_at[order_id]) * 1000.0)
                outstanding.discard(order_id)
        if outstanding:
            time.sleep(0.25)

    e2e = [ms for _, ms in settled.values()]
    outcomes = {}
    for status, _ in settled.values():
        outcomes[status] = outcomes.get(status, 0) + 1

    print("\n--- POST /api/orders latency (ms) ---")
    print("  n=%d  min=%.0f  p50=%.0f  p95=%.0f  p99=%.0f  max=%.0f  mean=%.0f"
          % (len(post_latencies), min(post_latencies), percentile(post_latencies, 50),
             percentile(post_latencies, 95), percentile(post_latencies, 99),
             max(post_latencies), statistics.mean(post_latencies)))

    print("\n--- end-to-end: POST until terminal status (ms) ---")
    if e2e:
        print("  n=%d  min=%.0f  p50=%.0f  p95=%.0f  p99=%.0f  max=%.0f  mean=%.0f"
              % (len(e2e), min(e2e), percentile(e2e, 50), percentile(e2e, 95),
                 percentile(e2e, 99), max(e2e), statistics.mean(e2e)))
    else:
        print("  nothing settled")

    print("\n--- outcomes ---")
    for status, count in sorted(outcomes.items()):
        print("  %-18s %d" % (status, count))
    if outstanding:
        print("  %-18s %d   (did not settle within %.0fs)"
              % ("STILL PENDING", len(outstanding), args.settle_timeout))

    print("\n--- final stock (first product) ---")
    print("  %s" % json.dumps(get("%s/api/inventory?productId=%d&warehouseId=WH-1"
                                  % (GATEWAY, product_ids[0]))))

    return 0 if not outstanding and not errors else 1


if __name__ == "__main__":
    sys.exit(main())
