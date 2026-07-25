# Running an exam — checklist and runbook

For the person actually running the sitting. No commands unless something has
gone wrong, and each of those says plainly what it does.

Two rules worth internalising before anything else:

> **A candidate's answers are already safe.** Every answer is stored on the server
> the moment it is chosen, and if the network drops the browser keeps them and
> resends them. A frozen screen is almost never lost work.

> **The clock is on the server.** Refreshing, reconnecting, even switching to
> another machine does not give a candidate extra time, and does not cost them
> any either.

---

# Part 1 — The rehearsal (do this first, at least a week ahead)

Do not let the first real sitting be the first sitting. One hour with about
twenty students finds things no amount of testing does.

### Set it up

1. Sign in at **`/admin/login`**.
2. **Create Exam** — call it "Rehearsal", 15 minutes, 10 questions is plenty.
3. **Sections** → add one, e.g. "General".
4. **Questions** → add 10, or upload a CSV.
5. **Slot** → a window covering the rehearsal hour.
6. **Students** → upload a CSV of the twenty volunteers:
   `hallTicket,name` — one per line.
7. **Live Monitor → Prepare Papers.** Wait for "Prepared N paper(s)".

### Run it

Sit the students at real lab machines, on the real network — not your desk.

Give them the exam address and nothing else. **Watch how many need help
signing in.** If several do, the hall ticket format is confusing and that is
worth knowing now rather than with 500 people waiting.

### Deliberately break things

This is the point of the rehearsal. While they are writing:

| Do this to one machine | What should happen |
|---|---|
| Unplug the network cable for 30 seconds | Amber "connection lost" banner; answers resume on reconnect; **no lost answers** |
| Close the browser entirely and reopen to the exam address | Signs back in, **same paper, same questions in the same order**, clock still correct |
| Press Escape / try to leave fullscreen | Warning appears; on the third violation the exam auto-submits |
| Close the laptop lid for a minute | Appears as **Disconnected** on your Live Monitor within ~2 minutes |

If any of those behave differently, stop and report it before scheduling a
real exam.

### Afterwards

- Check the **Live Monitor** shows everyone as *Submitted*.
- Open a few results. Do the scores look right for what the students actually
  answered? Ask two of them to confirm.
- Ask the students one question: *what was confusing?* They will tell you
  something the software cannot.

---

# Part 2 — The week before a real exam

- [ ] Exam created: correct **duration**, and marks/negative marks per question.
- [ ] Questions uploaded and **spot-checked** — open five at random and confirm
      the correct answer really is correct. A wrong answer key is the one
      mistake this system cannot detect for you.
- [ ] Slot window covers the sitting, **with margin at both ends**. If the paper
      is 180 minutes, do not open a 180-minute window: latecomers get cut short,
      because a candidate's finish time is capped at the slot's close.
- [ ] Candidates uploaded; the count matches your roll list exactly.
- [ ] Confirm the lab machines can reach the exam address — from the lab, on the
      lab network, in the browser the students will actually use.

# Part 3 — The night before

- [ ] **Live Monitor → Prepare Papers.** This builds every candidate's paper in
      advance. It is the difference between a calm start and several hundred
      people pressing Start at the same second. Re-run it if anyone was added
      afterwards; it is safe to run twice.
- [ ] Confirm the database backup ran.
- [ ] If a replica is configured, confirm it is keeping up (`deploy/FAILOVER.md`).

# Part 4 — Exam morning, before candidates enter

- [ ] Open the exam address on one lab machine and reach the sign-in page.
- [ ] Sign in to **Live Monitor** on the invigilator's machine and leave it open.
- [ ] Everyone should show **Not started**, and the total should match your roll.
- [ ] Have the roll list on paper. If screens fail, paper does not.

---

# Part 5 — During the exam

Keep **Live Monitor** open. It refreshes on its own every ten seconds.

### What the four states mean

| State | Meaning | Do |
|---|---|---|
| **Not started** | Enrolled, hasn't begun | Normal early on. Still showing 20 minutes in? Go and find them |
| **Writing** | Answering; answers arriving | Nothing |
| **Disconnected** | Started, but nothing has reached the server for 2 minutes | **Go to that machine.** Usually network or a closed lid |
| **Submitted** | Finished | Nothing |

A red banner appears above the table when anyone disconnects, and an amber one
when proctoring violations are recorded. Click either to filter to just those
candidates.

### Common situations

**"It says my details don't match."**
Check the hall ticket against your roll list, character for character. Then check
the name is exactly as uploaded. Both are case-insensitive; spelling is not.

**"My screen froze / the browser closed."**
Have them reopen the exam address and sign in again. They get the same paper and
the correct remaining time. **Their answers are still there.** Reassure them of
this — panic costs more marks than the outage.

**A machine dies completely.**
Move the candidate to a spare machine and have them sign in there. Nothing is
tied to a particular computer. Note the lost minutes and consider extending the
slot (see below).

**Someone shows several violations.**
The system already warned them, and auto-submits on the third. Your job is to
record it. The count is your evidence if the result is challenged later.

**Everyone reports it being slow or stuck at once.**
This is the one that is not about a candidate. Go to `deploy/FAILOVER.md`.
Do not restart anything at random — the guidance there is ordered from least to
most disruptive for a reason.

### Giving candidates more time

If an incident cost real minutes, extend the **slot end time** before the window
closes. A candidate's clock is capped by the slot, so extending the slot is what
actually gives the time back.

---

# Part 6 — After

- [ ] Live Monitor shows everyone **Submitted**. Anyone still *Writing* has left
      without submitting — their exam auto-submits when time runs out, and their
      answers still count.
- [ ] Anyone still **Not started** genuinely never sat. Confirm against your
      paper roll before recording an absence.
- [ ] Note any candidate with violations, with what you observed. The system
      records that something happened; only you can record what it looked like.
- [ ] Take a database backup before doing anything else.

---

## What this system will not catch for you

Said plainly, so it is not a surprise:

- **A wrong answer key.** It will mark confidently and consistently against
  whatever you uploaded. Spot-check.
- **A candidate sitting for someone else.** Identity is a hall ticket and a
  name. Physical verification is still yours.
- **A phone under the desk.** Proctoring covers what happens in the browser,
  nothing beyond it.
