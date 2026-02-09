/**
 * Jimi Web - 前端应用
 * Manus 风格的 AI Agent 交互界面
 */
(function () {
    'use strict';

    // ==================== State ====================
    const state = {
        sessions: [],
        currentSessionId: null,
        isExecuting: false,
        messages: {},       // sessionId -> [messages]
        eventSource: null,  // current SSE connection
    };

    // ==================== DOM Elements ====================
    const $ = (sel) => document.querySelector(sel);
    const $$ = (sel) => document.querySelectorAll(sel);

    const els = {
        sessionList: $('#session-list'),
        welcomePage: $('#welcome-page'),
        chatPage: $('#chat-page'),
        chatTitle: $('#chat-title'),
        chatStatus: $('#chat-status'),
        chatMessages: $('#chat-messages'),
        chatInput: $('#chat-input'),
        btnSend: $('#btn-send'),
        btnCancel: $('#btn-cancel'),
        btnNewSession: $('#btn-new-session'),
        btnWelcomeNew: $('#btn-welcome-new'),
        agentSelect: $('#agent-select'),
        modalOverlay: $('#modal-overlay'),
        modalClose: $('#modal-close'),
        modalCancelBtn: $('#modal-cancel-btn'),
        modalCreateBtn: $('#modal-create-btn'),
        inputWorkdir: $('#input-workdir'),
        inputAgent: $('#input-agent'),
    };

    // ==================== Markdown Config ====================
    if (typeof marked !== 'undefined') {
        marked.setOptions({
            highlight: function (code, lang) {
                if (typeof hljs !== 'undefined' && lang && hljs.getLanguage(lang)) {
                    return hljs.highlight(code, { language: lang }).value;
                }
                return code;
            },
            breaks: true,
        });
    }

    // ==================== API ====================
    const api = {
        async createSession(workDir, agentName) {
            const res = await fetch('/api/sessions', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ workDir, agentName }),
            });
            return res.json();
        },

        async listSessions() {
            const res = await fetch('/api/sessions');
            return res.json();
        },

        async closeSession(id) {
            await fetch(`/api/sessions/${id}`, { method: 'DELETE' });
        },

        async getAgents() {
            const res = await fetch('/api/agents');
            return res.json();
        },

        async cancelTask(sessionId) {
            await fetch(`/api/chat/${sessionId}/cancel`, { method: 'POST' });
        },

        chat(sessionId, message, onEvent, onDone, onError) {
            // Use fetch + ReadableStream for POST SSE
            const controller = new AbortController();

            fetch(`/api/chat/${sessionId}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ message }),
                signal: controller.signal,
            }).then(response => {
                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                let buffer = '';

                function read() {
                    reader.read().then(({ done, value }) => {
                        if (done) {
                            onDone();
                            return;
                        }

                        buffer += decoder.decode(value, { stream: true });
                        const lines = buffer.split('\n');
                        buffer = lines.pop() || '';

                        let currentEvent = null;
                        for (const line of lines) {
                            if (line.startsWith('event:')) {
                                currentEvent = line.substring(6).trim();
                            } else if (line.startsWith('data:')) {
                                const data = line.substring(5).trim();
                                if (data) {
                                    try {
                                        const parsed = JSON.parse(data);
                                        onEvent(currentEvent || parsed.type, parsed);
                                    } catch (e) {
                                        // ignore parse errors
                                    }
                                }
                            }
                        }

                        read();
                    }).catch(err => {
                        if (err.name !== 'AbortError') {
                            onError(err);
                        }
                    });
                }

                read();
            }).catch(err => {
                if (err.name !== 'AbortError') {
                    onError(err);
                }
            });

            return controller;
        }
    };

    // ==================== Session Management ====================
    async function loadAgents() {
        try {
            const agents = await api.getAgents();
            [els.agentSelect, els.inputAgent].forEach(select => {
                select.innerHTML = agents.map(a =>
                    `<option value="${a}">${a}</option>`
                ).join('');
            });
        } catch (e) {
            console.error('加载 Agent 列表失败', e);
        }
    }

    async function refreshSessions() {
        try {
            state.sessions = await api.listSessions();
            renderSessionList();
        } catch (e) {
            console.error('加载会话列表失败', e);
        }
    }

    function renderSessionList() {
        if (state.sessions.length === 0) {
            els.sessionList.innerHTML = '<div class="empty-state">暂无会话，点击上方按钮创建</div>';
            return;
        }

        els.sessionList.innerHTML = state.sessions.map(s => `
            <div class="session-item ${s.id === state.currentSessionId ? 'active' : ''}"
                 data-id="${s.id}">
                <span class="session-item-name" title="${s.displayName}">
                    ${s.displayName}
                </span>
                <button class="session-item-close" data-close-id="${s.id}" title="关闭">&times;</button>
            </div>
        `).join('');

        // Bind click events
        els.sessionList.querySelectorAll('.session-item').forEach(item => {
            item.addEventListener('click', (e) => {
                if (e.target.classList.contains('session-item-close')) return;
                switchSession(item.dataset.id);
            });
        });

        els.sessionList.querySelectorAll('.session-item-close').forEach(btn => {
            btn.addEventListener('click', async (e) => {
                e.stopPropagation();
                const id = btn.dataset.closeId;
                await api.closeSession(id);
                if (state.currentSessionId === id) {
                    state.currentSessionId = null;
                    showWelcome();
                }
                await refreshSessions();
            });
        });
    }

    async function createNewSession() {
        const workDir = els.inputWorkdir.value.trim() || null;
        const agentName = els.inputAgent.value;

        try {
            const session = await api.createSession(workDir, agentName);
            state.messages[session.id] = [];
            closeModal();
            await refreshSessions();
            switchSession(session.id);
        } catch (e) {
            alert('创建会话失败: ' + e.message);
        }
    }

    function switchSession(sessionId) {
        state.currentSessionId = sessionId;
        const session = state.sessions.find(s => s.id === sessionId);
        if (!session) return;

        // Init message array if needed
        if (!state.messages[sessionId]) {
            state.messages[sessionId] = [];
        }

        showChat();
        els.chatTitle.textContent = session.displayName;
        renderMessages();
        renderSessionList();
        els.chatInput.focus();
    }

    // ==================== UI State ====================
    function showWelcome() {
        els.welcomePage.classList.remove('hidden');
        els.chatPage.classList.add('hidden');
    }

    function showChat() {
        els.welcomePage.classList.add('hidden');
        els.chatPage.classList.remove('hidden');
    }

    function setExecuting(executing) {
        state.isExecuting = executing;
        els.btnSend.disabled = executing;
        els.chatInput.disabled = executing;
        els.btnCancel.classList.toggle('hidden', !executing);
        els.chatStatus.textContent = executing ? '执行中...' : '就绪';
        els.chatStatus.classList.toggle('running', executing);
    }

    // ==================== Message Rendering ====================
    function renderMessages() {
        const messages = state.messages[state.currentSessionId] || [];
        els.chatMessages.innerHTML = '';

        for (const msg of messages) {
            appendMessageElement(msg);
        }

        scrollToBottom();
    }

    function appendMessageElement(msg) {
        const div = document.createElement('div');

        switch (msg.type) {
            case 'user':
                div.className = 'message message-user';
                div.innerHTML = `<div class="message-content">${escapeHtml(msg.content)}</div>`;
                break;

            case 'assistant':
                div.className = 'message message-assistant';
                div.innerHTML = `<div class="message-content">${renderMarkdown(msg.content)}</div>`;
                break;

            case 'step_begin':
                div.className = 'step-indicator';
                div.innerHTML = `<span class="step-dot ${msg.active ? 'active' : ''}"></span> Step ${msg.stepNumber}`;
                break;

            case 'tool_call':
                div.className = 'tool-block';
                const argsDisplay = msg.args ? formatToolArgs(msg.args) : '';
                div.innerHTML = `
                    <div class="tool-header" onclick="this.nextElementSibling.classList.toggle('show'); this.querySelector('.tool-toggle').classList.toggle('expanded')">
                        <span class="tool-icon">🔧</span>
                        <span class="tool-name">${escapeHtml(msg.toolName)}</span>
                        <span class="tool-label">调用中...</span>
                        <span class="tool-toggle">▼</span>
                    </div>
                    <div class="tool-body">${escapeHtml(argsDisplay)}</div>
                `;
                break;

            case 'tool_result':
                div.className = 'tool-block';
                div.innerHTML = `
                    <div class="tool-header" onclick="this.nextElementSibling.classList.toggle('show'); this.querySelector('.tool-toggle').classList.toggle('expanded')">
                        <span class="tool-icon">✅</span>
                        <span class="tool-name">${escapeHtml(msg.toolName)}</span>
                        <span class="tool-label">完成</span>
                        <span class="tool-toggle">▼</span>
                    </div>
                    <div class="tool-body">${escapeHtml(truncate(msg.content, 2000))}</div>
                `;
                break;

            case 'reasoning':
                div.className = 'reasoning-block';
                div.innerHTML = `<div class="reasoning-label">💭 思考</div>${escapeHtml(msg.content)}`;
                break;

            case 'error':
                div.className = 'error-block';
                div.innerHTML = `⚠️ ${escapeHtml(msg.content)}`;
                break;

            case 'thinking':
                div.className = 'thinking';
                div.innerHTML = `<div class="thinking-dots"><span></span><span></span><span></span></div> 正在思考...`;
                div.id = 'thinking-indicator';
                break;

            default:
                return;
        }

        els.chatMessages.appendChild(div);
    }

    function scrollToBottom() {
        requestAnimationFrame(() => {
            els.chatMessages.scrollTop = els.chatMessages.scrollHeight;
        });
    }

    // ==================== Chat / SSE ====================
    let currentController = null;
    let assistantBuffer = '';
    let currentAssistantDiv = null;
    let reasoningBuffer = '';
    let currentReasoningDiv = null;

    function sendMessage() {
        const input = els.chatInput.value.trim();
        if (!input || !state.currentSessionId || state.isExecuting) return;

        const sessionId = state.currentSessionId;

        // Add user message
        addMessage(sessionId, { type: 'user', content: input });
        els.chatInput.value = '';
        autoResizeInput();

        // Reset streaming buffers
        assistantBuffer = '';
        currentAssistantDiv = null;
        reasoningBuffer = '';
        currentReasoningDiv = null;

        setExecuting(true);

        // Add thinking indicator
        addMessage(sessionId, { type: 'thinking' });

        // Start SSE
        currentController = api.chat(
            sessionId,
            input,
            // onEvent
            (eventType, data) => handleStreamEvent(sessionId, eventType, data),
            // onDone
            () => {
                removeThinking();
                finalizeAssistantMessage(sessionId);
                setExecuting(false);
                currentController = null;
            },
            // onError
            (err) => {
                removeThinking();
                finalizeAssistantMessage(sessionId);
                addMessage(sessionId, { type: 'error', content: err.message || '连接错误' });
                setExecuting(false);
                currentController = null;
            }
        );

        scrollToBottom();
    }

    function handleStreamEvent(sessionId, eventType, data) {
        if (sessionId !== state.currentSessionId) return;

        removeThinking();

        switch (data.type) {
            case 'TEXT':
                appendAssistantText(data.content || '');
                break;

            case 'REASONING':
                appendReasoning(data.content || '');
                break;

            case 'TOOL_CALL':
                finalizeAssistantMessage(sessionId);
                addMessage(sessionId, {
                    type: 'tool_call',
                    toolName: data.toolName,
                    toolCallId: data.toolCallId,
                    args: data.toolArgs,
                });
                break;

            case 'TOOL_RESULT':
                addMessage(sessionId, {
                    type: 'tool_result',
                    toolName: data.toolName,
                    content: data.content,
                });
                break;

            case 'STEP_BEGIN':
                finalizeAssistantMessage(sessionId);
                finalizeReasoning(sessionId);
                addMessage(sessionId, {
                    type: 'step_begin',
                    stepNumber: data.stepNumber,
                    active: true,
                });
                break;

            case 'STEP_END':
                // Deactivate step dot
                const dots = els.chatMessages.querySelectorAll('.step-dot.active');
                dots.forEach(d => d.classList.remove('active'));
                break;

            case 'ERROR':
                finalizeAssistantMessage(sessionId);
                addMessage(sessionId, { type: 'error', content: data.content });
                break;

            case 'DONE':
                finalizeAssistantMessage(sessionId);
                break;
        }

        scrollToBottom();
    }

    function appendAssistantText(text) {
        assistantBuffer += text;

        if (!currentAssistantDiv) {
            currentAssistantDiv = document.createElement('div');
            currentAssistantDiv.className = 'message message-assistant';
            currentAssistantDiv.innerHTML = '<div class="message-content"></div>';
            els.chatMessages.appendChild(currentAssistantDiv);
        }

        const contentDiv = currentAssistantDiv.querySelector('.message-content');
        contentDiv.innerHTML = renderMarkdown(assistantBuffer);
    }

    function finalizeAssistantMessage(sessionId) {
        if (assistantBuffer && currentAssistantDiv) {
            const msgs = state.messages[sessionId];
            if (msgs) {
                msgs.push({ type: 'assistant', content: assistantBuffer });
            }
            // Re-render with final markdown + highlight
            const contentDiv = currentAssistantDiv.querySelector('.message-content');
            contentDiv.innerHTML = renderMarkdown(assistantBuffer);
            highlightCode(contentDiv);
        }
        assistantBuffer = '';
        currentAssistantDiv = null;
    }

    function appendReasoning(text) {
        reasoningBuffer += text;

        if (!currentReasoningDiv) {
            currentReasoningDiv = document.createElement('div');
            currentReasoningDiv.className = 'reasoning-block';
            currentReasoningDiv.innerHTML = '<div class="reasoning-label">💭 思考</div><span class="reasoning-text"></span>';
            els.chatMessages.appendChild(currentReasoningDiv);
        }

        const textSpan = currentReasoningDiv.querySelector('.reasoning-text');
        textSpan.textContent = reasoningBuffer;
    }

    function finalizeReasoning(sessionId) {
        if (reasoningBuffer && currentReasoningDiv) {
            const msgs = state.messages[sessionId];
            if (msgs) {
                msgs.push({ type: 'reasoning', content: reasoningBuffer });
            }
        }
        reasoningBuffer = '';
        currentReasoningDiv = null;
    }

    function addMessage(sessionId, msg) {
        const msgs = state.messages[sessionId];
        if (msgs && msg.type !== 'thinking') {
            msgs.push(msg);
        }
        if (sessionId === state.currentSessionId) {
            appendMessageElement(msg);
            scrollToBottom();
        }
    }

    function removeThinking() {
        const el = document.getElementById('thinking-indicator');
        if (el) el.remove();
    }

    async function cancelExecution() {
        if (!state.currentSessionId) return;
        try {
            await api.cancelTask(state.currentSessionId);
        } catch (e) {
            console.error('取消失败', e);
        }
        if (currentController) {
            currentController.abort();
            currentController = null;
        }
        removeThinking();
        finalizeAssistantMessage(state.currentSessionId);
        setExecuting(false);
    }

    // ==================== Modal ====================
    function openModal() {
        els.inputWorkdir.value = '';
        els.modalOverlay.classList.remove('hidden');
        els.inputWorkdir.focus();
    }

    function closeModal() {
        els.modalOverlay.classList.add('hidden');
    }

    // ==================== Helpers ====================
    function renderMarkdown(text) {
        if (!text) return '';
        if (typeof marked !== 'undefined') {
            try { return marked.parse(text); } catch (e) { /* fallback */ }
        }
        return escapeHtml(text).replace(/\n/g, '<br>');
    }

    function highlightCode(container) {
        if (typeof hljs !== 'undefined') {
            container.querySelectorAll('pre code').forEach(block => {
                hljs.highlightElement(block);
            });
        }
    }

    function escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    function truncate(text, maxLen) {
        if (!text) return '';
        if (text.length <= maxLen) return text;
        return text.substring(0, maxLen) + '\n... (截断)';
    }

    function formatToolArgs(args) {
        if (!args) return '';
        try {
            const parsed = JSON.parse(args);
            return JSON.stringify(parsed, null, 2);
        } catch (e) {
            return args;
        }
    }

    function autoResizeInput() {
        const el = els.chatInput;
        el.style.height = 'auto';
        el.style.height = Math.min(el.scrollHeight, 150) + 'px';
    }

    // ==================== Event Bindings ====================
    function bindEvents() {
        // New session buttons
        els.btnNewSession.addEventListener('click', openModal);
        els.btnWelcomeNew.addEventListener('click', openModal);

        // Modal
        els.modalClose.addEventListener('click', closeModal);
        els.modalCancelBtn.addEventListener('click', closeModal);
        els.modalCreateBtn.addEventListener('click', createNewSession);
        els.modalOverlay.addEventListener('click', (e) => {
            if (e.target === els.modalOverlay) closeModal();
        });

        // Send
        els.btnSend.addEventListener('click', sendMessage);
        els.chatInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                sendMessage();
            }
        });
        els.chatInput.addEventListener('input', autoResizeInput);

        // Cancel
        els.btnCancel.addEventListener('click', cancelExecution);

        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                if (!els.modalOverlay.classList.contains('hidden')) {
                    closeModal();
                } else if (state.isExecuting) {
                    cancelExecution();
                }
            }
        });
    }

    // ==================== Init ====================
    async function init() {
        bindEvents();
        await loadAgents();
        await refreshSessions();

        // Auto-select first session if exists
        if (state.sessions.length > 0) {
            switchSession(state.sessions[0].id);
        }
    }

    // Boot
    init().catch(e => console.error('初始化失败', e));
})();
