use crate::{
    domain::*,
    network::Campus,
    platform::network::snapshot,
    storage::{Settings, Store},
    strings,
};
use std::sync::mpsc::{self, Receiver, SyncSender};
use windows_sys::Win32::UI::WindowsAndMessaging::{PostMessageW, WM_APP};
use zeroize::Zeroizing;

pub const RESULT_MESSAGE: u32 = WM_APP + 1;
pub enum Command {
    HomeAction,
    Login,
    Refresh,
    NetworkChanged,
    LogoutDevice(DeviceLogoutRequest),
    SaveAccount {
        username: String,
        password: Zeroizing<String>,
    },
    SelectAccount(String),
    DeleteAccount(String),
    SavePreferences(Preferences),
    Shutdown,
}

#[derive(Clone)]
pub struct View {
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
}
impl Default for View {
    fn default() -> Self {
        Self {
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
                    store,
                    settings,
                    network: Network::default(),
                    online: Online::Unknown,
                    account_info: AccountInfo::default(),
                    device_session: None,
                    device_generation: 0,
                    last_auto: None,
                };
                let initial = session.handle(Command::NetworkChanged);
                publish(session.view(initial));
                while let Ok(command) = receiver.recv() {
                    if matches!(command, Command::Shutdown) {
                        break;
                    }
                    let result = session.handle(command);
                    publish(session.view(result));
                }
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

struct Session {
    store: Store,
    settings: Settings,
    network: Network,
    online: Online,
    account_info: AccountInfo,
    device_session: Option<crate::account::DeviceSession>,
    device_generation: u64,
    last_auto: Option<(Network, String)>,
}

impl Session {
    fn view(&self, result: Result<String, String>) -> View {
        let failed = result.is_err();
        View {
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
        }
    }

    fn handle(&mut self, command: Command) -> Result<String, String> {
        match command {
            Command::HomeAction => {
                let network = snapshot();
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
                Ok(strings::SAVED.into())
            }
            Command::SelectAccount(username) => {
                self.update(|settings| settings.select(username))?;
                Ok(strings::SELECTED.into())
            }
            Command::DeleteAccount(username) => {
                self.update(|settings| {
                    settings.delete(&username);
                    Ok(())
                })?;
                Ok(strings::DELETED.into())
            }
            Command::SavePreferences(preferences) => {
                self.update(|settings| {
                    settings.preferences = preferences;
                    Ok(())
                })?;
                Ok(strings::SAVED.into())
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
        let network = snapshot();
        let changed = network != self.network;
        if changed {
            self.last_auto = None;
            self.online = Online::Unknown;
            self.account_info = AccountInfo::default();
            self.device_session = None;
        }
        self.network = network;
        self.remember_detected_wifi()?;
        if !self.settings.preferences.allows_auto_login(&self.network) {
            return if changed {
                self.refresh()
            } else {
                Ok(strings::READY.into())
            };
        }
        let Some(username) = self.settings.selected.clone() else {
            return Ok(strings::NO_ACCOUNT.into());
        };
        let key = (self.network.clone(), username);
        if self.last_auto.as_ref() == Some(&key) {
            return Ok(strings::READY.into());
        }
        self.last_auto = Some(key);
        let campus = Campus::new()?;
        self.online = campus.status(&self.network);
        let current = snapshot();
        if current != self.network {
            self.network = current;
            self.online = Online::Unknown;
            self.account_info = AccountInfo::default();
            self.device_session = None;
            return Err(strings::NETWORK_CHANGED.into());
        }
        // An unknown result must not trigger repeated credential submissions.
        match self.online {
            Online::Yes => {
                self.load_account()?;
                Ok(strings::LOGGED_IN.into())
            }
            Online::Unknown => Ok(strings::UNKNOWN.into()),
            Online::No => self.login(Some(self.network.clone()), true),
        }
    }

    fn login(&mut self, expected: Option<Network>, automatic: bool) -> Result<String, String> {
        let network = snapshot();
        self.online = Online::Unknown;
        self.account_info = AccountInfo::default();
        self.device_session = None;
        if expected.as_ref().is_some_and(|previous| {
            previous != &network
                || automatic && !self.settings.preferences.allows_auto_login(&network)
        }) {
            self.network = network;
            return Err(strings::NETWORK_CHANGED.into());
        }
        if !usable_ipv4(&network.ipv4) {
            return Err(strings::NO_NETWORK.into());
        }
        self.network = network;
        let username = self
            .settings
            .selected
            .as_deref()
            .ok_or(strings::NO_ACCOUNT)?;
        let password = self.settings.password(username)?;
        let result = Campus::new()?.login(username, &password, &self.network)?;
        match result {
            LoginResult::Success => {
                self.online = Online::Yes;
                self.load_account()?;
                Ok(strings::AUTHENTICATED.into())
            }
            LoginResult::AlreadyOnline => {
                self.online = Online::Yes;
                self.load_account()?;
                Ok(strings::ALREADY_ONLINE.into())
            }
            LoginResult::Failure(reason) => {
                self.online = Online::Unknown;
                // Do not echo untrusted server text, which may reflect credentials.
                Err(if reason
                    .to_ascii_lowercase()
                    .replace(' ', "")
                    .contains("ldapautherror")
                    || reason.contains("密码")
                {
                    strings::INVALID_PASSWORD
                } else {
                    strings::LOGIN_FAILED
                }
                .into())
            }
            LoginResult::Unknown => Err(strings::UNKNOWN_RESPONSE.into()),
        }
    }

    fn refresh(&mut self) -> Result<String, String> {
        self.network = snapshot();
        self.online = Online::Unknown;
        self.account_info = AccountInfo::default();
        self.device_session = None;
        self.remember_detected_wifi()?;
        if usable_ipv4(&self.network.ipv4) {
            self.online = Campus::new()?.status(&self.network);
        }
        self.ensure_current()?;
        self.load_account()?;
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
            crate::account::load(&self.network)
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
        let result = self
            .device_session
            .as_ref()
            .ok_or(strings::DEVICE_STALE)?
            .logout(&request.device);
        self.ensure_current()?;
        match result {
            Ok(crate::account::LogoutResult::Failure) => Err(strings::DEVICE_LOGOUT_FAILED.into()),
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
        let current = snapshot();
        if current != self.network {
            self.network = current;
            self.online = Online::Unknown;
            self.account_info = AccountInfo::default();
            self.device_session = None;
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
            store,
            settings,
            network: Network::default(),
            online: Online::Unknown,
            account_info: AccountInfo::default(),
            last_auto: None,
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
