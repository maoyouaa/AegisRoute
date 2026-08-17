# Architecture

AegisRoute v0.1 separates baseline serving from candidate evaluation. Gateway serves from an immutable in-memory route snapshot and never queries PostgreSQL on the request path. Shadow selection offers into a bounded local queue; a dedicated publisher sends events to Redpanda. Worker invokes the candidate and stores deterministic evidence through Control-owned persistence contracts.

Control publishes immutable, checksum-protected route revisions. Human actions increase candidate traffic. Deterministic safety policy may create an append-only rollback decision and a higher route revision with zero candidate traffic. Gateway acknowledgements close the evidence chain by proving convergence.

Default Compose keeps Control, Worker, PostgreSQL, Redpanda, and mock providers on `aegis-internal`. Control listens on `0.0.0.0:8081` inside its container but has no host port. A separate development override may bind `127.0.0.1:8081`.

Every long-running Compose service uses `restart: unless-stopped`, and Redpanda has an explicit cluster-health probe. A crashed PostgreSQL or Redpanda process is therefore restarted without manual intervention. Gateway snapshot polling and acknowledgement use a bounded Control request timeout so an unavailable dependency cannot permanently occupy the scheduler thread; baseline serving continues from LKG while dependencies recover. The observed failure and corrective evidence are recorded in the [Compose dependency recovery incident report](incidents/2026-08-17-compose-dependency-recovery.md).
