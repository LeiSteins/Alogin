use std::{
    collections::VecDeque,
    sync::atomic::{AtomicU64, Ordering},
    sync::{Mutex, OnceLock},
    time::SystemTime,
};

const MAX_ENTRIES: usize = 200;

#[derive(Default)]
pub struct NotificationBatch {
    generation: Option<u64>,
    counts: [u64; 4],
    last_codes: [isize; 4],
}

impl NotificationBatch {
    pub fn add(&mut self, generation: Option<u64>, kind: usize, code: isize) {
        if self.generation != generation {
            *self = Self::default();
            self.generation = generation;
        }
        if generation.is_some() && (1..=4).contains(&kind) {
            self.counts[kind - 1] = self.counts[kind - 1].saturating_add(1);
            self.last_codes[kind - 1] = code;
        }
    }

    pub fn flush(&mut self) {
        let generation = self.generation;
        for (index, count) in self.counts.iter().enumerate() {
            if *count == 0 {
                continue;
            }
            let kind = ["接口", "IPv4 地址", "IPv4 路由", "WLAN ACM"][index];
            record(
                generation,
                Category::Network,
                format!(
                    "{kind} 通知：{count} 次，最后类型 {}，当前通知代次 {}",
                    self.last_codes[index],
                    crate::platform::network::revision()
                ),
            );
        }
        *self = Self::default();
    }
}

thread_local! { static OPERATION: std::cell::Cell<Option<u64>> = const { std::cell::Cell::new(None) }; }
static NEXT_OPERATION: AtomicU64 = AtomicU64::new(1);

pub struct Operation(Option<u64>);
impl Operation {
    pub fn begin() -> Self {
        let id = NEXT_OPERATION.fetch_add(1, Ordering::Relaxed);
        Self(OPERATION.with(|current| current.replace(Some(id))))
    }
}
impl Drop for Operation {
    fn drop(&mut self) {
        OPERATION.with(|current| current.set(self.0));
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Category {
    Http,
    Network,
    Refresh,
}

#[derive(Clone, Debug)]
pub struct Entry {
    pub category: Category,
    pub operation: Option<u64>,
    pub time: SystemTime,
    pub message: String,
}

#[derive(Default)]
struct State {
    enabled: bool,
    generation: u64,
    entries: VecDeque<Entry>,
}

fn state() -> &'static Mutex<State> {
    static STATE: OnceLock<Mutex<State>> = OnceLock::new();
    STATE.get_or_init(|| Mutex::new(State::default()))
}

pub fn set_enabled(enabled: bool) {
    let mut state = state().lock().unwrap_or_else(|e| e.into_inner());
    state.enabled = enabled;
    state.generation = state.generation.wrapping_add(1);
    if !enabled {
        state.entries.clear();
    }
}

pub fn clear() {
    let mut state = state().lock().unwrap_or_else(|e| e.into_inner());
    state.generation = state.generation.wrapping_add(1);
    state.entries.clear();
}

pub fn entries() -> Vec<Entry> {
    state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .entries
        .iter()
        .cloned()
        .collect()
}

pub fn generation() -> Option<u64> {
    let state = state().lock().unwrap_or_else(|e| e.into_inner());
    state.enabled.then_some(state.generation)
}

pub fn record(generation: Option<u64>, category: Category, message: String) {
    let Some(generation) = generation else { return };
    let mut state = state().lock().unwrap_or_else(|e| e.into_inner());
    if !state.enabled || state.generation != generation {
        return;
    }
    if state.entries.len() == MAX_ENTRIES {
        state.entries.pop_front();
    }
    state.entries.push_back(Entry {
        category,
        operation: OPERATION.with(std::cell::Cell::get),
        time: SystemTime::now(),
        message,
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn notification_batch_counts_by_kind_and_resets_for_new_generation() {
        let mut batch = NotificationBatch::default();
        batch.add(Some(1), 1, 7);
        batch.add(Some(1), 1, 8);
        batch.add(Some(1), 4, 9);
        assert_eq!(batch.counts, [2, 0, 0, 1]);
        assert_eq!(batch.last_codes[0], 8);
        batch.add(Some(2), 2, 5);
        assert_eq!(batch.counts, [0, 1, 0, 0]);
        batch.add(None, 1, 4);
        assert_eq!(batch.counts, [0, 0, 0, 0]);
    }

    #[test]
    fn clearing_and_disabling_discard_in_flight_entries_and_bound_storage() {
        set_enabled(true);
        let old = generation();
        clear();
        record(old, Category::Http, "stale".into());
        assert!(entries().is_empty());
        let current = generation();
        let operation = Operation::begin();
        for i in 0..205 {
            record(current, Category::Http, i.to_string());
        }
        let snapshot = entries();
        assert_eq!(snapshot.len(), 200);
        assert_eq!(snapshot[0].message, "5");
        assert!(snapshot[0].operation.is_some());
        assert_eq!(snapshot[0].operation, snapshot[199].operation);
        drop(operation);
        set_enabled(false);
        record(current, Category::Http, "stale".into());
        assert!(entries().is_empty());
    }
}
