(() => {
  'use strict';

  // Change this one constant to switch the currency symbol shown everywhere.
  const CURRENCY = '₹';
  const API = '/api/v1';
  const REFRESH_MS = 15000;

  const nf = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const tf = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
  const money = (n) => CURRENCY + nf.format(n);
  const $ = (id) => document.getElementById(id);

  const el = {
    form: $('send-form'), amount: $('amount'), currency: $('currency'),
    from: $('from'), to: $('to'), swap: $('swap'),
    preview: $('preview'), error: $('form-error'), receipt: $('receipt'), btn: $('send-btn'),
    wallets: $('wallet-list'), activity: $('activity-list'),
  };
  let accounts = [];
  let transactions = [];
  let sending = false;

  el.currency.textContent = CURRENCY;

  // API
  async function api(path, options) {
    let res;
    try {
      res = await fetch(API + path, options);
    } catch {
      throw new Error("Can't reach the wallet service. Check that the app is running.");
    }
    let body = null;
    try { body = await res.json(); } catch { /* empty body */ }
    if (!res.ok) throw new Error(messageFrom(body, res.status));
    return body;
  }

  function messageFrom(body, status) {
    if (body && body.details) return Object.values(body.details).join(' ');
    if (body && body.message) return body.message;
    return `Something went wrong (${status}). Try again.`;
  }

  // Helpers
  const nameOf = (number) => {
    const a = accounts.find((x) => x.accountNumber === number);
    return a && a.ownerName ? a.ownerName : number;
  };
  const cents = (n) => Math.round(Number(n) * 100);

  // "250", "250.5", "1,250.50" -> integer cents, or null when the text is not a valid amount.
  function parseAmount(text) {
    const clean = text.trim().replace(/,/g, '');
    if (!/^\d+(\.\d{1,2})?$/.test(clean)) return null;
    const [whole, frac = ''] = clean.split('.');
    return Number(whole) * 100 + Number(frac.padEnd(2, '0'));
  }

  function toDate(ts) {
    if (Array.isArray(ts)) {
      const [y, mo, d, h = 0, mi = 0, s = 0] = ts;
      return new Date(y, mo - 1, d, h, mi, s);
    }
    return new Date(ts);
  }

  function node(tag, className, text) {
    const n = document.createElement(tag);
    if (className) n.className = className;
    if (text !== undefined) n.textContent = text;
    return n;
  }

  // Rendering
  function renderSelects() {
    const keepFrom = el.from.value;
    const keepTo = el.to.value;

    for (const select of [el.from, el.to]) {
      select.replaceChildren();
      for (const a of accounts) {
        const opt = node('option', '', a.ownerName || a.accountNumber);
        opt.value = a.accountNumber;
        select.append(opt);
      }
      select.disabled = accounts.length < 2;
    }

    const has = (v) => accounts.some((a) => a.accountNumber === v);
    el.from.value = has(keepFrom) ? keepFrom : (accounts[0] ? accounts[0].accountNumber : '');
    el.to.value = has(keepTo) && keepTo !== el.from.value
      ? keepTo
      : (accounts.find((a) => a.accountNumber !== el.from.value) || {}).accountNumber || '';
  }

  function renderWallets(changed = new Set()) {
    el.wallets.replaceChildren();
    if (!accounts.length) {
      el.wallets.append(node('li', 'empty', 'No wallets yet.'));
      return;
    }
    for (const a of accounts) {
      const li = node('li');
      const btn = node('button', 'wallet' + (changed.has(a.accountNumber) ? ' changed' : ''));
      btn.type = 'button';
      btn.setAttribute('aria-pressed', String(a.accountNumber === el.from.value));
      const left = node('span');
      left.append(node('span', 'who', a.ownerName || a.accountNumber), node('span', 'num', a.accountNumber));
      btn.append(left, node('span', 'bal', money(a.balance)));
      btn.addEventListener('click', () => selectSender(a.accountNumber));
      li.append(btn);
      el.wallets.append(li);
    }
  }

  function renderActivity() {
    el.activity.replaceChildren();
    if (!transactions.length) {
      el.activity.append(node('li', 'empty', 'No transfers yet. Your first one will show up here.'));
      return;
    }
    for (const t of transactions) {
      const ok = t.status === 'SUCCESS';
      const li = node('li');
      const left = node('span');
      left.append(
        node('span', 'what', `${nameOf(t.sourceAccount)} to ${nameOf(t.destinationAccount)}`),
        node('span', 'when', t.timestamp ? tf.format(toDate(t.timestamp)) : '')
      );
      const right = node('span', 'right');
      right.append(node('span', 'amt', money(t.amount)), node('span', 'state ' + (ok ? 'ok' : 'bad'), ok ? 'Sent' : 'Declined'));
      li.append(left, right);
      el.activity.append(li);
    }
  }

  function showNotice(message) {
    el.wallets.replaceChildren(node('li', 'notice', message));
  }

  // Form state
  function selectSender(number) {
    el.from.value = number;
    if (el.to.value === number) {
      const other = accounts.find((a) => a.accountNumber !== number);
      el.to.value = other ? other.accountNumber : '';
    }
    clearFeedback();
    refreshView();
  }

  function clearFeedback() {
    el.error.hidden = true;
    el.receipt.hidden = true;
  }

  function evaluate() {
    const text = el.amount.value;
    if (accounts.length < 2) return { ok: false, message: 'You need at least two wallets to send money.' };
    if (!text.trim()) return { ok: false, message: 'Enter an amount to see the new balances.' };

    const amt = parseAmount(text);
    if (amt === null) return { ok: false, message: 'Use a number with up to two decimals, like 250 or 250.50.' };
    if (amt === 0) return { ok: false, message: 'The amount must be more than zero.' };
    if (el.from.value === el.to.value) return { ok: false, message: 'Choose two different wallets.' };

    const src = accounts.find((a) => a.accountNumber === el.from.value);
    const dst = accounts.find((a) => a.accountNumber === el.to.value);
    if (!src || !dst) return { ok: false, message: 'Choose a sender and a recipient.' };

    const srcCents = cents(src.balance);
    if (amt > srcCents) {
      return { ok: false, message: `${nameOf(src.accountNumber)} has ${money(src.balance)}, which is not enough for this transfer.` };
    }
    return {
      ok: true, amt, src, dst,
      message: `After this: ${nameOf(src.accountNumber)} ${money((srcCents - amt) / 100)}, ${nameOf(dst.accountNumber)} ${money((cents(dst.balance) + amt) / 100)}.`,
    };
  }

  function refreshView() {
    const state = evaluate();
    el.preview.textContent = state.message;
    el.btn.disabled = sending || !state.ok;
    for (const b of el.wallets.querySelectorAll('.wallet')) {
      const num = b.querySelector('.num');
      b.setAttribute('aria-pressed', String(num && num.textContent === el.from.value));
    }
  }

  // Data
  async function load({ flash = false } = {}) {
    const before = new Map(accounts.map((a) => [a.accountNumber, cents(a.balance)]));
    try {
      [accounts, transactions] = await Promise.all([api('/accounts'), api('/transactions')]);
    } catch (e) {
      if (!accounts.length) showNotice(e.message);
      return;
    }
    const changed = new Set();
    if (flash) {
      for (const a of accounts) {
        if (before.has(a.accountNumber) && before.get(a.accountNumber) !== cents(a.balance)) changed.add(a.accountNumber);
      }
    }
    renderSelects();
    renderWallets(changed);
    renderActivity();
    refreshView();
  }

  // Events
  el.amount.addEventListener('input', () => {
    const cleaned = el.amount.value.replace(/[^\d.,]/g, '');
    if (cleaned !== el.amount.value) el.amount.value = cleaned;
    clearFeedback();
    refreshView();
  });

  for (const select of [el.from, el.to]) {
    select.addEventListener('change', () => { clearFeedback(); refreshView(); });
  }

  el.swap.addEventListener('click', () => {
    const f = el.from.value;
    el.from.value = el.to.value;
    el.to.value = f;
    clearFeedback();
    refreshView();
  });

  el.form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const state = evaluate();
    if (!state.ok || sending) return;

    sending = true;
    clearFeedback();
    el.btn.disabled = true;
    el.btn.textContent = 'Sending…';

    try {
      const result = await api('/transfers', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sourceAccount: state.src.accountNumber,
          destinationAccount: state.dst.accountNumber,
          amount: Number((state.amt / 100).toFixed(2)),
        }),
      });

      el.receipt.replaceChildren(
        node('strong', '', `Sent ${money(state.amt / 100)} to ${nameOf(state.dst.accountNumber)}`),
        node('span', '', `Reference ${result.transactionReference}`)
      );
      el.receipt.hidden = false;
      el.amount.value = '';
      await load({ flash: true });
    } catch (e) {
      el.error.textContent = e.message;
      el.error.hidden = false;
      await load({ flash: true });
    } finally {
      sending = false;
      el.btn.textContent = 'Send money';
      refreshView();
    }
  });

  load();
  setInterval(() => { if (!document.hidden && !sending) load({ flash: true }); }, REFRESH_MS);
})();
