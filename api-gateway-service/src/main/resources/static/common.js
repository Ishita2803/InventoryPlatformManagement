// Shared auth + fetch helpers for admin.html / vendor.html / customer.html / carrier.html.
// Same-origin, no build step -- this page is served by api-gateway-service itself.
const API = '';

function saveSession(token, role, businessId) {
  sessionStorage.setItem('impulse_token', token);
  sessionStorage.setItem('impulse_role', role);
  sessionStorage.setItem('impulse_businessId', businessId);
}

function getToken() { return sessionStorage.getItem('impulse_token'); }
function getRole() { return sessionStorage.getItem('impulse_role'); }
function getBusinessId() { return sessionStorage.getItem('impulse_businessId'); }

function clearSession() {
  sessionStorage.removeItem('impulse_token');
  sessionStorage.removeItem('impulse_role');
  sessionStorage.removeItem('impulse_businessId');
}

/** Turns an id/code containing characters that aren't valid in a DOM id (a UUID's
 * dashes are fine, but be defensive) into something usable as one. */
function cssId(value) { return String(value).replace(/[^a-zA-Z0-9_-]/g, '_'); }

function log(label, req, res) {
  const el = document.getElementById('log');
  if (el) el.textContent = `${label}\n\n--> ${JSON.stringify(req, null, 2)}\n\n<-- ${JSON.stringify(res, null, 2)}`;
}

/** Attaches the bearer token to every call once logged in -- the token, not a role
 * picked from a dropdown, is what the gateway's JwtAuthFilter actually trusts. */
async function api(method, path, body) {
  const headers = { 'Content-Type': 'application/json' };
  const token = getToken();
  if (token) headers['Authorization'] = 'Bearer ' + token;

  const res = await fetch(API + path, {
    method, headers, body: body ? JSON.stringify(body) : undefined
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch (e) { json = text; }
  log(`${method} ${path}`, body || {}, json);
  if (!res.ok) throw { status: res.status, body: json };
  return json;
}

async function doLogin(expectedRole, usernameFieldId, passwordFieldId, msgFieldId) {
  const msg = document.getElementById(msgFieldId);
  msg.className = 'msg'; msg.textContent = 'Signing in…';
  try {
    const username = document.getElementById(usernameFieldId).value;
    const password = document.getElementById(passwordFieldId).value;
    const result = await api('POST', '/auth/login', { username, password });

    if (result.role !== expectedRole) {
      msg.className = 'msg error';
      msg.textContent = `This page is for ${expectedRole} accounts -- that login is ${result.role}.`;
      return false;
    }

    saveSession(result.token, result.role, result.businessId);
    msg.className = 'msg ok'; msg.textContent = 'Signed in.';
    return true;
  } catch (e) {
    msg.className = 'msg error';
    msg.textContent = 'Login failed: ' + (e.body && e.body.message ? e.body.message : e.status || e);
    return false;
  }
}

function showGate() {
  document.querySelectorAll('.gate').forEach(el => el.classList.add('visible'));
  document.getElementById('loginPanel').style.display = 'none';
  const who = document.getElementById('whoami');
  if (who) who.innerHTML = `Signed in as <b>${getBusinessId()}</b> (${getRole()}) &middot; <a href="#" onclick="doLogout()">Sign out</a>`;
}

function doLogout() {
  clearSession();
  location.reload();
}

/** Called at the top of every page's script: wipes any leftover session so opening (or
 * reloading) any page always starts at the login form, never silently reusing a token
 * from a different tab, a different role's page, or an earlier visit. sessionStorage
 * (not localStorage) already meant a login never survives closing the tab; this makes
 * it not even survive opening a new page within the same tab. */
function requireFreshLogin() {
  clearSession();
}
