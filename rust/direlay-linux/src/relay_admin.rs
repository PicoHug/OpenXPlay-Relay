// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15

use crate::Result;
use crate::*;
use crate::{
    admin::*,
    provision::*,
    settings::{Settings, SharedWifiNetwork},
    supervisor::*,
};
use std::{
    collections::{BTreeMap, HashMap},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::Duration,
};
type Provision = WifiProvisioner<ProcessNetworkManagerCli>;
/// The admin page opens Bluetooth pairing for ten minutes.
pub const PAIRING_SECONDS: u32 = 600;
enum Operation {
    Start,
    Stop,
    Restart,
    Forget,
    ForgetCarlink,
    ConnectCarlink(Option<ArmedPin>),
    Join(String, String),
    /// The startup provisioning: like `Initialize`, but in device-hotspot mode the controller
    /// starts first and only its AP waits for the provisioning.
    Startup,
    Initialize,
    Outputs(Option<Box<Operation>>),
    Hotspot,
    Network {
        mode_changed: bool,
        reconnect: bool,
    },
}
struct ArmedPin {
    pin: String,
    expires: std::time::Instant,
}
fn expire_pin(pin: &mut Option<ArmedPin>) {
    if pin
        .as_ref()
        .is_some_and(|p| p.expires <= std::time::Instant::now())
    {
        *pin = None;
    }
}
fn apply_carlink_event(
    status: &mut AdminCarlink,
    armed: &mut Option<ArmedPin>,
    state: &std::path::Path,
    event: &serde_json::Value,
) {
    expire_pin(armed);
    status.apply_event(event, crate::admin::auth::monotonic_seconds());
    if event["type"] == "pin-request" {
        if let Some(pin) = armed.take() {
            if crate::platform::private_state_file(&carlink_pin_path(state), pin.pin.as_bytes())
                .is_ok()
            {
                status.clear_pin();
            }
        }
    }
    if carlink_attempt_failed(event)
        || (event["type"] == "state"
            && matches!(status.state.as_str(), "playing" | "active" | "connected"))
    {
        *armed = None;
    }
}
fn carlink_event_sink(
    carlink: Arc<Mutex<AdminCarlink>>,
    armed: Arc<Mutex<Option<ArmedPin>>>,
    state_dir: std::path::PathBuf,
    events: Arc<JsonEvents>,
) -> EventSink {
    Arc::new(move |v| {
        apply_carlink_event(
            &mut carlink.lock().unwrap(),
            &mut armed.lock().unwrap(),
            &state_dir,
            &v,
        );
        let name = v["type"]
            .as_str()
            .or_else(|| v["event"].as_str())
            .unwrap_or("diagnostic");
        events.emit(name, &v);
    })
}
/// Browser diagnostics stay in memory for the admin page; per-second stats never reach the log.
fn receiver_event(
    receivers: &crate::receiver_diagnostics::ReceiverDiagnostics,
    event: &serde_json::Value,
) -> bool {
    receivers.observe(event, std::time::Instant::now()) && event["type"] == "stats"
}
fn likely_iphone(device: &BondedDevice) -> bool {
    device.iap2 || device.phone_class || device.name.to_lowercase().contains("iphone")
}
/// The Carlink link left the head unit's network: it went down, its session ended (oxp-relay then
/// leaves the network and exits) or the child stopped. Errors and PIN timeouts alone leave the link
/// up; the status shows them, and a real departure follows when they end the session.
fn carlink_link_left(event: &serde_json::Value) -> bool {
    (event["type"] == "link" && event["state"] == "lost")
        || event["type"] == "receiver-stopped"
        || (event["type"] == "state"
            && matches!(
                event["state"].as_str(),
                Some("ended" | "disconnected" | "Ended" | "Disconnected")
            ))
}
// This mailbox contains only public link data; credentials stay in the worker's override.
#[derive(Default)]
struct CarlinkFollow {
    target: Option<String>,
    changed: Option<std::time::Instant>,
    generation: u64,
}
impl CarlinkFollow {
    fn observe(&mut self, event: &serde_json::Value, interface: &str) {
        let target = if event["type"] == "link" && event["state"] == "joined" {
            if event["network"]["interface"] != interface {
                return;
            }
            let Some(ssid) = event["network"]["ssid"].as_str() else {
                return;
            };
            Some(ssid.to_owned())
        } else if carlink_link_left(event) {
            None
        } else {
            return;
        };
        self.target = target;
        self.changed = Some(std::time::Instant::now());
        self.generation = self.generation.wrapping_add(1);
    }
    fn ready(&self) -> bool {
        self.changed
            .is_some_and(|t| t.elapsed() >= Duration::from_millis(750))
    }
}
pub struct LinuxRelayAdmin {
    /// The open pairing window and when it closes, in `auth::monotonic_seconds`.
    pairing: Mutex<Option<(crate::linux_bluetooth::PairingMode, u64)>>,
    /// BlueZ for the admin pairing window; a fake in tests.
    pairing_backend: Arc<dyn crate::linux_bluetooth::PairingBackend>,
    /// Secure Connections on, and off only while a pairing window is open.
    secure_connections: Arc<crate::secure_connections::SecureConnections>,
    options: Options,
    settings: Mutex<Settings>,
    state: Arc<PersistentState>,
    events: Arc<JsonEvents>,
    provision: Arc<Mutex<Provision>>,
    provision_interface: Mutex<String>,
    controller: Mutex<Option<Arc<LinuxCarPlayController>>>,
    hotspot_lifetime: Arc<crate::hotspot_lifetime::HotspotLifetime>,
    /// BlueZ changes for every controller's Bluetooth waits; the bus listener outlives them.
    bluetooth_wake: Arc<crate::bluetooth_wake::BluetoothWake>,
    worker: Mutex<Option<std::thread::JoinHandle<()>>>,
    operation_worker: Mutex<Option<std::thread::JoinHandle<()>>>,
    supervisor: Mutex<Option<RelaySupervisor>>,
    /// Outlives the supervisors that output changes, Start and Restart replace.
    carlink_backoff: Mutex<CarlinkBackoff>,
    child_factory: Arc<dyn ChildFactory>,
    input: Arc<DiPlayInput>,
    stream: Arc<StreamConnection>,
    follow: Arc<Mutex<CarlinkFollow>>,
    network_override: Mutex<Option<SharedWifiNetwork>>,
    lifecycle: Mutex<()>,
    sink: Arc<RecordingMediaSink>,
    desired: AtomicBool,
    operations: std::sync::mpsc::SyncSender<Operation>,
    pending: Mutex<HashMap<std::thread::ThreadId, Operation>>,
    operation_rx: Mutex<Option<std::sync::mpsc::Receiver<Operation>>>,
    busy: Arc<AtomicBool>,
    closed: AtomicBool,
    restart_required: AtomicBool,
    apply_pending: AtomicBool,
    carlink: Arc<Mutex<AdminCarlink>>,
    armed_pin: Arc<Mutex<Option<ArmedPin>>>,
    receivers: Arc<crate::receiver_diagnostics::ReceiverDiagnostics>,
    /// Set when an admin operation finished: the main loop ticks at once.
    tick_wake: (Mutex<bool>, std::sync::Condvar),
    /// Held while a provisioning step must finish before the controller's AP starts.
    provisioning: Arc<crate::provision::ProvisioningGate>,
}
impl LinuxRelayAdmin {
    pub fn new(
        options: Options,
        state: Arc<PersistentState>,
        events: Arc<JsonEvents>,
    ) -> Result<Self> {
        let mac =
            std::fs::read_to_string(format!("/sys/class/net/{}/address", options.wifi_interface))?;
        let provision = WifiProvisioner::new(
            ProcessNetworkManagerCli::default(),
            options.wifi_interface.clone(),
            mac.trim(),
            &options.state_dir,
        )?;
        let admin = Self::with_provisioner(options, state, events, provision)?;
        // Without BlueZ changes the controller's Bluetooth waits only poll.
        let signals = crate::linux_bluetooth::BluezSignals::new(admin.events.clone());
        if let Err(e) = admin.bluetooth_wake.listen(&signals) {
            admin
                .events
                .log(&format!("bluetooth-events unavailable: {e}; waits poll"));
        }
        Ok(admin)
    }
    fn with_provisioner(
        options: Options,
        state: Arc<PersistentState>,
        events: Arc<JsonEvents>,
        provision: Provision,
    ) -> Result<Self> {
        let input = Arc::new(DiPlayInput::with_plugins(
            events.clone(),
            options.settings.plugins,
        ));
        let stream =
            StreamConnection::new(options.stream_socket.clone(), input.clone(), events.clone());
        let sink = Arc::new(match &options.record_dir {
            Some(d) => RecordingMediaSink::new(d.clone(), events.clone())?,
            None => RecordingMediaSink::discard(events.clone()),
        });
        let (tx, rx) = std::sync::mpsc::sync_channel(16);
        let settings = Settings::load(&options.state_dir)?;
        input.initialize_video_state(&options.state_dir);
        // The AP stays down for a gap before the next one comes up, also across relay restarts.
        let hotspot_lifetime = Arc::new(crate::hotspot_lifetime::HotspotLifetime::with_down_gap(
            crate::hotspot_lifetime::AP_DOWN_GAP,
            Some(
                options
                    .state_dir
                    .join(crate::hotspot_lifetime::AP_DOWN_RECORD),
            ),
        ));
        let secure_connections = Arc::new(
            crate::secure_connections::SecureConnections::new(
                Arc::new(ProcessCommandRunner::new()),
                events.clone(),
            )
            .with_adapter_index(Arc::new(crate::linux_bluetooth::adapter_index)),
        );
        Ok(Self {
            pairing: Mutex::new(None),
            pairing_backend: Arc::new(crate::linux_bluetooth::BluezPairing::new(events.clone())),
            secure_connections,
            provision_interface: Mutex::new(options.wifi_interface.clone()),
            options,
            settings: Mutex::new(settings),
            state,
            events,
            provision: Arc::new(Mutex::new(provision)),
            controller: Mutex::new(None),
            hotspot_lifetime,
            bluetooth_wake: Arc::default(),
            worker: Mutex::new(None),
            operation_worker: Mutex::new(None),
            supervisor: Mutex::new(None),
            carlink_backoff: Mutex::default(),
            child_factory: Arc::new(ProcessChildFactory),
            input,
            stream: Arc::new(stream),
            follow: Arc::new(Mutex::new(CarlinkFollow::default())),
            network_override: Mutex::new(None),
            lifecycle: Mutex::new(()),
            sink,
            desired: AtomicBool::new(true),
            operations: tx,
            pending: Mutex::new(HashMap::new()),
            operation_rx: Mutex::new(Some(rx)),
            busy: Arc::new(AtomicBool::new(false)),
            closed: AtomicBool::new(false),
            restart_required: AtomicBool::new(false),
            apply_pending: AtomicBool::new(false),
            armed_pin: Arc::new(Mutex::new(None)),
            receivers: Arc::default(),
            tick_wake: Default::default(),
            provisioning: Arc::default(),
            carlink: Arc::new(Mutex::new(AdminCarlink {
                state: "idle".into(),
                ..Default::default()
            })),
        })
    }
    fn effective_options(&self) -> Result<Options> {
        let mut o = self.options.clone();
        o.settings = self.settings.lock().unwrap().clone();
        o.apply_overrides()?;
        Ok(o)
    }
    pub fn start_services(self: &Arc<Self>) -> Result<()> {
        // Secure Connections on from the start, and again whenever bluetoothd resets the adapter.
        self.secure_connections.start(Some(&self.bluetooth_wake))?;
        self.stream.start()?;
        let shell = self.clone();
        let rx = self
            .operation_rx
            .lock()
            .unwrap()
            .take()
            .ok_or_else(|| std::io::Error::other("Services already started"))?;
        *self.operation_worker.lock().unwrap() = Some(crate::platform::spawn_worker(
            "relay-admin-actions",
            Box::new(move || {
                while !shell.closed.load(Ordering::Acquire) {
                    expire_pin(&mut shell.armed_pin.lock().unwrap());
                    let op = match rx.recv_timeout(Duration::from_millis(100)) {
                        Ok(op) => Some(op),
                        Err(std::sync::mpsc::RecvTimeoutError::Timeout) => None,
                        Err(_) => break,
                    };
                    if op.is_none() && !shell.follow.lock().unwrap().ready() {
                        continue;
                    }
                    shell.busy.store(true, Ordering::Release);
                    let lifecycle = shell.lifecycle.lock().unwrap();
                    if let Err(e) = shell.reconcile_carlink_network() {
                        shell
                            .events
                            .emit("error", &serde_json::json!({"message":e.to_string()}));
                    }
                    let operated = op.is_some();
                    if let Some(op) = op {
                        let network = matches!(
                            op,
                            Operation::Join(..)
                                | Operation::Network { .. }
                                | Operation::Initialize
                                | Operation::Startup
                        );
                        if let Err(e) = shell.operation(op) {
                            shell.events.emit("error", &serde_json::json!({"message": if network { "error.networks".to_string() } else { e.to_string() }}));
                        }
                    }
                    drop(lifecycle);
                    shell.busy.store(false, Ordering::Release);
                    if operated {
                        shell.wake_tick();
                    }
                }
            }),
        )?);
        self.busy.store(true, Ordering::Release);
        self.operations
            .try_send(Operation::Startup)
            .map_err(std::io::Error::other)?;
        self.start_supervisor()?;
        Ok(())
    }
    fn start_supervisor(&self) -> Result<()> {
        let o = self.effective_options()?;
        self.input.set_plugins_enabled(o.settings.plugins);
        let mut addresses = crate::linux_hotspot::interface_addresses()?
            .into_iter()
            .filter(|(_, a, _)| !a.is_loopback())
            .map(|(_, a, _)| a)
            .collect::<Vec<_>>();
        addresses.push(std::net::Ipv4Addr::new(10, 78, 0, 1));
        let occupied = crate::linux_hotspot::interface_addresses()?
            .iter()
            .map(|(_, a, p)| format!("{a}/{p}"))
            .collect::<Vec<_>>();
        if let Ok(a) = HotspotAddressPolicy::select_subnet(&occupied) {
            addresses.push(a);
        }
        let carlink = if o.settings.output != "web" {
            Some(CarlinkSettings {
                ble: if o.settings.carlink_link == "ble" {
                    Some(BleCarlinkSettings {
                        interface: o.wifi_interface.clone(),
                        address: o.settings.carlink_address.clone(),
                        id: self.state.identity()?.pairing_id.clone(),
                        band: crate::linux_hotspot::supports_5ghz(
                            &ProcessCommandRunner::new(),
                            &o.wifi_interface,
                        ) as u8,
                    })
                } else {
                    None
                },
                head_unit: o
                    .settings
                    .carlink_head_unit
                    .parse()
                    .unwrap_or(std::net::Ipv4Addr::UNSPECIFIED),
                local: o
                    .settings
                    .carlink_local
                    .parse()
                    .unwrap_or(std::net::Ipv4Addr::UNSPECIFIED),
                pin: o.settings.carlink_pin.clone(),
                ports: o.settings.carlink_ports.clone(),
            })
        } else {
            None
        };
        let settings = SupervisorSettings {
            binary: o.oxp_relay,
            socket: o.stream_socket,
            web_port: if o.settings.output == "carlink" {
                0
            } else {
                o.settings.receiver_port
            },
            plugins: o.settings.plugins.then(|| crate::plugins::OutputPlugins {
                root: o.plugins_root.clone(),
                enabled: o.settings.plugins_enabled.clone(),
            }),
            web_root: o.web_root,
            state_dir: o.state_dir,
            addresses,
            pairing: o.settings.require_pairing_code,
            carlink,
        };
        *self.carlink.lock().unwrap() = AdminCarlink {
            state: "waiting".into(),
            ..Default::default()
        };
        let event_sink = carlink_event_sink(
            self.carlink.clone(),
            self.armed_pin.clone(),
            self.options.state_dir.clone(),
            self.events.clone(),
        );
        let follow = self.follow.clone();
        let interface = o.wifi_interface.clone();
        let stream = self.stream.clone();
        let timeline_events = self.events.clone();
        let receivers = self.receivers.clone();
        let hotspot = self.hotspot_lifetime.clone();
        let event_sink: EventSink = Arc::new(move |mut event| {
            if event["type"] == "pairing-url" {
                let rows = crate::linux_hotspot::interface_addresses().unwrap_or_default();
                prefer_pairing_address(&mut event, &interface, &rows);
            }
            if receiver_event(&receivers, &event) {
                return;
            }
            if event["type"] == "receiver-started" {
                stream.receiver_started();
            } else if event["type"] == "receiver-stopped" {
                stream.receiver_stopped();
            }
            let mut carlink = follow.lock().unwrap();
            if event["type"] == "link"
                && event["state"] == "joined"
                && event["network"]["interface"] == interface
                && carlink.target.as_deref() != event["network"]["ssid"].as_str()
            {
                timeline_events.follow_over("link-joined");
            }
            carlink.observe(&event, &interface);
            // The link owns the radio from now on. Until the debounced follow-over stops the old
            // controller, its retries must not bring an AP up on the link's interface.
            if carlink.target.is_some() {
                hotspot.hold_for_carlink(true);
            }
            drop(carlink);
            event_sink(event);
        });
        let mut supervisor = RelaySupervisor::new(settings, self.child_factory.clone(), event_sink)
            .with_carlink_backoff(self.carlink_backoff.lock().unwrap().clone());
        supervisor.start()?;
        *self.supervisor.lock().unwrap() = Some(supervisor);
        Ok(())
    }
    fn reconcile_carlink_network(&self) -> Result<()> {
        let (target, generation) = {
            let follow = self.follow.lock().unwrap();
            if !follow.ready() {
                return Ok(());
            }
            (follow.target.clone(), follow.generation)
        };
        let current = self
            .network_override
            .lock()
            .unwrap()
            .as_ref()
            .map(|n| n.ssid.clone());
        if current == target {
            let mut follow = self.follow.lock().unwrap();
            if follow.generation == generation {
                follow.changed = None;
                if target.is_none() {
                    self.hotspot_lifetime.hold_for_carlink(false);
                    let held = self.provision.lock().unwrap().hold_for_carlink(false);
                    drop(follow);
                    if held {
                        self.operation(Operation::Initialize)?;
                    }
                }
            }
            return Ok(());
        }
        // Hold the provisioner even if reading the secret fails; never reconnect home here.
        if target.is_some() {
            self.provision.lock().unwrap().hold_for_carlink(true);
        }
        let network = match target
            .as_ref()
            .map(|ssid| self.provision.lock().unwrap().carlink_network(ssid))
            .transpose()
        {
            Ok(network) => network,
            Err(error) => {
                // Do not let the old controller retry its hotspot while Carlink owns the radio.
                // tick remains blocked until the target credentials have been resolved.
                self.stop_controller();
                let mut follow = self.follow.lock().unwrap();
                if follow.generation == generation {
                    follow.changed = Some(std::time::Instant::now());
                }
                return Err(error);
            }
        };
        let mut follow = self.follow.lock().unwrap();
        if follow.generation != generation {
            return Ok(());
        }
        *self.network_override.lock().unwrap() = network;
        follow.changed = None;
        drop(follow);
        // Publish the resolved target before stopping; tick shares the lifecycle lock.
        self.events.follow_over("controller-stop");
        self.stop_controller();
        if target.is_none() {
            self.events.follow_over("link-left");
            {
                // A newer join keeps the radio held until its own follow-over settles.
                let follow = self.follow.lock().unwrap();
                if follow.target.is_none() {
                    self.hotspot_lifetime.hold_for_carlink(false);
                }
            }
            self.provision.lock().unwrap().hold_for_carlink(false);
            self.operation(Operation::Initialize)?;
        }
        Ok(())
    }
    fn carplay_options(&self) -> Result<Options> {
        let mut options = self.effective_options()?;
        if let Some(network) = self.network_override.lock().unwrap().clone() {
            options.settings.network_mode = "shared-wifi".into();
            options.settings.shared_wifi_networks = vec![network];
        }
        Ok(options)
    }
    fn stop_controller(&self) {
        self.stop_controller_for_restart(false);
    }
    fn stop_controller_for_restart(&self, keep_hotspot: bool) {
        if keep_hotspot {
            self.hotspot_lifetime.keep_for_restart();
        }
        if let Some(c) = self.controller.lock().unwrap().take() {
            let _ = c.close();
        }
        if let Some(w) = self.worker.lock().unwrap().take() {
            let _ = w.join();
        }
        if !keep_hotspot {
            if let Err(e) = self.hotspot_lifetime.close() {
                self.events.log(&format!("Hotspot shutdown failed: {e}"));
            }
        }
        let _ = self.input.stop();
    }
    fn operation(&self, op: Operation) -> Result<()> {
        match op {
            Operation::Startup => {
                // In device-hotspot mode the provisioning only takes the saved Wi-Fi client
                // connections down, which matters to the AP alone; at boot its nmcli calls end
                // once NetworkManager has set wlan0 up. So the controller starts now, Bluetooth
                // and iAP2 go ahead, and only the AP's activation waits for the provisioning.
                // In shared-Wi-Fi mode the provisioning brings the network up and decides
                // whether CarPlay runs at all, so the controller still follows it.
                let early = self.network_override.lock().unwrap().is_none()
                    && self.desired.load(Ordering::Acquire)
                    && self.effective_options()?.settings.network_mode == "device-hotspot";
                let _provisioning = early.then(|| self.provisioning.hold());
                if early {
                    if let Err(e) = self.ensure_controller() {
                        self.events
                            .emit("error", &serde_json::json!({"message":e.to_string()}));
                    }
                }
                self.operation(Operation::Initialize)?;
            }
            Operation::Initialize => {
                if self.network_override.lock().unwrap().is_some() {
                    return Ok(());
                }
                let options = self.effective_options()?;
                let mut provision = self.provision.lock().unwrap();
                let mut interface = self.provision_interface.lock().unwrap();
                if *interface != options.wifi_interface {
                    provision.stop_setup()?;
                    let mac = std::fs::read_to_string(format!(
                        "/sys/class/net/{}/address",
                        options.wifi_interface
                    ))?;
                    *provision = WifiProvisioner::new(
                        ProcessNetworkManagerCli::default(),
                        options.wifi_interface.clone(),
                        mac.trim(),
                        &options.state_dir,
                    )?;
                    *interface = options.wifi_interface.clone();
                }
                provision.sync(&options.settings.shared_wifi_networks)?;
                if options.settings.network_mode == "shared-wifi" {
                    provision.start()?;
                } else {
                    provision.stop()?;
                }
            }
            Operation::Start => {
                self.stop_controller();
                self.operation(Operation::Initialize)?;
                if let Some(mut s) = self.supervisor.lock().unwrap().take() {
                    s.stop();
                }
                self.start_supervisor()?;
                self.desired.store(true, Ordering::Release);
            }
            Operation::Stop => {
                self.desired.store(false, Ordering::Release);
                self.stop_controller();
            }
            Operation::Hotspot => {
                if self.settings.lock().unwrap().network_mode == "device-hotspot"
                    && self.network_override.lock().unwrap().is_none()
                {
                    self.stop_controller();
                }
                self.apply_pending.store(false, Ordering::Release);
            }
            Operation::Outputs(network) => {
                // Output processes can be replaced while the controller and AP keep running.
                if let Some(mut supervisor) = self.supervisor.lock().unwrap().take() {
                    supervisor.stop();
                }
                if let Some(network) = network {
                    self.operation(*network)?;
                }
                self.start_supervisor()?;
                self.apply_pending.store(false, Ordering::Release);
            }
            Operation::Restart => {
                // An explicit restart starts over like Start, closing the AP too; only receiver
                // renegotiation keeps it (see tick).
                self.stop_controller();
                self.operation(Operation::Initialize)?;
                if let Some(mut s) = self.supervisor.lock().unwrap().take() {
                    s.stop();
                }
                self.start_supervisor()?;
                self.desired.store(true, Ordering::Release);
                self.restart_required.store(false, Ordering::Release);
                self.apply_pending.store(false, Ordering::Release);
            }
            Operation::Forget => {
                self.stop_controller();
                self.provision.lock().unwrap().forget()?;
                let mut settings = self.settings.lock().unwrap();
                settings.shared_wifi_networks.clear();
                settings.save(&self.options.state_dir)?;
            }
            Operation::ConnectCarlink(mut pin) => {
                let supplied = pin.is_some();
                expire_pin(&mut pin);
                if supplied && pin.is_none() {
                    return Err(std::io::Error::other("Pairing code expired"));
                }
                let mut supervisor = self.supervisor.lock().unwrap();
                if let Some(s) = supervisor.as_mut() {
                    // Drain the old child's events before arming the next attempt's code.
                    s.stop();
                }
                remove_carlink_pin(&self.options.state_dir)?;
                *self.armed_pin.lock().unwrap() = pin;
                if let Some(s) = supervisor.as_mut() {
                    s.connect_carlink();
                    s.start()?;
                }
            }
            Operation::ForgetCarlink => {
                *self.armed_pin.lock().unwrap() = None;
                let supervisor = self.supervisor.lock().unwrap().take();
                let restart = supervisor.is_some();
                if let Some(mut supervisor) = supervisor {
                    supervisor.stop();
                }
                // The band escalation belonged to the forgotten head unit.
                *self.carlink_backoff.lock().unwrap() = CarlinkBackoff::default();
                let mut carlink = self.carlink.lock().unwrap();
                *carlink = AdminCarlink {
                    state: "idle".into(),
                    ..Default::default()
                };
                remove_carlink_pin(&self.options.state_dir)?;
                clear_carlink_trust(&self.options.state_dir)?;
                let mut settings = self.settings.lock().unwrap();
                settings.carlink_address = None;
                settings.save(&self.options.state_dir)?;
                drop(settings);
                drop(carlink);
                if restart {
                    self.start_supervisor()?;
                }
            }
            Operation::Network {
                mode_changed,
                reconnect,
            } => {
                if mode_changed || reconnect {
                    self.stop_controller();
                }
                let options = self.effective_options()?;
                let mut provision = self.provision.lock().unwrap();
                provision.sync(&options.settings.shared_wifi_networks)?;
                if options.settings.network_mode == "shared-wifi" {
                    if mode_changed || reconnect || provision.status().ssid.is_none() {
                        provision.start()?;
                    }
                } else if mode_changed {
                    provision.stop()?;
                }
            }
            Operation::Join(ssid, password) => {
                self.stop_controller();
                self.provision.lock().unwrap().join(&ssid, &password)?;
                let mut settings = self.settings.lock().unwrap();
                settings.network_mode = "shared-wifi".into();
                if let Some(network) = settings
                    .shared_wifi_networks
                    .iter_mut()
                    .find(|n| n.ssid == ssid)
                {
                    network.password = password;
                } else {
                    settings
                        .shared_wifi_networks
                        .push(SharedWifiNetwork { ssid, password });
                }
                settings.save(&self.options.state_dir)?;
            }
        }
        Ok(())
    }
    pub fn host_addresses(&self) -> Result<Vec<std::net::Ipv4Addr>> {
        let options = self.effective_options()?;
        let following_carlink = self.network_override.lock().unwrap().is_some();
        let carplay_hotspot = !following_carlink
            && options.settings.network_mode == "device-hotspot"
            && self.controller.lock().unwrap().is_some();
        let mut addresses = crate::linux_hotspot::interface_addresses()?
            .into_iter()
            .filter(|(i, a, _)| {
                !a.is_loopback() && (!carplay_hotspot || i != &options.wifi_interface)
            })
            .map(|(_, a, _)| a)
            .collect::<Vec<_>>();
        addresses.sort();
        addresses.dedup();
        Ok(addresses)
    }
    /// Waits up to `timeout` before the next [`Self::tick`]; a finished admin operation ends the
    /// wait early. The startup network initialisation is one: the controller then starts at once.
    pub fn wait_for_tick(&self, timeout: Duration) {
        let (woken, changed) = &self.tick_wake;
        let woken = woken.lock().unwrap();
        let mut woken = changed
            .wait_timeout_while(woken, timeout, |woken| !*woken)
            .unwrap()
            .0;
        *woken = false;
    }
    fn wake_tick(&self) {
        *self.tick_wake.0.lock().unwrap() = true;
        self.tick_wake.1.notify_all();
    }
    pub fn tick(&self) -> Result<()> {
        let Ok(_lifecycle) = self.lifecycle.try_lock() else {
            return Ok(());
        };
        if self.busy.load(Ordering::Acquire)
            || !self.desired.load(Ordering::Acquire)
            || !self.pending.lock().unwrap().is_empty()
        {
            return Ok(());
        }
        self.ensure_controller()
    }
    /// Runs the CarPlay controller: starts one unless it runs, Carlink or the setup hotspot owns
    /// the radio, and applies a requested session restart. The caller holds `lifecycle`.
    fn ensure_controller(&self) -> Result<()> {
        {
            let follow = self.follow.lock().unwrap();
            let current = self.network_override.lock().unwrap();
            if follow
                .target
                .as_deref()
                .is_some_and(|ssid| current.as_ref().map(|n| n.ssid.as_str()) != Some(ssid))
            {
                return Ok(());
            }
        }
        if self.network_override.lock().unwrap().is_none()
            && self.provision.lock().unwrap().status().setup_hotspot
        {
            return Ok(());
        }
        let controller_running = self.controller.lock().unwrap().is_some();
        if controller_running {
            self.input
                .apply_requested_restart(|| self.stop_controller_for_restart(true));
            if self.controller.lock().unwrap().is_some() {
                return Ok(());
            }
        }
        let mut options = self.carplay_options()?;
        if self
            .pairing
            .lock()
            .unwrap()
            .as_ref()
            .is_some_and(|(_, d)| *d > crate::admin::auth::monotonic_seconds())
        {
            options.pair_seconds = None;
        }
        self.events.follow_over("controller-start");
        let platform = Arc::new(
            LinuxWirelessPlatform::new(
                options,
                Arc::new(ProcessCommandRunner::new()),
                self.state.identity()?,
                self.state.pairings()?,
                self.sink.clone(),
                self.events.clone(),
                self.input.clone(),
            )
            .with_hotspot_lifetime(self.hotspot_lifetime.clone())
            .with_bluetooth_wake(self.bluetooth_wake.clone())
            .with_secure_connections(self.secure_connections.clone())
            .with_provisioning(self.provisioning.clone()),
        );
        // Test mode: a simulated iPhone behind the same hotspot.
        let platform: Arc<dyn WirelessPlatform> = match &self.options.test_input {
            Some(test) => test.platform(
                platform,
                self.input.clone(),
                self.sink.clone(),
                self.events.clone(),
            ),
            None => platform,
        };
        let controller = Arc::new(LinuxCarPlayController::new(
            platform,
            self.events.clone(),
            Duration::from_secs(45),
        ));
        *self.controller.lock().unwrap() = Some(controller.clone());
        let events = self.events.clone();
        *self.worker.lock().unwrap() = Some(crate::platform::spawn_worker(
            "carplay-controller",
            Box::new(move || {
                if let Err(e) = controller.run() {
                    events.emit("error", &serde_json::json!({"message":e.to_string()}));
                }
            }),
        )?);
        Ok(())
    }
    pub fn shutdown(&self) {
        if let Some((pairing, _)) = self.pairing.lock().unwrap().take() {
            pairing.end("relay-stop");
        }
        self.closed.store(true, Ordering::Release);
        self.desired.store(false, Ordering::Release);
        if let Some(worker) = self.operation_worker.lock().unwrap().take() {
            let _ = worker.join();
        }
        self.stop_controller();
        // After the controller's `--pair` window has closed too.
        self.secure_connections.close("relay-stop");
        let _ = self.provision.lock().unwrap().stop_setup();
        if let Some(mut s) = self.supervisor.lock().unwrap().take() {
            s.stop();
        }
        let _ = self.stream.close();
        let _ = self.sink.close();
    }
    fn defer(&self, op: Operation) -> AdminResult {
        let mut pending = self.pending.lock().unwrap();
        if self.busy.load(Ordering::Acquire) || !pending.is_empty() {
            return Err("error.busy".into());
        }
        pending.insert(std::thread::current().id(), op);
        Ok(())
    }
}
fn carlink_pin_path(state: &std::path::Path) -> std::path::PathBuf {
    state.join("carlink/pin")
}
fn remove_carlink_pin(state: &std::path::Path) -> Result<()> {
    match std::fs::remove_file(carlink_pin_path(state)) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        result => result,
    }
}
fn clear_carlink_trust(state: &std::path::Path) -> Result<()> {
    let entries = match std::fs::read_dir(state.join("carlink")) {
        Ok(entries) => entries,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(e),
    };
    for entry in entries {
        let entry = entry?;
        if entry.file_name() == "pin" {
            continue;
        }
        if entry.file_type()?.is_dir() {
            std::fs::remove_dir_all(entry.path())?;
        } else {
            std::fs::remove_file(entry.path())?;
        }
    }
    Ok(())
}
fn picture_section() -> Section {
    Section {
        id: "picture".into(),
        title_key: "section.linuxPicture".into(),
        fields: [
            (
                "resolution",
                vec!["auto", "native", "1080p", "720p", "screen"],
            ),
            ("screenWidth", vec!["125", "200", "250", "300"]),
            ("videoCodec", vec!["auto", "h265", "h264"]),
        ]
        .into_iter()
        .map(|(key, choices)| Field {
            key: key.into(),
            field_type: "choice".into(),
            label_key: format!("field.linux.{key}"),
            hint_key: (key == "screenWidth").then(|| "hint.linux.screenWidth".into()),
            options: Some(
                choices
                    .into_iter()
                    .map(|value| FieldOption {
                        value: if key == "screenWidth" {
                            serde_json::json!(value.parse::<u32>().unwrap())
                        } else {
                            value.into()
                        },
                        label_key: format!("option.linux.{key}.{value}"),
                        hint_key: None,
                        illustration: None,
                    })
                    .collect(),
            ),
            visible_when: None,
            min: None,
            max: None,
        })
        .collect(),
    }
}
pub fn settings_schema() -> Vec<Section> {
    let fields: Vec<Field> = [
        ("networkMode", "choice"),
        ("sharedWifiNetworks", "networks"),
        ("hotspotChannel", "number"),
        ("iphone", "phone"),
        ("wifiInterface", "text"),
        ("rfcommChannel", "number"),
        ("requirePairingCode", "bool"),
        ("output", "choice"),
        ("carlinkLink", "choice"),
        ("carlinkAddress", "text"),
        ("carlinkHeadUnit", "text"),
        ("carlinkLocal", "text"),
    ]
    .into_iter()
    .map(|(key, kind)| Field {
        key: key.into(),
        field_type: kind.into(),
        label_key: format!(
            "field.{}",
            if key == "hotspotChannel" {
                "wifiChannel"
            } else {
                key
            }
        ),
        hint_key: matches!(
            key,
            "output" | "carlinkLink" | "carlinkAddress" | "carlinkHeadUnit" | "carlinkLocal"
        )
        .then(|| format!("hint.field.{key}")),
        options: match key {
            "networkMode" => Some(
                ["device-hotspot", "shared-wifi"]
                    .into_iter()
                    .map(|v| FieldOption {
                        value: v.into(),
                        label_key: format!("option.networkMode.{v}"),
                        hint_key: None,
                        illustration: None,
                    })
                    .collect(),
            ),
            "carlinkLink" => Some(
                ["ble", "static"]
                    .into_iter()
                    .map(|v| FieldOption {
                        value: v.into(),
                        label_key: format!("option.carlinkLink.{v}"),
                        hint_key: None,
                        illustration: None,
                    })
                    .collect(),
            ),
            "output" => Some(
                ["web", "carlink", "both"]
                    .into_iter()
                    .map(|v| FieldOption {
                        value: v.into(),
                        label_key: format!("option.output.{v}"),
                        hint_key: None,
                        illustration: None,
                    })
                    .collect(),
            ),
            _ => None,
        },
        visible_when: match key {
            "carlinkHeadUnit" | "carlinkLocal" => Some(VisibleWhen {
                key: "carlinkLink".into(),
                equals: vec!["static".into()],
            }),
            "carlinkAddress" => Some(VisibleWhen {
                key: "carlinkLink".into(),
                equals: vec!["ble".into()],
            }),
            "carlinkLink" => Some(VisibleWhen {
                key: "output".into(),
                equals: vec!["carlink".into(), "both".into()],
            }),
            "hotspotChannel" => Some(VisibleWhen {
                key: "networkMode".into(),
                equals: vec!["device-hotspot".into()],
            }),
            _ => None,
        },
        min: match key {
            "hotspotChannel" | "rfcommChannel" => Some(1.0),
            _ => None,
        },
        max: match key {
            "hotspotChannel" => Some(177.0),
            "rfcommChannel" => Some(30.0),
            _ => None,
        },
    })
    .collect();
    vec![
        picture_section(),
        Section {
            id: "connection".into(),
            title_key: "section.connection".into(),
            fields,
        },
        Section {
            id: "plugins".into(),
            title_key: "section.plugins".into(),
            fields: [("plugins", "bool"), ("pluginsEnabled", "plugins")]
                .into_iter()
                .map(|(key, kind)| Field {
                    key: key.into(),
                    field_type: kind.into(),
                    label_key: format!("field.{key}"),
                    hint_key: (key == "plugins").then(|| "hint.plugins".into()),
                    options: None,
                    visible_when: None,
                    min: None,
                    max: None,
                })
                .collect(),
        },
    ]
}
pub fn update_settings(
    current: &Settings,
    values: Values,
) -> std::result::Result<Settings, BTreeMap<String, String>> {
    let mut merged = serde_json::to_value(current).unwrap();
    let mut errors = BTreeMap::new();
    for (k, mut v) in values {
        if k == "pluginsEnabled" {
            let ids = serde_json::from_value::<Vec<String>>(v.clone());
            let mut seen = std::collections::BTreeSet::new();
            if !ids.is_ok_and(|ids| {
                ids.iter()
                    .all(|id| crate::plugins::valid_id(id) && seen.insert(id.clone()))
            }) {
                errors.insert(k, "error.plugins".into());
                continue;
            }
        }
        if k == "sharedWifiNetworks" {
            let parsed = (|| -> Option<serde_json::Value> {
                let mut networks = Vec::new();
                for item in v.as_array()? {
                    let item = item.as_object()?;
                    if item.keys().any(|k| k != "ssid" && k != "password") {
                        return None;
                    }
                    let ssid = item.get("ssid")?.as_str()?;
                    let password = match item.get("password") {
                        Some(value) => value.as_str()?,
                        None => {
                            &current
                                .shared_wifi_networks
                                .iter()
                                .find(|n| n.ssid == ssid)?
                                .password
                        }
                    };
                    networks.push(SharedWifiNetwork {
                        ssid: ssid.into(),
                        password: password.into(),
                    });
                }
                let check = Settings {
                    shared_wifi_networks: networks.clone(),
                    ..Default::default()
                };
                check.validate().ok()?;
                serde_json::to_value(networks).ok()
            })();
            match parsed {
                Some(networks) => v = networks,
                None => {
                    errors.insert(k, "error.networks".into());
                    continue;
                }
            }
        }
        if (k == "iphone" && v == "auto") || (k == "carlinkAddress" && v == "") {
            v = serde_json::Value::Null
        }
        if k == "rfcommChannel" && (v == 0 || v == "") {
            v = serde_json::Value::Null
        }
        if merged.get(&k).is_none() {
            errors.insert(k, "error.setting".into());
        } else {
            merged[&k] = v;
        }
    }
    if !errors.is_empty() {
        return Err(errors);
    }
    match serde_json::from_value::<Settings>(merged)
        .map_err(std::io::Error::other)
        .and_then(|s| s.validate().map(|_| s))
    {
        Ok(s)
            if s.network_mode == "shared-wifi"
                && current.network_mode != s.network_mode
                && s.shared_wifi_networks.is_empty() =>
        {
            Err(BTreeMap::from([(
                "networkMode".into(),
                "error.noSavedWifi".into(),
            )]))
        }
        Ok(s) => Ok(s),
        Err(e) => Err(BTreeMap::from([("settings".into(), e.to_string())])),
    }
}
impl RelayAdmin for LinuxRelayAdmin {
    fn response_failed(&self) {
        self.pending
            .lock()
            .unwrap()
            .remove(&std::thread::current().id());
    }
    fn response_sent(&self) {
        if let Some(op) = self
            .pending
            .lock()
            .unwrap()
            .remove(&std::thread::current().id())
        {
            if self.operations.try_send(op).is_err() {
                self.events.log("Admin action queue full");
            }
        }
    }
    fn status(&self) -> AdminStatus {
        let options = self
            .effective_options()
            .unwrap_or_else(|_| self.options.clone());
        let wifi = self.wifi_status();
        let devices = LinuxBluetooth::new(Arc::new(ProcessCommandRunner::new()), None, None)
            .devices()
            .unwrap_or_default();
        let now = crate::admin::auth::monotonic_seconds();
        let wall_now = crate::platform::wall_clock().as_secs();
        let pairing_remaining = self
            .pairing
            .lock()
            .unwrap()
            .as_ref()
            .map(|(_, deadline)| *deadline)
            .filter(|deadline| *deadline > now)
            .map(|deadline| deadline - now);
        let selection =
            crate::linux_bluetooth::IphoneSelection::select(options.iphone.as_deref(), &devices);
        let following_carlink = self.network_override.lock().unwrap().is_some();
        let controller = self.controller.lock().unwrap();
        let stage = controller.as_ref().map(|c| c.stage()).unwrap_or_else(|| {
            if wifi.setup_hotspot {
                "WaitingForWifi".into()
            } else {
                "Idle".into()
            }
        });
        let receiver_urls = self
            .supervisor
            .lock()
            .unwrap()
            .as_ref()
            .map(|s| s.receiver_urls("openxplay-linux.local"))
            .unwrap_or_default();
        let hotspot = crate::linux_hotspot::hotspot_credentials(&options.state_dir)
            .ok()
            .map(|(ssid, password)| {
                let ip = if !following_carlink
                    && options.settings.network_mode == "device-hotspot"
                    && controller.is_some()
                {
                    controller
                        .as_ref()
                        .and_then(|c| c.hotspot_info())
                        .and_then(|info| info.host_address)
                        .map(|a| a.to_string())
                } else {
                    None
                };
                AdminHotspot {
                    has_password: !password.is_empty(),
                    ssid,
                    password,
                    active: ip.is_some(),
                    ip,
                }
            });
        AdminStatus {
            video: self.input.video_status(),
            hotspot,
            wifi_control: true,
            host_addresses: crate::linux_hotspot::interface_addresses()
                .unwrap_or_default()
                .into_iter()
                .filter(|(interface, a, _)| {
                    let flags =
                        std::fs::read_to_string(format!("/sys/class/net/{interface}/flags"))
                            .ok()
                            .and_then(|flags| {
                                u32::from_str_radix(flags.trim().trim_start_matches("0x"), 16).ok()
                            });
                    !a.is_loopback() && flags.is_none_or(|flags| flags & 1 != 0)
                })
                .map(|(interface, a, _)| AdminHostAddress::new(interface, a.to_string()))
                .collect(),
            receiver_port: options.settings.receiver_port,
            admin_port: 6975,
            pairing_available: true,
            pairing_deadline: pairing_remaining.map(|remaining| wall_now.saturating_add(remaining)),
            pairing_remaining,
            pairing_cancelable: true,
            paired_devices: devices
                .iter()
                .filter(|d| d.paired)
                .map(|d| AdminPairedDevice {
                    name: d.name.clone(),
                    connected: d.connected,
                    iphone: likely_iphone(d),
                })
                .collect(),
            receivers: None,
            bluetooth_guard: crate::bluetooth_guard::read(&options.bluetooth_guard_status),
            bluetooth_secure_connections: self.secure_connections.enabled(),
            platform: "linux".into(),
            name: crate::branding::DEVICE_NAME.into(),
            version: env!("CARGO_PKG_VERSION").into(),
            host: "openxplay-linux.local".into(),
            admin_url: "https://openxplay-linux.local:6975/".into(),
            receiver_urls,
            state: stage,
            running: controller.is_some(),
            auto_start_paused: !self.desired.load(Ordering::Acquire),
            iphone: AdminIphone {
                name: selection.device().map(|d| d.name.clone()),
                reason: selection.reason().into(),
            },
            network: AdminNetwork {
                following_carlink,
                mode: options.settings.network_mode,
                wifi: wifi.ssid.clone(),
            },
            output: options.settings.output.clone(),
            carlink: if options.settings.output != "web" {
                let mut status = self.carlink.lock().unwrap().clone().at(now, wall_now);
                status.trusted = has_trusted_carlink(&self.options.state_dir);
                Some(status)
            } else {
                None
            },
            notices: {
                let mut notices = self
                    .provision
                    .try_lock()
                    .map(|p| p.notices().to_vec())
                    .unwrap_or_default();
                if self.restart_required.load(Ordering::Acquire)
                    || self.apply_pending.load(Ordering::Acquire)
                {
                    notices.push("notice.nextStart".into());
                }
                notices
            },
            wifi_provisioning: Some(wifi.setup_hotspot),
            wifi_client: Some(wifi),
        }
    }
    fn settings(&self) -> AdminSettings {
        let mut values = serde_json::to_value(self.settings.lock().unwrap().clone())
            .unwrap()
            .as_object()
            .unwrap()
            .clone();
        let current = self
            .provision
            .try_lock()
            .ok()
            .and_then(|p| p.current_ssid());
        let networks = self.settings.lock().unwrap().shared_wifi_networks.iter().map(|n|
            serde_json::json!({"ssid": n.ssid, "hasPassword": !n.password.is_empty(), "current": current.as_ref() == Some(&n.ssid)})
        ).collect::<Vec<_>>();
        values.insert("sharedWifiNetworks".into(), serde_json::json!(networks));
        let display = oxp_wire::display_settings(&self.input.display_settings());
        for key in ["resolution", "screenWidth", "videoCodec"] {
            values.insert(key.into(), display[key].clone());
        }
        let schema = settings_schema();
        values.retain(|key, _| {
            schema
                .iter()
                .any(|section| section.fields.iter().any(|field| &field.key == key))
        });
        if values["iphone"].is_null() {
            values.insert("iphone".into(), "auto".into());
        }
        AdminSettings {
            schema,
            values,
            installed_plugins: crate::plugins::installed(&self.options.plugins_root),
        }
    }
    fn update(&self, mut values: Values) -> AdminUpdate {
        let mut display = oxp_wire::display_settings(&self.input.display_settings());
        let mut picture_keys = Vec::new();
        for key in ["resolution", "screenWidth", "videoCodec"] {
            if let Some(value) = values.remove(key) {
                display[key] = value;
                picture_keys.push(key);
            }
        }
        let picture_changed = !picture_keys.is_empty();
        // The admin page shows errors by field key.
        let picture_errors = |keys: &[&str]| {
            AdminUpdate::Errors(
                keys.iter()
                    .map(|k| (k.to_string(), "error.linuxPicture".to_string()))
                    .collect(),
            )
        };
        let picture = match oxp_wire::decode_display_settings(&display) {
            Ok(picture) => picture,
            Err(_) => return picture_errors(&picture_keys),
        };
        let validate_plugins = values.contains_key("pluginsEnabled");
        let mut current = self.settings.lock().unwrap();
        match update_settings(&current, values) {
            Ok(next) => {
                if validate_plugins {
                    let installed = crate::plugins::installed(&self.options.plugins_root);
                    if next
                        .plugins_enabled
                        .iter()
                        .any(|id| !installed.iter().any(|p| &p.id == id))
                    {
                        return AdminUpdate::Errors(BTreeMap::from([(
                            "pluginsEnabled".into(),
                            "error.plugins".into(),
                        )]));
                    }
                }
                let mode_changed = next.network_mode != current.network_mode;
                let networks_changed = next.shared_wifi_networks != current.shared_wifi_networks;
                let network_update = mode_changed || networks_changed;
                let outputs_changed = next.plugins != current.plugins
                    || next.plugins_enabled != current.plugins_enabled
                    || next.output != current.output
                    || next.require_pairing_code != current.require_pairing_code
                    || next.receiver_port != current.receiver_port
                    || next.carlink_link != current.carlink_link
                    || next.carlink_address != current.carlink_address
                    || next.carlink_head_unit != current.carlink_head_unit
                    || next.carlink_local != current.carlink_local
                    || next.carlink_pin != current.carlink_pin
                    || next.carlink_ports != current.carlink_ports;
                let controller_changed = next.iphone != current.iphone
                    || next.rfcomm_channel != current.rfcomm_channel
                    || next.wifi_interface != current.wifi_interface
                    || next.hotspot_channel != current.hotspot_channel;
                let network = if network_update {
                    let ssid = self
                        .provision
                        .try_lock()
                        .ok()
                        .and_then(|p| p.current_ssid());
                    let reconnect = ssid.as_ref().is_some_and(|ssid| {
                        current
                            .shared_wifi_networks
                            .iter()
                            .find(|n| &n.ssid == ssid)
                            != next.shared_wifi_networks.iter().find(|n| &n.ssid == ssid)
                    });
                    Some(Operation::Network {
                        mode_changed,
                        reconnect,
                    })
                } else {
                    None
                };
                let operation = if outputs_changed {
                    Some(Operation::Outputs(network.map(Box::new)))
                } else {
                    network
                };
                if let Some(operation) = operation {
                    if let Err(error) = self.defer(operation) {
                        return AdminUpdate::Errors(BTreeMap::from([("settings".into(), error)]));
                    }
                }
                if picture_changed {
                    if let Err(e) = self.input.update_display_settings(picture) {
                        self.events.emit("error", &serde_json::json!({"message": format!("picture settings not applied: {e}")}));
                        return picture_errors(&picture_keys);
                    }
                }
                if let Err(e) = next.save(&self.options.state_dir) {
                    self.response_failed();
                    return AdminUpdate::Errors(BTreeMap::from([(
                        "settings".into(),
                        e.to_string(),
                    )]));
                }
                *current = next;
                if outputs_changed {
                    self.apply_pending.store(true, Ordering::Release);
                }
                if controller_changed {
                    self.restart_required.store(true, Ordering::Release);
                    AdminUpdate::NextStart
                } else {
                    AdminUpdate::AppliedNow
                }
            }
            Err(e) => AdminUpdate::Errors(e),
        }
    }
    fn action(&self, name: &str) -> AdminResult {
        match name {
            "pair-iphone" => {
                let mut pairing = self.pairing.lock().unwrap();
                let now = crate::admin::auth::monotonic_seconds();
                if pairing
                    .as_ref()
                    .is_some_and(|(_, deadline)| *deadline > now)
                {
                    return Ok(());
                }
                if let Some((previous, _)) = pairing.take() {
                    let _ = previous.close();
                }
                let mode =
                    crate::linux_bluetooth::PairingMode::new(PAIRING_SECONDS, self.events.clone())
                        .with_backend(self.pairing_backend.clone())
                        .with_secure_connections(self.secure_connections.clone());
                mode.start().map_err(|_| "error.pairing".to_string())?;
                *pairing = Some((
                    mode,
                    crate::admin::auth::monotonic_seconds() + u64::from(PAIRING_SECONDS),
                ));
                Ok(())
            }
            "cancel-pairing" => {
                if let Some((pairing, _)) = self.pairing.lock().unwrap().take() {
                    pairing.end("pairing-cancelled");
                }
                Ok(())
            }
            "start" => self.defer(Operation::Start),
            "stop" => self.defer(Operation::Stop),
            "restart" => self.defer(Operation::Restart),
            "forget-wifi" => self.defer(Operation::Forget),
            "forget-carlink" => self.defer(Operation::ForgetCarlink),
            _ => Err("error.action".into()),
        }
    }
    fn carlink_connect(&self, pin: Option<&str>) -> AdminResult {
        if self.settings.lock().unwrap().output == "web" {
            return Err("error.action".into());
        }
        if pin
            .is_some_and(|p| !(4..=8).contains(&p.len()) || !p.bytes().all(|b| b.is_ascii_digit()))
            || (pin.is_none() && !has_trusted_carlink(&self.options.state_dir))
        {
            return Err("error.carlinkPin".into());
        }
        self.defer(Operation::ConnectCarlink(pin.map(|pin| ArmedPin {
            pin: pin.into(),
            expires: std::time::Instant::now() + Duration::from_secs(110),
        })))
    }
    fn carlink_pin(&self, pin: &str) -> AdminResult {
        if self.busy.load(Ordering::Acquire) {
            return Err("error.busy".into());
        }
        if !(4..=8).contains(&pin.len()) || !pin.bytes().all(|b| b.is_ascii_digit()) {
            return Err("error.carlinkPin".into());
        }
        let mut status = self.carlink.lock().unwrap();
        if !status.pin_requested
            || status
                .pin_expiry
                .is_none_or(|d| d <= crate::admin::auth::monotonic_seconds())
        {
            return Err("error.carlinkPinNotRequested".into());
        }
        crate::platform::private_state_file(
            &carlink_pin_path(&self.options.state_dir),
            pin.as_bytes(),
        )
        .map_err(|_| "error.request".to_string())?;
        status.clear_pin();
        Ok(())
    }
    fn phones(&self) -> Vec<AdminPhone> {
        let choice = self.effective_options().ok().and_then(|o| o.iphone);
        LinuxBluetooth::new(Arc::new(ProcessCommandRunner::new()), None, None)
            .devices()
            .unwrap_or_default()
            .into_iter()
            .filter(|d| d.paired)
            .map(|d| AdminPhone {
                id: d.address.clone(),
                name: d.name.clone(),
                kind: "bluetooth".into(),
                connected: d.connected,
                likely_iphone: likely_iphone(&d),
                selected: choice
                    .as_ref()
                    .is_some_and(|c| c.eq_ignore_ascii_case(&d.address)),
            })
            .collect()
    }
    fn hotspot_update(&self, ssid: &str, password: &str) -> AdminResult {
        crate::linux_hotspot::validate_hotspot_credentials(ssid, password)
            .map_err(str::to_owned)?;
        self.defer(Operation::Hotspot)?;
        if crate::linux_hotspot::save_hotspot_credentials(&self.options.state_dir, ssid, password)
            .is_err()
        {
            self.response_failed();
            return Err("error.request".into());
        }
        if self.settings.lock().unwrap().network_mode == "device-hotspot" {
            self.apply_pending.store(true, Ordering::Release);
        }
        Ok(())
    }
    fn receivers(&self) -> Vec<serde_json::Value> {
        self.receivers.snapshot(std::time::Instant::now())
    }
    fn wifi_status(&self) -> WifiClient {
        self.provision
            .try_lock()
            .map(|p| p.status())
            .unwrap_or_default()
    }
    fn wifi_networks(&self) -> std::result::Result<Vec<WifiNetwork>, String> {
        self.provision
            .try_lock()
            .map_err(|_| "error.busy".to_string())?
            .scan()
            .map_err(|e| e.to_string())
    }
    fn wifi_join(&self, ssid: &str, password: Option<&str>) -> AdminResult {
        let settings = self.settings.lock().unwrap();
        let password = password
            .or_else(|| {
                settings
                    .shared_wifi_networks
                    .iter()
                    .find(|n| n.ssid == ssid)
                    .map(|n| n.password.as_str())
            })
            .ok_or("error.networks")?;
        let network = SharedWifiNetwork {
            ssid: ssid.into(),
            password: password.into(),
        };
        if !network.valid()
            || (settings.shared_wifi_networks.len() >= 10
                && !settings.shared_wifi_networks.iter().any(|n| n.ssid == ssid))
        {
            return Err("error.networks".into());
        }
        self.defer(Operation::Join(ssid.into(), password.into()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fake() -> (Arc<LinuxRelayAdmin>, std::path::PathBuf) {
        let dir = std::env::temp_dir().join(format!(
            "wp4a-admin-{}",
            crate::admin::auth::random_hex(8).unwrap()
        ));
        let options =
            Options::parse(&["--state-dir".into(), dir.to_str().unwrap().into()]).unwrap();
        let state = Arc::new(PersistentState::open(&dir).unwrap());
        let events = Arc::new(JsonEvents::new(Arc::new(|_| {})));
        let provision = WifiProvisioner::new(
            ProcessNetworkManagerCli {
                executable: "/bin/false".into(),
            },
            "wlan0".into(),
            "AA:BB:CC:DD:EE:FF",
            &dir,
        )
        .unwrap();
        (
            Arc::new(LinuxRelayAdmin::with_provisioner(options, state, events, provision).unwrap()),
            dir,
        )
    }
    struct UnusedPlatform;
    impl WirelessPlatform for UnusedPlatform {
        fn check_bluetooth_radio(&self) -> Result<()> {
            unreachable!()
        }
        fn prepare_mfi(&self) -> Result<MfiResource> {
            unreachable!()
        }
        fn create_hotspot(&self) -> Result<Arc<dyn WirelessHotspotManager>> {
            unreachable!()
        }
        fn start_pairing(&self) -> Result<Option<Arc<dyn CloseResource>>> {
            unreachable!()
        }
        fn select_iphone(&self) -> Result<BondedDevice> {
            unreachable!()
        }
        fn create_bluetooth(&self, _: &BondedDevice) -> Result<Arc<dyn BluetoothConnection>> {
            unreachable!()
        }
        fn create_network(
            &self,
            _: WirelessHotspotInfo,
            _: Arc<dyn carplay_iap2::mfi::MfiAuthenticator>,
            _: NetworkCallbacks,
        ) -> Result<Arc<dyn NetworkAttachment>> {
            unreachable!()
        }
        fn run_bootstrap(
            &self,
            _: Arc<dyn BluetoothConnection>,
            _: Arc<dyn PendingNetwork>,
            _: Arc<dyn carplay_iap2::mfi::MfiAuthenticator>,
            _: BootstrapCallbacks,
        ) -> Result<()> {
            unreachable!()
        }
    }
    fn fake_controller(a: &LinuxRelayAdmin) -> Arc<LinuxCarPlayController> {
        let controller = Arc::new(LinuxCarPlayController::new(
            Arc::new(UnusedPlatform),
            a.events.clone(),
            Duration::from_secs(1),
        ));
        *a.controller.lock().unwrap() = Some(controller.clone());
        controller
    }
    /// An admin whose nmcli blocks until `release` exists in the state directory, with a saved
    /// Wi-Fi client profile the startup provisioning takes down.
    fn blocked_provisioning(mode: &str) -> (Arc<LinuxRelayAdmin>, std::path::PathBuf) {
        use std::os::unix::fs::PermissionsExt;
        let dir = std::env::temp_dir().join(format!(
            "wp4a-startup-{}",
            crate::admin::auth::random_hex(8).unwrap()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let nmcli = dir.join("nmcli");
        std::fs::write(
            &nmcli,
            format!(
                "#!/bin/sh\nwhile [ ! -e '{}' ]; do sleep 0.01; done\n",
                dir.join("release").display()
            ),
        )
        .unwrap();
        std::fs::set_permissions(&nmcli, std::fs::Permissions::from_mode(0o755)).unwrap();
        std::fs::write(
            dir.join("wifi-owned.json"),
            r#"{"ap":null,"clients":[{"uuid":"00000000-0000-4000-8000-000000000001","ssid":"Home"}]}"#,
        )
        .unwrap();
        Settings {
            network_mode: mode.into(),
            shared_wifi_networks: vec![SharedWifiNetwork {
                ssid: "Home".into(),
                password: "password".into(),
            }],
            ..Default::default()
        }
        .save(&dir)
        .unwrap();
        let options =
            Options::parse(&["--state-dir".into(), dir.to_str().unwrap().into()]).unwrap();
        let state = Arc::new(PersistentState::open(&dir).unwrap());
        let events = Arc::new(JsonEvents::new(Arc::new(|_| {})));
        let provision = WifiProvisioner::new(
            ProcessNetworkManagerCli { executable: nmcli },
            "wlan0".into(),
            "AA:BB:CC:DD:EE:FF",
            &dir,
        )
        .unwrap();
        (
            Arc::new(LinuxRelayAdmin::with_provisioner(options, state, events, provision).unwrap()),
            dir,
        )
    }
    #[test]
    fn startup_starts_bluetooth_before_the_wifi_provisioning_and_holds_only_the_ap() {
        let (a, dir) = blocked_provisioning("device-hotspot");
        let startup = {
            let a = a.clone();
            std::thread::spawn(move || {
                let _lifecycle = a.lifecycle.lock().unwrap();
                a.operation(Operation::Startup)
            })
        };
        // The controller, and with it Bluetooth, runs while nmcli still blocks the provisioning.
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while a.controller.lock().unwrap().is_none() {
            assert!(
                std::time::Instant::now() < deadline,
                "controller not started"
            );
            std::thread::sleep(Duration::from_millis(5));
        }
        std::thread::sleep(Duration::from_millis(50));
        assert!(!startup.is_finished());
        // Its AP may not start yet.
        assert!(!a.provisioning.wait(Duration::ZERO));
        std::fs::write(dir.join("release"), "").unwrap();
        startup.join().unwrap().unwrap();
        assert!(a.provisioning.wait(Duration::ZERO));
        a.shutdown();
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn in_shared_wifi_mode_the_controller_follows_the_startup_provisioning() {
        // There the provisioning brings the network up and decides whether CarPlay runs.
        let (a, dir) = blocked_provisioning("shared-wifi");
        let startup = {
            let a = a.clone();
            std::thread::spawn(move || {
                let _lifecycle = a.lifecycle.lock().unwrap();
                let _ = a.operation(Operation::Startup);
            })
        };
        std::thread::sleep(Duration::from_millis(100));
        assert!(!startup.is_finished());
        assert!(a.controller.lock().unwrap().is_none());
        assert!(a.provisioning.wait(Duration::ZERO));
        std::fs::write(dir.join("release"), "").unwrap();
        startup.join().unwrap();
        assert!(a.controller.lock().unwrap().is_none());
        a.shutdown();
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn a_finished_operation_ends_the_wait_for_the_next_tick() {
        let (a, dir) = fake();
        let started = std::time::Instant::now();
        a.wait_for_tick(Duration::from_millis(30));
        assert!(started.elapsed() >= Duration::from_millis(30));
        // The operation worker finishes the startup network initialisation: the controller
        // starts on the tick that follows at once.
        a.wake_tick();
        let started = std::time::Instant::now();
        a.wait_for_tick(Duration::from_secs(5));
        assert!(started.elapsed() < Duration::from_secs(1));
        a.shutdown();
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn receiver_restart_closes_only_the_carplay_controller() {
        let (a, dir) = fake();
        let controller = fake_controller(&a);
        {
            let mut input = a.input.state.lock().unwrap();
            input.desired_fps = 45;
        }
        a.input
            .control(&oxp_wire::Message::Json(
                serde_json::json!({"type":"restart"}),
            ))
            .unwrap();
        assert!(a
            .input
            .apply_requested_restart(|| a.stop_controller_for_restart(true)));
        assert!(a.controller.lock().unwrap().is_none());
        assert!(a.desired.load(Ordering::Acquire));
        assert_eq!(a.input.configure_frame_rate(), 45);
        assert!(!a
            .input
            .apply_requested_restart(|| panic!("duplicate controller stop")));
        drop(controller);
        let _ = std::fs::remove_dir_all(dir);
    }
    #[test]
    fn settings_restart_closes_retained_hotspot() {
        let (a, dir) = fake();
        let hotspot = Arc::new(crate::hotspot_lifetime::tests::Fake::default());
        let lease = a.hotspot_lifetime.create(|| hotspot.clone()).unwrap();
        lease.start(Duration::from_secs(1)).unwrap();
        a.hotspot_lifetime.keep_for_restart();
        lease.close().unwrap();
        assert_eq!(hotspot.closes.load(Ordering::SeqCst), 0);
        // A network settings change must close even an AP retained between controllers.
        let _ = a.operation(Operation::Network {
            mode_changed: true,
            reconnect: false,
        });
        assert_eq!(hotspot.closes.load(Ordering::SeqCst), 1);
        let _ = std::fs::remove_dir_all(dir);
    }
    fn settle_follow(a: &LinuxRelayAdmin) {
        a.follow.lock().unwrap().changed = Some(std::time::Instant::now() - Duration::from_secs(1));
        a.reconcile_carlink_network().unwrap();
    }
    #[test]
    fn carlink_override_restarts_controller_is_transient_and_debounces_departure() {
        use std::os::unix::fs::PermissionsExt;
        for mode in ["device-hotspot", "shared-wifi"] {
            let (mut a, dir) = fake();
            let captured = Arc::new(Mutex::new(Vec::new()));
            let copy = captured.clone();
            Arc::get_mut(&mut a).unwrap().events =
                Arc::new(JsonEvents::new(Arc::new(move |line| {
                    copy.lock().unwrap().push(line)
                })));
            let script = dir.join("nmcli-fake");
            std::fs::write(&script, r#"#!/bin/sh
printf '%s\n' "$*" >> "$(dirname "$0")/nmcli-calls"
case "$*" in
  *GENERAL.CON-UUID*) echo 00000000-0000-4000-8000-000000000001 ;;
  *'connection add'*) echo "Connection 'Home' (00000000-0000-4000-8000-000000000002) successfully added." ;;
  *802-11-wireless-security.psk*) echo private-car-secret ;;
  *802-11-wireless.ssid*) echo DIRECT-Car ;;
  *SSID,SIGNAL,SECURITY,IN-USE*) echo 'Home:82:WPA2:*' ;;
esac
"#).unwrap();
            std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o700)).unwrap();
            *a.provision.lock().unwrap() = WifiProvisioner::new(
                ProcessNetworkManagerCli { executable: script },
                "wlan0".into(),
                "AA:BB:CC:DD:EE:FF",
                &dir,
            )
            .unwrap();
            {
                let mut settings = a.settings.lock().unwrap();
                settings.network_mode = mode.into();
                settings.shared_wifi_networks = vec![SharedWifiNetwork {
                    ssid: "Home".into(),
                    password: "home-password".into(),
                }];
                settings.save(&dir).unwrap();
            }
            let original = std::fs::read(dir.join("settings.json")).unwrap();
            let first = fake_controller(&a);
            let shell = a.clone();
            let closing = first.clone();
            *a.worker.lock().unwrap() = Some(std::thread::spawn(move || {
                let deadline = std::time::Instant::now() + Duration::from_secs(5);
                while closing.stage() != "Stopped" {
                    assert!(std::time::Instant::now() < deadline);
                    std::thread::yield_now();
                }
                assert_eq!(
                    shell
                        .carplay_options()
                        .unwrap()
                        .settings
                        .shared_wifi_networks[0]
                        .ssid,
                    "DIRECT-Car"
                );
            }));
            // Receiver negotiation can request a restart while Carlink joins.
            a.input.state.lock().unwrap().desired_fps = 30;
            a.input
                .control(&oxp_wire::Message::Json(
                    serde_json::json!({"type":"restart"}),
                ))
                .unwrap();
            let hotspot = Arc::new(crate::hotspot_lifetime::tests::Fake::default());
            let lease = a.hotspot_lifetime.create(|| hotspot.clone()).unwrap();
            lease.start(Duration::from_secs(1)).unwrap();
            let joined = serde_json::json!({"type":"link","state":"joined","network":{"ssid":"DIRECT-Car","interface":"wlan0"}});
            a.follow.lock().unwrap().observe(&joined, "wlan1");
            settle_follow(&a);
            assert!(a.network_override.lock().unwrap().is_none());
            a.follow.lock().unwrap().observe(&joined, "wlan0");
            a.reconcile_carlink_network().unwrap();
            assert_eq!(first.stage(), "Idle");
            settle_follow(&a);
            assert_eq!(first.stage(), "Stopped");
            assert_eq!(hotspot.closes.load(Ordering::SeqCst), 1);
            // The replacement snapshots pending receiver changes before its worker runs.
            assert_eq!(a.input.configure_session().0, 30);
            let options = a.carplay_options().unwrap();
            assert_eq!(options.settings.network_mode, "shared-wifi");
            assert_eq!(options.settings.shared_wifi_networks[0].ssid, "DIRECT-Car");
            assert_eq!(
                options.settings.shared_wifi_networks[0].password,
                "private-car-secret"
            );
            assert!(a.status().network.following_carlink);
            let second = fake_controller(&a);
            a.tick().unwrap();
            assert_eq!(second.stage(), "Idle");
            a.follow.lock().unwrap().observe(&joined, "wlan0");
            settle_follow(&a);
            a.tick().unwrap();
            assert_eq!(second.stage(), "Idle");
            assert_eq!(hotspot.closes.load(Ordering::SeqCst), 1);
            let lost = serde_json::json!({"type":"link","state":"lost"});
            a.follow.lock().unwrap().observe(&lost, "wlan0");
            a.reconcile_carlink_network().unwrap();
            a.follow.lock().unwrap().observe(&joined, "wlan0");
            settle_follow(&a);
            assert_eq!(second.stage(), "Idle");
            // Initialize cannot bring the home profile up during the override.
            let calls = std::fs::read_to_string(dir.join("nmcli-calls")).unwrap();
            a.operation(Operation::Initialize).unwrap();
            assert_eq!(
                calls,
                std::fs::read_to_string(dir.join("nmcli-calls")).unwrap()
            );
            assert!(a.provision.lock().unwrap().start().is_err());
            a.follow.lock().unwrap().observe(
                &serde_json::json!({"type":"state","state":"Ended"}),
                "wlan0",
            );
            settle_follow(&a);
            assert_eq!(second.stage(), "Stopped");
            assert!(a.network_override.lock().unwrap().is_none());
            assert!(!a.status().network.following_carlink);
            assert_eq!(a.carplay_options().unwrap().settings.network_mode, mode);
            if mode == "shared-wifi" {
                assert!(std::fs::read_to_string(dir.join("nmcli-calls"))
                    .unwrap()
                    .contains("connection up uuid"));
            }
            assert_eq!(original, std::fs::read(dir.join("settings.json")).unwrap());
            assert!(!serde_json::to_string(&a.status())
                .unwrap()
                .contains("private-car-secret"));
            assert!(!captured
                .lock()
                .unwrap()
                .join(" ")
                .contains("private-car-secret"));
            std::fs::remove_dir_all(dir).unwrap();
        }
    }
    #[test]
    fn credential_failure_holds_radio_until_link_ends_without_starting_hotspot() {
        let (a, dir) = fake();
        let controller = fake_controller(&a);
        a.follow.lock().unwrap().observe(&serde_json::json!({"type":"link","state":"joined","network":{"ssid":"DIRECT-Car","interface":"wlan0"}}), "wlan0");
        a.follow.lock().unwrap().changed = Some(std::time::Instant::now() - Duration::from_secs(1));
        let error = a.reconcile_carlink_network().unwrap_err();
        assert_eq!(
            error.to_string(),
            "Could not read the Carlink network credentials"
        );
        assert_eq!(controller.stage(), "Stopped");
        assert!(a.provision.lock().unwrap().stop().is_err());
        a.tick().unwrap();
        assert!(a.controller.lock().unwrap().is_none());
        a.follow
            .lock()
            .unwrap()
            .observe(&serde_json::json!({"type":"receiver-stopped"}), "wlan0");
        settle_follow(&a);
        a.provision.lock().unwrap().stop().unwrap();
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn picture_settings_live_outside_rollback_settings() {
        let (a, dir) = fake();
        assert!(a
            .settings()
            .schema
            .iter()
            .any(|s| s.id == "picture" && s.fields.len() == 3));
        let update =
            serde_json::json!({"resolution":"native","screenWidth":250,"videoCodec":"h264"});
        assert!(matches!(
            a.update(update.as_object().unwrap().clone()),
            AdminUpdate::AppliedNow
        ));
        assert_eq!(a.settings().values["resolution"], "native");
        assert_eq!(a.input.display_settings().screen_width, 250);
        let stored: serde_json::Value =
            serde_json::from_slice(&std::fs::read(dir.join("settings.json")).unwrap()).unwrap();
        assert!(stored.get("resolution").is_none());
        Settings::load(&dir).unwrap();
        assert!(matches!(
            a.update(
                serde_json::json!({"screenWidth":150})
                    .as_object()
                    .unwrap()
                    .clone()
            ),
            AdminUpdate::Errors(_)
        ));
        assert_eq!(a.input.display_settings().screen_width, 250);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn network_values_are_secret_free_and_updates_preserve_omitted_passwords() {
        let (a, dir) = fake();
        a.settings.lock().unwrap().shared_wifi_networks = vec![SharedWifiNetwork {
            ssid: "Home".into(),
            password: "private-secret".into(),
        }];
        let settings = a.settings();
        let value = serde_json::to_value(settings).unwrap();
        assert!(!value.to_string().contains("private-secret"));
        assert_eq!(
            value["values"]["sharedWifiNetworks"][0],
            serde_json::json!({"ssid":"Home","hasPassword":true,"current":false})
        );
        let schema = settings_schema();
        let field = schema
            .iter()
            .flat_map(|s| &s.fields)
            .find(|f| f.key == "sharedWifiNetworks")
            .unwrap();
        assert_eq!(field.field_type, "networks");
        assert!(field.visible_when.is_none());
        let current = a.settings.lock().unwrap().clone();
        let next = update_settings(&current, serde_json::json!({"sharedWifiNetworks":[{"ssid":"Home"},{"ssid":"Open","password":""}]}).as_object().unwrap().clone()).unwrap();
        assert_eq!(next.shared_wifi_networks[0].password, "private-secret");
        assert!(next.shared_wifi_networks[1].password.is_empty());
        for list in [
            serde_json::json!([{"ssid":"New"}]),
            serde_json::json!([{"ssid":"Home","password":null}]),
            serde_json::json!([{"ssid":"Home","password":"short"}]),
        ] {
            let error = update_settings(
                &current,
                serde_json::json!({"sharedWifiNetworks":list})
                    .as_object()
                    .unwrap()
                    .clone(),
            )
            .unwrap_err();
            assert_eq!(error["sharedWifiNetworks"], "error.networks");
        }
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn mode_switch_is_saved_now_but_radio_work_waits_for_response() {
        let (a, dir) = fake();
        let values = || {
            serde_json::json!({"networkMode":"shared-wifi"})
                .as_object()
                .unwrap()
                .clone()
        };
        match a.update(values()) {
            AdminUpdate::Errors(errors) => assert_eq!(errors["networkMode"], "error.noSavedWifi"),
            _ => panic!("empty shared Wi-Fi accepted"),
        }
        a.settings
            .lock()
            .unwrap()
            .shared_wifi_networks
            .push(SharedWifiNetwork {
                ssid: "Home".into(),
                password: "password".into(),
            });
        assert!(matches!(a.update(values()), AdminUpdate::AppliedNow));
        assert_eq!(Settings::load(&dir).unwrap().network_mode, "shared-wifi");
        let guard = a.operation_rx.lock().unwrap();
        let rx = guard.as_ref().unwrap();
        assert!(rx.try_recv().is_err());
        a.response_sent();
        assert!(matches!(
            rx.try_recv().unwrap(),
            Operation::Network {
                mode_changed: true,
                ..
            }
        ));
        assert!(matches!(
            a.update(
                serde_json::json!({"hotspotChannel":6})
                    .as_object()
                    .unwrap()
                    .clone()
            ),
            AdminUpdate::NextStart
        ));
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn wifi_put_uses_saved_password_only_and_defers_radio_changes() {
        let (a, dir) = fake();
        a.settings
            .lock()
            .unwrap()
            .shared_wifi_networks
            .push(SharedWifiNetwork {
                ssid: "Home".into(),
                password: "private-secret".into(),
            });
        let mut auth = AdminAuth::new(Box::new(FileAuthStore(dir.join("auth.json")))).unwrap();
        let token = auth
            .login("127.0.0.1".parse().unwrap(), "openxplay", 100)
            .ok()
            .unwrap();
        let server = AdminServer::new(
            a.clone(),
            auth,
            AdminServerConfig::new(dir.clone(), dir.clone(), vec![]),
        );
        let mut request = Request {
            method: "PUT".into(),
            path: "/api/v0/wifi".into(),
            headers: BTreeMap::from([
                ("content-type".into(), "application/json".into()),
                ("x-openxplay-admin".into(), "1".into()),
                ("cookie".into(), format!("oxp_admin={token}")),
            ]),
            body: br#"{"ssid":"Home"}"#.to_vec(),
            peer: "127.0.0.1".parse().unwrap(),
        };
        assert_eq!(server.route(&request, 100).status, 200);
        let guard = a.operation_rx.lock().unwrap();
        let rx = guard.as_ref().unwrap();
        assert!(rx.try_recv().is_err());
        a.response_sent();
        assert!(
            matches!(rx.try_recv().unwrap(), Operation::Join(ssid, password) if ssid == "Home" && password == "private-secret")
        );
        request.body = br#"{"ssid":"New"}"#.to_vec();
        let response = server.route(&request, 102);
        assert!(String::from_utf8(response.body)
            .unwrap()
            .contains("error.networks"));
        request.body = br#"{"ssid":"Home","password":null}"#.to_vec();
        assert_eq!(server.route(&request, 104).status, 422);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn linux_status_has_hotspot_credentials_and_wifi_control() {
        let (a, dir) = fake();
        let json = serde_json::to_value(a.status()).unwrap();
        assert_eq!(json["wifiControl"], true);
        assert_eq!(json["hotspot"]["active"], false);
        assert!(json["hotspot"]["ip"].is_null());
        let (ssid, password) = crate::linux_hotspot::hotspot_credentials(&dir).unwrap();
        assert_eq!(json["hotspot"]["ssid"], ssid);
        assert_eq!(json["hotspot"]["password"], password);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn hotspot_api_requires_auth_validates_and_defers_radio_change() {
        let (a, dir) = fake();
        let mut auth = AdminAuth::new(Box::new(FileAuthStore(dir.join("auth.json")))).unwrap();
        let token = auth
            .login("127.0.0.1".parse().unwrap(), "openxplay", 100)
            .ok()
            .unwrap();
        let server = AdminServer::new(
            a.clone(),
            auth,
            AdminServerConfig::new(dir.clone(), dir.clone(), vec![]),
        );
        let mut request = Request {
            method: "PUT".into(),
            path: "/api/v0/hotspot".into(),
            headers: BTreeMap::from([
                ("content-type".into(), "application/json".into()),
                ("x-openxplay-admin".into(), "1".into()),
            ]),
            body: br#"{"ssid":"New Relay","password":"newpass123"}"#.to_vec(),
            peer: "127.0.0.1".parse().unwrap(),
        };
        assert_eq!(server.route(&request, 100).status, 401);
        request.path = "/api/v0/status".into();
        request.method = "GET".into();
        let response = server.route(&request, 100);
        assert_eq!(response.status, 401);
        assert!(!String::from_utf8(response.body)
            .unwrap()
            .contains("password\":"));
        request.path = "/api/v0/hotspot".into();
        request.method = "PUT".into();
        request
            .headers
            .insert("cookie".into(), format!("oxp_admin={token}"));
        request.headers.remove("x-openxplay-admin");
        assert_eq!(server.route(&request, 100).status, 403);
        request
            .headers
            .insert("x-openxplay-admin".into(), "1".into());
        for body in [
            serde_json::json!({"ssid":"", "password":"12345678"}),
            serde_json::json!({"ssid":"Relay", "password":"short"}),
            serde_json::json!({"ssid":null,"password":"12345678"}),
        ] {
            request.body = serde_json::to_vec(&body).unwrap();
            assert_eq!(server.route(&request, 100).status, 422);
        }
        request.body = br#"{"ssid":"New Relay","password":"newpass123"}"#.to_vec();
        assert_eq!(server.route(&request, 100).status, 200);
        assert_eq!(
            crate::linux_hotspot::hotspot_credentials(&dir).unwrap(),
            ("New Relay".into(), "newpass123".into())
        );
        let rx = a.operation_rx.lock().unwrap().take().unwrap();
        assert!(rx.try_recv().is_err());
        assert!(a.status().notices.contains(&"notice.nextStart".into()));
        a.response_sent();
        let hotspot = Arc::new(crate::hotspot_lifetime::tests::Fake::default());
        let lease = a.hotspot_lifetime.create(|| hotspot.clone()).unwrap();
        lease.start(Duration::from_secs(1)).unwrap();
        let op = rx.try_recv().unwrap();
        assert!(matches!(op, Operation::Hotspot));
        a.operation(op).unwrap();
        assert_eq!(hotspot.closes.load(Ordering::SeqCst), 1);
        assert!(!a.apply_pending.load(Ordering::Acquire));
        assert!(a.status().hotspot.unwrap().has_password);
        // In shared mode saving credentials leaves the current controller and AP alone.
        a.settings.lock().unwrap().network_mode = "shared-wifi".into();
        let controller = fake_controller(&a);
        a.hotspot_update("Later", "laterpass1").unwrap();
        a.response_sent();
        a.operation(rx.try_recv().unwrap()).unwrap();
        assert!(Arc::ptr_eq(
            a.controller.lock().unwrap().as_ref().unwrap(),
            &controller
        ));
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn plugins_api_and_output_only_apply() {
        let (mut a, dir) = fake();
        let root = dir.join("packages");
        crate::plugins::tests::package(
            &root,
            "org.openxplay.pet",
            "(MIT OR Apache-2.0) AND CC-BY-NC-SA-4.0",
        );
        let outputs = Arc::new(OutputFake::default());
        let admin = Arc::get_mut(&mut a).unwrap();
        admin.options.plugins_root = root;
        admin.child_factory = outputs.clone();
        a.start_supervisor().unwrap();
        let wait = |count| {
            let deadline = std::time::Instant::now() + Duration::from_secs(2);
            while outputs.launches.lock().unwrap().len() < count {
                assert!(std::time::Instant::now() < deadline);
                std::thread::sleep(Duration::from_millis(5));
            }
        };
        wait(1);
        let controller = fake_controller(&a);
        let hotspot = Arc::new(crate::hotspot_lifetime::tests::Fake::default());
        let lease = a.hotspot_lifetime.create(|| hotspot.clone()).unwrap();
        lease.start(Duration::from_secs(1)).unwrap();
        let contract = serde_json::to_value(a.settings()).unwrap();
        assert_eq!(contract["values"]["plugins"], false);
        assert_eq!(contract["values"]["pluginsEnabled"], serde_json::json!([]));
        assert_eq!(contract["installedPlugins"][0]["name"]["zh-CN"], "宠物");
        assert_eq!(contract["installedPlugins"][0]["nonCommercialAssets"], true);
        assert!(contract["schema"]
            .as_array()
            .unwrap()
            .iter()
            .any(|s| s["id"] == "plugins"));
        for invalid in [
            serde_json::json!(["org.missing"]),
            serde_json::json!(["../escape"]),
            serde_json::json!(["org.openxplay.pet", "org.openxplay.pet"]),
            serde_json::json!("org.openxplay.pet"),
        ] {
            assert!(matches!(
                a.update(
                    serde_json::json!({"pluginsEnabled":invalid})
                        .as_object()
                        .unwrap()
                        .clone()
                ),
                AdminUpdate::Errors(_)
            ));
        }
        let rx = a.operation_rx.lock().unwrap().take().unwrap();
        for (index, patch) in [
            serde_json::json!({"pluginsEnabled":["org.openxplay.pet"]}),
            serde_json::json!({"plugins":true}),
            serde_json::json!({"plugins":false}),
            serde_json::json!({"pluginsEnabled":[]}),
        ]
        .into_iter()
        .enumerate()
        {
            assert!(matches!(
                a.update(patch.as_object().unwrap().clone()),
                AdminUpdate::AppliedNow
            ));
            a.response_sent();
            let operation = rx.try_recv().unwrap();
            assert!(matches!(operation, Operation::Outputs(None)));
            a.operation(operation).unwrap();
            wait(index + 2);
            assert_eq!(a.input.plugins_enabled(), index == 1);
            assert!(Arc::ptr_eq(
                a.controller.lock().unwrap().as_ref().unwrap(),
                &controller
            ));
            assert_eq!(hotspot.closes.load(Ordering::SeqCst), 0);
            assert!(!a.restart_required.load(Ordering::Acquire));
        }
        let launches = outputs.launches.lock().unwrap();
        assert_eq!(launches[0], launches[1]);
        assert!(launches[2].iter().any(|arg| arg == "--plugins-enabled"));
        assert_eq!(launches[0], launches[3]);
        assert_eq!(launches[0], launches[4]);
        assert_eq!(outputs.stops.load(Ordering::SeqCst), 4);
        drop(launches);
        a.shutdown();
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[derive(Default)]
    struct OutputFake {
        launches: Mutex<Vec<Vec<std::ffi::OsString>>>,
        stops: Arc<std::sync::atomic::AtomicUsize>,
    }
    struct OutputChild(Arc<std::sync::atomic::AtomicUsize>);
    impl RelayChild for OutputChild {
        fn exited(&mut self) -> std::io::Result<bool> {
            Ok(false)
        }
        fn stop(&mut self) -> std::io::Result<()> {
            self.0.fetch_add(1, Ordering::SeqCst);
            Ok(())
        }
    }
    impl ChildFactory for OutputFake {
        fn spawn(
            &self,
            _: &std::path::Path,
            args: &[std::ffi::OsString],
            _: EventSink,
        ) -> std::io::Result<Box<dyn RelayChild>> {
            self.launches.lock().unwrap().push(args.to_vec());
            Ok(Box::new(OutputChild(self.stops.clone())))
        }
    }
    #[test]
    fn output_apply_replaces_only_child_and_retains_controller_and_hotspot() {
        let (mut a, dir) = fake();
        let outputs = Arc::new(OutputFake::default());
        Arc::get_mut(&mut a).unwrap().child_factory = outputs.clone();
        a.start_supervisor().unwrap();
        let wait_for_launches = |count| {
            let deadline = std::time::Instant::now() + Duration::from_secs(2);
            while outputs.launches.lock().unwrap().len() < count {
                assert!(
                    std::time::Instant::now() < deadline,
                    "output did not launch"
                );
                std::thread::sleep(Duration::from_millis(5));
            }
        };
        wait_for_launches(1);
        std::fs::create_dir_all(dir.join("carlink")).unwrap();
        std::fs::write(
            dir.join("carlink/trust.json"),
            serde_json::to_vec(
                &serde_json::json!({"peers":{"unit":format!("{}==", "A".repeat(86))}}),
            )
            .unwrap(),
        )
        .unwrap();
        let controller = fake_controller(&a);
        let hotspot = Arc::new(crate::hotspot_lifetime::tests::Fake::default());
        let lease = a.hotspot_lifetime.create(|| hotspot.clone()).unwrap();
        lease.start(Duration::from_secs(1)).unwrap();
        assert!(matches!(
            a.update(
                serde_json::json!({"output":"carlink"})
                    .as_object()
                    .unwrap()
                    .clone()
            ),
            AdminUpdate::AppliedNow
        ));
        let rx = a.operation_rx.lock().unwrap().take().unwrap();
        assert_eq!(outputs.launches.lock().unwrap().len(), 1);
        a.response_sent();
        a.operation(rx.try_recv().unwrap()).unwrap();
        wait_for_launches(2);
        assert_eq!(outputs.stops.load(Ordering::SeqCst), 1);
        let launches = outputs.launches.lock().unwrap();
        assert_eq!(launches.len(), 2);
        assert!(launches[0].iter().any(|arg| arg == "--web-port"));
        assert!(!launches[1].iter().any(|arg| arg == "--web-port"));
        assert!(Arc::ptr_eq(
            a.controller.lock().unwrap().as_ref().unwrap(),
            &controller
        ));
        assert_eq!(hotspot.closes.load(Ordering::SeqCst), 0);
        assert!(!a.apply_pending.load(Ordering::Acquire));
        drop(launches);
        a.shutdown();
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn outputs_apply_now_and_controller_changes_report_restart_required() {
        let (a, dir) = fake();
        let controller = fake_controller(&a);
        assert!(matches!(
            a.update(
                serde_json::json!({"output":"both"})
                    .as_object()
                    .unwrap()
                    .clone()
            ),
            AdminUpdate::AppliedNow
        ));
        assert!(a
            .pending
            .lock()
            .unwrap()
            .values()
            .any(|op| matches!(op, Operation::Outputs(None))));
        assert!(Arc::ptr_eq(
            a.controller.lock().unwrap().as_ref().unwrap(),
            &controller
        ));
        a.response_failed();
        assert!(a.status().notices.contains(&"notice.nextStart".into()));
        assert!(matches!(
            a.update(
                serde_json::json!({"rfcommChannel":3})
                    .as_object()
                    .unwrap()
                    .clone()
            ),
            AdminUpdate::NextStart
        ));
        assert!(matches!(
            a.update(
                serde_json::json!({"hotspotChannel":6})
                    .as_object()
                    .unwrap()
                    .clone()
            ),
            AdminUpdate::NextStart
        ));
        std::fs::remove_dir_all(dir).unwrap();
    }
    fn pin_request(a: &LinuxRelayAdmin) {
        a.carlink.lock().unwrap().apply_event(
            &serde_json::json!({"type":"pin-request","client":"unit"}),
            crate::admin::auth::monotonic_seconds(),
        );
    }
    #[test]
    fn connect_endpoint_validates_auth_csrf_digits_rate_and_optional_trusted_pin() {
        let (a, dir) = fake();
        a.settings.lock().unwrap().output = "both".into();
        let mut auth = AdminAuth::new(Box::new(FileAuthStore(dir.join("auth.json")))).unwrap();
        let token = auth
            .login("127.0.0.1".parse().unwrap(), "openxplay", 100)
            .ok()
            .unwrap();
        let server = AdminServer::new(
            a.clone(),
            auth,
            AdminServerConfig::new(dir.clone(), dir.clone(), vec![]),
        );
        let mut req = Request {
            method: "POST".into(),
            path: "/api/carlink/connect".into(),
            headers: BTreeMap::from([
                ("content-type".into(), "application/json".into()),
                ("x-openxplay-admin".into(), "1".into()),
            ]),
            body: br#"{"pin":"012345"}"#.to_vec(),
            peer: "127.0.0.1".parse().unwrap(),
        };
        assert_eq!(server.route(&req, 100).status, 401);
        req.headers
            .insert("cookie".into(), format!("oxp_admin={token}"));
        req.headers.remove("x-openxplay-admin");
        assert_eq!(server.route(&req, 100).status, 403);
        req.headers.insert("x-openxplay-admin".into(), "1".into());
        for (i, pin) in [
            serde_json::json!("123"),
            serde_json::json!("123456789"),
            serde_json::json!("12a4"),
            serde_json::json!("１２３４"),
            serde_json::json!("1234\n"),
            serde_json::json!(1234),
            serde_json::Value::Null,
        ]
        .into_iter()
        .enumerate()
        {
            req.body = serde_json::to_vec(&serde_json::json!({"pin":pin})).unwrap();
            assert_eq!(server.route(&req, 100 + i as u64 * 2).status, 422);
        }
        req.body = br#"{"pin":"012345"}"#.to_vec();
        assert_eq!(server.route(&req, 112).status, 429);
        assert_eq!(server.route(&req, 114).status, 200);
        assert!(!carlink_pin_path(&dir).exists());
        assert!(a.armed_pin.lock().unwrap().is_none());
        a.response_failed();
        assert!(a.pending.lock().unwrap().is_empty());
        assert_eq!(server.route(&req, 116).status, 200);
        a.response_sent();
        let rx = a.operation_rx.lock().unwrap().take().unwrap();
        let op = rx.recv_timeout(Duration::from_secs(1)).unwrap();
        if let Operation::ConnectCarlink(Some(pin)) = &op {
            assert_eq!(pin.pin, "012345");
            assert!(
                pin.expires.duration_since(std::time::Instant::now()) <= Duration::from_secs(110)
            );
        } else {
            panic!("missing armed PIN");
        }
        a.operation(op).unwrap();
        assert!(!carlink_pin_path(&dir).exists());
        assert!(!std::fs::read_to_string(dir.join("settings.json"))
            .unwrap_or_default()
            .contains("012345"));
        req.body = b"{}".to_vec();
        assert_eq!(server.route(&req, 118).status, 409);
        std::fs::create_dir_all(dir.join("carlink")).unwrap();
        std::fs::write(
            dir.join("carlink/trust.json"),
            serde_json::to_vec(
                &serde_json::json!({"peers":{"unit":format!("{}==", "A".repeat(86))}}),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(server.route(&req, 120).status, 200);
        a.response_failed();
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn prearmed_pin_is_immediate_single_use_private_and_absent_from_events() {
        use std::os::unix::fs::PermissionsExt;
        let (a, dir) = fake();
        let logs = Arc::new(Mutex::new(Vec::new()));
        let copy = logs.clone();
        let sink = carlink_event_sink(
            a.carlink.clone(),
            a.armed_pin.clone(),
            dir.clone(),
            Arc::new(JsonEvents::new(Arc::new(move |line| {
                copy.lock().unwrap().push(line)
            }))),
        );
        *a.armed_pin.lock().unwrap() = Some(ArmedPin {
            pin: "098765".into(),
            expires: std::time::Instant::now() + Duration::from_secs(110),
        });
        sink(serde_json::json!({"type":"pin-request","client":"unit"}));
        assert_eq!(std::fs::read(carlink_pin_path(&dir)).unwrap(), b"098765");
        assert_eq!(
            std::fs::metadata(carlink_pin_path(&dir))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
        assert!(a.armed_pin.lock().unwrap().is_none());
        assert!(!a.carlink.lock().unwrap().pin_requested);
        remove_carlink_pin(&dir).unwrap();
        sink(serde_json::json!({"type":"pin-request","client":"unit"}));
        assert!(a.carlink.lock().unwrap().pin_requested);
        assert!(!carlink_pin_path(&dir).exists());
        assert!(!logs.lock().unwrap().join(" ").contains("098765"));
        assert!(!serde_json::to_string(&*a.carlink.lock().unwrap())
            .unwrap()
            .contains("098765"));
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn expired_and_completed_attempts_clear_prearmed_pin() {
        let (a, dir) = fake();
        let mut status = AdminCarlink::default();
        let mut armed = Some(ArmedPin {
            pin: "1234".into(),
            expires: std::time::Instant::now(),
        });
        apply_carlink_event(
            &mut status,
            &mut armed,
            &dir,
            &serde_json::json!({"type":"pin-request"}),
        );
        assert!(armed.is_none());
        assert!(status.pin_requested);
        assert!(!carlink_pin_path(&dir).exists());
        for event in [
            serde_json::json!({"type":"link","state":"lost","reason":"failed"}),
            serde_json::json!({"type":"state","state":"Playing"}),
            serde_json::json!({"type":"error","message":"failed"}),
        ] {
            armed = Some(ArmedPin {
                pin: "1234".into(),
                expires: std::time::Instant::now() + Duration::from_secs(110),
            });
            apply_carlink_event(&mut status, &mut armed, &dir, &event);
            assert!(armed.is_none());
        }
        status.apply_event(&serde_json::json!({"type":"carlink-unpaired"}), 200);
        assert_eq!(status.state, "unpaired");
        assert_eq!(status.detail, "carlink.unpairedHint");
        assert_eq!(status.last_error.as_deref(), Some("failed"));
        assert!(!status.pin_requested);
        let expired = Operation::ConnectCarlink(Some(ArmedPin {
            pin: "1234".into(),
            expires: std::time::Instant::now(),
        }));
        assert!(a.operation(expired).is_err());
        assert!(a.armed_pin.lock().unwrap().is_none());
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn carlink_schema_and_empty_address_update() {
        let schema = settings_schema();
        let fields: Vec<_> = schema.iter().flat_map(|s| &s.fields).collect();
        for key in [
            "output",
            "carlinkLink",
            "carlinkAddress",
            "carlinkHeadUnit",
            "carlinkLocal",
        ] {
            assert!(fields.iter().any(|f| f.key == key && f.hint_key.is_some()));
        }
        for key in ["carlinkHeadUnit", "carlinkLocal"] {
            let condition = fields
                .iter()
                .find(|f| f.key == key)
                .unwrap()
                .visible_when
                .as_ref()
                .unwrap();
            assert_eq!(condition.key, "carlinkLink");
            assert_eq!(condition.equals, vec![serde_json::json!("static")]);
        }
        let current = Settings {
            carlink_address: Some("AA:BB:CC:DD:EE:FF".into()),
            ..Default::default()
        };
        let next = update_settings(
            &current,
            serde_json::json!({"carlinkAddress":""})
                .as_object()
                .unwrap()
                .clone(),
        )
        .unwrap();
        assert!(next.carlink_address.is_none());
    }
    #[test]
    fn carlink_pin_endpoint_checks_auth_csrf_request_format_rate_and_atomic_mode() {
        use std::os::unix::fs::{MetadataExt, PermissionsExt};
        let (a, dir) = fake();
        let mut auth = AdminAuth::new(Box::new(FileAuthStore(dir.join("auth.json")))).unwrap();
        let token = auth
            .login("127.0.0.1".parse().unwrap(), "openxplay", 100)
            .ok()
            .unwrap();
        let server = AdminServer::new(
            a.clone(),
            auth,
            AdminServerConfig::new(dir.clone(), dir.clone(), vec![]),
        );
        let mut request = Request {
            method: "POST".into(),
            path: "/api/carlink/pin".into(),
            headers: BTreeMap::from([
                ("content-type".into(), "application/json".into()),
                ("x-openxplay-admin".into(), "1".into()),
            ]),
            body: br#"{"pin":"1234"}"#.to_vec(),
            peer: "127.0.0.1".parse().unwrap(),
        };
        assert_eq!(server.route(&request, 100).status, 401);
        request
            .headers
            .insert("cookie".into(), format!("oxp_admin={token}"));
        request.headers.remove("x-openxplay-admin");
        assert_eq!(server.route(&request, 100).status, 403);
        request
            .headers
            .insert("x-openxplay-admin".into(), "1".into());
        assert_eq!(server.route(&request, 100).status, 409);
        assert!(!carlink_pin_path(&dir).exists());
        pin_request(&a);
        for (i, pin) in ["123", "123456789", "12a4", "１２３４", "1234\n"]
            .iter()
            .enumerate()
        {
            request.body = serde_json::to_vec(&serde_json::json!({"pin":pin})).unwrap();
            assert_eq!(server.route(&request, 102 + i as u64 * 2).status, 422);
        }
        request.body = br#"{"pin":"012345"}"#.to_vec();
        assert_eq!(server.route(&request, 110).status, 429);
        let path = carlink_pin_path(&dir);
        crate::platform::private_state_file(&path, b"old").unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();
        let old = std::fs::File::open(&path).unwrap();
        assert_eq!(server.route(&request, 112).status, 200);
        assert_eq!(std::fs::read(&path).unwrap(), b"012345");
        let metadata = std::fs::metadata(&path).unwrap();
        assert_eq!(metadata.permissions().mode() & 0o777, 0o600);
        assert_ne!(metadata.ino(), old.metadata().unwrap().ino());
        assert_eq!(
            std::fs::read_dir(path.parent().unwrap()).unwrap().count(),
            1
        );
        assert!(!a.carlink.lock().unwrap().pin_requested);
        assert_eq!(server.route(&request, 114).status, 409);
        pin_request(&a);
        a.carlink.lock().unwrap().pin_expiry = Some(1);
        assert_eq!(server.route(&request, 116).status, 409);
        pin_request(&a);
        a.busy.store(true, Ordering::Release);
        assert_eq!(server.route(&request, 118).status, 409);
        drop(server);
        drop(a);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn forget_carlink_is_deferred_and_clears_trust_and_pin_but_keeps_identity() {
        let (a, dir) = fake();
        let identity = a.state.identity().unwrap().pairing_id.clone();
        let trust = dir.join("carlink/trust/nested");
        std::fs::create_dir_all(&trust).unwrap();
        std::fs::write(trust.join("unit"), "trusted").unwrap();
        crate::platform::private_state_file(&carlink_pin_path(&dir), b"1234").unwrap();
        a.settings.lock().unwrap().carlink_address = Some("AA:BB:CC:DD:EE:FF".into());
        pin_request(&a);
        a.action("forget-carlink").unwrap();
        assert!(trust.exists());
        a.response_sent();
        let op = a
            .operation_rx
            .lock()
            .unwrap()
            .as_ref()
            .unwrap()
            .try_recv()
            .unwrap();
        assert!(matches!(op, Operation::ForgetCarlink));
        a.operation(op).unwrap();
        assert_eq!(std::fs::read_dir(dir.join("carlink")).unwrap().count(), 0);
        assert!(Settings::load(&dir).unwrap().carlink_address.is_none());
        assert!(!a.carlink.lock().unwrap().pin_requested);
        assert_eq!(a.state.identity().unwrap().pairing_id, identity);
        drop(a);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn trust_cleanup_keeps_pin_path_separate() {
        let (a, dir) = fake();
        crate::platform::private_state_file(&carlink_pin_path(&dir), b"1234").unwrap();
        std::fs::write(dir.join("carlink/trust.json"), "trust").unwrap();
        clear_carlink_trust(&dir).unwrap();
        assert!(carlink_pin_path(&dir).exists());
        assert!(!dir.join("carlink/trust.json").exists());
        drop(a);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn wifi_join_waits_for_response_flush() {
        let (a, dir) = fake();
        a.wifi_join("Car", Some("password")).unwrap();
        assert!(a
            .operation_rx
            .lock()
            .unwrap()
            .as_ref()
            .unwrap()
            .try_recv()
            .is_err());
        a.response_sent();
        let op = a
            .operation_rx
            .lock()
            .unwrap()
            .as_ref()
            .unwrap()
            .try_recv()
            .unwrap();
        assert!(matches!(op,Operation::Join(ssid,password)if ssid=="Car"&&password=="password"));
        drop(a);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn failed_response_discards_deferred_join() {
        let (a, dir) = fake();
        a.wifi_join("Car", Some("password")).unwrap();
        a.response_failed();
        a.response_sent();
        assert!(a
            .operation_rx
            .lock()
            .unwrap()
            .as_ref()
            .unwrap()
            .try_recv()
            .is_err());
        drop(a);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn unrelated_response_cannot_release_join() {
        let (a, dir) = fake();
        a.wifi_join("Car", Some("password")).unwrap();
        let other = a.clone();
        std::thread::spawn(move || other.response_sent())
            .join()
            .unwrap();
        assert!(a
            .operation_rx
            .lock()
            .unwrap()
            .as_ref()
            .unwrap()
            .try_recv()
            .is_err());
        a.response_sent();
        assert!(matches!(
            a.operation_rx
                .lock()
                .unwrap()
                .as_ref()
                .unwrap()
                .try_recv()
                .unwrap(),
            Operation::Join(..)
        ));
        drop(a);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn actions_are_queued_and_unknown_actions_rejected() {
        let (a, dir) = fake();
        for (name, expected) in [
            ("start", 0),
            ("stop", 1),
            ("restart", 2),
            ("forget-wifi", 3),
        ] {
            a.action(name).unwrap();
            a.response_sent();
            let op = a
                .operation_rx
                .lock()
                .unwrap()
                .as_ref()
                .unwrap()
                .try_recv()
                .unwrap();
            let actual = match op {
                Operation::Start => 0,
                Operation::Stop => 1,
                Operation::Restart => 2,
                Operation::Forget => 3,
                _ => 4,
            };
            assert_eq!(actual, expected);
        }
        assert!(a.action("unknown").is_err());
        drop(a);
        std::fs::remove_dir_all(dir).unwrap();
    }
    /// `btmgmt` that records each Secure Connections switch and always succeeds.
    #[derive(Default)]
    struct Btmgmt(Mutex<Vec<String>>);
    impl CommandRunner for Btmgmt {
        fn run(&self, args: &[String], _: Duration) -> Result<CommandResult> {
            let on = args[2] == "exec btmgmt --index 0 sc on <&1";
            assert!(on || args[2] == "exec btmgmt --index 0 sc off <&1");
            self.0
                .lock()
                .unwrap()
                .push(if on { "on" } else { "off" }.into());
            Ok(CommandResult {
                exit_code: 0,
                output: format!(
                    "hci0 Set Secure Connections complete, settings: powered ssp br/edr le{}\n",
                    if on { " secure-conn" } else { "" }
                ),
            })
        }
    }
    struct Discoverable;
    struct Hidden;
    impl crate::linux_bluetooth::PairingBackend for Discoverable {
        fn open(
            &self,
            _: u32,
            _: std::time::Instant,
        ) -> Result<Box<dyn crate::linux_bluetooth::PairingSession>> {
            Ok(Box::new(Hidden))
        }
    }
    impl crate::linux_bluetooth::PairingSession for Hidden {
        fn close(self: Box<Self>) {}
    }
    #[test]
    fn admin_pairing_turns_secure_connections_off_and_cancel_or_stop_restore_it() {
        let (mut a, dir) = fake();
        let runner = Arc::new(Btmgmt::default());
        let lines = Arc::new(Mutex::new(Vec::new()));
        let copy = lines.clone();
        {
            let admin = Arc::get_mut(&mut a).unwrap();
            admin.events = Arc::new(JsonEvents::new(Arc::new(move |line| {
                copy.lock().unwrap().push(line)
            })));
            admin.pairing_backend = Arc::new(Discoverable);
            admin.secure_connections = Arc::new(crate::secure_connections::SecureConnections::new(
                runner.clone(),
                admin.events.clone(),
            ));
        }
        let switches = || runner.0.lock().unwrap().clone();
        let reported = |a: &LinuxRelayAdmin| {
            serde_json::to_value(a.status()).unwrap()["bluetoothSecureConnections"].clone()
        };
        assert!(reported(&a).is_null());
        a.action("pair-iphone").unwrap();
        assert_eq!(switches(), ["off"]);
        assert_eq!(reported(&a), false);
        // A second request while the window is open changes nothing.
        a.action("pair-iphone").unwrap();
        assert_eq!(switches(), ["off"]);
        a.action("cancel-pairing").unwrap();
        assert_eq!(switches(), ["off", "on"]);
        assert_eq!(reported(&a), true);
        a.action("pair-iphone").unwrap();
        a.shutdown();
        assert_eq!(switches(), ["off", "on", "off", "on"]);
        let logs: Vec<String> = lines
            .lock()
            .unwrap()
            .iter()
            .filter_map(|l| {
                let v: serde_json::Value = serde_json::from_str(l).unwrap();
                v["message"]
                    .as_str()
                    .filter(|m| m.starts_with("bluetooth-sc "))
                    .map(str::to_string)
            })
            .collect();
        assert_eq!(
            logs,
            [
                "bluetooth-sc off reason=pairing",
                "bluetooth-sc on reason=pairing-cancelled",
                "bluetooth-sc off reason=pairing",
                "bluetooth-sc on reason=relay-stop"
            ]
        );
        drop(a);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn pairing_status_is_cancelable_and_closing_without_a_window_is_harmless() {
        let (a, dir) = fake();
        assert_eq!(PAIRING_SECONDS, 600);
        let json = serde_json::to_value(a.status()).unwrap();
        assert_eq!(json["pairingAvailable"], true);
        assert_eq!(json["pairingCancelable"], true);
        assert!(json["pairingDeadline"].is_null() && json["pairingRemaining"].is_null());
        assert!(json["pairedDevices"].is_array());
        assert!(json.get("receivers").is_none());
        assert!(json["video"]["codec"].is_string());
        a.action("cancel-pairing").unwrap();
        assert!(a.pairing.lock().unwrap().is_none());
        drop(a);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn pairing_window_runs_on_the_monotonic_clock_and_shows_a_wall_clock_deadline() {
        let (mut a, dir) = fake();
        {
            let admin = Arc::get_mut(&mut a).unwrap();
            admin.pairing_backend = Arc::new(Discoverable);
            admin.secure_connections = Arc::new(crate::secure_connections::SecureConnections::new(
                Arc::new(Btmgmt::default()),
                admin.events.clone(),
            ));
        }
        a.action("pair-iphone").unwrap();
        let json = serde_json::to_value(a.status()).unwrap();
        let remaining = json["pairingRemaining"].as_u64().unwrap();
        assert!((u64::from(PAIRING_SECONDS) - 1..=u64::from(PAIRING_SECONDS)).contains(&remaining));
        // The display deadline is the same moment on the wall clock, however wrong that clock is.
        let wall = crate::platform::wall_clock().as_secs();
        let deadline = json["pairingDeadline"].as_u64().unwrap();
        assert!(deadline.abs_diff(wall + remaining) <= 1);
        // The window closes when the monotonic clock reaches its end, and a new one may open.
        a.pairing.lock().unwrap().as_mut().unwrap().1 = crate::admin::auth::monotonic_seconds();
        let json = serde_json::to_value(a.status()).unwrap();
        assert!(json["pairingDeadline"].is_null() && json["pairingRemaining"].is_null());
        a.action("pair-iphone").unwrap();
        assert!(serde_json::to_value(a.status()).unwrap()["pairingRemaining"].is_u64());
        a.action("cancel-pairing").unwrap();
        drop(a);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn bluetooth_guard_file_is_optional_and_configurable() {
        let (a, dir) = fake();
        // Release the state directory lock before a second shell opens it.
        drop(a);
        let path = dir.join("bluetooth-guard.json");
        let options = Options::parse(&[
            "--state-dir".into(),
            dir.to_str().unwrap().into(),
            "--bluetooth-guard-status".into(),
            path.to_str().unwrap().into(),
        ])
        .unwrap();
        assert_eq!(
            Options::parse(&["--state-dir".into(), dir.to_str().unwrap().into()])
                .unwrap()
                .bluetooth_guard_status,
            std::path::PathBuf::from(crate::bluetooth_guard::DEFAULT_STATUS_PATH)
        );
        let state = Arc::new(PersistentState::open(&dir).unwrap());
        let events = Arc::new(JsonEvents::new(Arc::new(|_| {})));
        let provision = WifiProvisioner::new(
            ProcessNetworkManagerCli {
                executable: "/bin/false".into(),
            },
            "wlan0".into(),
            "AA:BB:CC:DD:EE:FF",
            &dir,
        )
        .unwrap();
        let guarded = LinuxRelayAdmin::with_provisioner(options, state, events, provision).unwrap();
        assert!(serde_json::to_value(guarded.status())
            .unwrap()
            .get("bluetoothGuard")
            .is_none());
        std::fs::write(
            &path,
            br#"{"state":"failed","attempts":3,"lastRecovery":"2026-10-16T08:00:00Z","lastError":"reset failed","address":null}"#,
        )
        .unwrap();
        let json = serde_json::to_value(guarded.status()).unwrap();
        assert_eq!(
            json["bluetoothGuard"],
            serde_json::json!({"state":"failed","attempts":3,"lastRecovery":"2026-10-16T08:00:00Z","lastError":"reset failed","address":null})
        );
        drop(guarded);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn receiver_events_are_kept_bounded_and_stats_stay_out_of_the_log() {
        let (a, dir) = fake();
        let info = serde_json::json!({"type":"receiver-info","client":"4","info":{"refreshHz":60}});
        assert!(!receiver_event(&a.receivers, &info));
        for fps in 0..40 {
            assert!(receiver_event(
                &a.receivers,
                &serde_json::json!({"type":"stats","client":"4","renderedFps":fps})
            ));
        }
        assert!(!receiver_event(
            &a.receivers,
            &serde_json::json!({"type":"link","state":"joined"})
        ));
        let receivers = a.receivers();
        assert_eq!(receivers.len(), 1);
        assert_eq!(receivers[0]["info"]["refreshHz"], 60);
        assert_eq!(
            receivers[0]["stats"].as_array().unwrap().len(),
            crate::receiver_diagnostics::MAX_SAMPLES
        );
        assert!(!receiver_event(
            &a.receivers,
            &serde_json::json!({"type":"receiver-stopped"})
        ));
        assert!(a.receivers().is_empty());
        drop(a);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn relay_admin_settings_mapping_applies_network_now() {
        let (a, dir) = fake();
        assert!(matches!(a.update(serde_json::json!({"networkMode":"shared-wifi","sharedWifiNetworks":[{"ssid":"Car","password":"password"}],"iphone":"auto","hotspotChannel":6,"output":"both"}).as_object().unwrap().clone()),AdminUpdate::NextStart));
        let settings = a.settings();
        assert_eq!(settings.values["networkMode"], "shared-wifi");
        assert_eq!(settings.values["iphone"], "auto");
        assert_eq!(Settings::load(&dir).unwrap().hotspot_channel, 6);
        drop(a);
        std::fs::remove_dir_all(dir).unwrap();
    }
    fn trust_carlink(dir: &std::path::Path) {
        std::fs::create_dir_all(dir.join("carlink")).unwrap();
        std::fs::write(
            dir.join("carlink/trust.json"),
            serde_json::to_vec(
                &serde_json::json!({"peers":{"unit":format!("{}==", "A".repeat(86))}}),
            )
            .unwrap(),
        )
        .unwrap();
    }
    type Spawned = (Vec<std::ffi::OsString>, EventSink);
    /// Hands each child's arguments and event sink to the test.
    struct LinkFake(Mutex<std::sync::mpsc::Sender<Spawned>>);
    impl ChildFactory for LinkFake {
        fn spawn(
            &self,
            _: &std::path::Path,
            args: &[std::ffi::OsString],
            events: EventSink,
        ) -> std::io::Result<Box<dyn RelayChild>> {
            let _ = self.0.lock().unwrap().send((args.to_vec(), events));
            Ok(Box::new(OutputChild(Arc::default())))
        }
    }
    fn link_children(a: &mut Arc<LinuxRelayAdmin>) -> std::sync::mpsc::Receiver<Spawned> {
        let (tx, rx) = std::sync::mpsc::channel();
        Arc::get_mut(a).unwrap().child_factory = Arc::new(LinkFake(Mutex::new(tx)));
        rx
    }
    #[test]
    fn admin_restart_closes_the_hotspot_like_start() {
        let (mut a, dir) = fake();
        let _children = link_children(&mut a);
        for op in [Operation::Restart, Operation::Start] {
            // Even an AP retained between controllers for a soft restart is closed.
            let hotspot = Arc::new(crate::hotspot_lifetime::tests::Fake::default());
            let lease = a.hotspot_lifetime.create(|| hotspot.clone()).unwrap();
            lease.start(Duration::from_secs(1)).unwrap();
            a.hotspot_lifetime.keep_for_restart();
            lease.close().unwrap();
            assert_eq!(hotspot.closes.load(Ordering::SeqCst), 0);
            a.operation(op).unwrap();
            assert_eq!(hotspot.closes.load(Ordering::SeqCst), 1);
        }
        a.shutdown();
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn carlink_band_backoff_survives_admin_restarts_until_forget_carlink() {
        let (mut a, dir) = fake();
        let children = link_children(&mut a);
        {
            let mut settings = a.settings.lock().unwrap();
            settings.output = "carlink".into();
            settings.carlink_link = "static".into();
        }
        trust_carlink(&dir);
        let next = |millis| children.recv_timeout(Duration::from_millis(millis)).ok();
        let links = |args: &[std::ffi::OsString]| args.iter().any(|a| a == "--clink-head-unit");
        a.start_supervisor().unwrap();
        let (args, sink) = next(2000).unwrap();
        assert!(links(&args));
        sink(
            serde_json::json!({"type":"link","state":"lost","reason":"head_unit_band_unsupported:5180"}),
        );
        // Output changes, Restart and Start replace the supervisor; none relinks during the backoff.
        for op in [
            Operation::Outputs(None),
            Operation::Restart,
            Operation::Start,
        ] {
            a.operation(op).unwrap();
            assert!(next(200).is_none());
        }
        // Forgetting the head unit drops its escalation: the next trusted unit links at once.
        a.operation(Operation::ForgetCarlink).unwrap();
        assert!(next(200).is_none());
        trust_carlink(&dir);
        a.operation(Operation::Outputs(None)).unwrap();
        let (args, _) = next(2000).unwrap();
        assert!(links(&args));
        a.shutdown();
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn only_link_departure_undoes_the_follow_over() {
        let joined = serde_json::json!({"type":"link","state":"joined","network":{"ssid":"DIRECT-Car","interface":"wlan0"}});
        for departure in [
            serde_json::json!({"type":"link","state":"lost","reason":"network_link_lost"}),
            serde_json::json!({"type":"state","state":"Ended"}),
            serde_json::json!({"type":"receiver-stopped"}),
        ] {
            let mut follow = CarlinkFollow::default();
            follow.observe(&joined, "wlan0");
            let generation = follow.generation;
            // oxp-relay reports these while the link stays on the head unit's network.
            for event in [
                serde_json::json!({"type":"error","message":"Cannot read PIN file"}),
                serde_json::json!({"type":"error","code":"WIRE","message":"Broken pipe"}),
                serde_json::json!({"type":"pin-timeout","client":"unit"}),
                serde_json::json!({"type":"receiver-error","message":"No child"}),
                serde_json::json!({"type":"state","state":"Negotiating"}),
            ] {
                follow.observe(&event, "wlan0");
            }
            assert_eq!(follow.target.as_deref(), Some("DIRECT-Car"));
            assert_eq!(follow.generation, generation);
            follow.observe(&departure, "wlan0");
            assert!(follow.target.is_none());
            assert_ne!(follow.generation, generation);
        }
    }
    struct NoMfi;
    impl carplay_iap2::mfi::MfiAuthenticator for NoMfi {
        fn protocol_major(&self) -> Result<u8> {
            Ok(3)
        }
        fn read_certificate(&self, _: usize) -> Result<Vec<u8>> {
            unreachable!()
        }
        fn sign_challenge(&self, _: &[u8]) -> Result<Vec<u8>> {
            unreachable!()
        }
        fn baa_certificates(&self) -> Result<carplay_iap2::mfi::BaaCertificatePair> {
            unreachable!()
        }
    }
    /// Brings its AP up through the relay's hotspot lifetime, then finds no iPhone: the
    /// controller retries every few milliseconds and keeps the AP for the next attempt.
    struct RetryingPlatform {
        lifetime: Arc<crate::hotspot_lifetime::HotspotLifetime>,
        aps: Mutex<Vec<Arc<crate::hotspot_lifetime::tests::Fake>>>,
    }
    impl WirelessPlatform for RetryingPlatform {
        fn check_bluetooth_radio(&self) -> Result<()> {
            Ok(())
        }
        fn prepare_mfi(&self) -> Result<MfiResource> {
            Ok(MfiResource {
                authenticator: Arc::new(NoMfi),
            })
        }
        fn create_hotspot(&self) -> Result<Arc<dyn WirelessHotspotManager>> {
            self.lifetime.create(|| {
                let ap = Arc::new(crate::hotspot_lifetime::tests::Fake::default());
                self.aps.lock().unwrap().push(ap.clone());
                ap
            })
        }
        fn keep_hotspot_for_retry(&self) {
            self.lifetime.keep_for_retry();
        }
        fn cancel_hotspot_retry(&self) -> Result<()> {
            self.lifetime.cancel_retry()
        }
        fn start_pairing(&self) -> Result<Option<Arc<dyn CloseResource>>> {
            Ok(None)
        }
        fn select_iphone(&self) -> Result<BondedDevice> {
            Err(std::io::Error::other("No iPhone"))
        }
        fn create_bluetooth(&self, _: &BondedDevice) -> Result<Arc<dyn BluetoothConnection>> {
            unreachable!()
        }
        fn create_network(
            &self,
            _: WirelessHotspotInfo,
            _: Arc<dyn carplay_iap2::mfi::MfiAuthenticator>,
            _: NetworkCallbacks,
        ) -> Result<Arc<dyn NetworkAttachment>> {
            Err(std::io::Error::other("No iPhone"))
        }
        fn run_bootstrap(
            &self,
            _: Arc<dyn BluetoothConnection>,
            _: Arc<dyn PendingNetwork>,
            _: Arc<dyn carplay_iap2::mfi::MfiAuthenticator>,
            _: BootstrapCallbacks,
        ) -> Result<()> {
            unreachable!()
        }
    }
    #[test]
    fn pending_follow_over_keeps_old_controller_retries_off_the_radio() {
        let (mut a, dir) = fake();
        let children = link_children(&mut a);
        a.start_supervisor().unwrap();
        let (_, sink) = children.recv_timeout(Duration::from_secs(2)).unwrap();
        let platform = Arc::new(RetryingPlatform {
            lifetime: a.hotspot_lifetime.clone(),
            aps: Mutex::default(),
        });
        let controller = Arc::new(
            LinuxCarPlayController::new(platform.clone(), a.events.clone(), Duration::from_secs(1))
                .with_retry_delay(Duration::from_millis(1), Duration::from_millis(5)),
        );
        *a.controller.lock().unwrap() = Some(controller.clone());
        let running = controller.clone();
        *a.worker.lock().unwrap() = Some(std::thread::spawn(move || {
            let _ = running.run();
        }));
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        while platform
            .aps
            .lock()
            .unwrap()
            .first()
            .is_none_or(|ap| ap.starts.load(Ordering::SeqCst) == 0)
        {
            assert!(std::time::Instant::now() < deadline, "no AP started");
            std::thread::sleep(Duration::from_millis(1));
        }
        // Retries reuse the retained AP.
        std::thread::sleep(Duration::from_millis(30));
        assert_eq!(platform.aps.lock().unwrap().len(), 1);
        let ap = platform.aps.lock().unwrap()[0].clone();
        // Carlink joins the head unit's group on wlan0, which takes the radio from the AP.
        sink(
            serde_json::json!({"type":"link","state":"joined","network":{"ssid":"DIRECT-Car","interface":"wlan0"}}),
        );
        ap.failed.store(true, Ordering::SeqCst);
        // An error that leaves the link up is shown but does not end the follow-over.
        sink(serde_json::json!({"type":"error","message":"Cannot read PIN file"}));
        assert_eq!(
            a.carlink.lock().unwrap().last_error.as_deref(),
            Some("Cannot read PIN file")
        );
        // During the debounce the old controller keeps failing but brings no new AP up.
        std::thread::sleep(Duration::from_millis(100));
        assert_ne!(controller.stage(), "Stopped");
        assert_eq!(platform.aps.lock().unwrap().len(), 1);
        assert_eq!(ap.starts.load(Ordering::SeqCst), 1);
        assert_eq!(ap.closes.load(Ordering::SeqCst), 0);
        // The follow-over stops it; here the network credentials cannot be read.
        a.follow.lock().unwrap().changed = Some(std::time::Instant::now() - Duration::from_secs(1));
        assert!(a.reconcile_carlink_network().is_err());
        assert_eq!(controller.stage(), "Stopped");
        assert!(a.worker.lock().unwrap().is_none());
        assert_eq!(platform.aps.lock().unwrap().len(), 1);
        // Once the link has left, the next controller may bring its AP up again.
        sink(serde_json::json!({"type":"receiver-stopped"}));
        settle_follow(&a);
        assert!(a
            .hotspot_lifetime
            .create(|| Arc::new(crate::hotspot_lifetime::tests::Fake::default()))
            .is_ok());
        a.shutdown();
        std::fs::remove_dir_all(dir).unwrap();
    }
}

/// Select the interface shared with CarPlay rather than the first host interface (often usb0).
fn prefer_pairing_address(
    event: &mut serde_json::Value,
    interface: &str,
    addresses: &[(String, std::net::Ipv4Addr, u8)],
) {
    let Some((_, address, _)) = addresses
        .iter()
        .find(|(name, ip, _)| name == interface && !ip.is_loopback() && !ip.is_unspecified())
    else {
        return;
    };
    let Some(url) = event["url"].as_str() else {
        return;
    };
    let Some((scheme, rest)) = url.split_once("://") else {
        return;
    };
    let end = rest.find('/').unwrap_or(rest.len());
    let authority = &rest[..end];
    let port = authority.rfind(':').map(|i| &authority[i..]).unwrap_or("");
    event["url"] = format!("{scheme}://{address}{port}{}", &rest[end..]).into();
}

#[cfg(test)]
mod pairing_address_tests {
    use super::*;
    #[test]
    fn pairing_url_prefers_wifi_and_preserves_pairing_token() {
        let original = serde_json::json!({"type":"pairing-url","url":"https://192.168.68.1:8443/#pair=secret"});
        let mut event = original.clone();
        let rows = vec![
            ("usb0".into(), "192.168.68.1".parse().unwrap(), 24),
            ("wlan0".into(), "192.168.5.173".parse().unwrap(), 24),
        ];
        prefer_pairing_address(&mut event, "wlan0", &rows);
        assert_eq!(event["url"], "https://192.168.5.173:8443/#pair=secret");
        let mut fallback = original.clone();
        prefer_pairing_address(&mut fallback, "wlan1", &rows);
        assert_eq!(fallback, original);
    }
}
