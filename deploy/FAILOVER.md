# Database resilience and failover

The single MySQL was the one component whose failure ends a sitting in progress.
This is how to remove that, and what to do at 11:40 on exam morning if it happens
anyway.

---

## Start the replica

```bash
cd deploy
echo "REPLICA_PASSWORD=$(openssl rand -base64 24)" >> .env
docker compose -f docker-compose.yml -f docker-compose.ha.yml up -d
```

Then wire the replica to the primary, once:

```bash
# 1. On the PRIMARY: create the replication account.
docker compose exec db mysql -uroot -p"$DB_PASSWORD" -e "
  CREATE USER IF NOT EXISTS 'replica'@'%' IDENTIFIED WITH caching_sha2_password BY '$REPLICA_PASSWORD';
  GRANT REPLICATION SLAVE ON *.* TO 'replica'@'%';
  FLUSH PRIVILEGES;"

# 2. Seed the replica with a consistent snapshot.
docker compose exec db mysqldump -uroot -p"$DB_PASSWORD" \
  --single-transaction --set-gtid-purged=ON --databases exam_system \
  > /tmp/seed.sql
docker compose exec -T db-replica mysql -uroot -p"$DB_PASSWORD" < /tmp/seed.sql

# 3. Point it at the primary and start.
docker compose exec db-replica mysql -uroot -p"$DB_PASSWORD" -e "
  CHANGE REPLICATION SOURCE TO
    SOURCE_HOST='db', SOURCE_USER='replica',
    SOURCE_PASSWORD='$REPLICA_PASSWORD', SOURCE_AUTO_POSITION=1;
  START REPLICA;"
```

## Check it every exam morning

```bash
docker compose exec db-replica mysql -uroot -p"$DB_PASSWORD" \
  -e "SHOW REPLICA STATUS\G" | grep -E "Replica_IO_Running|Replica_SQL_Running|Seconds_Behind_Source|Last_Error"
```

You want:

```
Replica_IO_Running: Yes
Replica_SQL_Running: Yes
Seconds_Behind_Source: 0
```

**A replica nobody checks is not a replica.** Put this in the pre-exam checklist
next to "confirm the projector works".

---

## If the primary dies mid-exam

Candidates are not lost the instant the database goes: answers already queue in
the browser and retry, so a few minutes of downtime is recoverable if you act.
Work in this order.

### 1. Confirm it is actually the database (30 seconds)

```bash
curl -s localhost/health          # {"status":"DOWN","db":"DOWN"} means the DB
docker compose ps                 # is the container up?
docker compose logs --tail=50 db
```

If it is the app tier instead, `docker compose restart app1 app2 app3` — the
exam continues, because attempts and answers live in the database, not in the
app's memory.

### 2. Try the cheap fix first

```bash
docker compose restart db
```

A restart that takes 30 seconds is far less disruptive than a promotion. Give it
one attempt.

### 3. Promote the replica

Only if the primary will not come back:

```bash
# Stop replication and make it writable.
docker compose exec db-replica mysql -uroot -p"$DB_PASSWORD" -e "
  STOP REPLICA; RESET REPLICA ALL; SET GLOBAL read_only=OFF;"

# Point the app tier at it.
#   In .env:  DB_URL=jdbc:mysql://db-replica:3306/exam_system?useSSL=false&serverTimezone=Asia/Kolkata
docker compose up -d app1 app2 app3
curl -s localhost/health
```

Candidates whose browsers queued answers during the outage will sync them
automatically. **Extend the slot end time** so nobody loses the minutes they
spent staring at a connection banner.

### 4. Afterwards

The old primary must NOT be restarted as a primary — two writable copies diverge
and you will not be able to reconcile which answers are real. Rebuild it as a
fresh replica of the promoted node.

---

## What this does and does not buy you

| | |
|---|---|
| Primary process crashes | Restart, ~30s, no data loss |
| Primary disk fails | Promote replica, minutes, seconds of data at risk |
| Both fail | Restore from nightly dump — the sitting is lost |
| Someone drops a table | Replica faithfully drops it too. **Only backups help.** |

That last row is the one people forget: replication copies mistakes as
faithfully as it copies data. Keep the nightly dumps running regardless.

---

## Rehearse it once

Before trusting any of this, run the drill on a quiet afternoon with a test exam
and a handful of candidates:

1. Start a sitting, answer a few questions.
2. `docker compose stop db`.
3. Watch the candidate screens — they should show the connection banner and keep
   accepting answers, not crash.
4. Promote the replica using the steps above.
5. Confirm the queued answers land and the exam finishes cleanly.

A failover procedure nobody has practised is a document, not a plan.
