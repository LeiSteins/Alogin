use crate::{
    app::{Command, RESULT_MESSAGE, View, Worker},
    domain::{HomeAction, NetworkKind, Online, Preferences},
    platform::{
        network::{NETWORK_MESSAGE, NetworkEvents},
        wide,
    },
    strings,
};
use raw_window_handle::{HasWindowHandle, RawWindowHandle};
use slint::winit_030::{EventResult, WinitWindowAccessor};
use slint::{ComponentHandle, ModelRc, VecModel};
use std::{
    cell::RefCell,
    ptr,
    rc::Rc,
    sync::{
        atomic::{AtomicU32, Ordering},
        mpsc::TrySendError,
    },
};
use windows_sys::Win32::{
    Foundation::*,
    System::{LibraryLoader::GetModuleHandleW, Threading::CreateMutexW},
    UI::{Shell::*, WindowsAndMessaging::*},
};
use zeroize::Zeroizing;
#[cfg(test)]
mod tests;
mod theme;
slint::include_modules!();
const HOST_CLASS: &str = "Alogin.Windows.Host";
const TRAY_MESSAGE: u32 = WM_APP + 3;
const UI_MESSAGE: u32 = WM_APP + 4;
const OPEN_MESSAGE: u32 = WM_APP + 5;
const CLOSE_MESSAGE: u32 = WM_APP + 6;
const THEME_MESSAGE: u32 = WM_APP + 8;
const ERROR_MESSAGE: u32 = WM_APP + 9;
const DEBOUNCE_TIMER: usize = 1;
const NETWORK_REFRESH_DEBOUNCE_MS: u32 = 250;
const ID_LOGIN: usize = 101;
const ID_REFRESH: usize = 102;
const ID_ACCOUNTS: usize = 103;
const ID_SAVE: usize = 104;
const ID_DELETE: usize = 105;
const ID_SETTINGS: usize = 106;
const ID_HOME_ACTION: usize = 110;
const ID_SHOW: usize = 201;
const ID_EXIT: usize = 202;
static TASKBAR_CREATED: AtomicU32 = AtomicU32::new(0);

struct Mutex(HANDLE);
impl Drop for Mutex {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.0);
        }
    }
}

// Native callbacks enqueue work instead of borrowing UI state during reentrant
// Windows dispatch (window creation, painting, message boxes and popup menus).
thread_local! {
    static APP: RefCell<std::rc::Weak<RefCell<App>>> = const { RefCell::new(std::rc::Weak::new()) };
}
struct App {
    host: HWND,
    window: Option<MainWindow>,
    worker: Option<Worker>,
    events: Option<NetworkEvents>,
    view: View,
    busy: bool,
    dirty_network: bool,
    initialized: bool,
    user_action: bool,
    pending_error: Option<String>,
    tray: bool,
}
pub fn run() -> Result<(), String> {
    let mutex = unsafe {
        CreateMutexW(
            ptr::null(),
            0,
            wide("Local\\top.steins.alogin.windows").as_ptr(),
        )
    };
    if mutex.is_null() {
        return Err(strings::MUTEX_FAILED.into());
    }
    let already_running = unsafe { GetLastError() } == ERROR_ALREADY_EXISTS;
    let _mutex = Mutex(mutex);
    if already_running {
        let host = unsafe { FindWindowW(wide(HOST_CLASS).as_ptr(), ptr::null()) };
        if !host.is_null() {
            unsafe {
                PostMessageW(host, OPEN_MESSAGE, 0, 0);
            }
            return Ok(());
        }
        return Err(strings::SINGLE_INSTANCE.into());
    }
    let instance = unsafe { GetModuleHandleW(ptr::null()) };
    let icon = crate::platform::icon::load(false);
    if icon.is_null() {
        return Err(strings::ICON_FAILED.into());
    }
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name("software".into())
        .select()
        .map_err(|_| strings::WINDOW_FAILED)?;
    let name = wide(HOST_CLASS);
    let class = WNDCLASSW {
        lpfnWndProc: Some(host_proc),
        hInstance: instance,
        hIcon: icon,
        lpszClassName: name.as_ptr(),
        ..Default::default()
    };
    if unsafe { RegisterClassW(&class) } == 0 {
        return Err(strings::REGISTER_WINDOW_FAILED.into());
    }
    let app = Rc::new(RefCell::new(App {
        host: ptr::null_mut(),
        window: None,
        worker: None,
        events: None,
        view: View::default(),
        busy: true,
        dirty_network: false,
        initialized: false,
        user_action: false,
        pending_error: None,
        tray: false,
    }));
    let host = unsafe {
        CreateWindowExW(
            0,
            wide(HOST_CLASS).as_ptr(),
            wide(strings::APP).as_ptr(),
            WS_OVERLAPPED,
            0,
            0,
            0,
            0,
            ptr::null_mut(),
            ptr::null_mut(),
            instance,
            ptr::null(),
        )
    };
    if host.is_null() {
        return Err(strings::HOST_FAILED.into());
    }
    app.borrow_mut().host = host;
    TASKBAR_CREATED.store(
        unsafe { RegisterWindowMessageW(wide("TaskbarCreated").as_ptr()) },
        Ordering::Relaxed,
    );
    APP.with(|slot| *slot.borrow_mut() = Rc::downgrade(&app));
    let result = (|| {
        {
            let mut app = app.borrow_mut();
            app.add_tray()?;
            app.worker = Some(Worker::start(host as usize)?);
            app.events = Some(NetworkEvents::subscribe(host)?);
            if !std::env::args().any(|arg| arg == "--background") {
                // Winit creates native windows only while its event loop is
                // active. Use the same path as tray/second-instance opens so
                // the HWND exists before applying the icon and caption colors.
                post(host, OPEN_MESSAGE, 0);
            }
        }
        // Keep tray and network subscriptions alive without a visible window.
        slint::run_event_loop_until_quit().map_err(|_| strings::MESSAGE_LOOP_FAILED.into())
    })();
    APP.with(|slot| *slot.borrow_mut() = std::rc::Weak::new());
    let mut app = app.borrow_mut();
    app.events.take();
    if let Some(mut worker) = app.worker.take() {
        worker.shutdown();
    }
    app.close_window();
    app.remove_tray();
    unsafe {
        KillTimer(host, DEBOUNCE_TIMER);
        DestroyWindow(host);
        UnregisterClassW(wide(HOST_CLASS).as_ptr(), instance);
    }
    result
}

impl App {
    fn add_tray(&mut self) -> Result<(), String> {
        let mut data = self.tray_data();
        data.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
        data.uCallbackMessage = TRAY_MESSAGE;
        data.hIcon = crate::platform::icon::load(true);
        let tip = wide(strings::APP);
        data.szTip[..tip.len()].copy_from_slice(&tip);
        self.tray = unsafe { Shell_NotifyIconW(NIM_ADD, &data) } != 0;
        if self.tray {
            Ok(())
        } else {
            Err(strings::TRAY_FAILED.into())
        }
    }
    fn tray_data(&self) -> NOTIFYICONDATAW {
        NOTIFYICONDATAW {
            cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
            hWnd: self.host,
            uID: 1,
            ..Default::default()
        }
    }
    fn remove_tray(&mut self) {
        if self.tray {
            unsafe {
                Shell_NotifyIconW(NIM_DELETE, &self.tray_data());
            }
            self.tray = false;
        }
    }
    fn open_window(&mut self) -> Result<(), String> {
        if let Some(window) = &self.window {
            let native = native_window(window);
            unsafe {
                ShowWindow(native, SW_RESTORE);
                SetForegroundWindow(native);
            }
            return Ok(());
        }
        let window = MainWindow::new().map_err(|_| strings::WINDOW_FAILED)?;
        set_labels(&window);
        theme::refresh(&window);
        let host = self.host as usize;
        window.on_home_action(move || post(host as _, UI_MESSAGE, ID_HOME_ACTION));
        window.on_save_account(move || post(host as _, UI_MESSAGE, ID_SAVE));
        window.on_delete_account(move || post(host as _, UI_MESSAGE, ID_DELETE));
        window.on_select_account(move || post(host as _, UI_MESSAGE, ID_ACCOUNTS));
        window.on_save_settings(move || post(host as _, UI_MESSAGE, ID_SETTINGS));
        window.window().on_close_requested(move || {
            post(host as _, CLOSE_MESSAGE, 0);
            slint::CloseRequestResponse::KeepWindowShown
        });
        let weak = window.as_weak();
        let mut native_initialized = false;
        window.window().on_winit_window_event(move |_, _| {
            // show() can return before Winit creates the HWND, even when it is
            // called from the event loop. Initialize on the first native event
            // instead of silently sending icon/theme updates to a null handle.
            if !native_initialized && let Some(window) = weak.upgrade() {
                native_initialized = initialize_native_window(&window);
            }
            EventResult::Propagate
        });
        self.window = Some(window);
        self.render(true, true);
        let window = self.window.as_ref().unwrap();
        window.show().map_err(|_| strings::WINDOW_FAILED)?;
        if self.pending_error.is_some() {
            post(self.host, ERROR_MESSAGE, 0);
        }
        Ok(())
    }
    fn close_window(&mut self) {
        if let Some(window) = self.window.take() {
            window.set_password("".into());
            let _ = window.hide();
            // Dropping the component releases its native window and form drafts.
        }
    }
    fn submit(&mut self, command: Command) {
        if self.busy || self.view.fatal || !self.initialized {
            return;
        }
        let Some(worker) = &self.worker else {
            return;
        };
        let invalidates_info = matches!(
            command,
            Command::HomeAction | Command::Login | Command::Refresh | Command::NetworkChanged
        );
        let user_action = !matches!(command, Command::NetworkChanged);
        match worker.commands.try_send(command) {
            Ok(()) => {
                self.busy = true;
                self.user_action = user_action;
                self.view.message = strings::BUSY.into();
                if invalidates_info {
                    self.view.account_info = Default::default();
                    self.view.online = Online::Unknown;
                }
            }
            Err(TrySendError::Disconnected(_)) => {
                self.view.message = strings::THREAD_STOPPED.into();
                self.view.fatal = true;
                self.report_error(strings::THREAD_STOPPED.into());
            }
            Err(TrySendError::Full(_)) => {
                self.dirty_network = true;
            }
        }
        self.render(false, false);
    }
    fn result(&mut self) {
        let Some(worker) = &self.worker else {
            return;
        };
        let mut latest = None;
        while let Ok(view) = worker.results.try_recv() {
            latest = Some(view);
        }
        let Some(view) = latest else {
            return;
        };
        let sync_accounts = !self.initialized
            || self.view.accounts != view.accounts
            || self.view.selected != view.selected;
        let sync_preferences = !self.initialized
            || self.view.preferences.target_ssids != view.preferences.target_ssids
            || self.view.preferences.auto_login != view.preferences.auto_login;
        let error = (view.failed && (self.user_action || view.fatal)).then(|| view.message.clone());
        self.user_action = false;
        self.view = view;
        self.busy = false;
        self.initialized = true;
        self.render(sync_accounts, sync_preferences);
        if let Some(error) = error {
            self.report_error(error);
        }
        if self.dirty_network {
            self.dirty_network = false;
            unsafe {
                SetTimer(self.host, DEBOUNCE_TIMER, NETWORK_REFRESH_DEBOUNCE_MS, None);
            }
        }
    }
    fn report_error(&mut self, error: String) {
        self.pending_error = Some(error);
        post(self.host, ERROR_MESSAGE, 0);
    }
    fn render(&self, sync_accounts: bool, sync_preferences: bool) {
        let Some(window) = &self.window else { return };
        let network = &self.view.network;
        let ssid = network
            .ssid
            .as_deref()
            .unwrap_or(if network.ipv4.is_empty() {
                strings::DISCONNECTED
            } else if network.kind == NetworkKind::Ethernet {
                strings::ETHERNET
            } else {
                strings::SSID_UNAVAILABLE
            });
        window.set_network_name(ssid.into());
        window.set_network_ipv4(
            if network.ipv4.is_empty() {
                "—"
            } else {
                &network.ipv4
            }
            .into(),
        );
        let account = strings::account_card(&self.view.account_info, self.view.online);
        window.set_show_account(self.view.online == Online::Yes);
        window.set_account_name(account.username.into());
        window.set_account_balance(account.balance.into());
        window.set_account_error(account.error.into());
        window.set_has_overview(account.has_overview);
        window.set_show_traffic(account.progress.is_some());
        window.set_traffic_progress(account.progress.unwrap_or_default());
        window.set_traffic_used_percent(account.used_percent.into());
        window.set_traffic_used(account.used.into());
        window.set_traffic_remaining(account.remaining.into());
        window.set_action_label(
            if self.busy {
                strings::BUSY
            } else {
                match HomeAction::for_session(self.view.online, network, &self.view.preferences) {
                    HomeAction::Login => strings::LOGIN,
                    HomeAction::Refresh => strings::REFRESH,
                }
            }
            .into(),
        );
        window.set_ready(!self.busy && !self.view.fatal && self.initialized);
        let index = self
            .view
            .selected
            .as_ref()
            .and_then(|name| self.view.accounts.iter().position(|s| s == name));
        window.set_account_index(index.map_or(-1, |i| i as i32));
        if sync_accounts {
            window.set_accounts(ModelRc::new(VecModel::from(
                self.view
                    .accounts
                    .iter()
                    .map(|s| s.as_str().into())
                    .collect::<Vec<_>>(),
            )));
            window.set_username(self.view.selected.as_deref().unwrap_or("").into());
            window.set_password("".into());
        }
        if sync_preferences {
            window.set_targets(self.view.preferences.target_ssids.join("\n").into());
            window.set_auto_login(self.view.preferences.auto_login);
        }
    }
    fn command(&mut self, id: usize) {
        if id == ID_EXIT {
            let _ = slint::quit_event_loop();
            return;
        }
        if self.busy || self.view.fatal || !self.initialized {
            return;
        }
        match id {
            ID_HOME_ACTION => self.submit(Command::HomeAction),
            ID_LOGIN => self.submit(Command::Login),
            ID_REFRESH => self.submit(Command::Refresh),
            ID_SAVE => {
                if let Some(window) = &self.window {
                    let username = window.get_username().trim().to_owned();
                    let password = Zeroizing::new(window.get_password().to_string());
                    window.set_password("".into());
                    self.submit(Command::SaveAccount { username, password });
                }
            }
            ID_DELETE => {
                if let Some(name) = &self.view.selected {
                    self.submit(Command::DeleteAccount(name.clone()));
                }
            }
            ID_ACCOUNTS => {
                if let Some(window) = &self.window {
                    let index = window.get_account_index();
                    if index >= 0
                        && let Some(name) = self.view.accounts.get(index as usize)
                    {
                        self.submit(Command::SelectAccount(name.clone()));
                    }
                }
            }
            ID_SETTINGS => {
                if let Some(window) = &self.window {
                    match preferences(&window.get_targets(), window.get_auto_login()) {
                        Ok(preferences) => self.submit(Command::SavePreferences(preferences)),
                        Err(error) => self.report_error(error),
                    }
                }
            }
            _ => (),
        }
    }
}
fn preferences(targets: &str, auto_login: bool) -> Result<Preferences, String> {
    let mut target_ssids = Vec::new();
    for line in targets.lines().map(str::trim).filter(|s| !s.is_empty()) {
        if line.len() > 32 {
            return Err(strings::SSID_TOO_LONG.into());
        }
        if !target_ssids.iter().any(|existing| existing == line) {
            target_ssids.push(line.to_owned());
        }
    }
    Ok(Preferences {
        target_ssids,
        auto_login,
    })
}
fn set_labels(window: &MainWindow) {
    let labels = window.global::<Labels>();
    labels.set_app(strings::APP.into());
    labels.set_home(strings::HOME_PAGE.into());
    labels.set_accounts_page(strings::ACCOUNTS_PAGE.into());
    labels.set_network_page(strings::NETWORK_PAGE.into());
    labels.set_toggle_navigation(strings::TOGGLE_NAVIGATION.into());
    labels.set_network_title(strings::NETWORK_TITLE.into());
    labels.set_network_name(strings::NETWORK_NAME.into());
    labels.set_network_ipv4(strings::NETWORK_IPV4.into());
    labels.set_account_title(strings::ACCOUNT_TITLE.into());
    labels.set_account_username(strings::ACCOUNT_USERNAME.into());
    labels.set_remaining_money(strings::REMAINING_MONEY.into());
    labels.set_retry_fetch(strings::RETRY_FETCH.into());
    labels.set_traffic_progress(strings::TRAFFIC_PROGRESS.into());
    labels.set_accounts(strings::ACCOUNTS.into());
    labels.set_username(strings::USERNAME.into());
    labels.set_password(strings::PASSWORD.into());
    labels.set_save(strings::SAVE.into());
    labels.set_delete(strings::DELETE.into());
    labels.set_targets(strings::TARGETS.into());
    labels.set_auto(strings::AUTO.into());
    labels.set_save_settings(strings::SAVE_SETTINGS.into());
}
fn native_window(window: &MainWindow) -> HWND {
    window
        .window()
        .window_handle()
        .window_handle()
        .ok()
        .map(|h| h.as_raw())
        .and_then(|handle| match handle {
            RawWindowHandle::Win32(handle) => Some(handle.hwnd.get() as HWND),
            _ => None,
        })
        .unwrap_or(ptr::null_mut())
}
fn initialize_native_window(window: &MainWindow) -> bool {
    let native = native_window(window);
    if native.is_null() {
        return false;
    }
    crate::platform::icon::apply(native);
    theme::title_bar(native, window);
    unsafe {
        SetForegroundWindow(native);
    }
    true
}
fn post(host: HWND, message: u32, id: usize) {
    unsafe {
        PostMessageW(host, message, id, 0);
    }
}
fn enqueue(action: impl FnOnce(Rc<RefCell<App>>) + Send + 'static) {
    let _ = slint::invoke_from_event_loop(move || {
        APP.with(|slot| {
            if let Some(app) = slot.borrow().upgrade() {
                action(app);
            }
        });
    });
}
fn open_app_window(app: Rc<RefCell<App>>) {
    let result = app.borrow_mut().open_window();
    if let Err(error) = result {
        show_error(&error);
    }
}
unsafe extern "system" fn host_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    if (message == WM_DRAWITEM || message == WM_MEASUREITEM)
        && lparam != 0
        && let Some(result) = theme::menu_message(message, lparam)
    {
        return result;
    }
    match message {
        RESULT_MESSAGE => enqueue(|app| app.borrow_mut().result()),
        NETWORK_MESSAGE => unsafe {
            SetTimer(window, DEBOUNCE_TIMER, NETWORK_REFRESH_DEBOUNCE_MS, None);
        },
        WM_TIMER if wparam == DEBOUNCE_TIMER => {
            unsafe {
                KillTimer(window, DEBOUNCE_TIMER);
            }
            enqueue(|app| {
                let mut app = app.borrow_mut();
                if app.busy {
                    app.dirty_network = true;
                } else {
                    app.submit(Command::NetworkChanged);
                }
            });
        }
        OPEN_MESSAGE => enqueue(open_app_window),
        CLOSE_MESSAGE => enqueue(|app| app.borrow_mut().close_window()),
        UI_MESSAGE if wparam == ID_SHOW => enqueue(open_app_window),
        UI_MESSAGE => enqueue(move |app| app.borrow_mut().command(wparam)),
        WM_SETTINGCHANGE | WM_THEMECHANGED | WM_SYSCOLORCHANGE => post(window, THEME_MESSAGE, 0),
        THEME_MESSAGE => enqueue(|app| {
            if let Some(window) = &app.borrow().window {
                theme::refresh(window);
                theme::title_bar(native_window(window), window);
            }
        }),
        ERROR_MESSAGE => enqueue(|app| {
            let error = {
                let mut app = app.borrow_mut();
                app.window
                    .as_ref()
                    .map(native_window)
                    .and_then(|window| app.pending_error.take().map(|error| (window, error)))
            };
            // Modal native loops must never hold an application state borrow.
            if let Some((window, error)) = error {
                show_window_error(window, &error);
            }
        }),
        TRAY_MESSAGE => match lparam as u32 {
            WM_LBUTTONUP | WM_LBUTTONDBLCLK => post(window, OPEN_MESSAGE, 0),
            WM_RBUTTONUP | WM_CONTEXTMENU => enqueue(|app| {
                let (host, disabled) = {
                    let app = app.borrow();
                    (app.host, app.busy || app.view.fatal || !app.initialized)
                };
                tray_menu(host, disabled);
            }),
            _ => (),
        },
        WM_POWERBROADCAST if wparam == 0x12 => {
            unsafe {
                SetTimer(window, DEBOUNCE_TIMER, 1500, None);
            }
            return 1;
        }
        _ if message != 0 && message == TASKBAR_CREATED.load(Ordering::Relaxed) => {
            enqueue(|app| {
                let mut app = app.borrow_mut();
                app.tray = false;
                if let Err(error) = app.add_tray() {
                    app.report_error(error);
                }
            });
        }
        _ => return unsafe { DefWindowProcW(window, message, wparam, lparam) },
    }
    0
}
// TrackPopupMenu runs a nested message loop: never hold an App borrow across it.
fn tray_menu(host: HWND, disabled: bool) {
    let menu = unsafe { CreatePopupMenu() };
    if menu.is_null() {
        return;
    }
    // Keep the menu background brush alive until DestroyMenu below.
    let menu_theme = theme::MenuTheme::new(menu);
    let dark = menu_theme.dark();
    for (id, label) in [
        (ID_SHOW, strings::SHOW),
        (ID_LOGIN, strings::LOGIN),
        (ID_REFRESH, strings::REFRESH),
        (ID_EXIT, strings::EXIT),
    ] {
        let mut flags = if (id == ID_LOGIN || id == ID_REFRESH) && disabled {
            MF_STRING | MF_GRAYED
        } else {
            MF_STRING
        };
        if dark {
            flags |= MF_OWNERDRAW;
        }
        unsafe {
            let mut text = wide(label);
            AppendMenuW(
                menu,
                flags,
                id,
                if dark { ptr::null() } else { text.as_ptr() },
            );
            if dark {
                let info = MENUITEMINFOW {
                    cbSize: std::mem::size_of::<MENUITEMINFOW>() as u32,
                    fMask: MIIM_STRING,
                    dwTypeData: text.as_mut_ptr(),
                    ..Default::default()
                };
                SetMenuItemInfoW(menu, id as u32, 0, &info);
            }
        }
    }
    let mut point = POINT::default();
    unsafe {
        GetCursorPos(&mut point);
        SetForegroundWindow(host);
    }
    let id = unsafe {
        TrackPopupMenu(
            menu,
            TPM_RETURNCMD | TPM_NONOTIFY | TPM_RIGHTBUTTON,
            point.x,
            point.y,
            0,
            host,
            ptr::null(),
        )
    };
    unsafe {
        DestroyMenu(menu);
        PostMessageW(host, WM_NULL, 0, 0);
    }
    if id != 0 {
        unsafe {
            PostMessageW(host, UI_MESSAGE, id as usize, 0);
        }
    }
}

fn scale(value: i32, dpi: u32) -> i32 {
    ((value as i64 * dpi as i64 + 48) / 96) as i32
}
pub fn show_error(message: &str) {
    show_window_error(ptr::null_mut(), message);
}
fn show_window_error(owner: HWND, message: &str) {
    unsafe {
        MessageBoxW(
            owner,
            wide(message).as_ptr(),
            wide(strings::APP).as_ptr(),
            MB_OK | MB_ICONERROR,
        );
    }
}
