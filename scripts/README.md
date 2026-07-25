# End-to-end checks

Two scripts that exercise the API against a **running** backend and a real
database. They create their own throwaway institutions, exams and candidates, so
they can be run repeatedly without cleanup.

## Running

Start the backend first (a JWT secret is required):

```bash
JWT_SECRET="a-secret-of-at-least-32-characters-long" ./mvnw spring-boot:run
```

Then, from anywhere:

```bash
python scripts/e2e_exam_engine.py
python scripts/e2e_auth_and_tenancy.py
```

Both exit non-zero if any check fails.

## What they cover

**`e2e_exam_engine.py`** — the exam itself:

- the student paper contains no answer key in any form
- question and option order stay stable across refetches
- marks and negative marks are applied correctly (2 correct ×4, 1 wrong −1,
  1 skipped → 7.0 / 16.0)
- results are withheld until the attempt is submitted
- submit is idempotent; answers are refused afterwards
- a submitted candidate cannot re-enter
- CSV import survives quoted commas and reports every rejected row

**`e2e_auth_and_tenancy.py`** — who may do what:

- every admin and student route rejects anonymous and forged tokens
- passwords are hashed; no response leaks one; login does not reveal whether an
  account exists
- one institution cannot read, edit or delete another's exams, questions,
  sections or candidates
- a candidate cannot touch another candidate's attempt, paper, responses,
  submission or result
- client-supplied `adminId` / `studentId` are ignored in favour of the token
- uploads reject traversal filenames, wrong MIME types and executables

## Visual / browser check

`seed_demo_paper.py` creates a realistic three-section EAMCET-style paper and
prints a candidate's hall ticket:

```bash
python scripts/seed_demo_paper.py
# {"examId": 32, "hallTicket": "24EAMA5A92", "name": "Asha Ramakrishna Rao", ...}
```

`drive_browser_flow.mjs` then drives the **real** candidate journey in Chrome and
captures screenshots — sign in, briefing, fullscreen gate, answering, section
switching, submit summary. It speaks the DevTools protocol over Node 22's
built-in WebSocket, so there is nothing to install:

```bash
# any installed Chrome works
"/c/Program Files (x86)/Google/Chrome/Application/chrome.exe" \
  --headless=new --remote-debugging-port=9222 --disable-gpu \
  --user-data-dir=/tmp/chrome-profile --window-size=1600,950 about:blank &

node scripts/drive_browser_flow.mjs "<hallTicket>" "<name>" ./shots
```

It reports section tallies, palette cell count, horizontal overflow and any
console errors — then writes the PNGs to the output directory.

Headless Chrome refuses genuine fullscreen, so the script stubs
`requestFullscreen` before clicking the gate. That stub lives in the harness,
never in application code.

## Note

These are integration checks, not unit tests — they need the server and MySQL up.
There is no JUnit suite yet; adding one is worthwhile before the codebase grows
much further.
