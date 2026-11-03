const notice = document.querySelector("#notice");
const rows = document.querySelector("#questionRows");
const categoryFilter = document.querySelector("#categoryFilter");
const questionForm = document.querySelector("#questionForm");
const questionDialog = document.querySelector("#questionDialog");
const aiDialog = document.querySelector("#aiQuestionDialog");
const aiNotice = document.querySelector("#aiNotice");
const aiPreview = document.querySelector("#aiPreview");
const loginView = document.querySelector("#loginView");
const adminView = document.querySelector("#adminView");
const page = location.pathname === "/questions" ? "questions" : "settings";
const isLoginPage = !["/settings", "/questions"].includes(location.pathname);
let questions = [];
let aiQuestions = [];

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

function updateAiSelection() {
  const count = document.querySelectorAll("#aiQuestionList input:checked").length;
  const button = document.querySelector("#importAiQuestions");
  button.textContent = `加入题库（${count} 道）`;
  button.disabled = count === 0;
}

function renderAiQuestions(poem) {
  const list = document.querySelector("#aiQuestionList");
  document.querySelector("#aiPoemTitle").textContent = `《${poem.title}》 · ${poem.author}`;
  list.replaceChildren();
  for (const [index, question] of aiQuestions.entries()) {
    const card = document.createElement("article");
    card.className = "ai-question";
    const label = document.createElement("label");
    label.className = "ai-question-heading";
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.value = String(index);
    checkbox.checked = true;
    checkbox.addEventListener("change", updateAiSelection);
    const prompt = document.createElement("span");
    prompt.textContent = `${index + 1}. ${question.prompt}`;
    label.append(checkbox, prompt);
    const options = document.createElement("ul");
    options.className = "ai-options";
    for (const [optionIndex, option] of question.options.entries()) {
      const item = document.createElement("li");
      item.textContent = `${"ABCD"[optionIndex]}. ${option}`;
      if (option === question.answer) {
        item.className = "correct";
        const mark = document.createElement("span");
        mark.textContent = "正确答案";
        item.append(mark);
      }
      options.append(item);
    }
    card.append(label, options);
    list.append(card);
  }
  aiPreview.hidden = false;
  updateAiSelection();
}

async function generateAiQuestions(event) {
  event.preventDefault();
  const button = document.querySelector("#generateAiQuestions");
  const titleInput = document.querySelector("#poemTitleInput");
  const countInput = document.querySelector("#aiCountInput");
  if (button.disabled) return;
  button.disabled = true;
  titleInput.disabled = true;
  countInput.disabled = true;
  button.textContent = "正在出题…";
  aiPreview.hidden = true;
  aiQuestions = [];
  aiNotice.classList.remove("error");
  aiNotice.textContent = "正在围绕这首诗词出题，请稍候…";
  document.querySelector("#aiGenerationForm").setAttribute("aria-busy", "true");
  try {
    const poem = await api("/api/v1/admin/questions/generate", {
      method: "POST", body: JSON.stringify({ title: titleInput.value.trim(), count: Number(countInput.value) }),
    });
    aiQuestions = poem.questions;
    renderAiQuestions(poem);
    aiNotice.textContent = `已生成 ${aiQuestions.length} 道题，尚未加入题库`;
    if (!aiDialog.open) setNotice(`《${poem.title}》已生成，打开“AI 出题”查看结果`);
  } catch (error) {
    handleError(error, aiNotice);
    if (!aiDialog.open) handleError(error);
  } finally {
    button.disabled = false;
    titleInput.disabled = false;
    countInput.disabled = false;
    button.textContent = "重新出题";
    document.querySelector("#aiGenerationForm").removeAttribute("aria-busy");
  }
}

async function importAiQuestions() {
  const selected = [...document.querySelectorAll("#aiQuestionList input:checked")].map(input => aiQuestions[Number(input.value)]);
  if (!selected.length) return;
  const button = document.querySelector("#importAiQuestions");
  const generateButton = document.querySelector("#generateAiQuestions");
  const checkboxes = document.querySelectorAll("#aiQuestionList input");
  button.disabled = true;
  generateButton.disabled = true;
  for (const checkbox of checkboxes) checkbox.disabled = true;
  aiNotice.classList.remove("error");
  try {
    const result = await api("/api/v1/admin/questions/batch", { method: "POST", body: JSON.stringify({ questions: selected }) });
    aiQuestions = [];
    aiPreview.hidden = true;
    aiNotice.textContent = "";
    aiDialog.close();
    await loadState();
    setNotice(`已加入 ${result.added} 道题目${result.skipped ? `，跳过 ${result.skipped} 道重复题目` : ""}`);
  } catch (error) {
    handleError(error, aiDialog.open ? aiNotice : notice);
  } finally {
    generateButton.disabled = false;
    for (const checkbox of checkboxes) checkbox.disabled = false;
    updateAiSelection();
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
document.querySelector("#aiQuestionButton").addEventListener("click", () => {
  aiDialog.showModal();
  document.querySelector("#poemTitleInput").focus();
});
document.querySelector("#closeAiDialog").addEventListener("click", () => aiDialog.close());
aiDialog.addEventListener("close", () => document.querySelector("#aiQuestionButton").focus());
document.querySelector("#aiGenerationForm").addEventListener("submit", generateAiQuestions);
document.querySelector("#importAiQuestions").addEventListener("click", importAiQuestions);
categoryFilter.addEventListener("change", renderQuestions);
initialize();
