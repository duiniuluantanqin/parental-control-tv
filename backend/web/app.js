const notice = document.querySelector("#notice");
const rows = document.querySelector("#questionRows");
const categoryFilter = document.querySelector("#categoryFilter");
const questionForm = document.querySelector("#questionForm");
const questionDialog = document.querySelector("#questionDialog");
const loginView = document.querySelector("#loginView");
const adminView = document.querySelector("#adminView");
const page = location.pathname === "/questions" ? "questions" : "settings";
const isLoginPage = !["/settings", "/questions"].includes(location.pathname);
let questions = [];

// Remove credentials saved by the previous version of the admin page.
sessionStorage.removeItem("tvParentalAdminToken");

function setNotice(message, isError = false) {
  notice.textContent = message;
  notice.classList.toggle("error", isError);
}

function loginAddress() {
  return `/login?next=${encodeURIComponent(location.pathname === "/questions" ? "/questions" : "/settings")}`;
}

function handleError(error, target = notice) {
  if (error.status === 401 && loginView.hidden) {
    location.replace(loginAddress());
    return;
  }
  target.textContent = error.message;
  target.classList.add("error");
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.body) headers.set("Content-Type", "application/json");
  const response = await fetch(path, { ...options, headers, credentials: "same-origin" });
  const body = await response.json();
  if (!response.ok) {
    const error = new Error(body.error || `HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return body;
}

function renderCategories() {
  const selected = categoryFilter.value;
  const categories = [...new Set(questions.map(question => question.category))].sort((a, b) => a.localeCompare(b, "zh-CN"));
  categoryFilter.replaceChildren(new Option("全部类别", ""));
  for (const category of categories) categoryFilter.add(new Option(category, category));
  categoryFilter.value = categories.includes(selected) ? selected : "";
}

function renderQuestions() {
  const filtered = categoryFilter.value ? questions.filter(question => question.category === categoryFilter.value) : questions;
  document.querySelector("#questionCount").textContent = String(questions.length);
  rows.replaceChildren();
  if (!filtered.length) {
    const tr = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = 4;
    td.className = "empty";
    td.textContent = "没有符合条件的题目";
    tr.append(td);
    rows.append(tr);
    return;
  }
  for (const question of filtered) {
    const tr = document.createElement("tr");
    for (const value of [question.category, question.prompt, question.answer]) {
      const td = document.createElement("td");
      td.textContent = value;
      tr.append(td);
    }
    const action = document.createElement("td");
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "delete-button";
    remove.textContent = "删除";
    remove.addEventListener("click", () => deleteQuestion(question, remove));
    action.append(remove);
    tr.append(action);
    rows.append(tr);
  }
}

async function loadState() {
  const state = await api("/api/v1/admin/state");
  document.querySelector("#dailyLimit").value = state.settings.dailyLimitMinutes;
  document.querySelector("#questionInterval").value = state.settings.questionIntervalMinutes;
  document.querySelector("#wrongLock").value = state.settings.wrongLockMinutes;
  document.querySelector("#syncInterval").value = state.settings.syncIntervalMinutes;
  questions = state.questions;
  renderCategories();
  renderQuestions();
}

async function login(event) {
  event.preventDefault();
  const button = document.querySelector("#loginButton");
  const loginNotice = document.querySelector("#loginNotice");
  button.disabled = true;
  loginNotice.textContent = "";
  try {
    await api("/api/v1/admin/login", {
      method: "POST",
      body: JSON.stringify({ username: document.querySelector("#usernameInput").value.trim(), password: document.querySelector("#passwordInput").value }),
    });
    document.querySelector("#passwordInput").value = "";
    const next = new URLSearchParams(location.search).get("next");
    location.replace(["/settings", "/questions"].includes(next) ? next : "/settings");
  } catch (error) {
    handleError(error, loginNotice);
  } finally {
    button.disabled = false;
  }
}

async function saveSettings(event) {
  event.preventDefault();
  const button = document.querySelector("#saveSettings");
  const settings = {
    dailyLimitMinutes: Number(document.querySelector("#dailyLimit").value),
    questionIntervalMinutes: Number(document.querySelector("#questionInterval").value),
    wrongLockMinutes: Number(document.querySelector("#wrongLock").value),
    syncIntervalMinutes: Number(document.querySelector("#syncInterval").value),
  };
  button.disabled = true;
  try {
    await api("/api/v1/admin/settings", { method: "POST", body: JSON.stringify(settings) });
    setNotice("规则已保存，电视将在下次同步时应用");
  } catch (error) {
    handleError(error);
  } finally {
    button.disabled = false;
  }
}

async function addQuestion(event) {
  event.preventDefault();
  const button = document.querySelector("#submitQuestion");
  const options = [...document.querySelectorAll(".option-input")].map(input => input.value.trim());
  const answerIndex = Number(document.querySelector("#answerInput").value);
  const question = {
    prompt: document.querySelector("#promptInput").value.trim(),
    category: document.querySelector("#categoryInput").value.trim(),
    difficulty: document.querySelector("#difficultyInput").value,
    options,
    answer: options[answerIndex],
  };
  if (new Set(options).size !== 4) {
    document.querySelector("#questionNotice").textContent = "四个选项需要各不相同";
    return;
  }
  button.disabled = true;
  document.querySelector("#questionNotice").textContent = "";
  try {
    await api("/api/v1/admin/questions", { method: "POST", body: JSON.stringify(question) });
    questionDialog.close();
    await loadState();
    setNotice("题目已添加");
  } catch (error) {
    handleError(error, questionDialog.open ? document.querySelector("#questionNotice") : notice);
  } finally {
    button.disabled = false;
  }
}

async function deleteQuestion(question, button) {
  if (!window.confirm(`删除题目“${question.prompt}”？`)) return;
  button.disabled = true;
  try {
    await api(`/api/v1/admin/questions/${encodeURIComponent(question.id)}`, { method: "DELETE" });
    await loadState();
    setNotice("题目已删除");
  } catch (error) {
    handleError(error);
    button.disabled = false;
  }
}

async function initialize() {
  try {
    await loadState();
    if (isLoginPage) {
      location.replace("/settings");
      return;
    }
    adminView.hidden = false;
    document.querySelector("#logoutButton").hidden = false;
    document.querySelector(`#${page}Page`).hidden = false;
    document.querySelector(`[data-page="${page}"]`).setAttribute("aria-current", "page");
    document.title = `${page === "questions" ? "题库管理" : "使用规则"} · 看电视前答题`;
  } catch (error) {
    if (error.status === 401 && !isLoginPage) {
      location.replace(loginAddress());
      return;
    }
    loginView.hidden = false;
    if (error.status !== 401) handleError(error, document.querySelector("#loginNotice"));
    document.querySelector("#usernameInput").focus();
  } finally {
    document.querySelector("#loadingStatus").hidden = true;
  }
}

document.querySelector("#loginForm").addEventListener("submit", login);
document.querySelector("#settingsForm").addEventListener("submit", saveSettings);
document.querySelector("#logoutButton").addEventListener("click", async () => {
  try {
    await api("/api/v1/admin/logout", { method: "POST" });
    location.replace("/login");
  } catch (error) {
    handleError(error);
  }
});
document.querySelector("#addQuestionButton").addEventListener("click", () => {
  questionForm.reset();
  document.querySelector("#questionNotice").textContent = "";
  questionDialog.showModal();
  document.querySelector("#promptInput").focus();
});
for (const id of ["closeQuestionDialog", "cancelQuestion"]) {
  document.getElementById(id).addEventListener("click", () => questionDialog.close());
}
questionDialog.addEventListener("click", event => {
  if (event.target === questionDialog) {
    const bounds = questionDialog.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) questionDialog.close();
  }
});
questionDialog.addEventListener("close", () => document.querySelector("#addQuestionButton").focus());
questionForm.addEventListener("submit", addQuestion);
categoryFilter.addEventListener("change", renderQuestions);
initialize();
