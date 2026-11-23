const notice = document.querySelector("#notice");
const rows = document.querySelector("#questionRows");
const categoryFilter = document.querySelector("#categoryFilter");
const categoryInput = document.querySelector("#categoryInput");
const questionForm = document.querySelector("#questionForm");
const questionDialog = document.querySelector("#questionDialog");
const questionDetailDialog = document.querySelector("#questionDetailDialog");
const aiDialog = document.querySelector("#aiQuestionDialog");
const aiNotice = document.querySelector("#aiNotice");
const aiPreview = document.querySelector("#aiPreview");
const aiCategoryInput = document.querySelector("#aiCategoryInput");
const poemTitleInput = document.querySelector("#poemTitleInput");
const poemTitleField = document.querySelector("#poemTitleField");
const englishWordInput = document.querySelector("#englishWordInput");
const englishWordField = document.querySelector("#englishWordField");
const loginView = document.querySelector("#loginView");
const adminView = document.querySelector("#adminView");
const page = ["settings", "questions", "poems", "records", "devices"].includes(location.pathname.slice(1)) ? location.pathname.slice(1) : "settings";
const isRegisterPage = location.pathname === "/register";
const isLoginPage = !["/settings", "/questions", "/poems", "/records", "/devices"].includes(location.pathname);
const weekdays = [
  ["1", "周一"], ["2", "周二"], ["3", "周三"], ["4", "周四"],
  ["5", "周五"], ["6", "周六"], ["7", "周日"],
];
let questions = [];
let rewardQuestions = [];
let rewardDevices = [];
let rewardQuestionForm = false;
const questionTypeFilter = document.querySelector("#questionTypeFilter");
let poemHistory = [];
let aiQuestions = [];
let refillTimer;

// Remove credentials saved by the previous version of the admin page.
sessionStorage.removeItem("tvParentalAdminToken");

function setNotice(message, isError = false) {
  notice.textContent = message;
  notice.classList.toggle("error", isError);
}

function loginAddress() {
  return `/login?next=${encodeURIComponent(["/questions", "/records", "/devices"].includes(location.pathname) ? location.pathname : "/settings")}`;
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
  let body;
  try {
    body = await response.json();
  } catch {
    const error = new Error(`后台响应异常（HTTP ${response.status}），请稍后重试`);
    error.status = response.status;
    throw error;
  }
  if (!response.ok) {
    const error = new Error(body.error || `后台返回 HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return body;
}

function renderCategories() {
  const selected = categoryFilter.value;
  const categories = [...new Set([...questions, ...rewardQuestions].map(question => question.category))].sort((a, b) => a.localeCompare(b, "zh-CN"));
  categoryFilter.replaceChildren(new Option("全部类别", ""));
  for (const category of categories) categoryFilter.add(new Option(category, category));
  categoryFilter.value = categories.includes(selected) ? selected : "";
  categoryInput.replaceChildren();
  for (const category of new Set(["古诗词", "英语", "数学", "科学", "地理历史", "其他", ...categories])) {
    categoryInput.add(new Option(category, category, category === "其他", category === "其他"));
  }
}

function renderQuestions() {
  const query = document.querySelector("#questionSearch").value.trim().toLowerCase();
  const bank = [...questions, ...rewardQuestions];
  const filtered = bank.filter(question =>
    (!questionTypeFilter.value || questionTypeFilter.value === (question.rewardMinutes ? "reward" : "regular"))
    && (!categoryFilter.value || question.category === categoryFilter.value)
    && (!query || [question.prompt, question.answer, ...question.options].join(" ").toLowerCase().includes(query))
  );
  document.querySelector("#questionResults").textContent = `显示 ${filtered.length} / ${bank.length} 道`;
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
    for (const [column, value] of [question.category, question.prompt, question.answer].entries()) {
      const td = document.createElement("td");
      td.dataset.label = ["类别", "题目", "正确答案"][column];
      if (column === 1) {
        const prompt = document.createElement("button");
        prompt.type = "button";
        prompt.className = "question-link";
        prompt.textContent = value;
        prompt.setAttribute("aria-haspopup", "dialog");
        prompt.addEventListener("click", () => showQuestionDetail(question));
        td.append(prompt);
      } else if (column === 0) {
        const badge = document.createElement("span");
        badge.className = "category-badge";
        badge.textContent = value;
        td.append(badge);
        if (question.rewardMinutes) {
          const reward = document.createElement("span");
          reward.className = "category-badge reward-question-badge";
          reward.textContent = `奖励难题 · ${question.rewardMinutes} 分钟`;
          td.append(reward);
        }
      } else {
        td.textContent = value;
      }
      tr.append(td);
    }
    const action = document.createElement("td");
    action.dataset.label = "操作";
    const actions = document.createElement("div");
    actions.className = "question-actions";
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "delete-button";
    remove.textContent = "删除";
    remove.addEventListener("click", () => deleteQuestion(question, remove));
    if (!question.rewardMinutes) {
      const priorityLabel = document.createElement("label");
      priorityLabel.className = "question-priority";
      const priority = document.createElement("input");
      priority.type = "checkbox";
      priority.checked = Boolean(question.priority);
      priority.setAttribute("aria-label", `优先出题：${question.prompt}`);
      priorityLabel.append(priority, "优先");
      priority.addEventListener("change", () => setQuestionPriority(question, priority, remove));
      actions.append(priorityLabel);
    }
    actions.append(remove);
    action.append(actions);
    tr.append(action);
    rows.append(tr);
  }
}

function showQuestionDetail(question) {
  document.querySelector("#questionDetailMeta").textContent = question.rewardMinutes
    ? `奖励难题 · 答对奖励 ${question.rewardMinutes} 分钟 · 类别：${question.category}`
    : `类别：${question.category} · 难度：${question.difficulty}`;
  document.querySelector("#questionDetailPrompt").textContent = question.prompt;
  const options = document.querySelector("#questionDetailOptions");
  options.replaceChildren();
  for (const [index, option] of question.options.entries()) {
    const item = document.createElement("li");
    item.textContent = `${"ABCD"[index]}. ${option}`;
    if (option === question.answer) {
      item.className = "correct";
      const mark = document.createElement("span");
      mark.textContent = "正确答案";
      item.append(mark);
    }
    options.append(item);
  }
  const poemSection = document.querySelector("#questionDetailPoem");
  poemSection.hidden = !question.poem;
  document.querySelector("#questionDetailPoemTitle").textContent = question.poem ? `《${question.poem.title}》 · ${question.poem.author}` : "";
  document.querySelector("#questionDetailPoemContent").textContent = question.poem?.content || "";
  questionDetailDialog.showModal();
}

function renderTimeWindows(container, day, windows, disabled) {
  container.replaceChildren();
  if (!windows.length) {
    const emptyLabel = document.createElement("span");
    emptyLabel.className = disabled ? "all-day-label no-disabled-windows-label" : "all-day-label";
    emptyLabel.textContent = disabled ? "未设置禁用时段" : "全天可用";
    container.append(emptyLabel);
    return;
  }

  for (const [index, window] of windows.entries()) {
    const row = document.createElement("div");
    row.className = disabled ? "time-window disabled-time-window" : "time-window";
    const start = document.createElement("input");
    start.type = "time";
    start.required = true;
    start.value = window.start;
    start.dataset.windowStart = "true";
    start.setAttribute("aria-label", `${day}${disabled ? "禁用" : "允许"}时段 ${index + 1} 开始时间`);
    const end = document.createElement("input");
    end.type = "time";
    end.required = true;
    end.value = window.end;
    end.dataset.windowEnd = "true";
    end.setAttribute("aria-label", `${day}${disabled ? "禁用" : "允许"}时段 ${index + 1} 结束时间`);
    const separator = document.createElement("span");
    separator.textContent = "至";
    const remove = document.createElement("button");
    remove.className = "remove-window";
    remove.type = "button";
    remove.textContent = "×";
    remove.setAttribute("aria-label", `移除${day}${disabled ? "禁用" : "允许"}时段 ${index + 1}`);
    remove.addEventListener("click", () => {
      row.remove();
      container.closest(".window-editor")?.querySelector(".add-window")?.removeAttribute("disabled");
      if (!container.querySelector(".time-window")) {
        renderTimeWindows(container, day, [], disabled);
      }
    });
    row.append(start, separator, end, remove);
    container.append(row);
  }
}

function createWindowEditor(label, windows, disabled) {
  const editor = document.createElement("div");
  editor.className = disabled ? "window-editor disabled-window-editor" : "window-editor";
  const list = document.createElement("div");
  list.className = disabled ? "window-list disabled-window-list" : "window-list";
  renderTimeWindows(list, label, windows, disabled);
  const add = document.createElement("button");
  add.className = "add-window";
  add.type = "button";
  add.textContent = disabled ? "+ 添加禁用时段" : "+ 添加时段";
  add.disabled = windows.length >= 8;
  add.addEventListener("click", () => {
    const current = [...list.querySelectorAll(".time-window")].map(item => ({
      start: item.querySelector("[data-window-start]").value,
      end: item.querySelector("[data-window-end]").value,
    }));
    if (current.length >= 8) return;
    current.push(disabled ? { start: "12:00", end: "13:00" } : { start: "08:00", end: "20:00" });
    renderTimeWindows(list, label, current, disabled);
    add.disabled = current.length >= 8;
  });
  editor.append(list, add);
  return editor;
}

function renderWeeklySettings(settings) {
  const rows = document.querySelector("#weeklyRows");
  rows.replaceChildren();
  for (const [day, label] of weekdays) {
    const row = document.createElement("tr");
    row.dataset.day = day;
    const dayCell = document.createElement("th");
    dayCell.scope = "row";
    dayCell.textContent = label;
    const limitCell = document.createElement("td");
    limitCell.dataset.label = "每日时长";
    const limit = document.createElement("input");
    limit.className = "weekly-limit-input";
    limit.type = "number";
    limit.min = "0";
    limit.max = "1440";
    limit.required = true;
    limit.value = settings.weeklyDailyLimitMinutes[day];
    limit.dataset.day = day;
    limit.setAttribute("aria-label", `${label}每日使用时长，分钟`);
    const unit = document.createElement("span");
    unit.textContent = "分钟";
    const limitControl = document.createElement("div");
    limitControl.className = "weekly-limit-control";
    limitControl.append(limit, unit);
    limitCell.append(limitControl);

    const allowedCell = document.createElement("td");
    allowedCell.dataset.label = "允许时段";
    allowedCell.append(createWindowEditor(label, settings.usageTimeWindows[day], false));
    const disabledCell = document.createElement("td");
    disabledCell.dataset.label = "禁用时段";
    disabledCell.append(createWindowEditor(label, settings.disabledTimeWindows?.[day] || [], true));
    row.append(dayCell, limitCell, allowedCell, disabledCell);
    rows.append(row);
  }
}

function renderPoemHistory() {
  const query = document.querySelector("#poemSearch").value.trim();
  const visible = poemHistory.filter(poem => `${poem.title} ${poem.author}`.includes(query));
  const container = document.querySelector("#poemRows");
  container.replaceChildren();
  document.querySelector("#poemCount").textContent = `显示 ${visible.length} / ${poemHistory.length} 首`;
  if (!visible.length) {
    setEmptyRow(container, 2, query ? "没有匹配的作品" : "暂无已出题古诗词");
    return;
  }
  for (const poem of visible) {
    const row = document.createElement("tr");
    for (const value of [`《${poem.title}》`, poem.author || "待补全"]) {
      const cell = document.createElement("td");
      cell.textContent = value;
      row.append(cell);
    }
    container.append(row);
  }
}

function updatePoemHistoryHint() {
  const normalize = value => value.replace(/[\s《》（）()·・]/g, "");
  const query = normalize(poemTitleInput.value);
  const matches = query ? poemHistory.filter(poem => {
    const title = normalize(poem.title);
    return title.includes(query) || query.includes(title);
  }) : [];
  const hint = document.querySelector("#poemHistoryHint");
  hint.hidden = !matches.length;
  hint.textContent = matches.length
    ? `已有出题记录：${matches.slice(0, 5).map(poem => `《${poem.title}》${poem.author ? `（${poem.author}）` : ""}`).join("、")}${matches.length > 5 ? "等" : ""}。请核对，避免重复添加。`
    : "";
}

async function loadPoemHistory() {
  const result = await api("/api/v1/admin/poems");
  poemHistory = result.poems;
  renderPoemHistory();
  updatePoemHistoryHint();
}

function renderDevicePicker(selectId, buttonId, devices) {
  const select = document.getElementById(selectId);
  const previous = select.value;
  select.replaceChildren();
  for (const device of devices) select.add(new Option(`${device.name} (${device.deviceId})`, device.deviceId));
  if (devices.some(device => device.deviceId === previous)) select.value = previous;
  if (!devices.length) select.add(new Option("暂无已绑定电视", ""));
  select.disabled = !devices.length;
  document.getElementById(buttonId).disabled = !devices.length;
}

function renderRewardBalance() {
  const device = rewardDevices.find(device => device.deviceId === document.querySelector("#rewardDevice").value);
  document.querySelector("#rewardBalance").textContent = device
    ? `剩余奖励时间：${formatMinutes(device.rewardBalanceMillis)}`
    : "剩余奖励时间：—";
}

async function loadRewardDevices() {
  try {
    const { devices } = await api("/api/v1/admin/devices");
    rewardDevices = devices.filter(device => device.bound);
    renderDevicePicker("rewardDevice", "grantReward", rewardDevices);
  } catch (error) {
    rewardDevices = [];
    document.querySelector("#rewardDevice").replaceChildren(new Option("电视列表加载失败", ""));
    handleError(error, document.querySelector("#rewardGrantStatus"));
  }
  renderRewardBalance();
}

async function loadState() {
  const state = await api("/api/v1/admin/state");
  if (state.version) document.querySelector("#backendVersion").textContent = `后台 v${state.version}`;
  const currentAccount = document.querySelector("#currentAccount");
  currentAccount.textContent = `账号：${state.username}`;
  currentAccount.title = currentAccount.textContent;
  renderWeeklySettings(state.settings);
  document.querySelector("#questionInterval").value = state.settings.questionIntervalMinutes;
  document.querySelector("#wrongLock").value = state.settings.wrongLockMinutes;
  document.querySelector("#syncInterval").value = state.settings.syncIntervalMinutes;
  document.querySelector("#dailyRewardLimit").value = state.settings.dailyRewardLimitMinutes;
  if (page === "devices" && !isLoginPage) {
    const { devices: allDevices } = await api("/api/v1/admin/devices");
    const devices = allDevices.filter(device => device.bound);
    renderBoundDevices(devices);
    renderDevicePicker("usageResetDevice", "resetDeviceUsage", devices);
  }
  questions = state.questions;
  rewardQuestions = state.rewardQuestions || [];
  renderCategories();
  renderQuestions();
  if (page === "poems" && !isLoginPage) await loadPoemHistory();
  if (refillTimer && questions.length) setNotice(`题库已更新，共 ${questions.length} 道题目`);
  clearTimeout(refillTimer);
  refillTimer = undefined;
  if (!isLoginPage && page === "questions" && !questions.length) {
    const refill = state.refill || {};
    setNotice(refill.error ? `题库已空：${refill.error}` : refill.running ? "题库已空，AI 正在补充 50 道综合题…" : "题库已空，等待后台自动补题…", Boolean(refill.error));
    refillTimer = setTimeout(() => loadState().catch(handleError), 5000);
  } else if (!isLoginPage && page === "questions" && state.poemEnrichment?.pending) {
    const enrichment = state.poemEnrichment;
    setNotice(enrichment.error
      ? `还有 ${enrichment.pending} 道题缺少诗词全文：${enrichment.error}`
      : `AI 正在补全诗词全文，还剩 ${enrichment.pending} 道题…`, Boolean(enrichment.error));
    refillTimer = setTimeout(() => loadState().catch(handleError), enrichment.running ? 5000 : 60000);
  }
}

function setEmptyRow(container, columnCount, message) {
  const row = document.createElement("tr");
  const cell = document.createElement("td");
  cell.colSpan = columnCount;
  cell.className = "empty";
  cell.textContent = message;
  row.append(cell);
  container.append(row);
}

function renderRecords(records) {
  const usageRows = document.querySelector("#usageRows");
  usageRows.replaceChildren();
  const deviceNames = new Map((records.deviceDetails || []).map((device) => [device.deviceId, device.name]));

  for (const record of records.usage) {
    const row = document.createElement("tr");
    const deviceName = deviceNames.get(record.deviceId) || record.deviceId;
    const device = document.createElement("td");
    device.className = "usage-device-cell";
    device.textContent = deviceName;
    device.title = deviceName === record.deviceId ? `设备 ID：${record.deviceId}` : `${deviceName} · 设备 ID：${record.deviceId}`;
    row.append(device);
    const total = document.createElement("td");
    total.className = "usage-total-cell";
    total.textContent = `总计 ${formatMinutes(record.usageMillis + (record.rewardUsedMillis || 0) + (record.parentUnlockedMillis || 0) + (record.answeringMillis || 0))}`;
    for (const [label, millis] of [["计入限额", record.usageMillis], ["奖励", record.rewardUsedMillis],
      ["家长放行", record.parentUnlockedMillis], ["答题", record.answeringMillis]]) {
      if (label !== "计入限额" && !(millis > 0)) continue;
      const detail = document.createElement("small");
      detail.textContent = `${label} ${formatMinutes(millis)}`;
      total.append(detail);
    }
    row.append(total);
    const reported = document.createElement("td");
    reported.className = "usage-report-cell";
    if (record.lastReportedAt) {
      const reportDate = new Date(record.lastReportedAt);
      reported.textContent = reportDate.toLocaleString("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" });
      reported.title = reportDate.toLocaleString("zh-CN");
    } else {
      reported.textContent = "暂无上报";
    }
    row.append(reported);
    const detailCell = document.createElement("td");
    const detailButton = document.createElement("button");
    detailButton.className = "text-button";
    detailButton.type = "button";
    detailButton.textContent = "详情";
    detailButton.setAttribute("aria-label", `查看${deviceName}的使用详情`);
    detailButton.addEventListener("click", () => showUsageDetails(record, deviceName));
    detailCell.append(detailButton);
    row.append(detailCell);
    usageRows.append(row);
  }
  if (!records.usage.length) setEmptyRow(usageRows, 4, "暂无设备记录");
  renderChallengeRecords(records, deviceNames);
}

function renderChallengeRecords(records, deviceNames) {
  const container = document.querySelector("#challengeRecords");
  const challenges = records.challenges || [];
  const answersByChallenge = new Map();
  for (const answer of records.answers || []) {
    if (!answer.challengeId) continue;
    const answers = answersByChallenge.get(answer.challengeId) || [];
    answers.push(answer);
    answersByChallenge.set(answer.challengeId, answers);
  }
  const statusLabels = {
    active: "答题中",
    answered: "全部作答",
    timeout: "倒计时结束退出",
    reboot: "关机或重启中断",
    daily_limit: "每日限额退出",
    parent_skip: "家长密码跳过",
  };
  const triggerLabels = {
    startup: "首次启动",
    interval: "答题间隔到达",
    lockout_retry: "答错锁定重试",
    reboot_resume: "关机后恢复未完成答题",
    unknown: "历史记录 / 类型未知",
  };
  container.replaceChildren();
  document.querySelector("#recordsSummary").textContent = `${records.usage.length} 台设备 · ${challenges.length} 次五题答题`;

  for (const challenge of challenges) {
    const pane = document.createElement("details");
    pane.className = "challenge-record";
    const summary = document.createElement("summary");
    summary.className = "challenge-summary";
    const main = document.createElement("span");
    main.className = "challenge-summary-main";
    const device = document.createElement("span");
    device.className = "challenge-device";
    device.textContent = challenge.deviceName || deviceNames.get(challenge.deviceId) || challenge.deviceId;
    device.title = `${device.textContent} · ${challenge.deviceId}`;
    const time = document.createElement("time");
    time.className = "challenge-time";
    time.dateTime = new Date(challenge.startedAt).toISOString();
    time.textContent = new Date(challenge.startedAt).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
    main.append(device, time);
    const meta = document.createElement("span");
    meta.className = "challenge-summary-meta";
    const trigger = document.createElement("span");
    trigger.className = "challenge-trigger";
    trigger.textContent = `弹出：${triggerLabels[challenge.trigger] || triggerLabels.unknown}`;
    if (challenge.resumedFromSessionId) trigger.title = `恢复自答题记录：${challenge.resumedFromSessionId}`;
    const status = document.createElement("span");
    status.className = `challenge-status status-${challenge.status}`;
    status.textContent = statusLabels[challenge.status] || challenge.status;
    const score = document.createElement("span");
    score.className = "challenge-score";
    score.textContent = `已答 ${challenge.answeredCount}/5 · 答对 ${challenge.correctCount}/5`;
    meta.append(trigger, status, score);
    summary.append(main, meta);
    pane.append(summary);

    const questions = document.createElement("div");
    questions.className = "challenge-questions";
    const answers = answersByChallenge.get(challenge.sessionId) || [];
    for (const [index, question] of challenge.questions.entries()) {
      const attempt = answers.find((item) => item.questionId === question.questionId);
      const row = document.createElement("div");
      row.className = "challenge-question";
      const number = document.createElement("span");
      number.className = "challenge-question-number";
      number.textContent = String(index + 1).padStart(2, "0");
      const prompt = document.createElement("span");
      prompt.className = "challenge-question-prompt";
      prompt.textContent = question.prompt;
      const result = document.createElement("span");
      const unanswered = !attempt || attempt.answered === false;
      const interrupted = challenge.interruptedQuestionId === question.questionId;
      let answerStatus;
      if (!unanswered) {
        answerStatus = attempt.correct ? "correct" : "incorrect";
      } else if (interrupted && challenge.status === "timeout") {
        answerStatus = "timeout";
      } else if (interrupted && challenge.status === "reboot") {
        answerStatus = "reboot";
      } else if (interrupted && challenge.status === "parent_skip") {
        answerStatus = "parent-skip";
      } else {
        answerStatus = attempt ? "unanswered" : "not-shown";
      }
      result.className = `challenge-question-result ${answerStatus}`;
      result.textContent = {
        correct: "答对",
        incorrect: "答错",
        timeout: "倒计时结束，未作答",
        reboot: "关机或重启时未作答",
        "parent-skip": "家长跳过，未作答",
        unanswered: "未作答",
        "not-shown": "未弹出",
      }[answerStatus];
      row.append(number, prompt, result);
      if (!unanswered) {
        const answer = document.createElement("span");
        answer.className = "challenge-question-answer";
        answer.textContent = `作答：${attempt.selectedAnswer}`;
        row.append(answer);
      }
      if (attempt?.correct) {
        if (attempt.restorable && !attempt.inQuestionBank) {
          const restoreButton = document.createElement("button");
          restoreButton.className = "text-button restore-question-button";
          restoreButton.type = "button";
          restoreButton.textContent = "放回题库";
          restoreButton.addEventListener("click", () => restoreAnsweredQuestion(attempt, restoreButton));
          row.append(restoreButton);
        } else {
          const restoreStatus = document.createElement("span");
          restoreStatus.className = "challenge-question-restore-status";
          restoreStatus.textContent = attempt.inQuestionBank ? "已在题库" : "题目内容不可恢复";
          row.append(restoreStatus);
        }
      }
      questions.append(row);
    }
    pane.append(questions);
    container.append(pane);
  }

  if (!challenges.length) {
    const empty = document.createElement("div");
    empty.className = "challenge-empty";
    empty.textContent = "当天暂无答题记录";
    container.append(empty);
  }
}

function showUsageDetails(record, deviceName) {
  const dialog = document.querySelector("#usageDetailDialog");
  const rows = document.querySelector("#usageIntervalRows");
  rows.replaceChildren();
  const intervals = record.intervals || [];
  document.querySelector("#usageDetailTitle").textContent = `${deviceName} · 使用明细`;
  const summary = [`${record.date}`, `总计 ${formatMinutes(record.usageMillis + (record.rewardUsedMillis || 0) + (record.parentUnlockedMillis || 0) + (record.answeringMillis || 0))}`,
    `计入限额 ${formatMinutes(record.usageMillis)}`];
  for (const [label, millis] of [["奖励", record.rewardUsedMillis], ["家长放行", record.parentUnlockedMillis], ["答题", record.answeringMillis]]) {
    if (millis > 0) summary.push(`${label} ${formatMinutes(millis)}`);
  }
  document.querySelector("#usageDetailSubtitle").textContent = summary.join(" · ");
  const labels = { regular: "计入限额", reward: "奖励观看", answering: "答题", parent_unlocked: "家长放行" };
  function appendDuration(periodText, millis, kind = "regular") {
    const durationSeconds = Math.max(0, Math.round(millis / 1_000));
    const hours = Math.floor(durationSeconds / 3_600);
    const minutes = Math.floor((durationSeconds % 3_600) / 60);
    const seconds = durationSeconds % 60;
    const duration = `${hours ? `${hours}小时` : ""}${minutes ? `${minutes}分` : ""}${seconds || (!hours && !minutes) ? `${seconds}秒` : ""}`;
    const row = document.createElement("tr");
    const period = document.createElement("td");
    period.className = "usage-period-cell";
    period.textContent = periodText;
    const type = document.createElement("td");
    type.textContent = labels[kind] || labels.regular;
    const elapsed = document.createElement("td");
    elapsed.className = "usage-interval-duration";
    elapsed.textContent = duration;
    row.append(period, type, elapsed);
    rows.append(row);
  }
  for (const interval of intervals) {
    const start = new Date(interval.start);
    const end = new Date(interval.end);
    appendDuration(`${start.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" })}–${end.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" })}`,
      interval.usageMillis ?? interval.end - interval.start, interval.kind);
  }
  if (record.unlocatedUsageMillis > 0) appendDuration("未定位到具体时段（历史上报缺少明细）", record.unlocatedUsageMillis);
  if (record.unlocatedRewardMillis > 0) appendDuration("未定位到具体时段（历史上报缺少明细）", record.unlocatedRewardMillis, "reward");
  if (!rows.children.length) setEmptyRow(rows, 3, "暂无使用时段");
  dialog.showModal();
}

async function loadRecords() {
  const date = document.querySelector("#recordsDate").value;
  if (!date) return;
  const records = await api(`/api/v1/admin/records?${new URLSearchParams({ date })}`);
  renderRecords(records);
}

async function restoreAnsweredQuestion(answer, button) {
  button.disabled = true;
  try {
    const result = await api("/api/v1/admin/answers/restore", {
      method: "POST",
      body: JSON.stringify({ attemptId: answer.attemptId }),
    });
    answer.inQuestionBank = true;
    const status = document.createElement("span");
    status.className = "challenge-question-restore-status";
    status.textContent = result.inQuestionBank ? "已在题库" : "已放回题库";
    button.replaceWith(status);
    setNotice(result.inQuestionBank ? "该题已在题库中" : "题目已放回题库");
  } catch (error) {
    handleError(error);
    button.disabled = false;
  }
}

function localDate() {
  const today = new Date();
  return [today.getFullYear(), String(today.getMonth() + 1).padStart(2, "0"), String(today.getDate()).padStart(2, "0")].join("-");
}

function shiftRecordsDate(days) {
  const input = document.querySelector("#recordsDate");
  const [year, month, day] = input.value.split("-").map(Number);
  const shifted = new Date(year, month - 1, day + days);
  input.value = [shifted.getFullYear(), String(shifted.getMonth() + 1).padStart(2, "0"), String(shifted.getDate()).padStart(2, "0")].join("-");
  loadRecords().catch(handleError);
}

function formatMinutes(millis = 0) {
  return `${(millis / 60_000).toFixed(1)} 分钟`;
}

function renderBoundDevices(devices) {
  const container = document.querySelector("#boundDevices");
  container.replaceChildren();
  for (const device of devices) {
    const row = document.createElement("div");
    row.className = "bound-device";
    const name = document.createElement("span");
    name.textContent = `${device.name} · ${device.deviceId}`;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "button caution";
    button.textContent = "解绑";
    button.addEventListener("click", async () => {
      if (!window.confirm(`确认解绑 ${device.name}？历史记录保留，电视可重新绑定其他账号。`)) return;
      button.disabled = true;
      try {
        await api("/api/v1/admin/devices/unbind", { method: "POST", body: JSON.stringify({ deviceId: device.deviceId }) });
        await loadState();
        setNotice("设备已解绑，电视下次联网检查后生效");
      } catch (error) {
        handleError(error);
        button.disabled = false;
      }
    });
    row.append(name, button);
    container.append(row);
  }
}

async function bindDevice(event) {
  event.preventDefault();
  const button = document.querySelector("#bindDeviceButton");
  const notice = document.querySelector("#bindingNotice");
  button.disabled = true;
  notice.textContent = "";
  try {
    const code = document.querySelector("#bindingCode").value.trim();
    const device = await api("/api/v1/admin/devices/preview", { method: "POST", body: JSON.stringify({ code }) });
    if (!window.confirm(`绑定电视 ${device.name}（${device.deviceId}）到当前账号？`)) return;
    await api("/api/v1/admin/devices/bind", { method: "POST", body: JSON.stringify({ code, deviceId: device.deviceId }) });
    document.querySelector("#bindDeviceForm").reset();
    await loadState();
    notice.classList.remove("error");
    notice.textContent = "绑定成功，电视将自动同步当前账号的题库和规则。";
  } catch (error) {
    handleError(error, notice);
  } finally {
    button.disabled = false;
  }
}

async function login(event) {
  event.preventDefault();
  const button = document.querySelector("#loginButton");
  const loginNotice = document.querySelector("#loginNotice");
  button.disabled = true;
  loginNotice.textContent = "";
  try {
    await api(isRegisterPage ? "/api/v1/admin/register" : "/api/v1/admin/login", {
      method: "POST",
      body: JSON.stringify({ username: document.querySelector("#usernameInput").value.trim(), password: document.querySelector("#passwordInput").value, confirmPassword: document.querySelector("#registerConfirmInput").value }),
    });
    document.querySelector("#passwordInput").value = "";
    if (isRegisterPage) {
      location.replace("/login?registered=1");
      return;
    }
    const next = new URLSearchParams(location.search).get("next");
    location.replace(["/settings", "/questions", "/poems", "/records", "/devices"].includes(next) ? next : "/settings");
  } catch (error) {
    handleError(error, loginNotice);
  } finally {
    button.disabled = false;
  }
}

async function changePassword(event) {
  event.preventDefault();
  const button = document.querySelector("#submitChangePassword");
  const notice = document.querySelector("#changePasswordNotice");
  const newPassword = document.querySelector("#newPasswordInput").value;
  button.disabled = true;
  notice.textContent = "";
  notice.classList.remove("error");
  if (newPassword !== document.querySelector("#confirmPasswordInput").value) {
    notice.textContent = "两次输入的新密码不一致";
    notice.classList.add("error");
    button.disabled = false;
    return;
  }
  try {
    await api("/api/v1/admin/password/change", {
      method: "POST",
      body: JSON.stringify({
        currentPassword: document.querySelector("#currentPasswordInput").value,
        newPassword,
        confirmPassword: document.querySelector("#confirmPasswordInput").value,
      }),
    });
    document.querySelector("#changePasswordDialog").close();
    setNotice("管理员密码已修改");
  } catch (error) {
    handleError(error, notice);
  } finally {
    button.disabled = false;
  }
}

async function saveSettings(event) {
  event.preventDefault();
  const button = document.querySelector("#saveSettings");
  const weeklyDailyLimitMinutes = Object.fromEntries(weekdays.map(([day]) => [
    day,
    Number(document.querySelector(`.weekly-limit-input[data-day="${day}"]`).value),
  ]));
  const usageTimeWindows = Object.fromEntries(weekdays.map(([day]) => [
    day,
    [...document.querySelectorAll(`#weeklyRows tr[data-day="${day}"] .window-list:not(.disabled-window-list) .time-window`)].map(row => ({
      start: row.querySelector("[data-window-start]").value,
      end: row.querySelector("[data-window-end]").value,
    })),
  ]));
  const disabledTimeWindows = Object.fromEntries(weekdays.map(([day]) => [
    day,
    [...document.querySelectorAll(`#weeklyRows tr[data-day="${day}"] .disabled-window-list .time-window`)].map(row => ({
      start: row.querySelector("[data-window-start]").value,
      end: row.querySelector("[data-window-end]").value,
    })),
  ]));
  if ([usageTimeWindows, disabledTimeWindows].some(byDay =>
    Object.values(byDay).some(windows => windows.some(window => window.start === window.end))
  )) {
    setNotice("时段开始和结束时间不能相同", true);
    return;
  }
  const settings = {
    weeklyDailyLimitMinutes,
    usageTimeWindows,
    disabledTimeWindows,
    questionIntervalMinutes: Number(document.querySelector("#questionInterval").value),
    wrongLockMinutes: Number(document.querySelector("#wrongLock").value),
    syncIntervalMinutes: Number(document.querySelector("#syncInterval").value),
    dailyRewardLimitMinutes: document.querySelector("#dailyRewardLimit").valueAsNumber,
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
    category: categoryInput.value,
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
    const bank = rewardQuestionForm ? "reward-questions" : "questions";
    if (rewardQuestionForm) question.rewardMinutes = document.querySelector("#challengeRewardMinutes").valueAsNumber;
    await api(`/api/v1/admin/${bank}`, { method: "POST", body: JSON.stringify(question) });
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

function renderAiQuestions(result) {
  const list = document.querySelector("#aiQuestionList");
  document.querySelector("#aiPoemTitle").textContent = result.category === "古诗词"
    ? `《${result.title}》 · ${result.author}`
    : result.category === "英语"
      ? `英语单词：${result.title}`
    : `${result.category} · 随机出题`;
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
    if (question.poem) {
      const poem = document.createElement("details");
      poem.className = "poem-reading";
      const summary = document.createElement("summary");
      summary.textContent = `诗词全文：《${question.poem.title}》 · ${question.poem.author}`;
      const content = document.createElement("p");
      content.className = "poem-content";
      content.textContent = question.poem.content;
      poem.append(summary, content);
      card.append(poem);
    }
    list.append(card);
  }
  aiPreview.hidden = false;
  updateAiSelection();
}

async function generateAiQuestions(event) {
  event.preventDefault();
  const button = document.querySelector("#generateAiQuestions");
  const countInput = document.querySelector("#aiCountInput");
  const difficultyInput = document.querySelector("#aiDifficultyInput");
  const category = aiCategoryInput.value;
  const isPoem = category === "古诗词";
  const isEnglish = category === "英语";
  if (button.disabled) return;
  button.disabled = true;
  aiCategoryInput.disabled = true;
  poemTitleInput.disabled = true;
  englishWordInput.disabled = true;
  countInput.disabled = true;
  difficultyInput.disabled = true;
  button.textContent = "正在出题…";
  aiPreview.hidden = true;
  aiQuestions = [];
  aiNotice.classList.remove("error");
  aiNotice.textContent = isPoem
    ? "正在围绕这首诗词出题，请稍候…"
    : isEnglish
      ? `正在围绕单词“${englishWordInput.value.trim()}”出题，请稍候…`
      : `正在随机生成${category}题，请稍候…`;
  document.querySelector("#aiGenerationForm").setAttribute("aria-busy", "true");
  try {
    const request = { category, count: Number(countInput.value), difficulty: difficultyInput.value };
    if (isPoem) request.title = poemTitleInput.value.trim();
    if (isEnglish) request.word = englishWordInput.value.trim();
    const result = await api("/api/v1/admin/questions/generate", {
      method: "POST", body: JSON.stringify(request),
    });
    aiQuestions = result.questions;
    renderAiQuestions(result);
    aiNotice.textContent = `已生成 ${aiQuestions.length} 道${category}题（难度：${aiQuestions[0].difficulty}），尚未加入题库`;
    if (!aiDialog.open) setNotice(`已生成 ${aiQuestions.length} 道${category}题，打开“AI 出题”查看结果`);
  } catch (error) {
    handleError(error, aiNotice);
    if (!aiDialog.open) handleError(error);
  } finally {
    button.disabled = false;
    aiCategoryInput.disabled = false;
    poemTitleInput.disabled = false;
    englishWordInput.disabled = false;
    countInput.disabled = false;
    difficultyInput.disabled = false;
    button.textContent = "重新出题";
    document.querySelector("#aiGenerationForm").removeAttribute("aria-busy");
  }
}

function updateAiCategoryFields() {
  const isPoem = aiCategoryInput.value === "古诗词";
  const isEnglish = aiCategoryInput.value === "英语";
  document.querySelector(".ai-fields").classList.toggle("random-category", !isPoem && !isEnglish);
  poemTitleField.hidden = !isPoem;
  poemTitleInput.required = isPoem;
  englishWordField.hidden = !isEnglish;
  englishWordInput.required = isEnglish;
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

async function setQuestionPriority(question, input, removeButton) {
  input.disabled = true;
  removeButton.disabled = true;
  try {
    const result = await api(`/api/v1/admin/questions/${encodeURIComponent(question.id)}/priority`, {
      method: "POST", body: JSON.stringify({ priority: input.checked }),
    });
    question.priority = result.question.priority;
    setNotice(question.priority ? "已设为优先出题，电视下次同步后生效" : "已取消优先出题，电视下次同步后生效");
  } catch (error) {
    handleError(error);
  } finally {
    input.checked = Boolean(question.priority);
    input.disabled = false;
    removeButton.disabled = false;
  }
}

async function deleteQuestion(question, button) {
  if (!window.confirm(`删除题目“${question.prompt}”？`)) return;
  button.disabled = true;
  try {
    const bank = question.rewardMinutes ? "reward-questions" : "questions";
    await api(`/api/v1/admin/${bank}/${encodeURIComponent(question.id)}`, { method: "DELETE" });
    await loadState();
    if (questions.length) setNotice("题目已删除");
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
    document.querySelector("#currentAccount").hidden = false;
    document.querySelector("#changePasswordButton").hidden = false;
    document.querySelector("#logoutButton").hidden = false;
    document.querySelector(`#${page}Page`).hidden = false;
    const currentLink = document.querySelector(`[data-page="${page}"]`);
    currentLink.setAttribute("aria-current", "page");
    if (window.matchMedia("(max-width: 1000px)").matches) currentLink.scrollIntoView({ block: "nearest", inline: "nearest" });
    const titles = { settings: "使用规则", questions: "题库管理", poems: "已出题古诗词", records: "使用记录", devices: "设备与重置" };
    document.querySelector("#currentSection").textContent = `${["questions", "poems"].includes(page) ? "内容管理" : page === "devices" ? "设备维护" : "使用管理"} / ${titles[page]}`;
    if (page === "settings") await loadRewardDevices();
    if (page === "records") {
      const dateInput = document.querySelector("#recordsDate");
      if (!dateInput.value) {
        dateInput.value = localDate();
      }
      await loadRecords();
    }
    document.title = `${titles[page]} · 健康使用`;
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

if (isRegisterPage) {
  document.querySelector("#loginTitle").textContent = "创建家长账号";
  document.querySelector("#authDescription").textContent = "每个账号独立管理自己的设备、题库与使用记录。";
  document.querySelector("#registerConfirmation").hidden = false;
  document.querySelector("#registerConfirmInput").required = true;
  document.querySelector("#passwordInput").autocomplete = "new-password";
  document.querySelector("#loginButton").textContent = "注册";
  document.querySelector("#authSwitch").href = "/login";
  document.querySelector("#authSwitch").textContent = "已有账号？登录";
} else if (new URLSearchParams(location.search).get("registered") === "1") {
  document.querySelector("#loginNotice").classList.remove("error");
  document.querySelector("#loginNotice").textContent = "注册成功，请登录。";
}
document.querySelector("#bindDeviceForm").addEventListener("submit", bindDevice);
document.querySelector("#loginForm").addEventListener("submit", login);
document.querySelector("#changePasswordButton").addEventListener("click", () => {
  document.querySelector("#changePasswordNotice").textContent = "";
  const dialog = document.querySelector("#changePasswordDialog");
  dialog.showModal();
  document.querySelector("#currentPasswordInput").focus();
});
document.querySelector("#changePasswordForm").addEventListener("submit", changePassword);
for (const id of ["closeChangePassword", "cancelChangePassword"]) {
  document.getElementById(id).addEventListener("click", () => document.querySelector("#changePasswordDialog").close());
}
document.querySelector("#changePasswordDialog").addEventListener("close", () => {
  document.querySelector("#changePasswordForm").reset();
  document.querySelector("#changePasswordButton").focus();
});
document.querySelector("#settingsForm").addEventListener("submit", saveSettings);
document.querySelector("#recordsDate").addEventListener("change", () => loadRecords().catch(handleError));

document.querySelector("#todayRecordsDate").addEventListener("click", () => {
  document.querySelector("#recordsDate").value = localDate();
  loadRecords().catch(handleError);
});
document.querySelector("#previousRecordsDate").addEventListener("click", () => shiftRecordsDate(-1));
document.querySelector("#nextRecordsDate").addEventListener("click", () => shiftRecordsDate(1));
document.querySelector("#refreshRecords").addEventListener("click", () => loadRecords().catch(handleError));
document.querySelector("#closeUsageDetail").addEventListener("click", () => document.querySelector("#usageDetailDialog").close());
document.querySelector("#resetDevicePin").addEventListener("click", async () => {
  if (!window.confirm("确认将电视 PIN 重置为“上下左右上上”？电视下次同步后生效。")) return;
  const button = document.querySelector("#resetDevicePin");
  button.disabled = true;
  try {
    await api("/api/v1/admin/password/reset", { method: "POST", body: JSON.stringify({}) });
    document.querySelector("#passwordResetStatus").textContent = "重置指令已发送，电视将在下次同步时应用。";
    setNotice("电视 PIN 重置指令已排队");
  } catch (error) {
    handleError(error);
  } finally {
    button.disabled = false;
  }
});
document.querySelector("#rewardDevice").addEventListener("change", renderRewardBalance);
document.querySelector("#rewardGrantForm").addEventListener("submit", async event => {
  event.preventDefault();
  const select = document.querySelector("#rewardDevice");
  const input = document.querySelector("#rewardMinutes");
  const button = document.querySelector("#grantReward");
  const status = document.querySelector("#rewardGrantStatus");
  if (!select.value || button.disabled) return;
  const deviceId = select.value;
  const name = select.selectedOptions[0].textContent;
  const minutes = input.valueAsNumber;
  if (!window.confirm(`为 ${name} 发放 ${minutes} 分钟奖励时长？`)) return;
  button.disabled = true;
  select.disabled = true;
  input.disabled = true;
  status.textContent = "";
  status.classList.remove("error");
  try {
    const { rewardBalanceMillis } = await api("/api/v1/admin/rewards/grant", {
      method: "POST", body: JSON.stringify({ deviceId, minutes }),
    });
    rewardDevices.find(device => device.deviceId === deviceId).rewardBalanceMillis = rewardBalanceMillis;
    renderRewardBalance();
    input.value = "";
    status.textContent = `${name} 已收到 ${minutes} 分钟奖励，余额 ${formatMinutes(rewardBalanceMillis)}。`;
  } catch (error) {
    handleError(error, status);
  } finally {
    select.disabled = false;
    input.disabled = false;
    button.disabled = !select.value;
  }
});
document.querySelector("#resetDeviceUsage").addEventListener("click", async () => {
  const select = document.querySelector("#usageResetDevice");
  if (!select.value) return;
  const deviceId = select.value;
  const name = select.selectedOptions[0].textContent;
  if (!window.confirm(`确认清零 ${name} 今天的使用时长和临时加时？电视下次同步后恢复当天配置额度，允许时段和答题规则仍然生效。`)) return;
  const button = document.querySelector("#resetDeviceUsage");
  button.disabled = true;
  try {
    const { date } = await api("/api/v1/admin/usage/reset", { method: "POST", body: JSON.stringify({ deviceId }) });
    document.querySelector("#passwordResetStatus").textContent = `${name}：${date} 使用时长重置指令已发送，电视将在下次同步时应用。`;
    setNotice("使用时长重置指令已排队");
  } catch (error) {
    handleError(error);
  } finally {
    button.disabled = false;
  }
});
document.querySelector("#logoutButton").addEventListener("click", async () => {
  try {
    await api("/api/v1/admin/logout", { method: "POST" });
    location.replace("/login");
  } catch (error) {
    handleError(error);
  }
});
function openQuestionForm(reward) {
  rewardQuestionForm = reward;
  questionForm.reset();
  document.querySelector("#questionDialogTitle").textContent = reward ? "添加奖励难题" : "添加题目";
  document.querySelector("#rewardMinutesField").hidden = !reward;
  document.querySelector("#challengeRewardMinutes").disabled = !reward;
  document.querySelector("#questionNotice").textContent = "";
  questionDialog.showModal();
  document.querySelector("#promptInput").focus();
}
document.querySelector("#addQuestionButton").addEventListener("click", () => openQuestionForm(false));
document.querySelector("#addRewardQuestionButton").addEventListener("click", () => openQuestionForm(true));
for (const id of ["closeQuestionDialog", "cancelQuestion"]) {
  document.getElementById(id).addEventListener("click", () => questionDialog.close());
}
for (const dialog of [questionDialog, questionDetailDialog]) {
  dialog.addEventListener("click", event => {
    if (event.target === dialog) {
      const bounds = dialog.getBoundingClientRect();
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dialog.close();
    }
  });
}
questionDialog.addEventListener("close", () => {
  document.querySelector(rewardQuestionForm ? "#addRewardQuestionButton" : "#addQuestionButton").focus();
});
questionForm.addEventListener("submit", addQuestion);

for (const id of ["closeQuestionDetail", "dismissQuestionDetail"]) {
  document.getElementById(id).addEventListener("click", () => questionDetailDialog.close());
}
document.querySelector("#aiQuestionButton").addEventListener("click", async () => {
  aiDialog.showModal();
  aiCategoryInput.focus();
  try {
    await loadPoemHistory();
  } catch (error) {
    handleError(error, aiNotice);
  }
});
document.querySelector("#closeAiDialog").addEventListener("click", () => aiDialog.close());
aiDialog.addEventListener("close", () => document.querySelector("#aiQuestionButton").focus());
document.querySelector("#aiGenerationForm").addEventListener("submit", generateAiQuestions);
aiCategoryInput.addEventListener("change", updateAiCategoryFields);
document.querySelector("#importAiQuestions").addEventListener("click", importAiQuestions);
categoryFilter.addEventListener("change", renderQuestions);
questionTypeFilter.addEventListener("change", renderQuestions);
document.querySelector("#questionSearch").addEventListener("input", renderQuestions);
document.querySelector("#poemSearch").addEventListener("input", renderPoemHistory);
document.querySelector("#refreshPoems").addEventListener("click", () => loadPoemHistory().catch(handleError));
poemTitleInput.addEventListener("input", updatePoemHistoryHint);
updateAiCategoryFields();
initialize();
