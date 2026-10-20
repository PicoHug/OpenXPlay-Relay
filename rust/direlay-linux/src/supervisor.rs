// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15
// OpenXPlay-Stream oxp-relay CLI contract tests.
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    ffi::OsString,
    io::{self, BufRead, BufReader, Read},
    net::Ipv4Addr,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Condvar, Mutex,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};
pub type EventSink = Arc<dyn Fn(Value) + Send + Sync>;
#[derive(Clone, Debug)]
pub struct CarlinkSettings {
    pub ble: Option<BleCarlinkSettings>,
    pub head_unit: Ipv4Addr,
    pub local: Ipv4Addr,
    pub pin: Option<String>,
    pub ports: BTreeMap<String, u16>,
}
#[derive(Clone, Debug)]
pub struct BleCarlinkSettings {
    pub interface: String,
    pub address: Option<String>,
    pub id: String,
    /// Carlink band the head unit creates its group on: 0 is 2.4 GHz, 1 allows 5 GHz.
    pub band: u8,
}
#[derive(Clone, Debug)]
pub struct SupervisorSettings {
    pub binary: PathBuf,
    pub socket: PathBuf,
    pub web_port: u16,
    pub web_root: PathBuf,
    pub plugins: Option<crate::plugins::OutputPlugins>,
    pub state_dir: PathBuf,
    pub addresses: Vec<Ipv4Addr>,
    pub pairing: bool,
    pub carlink: Option<CarlinkSettings>,
}
impl SupervisorSettings {
    pub fn arguments(&self) -> Vec<OsString> {
        let mut args: Vec<OsString> = if self.web_port == 0 {
            vec!["--unix".into(), self.socket.as_os_str().into()]
        } else {
            vec![
                "--unix".into(),
                self.socket.as_os_str().into(),
                "--web-port".into(),
                self.web_port.to_string().into(),
                "--web-root".into(),
                self.web_root.as_os_str().into(),
                "--web-bind".into(),
                "0.0.0.0".into(),
                "--tls-self-signed".into(),
                self.state_dir.join("receiver-tls").into_os_string(),
                "--tls-san".into(),
                "openxplay-linux.local".into(),
            ]
        };
        args.extend([
            "--relay-name".into(),
            crate::branding::DEVICE_NAME.into(),
            "--relay-version".into(),
            env!("CARGO_PKG_VERSION").into(),
        ]);
        let mut ips = self.addresses.clone();
        ips.sort();
        ips.dedup();
        for ip in ips.into_iter().filter(|_| self.web_port != 0) {
            args.extend(["--tls-san".into(), ip.to_string().into()]);
        }
        if self.pairing && self.web_port != 0 {
            args.push("--pairing".into());
        }
        if let Some(c) = &self.carlink {
            args.extend([
                "--state-dir".into(),
                self.state_dir.join("carlink").into_os_string(),
            ]);
            if let Some(ble) = &c.ble {
                args.extend([
                    "--clink-link".into(),
                    "ble".into(),
                    "--clink-interface".into(),
                    ble.interface.clone().into(),
                    "--clink-name".into(),
                    crate::branding::DEVICE_NAME.into(),
                    "--clink-model".into(),
                    crate::branding::MODEL.into(),
                    "--clink-id".into(),
                    ble.id.clone().into(),
                    "--clink-band".into(),
                    ble.band.to_string().into(),
                    "--pin-file".into(),
                    self.state_dir.join("carlink/pin").into_os_string(),
                ]);
                if let Some(address) = &ble.address {
                    args.extend(["--clink-address".into(), address.into()]);
                }
            } else {
                args.extend([
                    "--clink-head-unit".into(),
                    c.head_unit.to_string().into(),
                    "--clink-local".into(),
                    c.local.to_string().into(),
                ]);
                if let Some(pin) = &c.pin {
                    args.extend(["--pin".into(), pin.into()]);
                }
            }
            if !c.ports.is_empty() {
                args.extend([
                    "--port".into(),
                    c.ports
                        .iter()
                        .map(|(k, v)| format!("{k}={v}"))
                        .collect::<Vec<_>>()
                        .join(",")
                        .into(),
                ]);
            }
        }
        if let Some(plugins) = &self.plugins {
            let enabled = plugins.present_ids();
            if !enabled.is_empty() {
                args.extend([
                    "--plugins-root".into(),
                    plugins.root.as_os_str().into(),
                    "--plugins-enabled".into(),
                    enabled.join(",").into(),
                    // Stream adds plugins/storage below this state directory.
                    "--plugins-state-dir".into(),
                    self.state_dir.as_os_str().into(),
                ]);
            }
        }
        args
    }
    pub fn receiver_urls(&self, host: &str) -> Vec<String> {
        if self.web_port == 0 {
            return Vec::new();
        }
        let mut urls = vec![format!("https://{host}:{}/", self.web_port)];
        for ip in &self.addresses {
            let url = format!("https://{ip}:{}/", self.web_port);
            if !urls.contains(&url) {
                urls.push(url);
            }
        }
        urls
    }
}
pub fn carlink_attempt_failed(event: &Value) -> bool {
    (event["type"] == "link" && event["state"] == "lost")
        || event["type"] == "error"
        || event["type"] == "pin-timeout"
        || (event["type"] == "state"
            && matches!(
                event["state"].as_str(),
                Some("ended" | "disconnected" | "Ended" | "Disconnected")
            ))
}
// oxp-bin FileTrust stores base64 64-byte peer public keys separately from its own identity.
pub fn has_trusted_carlink(state: &Path) -> bool {
    std::fs::read(state.join("carlink/trust.json"))
        .ok()
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        .and_then(|v| v["peers"].as_object().cloned())
        .is_some_and(|peers| {
            peers.values().any(|key| {
                key.as_str().is_some_and(|s| {
                    s.len() == 88
                        && s.ends_with("==")
                        && s.bytes()
                            .take(86)
                            .all(|b| b.is_ascii_alphanumeric() || b == b'+' || b == b'/')
                })
            })
        })
}
impl SupervisorSettings {
    pub fn launch_arguments(&self, attempt: bool) -> Vec<OsString> {
        let mut settings = self.clone();
        if !attempt && !has_trusted_carlink(&self.state_dir) {
            settings.carlink = None;
        }
        settings.arguments()
    }
}
pub trait RelayChild: Send {
    fn exited(&mut self) -> io::Result<bool>;
    fn stop(&mut self) -> io::Result<()>;
}
pub trait ChildFactory: Send + Sync + 'static {
    fn spawn(
        &self,
        binary: &Path,
        args: &[OsString],
        events: EventSink,
    ) -> io::Result<Box<dyn RelayChild>>;
}
pub struct ProcessChildFactory;
struct ProcessChild {
    child: Child,
    readers: Vec<JoinHandle<()>>,
}
/// Ignore invalid/oversized event lines while keeping a bounded buffer.
pub fn forward_json_lines(input: impl Read, events: &EventSink) -> io::Result<()> {
    let mut input = BufReader::new(input);
    let mut line = Vec::new();
    let mut oversized = false;
    loop {
        let buffer = input.fill_buf()?;
        if buffer.is_empty() {
            if !oversized && !line.is_empty() {
                if let Ok(Value::Object(value)) = serde_json::from_slice::<Value>(&line) {
                    events(Value::Object(value));
                }
            }
            break;
        }
        let end = buffer.iter().position(|b| *b == b'\n');
        let count = end.map_or(buffer.len(), |p| p + 1);
        if line.len() + count > 64 * 1024 {
            oversized = true;
        }
        if !oversized {
            line.extend_from_slice(&buffer[..count]);
        }
        input.consume(count);
        if end.is_some() {
            if !oversized {
                if let Ok(Value::Object(value)) = serde_json::from_slice::<Value>(&line) {
                    events(Value::Object(value));
                }
            }
            line.clear();
            oversized = false;
        }
    }
    Ok(())
}
impl ChildFactory for ProcessChildFactory {
    fn spawn(
        &self,
        binary: &Path,
        args: &[OsString],
        events: EventSink,
    ) -> io::Result<Box<dyn RelayChild>> {
        let mut child = Command::new(binary)
            .args(args)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()?;
        let stdout = child.stdout.take().unwrap();
        let stderr = child.stderr.take().unwrap();
        let copy = events.clone();
        let readers = vec![
            thread::spawn(move || {
                let _ = forward_json_lines(stdout, &copy);
            }),
            thread::spawn(move || {
                let _ = forward_json_lines(stderr, &events);
            }),
        ];
        Ok(Box::new(ProcessChild { child, readers }))
    }
}
impl RelayChild for ProcessChild {
    fn exited(&mut self) -> io::Result<bool> {
        Ok(self.child.try_wait()?.is_some())
    }
    fn stop(&mut self) -> io::Result<()> {
        if self.child.try_wait()?.is_none() {
            // The child PID stays ours until it is reaped.
            let result = unsafe { libc::kill(self.child.id() as libc::pid_t, libc::SIGTERM) };
            if result != 0 && io::Error::last_os_error().raw_os_error() != Some(libc::ESRCH) {
                return Err(io::Error::last_os_error());
            }
            let deadline = Instant::now() + Duration::from_secs(3);
            while self.child.try_wait()?.is_none() && Instant::now() < deadline {
                thread::sleep(Duration::from_millis(20));
            }
            if self.child.try_wait()?.is_none() {
                self.child.kill()?;
            }
        }
        self.child.wait()?;
        for reader in self.readers.drain(..) {
            let _ = reader.join();
        }
        Ok(())
    }
}
impl Drop for ProcessChild {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}
#[derive(Clone, Copy, Debug)]
pub struct RestartPolicy {
    pub initial: Duration,
    pub maximum: Duration,
    pub stable_after: Duration,
    pub poll: Duration,
}
impl Default for RestartPolicy {
    fn default() -> Self {
        Self {
            initial: Duration::from_millis(250),
            maximum: Duration::from_secs(30),
            stable_after: Duration::from_secs(30),
            poll: Duration::from_millis(100),
        }
    }
}
pub fn restart_delay(policy: RestartPolicy, failures: u32) -> Duration {
    policy
        .initial
        .saturating_mul(1u32.checked_shl(failures.min(31)).unwrap_or(u32::MAX))
        .min(policy.maximum)
}
struct BandBackoff {
    initial: Duration,
    maximum: Duration,
    failures: u32,
    until: Option<Instant>,
}
impl BandBackoff {
    fn new(initial: Duration, maximum: Duration) -> Self {
        Self {
            initial,
            maximum,
            failures: 0,
            until: None,
        }
    }
    fn observe(&mut self, event: &Value, now: Instant) -> Option<Duration> {
        if event["type"] == "link" && event["state"] == "joined" {
            self.failures = 0;
            self.until = None;
        }
        let reason = event["reason"]
            .as_str()
            .or_else(|| event["message"].as_str());
        if !carlink_attempt_failed(event)
            || !reason.is_some_and(|r| r.starts_with("head_unit_band_unsupported"))
            || self.until.is_some_and(|until| until > now)
        {
            return None;
        }
        let delay = self
            .initial
            .saturating_mul(1u32.checked_shl(self.failures.min(31)).unwrap_or(u32::MAX))
            .min(self.maximum);
        self.failures = self.failures.saturating_add(1);
        self.until = Some(now + delay);
        Some(delay)
    }
}
/// Pause after a head unit rejects the relay's Wi-Fi band, escalating from 60 s to 300 s. It
/// outlives child and supervisor restarts: share one between the supervisors a relay creates.
/// Only a successful Carlink join resets the escalation; Connect skips the current wait once.
#[derive(Clone)]
pub struct CarlinkBackoff(Arc<Mutex<BandBackoff>>);
impl Default for CarlinkBackoff {
    fn default() -> Self {
        Self::with_delays(Duration::from_secs(60), Duration::from_secs(300))
    }
}
impl CarlinkBackoff {
    pub fn with_delays(initial: Duration, maximum: Duration) -> Self {
        Self(Arc::new(Mutex::new(BandBackoff::new(initial, maximum))))
    }
    fn observe(&self, event: &Value, now: Instant) -> Option<Duration> {
        self.0.lock().unwrap().observe(event, now)
    }
    /// Time left in the current backoff; an expired one is cleared.
    fn remaining(&self, now: Instant) -> Option<Duration> {
        let mut backoff = self.0.lock().unwrap();
        let remaining = backoff
            .until
            .map(|until| until.saturating_duration_since(now))
            .filter(|left| !left.is_zero());
        if remaining.is_none() {
            backoff.until = None;
        }
        remaining
    }
    /// An explicit Connect may try at once; the next failure still escalates.
    fn allow_attempt(&self) {
        self.0.lock().unwrap().until = None;
    }
}
pub struct RelaySupervisor {
    settings: SupervisorSettings,
    factory: Arc<dyn ChildFactory>,
    events: EventSink,
    policy: RestartPolicy,
    stop: Arc<(Mutex<bool>, Condvar)>,
    running: Arc<AtomicBool>,
    worker: Option<JoinHandle<()>>,
    pairing_fragment: Arc<Mutex<Option<String>>>,
    connect: Arc<AtomicBool>,
    restart: Arc<AtomicBool>,
    band_backoff: CarlinkBackoff,
}
impl RelaySupervisor {
    pub fn new(
        settings: SupervisorSettings,
        factory: Arc<dyn ChildFactory>,
        events: EventSink,
    ) -> Self {
        Self {
            settings,
            factory,
            events,
            policy: RestartPolicy::default(),
            stop: Arc::new((Mutex::new(false), Condvar::new())),
            running: Arc::new(AtomicBool::new(false)),
            worker: None,
            pairing_fragment: Arc::new(Mutex::new(None)),
            connect: Arc::new(AtomicBool::new(false)),
            restart: Arc::new(AtomicBool::new(false)),
            band_backoff: CarlinkBackoff::default(),
        }
    }
    /// Shares the band backoff with earlier and later supervisors of the same relay.
    pub fn with_carlink_backoff(mut self, backoff: CarlinkBackoff) -> Self {
        self.band_backoff = backoff;
        self
    }
    pub fn set_restart_policy(&mut self, policy: RestartPolicy) {
        self.policy = policy;
    }
    pub fn receiver_urls(&self, host: &str) -> Vec<String> {
        let mut urls = self.settings.receiver_urls(host);
        if let Some(fragment) = self.pairing_fragment.lock().unwrap().as_ref() {
            for url in &mut urls {
                url.push('#');
                url.push_str(fragment);
            }
        }
        urls
    }
    pub fn running(&self) -> bool {
        self.running.load(Ordering::Relaxed)
    }
    pub fn connect_carlink(&self) {
        // Restart the child with Carlink arguments for one explicit attempt, also during a band
        // backoff. Lift the backoff first: the worker reads it after taking `connect`.
        self.band_backoff.allow_attempt();
        let stop = self.stop.0.lock().unwrap();
        self.connect.store(true, Ordering::Release);
        self.restart.store(true, Ordering::Release);
        drop(stop);
        self.stop.1.notify_all();
    }
    pub fn start(&mut self) -> io::Result<()> {
        if self.worker.is_some() {
            return Ok(());
        }
        *self.stop.0.lock().unwrap() = false;
        let (settings, factory, events, policy, stop, running) = (
            self.settings.clone(),
            self.factory.clone(),
            self.events.clone(),
            self.policy,
            self.stop.clone(),
            self.running.clone(),
        );
        let connect = self.connect.clone();
        let restart = self.restart.clone();
        let fragment = self.pairing_fragment.clone();
        let linking = Arc::new(AtomicBool::new(false));
        let band_backoff = self.band_backoff.clone();
        let child_events: EventSink = Arc::new({
            let events = events.clone();
            let fragment = fragment.clone();
            let restart = restart.clone();
            let state_dir = settings.state_dir.clone();
            let linking = linking.clone();
            let band_backoff = band_backoff.clone();
            move |event| {
                if event["type"] == "pairing-url" {
                    *fragment.lock().unwrap() = event["url"]
                        .as_str()
                        .and_then(|url| url.split_once('#').map(|(_, value)| value.to_owned()));
                }
                if linking.load(Ordering::Acquire)
                    && carlink_attempt_failed(&event)
                    && !has_trusted_carlink(&state_dir)
                {
                    restart.store(true, Ordering::Release);
                }
                let delay = if linking.load(Ordering::Acquire) {
                    band_backoff.observe(&event, Instant::now())
                } else {
                    None
                };
                let reason = event["reason"]
                    .as_str()
                    .or_else(|| event["message"].as_str())
                    .map(str::to_owned);
                events(event);
                if let Some(delay) = delay {
                    // For display only: the backoff itself runs on the monotonic clock.
                    let next = crate::platform::wall_clock()
                        .as_secs()
                        .saturating_add(delay.as_secs());
                    events(json!({"type":"carlink-backoff", "reason":reason,
                        "delayMillis":delay.as_millis() as u64, "nextAttempt":next}));
                    // Stop internal link retries as well as supervisor respawns.
                    restart.store(true, Ordering::Release);
                }
            }
        });
        self.worker = Some(thread::Builder::new()
            .name("receiver-supervisor".into()).stack_size(256 * 1024)
            .spawn(move || {
                let wait = |duration| {
                    let (lock, wake) = &*stop;
                    let state = lock.lock().unwrap();
                    *wake.wait_timeout_while(state, duration, |stopped| !*stopped && !connect.load(Ordering::Acquire)).unwrap().0
                };
                let mut failures = 0;
                let mut unpaired_reported = false;
                while !*stop.0.lock().unwrap() {
                    *fragment.lock().unwrap() = None;
                    let attempt = connect.swap(false, Ordering::AcqRel);
                    // Connect lifted the backoff for this one attempt.
                    let backoff = band_backoff
                        .remaining(Instant::now())
                        .filter(|_| !attempt && settings.carlink.is_some());
                    if let Some(remaining) = backoff.filter(|_| settings.web_port == 0) {
                        // Carlink only: nothing to serve until the backoff ends or Connect.
                        let (lock, wake) = &*stop;
                        let state = lock.lock().unwrap();
                        if *wake.wait_timeout_while(state, remaining, |stopped| !*stopped && !connect.load(Ordering::Acquire)).unwrap().0 { break; }
                        continue;
                    }
                    // With the web receiver in the same child, keep it up without Carlink.
                    let backing_off = backoff.is_some();
                    let enabled = settings.carlink.is_some() && !backing_off && (attempt || has_trusted_carlink(&settings.state_dir));
                    linking.store(enabled, Ordering::Release);
                    if settings.carlink.is_some() && !enabled && !backing_off {
                        if !unpaired_reported {
                            events(json!({"type":"carlink-unpaired"}));
                            unpaired_reported = true;
                        }
                        if settings.web_port == 0 {
                            let (lock, wake) = &*stop;
                            let state = lock.lock().unwrap();
                            let _state = wake.wait_while(state, |stopped| !*stopped && !connect.load(Ordering::Acquire)).unwrap();
                            continue;
                        }
                    } else if !backing_off {
                        unpaired_reported = false;
                    }
                    let started = Instant::now();
                    remove_stale_socket(&settings.socket);
                    restart.store(false, Ordering::Release);
                    let arguments = if backing_off {
                        SupervisorSettings { carlink: None, ..settings.clone() }.arguments()
                    } else {
                        settings.launch_arguments(attempt)
                    };
                    match factory.spawn(&settings.binary, &arguments, child_events.clone()) {
                        Ok(mut child) => {
                            running.store(true, Ordering::Relaxed);
                            events(json!({"type": "receiver-started"}));
                            loop {
                                if wait(policy.poll) || restart.load(Ordering::Acquire) { break; }
                                // The web-only child makes way for Carlink when the backoff ends.
                                if backing_off && band_backoff.remaining(Instant::now()).is_none() { break; }
                                match child.exited() {
                                    Ok(false) => {},
                                    Ok(true) => break,
                                    Err(e) => {
                                        events(json!({"type": "receiver-error", "message": e.to_string()}));
                                        break;
                                    }
                                }
                            }
                            running.store(false, Ordering::Relaxed);
                            if let Err(e) = child.stop() {
                                events(json!({"type": "receiver-error", "message": e.to_string()}));
                            }
                            events(json!({"type":"receiver-stopped"}));
                        }
                        Err(e) => events(json!({"type": "receiver-error", "message": e.to_string()})),
                    }
                    *fragment.lock().unwrap() = None;
                    if *stop.0.lock().unwrap() { break; }
                    if started.elapsed() >= policy.stable_after { failures = 0; }
                    // Entering or leaving a backoff swaps the child's arguments at once.
                    let switched = backing_off != band_backoff.remaining(Instant::now()).is_some();
                    let delay = if switched || connect.load(Ordering::Acquire) { Duration::ZERO } else { restart_delay(policy, failures) };
                    failures = failures.saturating_add(1);
                    events(json!({"type": "receiver-restarting", "delayMillis": delay.as_millis() as u64}));
                    if wait(delay) { break; }
                }
                running.store(false, Ordering::Relaxed);
            })?);
        Ok(())
    }
    pub fn stop(&mut self) {
        *self.stop.0.lock().unwrap() = true;
        self.stop.1.notify_all();
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}
impl Drop for RelaySupervisor {
    fn drop(&mut self) {
        self.stop();
    }
}

/// A socket file left by a relay that died (or a previous service run) makes the next bind fail
/// with "Address in use"; remove it when nothing accepts connections on it.
fn remove_stale_socket(path: &Path) {
    use std::os::unix::fs::FileTypeExt;
    let is_socket = std::fs::symlink_metadata(path).is_ok_and(|m| m.file_type().is_socket());
    if is_socket && std::os::unix::net::UnixStream::connect(path).is_err() {
        let _ = std::fs::remove_file(path);
    }
}

#[cfg(test)]
mod band_tests {
    use super::*;
    use std::sync::mpsc::{channel, Receiver, Sender};

    #[test]
    fn band_backoff_sequence_duplicate_failure_and_success_reset() {
        let mut backoff = BandBackoff::new(Duration::from_secs(60), Duration::from_secs(300));
        let mut now = Instant::now();
        let failure =
            json!({"type":"link","state":"lost","reason":"head_unit_band_unsupported:5180"});
        for seconds in [60, 120, 240, 300, 300] {
            assert_eq!(
                backoff.observe(&failure, now),
                Some(Duration::from_secs(seconds))
            );
            assert_eq!(backoff.until, Some(now + Duration::from_secs(seconds)));
            assert!(backoff
                .observe(
                    &json!({"type":"error","message":"head_unit_band_unsupported:5180"}),
                    now
                )
                .is_none());
            now += Duration::from_secs(seconds);
        }
        backoff.observe(&json!({"type":"link","state":"joined"}), now);
        assert_eq!(backoff.failures, 0);
        assert_eq!(
            backoff.observe(&failure, now),
            Some(Duration::from_secs(60))
        );
    }

    struct Child(Sender<()>);
    impl RelayChild for Child {
        fn exited(&mut self) -> io::Result<bool> {
            Ok(false)
        }
        fn stop(&mut self) -> io::Result<()> {
            let _ = self.0.send(());
            Ok(())
        }
    }
    struct Factory(Sender<(Vec<OsString>, EventSink)>, Sender<()>);
    impl ChildFactory for Factory {
        fn spawn(
            &self,
            _: &Path,
            args: &[OsString],
            events: EventSink,
        ) -> io::Result<Box<dyn RelayChild>> {
            self.0.send((args.to_vec(), events)).unwrap();
            Ok(Box::new(Child(self.1.clone())))
        }
    }
    struct Fixture {
        dir: PathBuf,
        settings: SupervisorSettings,
        spawns: Receiver<(Vec<OsString>, EventSink)>,
        stops: Receiver<()>,
        events: Receiver<Value>,
        factory: Arc<Factory>,
        sink: EventSink,
    }
    impl Fixture {
        fn new(name: &str, web_port: u16) -> Self {
            let dir = std::env::temp_dir().join(format!(
                "band-backoff-{name}-{}-{:?}",
                std::process::id(),
                thread::current().id()
            ));
            std::fs::create_dir_all(&dir).unwrap();
            let settings = SupervisorSettings {
                binary: "fake".into(),
                socket: dir.join("socket"),
                web_port,
                web_root: dir.clone(),
                plugins: None,
                state_dir: dir.clone(),
                addresses: vec![],
                pairing: false,
                carlink: Some(CarlinkSettings {
                    ble: None,
                    head_unit: Ipv4Addr::LOCALHOST,
                    local: Ipv4Addr::LOCALHOST,
                    pin: None,
                    ports: BTreeMap::new(),
                }),
            };
            let (spawn_tx, spawns) = channel();
            let (stop_tx, stops) = channel();
            let (event_tx, events) = channel();
            let event_tx = Mutex::new(event_tx);
            Self {
                dir,
                settings,
                spawns,
                stops,
                events,
                factory: Arc::new(Factory(spawn_tx, stop_tx)),
                sink: Arc::new(move |event| {
                    let _ = event_tx.lock().unwrap().send(event);
                }),
            }
        }
        fn supervisor(&self, backoff: &CarlinkBackoff) -> RelaySupervisor {
            let mut relay = RelaySupervisor::new(
                self.settings.clone(),
                self.factory.clone(),
                self.sink.clone(),
            )
            .with_carlink_backoff(backoff.clone());
            relay.set_restart_policy(RestartPolicy {
                poll: Duration::from_millis(1),
                initial: Duration::ZERO,
                ..RestartPolicy::default()
            });
            relay
        }
        /// The next child: whether it links Carlink, and its event sink.
        fn spawned(&self) -> (bool, bool, EventSink) {
            let (args, sink) = self.spawns.recv_timeout(Duration::from_secs(2)).unwrap();
            let has = |flag: &str| args.iter().any(|arg| arg == flag);
            (has("--clink-head-unit"), has("--web-port"), sink)
        }
        fn backoff_delays(&self) -> Vec<u64> {
            self.events
                .try_iter()
                .filter(|v| v["type"] == "carlink-backoff")
                .map(|v| v["delayMillis"].as_u64().unwrap())
                .collect()
        }
        fn trust(&self) {
            std::fs::create_dir_all(self.dir.join("carlink")).unwrap();
            std::fs::write(
                self.dir.join("carlink/trust.json"),
                serde_json::to_vec(&json!({"peers":{"unit":format!("{}==", "A".repeat(86))}}))
                    .unwrap(),
            )
            .unwrap();
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }
    fn band_failure() -> Value {
        json!({"type":"link","state":"lost","reason":"head_unit_band_unsupported:5180"})
    }

    #[test]
    fn carlink_only_band_failure_blocks_respawn_until_connect_and_survives_restarts() {
        let f = Fixture::new("carlink-only", 0);
        let backoff = CarlinkBackoff::default();
        let mut relay = f.supervisor(&backoff);
        relay.connect_carlink();
        relay.start().unwrap();
        let (carlink, _, sink) = f.spawned();
        assert!(carlink);
        sink(band_failure());
        f.stops.recv_timeout(Duration::from_secs(2)).unwrap();
        assert!(f.spawns.recv_timeout(Duration::from_millis(100)).is_err());
        let backoff_event = f
            .events
            .try_iter()
            .find(|v| v["type"] == "carlink-backoff")
            .unwrap();
        assert_eq!(backoff_event["delayMillis"], 60000);
        assert_eq!(backoff_event["reason"], "head_unit_band_unsupported:5180");
        assert!(backoff_event["nextAttempt"].as_u64().is_some());
        // Restarting the supervisor, or replacing it, keeps the backoff.
        relay.stop();
        relay.start().unwrap();
        assert!(f.spawns.recv_timeout(Duration::from_millis(100)).is_err());
        relay.stop();
        let mut relay = f.supervisor(&backoff);
        relay.start().unwrap();
        assert!(f.spawns.recv_timeout(Duration::from_millis(100)).is_err());
        // Connect tries at once, and the next failure escalates.
        relay.connect_carlink();
        let (carlink, _, sink) = f.spawned();
        assert!(carlink);
        sink(band_failure());
        f.stops.recv_timeout(Duration::from_secs(2)).unwrap();
        assert!(f.spawns.recv_timeout(Duration::from_millis(100)).is_err());
        assert_eq!(f.backoff_delays(), [120000]);
        relay.stop();
    }

    #[test]
    fn both_outputs_keep_the_web_receiver_during_band_backoff() {
        let f = Fixture::new("both", 8443);
        f.trust();
        let backoff =
            CarlinkBackoff::with_delays(Duration::from_millis(400), Duration::from_secs(60));
        let mut relay = f.supervisor(&backoff);
        relay.start().unwrap();
        let (carlink, web, sink) = f.spawned();
        assert!(carlink && web);
        let failed = Instant::now();
        sink(band_failure());
        // The web receiver comes back at once without Carlink.
        let (carlink, web, _) = f.spawned();
        assert!(!carlink && web);
        assert!(failed.elapsed() < Duration::from_millis(350));
        assert_eq!(f.backoff_delays(), [400]);
        // When the backoff ends the child relinks Carlink.
        let (carlink, web, sink) = f.spawned();
        assert!(carlink && web);
        assert!(failed.elapsed() >= Duration::from_millis(400));
        sink(band_failure());
        let (carlink, _, _) = f.spawned();
        assert!(!carlink);
        assert_eq!(f.backoff_delays(), [800]);
        // A supervisor restart keeps the web-only child.
        relay.stop();
        relay.start().unwrap();
        let (carlink, web, _) = f.spawned();
        assert!(!carlink && web);
        // Connect relinks at once; a successful join resets the escalation.
        relay.connect_carlink();
        let (carlink, _, sink) = f.spawned();
        assert!(carlink);
        sink(json!({"type":"link","state":"joined"}));
        sink(band_failure());
        let (carlink, _, _) = f.spawned();
        assert!(!carlink);
        assert_eq!(f.backoff_delays(), [400]);
        relay.stop();
    }
}
