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
    userbar: $('userbar'), userName: $('user-name'), logout: $('logout'),
    authView: $('auth-view'), appView: $('app-view'),
    // auth
    authTitle: $('auth-title'), modeRegister: $('mode-register'), modeLogin: $('mode-login'),
    authForm: $('auth-form'), nameField: $('name-field'), fullName: $('full-name'),
    email: $('email'), password: $('password'), togglePw: $('toggle-pw'), pwHint: $('pw-hint'),
    authError: $('auth-error'), authBtn: $('auth-btn'),
    // send
    sendForm: $('send-form'), recipient: $('recipient'), recipientNote: $('recipient-note'),
    amount: $('amount'), currency: $('currency'), preview: $('preview'),
    error: $('form-error'), receipt: $('receipt'), sendBtn: $('send-btn'),
    // wallet
    balance: $('balance'), walletNumber: $('wallet-number'), copy: $('copy'),
    addForm: $('add-form'), addAmount: $('add-amount'), addBtn: $('add-btn'), addMessage: $('add-message'),
    activity: $('activity-list'),
  };

  const state = {
    user: null,            // { fullName, email, wallet: { accountNumber, balance } }
    history: [],
    mode: 'register',
    sending: false,
    adding: false,
    recipient: { query: '', status: 'idle', found: null }, // idle | checking | found | missing
  };
  let lookupTimer = null;
  let lookupSeq = 0;

  el.currency.textContent = CURRENCY;

  // API
  class ApiError extends Error {
    constructor(message, status) { super(message); this.status = status; }
  }

  async function api(path, options = {}) {
    const headers = { 'X-Requested-With': 'wallet', ...(options.headers || {}) };
    if (options.body) headers['Content-Type'] = 'application/json';

    let res;
    try {
      res = await fetch(API + path, { ...options, headers });
    } catch {
      throw new ApiError("Can't reach the wallet service. Check that the app is running.", 0);
    }
    let body = null;
    try { body = await res.json(); } catch { /* empty body */ }

    if (!res.ok) {
      if (res.status === 401 && state.user) {
        goSignedOut('Your session has ended. Please sign in again.');
      }
      throw new ApiError(messageFrom(body, res.status), res.status);
    }
    return body;
  }

  function messageFrom(body, status) {
    if (body && body.details) return Object.values(body.details).join(' ');
    if (body && body.message) return body.message;
    return `Something went wrong (${status}). Try again.`;
  }

  // Helpers
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

  const sanitizeAmountInput = (input) => {
    const cleaned = input.value.replace(/[^\d.,]/g, '');
    if (cleaned !== input.value) input.value = cleaned;
  };

  // Switching between signed-out and signed-in
  function goSignedOut(message) {
    state.user = null;
    state.history = [];
    clearInterval(refreshTimer);
    el.userbar.hidden = true;
    el.appView.hidden = true;
    el.authView.hidden = false;
    el.password.value = '';
    setMode('login');
    if (message) showAuthError(message);
    el.email.focus();
  }

  function goSignedIn(profile) {
    state.user = profile;
    el.authView.hidden = true;
    el.appView.hidden = false;
    el.userbar.hidden = false;
    el.userName.textContent = profile.fullName;
    el.authForm.reset();
    hideAuthError();
    resetSendForm();
    renderWallet(false);
    loadHistory();
    clearInterval(refreshTimer);
    refreshTimer = setInterval(() => { if (!document.hidden && !state.sending && !state.adding) refresh(); }, REFRESH_MS);
  }

  let refreshTimer = null;

  // Sign up / sign in
  function setMode(mode) {
    state.mode = mode;
    const registering = mode === 'register';
    el.modeRegister.setAttribute('aria-pressed', String(registering));
    el.modeLogin.setAttribute('aria-pressed', String(!registering));
    el.authTitle.textContent = registering ? 'Create your wallet' : 'Welcome back';
    el.nameField.hidden = registering ? false : true;
    el.pwHint.hidden = !registering;
    el.password.autocomplete = registering ? 'new-password' : 'current-password';
    el.authBtn.textContent = registering ? 'Create wallet' : 'Sign in';
    hideAuthError();
  }

  function showAuthError(message) { el.authError.textContent = message; el.authError.hidden = false; }
  function hideAuthError() { el.authError.hidden = true; }

  el.modeRegister.addEventListener('click', () => setMode('register'));
  el.modeLogin.addEventListener('click', () => setMode('login'));

  el.togglePw.addEventListener('click', () => {
    const show = el.password.type === 'password';
    el.password.type = show ? 'text' : 'password';
    el.togglePw.textContent = show ? 'Hide' : 'Show';
    el.togglePw.setAttribute('aria-pressed', String(show));
  });

  el.authForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    hideAuthError();

    const registering = state.mode === 'register';
    const fullName = el.fullName.value.trim();
    const email = el.email.value.trim();
    const password = el.password.value;

    if (registering && fullName.length < 2) return showAuthError('Enter your full name.');
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return showAuthError('Enter a valid email address.');
    if (!password) return showAuthError('Enter your password.');
    if (registering && password.length < 8) return showAuthError('Password must be at least 8 characters.');

    el.authBtn.disabled = true;
    const label = el.authBtn.textContent;
    el.authBtn.textContent = registering ? 'Creating…' : 'Signing in…';
    try {
      const profile = await api(registering ? '/auth/register' : '/auth/login', {
        method: 'POST',
        body: JSON.stringify(registering ? { fullName, email, password } : { email, password }),
      });
      goSignedIn(profile);
    } catch (e) {
      showAuthError(e.message);
    } finally {
      el.authBtn.disabled = false;
      el.authBtn.textContent = label;
    }
  });

  el.logout.addEventListener('click', async () => {
    try { await api('/auth/logout', { method: 'POST' }); } catch { /* signing out anyway */ }
    goSignedOut();
  });

  // Wallet
  function renderWallet(flash) {
    const w = state.user.wallet;
    el.balance.textContent = money(w.balance);
    el.walletNumber.textContent = w.accountNumber;
    if (flash) {
      el.balance.classList.remove('changed');
      void el.balance.offsetWidth; // restart the animation
      el.balance.classList.add('changed');
    }
    refreshSendView();
  }

  function setBalance(balance) {
    const changed = cents(balance) !== cents(state.user.wallet.balance);
    state.user.wallet.balance = balance;
    renderWallet(changed);
  }

  async function refresh() {
    try {
      const profile = await api('/me');
      setBalance(profile.wallet.balance);
      await loadHistory();
    } catch {
        // keep showing what we have
    }
  }

  async function loadHistory() {
    try {
      state.history = await api('/me/transactions');
      renderActivity();
    } catch {
        // ignore
    }
  }

  function renderActivity() {
    el.activity.replaceChildren();
    if (!state.history.length) {
      el.activity.append(node('li', 'empty', 'No activity yet. Add money or send some to get started.'));
      return;
    }
    for (const t of state.history) {
      const ok = t.status === 'SUCCESS';
      const incoming = t.direction === 'IN';
      const topUp = t.counterparty === 'Added money';

      const title = topUp ? 'Added money' : `${incoming ? 'From' : 'To'} ${t.counterparty}`;
      const li = node('li');
      const left = node('span');
      left.append(node('span', 'what', title), node('span', 'when', t.timestamp ? tf.format(toDate(t.timestamp)) : ''));

      const right = node('span', 'right');
      const sign = !ok ? '' : (incoming ? '+' : '\u2212');
      right.append(
        node('span', 'amt' + (ok && incoming ? ' in' : ''), sign + money(t.amount)),
        node('span', 'state ' + (ok ? 'ok' : 'bad'), !ok ? 'Declined' : (topUp ? 'Added' : (incoming ? 'Received' : 'Sent')))
      );
      li.append(left, right);
      el.activity.append(li);
    }
  }

  el.copy.addEventListener('click', async () => {
    const number = state.user.wallet.accountNumber;
    try {
      await navigator.clipboard.writeText(number);
      el.copy.textContent = 'Copied';
    } catch {
      el.copy.textContent = 'Press Ctrl+C';
      const range = document.createRange();
      range.selectNodeContents(el.walletNumber);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    }
    setTimeout(() => { el.copy.textContent = 'Copy'; }, 1800);
  });

  // Add money (demo)
  function setAddMessage(text, kind) {
    el.addMessage.textContent = text;
    el.addMessage.className = 'add-message' + (kind ? ' ' + kind : '');
  }

  for (const chip of document.querySelectorAll('.chip')) {
    chip.addEventListener('click', () => {
      el.addAmount.value = chip.dataset.amount;
      setAddMessage('');
      el.addAmount.focus();
    });
  }

  el.addAmount.addEventListener('input', () => { sanitizeAmountInput(el.addAmount); setAddMessage(''); });

  el.addForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (state.adding) return;
    const amt = parseAmount(el.addAmount.value);
    if (amt === null || amt === 0) return setAddMessage('Enter an amount like 500 or 500.50.', 'bad');
    if (amt > 10000000) return setAddMessage('You can add up to ₹1,00,000 at a time.', 'bad');

    state.adding = true;
    el.addBtn.disabled = true;
    try {
      const result = await api('/me/deposit', { method: 'POST', body: JSON.stringify({ amount: Number((amt / 100).toFixed(2)) }) });
      el.addAmount.value = '';
      setBalance(result.balance);
      setAddMessage(`Added ${money(amt / 100)} to your wallet.`, 'ok');
      await loadHistory();
    } catch (e) {
      setAddMessage(e.message, 'bad');
    } finally {
      state.adding = false;
      el.addBtn.disabled = false;
    }
  });

  // Send money
  function resetSendForm() {
    el.recipient.value = '';
    el.amount.value = '';
    state.recipient = { query: '', status: 'idle', found: null };
    clearFeedback();
    renderRecipientNote();
    refreshSendView();
  }

  function clearFeedback() {
    el.error.hidden = true;
    el.receipt.hidden = true;
  }

  function renderRecipientNote() {
    const r = state.recipient;
    el.recipientNote.className = 'recipient-note';
    el.recipientNote.replaceChildren();
    if (r.status === 'checking') {
      el.recipientNote.textContent = 'Looking up…';
    } else if (r.status === 'found') {
      if (state.user && r.found.accountNumber === state.user.wallet.accountNumber) {
        el.recipientNote.classList.add('bad');
        el.recipientNote.textContent = "That's your own wallet. Choose someone else.";
      } else {
        el.recipientNote.append('Sending to ', node('strong', '', r.found.name), ` (${r.found.accountNumber})`);
      }
    } else if (r.status === 'missing') {
      el.recipientNote.classList.add('bad');
      el.recipientNote.textContent = 'No wallet found for that email or wallet number.';
    }
  }

  function scheduleLookup() {
    clearTimeout(lookupTimer);
    const query = el.recipient.value.trim();
    state.recipient = { query, status: query ? 'checking' : 'idle', found: null };
    renderRecipientNote();
    refreshSendView();
    if (!query) return;

    const seq = ++lookupSeq;
    lookupTimer = setTimeout(async () => {
      try {
        const found = await api('/me/recipient?q=' + encodeURIComponent(query));
        if (seq !== lookupSeq) return;
        state.recipient = { query, status: 'found', found };
      } catch (e) {
        if (seq !== lookupSeq) return;
        state.recipient = { query, status: e.status === 404 ? 'missing' : 'idle', found: null };
      }
      renderRecipientNote();
      refreshSendView();
    }, 350);
  }

  function evaluateSend() {
    if (!state.user) return { ok: false, message: '' };
    const balanceCents = cents(state.user.wallet.balance);
    const text = el.amount.value;

    if (!text.trim()) {
      return { ok: false, message: balanceCents === 0 ? 'Your wallet is empty. Add money first, then send.' : 'Enter an amount to see your new balance.' };
    }
    const amt = parseAmount(text);
    if (amt === null) return { ok: false, message: 'Use a number with up to two decimals, like 250 or 250.50.' };
    if (amt === 0) return { ok: false, message: 'The amount must be more than zero.' };
    if (amt > balanceCents) return { ok: false, message: `You have ${money(balanceCents / 100)}, which is not enough for this transfer.` };

    const r = state.recipient;
    const own = r.status === 'found' && r.found.accountNumber === state.user.wallet.accountNumber;
    const ready = r.status === 'found' && !own;
    return {
      ok: ready, amt,
      message: `After this you'll have ${money((balanceCents - amt) / 100)}.` + (ready ? '' : ' Choose who to send to.'),
    };
  }

  function refreshSendView() {
    const s = evaluateSend();
    el.preview.textContent = s.message;
    el.sendBtn.disabled = state.sending || !s.ok;
  }

  el.recipient.addEventListener('input', () => { clearFeedback(); scheduleLookup(); });
  el.amount.addEventListener('input', () => { sanitizeAmountInput(el.amount); clearFeedback(); refreshSendView(); });

  el.sendForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const s = evaluateSend();
    if (!s.ok || state.sending) return;

    const to = state.recipient.found;
    state.sending = true;
    clearFeedback();
    el.sendBtn.disabled = true;
    el.sendBtn.textContent = 'Sending…';
    try {
      const result = await api('/me/transfers', {
        method: 'POST',
        body: JSON.stringify({ recipient: state.recipient.query, amount: Number((s.amt / 100).toFixed(2)) }),
      });
      el.receipt.replaceChildren(
        node('strong', '', `Sent ${money(s.amt / 100)} to ${result.recipientName || to.name}`),
        node('span', '', `Reference ${result.transactionReference}`)
      );
      el.receipt.hidden = false;
      el.amount.value = '';
      setBalance(result.balance);
      await loadHistory();
    } catch (e) {
      el.error.textContent = e.message;
      el.error.hidden = false;
      await refresh();
    } finally {
      state.sending = false;
      el.sendBtn.textContent = 'Send money';
      refreshSendView();
    }
  });

  /* ---------- Left navigation: one section at a time ---------- */
  const VIEWS = ['wallet', 'send', 'add', 'activity'];

  function viewFromHash() {
    const name = location.hash.replace('#', '');
    return VIEWS.includes(name) ? name : 'wallet';
  }

  function showView(name, moveFocus) {
    for (const s of document.querySelectorAll('[data-view]')) s.hidden = s.dataset.view !== name;
    for (const a of document.querySelectorAll('[data-nav]')) {
      if (a.dataset.nav === name) a.setAttribute('aria-current', 'page');
      else a.removeAttribute('aria-current');
    }
    if (moveFocus) {
      const heading = document.querySelector(`[data-view="${name}"] h1`);
      if (heading) heading.focus();
      window.scrollTo(0, 0);
    }
  }

  window.addEventListener('hashchange', () => { if (state.user) showView(viewFromHash(), true); });

  // Start
  async function start() {
    setMode('register');
    try {
      goSignedIn(await api('/me'));
    } catch (e) {
      goSignedOut(e.status === 0 ? e.message : '');
      setMode('register');
      hideAuthError();
      if (e.status === 0) showAuthError(e.message);
    }
  }

  start();
})();
