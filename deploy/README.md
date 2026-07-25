# Deploying for exam-day scale

This stack runs the platform as a horizontal fleet: one tuned MySQL, three
stateless app instances, and an nginx load balancer that also serves uploaded
assets. It is designed to carry a full examination hall and to scale past it by
adding instances.

## Run it

```bash
cd deploy
cp .env.example .env
#  edit .env — set JWT_SECRET (openssl rand -base64 48) and DB_PASSWORD
docker compose up -d --build
```

The platform is then on `http://localhost`. nginx fans requests across `app1/2/3`;
MySQL is not exposed outside the compose network.

Scaling the app tier is two lines: add `app4` (a `<<: *app` copy) in
`docker-compose.yml` and a `server app4:8080;` line in `nginx.conf`, then
`docker compose up -d`. Because every instance is stateless — auth is a signed
JWT, all exam state lives in the database — any request can go to any instance
with no session affinity.

## Why this reaches 5,000 candidates

The number that governs capacity is **requests per second at real exam pacing**.
During a paper a candidate is mostly reading and thinking; across the sitting
they emit roughly one request every 5–10 seconds — call it **0.15 req/s**. So:

```
   concurrent candidates  ≈  sustained req/s  ÷  0.15
```

Measured on a single 4-core laptop with MySQL co-resident (the worst case —
client, app and database all fighting for the same cores):

| Load                         | Result                                    |
|------------------------------|-------------------------------------------|
| 2,000 concurrent, full flow  | **0 errors**, median answer save **180 ms** |
| Sustained throughput         | ~300–350 req/s → **~2,000 candidates**    |
| Start burst (2,000 at once)  | all papers frozen in ~9 s, **0 errors**   |

One co-resident instance already covers ~2,000. Splitting the database onto its
own host (so the app gets all its cores) lifts a single instance well beyond
that, and the fleet then adds linearly:

```
   3 app instances  +  1 dedicated tuned MySQL   →   5,000 with headroom
```

MySQL is the shared limit, not the app tier, which is why `mysql-tuning.cnf`
raises `max_connections` to 300 and the buffer pool to keep the working set in
memory. Keep `instances × DB_POOL_SIZE` under that 300.

## The one rough edge: the start burst

Everyone clicks **Start** at the same second. Each start creates an attempt and
freezes a ~180-row paper. Batching that freeze into a single JDBC statement
(rather than 180 inserts) is what let 2,000 simultaneous starts clear in ~9 s
with zero failures even on the laptop. Two further levers, in order of effort:

1. **Stagger** the slot-open — even a 60-second spread flattens the spike.
2. **Pre-create attempts** at candidate-upload time, so Start becomes a cheap
   status flip instead of a burst of inserts.

## Before a real exam

- Set a strong `JWT_SECRET` and a real `DB_PASSWORD`. The app now **refuses to
  start** on the development secret outside a dev/test profile, so this cannot be
  forgotten silently.
- Keep `DDL_AUTO=validate` and manage schema with migrations — see below.
- Put TLS in front (terminate at nginx or an upstream load balancer).
- Point `CORS_ORIGINS` at the real frontend origin.
- Serve question images through a CDN if papers are image-heavy.

---

## Two things this repo has NOT done yet

Both are deliberate, and both matter before high-stakes use.

### 1. Schema is still `ddl-auto`, not migrations

`spring.jpa.hibernate.ddl-auto=update` lets Hibernate reshape tables to match the
entities. That is fine in development and **dangerous in production**: it can
silently alter a live table, it cannot be reviewed before it runs, and it gives
no way to roll back.

The migration path is mechanical but must be done deliberately:

```xml
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-mysql</artifactId>
</dependency>
```

1. Point Flyway at the existing schema and run `flyway baseline` so the current
   tables become version 1 rather than being recreated.
2. Move every future change into `src/main/resources/db/migration/V2__*.sql`.
3. Set `DDL_AUTO=validate` permanently — Hibernate then only verifies that the
   entities match the schema and refuses to start if they drift.

Until that is done, **take a database dump before every deploy.**

### 2. Tokens are in `localStorage`, not httpOnly cookies

The client stores its bearer token in `localStorage`. It is simple and it works,
but any cross-site scripting flaw can read it, and the token is long-lived.

The stronger design is an httpOnly, `SameSite=Strict`, `Secure` cookie that
JavaScript cannot read, plus a short-lived access token and a refresh endpoint.
It is not a drop-in change — it needs CSRF protection re-enabled (the current
config disables it precisely because auth is a bearer header, not a cookie), so
it should be done as one deliberate piece of work rather than piecemeal.

Interim mitigations already in place: admin and student tokens are separate,
tokens expire (8h admin / 6h student), and a 401 clears the session.

## Measuring it yourself

`../scripts/load.mjs` drives the real endpoints — start burst then steady state —
and reports req/s, latency percentiles, error rate, and the candidate estimate:

```bash
node ../scripts/load.mjs n=2000 dur=20 pace=1   # realistic pacing
node ../scripts/load.mjs n=200  dur=15          # flat-out throughput ceiling
```
