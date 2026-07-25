"""Phase 2 verification: authentication, tenant isolation, and the attempt IDOR."""
import json, urllib.request, urllib.error, datetime, uuid, sys

BASE = "http://localhost:8080"
failures = []

def call(method, path, body=None, files=None, token=None):
    url = BASE + path
    headers = {}
    if token: headers["Authorization"] = "Bearer " + token

    if files is not None:
        boundary = "----b" + uuid.uuid4().hex
        parts = []
        for k, v in (body or {}).items():
            parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n")
        for k, (fname, content, ctype) in files.items():
            parts.append(
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"; filename=\"{fname}\"\r\n"
                f"Content-Type: {ctype}\r\n\r\n{content}\r\n")
        parts.append(f"--{boundary}--\r\n")
        data = "".join(parts).encode()
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    elif body is not None and method != "GET":
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    else:
        data = None

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
tagA, tagB = uuid.uuid4().hex[:6], uuid.uuid4().hex[:6]

print("\n=== UNAUTHENTICATED ACCESS ===")
for method, path in [("GET", "/admin/question/1"), ("GET", "/admin/students"),
                     ("POST", "/admin/exam"), ("GET", "/admin/exam"),
                     ("GET", "/student/paper/1"), ("GET", "/student/result/1")]:
    status, _ = call(method, path, {} if method == "POST" else None)
    check(f"{method} {path} requires a token", status in (401, 403), f"got {status}")

status, _ = call("GET", "/admin/question/all")
check("the old unauthenticated dump endpoint is gone", status in (401, 403, 404), f"got {status}")

print("\n=== REGISTRATION & PASSWORD HANDLING ===")
status, weak = call("POST", "/admin/register", {
    "collegeName": "Weak Co", "email": f"weak{tagA}@x.edu", "password": "short"}, files={})
check("short passwords rejected", status == 400, f"{status} {weak}")

status, a = call("POST", "/admin/register", {
    "collegeName": "Alpha Institute", "email": f"alpha{tagA}@x.edu",
    "password": "alpha-password-1", "collegeAddress": "Hyderabad"}, files={})
check("institution A registers", status == 200 and a.get("token"), a)
check("register response contains no password", "password" not in json.dumps(a).lower(), a)
tokenA = a["token"]

status, b = call("POST", "/admin/register", {
    "collegeName": "Beta College", "email": f"beta{tagB}@x.edu",
    "password": "beta-password-1", "collegeAddress": "Chennai"}, files={})
tokenB = b["token"]
check("institution B registers", status == 200 and b.get("token"))

status, dup = call("POST", "/admin/register", {
    "collegeName": "Dupe", "email": f"alpha{tagA}@x.edu", "password": "another-password"}, files={})
check("duplicate email rejected", status == 409, f"{status} {dup}")

status, login = call("POST", "/admin/login", {"email": f"alpha{tagA}@x.edu", "password": "alpha-password-1"})
check("login with correct password works", status == 200 and login.get("token"), login)
check("login response contains no password", "password" not in json.dumps(login).lower())

status, bad = call("POST", "/admin/login", {"email": f"alpha{tagA}@x.edu", "password": "wrong"})
check("wrong password rejected", status == 401, f"{status} {bad}")

status, ghost = call("POST", "/admin/login", {"email": "nobody@nowhere.edu", "password": "whatever"})
check("unknown account gives the same message (no enumeration)",
      status == 401 and ghost.get("message") == bad.get("message"), f"{ghost} vs {bad}")

status, forged = call("GET", "/admin/exam", token="not.a.real.token")
check("forged token rejected", status in (401, 403), f"got {status}")

print("\n=== TENANT ISOLATION ===")
def build_exam(token, title, tag):
    _, exam = call("POST", "/admin/exam", {
        "title": title, "duration": 30,
        "startDate": fmt(now - datetime.timedelta(hours=1)),
        "endDate": fmt(now + datetime.timedelta(hours=3))}, token=token)
    _, sec = call("POST", "/admin/section", {"examId": exam["id"], "name": "General"}, token=token)
    _, q = call("POST", "/admin/question", {
        "examId": exam["id"], "sectionId": sec["id"], "questionText": f"{title} question?",
        "optionA": "right", "optionB": "wrong", "optionC": "no", "optionD": "nope",
        "correctAnswer": "A", "marks": 4, "negativeMarks": 1.0}, token=token)
    _, slot = call("POST", "/admin/slot", {
        "examId": exam["id"],
        "startTime": fmt(now - datetime.timedelta(minutes=30)),
        "endTime": fmt(now + datetime.timedelta(hours=2))}, token=token)
    call("POST", f"/admin/exam/{exam['id']}/publish", token=token, body={})
    hall = f"HT{tag.upper()}"
    call("POST", "/admin/students/upload", {"examId": exam["id"], "slotId": slot["id"]},
         {"file": ("s.csv", f"{hall},Candidate {tag}\n", "text/csv")}, token=token)
    return exam["id"], q["id"], slot["id"], hall

examA, qA, slotA, hallA = build_exam(tokenA, "Alpha EAMCET Mock", tagA)
examB, qB, slotB, hallB = build_exam(tokenB, "Beta NQT Mock", tagB)
print(f"  A: exam {examA}   B: exam {examB}")

status, listA = call("GET", "/admin/exam", token=tokenA)
ids = [e["id"] for e in listA]
check("A sees only its own exams", examA in ids and examB not in ids, ids)

status, cross = call("GET", f"/admin/question/{examB}", token=tokenA)
check("A cannot read B's question bank", status == 403, f"{status} {cross}")

status, cross = call("GET", f"/admin/exam/{examB}", token=tokenA)
check("A cannot read B's exam", status == 403, f"{status} {cross}")

status, cross = call("PUT", f"/admin/question/{qB}", {"questionText": "hijacked"}, token=tokenA)
check("A cannot edit B's question", status == 403, f"{status} {cross}")

status, cross = call("DELETE", f"/admin/question/{qB}", token=tokenA)
check("A cannot delete B's question", status == 403, f"{status} {cross}")

status, cross = call("POST", "/admin/section", {"examId": examB, "name": "Injected"}, token=tokenA)
check("A cannot add a section to B's exam", status == 403, f"{status} {cross}")

status, cross = call("GET", f"/admin/section/{examB}", token=tokenA)
check("A cannot list B's sections", status == 403, f"{status} {cross}")

status, studentsA = call("GET", "/admin/students", token=tokenA)
halls = [s["hallTicket"] for s in studentsA]
check("A sees only its own candidates", hallA in halls and hallB not in halls, halls)

# Ownership must be server-assigned, not client-supplied.
_, planted = call("POST", "/admin/exam", {
    "title": "Planted", "duration": 10, "adminId": 999999}, token=tokenA)
status, verify = call("GET", f"/admin/exam/{planted['id']}", token=tokenA)
check("client-supplied adminId is ignored", status == 200 and verify["adminId"] == a["id"],
      verify.get("adminId"))

print("\n=== STUDENT TOKENS & THE ATTEMPT IDOR ===")
status, vA = call("POST", "/student/validate", {"hallTicket": hallA, "name": f"Candidate {tagA}"})
check("candidate A validates and receives a token", status == 200 and vA.get("token"), vA)
stokenA = vA["token"]

status, vB = call("POST", "/student/validate", {"hallTicket": hallB, "name": f"Candidate {tagB}"})
stokenB = vB["token"]

status, noTok = call("GET", f"/student/exam-info/{examA}")
check("student endpoints reject anonymous callers", status in (401, 403), f"got {status}")

_, attA = call("POST", "/student/start", {"examId": examA}, token=stokenA)
attemptA = attA["attemptId"]
_, attB = call("POST", "/student/start", {"examId": examB}, token=stokenB)
attemptB = attB["attemptId"]

status, steal = call("GET", f"/student/paper/{attemptB}", token=stokenA)
check("candidate A cannot read B's paper", status == 403, f"{status} {steal}")

status, steal = call("GET", f"/student/responses/{attemptB}", token=stokenA)
check("candidate A cannot read B's responses", status == 403, f"{status} {steal}")

status, steal = call("POST", "/student/answer",
                     {"attemptId": attemptB, "questionId": qB, "selectedOption": "D"}, token=stokenA)
check("candidate A cannot answer on B's attempt", status == 403, f"{status} {steal}")

status, steal = call("POST", f"/student/submit/{attemptB}", token=stokenA)
check("candidate A cannot submit B's attempt", status == 403, f"{status} {steal}")

status, steal = call("GET", f"/student/result/{attemptB}", token=stokenA)
check("candidate A cannot read B's result", status == 403, f"{status} {steal}")

status, wrongExam = call("GET", f"/student/exam-info/{examB}", token=stokenA)
check("candidate A's token is not valid for exam B", status == 403, f"{status} {wrongExam}")

status, spoof = call("POST", "/student/start", {"examId": examA, "studentId": 999999}, token=stokenA)
check("client-supplied studentId is ignored", status == 200 and spoof["attemptId"] == attemptA,
      f"{status} {spoof}")

status, crossRole = call("GET", "/admin/exam", token=stokenA)
check("a student token cannot reach admin routes", status == 403, f"got {status}")

status, crossRole = call("GET", f"/student/paper/{attemptA}", token=tokenA)
check("an admin token cannot reach student routes", status == 403, f"got {status}")

print("\n=== UPLOAD HARDENING ===")
status, evil = call("POST", "/upload/logo", {},
                    {"file": ("../../../etc/passwd.png", "not an image", "text/plain")}, token=tokenA)
check("traversal filename + wrong MIME rejected", status == 400, f"{status} {evil}")

status, exe = call("POST", "/upload/logo", {},
                   {"file": ("payload.exe", "MZ binary", "application/octet-stream")}, token=tokenA)
check("executable upload rejected", status == 400, f"{status} {exe}")

status, ok = call("POST", "/upload/logo", {},
                  {"file": ("logo.png", "fake png bytes", "image/png")}, token=tokenA)
check("valid image accepted with a generated name",
      status == 200 and ok.get("filename", "").endswith(".png") and "/" not in ok.get("filename", ""),
      ok)

status, anon = call("POST", "/upload/logo", {}, {"file": ("x.png", "y", "image/png")})
check("uploads require authentication", status in (401, 403), f"got {status}")

print("\n=== SCORING STILL CORRECT UNDER AUTH ===")
call("POST", "/student/answer", {"attemptId": attemptA, "questionId": qA, "selectedOption": "A"},
     token=stokenA)
call("POST", f"/student/submit/{attemptA}", token=stokenA)
status, res = call("GET", f"/student/result/{attemptA}", token=stokenA)
check(f"score is 4.0 for one correct answer  [got {res.get('score')}]", res.get("score") == 4.0, res)

print("\n" + "=" * 60)
if failures:
    print(f"FAILED: {len(failures)}")
    for f in failures: print("  - " + f)
    sys.exit(1)
print("ALL CHECKS PASSED")
