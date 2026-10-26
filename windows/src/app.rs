use crate::{
    domain::*,
    maintenance::{Maintenance, PauseReason},
    network::Campus,
    platform::network::snapshot,
    storage::{Settings, Store},
    strings,
};
use std::{
    sync::mpsc::{self, Receiver, RecvTimeoutError, SyncSender},
    time::Instant,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{PostMessageW, WM_APP};
use zeroize::Zeroizing;

pub const RESULT_MESSAGE: u32 = WM_APP + 1;
pub enum Command {
    HomeAction,
    Login,
    Refresh,
    NetworkChanged,
    ResumeMaintenance,
    LogoutDevice(DeviceLogoutRequest),
    SaveAccount {
        username: String,
        password: Zeroizing<String>,
    },
    SelectAccount(String),
    DeleteAccount(String),
    SavePreferences(Preferences),
    SetStartup(bool),
    Shutdown,
}

#[derive(Clone)]
pub struct View {
    pub startup: Result<bool, String>,
    pub accounts: Vec<String>,
    pub selected: Option<String>,
    pub preferences: Preferences,
    pub network: Network,
    pub online: Online,
    pub account_info: AccountInfo,
    pub device_generation: u64,
    pub message: String,
    pub failed: bool,
    pub fatal: bool,
    pub background: bool,
    pub maintenance_status: String,
    pub maintenance_detail: String,
}
impl Default for View {
    fn default() -> Self {
        Self {
            startup: Ok(false),
            accounts: Vec::new(),
            selected: None,
            preferences: Preferences::default(),
            network: Network::default(),
            online: Online::Unknown,
            account_info: AccountInfo::default(),
            device_generation: 0,
            message: strings::LOADING.into(),
            failed: false,
            fatal: false,
            background: false,
            maintenance_status: strings::MAINTENANCE_OFF.into(),
            maintenance_detail: String::new(),
        }
    }
}

pub struct Worker {
    pub commands: SyncSender<Command>,
    pub results: Receiver<View>,
    thread: Option<std::thread::JoinHandle<()>>,
}
impl Worker {
    #[cfg(test)]
    pub fn test_channel() -> (Self, Receiver<Command>, mpsc::Sender<View>) {
        let (commands, receiver) = mpsc::sync_channel(8);
        let (sender, results) = mpsc::channel();
        (
            Self {
                commands,
                results,
                thread: None,
            },
            receiver,
            sender,
        )
    }

    pub fn start(window: usize) -> Result<Self, String> {
        let (commands, receiver) = mpsc::sync_channel(8);
        let (sender, results) = mpsc::channel();
        let thread = std::thread::Builder::new()
            .name("alogin-io".into())
            .spawn(move || {
                let publish = |view| {
                    if sender.send(view).is_ok() {
                        unsafe {
                            PostMessageW(window as _, RESULT_MESSAGE, 0, 0);
                        }
                    }
                };
                let (store, settings) = match Store::open() {
                    Ok(value) => value,
                    Err(error) => {
                        publish(View {
                            fatal: true,
                            failed: true,
                            message: error,
                            ..View::default()
                        });
                        return;
                    }
                };
                let mut session = Session {
                    startup: crate::platform::startup::enabled(),
                    store,
                    settings,
                    network: Network::default(),
                    online: Online::Unknown,
                    account_info: AccountInfo::default(),
                    device_session: None,
                    device_generation: 0,
                    maintenance: Maintenance::default(),
                    maintenance_detail: String::new(),
                    services: LiveServices,
                    revision: crate::platform::network::revision(),
                    network_refresh_pending: true,
                };
                let initial = session.handle(Command::NetworkChanged);
                publish(session.view(initial));
                session.run(&receiver, publish);
            })
            .map_err(|_| strings::THREAD_FAILED)?;
        Ok(Self {
            commands,
            results,
            thread: Some(thread),
        })
    }

    pub fn shutdown(&mut self) {
        let _ = self.commands.send(Command::Shutdown);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

trait Services {
    fn set_startup(&self, enabled: bool) -> Result<(), String>;
    fn snapshot(&self) -> Network;
    fn revision(&self) -> u64;
    fn status(&self, network: &Network) -> Result<Online, String>;
    fn login(
        &self,
        username: &str,
        password: &str,
        network: &Network,
        revision: u64,
    ) -> Result<LoginResult, String>;
    fn account(&self, network: &Network) -> (AccountInfo, Option<crate::account::DeviceSession>);
}

struct LiveServices;
impl Services for LiveServices {
    fn set_startup(&self, enabled: bool) -> Result<(), String> {
        crate::platform::startup::set_enabled(enabled)
    }
    fn snapshot(&self) -> Network {
        snapshot()
    }
    fn revision(&self) -> u64 {
        crate::platform::network::revision()
    }
    fn status(&self, network: &Network) -> Result<Online, String> {
        Ok(Campus::new()?.status(network))
    }
    fn login(
        &self,
        username: &str,
        password: &str,
        network: &Network,
        revision: u64,
    ) -> Result<LoginResult, String> {
        Campus::new()?.login(username, password, network, revision)
    }
    fn account(&self, network: &Network) -> (AccountInfo, Option<crate::account::DeviceSession>) {
        crate::account::load(network)
    }
}

struct Session<S = LiveServices> {
    startup: Result<bool, String>,
    store: Store,
    settings: Settings,
    network: Network,
    online: Online,
    account_info: AccountInfo,
    device_session: Option<crate::account::DeviceSession>,
    device_generation: u64,
    maintenance: Maintenance,
    maintenance_detail: String,
    services: S,
    revision: u64,
    network_refresh_pending: bool,
}

impl<S: Services> Session<S> {
    fn run(&mut self, receiver: &Receiver<Command>, publish: impl Fn(View)) {
        loop {
            let received = if self.eligible() {
                let deadline = self.maintenance.deadline.get_or_insert_with(Instant::now);
                receiver.recv_timeout(deadline.saturating_duration_since(Instant::now()))
            } else {
                receiver.recv().map_err(|_| RecvTimeoutError::Disconnected)
            };
            let command = match received {
                Ok(command) => command,
                Err(RecvTimeoutError::Timeout) => {
                    let result = self.maintain();
                    let mut view = self.view(result);
                    view.background = true;
                    publish(view);
                    continue;
                }
                Err(RecvTimeoutError::Disconnected) => break,
            };
            if matches!(command, Command::Shutdown) {
                break;
            }
            let notification = matches!(command, Command::NetworkChanged);
            if notification {
                self.adopt_network();
                if !self.network_refresh_pending {
                    continue;
                }
            }
            let result = self.handle(command);
            let mut view = self.view(result);
            view.background = notification;
            publish(view);
        }
    }

    fn view(&self, result: Result<String, String>) -> View {
        let failed = result.is_err();
        View {
            startup: self.startup.clone(),
            accounts: self.settings.usernames(),
            selected: self.settings.selected.clone(),
            preferences: self.settings.preferences.clone(),
            network: self.network.clone(),
            online: self.online,
            account_info: self.account_info.clone(),
            device_generation: self.device_generation,
            message: result.unwrap_or_else(|error| error),
            failed,
            fatal: false,
            background: false,
            maintenance_status: self.maintenance_status().into(),
            maintenance_detail: self.maintenance_detail.clone(),
        }
    }

    fn handle(&mut self, command: Command) -> Result<String, String> {
        match command {
            Command::HomeAction => {
                let network = self.services.snapshot();
                let online = if network == self.network {
                    self.online
                } else {
                    Online::Unknown
                };
                match HomeAction::for_session(online, &network, &self.settings.preferences) {
                    HomeAction::Login => self.login(Some(network), false),
                    HomeAction::Refresh => self.refresh(),
                }
            }
            Command::Login => self.login(None, false),
            Command::LogoutDevice(request) => self.logout_device(request),
            Command::SaveAccount { username, password } => {
                self.update(|settings| settings.upsert(username, &password))?;
                self.maintenance.restart(Instant::now());
                self.maintenance_detail.clear();
                Ok(strings::SAVED.into())
            }
            Command::SelectAccount(username) => {
                self.update(|settings| settings.select(username))?;
                self.maintenance.restart(Instant::now());
                self.maintenance_detail.clear();
                Ok(strings::SELECTED.into())
            }
            Command::DeleteAccount(username) => {
                self.update(|settings| {
                    settings.delete(&username);
                    Ok(())
                })?;
                self.maintenance.restart(Instant::now());
                self.maintenance_detail.clear();
                Ok(strings::DELETED.into())
            }
            Command::SavePreferences(preferences) => {
                if !preferences.maintenance.valid() {
                    return Err(strings::INVALID_MAINTENANCE.into());
                }
                let rearm = preferences.auto_login != self.settings.preferences.auto_login;
                let reschedule = preferences.maintenance != self.settings.preferences.maintenance
                    || preferences.target_ssids != self.settings.preferences.target_ssids;
                self.update(|settings| {
                    settings.preferences = preferences;
                    Ok(())
                })?;
                if rearm {
                    self.maintenance.restart(Instant::now());
                    self.maintenance_detail.clear();
                } else if reschedule {
                    self.maintenance.wake(Instant::now());
                }
                Ok(strings::SAVED.into())
            }
            Command::SetStartup(enabled) => {
                self.services.set_startup(enabled)?;
                self.startup = Ok(enabled);
                Ok(strings::SAVED.into())
            }
            Command::ResumeMaintenance => {
                self.maintenance.restart(Instant::now());
                self.maintenance_detail.clear();
                Ok(strings::MAINTENANCE_RESUMED.into())
            }
            Command::NetworkChanged => self.network_changed(),
            Command::Refresh => self.refresh(),
            Command::Shutdown => Ok(String::new()),
        }
    }

    fn update(
        &mut self,
        change: impl FnOnce(&mut Settings) -> Result<(), String>,
    ) -> Result<(), String> {
        let mut candidate = self.settings.clone();
        change(&mut candidate)?;
        self.store.save(&candidate)?;
        self.settings = candidate;
        Ok(())
    }

    fn network_changed(&mut self) -> Result<String, String> {
        self.adopt_network();
        self.remember_detected_wifi()?;
        self.network_refresh_pending = false;
        if self.eligible() {
            self.maintenance.wake(Instant::now());
            return Ok(strings::READY.into());
        }
        self.refresh()
    }

    fn eligible(&self) -> bool {
        self.settings.preferences.allows_auto_login(&self.network)
            && self.settings.selected.is_some()
    }

    fn maintenance_status(&self) -> &'static str {
        if !self.settings.preferences.auto_login {
            return strings::MAINTENANCE_OFF;
        }
        if !self.settings.preferences.allows_auto_login(&self.network) {
            return strings::MAINTENANCE_WAIT_NETWORK;
        }
        if self.settings.selected.is_none() {
            return strings::NO_ACCOUNT;
        }
        match self.maintenance.paused {
            Some(PauseReason::Credentials) => strings::DECRYPT_FAILED,
            Some(PauseReason::InvalidPassword) => strings::MAINTENANCE_PASSWORD,
            Some(PauseReason::UserLogout) => strings::MAINTENANCE_LOGOUT,
            None if self.online == Online::Yes => strings::MAINTENANCE_ONLINE,
            None => strings::MAINTENANCE_WAITING,
        }
    }

    fn adopt_network(&mut self) -> bool {
        let revision = self.services.revision();
        let current = self.services.snapshot();
        let changed = current != self.network;
        if changed {
            self.maintenance.network_changed(Instant::now());
            self.maintenance_detail.clear();
            self.invalidate_session();
        }
        self.network = current;
        self.revision = revision;
        changed
    }

    fn invalidate_session(&mut self) {
        self.online = Online::Unknown;
        self.account_info = AccountInfo::default();
        self.device_session = None;
        self.device_generation = self.device_generation.wrapping_add(1);
        self.network_refresh_pending = true;
    }

    fn maintain(&mut self) -> Result<String, String> {
        let changed = self.adopt_network();
        // Always leave a future deadline, including error and network-change exits.
        self.maintenance.wake(Instant::now());
        if changed || !self.eligible() {
            self.remember_detected_wifi()?;
            return Ok(strings::READY.into());
        }
        self.network_refresh_pending = false;
        let observed = self
            .services
            .status(&self.network)
            .unwrap_or(Online::Unknown);
        self.ensure_current()?;
        let now = Instant::now();
        self.maintenance
            .observed(observed, now, &self.settings.preferences.maintenance);
        self.maintenance_detail = match observed {
            Online::Yes => strings::MAINTENANCE_CHECK_ONLINE.into(),
            Online::No => strings::MAINTENANCE_CHECK_OFFLINE.into(),
            Online::Unknown => strings::MAINTENANCE_CHECK_UNKNOWN.into(),
        };
        // Retain the last confirmed display during an inconclusive probe.
        if observed == Online::Unknown {
            return Ok(strings::UNKNOWN.into());
        }
        let was_online = self.online == Online::Yes;
        self.online = observed;
        if observed == Online::Yes {
            if !was_online || self.account_info.username.is_none() {
                self.load_account()?;
            }
            self.maintenance.observed(
                Online::Yes,
                Instant::now(),
                &self.settings.preferences.maintenance,
            );
            return Ok(strings::LOGGED_IN.into());
        }
        self.account_info = AccountInfo::default();
        self.device_session = None;
        if self.maintenance.can_login(now) {
            let result = self.login(Some(self.network.clone()), true);
            if let Err(error) = &result {
                self.maintenance_detail = error.clone();
            }
            result
        } else {
            Ok(strings::LOGGED_OUT.into())
        }
    }

    fn login(&mut self, expected: Option<Network>, automatic: bool) -> Result<String, String> {
        if !automatic {
            self.maintenance.restart(Instant::now());
        }
        let expected_revision = self.revision;
        let revision = self.services.revision();
        let network = self.services.snapshot();
        self.online = Online::Unknown;
        self.account_info = AccountInfo::default();
        self.device_session = None;
        if expected.as_ref().is_some_and(|previous| {
            previous != &network
                || automatic && revision != expected_revision
                || automatic && !self.settings.preferences.allows_auto_login(&network)
        }) || self.services.revision() != revision
        {
            if !self.adopt_network() {
                self.invalidate_session();
            }
            return Err(strings::NETWORK_CHANGED.into());
        }
        if !usable_ipv4(&network.ipv4) {
            return Err(strings::NO_NETWORK.into());
        }
        self.network = network;
        self.revision = revision;
        let username = self
            .settings
            .selected
            .as_deref()
            .ok_or(strings::NO_ACCOUNT)?;
        let password = match self.settings.password(username) {
            Ok(password) => password,
            Err(error) => {
                self.maintenance.pause(PauseReason::Credentials);
                return Err(error);
            }
        };
        let result = match self
            .services
            .login(username, &password, &self.network, self.revision)
        {
            Ok(result) => result,
            Err(error) => {
                self.maintenance
                    .login_failed(Instant::now(), &self.settings.preferences.maintenance);
                self.ensure_current()?;
                return Err(error);
            }
        };
        self.ensure_current()?;
        if result.invalid_credentials() {
            self.maintenance.pause(PauseReason::InvalidPassword);
            self.maintenance.observed(
                Online::No,
                Instant::now(),
                &self.settings.preferences.maintenance,
            );
            return Err(strings::INVALID_PASSWORD.into());
        }
        match result {
            LoginResult::Success => {
                self.online = Online::Yes;
                self.load_account()?;
                self.maintenance
                    .submitted(Instant::now(), &self.settings.preferences.maintenance);
                Ok(strings::AUTHENTICATED.into())
            }
            LoginResult::AlreadyOnline => {
                self.online = Online::Yes;
                self.load_account()?;
                self.maintenance
                    .submitted(Instant::now(), &self.settings.preferences.maintenance);
                Ok(strings::ALREADY_ONLINE.into())
            }
            LoginResult::Failure(_) => {
                self.online = Online::Unknown;
                self.maintenance
                    .login_failed(Instant::now(), &self.settings.preferences.maintenance);
                Err(strings::LOGIN_FAILED.into())
            }
            LoginResult::Unknown => {
                self.maintenance
                    .login_failed(Instant::now(), &self.settings.preferences.maintenance);
                Err(strings::UNKNOWN_RESPONSE.into())
            }
        }
    }

    fn refresh(&mut self) -> Result<String, String> {
        self.adopt_network();
        self.network_refresh_pending = false;
        self.online = Online::Unknown;
        self.account_info = AccountInfo::default();
        self.device_session = None;
        self.remember_detected_wifi()?;
        if usable_ipv4(&self.network.ipv4) {
            self.online = self.services.status(&self.network)?;
        }
        self.ensure_current()?;
        self.load_account()?;
        if self.eligible() {
            self.maintenance.observed(
                self.online,
                Instant::now(),
                &self.settings.preferences.maintenance,
            );
            if self.online == Online::No && self.maintenance.can_login(Instant::now()) {
                self.maintenance.deadline = Some(Instant::now());
            }
        }
        Ok(if self.network.ipv4.is_empty() {
            strings::NO_NETWORK
        } else {
            match self.online {
                Online::Yes => strings::LOGGED_IN,
                Online::No => strings::LOGGED_OUT,
                Online::Unknown => strings::UNKNOWN,
            }
        }
        .into())
    }

    fn remember_detected_wifi(&mut self) -> Result<(), String> {
        let mut preferences = self.settings.preferences.clone();
        if preferences.add_detected_wifi(&self.network) {
            self.update(|settings| {
                settings.preferences = preferences;
                Ok(())
            })?;
        }
        Ok(())
    }

    fn load_account(&mut self) -> Result<(), String> {
        self.ensure_current()?;
        self.device_generation = self.device_generation.wrapping_add(1);
        let (info, session) = if self.online == Online::Yes {
            self.services.account(&self.network)
        } else {
            (AccountInfo::default(), None)
        };
        self.account_info = info;
        self.device_session = session;
        self.ensure_current()
    }

    fn logout_device(&mut self, request: DeviceLogoutRequest) -> Result<String, String> {
        self.ensure_current()?;
        if !request.matches(
            self.device_generation,
            &self.network,
            self.online,
            &self.account_info,
        ) {
            return Err(strings::DEVICE_STALE.into());
        }
        if self.device_session.is_none() {
            return Err(strings::DEVICE_STALE.into());
        }
        let previous_pause = self.maintenance.paused;
        if request.device.ip == self.network.ipv4
            && self.settings.preferences.maintenance.pause_after_logout
        {
            // A transport error may occur after the server accepted the logout.
            self.maintenance.pause(PauseReason::UserLogout);
        }
        let result = self
            .device_session
            .as_ref()
            .ok_or(strings::DEVICE_STALE)?
            .logout(&request.device);
        self.ensure_current()?;
        match result {
            Ok(crate::account::LogoutResult::Failure) => {
                self.maintenance.paused = previous_pause;
                Err(strings::DEVICE_LOGOUT_FAILED.into())
            }
            Ok(outcome) => {
                self.refresh()?;
                if outcome == crate::account::LogoutResult::Success {
                    Ok(strings::DEVICE_LOGOUT_SUCCESS.into())
                } else {
                    Err(strings::DEVICE_LOGOUT_UNKNOWN.into())
                }
            }
            Err(error) => {
                self.device_session = None;
                self.account_info.devices = None;
                self.account_info.devices_error = Some(error.clone());
                Err(error)
            }
        }
    }

    fn ensure_current(&mut self) -> Result<(), String> {
        let current = self.services.snapshot();
        if current != self.network || self.services.revision() != self.revision {
            if !self.adopt_network() {
                self.invalidate_session();
            }
            Err(strings::NETWORK_CHANGED.into())
        } else {
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::{
        cell::{Cell, RefCell},
        path::PathBuf,
        time::Duration,
    };

    struct TestDirectory(PathBuf);
    impl Drop for TestDirectory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    struct MockServices {
        network: RefCell<Network>,
        revision: Cell<u64>,
        online: Cell<Online>,
        status_count: Cell<u32>,
        login_count: Cell<u32>,
        account_count: Cell<u32>,
        invalid_password: Cell<bool>,
        transient_failure: Cell<bool>,
        change_on_status: Cell<bool>,
        change_on_login: Cell<bool>,
    }
    impl Services for MockServices {
        fn set_startup(&self, _: bool) -> Result<(), String> {
            if self.transient_failure.get() {
                Err(strings::STARTUP_WRITE_FAILED.into())
            } else {
                Ok(())
            }
        }
        fn snapshot(&self) -> Network {
            self.network.borrow().clone()
        }
        fn revision(&self) -> u64 {
            self.revision.get()
        }
        fn status(&self, _: &Network) -> Result<Online, String> {
            self.status_count.set(self.status_count.get() + 1);
            if self.change_on_status.replace(false) {
                self.revision.set(self.revision.get() + 1);
            }
            Ok(self.online.get())
        }
        fn login(&self, _: &str, _: &str, _: &Network, _: u64) -> Result<LoginResult, String> {
            self.login_count.set(self.login_count.get() + 1);
            if self.change_on_login.replace(false) {
                self.revision.set(self.revision.get() + 1);
            }
            if self.invalid_password.get() {
                return Ok(LoginResult::Failure("ldap auth error".into()));
            }
            if self.transient_failure.get() {
                return Err(strings::REQUEST_TIMEOUT.into());
            }
            Ok(LoginResult::Success)
        }
        fn account(&self, _: &Network) -> (AccountInfo, Option<crate::account::DeviceSession>) {
            self.account_count.set(self.account_count.get() + 1);
            (
                AccountInfo {
                    username: Some("test-student".into()),
                    error: Some(strings::ACCOUNT_FAILED.into()),
                    ..Default::default()
                },
                None,
            )
        }
    }

    #[test]
    fn startup_changes_only_after_system_write_succeeds_without_changing_login_settings() {
        let (mut session, _directory) = maintenance_fixture();
        let preferences = serde_json::to_value(&session.settings.preferences).unwrap();
        let deadline = session.maintenance.deadline;
        session.services.transient_failure.set(true);
        assert!(session.handle(Command::SetStartup(true)).is_err());
        assert_eq!(session.startup, Ok(false));
        session.services.transient_failure.set(false);
        session.handle(Command::SetStartup(true)).unwrap();
        assert_eq!(session.view(Ok(String::new())).startup, Ok(true));
        session.services.transient_failure.set(true);
        assert!(session.handle(Command::SetStartup(false)).is_err());
        assert_eq!(session.startup, Ok(true));
        session.services.transient_failure.set(false);
        session.handle(Command::SetStartup(false)).unwrap();
        assert_eq!(session.startup, Ok(false));
        assert_eq!(
            serde_json::to_value(&session.settings.preferences).unwrap(),
            preferences
        );
        assert_eq!(session.maintenance.deadline, deadline);
        assert_eq!(session.services.login_count.get(), 0);
        session.startup = Err(strings::STARTUP_READ_FAILED.into());
        assert!(session.view(Ok(String::new())).startup.is_err());
        session.handle(Command::SetStartup(true)).unwrap();
        assert_eq!(session.startup, Ok(true));
    }

    fn maintenance_fixture() -> (Session<MockServices>, TestDirectory) {
        let directory = TestDirectory(std::env::temp_dir().join(format!(
                "alogin-maintenance-{}-{}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            )));
        let (store, mut settings) = Store::at(directory.0.join("settings.json")).unwrap();
        settings
            .upsert("test-student".into(), "synthetic-test-password")
            .unwrap();
        settings.preferences.auto_login = true;
        let network = Network {
            interface: 1,
            kind: NetworkKind::Wifi,
            ssid: Some("bjut_wifi".into()),
            ipv4: "10.1.2.3".into(),
        };
        (
            Session {
                startup: Ok(false),
                store,
                settings,
                network: network.clone(),
                online: Online::Unknown,
                account_info: AccountInfo::default(),
                device_session: None,
                device_generation: 0,
                maintenance: Maintenance::default(),
                maintenance_detail: String::new(),
                revision: 0,
                network_refresh_pending: false,
                services: MockServices {
                    network: RefCell::new(network),
                    revision: Cell::new(0),
                    online: Cell::new(Online::No),
                    status_count: Cell::new(0),
                    login_count: Cell::new(0),
                    account_count: Cell::new(0),
                    invalid_password: Cell::new(false),
                    transient_failure: Cell::new(false),
                    change_on_status: Cell::new(false),
                    change_on_login: Cell::new(false),
                },
            },
            directory,
        )
    }

    #[test]
    fn unchanged_notifications_preserve_display_and_schedule_without_io_or_publication() {
        for auto_login in [false, true] {
            let (mut session, _directory) = maintenance_fixture();
            session.settings.preferences.auto_login = auto_login;
            session.services.online.set(Online::Yes);
            session.refresh().unwrap();
            session.maintenance.pause(PauseReason::UserLogout);
            session.maintenance_detail = "last confirmed probe".into();
            let account = session.account_info.clone();
            let generation = session.device_generation;
            let deadline = Some(Instant::now() + Duration::from_secs(120));
            session.maintenance.deadline = deadline;
            session.services.revision.set(7);
            let (commands, receiver) = mpsc::sync_channel(8);
            commands.send(Command::NetworkChanged).unwrap();
            commands.send(Command::NetworkChanged).unwrap();
            commands.send(Command::Shutdown).unwrap();
            session.run(&receiver, |_| {
                panic!("unchanged notification published a view")
            });
            assert_eq!(session.online, Online::Yes);
            assert_eq!(session.account_info, account);
            assert_eq!(session.device_generation, generation);
            assert_eq!(session.revision, 7);
            assert_eq!(session.maintenance.deadline, deadline);
            assert_eq!(session.maintenance.paused, Some(PauseReason::UserLogout));
            assert_eq!(session.maintenance_detail, "last confirmed probe");
            assert_eq!(session.services.status_count.get(), 1);
            assert_eq!(session.services.account_count.get(), 1);
            assert_eq!(session.services.login_count.get(), 0);
        }
    }

    #[test]
    fn startup_and_manual_refresh_still_load_an_unchanged_network_with_auto_login_off() {
        let (mut session, _directory) = maintenance_fixture();
        session.settings.preferences.auto_login = false;
        session.services.online.set(Online::Yes);
        session.network_refresh_pending = true;
        let (commands, receiver) = mpsc::sync_channel(8);
        commands.send(Command::NetworkChanged).unwrap();
        commands.send(Command::NetworkChanged).unwrap();
        commands.send(Command::Refresh).unwrap();
        commands.send(Command::Shutdown).unwrap();
        let published = RefCell::new(Vec::new());
        session.run(&receiver, |view| published.borrow_mut().push(view));
        assert_eq!(published.borrow().len(), 2);
        assert!(published.borrow()[0].background);
        assert!(!published.borrow()[1].background);
        assert_eq!(session.services.status_count.get(), 2);
        assert_eq!(session.services.account_count.get(), 2);
        assert_eq!(session.services.login_count.get(), 0);
    }

    #[test]
    fn changes_to_any_network_field_refresh_once_and_disconnect_clears_the_session() {
        for field in ["interface", "kind", "ssid", "ipv4", "disconnect"] {
            let (mut session, _directory) = maintenance_fixture();
            session.settings.preferences.auto_login = false;
            session.services.online.set(Online::Yes);
            session.refresh().unwrap();
            session.maintenance.pause(PauseReason::UserLogout);
            let generation = session.device_generation;
            {
                let mut network = session.services.network.borrow_mut();
                match field {
                    "interface" => network.interface += 1,
                    "kind" => network.kind = NetworkKind::Ethernet,
                    "ssid" => network.ssid = Some("another-wifi".into()),
                    "ipv4" => network.ipv4 = "10.1.2.4".into(),
                    "disconnect" => *network = Network::default(),
                    _ => unreachable!(),
                }
            }
            session.services.revision.set(1);
            let (commands, receiver) = mpsc::sync_channel(8);
            commands.send(Command::NetworkChanged).unwrap();
            commands.send(Command::NetworkChanged).unwrap();
            commands.send(Command::Shutdown).unwrap();
            let published = RefCell::new(Vec::new());
            session.run(&receiver, |view| published.borrow_mut().push(view));
            assert_eq!(published.borrow().len(), 1, "{field}");
            assert!(published.borrow()[0].background);
            assert_eq!(session.network, *session.services.network.borrow());
            assert!(session.device_generation > generation);
            assert_eq!(session.maintenance.paused, None);
            let disconnected = field == "disconnect";
            assert_eq!(
                session.services.status_count.get(),
                if disconnected { 1 } else { 2 }
            );
            assert_eq!(
                session.services.account_count.get(),
                if disconnected { 1 } else { 2 }
            );
            if disconnected {
                assert_eq!(session.online, Online::Unknown);
                assert_eq!(session.account_info, AccountInfo::default());
                assert!(session.device_session.is_none());
            }
            assert_eq!(session.services.login_count.get(), 0);
        }
    }

    #[test]
    fn notification_during_a_request_discards_the_response_and_refreshes_afterwards() {
        let (mut session, _directory) = maintenance_fixture();
        session.settings.preferences.auto_login = false;
        session.services.online.set(Online::Yes);
        session.services.change_on_status.set(true);
        assert_eq!(session.refresh().unwrap_err(), strings::NETWORK_CHANGED);
        assert_eq!(session.online, Online::Unknown);
        assert_eq!(session.services.account_count.get(), 0);
        let (commands, receiver) = mpsc::sync_channel(8);
        commands.send(Command::NetworkChanged).unwrap();
        commands.send(Command::Shutdown).unwrap();
        let published = RefCell::new(Vec::new());
        session.run(&receiver, |view| published.borrow_mut().push(view));
        assert_eq!(published.borrow().len(), 1);
        assert!(published.borrow()[0].background);
        assert_eq!(session.online, Online::Yes);
        assert_eq!(session.services.status_count.get(), 2);
        assert_eq!(session.services.account_count.get(), 1);
        assert_eq!(session.services.login_count.get(), 0);
    }

    #[test]
    fn worker_wakes_for_background_checks_without_a_window_and_accepts_commands_and_shutdown() {
        let (mut session, _directory) = maintenance_fixture();
        session.services.online.set(Online::Yes);
        session.maintenance.deadline = Some(Instant::now());
        let (commands, receiver) = mpsc::sync_channel(8);
        let published = RefCell::new(Vec::new());
        session.run(&receiver, |view| {
            let background = view.background;
            published.borrow_mut().push(view);
            commands
                .send(if background {
                    Command::Refresh
                } else {
                    Command::Shutdown
                })
                .unwrap();
        });
        assert_eq!(published.borrow().len(), 2);
        assert!(published.borrow()[0].background);
        assert!(!published.borrow()[1].background);
        assert_eq!(session.services.status_count.get(), 2);
        assert_eq!(session.services.login_count.get(), 0);
    }

    #[test]
    fn background_recovers_silent_disconnect_without_reloading_accounts_on_each_online_probe() {
        let (mut session, _directory) = maintenance_fixture();
        session.network_changed().unwrap();
        assert_eq!(session.services.login_count.get(), 0);
        assert!(session.maintenance.deadline.unwrap() > Instant::now());
        session.maintain().unwrap();
        assert_eq!(session.services.login_count.get(), 1);
        assert_eq!(session.online, Online::Yes);
        assert_eq!(
            session.account_info.username.as_deref(),
            Some("test-student")
        );
        session.services.online.set(Online::Yes);
        session.maintain().unwrap();
        session.maintain().unwrap();
        assert_eq!(session.services.account_count.get(), 1);
        assert_eq!(session.services.login_count.get(), 1);
        session.services.online.set(Online::No);
        session.maintain().unwrap();
        assert_eq!(session.services.login_count.get(), 2);
        assert_eq!(session.services.account_count.get(), 2);
    }

    #[test]
    fn unknown_probe_keeps_confirmed_identity_and_never_submits_credentials() {
        let (mut session, _directory) = maintenance_fixture();
        session.services.online.set(Online::Yes);
        session.maintain().unwrap();
        let previous = session.account_info.clone();
        session.services.online.set(Online::Unknown);
        session.maintain().unwrap();
        assert_eq!(session.online, Online::Yes);
        assert_eq!(session.account_info, previous);
        assert_eq!(session.services.login_count.get(), 0);
        assert_eq!(session.services.account_count.get(), 1);
        assert_eq!(
            session.maintenance_detail,
            strings::MAINTENANCE_CHECK_UNKNOWN
        );
    }

    #[test]
    fn transient_failure_obeys_cooldown_even_after_network_notifications() {
        let (mut session, _directory) = maintenance_fixture();
        session.services.transient_failure.set(true);
        assert!(session.maintain().is_err());
        let retry = session.maintenance.retry_at;
        session.network_changed().unwrap();
        session.maintain().unwrap();
        assert_eq!(session.services.login_count.get(), 1);
        assert_eq!(session.maintenance.retry_at, retry);
        session.maintenance.retry_at = Some(Instant::now() - Duration::from_secs(1));
        assert!(session.maintain().is_err());
        assert_eq!(session.services.login_count.get(), 2);
    }

    #[test]
    fn unreadable_credentials_pause_automatic_submissions_without_network_login() {
        let (mut session, _directory) = maintenance_fixture();
        let mut value = serde_json::to_value(&session.settings).unwrap();
        value["accounts"][0]["protected_password"] = serde_json::json!([1, 2, 3]);
        session.settings = serde_json::from_value(value).unwrap();
        assert_eq!(session.maintain().unwrap_err(), strings::DECRYPT_FAILED);
        session.maintain().unwrap();
        assert_eq!(session.services.login_count.get(), 0);
        assert_eq!(session.maintenance.paused, Some(PauseReason::Credentials));
    }

    #[test]
    fn invalid_password_pauses_across_networks_until_explicit_resume_or_credential_update() {
        let (mut session, _directory) = maintenance_fixture();
        session.services.invalid_password.set(true);
        assert_eq!(session.maintain().unwrap_err(), strings::INVALID_PASSWORD);
        session.maintain().unwrap();
        session.services.network.borrow_mut().interface = 2;
        session.network_changed().unwrap();
        session.maintain().unwrap();
        assert_eq!(session.services.login_count.get(), 1);
        assert_eq!(
            session.maintenance.paused,
            Some(PauseReason::InvalidPassword)
        );
        session.handle(Command::ResumeMaintenance).unwrap();
        assert!(session.maintain().is_err());
        assert_eq!(session.services.login_count.get(), 2);
        session.services.invalid_password.set(false);
        session
            .handle(Command::SaveAccount {
                username: "test-student".into(),
                password: Zeroizing::new("changed-test-password".into()),
            })
            .unwrap();
        session.maintain().unwrap();
        assert_eq!(session.services.login_count.get(), 3);
    }

    #[test]
    fn network_revision_changes_discard_offline_probe_and_successful_login_even_with_same_snapshot()
    {
        let (mut session, _directory) = maintenance_fixture();
        session.services.change_on_status.set(true);
        assert_eq!(session.maintain().unwrap_err(), strings::NETWORK_CHANGED);
        assert_eq!(session.services.login_count.get(), 0);
        session.services.change_on_login.set(true);
        assert_eq!(session.maintain().unwrap_err(), strings::NETWORK_CHANGED);
        assert_eq!(session.online, Online::Unknown);
        assert!(session.account_info.username.is_none());
        assert_eq!(session.services.account_count.get(), 0);
    }

    #[test]
    fn disabling_auto_login_and_leaving_targets_prevent_background_authentication() {
        let (mut session, _directory) = maintenance_fixture();
        let mut preferences = session.settings.preferences.clone();
        preferences.auto_login = false;
        session
            .handle(Command::SavePreferences(preferences))
            .unwrap();
        session.maintain().unwrap();
        assert!(!session.eligible());
        assert_eq!(session.services.status_count.get(), 0);
        session.settings.preferences.auto_login = true;
        for (kind, ssid) in [
            (NetworkKind::Ethernet, None),
            (NetworkKind::Wifi, None),
            (NetworkKind::Wifi, Some("other-wifi".into())),
        ] {
            {
                let mut network = session.services.network.borrow_mut();
                network.kind = kind;
                network.ssid = ssid;
            }
            session.adopt_network();
            session.maintain().unwrap();
            assert!(!session.eligible());
        }
        assert_eq!(session.services.login_count.get(), 0);
    }

    #[test]
    fn failed_write_keeps_previous_in_memory_and_on_disk_settings() {
        let root = std::env::temp_dir().join(format!(
            "alogin-transaction-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let path = root.join("settings.json");
        let (store, settings) = Store::at(path.clone()).unwrap();
        let mut session = Session {
            startup: Ok(false),
            store,
            settings,
            network: Network::default(),
            online: Online::Unknown,
            account_info: AccountInfo::default(),
            maintenance: Maintenance::default(),
            maintenance_detail: String::new(),
            services: LiveServices,
            revision: crate::platform::network::revision(),
            network_refresh_pending: true,
            device_session: None,
            device_generation: 0,
        };
        session.network = Network {
            kind: NetworkKind::Wifi,
            ssid: Some("bjut-sushe-101".into()),
            ..Network::default()
        };
        session.remember_detected_wifi().unwrap();
        assert_eq!(
            Store::at(path.clone()).unwrap().1.preferences.target_ssids,
            ["bjut_wifi", "bjut-sushe-101"]
        );
        session
            .update(|settings| {
                settings.preferences.auto_login = true;
                Ok(())
            })
            .unwrap();
        // A directory at the staging path forces a genuine filesystem write failure.
        fs::create_dir(path.with_extension("json.tmp")).unwrap();
        session.network.ssid = Some("bjut-sushe-102".into());
        assert!(session.remember_detected_wifi().is_err());
        assert_eq!(
            session.settings.preferences.target_ssids,
            ["bjut_wifi", "bjut-sushe-101"]
        );
        let result = session.handle(Command::SavePreferences(Preferences {
            auto_login: false,
            ..Preferences::default()
        }));
        assert!(result.is_err());
        let view = session.view(result);
        assert!(view.failed);
        assert!(view.preferences.auto_login);
        assert!(session.settings.preferences.auto_login);
        assert!(Store::at(path.clone()).unwrap().1.preferences.auto_login);
        assert_eq!(
            Store::at(path.clone()).unwrap().1.preferences.target_ssids,
            ["bjut_wifi", "bjut-sushe-101"]
        );
        fs::remove_dir(path.with_extension("json.tmp")).unwrap();
        fs::remove_file(path).unwrap();
        fs::remove_dir(root).unwrap();
    }
}
