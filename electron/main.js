const { app, BrowserWindow, screen } = require('electron');

const FLOAT_MODE = process.env.FLOAT_MODE === 'true';
const TARGET_URL = 'http://localhost:8080';

// Retry interval (ms) when Spring Boot hasn't started yet
const RETRY_INTERVAL = 2000;
const MAX_RETRIES = 30;

function createWindow() {
  let win;

  if (FLOAT_MODE) {
    // ── Floating overlay: 420×800, frameless, always-on-top, transparent ──
    const { width: sw } = screen.getPrimaryDisplay().workAreaSize;

    win = new BrowserWindow({
      width:           420,
      height:          800,
      x:               sw - 440,   // 20px margin from right edge
      y:               60,
      frame:           false,
      transparent:     true,
      alwaysOnTop:     true,
      resizable:       false,
      skipTaskbar:     false,
      webPreferences: {
        nodeIntegration: false,
        contextIsolation: true,
      },
    });

    // Allow dragging the frameless window by the page body
    win.setMovable(true);

  } else {
    // ── Normal window: 1400×900, framed ──
    win = new BrowserWindow({
      width:  1400,
      height: 900,
      center: true,
      webPreferences: {
        nodeIntegration: false,
        contextIsolation: true,
      },
    });
  }

  win.setTitle('CryptoMind');

  // Load with retry until Spring Boot is ready
  loadWithRetry(win, TARGET_URL, 0);

  return win;
}

function loadWithRetry(win, url, attempt) {
  win.loadURL(url).catch(() => {
    if (attempt >= MAX_RETRIES) {
      win.loadURL(`data:text/html,<body style="background:#0d1117;color:#8b949e;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0"><p>无法连接到 ${url}，请确认 Spring Boot 已启动。</p></body>`);
      return;
    }
    setTimeout(() => loadWithRetry(win, url, attempt + 1), RETRY_INTERVAL);
  });
}

app.whenReady().then(() => {
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
