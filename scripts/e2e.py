"""End-to-end check of the exam engine against a live backend."""
import json, urllib.request, urllib.error, datetime, uuid, sys

BASE = "http://localhost:8080"
failures = []

def call(method, path, body=None, files=None, token=None):
    url = BASE + path
    if files is not None:
        boundary = "----b" + uuid.uuid4().hex
        parts = []
        for k, v in (body or {}).items():
            parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n")
        for k, (fname, content) in files.items():
            parts.append(
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"; filename=\"{fname}\"\r\n"
                f"Content-Type: text/csv\r\n\r\n{content}\r\n")
        parts.append(f"--{boundary}--\r\n")
        data = "".join(parts).encode()
        headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
        if token: headers["Authorization"] = "Bearer " + token
    else:
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json"}
        if token: headers["Authorization"] = "Bearer " + token

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read().decode()
            try: return r.status, json.loads(raw)
            except json.JSONDecodeError: return r.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try: return e.code, json.loads(raw)
        except json.JSONDecodeError: return e.code, raw

def check(label, ok, detail=""):
    print(("  PASS  " if ok else "  FAIL  ") + label + (f"  -> {detail}" if detail and not ok else ""))
    if not ok: failures.append(label)

now = datetime.datetime.now()
fmt = lambda d: d.strftime("%Y-%m-%dT%H:%M:%S")
tag = uuid.uuid4().hex[:6]

_, _admin = call("POST", "/admin/register", {
    "collegeName": "E2E Institute", "email": f"e2e{tag}@x.edu",
    "password": "e2e-password-123"}, files={})
ADMIN = _admin["token"]

print("\n=== SETUP ===")
_, exam = call("POST", "/admin/exam", token=ADMIN, body={
    "collegeName": "E2E Institute of Technology",
    "title": f"EAMCET Mock {tag}",
    "duration": 60,
    "startDate": fmt(now - datetime.timedelta(hours=1)),
    "endDate": fmt(now + datetime.timedelta(hours=3)),
    "enableCamera": False, "enableMic": False,
})
exam_id = exam["id"]
print(f"  exam {exam_id}")

sections = {}
for name in ("Physics", "Chemistry"):
    _, s = call("POST", "/admin/section", token=ADMIN, body={"examId": exam_id, "name": name, "totalMarks": 8})
    sections[name] = s["id"]
print(f"  sections {sections}")

# 4 marks correct, 1 mark penalty -> EAMCET/NEET-style marking.
spec = [
    ("Physics",   "A unit of force, with commas, is?", "newton", "pascal", "joule", "watt", "A"),
    ("Physics",   "Acceleration due to gravity?",      "9.8",    "8.9",    "10.8",  "7.8",  "A"),
    ("Chemistry", "Atomic number of Carbon?",          "6",      "12",     "8",     "14",   "A"),
    ("Chemistry", "pH of pure water at 25C?",          "7",      "1",      "14",    "0",    "A"),
]
qids = []
for sec, text, a, b, c, d, correct in spec:
    _, q = call("POST", "/admin/question", token=ADMIN, body={
        "examId": exam_id, "sectionId": sections[sec], "questionText": text,
        "optionA": a, "optionB": b, "optionC": c, "optionD": d,
        "correctAnswer": correct, "marks": 4, "negativeMarks": 1.0,
    })
    qids.append(q["id"])
print(f"  questions {qids}")

_, slot = call("POST", "/admin/slot", token=ADMIN, body={
    "examId": exam_id,
    "startTime": fmt(now - datetime.timedelta(minutes=30)),
    "endTime": fmt(now + datetime.timedelta(hours=2)),
})
slot_id = slot["id"]
call("POST", f"/admin/exam/{exam_id}/publish", token=ADMIN, body={})

hall = f"HT{tag.upper()}"
status, _ = call("POST", "/admin/students/upload",
                 {"examId": exam_id, "slotId": slot_id},
                 {"file": ("students.csv", f"{hall},Asha Rao\n")}, token=ADMIN)
print(f"  slot {slot_id}, student upload -> {status}")

print("\n=== VALIDATION & START ===")
status, v = call("POST", "/student/validate", {"hallTicket": hall, "name": "Asha Rao"})
check("hall ticket validates inside the slot window", status == 200 and v.get("status") == "Allowed", v)
student_id = v["studentId"]
STU = v["token"]

status, wrong = call("POST", "/student/validate", {"hallTicket": hall, "name": "Not The Candidate"})
check("wrong name is rejected", status != 200, wrong)

status, att = call("POST", "/student/start", {"examId": exam_id}, token=STU)
check("attempt starts", status == 200 and "attemptId" in att, att)
attempt_id = att["attemptId"]

_, again = call("POST", "/student/start", {"examId": exam_id}, token=STU)
check("re-start is idempotent (no duplicate attempt)", again.get("attemptId") == attempt_id,
      f"{again.get('attemptId')} != {attempt_id}")

print("\n=== THE ANSWER-KEY LEAK ===")
status, paper = call("GET", f"/student/paper/{attempt_id}", token=STU)
check("paper returns all questions", status == 200 and len(paper) == 4, f"got {len(paper) if isinstance(paper, list) else paper}")

blob = json.dumps(paper).lower()
check("no 'correctindex' in student paper", "correctindex" not in blob)
check("no 'correctanswer' in student paper", "correctanswer" not in blob)
check("no 'iscorrect' in student paper", "iscorrect" not in blob)

opt_keys = set()
for q in paper:
    for o in q["options"]:
        opt_keys |= set(o.keys())
check("options expose only id/text/image", opt_keys <= {"id", "text", "image"}, opt_keys)
check("marking scheme visible to candidate", all(q["marks"] == 4 and q["negativeMarks"] == 1.0 for q in paper))
check("sections labelled on paper", {q["sectionName"] for q in paper} == {"Physics", "Chemistry"},
      {q["sectionName"] for q in paper})

print("\n=== PAPER STABILITY ===")
_, paper2 = call("GET", f"/student/paper/{attempt_id}", token=STU)
same_q = [q["id"] for q in paper] == [q["id"] for q in paper2]
same_o = [[o["id"] for o in q["options"]] for q in paper] == [[o["id"] for o in q["options"]] for q in paper2]
check("question order stable across refetch", same_q)
check("option order stable across refetch", same_o)

print("\n=== ANSWERING ===")
# Two right, one wrong, one skipped -> 4+4-1 = 7 of 16.
by_id = {q["id"]: q for q in paper}
call("POST", "/student/answer", {"attemptId": attempt_id, "questionId": qids[0], "selectedOption": "A"}, token=STU)
call("POST", "/student/answer", {"attemptId": attempt_id, "questionId": qids[1], "selectedOption": "A"}, token=STU)
call("POST", "/student/answer", {"attemptId": attempt_id, "questionId": qids[2], "selectedOption": "B"}, token=STU)

status, resp = call("GET", f"/student/responses/{attempt_id}", token=STU)
check("saved responses replay for resume", status == 200 and len(resp) == 3, resp)

status, bad = call("POST", "/student/answer",
                   {"attemptId": attempt_id, "questionId": qids[0], "selectedOption": "Z"}, token=STU)
check("invalid option letter rejected", status == 400, f"{status} {bad}")

status, foreign = call("POST", "/student/answer",
                       {"attemptId": attempt_id, "questionId": 999999, "selectedOption": "A"}, token=STU)
check("answer for a question not on the paper rejected", status == 400, f"{status} {foreign}")

status, early = call("GET", f"/student/result/{attempt_id}", token=STU)
check("result withheld while exam is live", status == 409, f"{status} {early}")

print("\n=== SUBMIT & SCORE ===")
status, sub = call("POST", f"/student/submit/{attempt_id}", token=STU)
check("submit succeeds", status == 200 and sub.get("submitted"), sub)

status, resub = call("POST", f"/student/submit/{attempt_id}", token=STU)
check("double submit is idempotent", status == 200, f"{status} {resub}")

status, blocked = call("POST", "/student/answer",
                       {"attemptId": attempt_id, "questionId": qids[3], "selectedOption": "A"}, token=STU)
check("answers rejected after submission", status == 409, f"{status} {blocked}")

status, r = call("GET", f"/student/result/{attempt_id}", token=STU)
check("result available after submit", status == 200, r)

check(f"score is 7.0 (2 correct x4, 1 wrong -1, 1 skipped)  [got {r.get('score')}]", r.get("score") == 7.0)
check(f"maxScore is 16.0  [got {r.get('maxScore')}]", r.get("maxScore") == 16.0)
check(f"correct=2  [got {r.get('correct')}]", r.get("correct") == 2)
check(f"incorrect=1  [got {r.get('incorrect')}]", r.get("incorrect") == 1)
check(f"unanswered=1  [got {r.get('unanswered')}]", r.get("unanswered") == 1)
check("section breakdown present", len(r.get("sections") or []) == 2, r.get("sections"))
check("response sheet reveals the key after submit",
      all("correctAnswer" in q for q in (r.get("questions") or [])))
check("candidate identity on scorecard", r.get("hallTicket") == hall, r.get("hallTicket"))

print("\n=== RE-ENTRY ===")
status, back = call("POST", "/student/validate", {"hallTicket": hall, "name": "Asha Rao"})
check("submitted candidate cannot re-enter", status != 200, back)

print("\n=== CSV ROBUSTNESS ===")
csv = (
    'questionText,optionA,optionB,optionC,optionD,correctAnswer,marks,negativeMarks,section\n'
    '"If x = 2, find y, given y = x + 1","3","4","5","6",A,4,1,Maths\n'
    '"Valid second row","1","2","3","4",B,4,1,Maths\n'
    'Broken row with too few columns\n'
    '"Bad key row","1","2","3","4",Z,4,1,Maths\n'
)
status, rep = call("POST", "/admin/question/upload", {"examId": exam_id}, {"file": ("q.csv", csv)}, token=ADMIN)
check("header row skipped, quoted commas survive, bad rows reported",
      status == 200 and rep.get("saved") == 2 and rep.get("skipped") == 2,
      rep)
check("rejection reasons returned to admin", len(rep.get("errors") or []) == 2, rep.get("errors"))

print("\n" + "=" * 60)
if failures:
    print(f"FAILED: {len(failures)}")
    for f in failures: print("  - " + f)
    sys.exit(1)
print("ALL CHECKS PASSED")
