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