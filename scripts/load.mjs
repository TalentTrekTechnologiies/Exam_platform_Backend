/**
 * Load harness for the exam engine.
 *
 * Two things matter for exam-day load, and they're measured separately:
 *   A) START BURST  — everyone clicks Start at the same instant (slot open).
 *      Each start creates an Attempt and freezes a ~15-row paper.
 *   B) STEADY STATE — the sustained mix of answer-saves and clock-polls that
 *      runs for the whole exam. These are the hot paths that were optimised.
 *
 * Client, server and DB share one 4-core laptop, so absolute numbers are a
 * FLOOR — real infra separates them. We report req/s and latency percentiles,
 * then translate throughput into "candidates supported" using a realistic
 * per-candidate request rate.
 */
import http from "node:http";

const BASE = "http://127.0.0.1:8080";
const args = Object.fromEntries(process.argv.slice(2).map((a) => a.split("=")));
const N = Number(args.n || 500);            // virtual candidates
const DURATION = Number(args.dur || 20);    // steady-state seconds
const CANDIDATE_RPS = Number(args.rps || 0.15); // realistic steady req/s per candidate

const agent = new http.Agent({ keepAlive: true, maxSockets: N + 50, maxFreeSockets: N + 50 });

// The server clock is local (Asia/Kolkata). toISOString() would emit UTC and
// put the slot window 5.5h in the past — format LOCAL time without the Z.
const localIso = (offsetMs) => {
  const d = new Date(Date.now() + offsetMs);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
};

function req(method, path, { token, json, form } = {}) {
  return new Promise((resolve) => {
    const headers = {};
    let body = null;
    if (token) headers.Authorization = "Bearer " + token;
    if (json !== undefined) { body = JSON.stringify(json); headers["Content-Type"] = "application/json"; }
    if (form) { body = form.body; headers["Content-Type"] = form.contentType; }
    if (body) headers["Content-Length"] = Buffer.byteLength(body);

    const start = process.hrtime.bigint();
    const r = http.request(BASE + path, { method, headers, agent }, (res) => {
      let data = "";
      res.on("data", (c) => (data += c));
      res.on("end", () => {
        const ms = Number(process.hrtime.bigint() - start) / 1e6;
        let parsed = null;
        try { parsed = data ? JSON.parse(data) : null; } catch { parsed = data; }
        resolve({ status: res.statusCode, body: parsed, ms });
      });
    });
    r.on("error", (e) => resolve({ status: 0, body: String(e.message), ms: Number(process.hrtime.bigint() - start) / 1e6 }));
    if (body) r.write(body);
    r.end();
  });
}

const pct = (arr, p) => {
  if (!arr.length) return 0;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))];
};
const stats = (lat) => ({
  n: lat.length,
  p50: +pct(lat, 50).toFixed(1),
  p95: +pct(lat, 95).toFixed(1),
  p99: +pct(lat, 99).toFixed(1),
  max: +Math.max(...lat, 0).toFixed(1),
});

// Run `tasks` with a fixed worker pool of `width`.
async function pool(items, width, fn) {
  let i = 0;
  const results = [];
  const workers = Array.from({ length: Math.min(width, items.length) }, async () => {
    while (i < items.length) {
      const idx = i++;
      results[idx] = await fn(items[idx], idx);
    }
  });
  await Promise.all(workers);
  return results;
}

const multipart = (fields, file) => {
  const b = "----load" + Date.now();
  let s = "";
  for (const [k, v] of Object.entries(fields)) s += `--${b}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`;
  s += `--${b}\r\nContent-Disposition: form-data; name="file"; filename="s.csv"\r\nContent-Type: text/csv\r\n\r\n${file}\r\n--${b}--\r\n`;
  return { body: s, contentType: `multipart/form-data; boundary=${b}` };
};

async function main() {
  const tag = Math.random().toString(36).slice(2, 7);
  console.log(`\n═══ LOAD TEST · ${N} candidates · ${DURATION}s steady ═══\n`);

  // ── Setup: one exam, 15 questions, one wide slot, N candidates ────────────
  const reg = await req("POST", "/admin/register", {
    form: multipart({ collegeName: "Load Test College", email: `load${tag}@x.edu`, password: "load-password-123" }, ""),
  });
  const admin = reg.body.token;

  const exam = (await req("POST", "/admin/exam", { token: admin, json: {
    title: "Load Test Mock", duration: 180,
    startDate: localIso(-36e5),
    endDate: localIso(6*36e5),
  } })).body;

  const sec = (await req("POST", "/admin/section", { token: admin, json: { examId: exam.id, name: "General" } })).body;
  for (let q = 0; q < 15; q++) {
    await req("POST", "/admin/question", { token: admin, json: {
      examId: exam.id, sectionId: sec.id, questionText: `Load question ${q + 1}?`,
      optionA: "A", optionB: "B", optionC: "C", optionD: "D", correctAnswer: "A", marks: 4, negativeMarks: 1,
    } });
  }
  const slot = (await req("POST", "/admin/slot", { token: admin, json: {
    examId: exam.id,
    startTime: localIso(-18e5),
    endTime: localIso(5*36e5),
  } })).body;

  // Candidates cannot sit an unpublished exam, so open it before enrolling them.
  await req("POST", `/admin/exam/${exam.id}/publish`, { token: admin, json: {} });

  const halls = Array.from({ length: N }, (_, i) => `LT${tag.toUpperCase()}${i}`);
  const csv = halls.map((h, i) => `${h},Candidate ${i}`).join("\n") + "\n";
  await req("POST", "/admin/students/upload", { token: admin, form: multipart({ examId: exam.id, slotId: slot.id }, csv) });
  console.log(`  seeded exam ${exam.id} · 15 questions · ${N} candidates`);

  // prepare=1 pre-builds every paper ahead of the sitting, the way an admin
  // would the night before. Start then becomes a single row update instead of
  // an attempt insert plus a full paper freeze.
  if (args.prepare === "1") {
    const t0 = Date.now();
    const p = await req("POST", `/admin/exam/${exam.id}/prepare`, { token: admin });
    console.log(`  pre-built papers offline in ${((Date.now() - t0) / 1000).toFixed(1)}s — ${p.body?.summary ?? JSON.stringify(p.body)}`);
  }

  // ── Validate all (get student tokens) ─────────────────────────────────────
  const sessions = await pool(halls, N, async (hall, i) => {
    const v = await req("POST", "/student/validate", { json: { hallTicket: hall, name: `Candidate ${i}` } });
    return v.body?.token ? { token: v.body.token, examId: exam.id } : null;
  });
  const valid = sessions.filter(Boolean);
  console.log(`  validated ${valid.length}/${N}\n`);

  // ── PHASE A: START BURST ──────────────────────────────────────────────────
  console.log("── Phase A · start burst (everyone hits Start at once) ──");
  const burstStart = Date.now();
  const startLat = [];
  let startErr = 0;
  await pool(valid, N, async (s) => {
    const r = await req("POST", "/student/start", { token: s.token, json: { examId: s.examId } });
    startLat.push(r.ms);
    if (r.status !== 200) { startErr++; return; }
    s.attemptId = r.body.attemptId;
  });
  const burstSec = (Date.now() - burstStart) / 1000;
  const started = valid.filter((s) => s.attemptId);
  console.log(`  ${started.length} attempts frozen in ${burstSec.toFixed(2)}s  →  ${(started.length / burstSec).toFixed(0)} starts/sec`);
  console.log(`  start latency  ${JSON.stringify(stats(startLat))}  errors=${startErr}\n`);

  // Fetch each candidate's paper once (their question ids). This is itself part
  // of the start burst — every candidate pulls their paper the moment they begin.
  await pool(started, N, async (s) => {
    const p = await req("GET", `/student/paper/${s.attemptId}`, { token: s.token });
    s.qids = Array.isArray(p.body) ? p.body.map((q) => q.id) : [];
    if (!s.qids.length && !globalThis.__shown) { globalThis.__shown = 1; console.log(`  paper-fetch failure sample: status=${p.status} body=${JSON.stringify(p.body).slice(0,160)}`); }
  });

  // Only candidates who actually hold a paper can answer. Counting the rest as
  // "answer errors" would blame the server for a client-side gap.
  const armed = started.filter((s) => s.qids.length > 0);
  if (armed.length !== started.length) {
    console.log(`  ⚠ ${started.length - armed.length} paper fetches failed — excluded from steady state`);
  }

  // ── PHASE B: STEADY STATE (answer + clock, driven flat out) ───────────────
  console.log(`── Phase B · steady state · ${DURATION}s at concurrency ${armed.length} ──`);
  const lat = { answer: [], remaining: [] };
  let ok = 0, err = 0;
  const deadline = Date.now() + DURATION * 1000;

  // pace=1 spaces each candidate's requests to real exam pacing (~1 every
  // 1/CANDIDATE_RPS seconds), so the latency measured is what a candidate would
  // actually experience. Without it, workers fire flat-out and the number
  // reflects deliberate over-subscription, not the real ceiling.
  const paced = args.pace === "1";
  const gapMs = 1000 / CANDIDATE_RPS;

  await Promise.all(armed.map(async (s) => {
    let n = 0;
    // Stagger starts across the gap so paced load is smooth, not lock-stepped.
    if (paced) await new Promise((r) => setTimeout(r, Math.random() * gapMs));
    while (Date.now() < deadline) {
      const t0 = Date.now();
      if (n % 5 === 4) {
        const r = await req("GET", `/student/remaining/${s.attemptId}`, { token: s.token });
        lat.remaining.push(r.ms);
        r.status === 200 ? ok++ : err++;
      } else {
        const qid = s.qids[n % s.qids.length];
        const opt = ["A", "B", "C", "D"][n % 4];
        const r = await req("POST", "/student/answer", { token: s.token, json: { attemptId: s.attemptId, questionId: qid, selectedOption: opt } });
        lat.answer.push(r.ms);
        r.status === 200 ? ok++ : err++;
      }
      n++;
      if (paced) {
        const wait = gapMs - (Date.now() - t0);
        if (wait > 0) await new Promise((r) => setTimeout(r, wait));
      }
    }
  }));

  const total = ok + err;
  const rps = total / DURATION;
  console.log(`  requests ${total}  →  ${rps.toFixed(0)} req/sec   errors=${err} (${((err / total) * 100).toFixed(2)}%)`);
  console.log(`  answer    ${JSON.stringify(stats(lat.answer))}`);
  console.log(`  remaining ${JSON.stringify(stats(lat.remaining))}`);

  // ── Translate throughput → candidates supported ───────────────────────────
  const supported = Math.round(rps / CANDIDATE_RPS);
  console.log(`\n── Capacity translation ──`);
  console.log(`  sustained ${rps.toFixed(0)} req/s at ~${CANDIDATE_RPS} req/s per candidate (real exam pacing)`);
  console.log(`  →  ~${supported.toLocaleString()} concurrent candidates per instance at this DB`);
  console.log(`     (behind a load balancer, multiply by instance count until the DB is the limit)\n`);

  process.exit(0);
}

main().catch((e) => { console.error(e); process.exit(1); });
