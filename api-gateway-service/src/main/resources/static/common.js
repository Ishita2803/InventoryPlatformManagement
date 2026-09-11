// Shared auth + fetch + app-shell helpers for login.html / admin.html / vendor.html /
// customer.html / carrier.html. Same-origin, no build step -- these pages are served by
// api-gateway-service itself.
const API = '';

// Where a role lands after login.html signs it in -- the single place that maps a role
// to its portal, so login.html and the 401/expiry handler in api() below agree.
const ROLE_HOME = {
  ADMIN: 'admin.html',
  VENDOR: 'vendor.html',
  CUSTOMER: 'customer.html',
  CARRIER: 'carrier.html'
};

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

/** Escapes text dropped into innerHTML (e.g. an admin-editable username/businessId) so it
 * renders as text, not markup -- these values come back from the API, not from a literal. */
function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/** Alias used when the value is interpolated inside an onclick="...('${...}')" attribute --
 * escapeHtml already escapes the single quote that JS string literal is delimited by, along
 * with the HTML delimiters the surrounding attribute is delimited by. */
const escapeAttr = escapeHtml;

function log(label, req, res) {
  const el = document.getElementById('log');
  if (el) el.textContent = `${label}\n\n--> ${JSON.stringify(req, null, 2)}\n\n<-- ${JSON.stringify(res, null, 2)}`;
}

/** Attaches the bearer token to every call once logged in -- the token, not a role
 * picked from a dropdown, is what the gateway's JwtAuthFilter actually trusts. A 401 on
 * an authenticated call (expired/garbage token, or a disabled account) drops the stale
 * session and returns to the login page rather than leaving the portal silently broken. */
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

  if (res.status === 401 && path !== '/auth/login') {
    clearSession();
    location.href = 'login.html';
  }
  if (!res.ok) throw { status: res.status, body: json };
  return json;
}

/** The actual "role-restricted page" gate -- call at the top of every portal page's
 * script, before it touches the DOM or fires a request. No token, or a token for a
 * different role, sends the visitor to login.html instead of rendering a page they
 * don't belong on. */
function requireRole(expectedRole) {
  const token = getToken();
  const role = getRole();
  if (!token || role !== expectedRole) {
    clearSession();
    location.href = 'login.html';
    throw new Error('not authorized for this portal -- redirecting to login');
  }
}

/** Wires up the chrome every portal page shares: avatar initials + name/role in the
 * sidebar footer, sign-out, the mobile drawer toggle, and closing that drawer on an
 * outside click. Call once, after requireRole() has already passed. */
function initShell() {
  const businessId = getBusinessId() || '?';
  const role = getRole() || '';
  const avatarEl = document.getElementById('avatarInitials');
  if (avatarEl) avatarEl.textContent = businessId.slice(0, 2).toUpperCase();
  const nameEl = document.getElementById('whoamiName');
  if (nameEl) nameEl.textContent = businessId;
  const roleEl = document.getElementById('whoamiRole');
  if (roleEl) roleEl.textContent = role;

  const overlay = document.querySelector('.sidebar-overlay');
  if (overlay) overlay.addEventListener('click', closeSidebar);
}

/** Switches the visible content-section and the matching sidebar link's active state.
 * Shared by every portal page -- each just needs a `.side-link[data-section]` per
 * section and a matching `#section-<name>` container. */
function showSection(name) {
  document.querySelectorAll('.side-link[data-section]').forEach(b => b.classList.remove('active'));
  const link = document.querySelector(`.side-link[data-section="${name}"]`);
  if (link) link.classList.add('active');

  document.querySelectorAll('.content-section').forEach(s => s.classList.remove('active'));
  const section = document.getElementById('section-' + name);
  if (section) section.classList.add('active');

  const title = document.getElementById('pageTitle');
  if (title && link) title.textContent = link.dataset.title || link.textContent.trim();

  closeSidebar();
}

function toggleSidebar() {
  document.querySelector('.sidebar')?.classList.toggle('open');
  document.querySelector('.sidebar-overlay')?.classList.toggle('visible');
}

function closeSidebar() {
  document.querySelector('.sidebar')?.classList.remove('open');
  document.querySelector('.sidebar-overlay')?.classList.remove('visible');
}

function doLogout() {
  clearSession();
  location.href = 'login.html';
}
