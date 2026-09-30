export const CONFIG = {
    BASE_URL: 'http://localhost:8080',
    MAX_TOOL_ROUNDS: 3,
    DEFAULT_PAGE: 'chat'
};

export const NAV_ITEMS = [
    { page: 'chat',      icon: '💬', title: '对话' },
    { page: 'knowledge', icon: '📚', title: '知识库' },
    { page: 'keys',      icon: '🔑', title: 'API Keys' },
    { page: 'profile',   icon: '👤', title: '个人中心' },
    { page: 'settings',  icon: '⚙️', title: '设置' }
];

export const PAGE_TITLES = {
    chat: '对话',
    knowledge: '知识库',
    keys: 'API Keys',
    profile: '个人中心',
    settings: '设置'
};
