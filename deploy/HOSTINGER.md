# Deploying on Hostinger — KSRM plus other institutions

Written for the exact setup you described: KSRM runs its own exams, and other
colleges register and use the same platform as a service.

---

## The one thing to know first

**hPanel shared hosting cannot run this backend.** It serves PHP and static
files; there is no JVM. Spring Boot and MySQL need a **VPS**.

That splits the deployment in two, and both halves are straightforward:

| Piece | Runs on | Notes |
|---|---|---|
| React build (`dist/`) | hPanel **or** the VPS | Static files. Either works. |
| Spring Boot + MySQL | **VPS only** | Docker Compose, already written. |

Serving both from the VPS is simpler — one origin, no CORS, no mixed-content
traps. Use hPanel for the frontend only if the marketing site already lives there
and you want the exam app under the same domain.

### The server is the only cost — and it can be free

Every piece of software here is free and open-source: MySQL Community, Docker,
nginx, and all the Java libraries. Email is free too (a college mail account or
Brevo's free tier). The one expense is a machine to run Java + MySQL around the
clock, and even that has a genuinely free option:

| Where | Cost | Good for |
|---|---|---|
| **Oracle Cloud "Always Free"** | **₹0 forever** — 4 ARM cores, 24 GB RAM | Real exams up to a few thousand candidates. The most machine anyone gives away free. |
| Hostinger VPS (KVM 2) | ~₹500–800/mo | If you prefer Hostinger's panel and support. |
| Any Ubuntu + Docker VPS | varies | The steps below are identical everywhere. |

Oracle's free tier is more than enough for KSRM. The deployment steps are the
same on it as on any Ubuntu box — install Docker, clone, `docker compose up`.
The only catch is Oracle sometimes reports "out of capacity" for free ARM
instances in a busy region; retry, or pick a different availability domain.

> hPanel shared hosting still cannot run the backend anywhere — it has no JVM.
> The free option is a *VPS-class* free tier, not shared hosting.

---

## 1. Prepare the VPS

Hostinger VPS → **Ubuntu 22.04 with Docker** template. Then:

```bash
ssh root@YOUR_VPS_IP

# Docker ships with the template; verify.
docker --version && docker compose version

# Basic firewall: SSH and web only. MySQL stays unreachable from outside.
ufw allow OpenSSH && ufw allow 80 && ufw allow 443 && ufw enable
```

Sizing: 2 vCPU / 4 GB carries a few hundred concurrent candidates comfortably.
For a 5,000-seat sitting use **4 vCPU / 8 GB or better**, and give MySQL its own
VPS if you can — that was the single biggest factor in the load tests.

## 2. Deploy the backend

```bash
git clone <your-backend-repo> /opt/exam
cd /opt/exam/deploy

cp .env.example .env
nano .env
```

Set, at minimum:

```bash
JWT_SECRET=<paste output of: openssl rand -base64 48>
DB_PASSWORD=<a real password>
CORS_ORIGINS=https://exams.ksrm.edu.in,https://ksrm.exams.yourdomain.com
DDL_AUTO=update      # first boot only; switch to validate afterwards
```

> The app now **refuses to start** on the default development secret outside a
> dev profile, so a forgotten `JWT_SECRET` fails loudly instead of shipping an
> open door.

```bash
docker compose up -d --build
docker compose logs -f app1        # wait for "Started ExamSystemApplication"
curl localhost/health              # {"status":"UP","db":"UP"}
```

Then set `DDL_AUTO=validate` in `.env` and `docker compose up -d` again, so the
schema can never be reshaped silently on a later deploy.

### Upgrading an existing database

If you already ran an older build, apply the migration **with the app stopped**:

```bash
docker compose stop app1 app2 app3
docker compose exec -T db mysql -uroot -p"$DB_PASSWORD" exam_system \
  < migrations/001_scope_students_to_institution.sql
docker compose start app1 app2 app3
```

That migration is what makes two colleges able to use the same roll numbers. It
must run — `ddl-auto` cannot drop the old platform-wide index by itself.

## 3. DNS

Point these at the VPS IP (Hostinger → DNS Zone, `A` records):

```
exams.ksrm.edu.in     A    YOUR_VPS_IP     # KSRM's own entrance
*.exams.yourdomain    A    YOUR_VPS_IP     # wildcard: every other college
```

The wildcard is what gives each registering institution its own entrance —
`stanley.exams.yourdomain.com` — without a DNS change per customer.

## 4. TLS

```bash
apt install certbot python3-certbot-nginx -y
certbot --nginx -d exams.ksrm.edu.in
```

For the wildcard you need a DNS-01 challenge:

```bash
certbot certonly --manual --preferred-challenges dns \
  -d "*.exams.yourdomain.com" -d exams.yourdomain.com
```

**HTTPS is not optional.** If the KSRM site is `https://` and the API is
`http://`, browsers block every request as mixed content and the exam simply
will not load.

## 5. Build and deploy the frontend

### For KSRM (dedicated entrance)

```bash
# .env.production
VITE_API_URL=https://exams.ksrm.edu.in
VITE_INSTITUTION_CODE=ksrm-college     # the code shown when KSRM registered
```

```bash
npm run build      # -> dist/
```

Pinning `VITE_INSTITUTION_CODE` means KSRM candidates type only their hall ticket
and name; the page already knows the college and shows its logo.

### For the shared platform (other colleges)

```bash
VITE_API_URL=https://exams.yourdomain.com
# VITE_INSTITUTION_CODE deliberately unset
```

With it unset the app reads the institution from the **subdomain**, so one build
serves every college.

### Uploading to hPanel (if hosting the frontend there)

Upload the contents of `dist/` to `public_html/`, then add `.htaccess` **in the
same folder**:

```apache
# React Router owns the routing. Without this, refreshing /exam or sharing a
# deep link returns Apache's 404 instead of the app.
<IfModule mod_rewrite.c>
  RewriteEngine On
  RewriteBase /
  RewriteRule ^index\.html$ - [L]
  RewriteCond %{REQUEST_FILENAME} !-f
  RewriteCond %{REQUEST_FILENAME} !-d
  RewriteRule . /index.html [L]
</IfModule>

# Hashed asset filenames are safe to cache hard; index.html must not be.
<IfModule mod_headers.c>
  <FilesMatch "\.(js|css|woff2|png|jpg|svg)$">
    Header set Cache-Control "public, max-age=31536000, immutable"
  </FilesMatch>
  <FilesMatch "index\.html$">
    Header set Cache-Control "no-cache"
  </FilesMatch>
</IfModule>
```

---

## 6. Integrating with the existing KSRM website

Pick whichever matches how the site is built:

**A subdomain — recommended.** `exams.ksrm.edu.in` is fully separate from the
main site, so an exam in progress cannot be affected by a CMS update, a plugin,
or a marketing deploy. On exam day that isolation is worth more than tidiness.
Link to it from the KSRM menu as "Online Examinations".

**A subdirectory** — `ksrm.edu.in/exams`. Keeps one domain, but the exam app now
shares a web server with the main site and needs `base: '/exams/'` in
`vite.config.js` plus a matching `basename` on the router. More moving parts, and
the main site's traffic and the exam's traffic compete.

Either way the **backend stays on the VPS**. Only the static frontend can live
beside the marketing site.

---

## 7. Onboarding a college

1. They register at `/admin/register`.
2. Registration returns their **institution code** (e.g. `stanley-college`),
   derived from the name and guaranteed unique.
3. Their candidate entrance is `https://<code>.exams.yourdomain.com`.
4. They create an exam, sections, questions, a slot, and upload candidates.
5. **The night before, they run Prepare Papers** —
   `POST /admin/exam/{id}/prepare`. This is what keeps the start burst calm when
   everyone clicks Start at once.

---

## 8. Before the first real exam

- [ ] `JWT_SECRET` and `DB_PASSWORD` are real values, not the examples.
- [ ] `DDL_AUTO=validate`.
- [ ] HTTPS works on every entrance, including the wildcard.
- [ ] The migration has been applied and `SHOW INDEX FROM students` no longer
      lists a bare `hall_ticket` unique index.
- [ ] **Automated database backups are running.** One MySQL is still a single
      point of failure — see below.
- [ ] A full rehearsal: create an exam, enrol 20 real candidates, have them sit
      it end to end on lab machines. Nothing substitutes for this.

```bash
# Nightly dump, kept 14 days.
echo '0 2 * * * docker compose -f /opt/exam/deploy/docker-compose.yml exec -T db \
  mysqldump -uroot -p"$DB_PASSWORD" exam_system | gzip > /opt/backups/exam-$(date +\%F).sql.gz' \
  | crontab -
```

---

## Still missing before a high-stakes sitting

Stated plainly:

- **No candidate self-registration and no email delivery.** Candidates are
  enrolled by uploading a roster; hall tickets are handed out by the exam
  officer. There is no sign-up form and the server cannot send mail at all.
- **No sectional hard-lock**, if you want true TCS NQT format.
- **The replica is opt-in.** `docker-compose.ha.yml` and `FAILOVER.md` exist, but
  the default `docker-compose.yml` runs a single MySQL. If you deploy the default
  stack, a MySQL failure still ends the sitting.

Resolved since this document was first written: there **is** a live invigilator
screen (Live Monitor — connected / disconnected / submitted, with violation
reasons), and there **is** a database replica option (`docker-compose.ha.yml`).

Capacity itself is not the concern — 5,000 concurrent candidates was measured
with zero errors on a single machine.
