# Outbox relay contract

Every service that publishes domain events through the transactional outbox
uses the same relay contract. The contract covers the claim, the ordering
guarantee, the claim lease, the recovery scan and the graceful shutdown.

Services covered: `product`, `inventory`, `media`, `promotion`, `rating`,
`payment`.

## 1. Claim

A relay tick runs one short transaction:

1. `findClaimableAggregates` reads the aggregate ids of the pending rows, in
   ascending outbox id order, limited to the aggregate window
   (`batch-size * 4` rows).
2. For every candidate aggregate the relay calls `claimAggregateEvents`. That
   statement
   - takes a PostgreSQL transaction scoped advisory lock derived from the
     aggregate id (`pg_try_advisory_xact_lock(hashtextextended(aggregate_id::text, 0))`),
   - refuses the aggregate when any event of that aggregate is still in
     `SENDING`,
   - locks the pending events of that aggregate with `FOR UPDATE` and returns
     them in ascending id order.
3. The relay stamps `status = SENDING`, `claimed_at = now()` and
   `lease_owner = <pid>-<uuid>` on the claimed rows and commits.

The advisory lock serialises the claim decision per aggregate, so two relay
instances can never decide on the same aggregate at the same time. The
`SENDING` check keeps an aggregate owned by one instance until that instance
has finalised the event. A locked aggregate never blocks another aggregate, so
the fleet keeps the parallelism of the batch claim: the claim is not pinned to
the head of the table.

## 2. Ordering guarantee

**Guarantee.** The events of one aggregate reach the broker in ascending outbox
id order, also when several relay instances publish concurrently.

The guarantee holds because

- only one instance can own an aggregate at a time (advisory lock plus the
  `SENDING` check), and
- the owner publishes the events of that aggregate in ascending id order (the
  batch is sorted by id), and
- the next event of an aggregate is claimed only after the previous one reached
  `SENT` or `FAILED`, or after its claim lease expired.

The regression test `OutboxAggregateOrderIT` (one per service) starts two relay
instances, blocks the first one inside the publish of the first event of an
aggregate and lets the second instance publish concurrently. The recorded
publication order per aggregate must stay in creation order.

**Limit of the guarantee.** The outbox is at-least-once. When a batch publish
fails for one record, the later records of the same aggregate were already
handed to the broker; the relay does not mark them as sent and republishes them
later, so a consumer can observe the later event before the republished earlier
one. Consumers must be idempotent.

## 3. Claim lease and recovery scan

The lease of a claim is `claimed_at` plus `lease_owner`.

`reclaimStaleSending` re-arms the rows that a dead relay left behind. A row is
re-armed when

- `status = SENDING` and `claimed_at < cutoff` (the lease expired), or
- `status = SENDING` and `claimed_at IS NULL`.

The second rule matters: a row that entered `SENDING` without a lease
timestamp satisfies neither `claimed_at < cutoff` nor `claimed_at > cutoff`.
Without the `IS NULL` branch such a row would stay in `SENDING` forever, be
invisible to the recovery scan and never be republished. The migration that
adds the lease columns backfills `claimed_at` from `updated_at`, but the
backfill cannot cover a row that enters `SENDING` afterwards without a
timestamp.

## 4. Graceful shutdown

`@PreDestroy releaseOwnClaims` hands the leases of this instance back to the
pool. It distinguishes three states of a leased event:

| State of the leased event | Action on shutdown |
| --- | --- |
| Delivery confirmed by the publisher, database state not written yet | Finalised as `SENT`, never handed back |
| Handed to the publisher, delivery not confirmed yet | Left in `SENDING`, the claim lease expiry recovers it |
| Never handed to the publisher | Handed back to `PENDING` |

The first row is the fix for the duplicate publish: a lease whose event is
already on the broker must not go back to `PENDING`, otherwise the event is
published twice. The relay therefore tracks the published events of the running
batch (`OutboxPublishLedger`) and excludes them from the release.

The outbox stays at-least-once across a shutdown: a publish that was interrupted
by the shutdown can still be repeated later. Consumers must be idempotent.
