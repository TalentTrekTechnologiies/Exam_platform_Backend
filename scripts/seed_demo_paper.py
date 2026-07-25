"""Seeds a realistic multi-section paper and prints the candidate's credentials."""
import json, urllib.request, urllib.error, datetime, uuid

BASE = "http://localhost:8080"

def call(method, path, body=None, files=None, token=None):
    headers = {}
    if token: headers["Authorization"] = "Bearer " + token
    if files is not None:
        b = "----b" + uuid.uuid4().hex
        parts = []
        for k, v in (body or {}).items():
            parts.append(f"--{b}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n")
        for k, (fn, content) in files.items():
            parts.append(f"--{b}\r\nContent-Disposition: form-data; name=\"{k}\"; filename=\"{fn}\"\r\n"
                         f"Content-Type: text/csv\r\n\r\n{content}\r\n")
        parts.append(f"--{b}--\r\n")
        data = "".join(parts).encode()
        headers["Content-Type"] = f"multipart/form-data; boundary={b}"
    else:
        data = json.dumps(body).encode() if body is not None else None
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try: return e.code, json.loads(raw)
        except json.JSONDecodeError: return e.code, raw

now = datetime.datetime.now()
fmt = lambda d: d.strftime("%Y-%m-%dT%H:%M:%S")
tag = uuid.uuid4().hex[:5]

_, admin = call("POST", "/admin/register", {
    "collegeName": "Sri Venkateswara Institute of Technology",
    "email": f"seed{tag}@sviT.edu", "password": "seed-password-123",
    "collegeAddress": "Hyderabad"}, files={})
T = admin["token"]

_, exam = call("POST", "/admin/exam", token=T, body={
    "title": "AP EAMCET 2026 — Full Length Mock Test 4",
    "duration": 180,
    "startDate": fmt(now - datetime.timedelta(hours=1)),
    "endDate": fmt(now + datetime.timedelta(hours=6)),
    "enableCamera": False, "enableMic": False})
exam_id = exam["id"]

PAPER = {
    "Physics": [
        ("A body of mass 2 kg moves with a velocity of 10 m/s. Find its momentum.",
         "20 kg m/s", "10 kg m/s", "5 kg m/s", "40 kg m/s", "A"),
        ("The dimensional formula of Planck's constant is:",
         "ML²T⁻¹", "ML²T⁻²", "MLT⁻¹", "ML⁻¹T⁻²", "A"),
        ("If x = 2 m, find the work done by a constant force of 5 N acting along the displacement.",
         "10 J", "2.5 J", "7 J", "3 J", "A"),
        ("A convex lens of focal length 20 cm forms an image at 60 cm. The object distance is:",
         "30 cm", "20 cm", "15 cm", "40 cm", "A"),
        ("The SI unit of magnetic flux is:", "weber", "tesla", "henry", "gauss", "A"),
    ],
    "Chemistry": [
        ("The atomic number of Carbon is:", "6", "12", "8", "14", "A"),
        ("Which of the following is an example of a Lewis acid?",
         "BF₃", "NH₃", "H₂O", "OH⁻", "A"),
        ("The pH of a neutral solution at 25 °C is:", "7", "0", "14", "1", "A"),
        ("The IUPAC name of CH₃–CH₂–OH is:",
         "Ethanol", "Methanol", "Propanol", "Ethanal", "A"),
    ],
    "Mathematics": [
        ("If sin θ = 3/5 and θ is acute, then cos θ equals:",
         "4/5", "3/4", "5/4", "5/3", "A"),
        ("The derivative of x³ with respect to x is:", "3x²", "x²", "3x", "2x³", "A"),
        ("The number of ways to arrange the letters of the word 'LEVEL' is:",
         "30", "60", "120", "20", "A"),
        ("If the roots of x² − 5x + 6 = 0 are α and β, then α + β equals:",
         "5", "6", "−5", "1", "A"),
        ("The value of ∫₀¹ 2x dx is:", "1", "2", "0", "1/2", "A"),
        ("The eccentricity of a parabola is:", "1", "0", "2", "1/2", "A"),
    ],
}

count = 0
for section_name, items in PAPER.items():
    _, sec = call("POST", "/admin/section", token=T,
                  body={"examId": exam_id, "name": section_name, "totalMarks": len(items) * 4})
    for text, a, b, c, d, correct in items:
        call("POST", "/admin/question", token=T, body={
            "examId": exam_id, "sectionId": sec["id"], "questionText": text,
            "optionA": a, "optionB": b, "optionC": c, "optionD": d,
            "correctAnswer": correct, "marks": 4, "negativeMarks": 1.0})
        count += 1

_, slot = call("POST", "/admin/slot", token=T, body={
    "examId": exam_id,
    "startTime": fmt(now - datetime.timedelta(minutes=30)),
    "endTime": fmt(now + datetime.timedelta(hours=5))})

hall = f"24EAM{tag.upper()}"
name = "Asha Ramakrishna Rao"
call("POST", "/admin/students/upload", {"examId": exam_id, "slotId": slot["id"]},
     {"file": ("s.csv", f"{hall},{name}\n")}, token=T)

print(json.dumps({"examId": exam_id, "hallTicket": hall, "name": name, "questions": count}))
