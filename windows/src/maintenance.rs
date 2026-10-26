use crate::domain::MaintenancePreferences;
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PauseReason {
    Credentials,
    InvalidPassword,
    UserLogout,
}

/// Scheduling only; Session owns IO and all authenticated state.
#[derive(Default)]
pub struct Maintenance {
    pub deadline: Option<Instant>,
    pub retry_at: Option<Instant>,
    pub paused: Option<PauseReason>,
    failures: u32,
    unknowns: u32,
}

impl Maintenance {
    pub fn restart(&mut self, now: Instant) {
        *self = Self::default();
        self.wake(now);
    }

    pub fn wake(&mut self, now: Instant) {
        // Notifications must not bypass a login cooldown or clear credential errors.
        self.deadline = Some(now + Duration::from_secs(2));
    }

    pub fn network_changed(&mut self, now: Instant) {
        let paused = self
            .paused
            .filter(|reason| *reason != PauseReason::UserLogout);
        *self = Self {
            paused,
            ..Self::default()
        };
        self.wake(now);
    }

    pub fn observed(
        &mut self,
        online: crate::domain::Online,
        now: Instant,
        prefs: &MaintenancePreferences,
    ) {
        use crate::domain::Online;
        let seconds = if online == Online::Unknown {
            let delay = [10, 30, 60][self.unknowns.min(2) as usize];
            self.unknowns = self.unknowns.saturating_add(1);
            delay
        } else {
            self.unknowns = 0;
            if online == Online::Yes {
                self.failures = 0;
                self.retry_at = None;
            }
            prefs.check_interval_secs
        };
        self.deadline = Some(now + Duration::from_secs(u64::from(seconds)));
        if online == Online::No
            && self.paused.is_none()
            && let Some(retry) = self.retry_at
        {
            self.deadline = Some(self.deadline.unwrap().min(retry.max(now)));
        }
    }

    pub fn can_login(&self, now: Instant) -> bool {
        self.paused.is_none() && self.retry_at.is_none_or(|retry| now >= retry)
    }

    pub fn login_failed(&mut self, now: Instant, prefs: &MaintenancePreferences) {
        let seconds = prefs
            .retry_initial_secs
            .saturating_mul(1u32 << self.failures.min(16))
            .min(prefs.retry_max_secs);
        self.failures = self.failures.saturating_add(1);
        self.retry_at = Some(now + Duration::from_secs(u64::from(seconds)));
        self.deadline =
            Some(now + Duration::from_secs(u64::from(seconds.min(prefs.check_interval_secs))));
    }

    pub fn submitted(&mut self, now: Instant, prefs: &MaintenancePreferences) {
        // Until the follow-up probe confirms online, preserve the failure history.
        self.login_failed(now, prefs);
        self.deadline = Some(now + Duration::from_secs(3));
    }

    pub fn pause(&mut self, reason: PauseReason) {
        self.paused = Some(reason);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::Online;

    #[test]
    fn retry_backoff_survives_notifications_and_is_reset_only_by_confirmed_online() {
        let now = Instant::now();
        let prefs = MaintenancePreferences::default();
        let mut policy = Maintenance::default();
        for seconds in [15, 30, 60, 120, 240, 300, 300] {
            policy.login_failed(now, &prefs);
            assert_eq!(policy.retry_at, Some(now + Duration::from_secs(seconds)));
            policy.wake(now);
            assert!(!policy.can_login(now));
            policy.observed(Online::Unknown, now, &prefs);
            assert!(!policy.can_login(now));
        }
        policy.observed(Online::Yes, now, &prefs);
        assert!(policy.can_login(now));
        policy.login_failed(now, &prefs);
        assert_eq!(policy.retry_at, Some(now + Duration::from_secs(15)));
    }

    #[test]
    fn configured_intervals_and_unconfirmed_successes_preserve_retry_limits() {
        let now = Instant::now();
        let prefs = MaintenancePreferences {
            check_interval_secs: 120,
            retry_initial_secs: 60,
            retry_max_secs: 120,
            ..Default::default()
        };
        let mut policy = Maintenance::default();
        policy.observed(Online::Yes, now, &prefs);
        assert_eq!(policy.deadline, Some(now + Duration::from_secs(120)));
        policy.submitted(now, &prefs);
        assert_eq!(policy.retry_at, Some(now + Duration::from_secs(60)));
        policy.observed(Online::No, now + Duration::from_secs(3), &prefs);
        assert_eq!(policy.deadline, Some(now + Duration::from_secs(60)));
        policy.submitted(now + Duration::from_secs(60), &prefs);
        assert_eq!(policy.retry_at, Some(now + Duration::from_secs(180)));
        policy.submitted(now + Duration::from_secs(180), &prefs);
        assert_eq!(policy.retry_at, Some(now + Duration::from_secs(300)));
    }

    #[test]
    fn unknown_results_back_off_without_granting_login_or_resetting_pause() {
        let now = Instant::now();
        let mut policy = Maintenance::default();
        policy.pause(PauseReason::InvalidPassword);
        for seconds in [10, 30, 60, 60] {
            policy.observed(Online::Unknown, now, &MaintenancePreferences::default());
            assert_eq!(policy.deadline, Some(now + Duration::from_secs(seconds)));
            assert!(!policy.can_login(now));
        }
        policy.network_changed(now);
        assert_eq!(policy.paused, Some(PauseReason::InvalidPassword));
        policy.restart(now);
        assert!(policy.can_login(now));
    }

    #[test]
    fn reconnect_resumes_user_logout_and_success_waits_for_confirmation() {
        let now = Instant::now();
        let mut policy = Maintenance::default();
        policy.pause(PauseReason::UserLogout);
        policy.wake(now);
        assert!(!policy.can_login(now));
        policy.network_changed(now);
        assert!(policy.can_login(now));
        policy.submitted(now, &MaintenancePreferences::default());
        assert!(!policy.can_login(now + Duration::from_secs(3)));
        assert_eq!(policy.deadline, Some(now + Duration::from_secs(3)));
    }
}
