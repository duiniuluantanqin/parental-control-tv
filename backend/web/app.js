const tokenInput = document.querySelector("#tokenInput");
const notice = document.querySelector("#notice");
const rows = document.querySelector("#questionRows");
const categoryFilter = document.querySelector("#categoryFilter");
const questionForm = document.querySelector("#questionForm");
let questions = [];

tokenInput.value = sessionStorage.getItem("tvParentalAdminToken") || "";

function setNotice(message, isError = false) {
  notice.textContent = message;
  notice.classList.toggle("error", isError);
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (tokenInput.value) headers.set("Authorization", `Bearer ${tokenInput.value.trim()}`);
  if (options.body) headers.set("Content-Type", "application/json");
  const response = await fetch(path, { ...options, headers });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
  return body;
}

function enableControls(enabled) {
  for (const id of ["dailyLimit", "questionInterval", "wrongLock", "syncInterval", "saveSettings", "categoryFilter", "toggleQuestionForm"]) {
    document.getElementById(id).disabled = !enabled;
  }
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
    remove.addEventListener("click", () => deleteQuestion(question));
    action.append(remove);
    tr.append(action);
    rows.append(tr);
  }
}

async function connect() {
  try {
    const state = await api("/api/v1/admin/state");
    sessionStorage.setItem("tvParentalAdminToken", tokenInput.value.trim());
    document.querySelector("#connectionDot").classList.add("online");
    document.querySelector("#connectionText").textContent = "已连接";
    enableControls(true);
    document.querySelector("#dailyLimit").value = state.settings.dailyLimitMinutes;
    document.querySelector("#questionInterval").value = state.settings.questionIntervalMinutes;
    document.querySelector("#wrongLock").value = state.settings.wrongLockMinutes;
    document.querySelector("#syncInterval").value = state.settings.syncIntervalMinutes ?? 5;
    questions = state.questions;
    renderCategories();
    renderQuestions();
    setNotice("数据已同步");
  } catch (error) {
    document.querySelector("#connectionDot").classList.remove("online");
    document.querySelector("#connectionText").textContent = "未连接";
    enableControls(false);
    setNotice(error.message, true);
  }
}

async function saveSettings() {
  const settings = {
    dailyLimitMinutes: Number(document.querySelector("#dailyLimit").value),
    questionIntervalMinutes: Number(document.querySelector("#questionInterval").value),
    wrongLockMinutes: Number(document.querySelector("#wrongLock").value),
    syncIntervalMinutes: Number(document.querySelector("#syncInterval").value),
  };
  try {
    await api("/api/v1/admin/settings", { method: "POST", body: JSON.stringify(settings) });
    setNotice("设置已保存，电视将在下次同步时应用");
  } catch (error) {
    setNotice(error.message, true);
  }
}

async function addQuestion(event) {
  event.preventDefault();
  const options = [...document.querySelectorAll(".option-input")].map(input => input.value.trim());
  const answerIndex = Number(document.querySelector("#answerInput").value);
  const question = {
    prompt: document.querySelector("#promptInput").value.trim(),
    category: document.querySelector("#categoryInput").value.trim(),
    difficulty: document.querySelector("#difficultyInput").value,
    options,
    answer: options[answerIndex],
  };
  try {
    await api("/api/v1/admin/questions", { method: "POST", body: JSON.stringify(question) });
    questionForm.reset();
    document.querySelector("#categoryInput").value = "其他";
    questionForm.hidden = true;
    await connect();
  } catch (error) {
    setNotice(error.message, true);
  }
}

async function deleteQuestion(question) {
  if (!window.confirm(`删除题目“${question.prompt}”？`)) return;
  try {
    await api(`/api/v1/admin/questions/${encodeURIComponent(question.id)}`, { method: "DELETE" });
    await connect();
  } catch (error) {
    setNotice(error.message, true);
  }
}

document.querySelector("#connectButton").addEventListener("click", connect);
document.querySelector("#saveSettings").addEventListener("click", saveSettings);
document.querySelector("#toggleQuestionForm").addEventListener("click", () => { questionForm.hidden = !questionForm.hidden; });
document.querySelector("#closeQuestionForm").addEventListener("click", () => { questionForm.hidden = true; });
document.querySelector("#cancelQuestion").addEventListener("click", () => { questionForm.reset(); questionForm.hidden = true; });
document.querySelector("#questionForm").addEventListener("submit", addQuestion);
categoryFilter.addEventListener("change", renderQuestions);
if (tokenInput.value) connect();
