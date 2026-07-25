/**
 * Drives the real app through the real candidate flow and captures screenshots.
 * Speaks the DevTools protocol over Node 22's built-in WebSocket — no installs.
 */
import { writeFileSync } from "node:fs";

const [, , HALL, NAME, OUT] = process.argv;
const CDP = "http://127.0.0.1:9222";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const targets = await (await fetch(`${CDP}/json/list`)).json();
const page = targets.find((t) => t.type === "page");
if (!page) throw new Error("No page target; is Chrome running with --remote-debugging-port?");

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => (ws.onopen = r));

let id = 0;
const pending = new Map();
ws.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
  }
};

const send = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const msgId = ++id;
    pending.set(msgId, { resolve, reject });
    ws.send(JSON.stringify({ id: msgId, method, params }));
  });

const evaluate = async (expression) => {
  const { result, exceptionDetails } = await send("Runtime.evaluate", {
    expression, awaitPromise: true, returnByValue: true,
  });
  if (exceptionDetails) throw new Error(exceptionDetails.exception?.description || "eval failed");
  return result.value;
};

const shot = async (name) => {
  const { data } = await send("Page.captureScreenshot", { format: "png" });
  const file = `${OUT}/${name}.png`;
  writeFileSync(file, Buffer.from(data, "base64"));
  console.log(`  captured ${name}.png`);
};

const consoleErrors = [];
await send("Runtime.enable");
await send("Page.enable");
ws.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
  if (m.method === "Runtime.consoleAPICalled" && m.params.type === "error") {
    consoleErrors.push(m.params.args.map((a) => a.value ?? a.description).join(" "));
  }
  if (m.method === "Runtime.exceptionThrown") {
    consoleErrors.push(m.params.exceptionDetails.exception?.description || "exception");
  }
});

await send("Emulation.setDeviceMetricsOverride", {
  width: 1600, height: 950, deviceScaleFactor: 1, mobile: false,
});

// ── Sign in ────────────────────────────────────────────────────────────────
console.log("→ /verify");
await send("Page.navigate", { url: "http://localhost:3000/verify" });
await sleep(2500);
await shot("1-verify");

await evaluate(`
  (() => {
    const set = (el, v) => {
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(el, v);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    };
    const inputs = [...document.querySelectorAll('input[type=text]')];
    set(inputs[0], ${JSON.stringify(HALL)});
    set(inputs[1], ${JSON.stringify(NAME)});
    document.querySelector('form').requestSubmit();
    return true;
  })()
`);
await sleep(2500);
console.log("  →", await evaluate("location.pathname"));
await shot("2-instructions");

// ── Begin the exam ─────────────────────────────────────────────────────────
await evaluate(`
  (() => {
    const box = document.querySelector('input[type=checkbox]');
    if (box && !box.checked) box.click();
    return true;
  })()
`);
await sleep(400);
await evaluate(`
  (() => {
    const btn = [...document.querySelectorAll('button')]
      .find(b => /BEGIN EXAM/i.test(b.textContent));
    if (btn) btn.click();
    return !!btn;
  })()
`);
await sleep(3000);
console.log("  →", await evaluate("location.pathname"));

// Headless Chrome refuses real fullscreen; stub it so the gate behaves as it
// would on a candidate's machine. This is a harness concern, not app code.
await evaluate(`
  (() => {
    const el = document.documentElement;
    el.requestFullscreen = () => {
      Object.defineProperty(document, 'fullscreenElement', {
        value: el, configurable: true,
      });
      document.dispatchEvent(new Event('fullscreenchange'));
      return Promise.resolve();
    };
    return true;
  })()
`);
await sleep(300);
await shot("3-fullscreen-gate");

await evaluate(`
  (() => {
    const btn = [...document.querySelectorAll('button')]
      .find(b => /Enter fullscreen/i.test(b.textContent));
    if (btn) btn.click();
    return !!btn;
  })()
`);
await sleep(2500);
await shot("4-exam-fresh");

// ── Work the paper so every palette state is represented ───────────────────
console.log("→ answering");
const click = async (re, wait = 500) => {
  await evaluate(`
    (() => {
      const b = [...document.querySelectorAll('button')].find(x => ${re}.test(x.textContent));
      if (b) b.click();
      return !!b;
    })()
  `);
  await sleep(wait);
};
const pickOption = async (letter) => {
  await evaluate(`
    (() => {
      const inp = document.querySelector('input[type=radio][value="${letter}"]');
      if (inp) inp.click();
      return !!inp;
    })()
  `);
  await sleep(450);
};

await pickOption("A");                 // Q1 answered
await click("/Save & Next/");
await pickOption("B");                 // Q2 answered
await click("/Save & Next/");
await click("/Save & Next/");          // Q3 visited, left blank -> Not Answered
await pickOption("C");                 // Q4 answered...
await click("/Mark for Review & Next/"); // ...and marked -> Answered & Marked
await click("/Mark for Review & Next/"); // Q5 marked, unanswered -> Marked
await sleep(800);
await shot("5-exam-states");

// Jump to another section to show the tabs working.
await evaluate(`
  (() => {
    const tab = [...document.querySelectorAll('button')].find(b => /^Chemistry/.test(b.textContent));
    if (tab) tab.click();
    return !!tab;
  })()
`);
await sleep(1200);
await pickOption("A");
await sleep(600);
await shot("6-section-switch");

// ── Submit summary ─────────────────────────────────────────────────────────
await click("/^Submit Exam$/", 1500);
await shot("7-submit-summary");

const report = await evaluate(`
  (() => {
    const counts = {};
    document.querySelectorAll('aside [aria-label]').forEach(() => {});
    return {
      path: location.pathname,
      sectionTabs: [...document.querySelectorAll('nav button')].map(b => b.textContent.trim()),
      paletteCells: document.querySelectorAll('[aria-label^="Question "]').length,
      summaryVisible: /Submit your exam/.test(document.body.innerText),
      horizontalOverflow: document.documentElement.scrollWidth > window.innerWidth,
    };
  })()
`);

console.log("\nreport:", JSON.stringify(report, null, 2));
console.log("console errors:", consoleErrors.length ? consoleErrors : "none");
ws.close();
