/* Wheelhouse: shared by every page. The nav, the profile dialog, badges and icons, and the
   one way every page talks to the server. */
const WH = (() => {
  const esc = s => String(s ?? "").replace(/[&<>"']/g, c =>
    ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]));
  const fmt1 = n => (Math.round((n || 0) * 10) / 10).toFixed(1);
  const ordinal = n => n + (["th", "st", "nd", "rd"][(n % 100 - 20) % 10]
    || ["th", "st", "nd", "rd"][n % 100] || "th");
  const ET = { timeZone: "America/New_York" };
  const kickoffText = iso => new Date(iso).toLocaleString("en-US",
    Object.assign({ weekday: "short", hour: "numeric", minute: "2-digit" }, ET)).replace(":00", "");
  const NOPHOTO = "https://a.espncdn.com/i/headshots/nophoto.png";
  const crest = abbr => abbr
    ? `https://a.espncdn.com/i/teamlogos/nfl/500-dark/${abbr.toLowerCase() === "was" ? "wsh" : abbr.toLowerCase()}.png`
    : null;

  /* Every request goes through here. Errors come back as a sentence to show as it is. */
  async function api(path, opts = {}) {
    const qs = opts.params ? "?" + new URLSearchParams(opts.params) : "";
    const init = { method: opts.method || "GET", credentials: "same-origin", headers: {} };
    if (opts.body !== undefined) {
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(opts.body);
    }
    const r = await fetch(path + qs, init);
    let body = {};
    try { body = await r.json(); } catch (e) { /* empty */ }
    if (!r.ok || (body && body.error)) {
      const err = new Error((body && body.error) || "Something went wrong. Try again.");
      err.status = r.status;
      throw err;
    }
    return body;
  }

  let meCache = null;
  async function me(fresh) {
    if (!meCache || fresh) {
      try { meCache = await api("/api/account/me"); } catch (e) { meCache = { signedIn: false }; }
    }
    return meCache;
  }

  const ICON = {
    play: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2.2"/><path d="M12 3v6.8M12 14.2V21M3 12h6.8M14.2 12H21"/>',
    live: '<path d="M3 12h4l2.5-6 5 12 2.5-6h4"/>',
    trophy: '<path d="M8 21h8M12 16.5V21M7 3.5h10V9a5 5 0 0 1-10 0z"/><path d="M17 5h3v1.8a3.4 3.4 0 0 1-3.2 3.4M7 5H4v1.8a3.4 3.4 0 0 0 3.2 3.4"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
    share: '<path d="M12 3v12M7.5 7.5 12 3l4.5 4.5"/><path d="M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6"/>',
    again: '<path d="M20 12a8 8 0 1 1-2.3-5.6"/><path d="M20 4v4.5h-4.5"/>',
  };
  const icon = (name, cls = "") => `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true">${ICON[name]}</svg>`;

  /* The nav is the same everywhere. The wordmark always goes home. */
  async function nav(active) {
    const host = document.getElementById("nav");
    const who = await me();
    const link = (href, key, label, ic) =>
      `<a href="${href}" class="${active === key ? "on" : ""}">${icon(ic)}<span class="${key === "profile" ? "me" : ""}">${esc(label)}</span></a>`;
    host.className = "top";
    host.innerHTML = `<div class="wrap">
      <a class="logo" href="/"><img src="/img/logo-mark.png" alt="" width="38" height="38"><span class="wm">Wheel<span>house</span></span></a>
      <div class="navlinks">
        ${link("/", "play", "Play", "play")}
        ${link("/live.html", "live", "Live", "live")}
        ${link("/leaderboards.html", "boards", "Leaderboards", "trophy")}
        ${link("/profile.html", "profile", who.signedIn ? who.name : "Sign in", "user")}
      </div></div>`;
  }

  /* Pages call this once they know what they are showing. Until then nothing paints. */
  function ready() { document.documentElement.classList.remove("loading"); }

  /* ---- slate badges ----
     Thursday and Monday nights wear their broadcasts' own logos; Sunday wears RedZone's, since
     the Sunday slate is every afternoon game at once. Served from Wikimedia at thumbnail size.
     If an image cannot load, the badge falls back to its name in text. */
  const LOGOS = {
    thu: "https://upload.wikimedia.org/wikipedia/en/thumb/1/10/Thursday_Night_Football_logo_2022.svg/250px-Thursday_Night_Football_logo_2022.svg.png",
    mon: "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1f/ESPN_Monday_Night_Football_logo.png/250px-ESPN_Monday_Night_Football_logo.png",
    sun: "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a3/NFL_RedZone_Logo_%282012%29.png/250px-NFL_RedZone_Logo_%282012%29.png",
  };
  const MOON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 14.6A8.6 8.6 0 0 1 9.4 4 8.6 8.6 0 1 0 20 14.6z"/></svg>';
  const BALL = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3.5 20.5C2.8 13 7.6 4.2 20.5 3.5 21.2 11 16.4 19.8 3.5 20.5z"/></svg>';
  const BACK = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M11.5 5.5v13L3 12zM21 5.5v13L12.5 12z"/></svg>';
  function badge(key, label, size = "") {
    if (LOGOS[key]) {
      return `<span class="sb-logo ${size}" title="${esc(label)}">`
        + `<img src="${LOGOS[key]}" alt="${esc(label)}" onerror="this.remove()"><span class="sb-txt">${esc(label)}</span></span>`;
    }
    const k = ["thu", "mon", "sun", "sat", "fri"].includes(key) ? key : "past";
    const glyph = k === "thu" || k === "mon" ? MOON : k === "past" ? BACK : BALL;
    return `<span class="sb ${k} ${size}">${glyph}<span>${esc(label)}</span></span>`;
  }

  /* ---- the stat parts, drawn: a flexed arm, football shoulder pads, a runner's legs, a cleat,
     receiving hands, a jersey, and a nose for the end zone. ---- */
  const PARTS = {
    arm: '<path d="M4 28v-7c0-3 1-4.5 2.5-5 1.5-5 10-7.5 14 1l-.5-6-1.8-1.8c-1.2-1.4-.8-4.2 1.3-5l5-.4c1.8 0 2.5 1.4 2.1 3.2l-1 4 1.8 11c.5 3.4-1.6 6-4.8 6z"/><path d="M19 6.9h4.5M19.2 9.2h4"/><path d="M9 21c2-1.8 5.2-2 7.5-.6"/>',
    shoulders: '<path d="M3 19c0-5 2.5-8 6.5-9L11 16c-3 0-5.5 1.5-8 3z"/><path d="M29 19c0-5-2.5-8-6.5-9L21 16c3 0 5.5 1.5 8 3z"/><path d="M9.5 10c1.5-1 3-1.5 3.5-1.8.5 2.8 1.6 4.4 3 4.4s2.5-1.6 3-4.4c.5.3 2 .8 3.5 1.8l-1 16-4.5 1-1-1.5-1 1.5-4.5-1z"/><path d="M16 13v10.5M14.6 16h2.8M14.6 19h2.8M14.6 22h2.8"/>',
    legs: '<path d="M10.5 4.5h10l2.3 6.5-5.4 1.6-1.6-2.8-2 3-4.2-1.4z"/><path d="M22.5 10.5l4.3 5.8c.5.7.4 1.5 0 2.1l-2.3 4.8 3.1 1.2c.8.3 1 1 .8 1.7l-.2.7h-6.8v-3.5l1.6-5.3-5.4-5.8"/><path d="M13.8 12.6l-2.2 6.8c-.3.9-1.3 1.4-2.2 1L5 18.4l-1.4 2.4c-.4.6-1.3.4-1.4-.3l.4-4.9c.1-.7.8-1.1 1.5-.9l4.9 1.7 1-5.6"/>',
    cleats: '<path d="M3.5 22.5V12.2c0-1.5 1.4-2.5 2.9-2l4.4 1.4 2.2-2.7 4.3 1.5c.6 2.8 2.3 4.9 5.4 5.9l4.6 1.5c2 .7 3.2 2.3 3.2 4.1v.6z"/><path d="M3.5 22.5h26.5"/><path d="M6.5 22.5l.6 3.4h2.2l.6-3.4M13 22.5l.6 3.4h2.2l.6-3.4M19.5 22.5l.6 3.4h2.2l.6-3.4M25.5 22.5l.6 3.4h2.2l.6-3.4"/><path d="M13.8 13.2l2.9 1M15 15.6l2.9 1M16.6 17.8l2.8.9"/><path d="M3.5 17h5"/>',
    hands: '<path d="M9.2 17.5V9a1.8 1.8 0 0 1 3.6 0v6"/><path d="M12.8 14V6.8a1.8 1.8 0 0 1 3.6 0V14"/><path d="M16.4 14V7.8a1.8 1.8 0 0 1 3.6 0V15"/><path d="M20 15v-3.8a1.8 1.8 0 0 1 3.6 0v7.3c0 5-3.7 9-8.7 9h-1c-3 0-5.1-1.4-6.7-3.8l-2.9-4.9a1.9 1.9 0 0 1 3.1-2.2l1.8 2.4"/>',
    chest: '<path d="M11.2 4 4 8l2.6 6.2 3.6-1.5V28h11.6V12.7l3.6 1.5L28 8l-7.2-4c-.8 2-2.7 3.4-4.8 3.4S12 6 11.2 4z"/><path d="M10.2 16.5h11.6M10.2 19.5h11.6"/>',
    nose: '<path d="M14.5 4.5c.4 5.4-1.3 10.3-4.8 15.2-.8 1.2.1 2.6 1.5 2.6H16"/><path d="M16 22.3c1.9.1 3.4-.9 3.8-2.4"/><path d="M9.5 27c2.4.9 6.4.9 9 0"/>',
  };
  const part = (key, cls = "pi") => PARTS[key]
    ? `<svg class="${cls}" viewBox="0 0 32 32" aria-hidden="true">${PARTS[key]}</svg>` : "";

  function toast(msg) {
    const t = document.createElement("div");
    t.className = "toast";
    t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(() => t.remove(), 2400);
  }

  /* Rosters this browser played before profiles, handed over on sign-in. */
  async function claimLocal() {
    let ids = [];
    try { ids = JSON.parse(localStorage.getItem("wh.entries") || "[]"); } catch (e) { /* none */ }
    if (!Array.isArray(ids) || !ids.length) return;
    try {
      await api("/api/account/claim", { method: "POST", body: { ids: ids.filter(x => typeof x === "string") } });
      localStorage.removeItem("wh.entries");
      localStorage.removeItem("wh.entry");
    } catch (e) { /* not worth interrupting sign-in for */ }
  }

  /* The sign-up / sign-in form. Used in the dialog and on the profile page. */
  function authForm(host, onDone, startMode = "signup") {
    let mode = startMode;
    const draw = () => {
      host.innerHTML = `
        <div class="tabs">
          <button type="button" data-mode="signup" class="${mode === "signup" ? "on" : ""}">New profile</button>
          <button type="button" data-mode="signin" class="${mode === "signin" ? "on" : ""}">Sign in</button>
        </div>
        <form novalidate>
          <label class="field"><span>Name</span>
            <input name="name" autocomplete="username" maxlength="20" required
              placeholder="${mode === "signup" ? "What the leaderboard calls you" : ""}"></label>
          <label class="field"><span>Password</span>
            <input name="password" type="password" required minlength="6"
              autocomplete="${mode === "signup" ? "new-password" : "current-password"}"
              placeholder="${mode === "signup" ? "6 characters or more" : ""}"></label>
          <p class="err" style="margin-bottom:12px"></p>
          <button class="btn primary lg" type="submit" style="width:100%">${mode === "signup" ? "Create profile" : "Sign in"}</button>
        </form>`;
      host.querySelectorAll("[data-mode]").forEach(b => b.addEventListener("click", () => {
        mode = b.dataset.mode; draw();
      }));
      const form = host.querySelector("form");
      const err = host.querySelector(".err");
      form.addEventListener("input", () => { err.textContent = ""; });
      form.addEventListener("submit", async e => {
        e.preventDefault();
        const name = form.name.value.trim();
        const password = form.password.value;
        if (!name) { err.textContent = "Enter a name."; return; }
        if (!password) { err.textContent = "Enter a password."; return; }
        const submit = form.querySelector("button[type=submit]");
        submit.disabled = true;
        try {
          const who = await api(`/api/account/${mode}`, { method: "POST", body: { name, password } });
          meCache = who;
          await claimLocal();
          onDone(who);
        } catch (x) {
          err.textContent = x.message;
          submit.disabled = false;
        }
      });
      host.querySelector("input[name=name]").focus();
    };
    draw();
  }

  /* Somebody who can play: a profile if there is one, otherwise a guest made on the spot.
     Nobody is asked for anything before their first spin. */
  async function ensurePlayer() {
    const who = await me();
    if (who.signedIn || who.guest) return who;
    meCache = await api("/api/account/guest", { method: "POST" });
    nav(document.body.dataset.page);
    return meCache;
  }

  /* Resolves with a real profile, asking for one first. A guest who makes one keeps what they
     played; a guest who signs in brings it with them. */
  async function requireAuth(copy = {}) {
    const who = await me();
    if (who.signedIn) return who;
    return new Promise((resolve, reject) => {
      const back = document.createElement("div");
      back.className = "modal-back";
      back.innerHTML = `<div class="modal" role="dialog" aria-modal="true" aria-labelledby="authTitle">
        <button class="close" aria-label="Close">&times;</button>
        <h2 id="authTitle">${esc(copy.title || "Your profile")}</h2>
        <p>${esc(copy.text || "Save your rosters and get your name on the leaderboards.")}</p>
        <div id="authHost"></div></div>`;
      document.body.appendChild(back);
      const close = () => { back.remove(); reject(Object.assign(new Error("cancelled"), { cancelled: true })); };
      back.querySelector(".close").addEventListener("click", close);
      back.addEventListener("click", e => { if (e.target === back) close(); });
      document.addEventListener("keydown", function onKey(e) {
        if (e.key === "Escape") { document.removeEventListener("keydown", onKey); close(); }
      });
      authForm(back.querySelector("#authHost"), who => {
        back.remove();
        nav(document.body.dataset.page);
        resolve(who);
      });
    });
  }

  const logo = key => LOGOS[key] || null;

  return { esc, fmt1, ordinal, kickoffText, crest, NOPHOTO, api, me, nav, ready, badge, logo, part,
           icon, toast, requireAuth, ensurePlayer, authForm };
})();
