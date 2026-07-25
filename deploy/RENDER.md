# Deploying to Render — backend, frontend, and the KSRM link

Written for exactly the setup you have: both repos already on GitHub
(`Exam_platform_Backend`, `Exam_platform_Frontend`), and a KSRM CMS elsewhere
that links to the exam admin.

## Why "clicking it shows nothing" is happening right now

Nothing is deployed yet. Two places still say `localhost`, which only exists on
your own machine:

1. `D:\ksrm-website\frontend\.env.local` has
   `NEXT_PUBLIC_EXAM_ADMIN_URL=http://localhost:5174` — the sidebar link is
   built from this at compile time, so the deployed KSRM site links to a port
   on *your* laptop.
2. The exam frontend has never been built with `VITE_API_URL` set, so its API
   calls fall back to `http://localhost:8080` too — same problem, one layer in.

Both are fixed by finishing this deployment and pointing both places at the
real deployed URLs, below.

## The one thing to decide before starting: the database

**Render has no managed MySQL**, and Render's own free Postgres **deletes your
data after 30 days** — disqualifying for real candidate records. Migrating off
MySQL is not the fix either: several queries (the answer upsert, the atomic
exam-start, the mail-outbox claim) are hand-written MySQL and would need
rewriting for no benefit.

The fix that keeps everything free and keeps MySQL: **Aiven's free MySQL
tier** — genuinely free forever, no card, no expiry (1 GB storage/RAM, single
node). One caveat worth knowing: it powers off after a period of no activity
and emails you before it does — restarting it takes under a minute from the
Aiven console. Fine for a mock-exam platform; just check it's running the
morning of an exam if there has been a quiet stretch.

---

## 1. Database — Aiven MySQL (free)

1. [aiven.io/free-mysql-database](https://aiven.io/free-mysql-database) → sign
   up (no card) → **Create service** → MySQL → the free plan.
2. Once it's up, the **Overview** tab gives you: Host, Port, User, Password,
   Default database name.
3. Aiven requires an encrypted connection. Build the JDBC URL as:
   ```
   jdbc:mysql://<HOST>:<PORT>/<DATABASE>?sslMode=REQUIRED&serverTimezone=Asia/Kolkata
   ```
   Keep these three values (URL, user, password) — you'll paste them into
   Render in step 2.

## 2. Backend — Render Web Service

1. [dashboard.render.com](https://dashboard.render.com) → **New** → **Web
   Service** → connect `Exam_platform_Backend`.
2. Runtime: **Docker** (it will find the repo's `Dockerfile` automatically).
   Plan: **Free**.
3. **Environment** tab — add these (values from Aiven for the DB three, and
   generate the JWT secret fresh, don't reuse an example):

   | Key | Value |
   |---|---|
   | `JWT_SECRET` | output of `openssl rand -base64 48` |
   | `DB_URL` | the Aiven JDBC URL from step 1 |
   | `DB_USER` | from Aiven |
   | `DB_PASSWORD` | from Aiven |
   | `DDL_AUTO` | `update` *(first deploy only — see step 5)* |
   | `ALLOW_REGISTRATION` | `false` |
   | `SEED_ADMIN_EMAIL` | e.g. `exams@ksrm.edu.in` |
   | `SEED_ADMIN_PASSWORD` | a real password — this is your only way in |
   | `SEED_ADMIN_COLLEGE` | `KSRM College of Engineering` |
   | `SEED_ADMIN_CODE` | `ksrm-college` — must equal the frontend's `VITE_INSTITUTION_CODE` in step 3 |
   | `CORS_ORIGINS` | leave a placeholder for now, e.g. `https://placeholder` — you'll fix this in step 4 once the frontend URL exists |
   | `CANDIDATE_BASE_URL` | same placeholder — fixed in step 4 |
   | `MAIL_ENABLED` | `false` |
   | `JAVA_OPTS` | `-Xmx300m -XX:MaxMetaspaceSize=128m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError` |
   | `DB_POOL_SIZE` | `5` |
   | `TOMCAT_THREADS` | `20` |

   The last three matter: the Dockerfile's default memory settings assume a
   real server with several GB (correct for the VPS path), which is enough to
   get the whole container OOM-killed on Render's free 512MB instance before
   Spring Boot finishes starting. These three keep it inside that budget.

4. **Create Web Service.** First build takes a few minutes. Watch the logs for
   `Started ExamSystemApplication`.
5. Confirm it's alive: `https://<your-service>.onrender.com/health` should
   return `{"status":"UP","db":"UP"}`. If `db` isn't `UP`, re-check the Aiven
   values — almost always the cause.
6. **Note this URL** — you need it for both remaining steps.

> **Free-tier cold start.** A free Render web service sleeps after 15 minutes
> with no traffic and takes ~30–60 seconds to wake on the next request. The
> first candidate to open the exam after a quiet spell sees a slow load, not a
> broken one — it recovers on its own. It will **not** sleep mid-exam; answer
> saves and the clock poll every 20 seconds count as traffic. If this is ever
> unacceptable (a paid sitting, a strict SLA), Render's cheapest paid web
> service (~$7/mo) removes it — mentioned only because you asked to flag
> anything with a cost, not because it's needed to function.

## 3. Frontend — Render Static Site

1. **New** → **Static Site** → connect `Exam_platform_Frontend`.
2. Build command: `npm run build`. Publish directory: `dist`.
3. **Environment** tab — these are baked in at build time, so they must be set
   *before* the first build:

   | Key | Value |
   |---|---|
   | `VITE_API_URL` | the backend URL from step 2, e.g. `https://exam-backend.onrender.com` |
   | `VITE_INSTITUTION_CODE` | `ksrm-college` — must equal `SEED_ADMIN_CODE` above |
   | `VITE_ALLOW_REGISTRATION` | `false` |
   | `VITE_PLATFORM_NAME` | `KSRM Examinations` |

4. **Add Rewrite Rule**: source `/*`, destination `/index.html`, action
   `Rewrite`. Without this, refreshing `/exam` or sharing a deep link 404s —
   React Router owns those paths client-side, and the static host needs to be
   told to hand every path to `index.html` and let the app route it.
5. **Create Static Site.** Static sites on Render don't sleep and have no cold
   start — this half of the deploy is unconditionally fast.
6. **Note this URL** — e.g. `https://exam-frontend.onrender.com`.

## 4. Close the loop: point the backend at the real frontend URL

Go back to the backend service → **Environment** → update the two placeholders
from step 2:

```
CORS_ORIGINS=https://exam-frontend.onrender.com,https://ksrm.edu.in
CANDIDATE_BASE_URL=https://exam-frontend.onrender.com
```

Include **every** origin that will call the API — the exam frontend's Render
URL and the live KSRM domain both. Missing one here is the single most common
cause of "the page loads but nothing on it works" — the browser silently
blocks the request and it only shows up as a CORS error in devtools console,
never in anything a candidate sees. Save → this triggers a redeploy.

## 5. Lock the schema

Once you've confirmed exams can be created (see verification below), go back
to the backend's environment and change:

```
DDL_AUTO=validate
```

Save. From here the schema is fixed — a later deploy can't silently reshape
your tables. Use the migration files in `deploy/migrations/` for any future
schema change.

## 6. Wire up the KSRM sidebar link

This is the fix for what you're seeing right now. In the **KSRM CMS
deployment's** environment (Vercel, or wherever it's hosted — not this repo):

```
NEXT_PUBLIC_EXAM_ADMIN_URL=https://exam-frontend.onrender.com
```

Redeploy the KSRM site — `NEXT_PUBLIC_*` variables are baked in at Next.js
build time too, so editing `.env.local` alone (which is gitignored and
local-only anyway) does nothing for the live site. Once redeployed, "Online
Examinations" in the KSRM sidebar links to the real deployed exam module.

## 7. Verify

```bash
curl https://exam-backend.onrender.com/health
# {"status":"UP","db":"UP"}
```

Then in a browser: open the frontend URL → `/admin/login` → sign in with
`SEED_ADMIN_EMAIL` / `SEED_ADMIN_PASSWORD` → create an exam → confirm it
saves. Then click through from the live KSRM site's "Online Examinations" link
and confirm it lands on the same login page instead of a blank tab.

Finish with a real rehearsal — a handful of people sitting an exam start to
finish on the deployed URLs, not just an admin login check.

---

## What's different from the Hostinger path

`deploy/HOSTINGER.md` and `docker-compose.yml` describe a VPS running three
app containers behind nginx over one MySQL — useful if a much larger sitting
ever needs it. Render runs each piece as its own managed service instead:
no nginx to configure, no container orchestration, but also **only one backend
instance** (no free horizontal scaling) and the cold-start behavior above. For
a KSRM mock exam at the scale discussed, that trade is a reasonable one and
costs nothing.
