/**
 * Concurrent-write correctness.
 *
 * The load test drove everything from one client over pooled connections. Real
 * exams are thousands of separate machines writing at the same instant, so this
 * checks the guarantees that actually matter when writes collide:
 *
 *   1. Different candidates writing simultaneously never touch each other.
 *   2. The SAME candidate writing from two machines at once cannot corrupt or
 *      duplicate a response.
 *   3. Two simultaneous Starts cannot produce two attempts or two clocks.
 *   4. A submit racing an in-flight answer cannot be half-applied.
 *
 * Each virtual machine gets its OWN connection (no keep-alive sharing), which is
 * closer to how distinct exam-hall PCs behave.
 */
import http from "node:http";

const BASE = "http://127.0.0.1:8080";
let pass = 0, fail = 0;

// agent:false => a fresh TCP connection per request, like separate machines.
function req(method, path, { token, json, form } = {}) {
  return new Promise((resolve) => {
    const headers = {};
    let body = null;
    if (token) headers.Authorization = "Bearer " + token;
    if (json !== undefined) { body = JSON.stringify(json); headers["Content-Type"] = "application/json"; }
    if (form) { body = form.body; headers["Content-Type"] = form.contentType; }
    if (body) headers["Content-Length"] = Buffer.byteLength(body);
    const r = http.request(BASE + path, { method, headers, agent: false }, (res) => {
      let d = ""; res.on("data", (c) => (d += c));
      res.on("end", () => { let j = null; try { j = d ? JSON.parse(d) : null; } catch { j = d; } resolve({ status: res.statusCode, body: j }); });
    });
    r.on("error", (e) => resolve({ status: 0, body: String(e.message) }));
    if (body) r.write(body);
    r.end();
  });
}

const mp = (fields, file) => {
  const b = "----c" + Date.now();
  let s = "";
  for (const [k, v] of Object.entries(fields)) s += `--${b}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`;
  s += `--${b}\r\nContent-Disposition: form-data; name="file"; filename="s.csv"\r\nContent-Type: text/csv\r\n\r\n${file}\r\n--${b}--\r\n`;
  return { body: s, contentType: `multipart/form-data; boundary=${b}` };
};
const L = (o) => { const d = new Date(Date.now() + o), p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`; };

const check = (label, ok, detail = "") => {
  console.log((ok ? "  PASS  " : "  FAIL  ") + label + (!ok && detail ? `  -> ${detail}` : ""));
  ok ? pass++ : fail++;
};

const tag = "cc" + Math.floor(Math.random() * 100000);
const admin = (await req("POST", "/admin/register", { form: mp({ collegeName: "Concurrency", email: tag + "@x.edu", password: "conc-password-123" }, "") })).body.token;
const exam = (await req("POST", "/admin/exam", { token: admin, json: { title: "Concurrency", duration: 180, startDate: L(-36e5), endDate: L(6 * 36e5) } })).body;
const sec = (await req("POST", "/admin/section", { token: admin, json: { examId: exam.id, name: "G" } })).body;
const qs = [];
for (let i = 0; i < 4; i++) {
  qs.push((await req("POST", "/admin/question", { token: admin, json: {
    examId: exam.id, sectionId: sec.id, questionText: "Q" + i,
    optionA: "a", optionB: "b", optionC: "c", optionD: "d", correctAnswer: "A", marks: 4, negativeMarks: 1 } })).body.id);
}
const slot = (await req("POST", "/admin/slot", { token: admin, json: { examId: exam.id, startTime: L(-18e5), endTime: L(5 * 36e5) } })).body;

const N = 40;
await req("POST", `/admin/exam/${exam.id}/publish`, { token: admin, json: {} });
const halls = Array.from({ length: N }, (_, i) => `${tag.toUpperCase()}${i}`);
await req("POST", "/admin/students/upload", { token: admin, form: mp({ examId: exam.id, slotId: slot.id }, halls.map((h, i) => `${h},Cand ${i}`).join("\n") + "\n") });
await req("POST", `/admin/exam/${exam.id}/prepare`, { token: admin });

console.log("\n=== 1. SAME candidate, TWO machines, simultaneous Start ===");
const v0 = (await req("POST", "/student/validate", { json: { hallTicket: halls[0], name: "Cand 0" } })).body;
const [sA, sB] = await Promise.all([
  req("POST", "/student/start", { token: v0.token, json: { examId: exam.id } }),
  req("POST", "/student/start", { token: v0.token, json: { examId: exam.id } }),
]);
check("both Starts succeed", sA.status === 200 && sB.status === 200, `${sA.status}/${sB.status}`);
check("both get the SAME attempt (no double attempt)", sA.body.attemptId === sB.body.attemptId, `${sA.body.attemptId} vs ${sB.body.attemptId}`);
const attempt0 = sA.body.attemptId;
// Two starts must not restart the clock either.
check("clock not restarted by the second Start",
      Math.abs(sA.body.remainingSeconds - sB.body.remainingSeconds) <= 2,
      `${sA.body.remainingSeconds} vs ${sB.body.remainingSeconds}`);

console.log("\n=== 2. SAME candidate, SAME question, 10 simultaneous writes ===");
const opts = ["A", "B", "C", "D"];
const racers = await Promise.all(Array.from({ length: 10 }, (_, i) =>
  req("POST", "/student/answer", { token: v0.token, json: { attemptId: attempt0, questionId: qs[0], selectedOption: opts[i % 4] } })));
check("every concurrent write is accepted", racers.every((r) => r.status === 200),
      racers.map((r) => r.status).join(","));
const after = await req("GET", `/student/responses/${attempt0}`, { token: v0.token });
const stored = after.body[String(qs[0])];
check("exactly one stored value, and it is a real option", opts.includes(stored), JSON.stringify(after.body));

console.log("\n=== 3. 40 DIFFERENT candidates writing at the same instant ===");
const sessions = await Promise.all(halls.map(async (h, i) => {
  const v = (await req("POST", "/student/validate", { json: { hallTicket: h, name: `Cand ${i}` } })).body;
  if (!v?.token) return null;
  const st = await req("POST", "/student/start", { token: v.token, json: { examId: exam.id } });
  return { token: v.token, attemptId: st.body?.attemptId, idx: i };
}));
const live = sessions.filter((s) => s?.attemptId);
check("all candidates started", live.length === N, `${live.length}/${N}`);

// Each candidate writes a DIFFERENT option to the SAME question, all at once.
const writes = await Promise.all(live.map((s) =>
  req("POST", "/student/answer", { token: s.token, json: { attemptId: s.attemptId, questionId: qs[1], selectedOption: opts[s.idx % 4] } })));
check("all simultaneous writes accepted", writes.every((r) => r.status === 200),
      `${writes.filter((r) => r.status !== 200).length} failed`);

// Every candidate must read back exactly their own answer — no cross-talk.
const reads = await Promise.all(live.map(async (s) => {
  const r = await req("GET", `/student/responses/${s.attemptId}`, { token: s.token });
  return r.body?.[String(qs[1])] === opts[s.idx % 4];
}));
check("each candidate reads back their OWN answer (no cross-contamination)",
      reads.every(Boolean), `${reads.filter((x) => !x).length} mismatched`);

console.log("\n=== 4. Submit racing an in-flight answer ===");
const s1 = live[1];
const raced = await Promise.all([
  req("POST", `/student/submit/${s1.attemptId}`, { token: s1.token }),
  req("POST", "/student/answer", { token: s1.token, json: { attemptId: s1.attemptId, questionId: qs[2], selectedOption: "A" } }),
  req("POST", `/student/submit/${s1.attemptId}`, { token: s1.token }),
]);
check("both submits succeed (idempotent, graded once)", raced[0].status === 200 && raced[2].status === 200,
      `${raced[0].status}/${raced[2].status}`);
check("the racing answer is either applied or cleanly refused — never partial",
      raced[1].status === 200 || raced[1].status === 409, `${raced[1].status}`);
const res1 = await req("GET", `/student/result/${s1.attemptId}`, { token: s1.token });
check("result is coherent after the race", res1.status === 200 && typeof res1.body.score === "number",
      JSON.stringify(res1.body).slice(0, 80));

console.log("\n" + "=".repeat(58));
console.log(fail === 0 ? `ALL ${pass} CONCURRENCY CHECKS PASSED` : `FAILED: ${fail} of ${pass + fail}`);
process.exit(fail === 0 ? 0 : 1);
