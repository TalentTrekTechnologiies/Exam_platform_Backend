# The judge host — running a coding round

A coding round executes programs written by candidates. Not untrusted *input* to
your program: their actual program, chosen by them, on a machine you own. This
document is how to give that its own machine and keep it away from everything
else.

Everything here is separate from the exam application. Nothing below touches the
box that serves `ksrmce.ac.in`.

---

## Why not on the exam server

The current production box is 2 cores, shared by MySQL, the exam application and
the college website. Compiling and running submissions is CPU-bound and bursty:
a hall of candidates presses **Run** in clusters, not evenly. On two shared
cores, three things happen at once —

- coding candidates queue, and experience the queue as the platform being broken;
- candidates sitting an ordinary multiple-choice paper at the same time get a
  slower clock poll and slower answer saves;
- the college website slows down, because it is on the same cores.

The application refuses to start with `judge.provider=local` under a production
profile for a related reason: that judge runs candidate code as the application's
own user, with its own file access. The sandbox is not optional.

---

## Sizing it

The number that matters is **executions per second**, and it is not the same as
candidates. Estimate it:

```
executions  =  candidates × (runs per candidate + submits per candidate)
per second  =  executions ÷ (length of the round in seconds)
cores       ≈  per second × seconds of CPU per execution
```

Candidates press Run far more often than they submit — assume 8–12 Runs and 1–2
Submits per question. A compile-and-run is roughly 1–3 seconds of CPU (C and
Python at the low end, Java at the high end, because the JVM starts every time).

Worked, for a TCS NQT-style round:

| | |
|---|---|
| 300 candidates, 2 coding questions, 45 minutes | |
| executions | 300 × (2 × 10) ≈ **6,000** |
| per second | 6,000 ÷ 2,700 ≈ **2.2/s** |
| CPU needed | 2.2 × 2s ≈ **4.4 cores** |

So **4 vCPU / 8 GB** carries about 300 concurrent coding candidates with a little
headroom. Scale roughly linearly: 8 vCPU for ~600. Add workers to match cores —
one worker saturates one core, so `docker compose up -d --scale workers=N`.

Two things this model does not capture, both of which argue for headroom:

- **Bursts.** Everyone runs their first attempt within a few minutes of the
  round opening. A queue that clears in 30 seconds is invisible; one that clears
  in 5 minutes is a complaint.
- **Java.** A round where most candidates choose Java costs noticeably more than
  the same round in Python. If you allow Java, size for it.

---

## Provisioning

Any small VPS will do; it needs Docker and nothing else.

```bash
# 1. Docker
curl -fsSL https://get.docker.com | sh

# 2. The repo, for the compose file
git clone <backend-repo> /opt/exam-judge
cd /opt/exam-judge/deploy

# 3. Secrets
cat > .env <<'EOF'
JUDGE_DB_PASSWORD=<a real password>
JUDGE_REDIS_PASSWORD=<another one>
JUDGE_TOKEN=<openssl rand -base64 32>
EOF

# 4. Up
docker compose -f docker-compose.judge.yml up -d
docker compose -f docker-compose.judge.yml logs -f server   # wait for it to settle
```

Check it answers, from the judge host itself:

```bash
curl -s -H "X-Auth-Token: $JUDGE_TOKEN" http://127.0.0.1:2358/languages | head -c 200
```

---

## Locking it down

**This service executes arbitrary code. It must never be reachable from the
internet.** The compose file binds it to `127.0.0.1`, which is the first line of
defence and is not by itself enough once the exam server needs to reach it.

Pick one, in order of preference:

1. **Private networking.** Most providers offer a private interface between your
   own machines. Bind the judge to that address and firewall the public one.
2. **A firewall rule allowing only the exam server's IP:**
   ```bash
   ufw default deny incoming
   ufw allow from <exam-server-ip> to any port 2358 proto tcp
   ufw allow ssh
   ufw enable
   ```
3. **WireGuard** between the two hosts, judge bound to the tunnel address.

`JUDGE_TOKEN` is defence in depth, not a substitute for any of the above: it
means a firewall mistake is not immediately an open code-execution endpoint.

---

## Pointing the exam application at it

On the **exam** server, in `/opt/exam/deploy/.env`:

```bash
JUDGE_PROVIDER=judge0
JUDGE_URL=http://<judge-host-private-ip>:2358
JUDGE_TOKEN=<the same token>
```

Then:

```bash
cd /opt/exam/deploy
sudo docker compose -f docker-compose.behind-nginx.yml up -d
```

Confirm the application agrees, signed in as an admin:

```
GET /admin/coding/health
→ {"available": true, "judge": "Judge0 at http://…", "languages": [...]}
```

While `available` is false, the publish screen refuses to release any paper
containing a coding question. That blocker is deliberate: the alternative is a
candidate discovering it after writing a solution.

---

## Before the first real coding round

- [ ] `GET /admin/coding/health` reports `available: true`
- [ ] Judge host is **not** reachable from the public internet — check from
      somewhere other than your own network
- [ ] A question authored, and its key checked with **Save cases & run** against
      a reference solution in every language you allow
- [ ] A test candidate has sat it end to end: Run, Submit, and a mark on the
      scorecard
- [ ] Sized for the burst, not the average
- [ ] A plan for the judge being down mid-round — it does not stop the exam, but
      coding answers cannot be submitted while it is
