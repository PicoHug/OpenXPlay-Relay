// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15

use crate::Result;
use crate::*;
use carplay_airplay::*;
use carplay_iap2::{mfi::MfiAuthenticator, LocalMfiAuthenticationClient, *};
use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, Weak,
    },
    time::Duration,
};
fn shared_network<'a>(
    saved: &'a [crate::settings::SharedWifiNetwork],
    rows: &str,
) -> Option<&'a crate::settings::SharedWifiNetwork> {
    let rows = rows
        .lines()
        .map(crate::provision::terse_fields)
        .collect::<Vec<_>>();
    if let Some(active) = rows.iter().find(|f| f.len() == 2 && f[0] == "*") {
        saved.iter().find(|n| n.ssid == active[1])
    } else {
        saved
            .iter()
            .find(|n| rows.iter().any(|f| f.len() == 2 && f[1] == n.ssid))
    }
}
pub struct LinuxWirelessPlatform {
    options: Options,
    runner: Arc<dyn CommandRunner>,
    identity: Arc<AirPlayIdentity>,
    pairings: Arc<dyn PairingStore>,
    sink: Arc<dyn MediaSink>,
    events: Arc<JsonEvents>,
    input: Arc<DiPlayInput>,
    bluetooth: LinuxBluetooth,
    session: (u32, bool),
    hotspot_lifetime: Arc<crate::hotspot_lifetime::HotspotLifetime>,
    bluetooth_wake: Option<Arc<crate::bluetooth_wake::BluetoothWake>>,
    secure_connections: Option<Arc<crate::secure_connections::SecureConnections>>,
    /// The SSID of the access point `create_hotspot` prepared, when known before it is up.
    planned_ssid: Mutex<Option<String>>,
    provisioning: Option<Arc<crate::provision::ProvisioningGate>>,
}
impl LinuxWirelessPlatform {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        options: Options,
        runner: Arc<dyn CommandRunner>,
        identity: Arc<AirPlayIdentity>,
        pairings: Arc<dyn PairingStore>,
        sink: Arc<dyn MediaSink>,
        events: Arc<JsonEvents>,
        input: Arc<DiPlayInput>,
    ) -> Self {
        input.initialize_video_state(&options.state_dir);
        let session = input.configure_session();
        let bluetooth = LinuxBluetooth::new(
            runner.clone(),
            options.iphone.clone(),
            options.rfcomm_channel,
        );
        Self {
            options,
            runner,
            identity,
            pairings,
            sink,
            events,
            input,
            bluetooth,
            session,
            hotspot_lifetime: Arc::default(),
            bluetooth_wake: None,
            secure_connections: None,
            planned_ssid: Mutex::new(None),
            provisioning: None,
        }
    }
    /// The relay's Wi-Fi provisioning, which the device hotspot's activation waits for.
    /// Bluetooth does not wait for it.
    pub fn with_provisioning(mut self, gate: Arc<crate::provision::ProvisioningGate>) -> Self {
        self.provisioning = Some(gate);
        self
    }
    pub fn with_hotspot_lifetime(
        mut self,
        lifetime: Arc<crate::hotspot_lifetime::HotspotLifetime>,
    ) -> Self {
        self.hotspot_lifetime = lifetime;
        self
    }
    /// BlueZ changes that end the controller's Bluetooth waits early.
    pub fn with_bluetooth_wake(mut self, wake: Arc<crate::bluetooth_wake::BluetoothWake>) -> Self {
        self.bluetooth_wake = Some(wake);
        self
    }
    /// The relay's Secure Connections policy: off while a `--pair` window is open.
    pub fn with_secure_connections(
        mut self,
        policy: Arc<crate::secure_connections::SecureConnections>,
    ) -> Self {
        self.secure_connections = Some(policy);
        self
    }
    /// Before this process brings its first AP up, removes the APs a killed run left behind.
    fn remove_stale_hotspots(&self) {
        let removed = self.hotspot_lifetime.remove_stale_once(|| {
            crate::linux_hotspot::remove_stale_hotspots(self.runner.as_ref())
        });
        match removed {
            Some(Ok(true)) => self.events.log(&format!(
                "hotspot-stale removed=up gapMs={}",
                self.hotspot_lifetime.down_remaining().as_millis()
            )),
            Some(Err(e)) => self
                .events
                .log(&format!("hotspot-stale cleanup failed: {e}")),
            _ => {}
        }
    }
    pub fn load_local_mfi(directory: &std::path::Path) -> Result<Arc<dyn MfiAuthenticator>> {
        let key = std::fs::read(directory.join("identity.pk8"))?;
        let cert = std::fs::read(directory.join("certificate.p7b"))?;
        Ok(Arc::new(LocalMfiAuthenticationClient::from_identity(
            &key, &cert,
        )?))
    }
}
struct BluetoothClient {
    stream: LinuxRfcommStream,
    address: String,
    channel: u8,
}
impl DuplexStream for BluetoothClient {
    fn send(&self, b: &[u8]) -> Result<()> {
        self.stream.send(b)
    }
    fn recv(&self, n: usize, t: Duration) -> Result<Receive> {
        self.stream.recv(n, t)
    }
    fn close(&self) -> Result<()> {
        self.stream.close()
    }
}
impl BluetoothConnection for BluetoothClient {
    fn connect(&self) -> Result<()> {
        self.stream
            .connect(&self.address, self.channel, Duration::from_secs(15))
    }
}
impl WirelessPlatform for LinuxWirelessPlatform {
    fn check_bluetooth_radio(&self) -> Result<()> {
        self.bluetooth.check_radio()?;
        // A bonded iPhone reconnects only with Secure Connections on: a switch the policy is
        // making for this adapter, e.g. at startup or after BlueZ reset it, completes first.
        if let Some(policy) = &self.secure_connections {
            policy.wait_settled(crate::secure_connections::SWITCH_TIMEOUT);
        }
        Ok(())
    }
    fn prepare_mfi(&self) -> Result<MfiResource> {
        Ok(MfiResource {
            authenticator: Self::load_local_mfi(&self.options.identity_dir)?,
        })
    }
    fn create_hotspot(&self) -> Result<Arc<dyn WirelessHotspotManager>> {
        if self.options.settings.network_mode == "shared-wifi" {
            let rows = crate::platform::command(
                self.runner.as_ref(),
                &[
                    "nmcli",
                    "-t",
                    "-f",
                    "IN-USE,SSID",
                    "device",
                    "wifi",
                    "list",
                    "--rescan",
                    "no",
                    "ifname",
                    &self.options.wifi_interface,
                ],
                Duration::from_secs(5),
            )?;
            let network = shared_network(&self.options.settings.shared_wifi_networks, &rows)
                .ok_or_else(|| std::io::Error::other("No saved Wi-Fi is connected or in range"))?;
            *self.planned_ssid.lock().unwrap() = Some(network.ssid.clone());
            Ok(Arc::new(crate::linux_hotspot::ExistingWifiManager::new(
                self.runner.clone(),
                self.options.wifi_interface.clone(),
                network.ssid.clone(),
                network.password.clone(),
            )))
        } else {
            let hotspot = self.hotspot_lifetime.create(|| {
                let hotspot = LinuxHotspot::new(
                    self.options.state_dir.clone(),
                    self.runner.clone(),
                    self.options.wifi_interface.clone(),
                    self.options.wifi_channel,
                );
                Arc::new(match &self.provisioning {
                    Some(gate) => hotspot.with_provisioning(gate.clone()),
                    None => hotspot,
                })
            })?;
            // A new AP takes the saved credentials; a reused one tells its SSID at once.
            *self.planned_ssid.lock().unwrap() = if self.hotspot_lifetime.reused() {
                None
            } else {
                crate::linux_hotspot::hotspot_credentials(&self.options.state_dir)
                    .ok()
                    .map(|(ssid, _)| ssid)
            };
            Ok(hotspot)
        }
    }
    /// At boot NetworkManager may answer nmcli only once it has set the Wi-Fi device up, so the
    /// look for a killed run's APs runs with the AP's start, not before Bluetooth.
    fn prepare_hotspot(&self) {
        if self.options.settings.network_mode != "shared-wifi" {
            self.remove_stale_hotspots();
        }
    }
    fn planned_hotspot_ssid(&self) -> Option<String> {
        self.planned_ssid.lock().unwrap().clone()
    }
    fn hotspot_reused(&self) -> bool {
        self.options.settings.network_mode != "shared-wifi" && self.hotspot_lifetime.reused()
    }
    fn keep_hotspot_for_retry(&self) {
        if self.options.settings.network_mode != "shared-wifi" {
            self.hotspot_lifetime.keep_for_retry();
        }
    }
    fn cancel_hotspot_retry(&self) -> Result<()> {
        if self.options.settings.network_mode == "shared-wifi" {
            return Ok(());
        }
        self.hotspot_lifetime.cancel_retry()
    }
    fn hotspot_stations(&self) -> Option<Vec<String>> {
        if self.options.settings.network_mode == "shared-wifi" {
            return None;
        }
        crate::linux_hotspot::associated_stations(
            self.runner.as_ref(),
            &self.options.wifi_interface,
        )
        .ok()
    }
    fn hotspot_down_remaining(&self) -> Duration {
        if self.options.settings.network_mode == "shared-wifi" {
            return Duration::ZERO;
        }
        self.hotspot_lifetime.down_remaining()
    }
    fn watch_bluetooth(
        &self,
        wake: crate::bluetooth_wake::BluetoothListener,
    ) -> Option<crate::bluetooth_wake::BluetoothSubscription> {
        Some(self.bluetooth_wake.as_ref()?.subscribe(wake))
    }
    fn start_pairing(&self) -> Result<Option<Arc<dyn CloseResource>>> {
        match self.options.pair_seconds {
            Some(n) => {
                let mut mode = PairingMode::new(n, self.events.clone());
                if let Some(policy) = &self.secure_connections {
                    mode = mode.with_secure_connections(policy.clone());
                }
                let mode = Arc::new(mode);
                mode.start()?;
                Ok(Some(mode))
            }
            None => Ok(None),
        }
    }
    fn select_iphone(&self) -> Result<BondedDevice> {
        self.bluetooth.select()
    }
    fn create_bluetooth(&self, device: &BondedDevice) -> Result<Arc<dyn BluetoothConnection>> {
        Ok(Arc::new(BluetoothClient {
            stream: LinuxRfcommStream::new(),
            address: device.address.clone(),
            channel: self.bluetooth.channel(&device.address)?,
        }))
    }
    fn create_network(
        &self,
        info: WirelessHotspotInfo,
        mfi: Arc<dyn MfiAuthenticator>,
        callbacks: NetworkCallbacks,
    ) -> Result<Arc<dyn NetworkAttachment>> {
        let mut config = air_play_config(
            info.bssid
                .clone()
                .ok_or_else(|| std::io::Error::other("AP BSSID unavailable"))?,
            self.bluetooth.local_address()?,
            self.session,
        );
        self.input.configure_display(&mut config.main);
        let input = self.input.clone();
        let sink = self.sink.clone();
        let events = self.events.clone();
        let mut network = LinuxNetwork::new(
            info,
            config,
            self.identity.clone(),
            self.pairings.clone(),
            mfi,
            self.sink.clone(),
            self.events.clone(),
            callbacks,
        );
        network.input = Some((input, sink, events));
        Ok(Arc::new(network))
    }
    fn run_bootstrap(
        &self,
        connection: Arc<dyn BluetoothConnection>,
        network: Arc<dyn PendingNetwork>,
        mfi: Arc<dyn MfiAuthenticator>,
        callbacks: BootstrapCallbacks,
    ) -> Result<()> {
        let events = self.events.clone();
        let session = Iap2Session::open_wireless(connection, Arc::new(move |s| events.trace(&s)))?;
        let result = (|| {
            // The identification names the AP, whose SSID is known before it is up; the Wi-Fi
            // configuration and StartSession wait for the started network.
            let config = identification(
                &self.identity,
                &self.bluetooth.local_address()?,
                &network.ssid(
                    carplay_iap2::transport::iap2_wireless_control_client::DEFAULT_ENDPOINT_WAIT,
                )?,
                self.input.plugins_enabled(),
            );
            let events = ControlEvents {
                events: self.events.clone(),
                ready: callbacks.on_authenticated,
                start: callbacks.on_start_session,
                wifi_sent: callbacks.on_wifi_configuration_sent,
                input: Some(self.input.clone()),
                network: Some(network.clone()),
                closed: Arc::new(AtomicBool::new(false)),
            };
            Iap2WirelessControlClient::new(session.clone(), mfi).run_deferred(
                &config,
                &PendingEndpoint(network),
                Some(Duration::from_secs(300)),
                &events,
            )
        })();
        let _ = session.close();
        let result = result?;
        self.events.emit("bootstrap-ended",&serde_json::json!({"terminal":format!("{:?}",result.terminal),"stage":format!("{:?}",result.stage)}));
        Ok(())
    }
}
/// The AirPlay receiver the Linux relay declares to the iPhone, for a session of (fps, HEVC).
fn air_play_config(device_id: String, bt_mac: String, session: (u32, bool)) -> AirPlayConfig {
    let mut config = AirPlayConfig::linux_receiver(device_id, bt_mac);
    config.microphone_opus = false;
    // The Linux relay cannot decode Opus and its receivers play only PCM and AAC.
    config.output_opus = false;
    config.main.fps = session.0;
    config.hevc = session.1;
    config.device_name = crate::branding::DEVICE_NAME.into();
    config.model = crate::branding::MODEL.into();
    // The car-button tile the iPhone shows to return to the car.
    config.icons = vec![AirPlayIcon {
        width_pixels: crate::branding::OEM_ICON_PIXELS,
        height_pixels: crate::branding::OEM_ICON_PIXELS,
        data: crate::branding::OEM_ICON_PNG.to_vec(),
    }];
    config.oem_label = crate::branding::OEM_ICON_LABEL.into();
    config
}
fn identification(
    identity: &AirPlayIdentity,
    bt: &str,
    ssid: &str,
    route_guidance: bool,
) -> Iap2IdentificationConfig {
    Iap2IdentificationConfig {
        route_guidance,
        name: crate::branding::DEVICE_NAME.into(),
        model_identifier: crate::branding::MODEL.into(),
        manufacturer: crate::branding::MANUFACTURER.into(),
        serial_number: identity.pairing_id.clone(),
        firmware_version: "0.1".into(),
        hardware_version: "1".into(),
        wireless: Some(Iap2WirelessIdentification {
            bluetooth_mac: bt.into(),
            ssid: ssid.into(),
        }),
        language: "en".into(),
        external_accessory_protocol: "com.shilapi.xcertplay".into(),
    }
}
/// The attempt's network as the iAP2 control client asks for it.
struct PendingEndpoint(Arc<dyn PendingNetwork>);
impl WirelessEndpointSource for PendingEndpoint {
    fn endpoint(&self) -> Result<Option<Iap2WirelessCarPlayEndpoint>> {
        self.0
            .wait(Duration::ZERO)?
            .map(|network| network.endpoint())
            .transpose()
    }
}
struct ControlEvents {
    input: Option<Arc<DiPlayInput>>,
    events: Arc<JsonEvents>,
    ready: Arc<dyn Fn() + Send + Sync>,
    start: Arc<dyn Fn() + Send + Sync>,
    wifi_sent: Arc<dyn Fn() + Send + Sync>,
    network: Option<Arc<dyn PendingNetwork>>,
    closed: Arc<AtomicBool>,
}
impl WirelessControlEvents for ControlEvents {
    fn on_incoming(&self, frame: &Iap2Frame) {
        if let Some(input) = &self.input {
            input.iap2_context(frame);
        }
    }
    fn before_start_session(&self) -> Result<()> {
        if let Some(n) = &self.network {
            n.wait(Duration::ZERO)?
                .ok_or_else(|| std::io::Error::other("Network is not ready"))?
                .validate_ready()?;
        }
        Ok(())
    }
    fn on_start_session_sent(&self, _: Iap2StartSessionSent) {
        (self.start)()
    }
    fn on_wifi_configuration_sent(&self) {
        (self.wifi_sent)()
    }
    fn on_ready(&self) {
        (self.ready)()
    }
    fn on_progress(&self, s: &str) {
        self.events.log(s)
    }
    fn keep_alive(&self) -> bool {
        !self.closed.load(Ordering::Acquire)
    }
}
struct NetworkState {
    server: Option<Arc<LinuxAirPlayServer>>,
    bonjour: Option<LinuxBonjour>,
    endpoint: Option<Iap2WirelessCarPlayEndpoint>,
}
type BridgeWiring = (Arc<DiPlayInput>, Arc<dyn MediaSink>, Arc<JsonEvents>);
pub struct LinuxNetwork {
    info: WirelessHotspotInfo,
    config: AirPlayConfig,
    identity: Arc<AirPlayIdentity>,
    pairings: Arc<dyn PairingStore>,
    mfi: Arc<dyn MfiAuthenticator>,
    sink: Arc<dyn MediaSink>,
    events: Arc<JsonEvents>,
    callbacks: NetworkCallbacks,
    state: Mutex<NetworkState>,
    closed: Arc<AtomicBool>,
    channels: Arc<Mutex<Vec<Arc<Iap2Session>>>>,
    input: Option<BridgeWiring>,
}
impl LinuxNetwork {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        info: WirelessHotspotInfo,
        config: AirPlayConfig,
        identity: Arc<AirPlayIdentity>,
        pairings: Arc<dyn PairingStore>,
        mfi: Arc<dyn MfiAuthenticator>,
        sink: Arc<dyn MediaSink>,
        events: Arc<JsonEvents>,
        callbacks: NetworkCallbacks,
    ) -> Self {
        Self {
            info,
            config,
            identity,
            pairings,
            mfi,
            sink,
            events,
            callbacks,
            state: Mutex::new(NetworkState {
                server: None,
                bonjour: None,
                endpoint: None,
            }),
            closed: Arc::new(AtomicBool::new(false)),
            channels: Arc::new(Mutex::new(Vec::new())),
            input: None,
        }
    }
    pub fn accept_tunnel(&self, stream: Arc<dyn DuplexStream>) -> Result<bool> {
        let endpoint = self.endpoint()?;
        spawn_tunnel(
            stream,
            self.closed.clone(),
            self.channels.clone(),
            self.events.clone(),
            self.callbacks.clone(),
            self.mfi.clone(),
            identification(
                &self.identity,
                &self.config.bt_mac,
                &self.info.ssid,
                self.input
                    .as_ref()
                    .is_some_and(|(i, _, _)| i.plugins_enabled()),
            ),
            endpoint,
            self.input.as_ref().map(|(i, _, _)| i.clone()),
        )
    }
}
struct SessionListener {
    input: Option<Arc<DiPlayInput>>,
    callbacks: NetworkCallbacks,
    closed: Arc<AtomicBool>,
    events: Arc<JsonEvents>,
    active: Mutex<Vec<Weak<AirPlaySession>>>,
}
impl AirPlaySessionListener for SessionListener {
    fn on_session_active(&self, s: Arc<AirPlaySession>) {
        if !self.closed.load(Ordering::Acquire) {
            self.active.lock().unwrap().push(Arc::downgrade(&s));
            if let Some(input) = &self.input {
                input.session_context(true);
            }
            self.events
                .emit("airplay-session-active", &serde_json::json!({}));
            (self.callbacks.on_air_play)()
        }
    }
    fn on_session_ended(&self, s: Arc<AirPlaySession>) {
        let mut active = self.active.lock().unwrap();
        let before = active.len();
        active.retain(|w| w.upgrade().is_some_and(|x| !Arc::ptr_eq(&x, &s)));
        if before > 0 && active.is_empty() {
            if let Some(input) = &self.input {
                input.session_context(false);
            }
        }
        if before > 0 && active.is_empty() && !self.closed.load(Ordering::Acquire) {
            (self.callbacks.on_failure)(std::io::Error::other("AirPlay session ended"));
        }
    }
    fn on_transport_error(&self, s: &str) {
        if !self.closed.load(Ordering::Acquire) {
            (self.callbacks.on_failure)(std::io::Error::other(s));
        }
    }
    fn on_command(&self, _: &AirPlaySession, kind: &str, params: &BplistValue) {
        if let Some(input) = &self.input {
            input.airplay_context(kind, params);
        }
        self.events
            .log(&format!("airplay command type={kind} params={params:?}"))
    }
    fn on_debug_log(&self, s: &str) {
        self.events.log(s)
    }
}
struct TunnelHandler {
    input: Option<Arc<DiPlayInput>>,
    closed: Arc<AtomicBool>,
    channels: Arc<Mutex<Vec<Arc<Iap2Session>>>>,
    events: Arc<JsonEvents>,
    callbacks: NetworkCallbacks,
    mfi: Arc<dyn MfiAuthenticator>,
    config: Iap2IdentificationConfig,
    endpoint: Arc<Mutex<Option<Iap2WirelessCarPlayEndpoint>>>,
}
impl IapTunnelListener for TunnelHandler {
    fn on_connected(&self, s: Arc<dyn DuplexStream>) -> Result<bool> {
        let e = self
            .endpoint
            .lock()
            .unwrap()
            .clone()
            .ok_or_else(|| std::io::Error::other("Network is not ready"))?;
        spawn_tunnel(
            s,
            self.closed.clone(),
            self.channels.clone(),
            self.events.clone(),
            self.callbacks.clone(),
            self.mfi.clone(),
            self.config.clone(),
            e,
            self.input.clone(),
        )
    }
    fn on_message(&self, _: &[u8]) -> Result<()> {
        Ok(())
    }
}
#[allow(clippy::too_many_arguments)]
fn spawn_tunnel(
    stream: Arc<dyn DuplexStream>,
    closed: Arc<AtomicBool>,
    channels: Arc<Mutex<Vec<Arc<Iap2Session>>>>,
    events: Arc<JsonEvents>,
    callbacks: NetworkCallbacks,
    mfi: Arc<dyn MfiAuthenticator>,
    mut config: Iap2IdentificationConfig,
    endpoint: Iap2WirelessCarPlayEndpoint,
    input: Option<Arc<DiPlayInput>>,
) -> Result<bool> {
    if closed.load(Ordering::Acquire) {
        return Ok(false);
    }
    config.route_guidance = input.as_ref().is_some_and(|i| i.plugins_enabled());
    let trace = events.clone();
    let session = Iap2Session::open_tunnel(stream, Arc::new(move |s| trace.trace(&s)))?;
    channels.lock().unwrap().push(session.clone());
    crate::platform::spawn_worker(
        "airplay-iap2",
        Box::new(move || {
            let ce = ControlEvents {
                input,
                events,
                ready: callbacks.on_tunnel_ready.clone(),
                start: Arc::new(|| {}),
                wifi_sent: Arc::new(|| {}),
                network: None,
                closed: closed.clone(),
            };
            let result = Iap2WirelessControlClient::new(session.clone(), mfi)
                .run(&config, &endpoint, None, &ce);
            let _ = session.close();
            channels
                .lock()
                .unwrap()
                .retain(|s| !Arc::ptr_eq(s, &session));
            if !closed.load(Ordering::Acquire) {
                let reason = match result {
                    Ok(r) => format!("AirPlay iAP2 tunnel ended: {:?}", r.terminal),
                    Err(e) => e.to_string(),
                };
                (callbacks.on_failure)(std::io::Error::other(reason));
            }
        }),
    )?;
    Ok(true)
}
impl NetworkAttachment for LinuxNetwork {
    fn start(&self) -> Result<()> {
        let address = self
            .info
            .host_address
            .ok_or_else(|| std::io::Error::other("Hotspot IPv4 unavailable"))?;
        let transport: Arc<dyn StreamTransport> =
            Arc::new(LinuxStreamTransport::new(address.into()));
        let media = Arc::new(CarPlayMediaEngine::new(
            self.sink.clone(),
            transport.clone(),
            true,
        ));
        let endpoint = Arc::new(Mutex::new(None));
        let handler = Arc::new(TunnelHandler {
            input: self.input.as_ref().map(|(i, _, _)| i.clone()),
            closed: self.closed.clone(),
            channels: self.channels.clone(),
            events: self.events.clone(),
            callbacks: self.callbacks.clone(),
            mfi: self.mfi.clone(),
            config: identification(
                &self.identity,
                &self.config.bt_mac,
                &self.info.ssid,
                self.input
                    .as_ref()
                    .is_some_and(|(i, _, _)| i.plugins_enabled()),
            ),
            endpoint: endpoint.clone(),
        });
        media.set_iap_tunnel_handler(Some(handler.clone()))?;
        let listener = Arc::new(SessionListener {
            input: self.input.as_ref().map(|(i, _, _)| i.clone()),
            callbacks: self.callbacks.clone(),
            closed: self.closed.clone(),
            events: self.events.clone(),
            active: Mutex::new(Vec::new()),
        });
        let mut server = LinuxAirPlayServer::new(
            address,
            self.config.clone(),
            self.identity.clone(),
            self.pairings.clone(),
            self.mfi.clone(),
            media,
            listener,
            transport,
        );
        if let Some((input, sink, events)) = &self.input {
            let (input, sink, events) = (input.clone(), sink.clone(), events.clone());
            server = server.with_media_factory(Arc::new(move |control, transport| {
                let bridge = Arc::new(
                    StreamBridgeSink::new(input.clone(), sink.clone(), control, events.clone())
                        .with_arrival_clock(Arc::new(|| oxp_core::monotonic_nanos() as u64)),
                );
                let engine = Arc::new(CarPlayMediaEngine::new(bridge, transport, true));
                let _ = engine.set_iap_tunnel_handler(Some(handler.clone()));
                engine
            }));
        }
        let server = Arc::new(server);
        server.start()?;
        let c = server.config();
        let e = Iap2WirelessCarPlayEndpoint {
            ssid: self.info.ssid.clone(),
            passphrase: self.info.passphrase.clone(),
            channel: self.info.channel,
            security: self.info.security,
            ip_addresses: vec![address.to_string()],
            air_play_port: c.port,
            device_identifier: c.device_id.clone(),
            public_key: self.identity.public_key_hex(),
            source_version: c.source_version.clone(),
            access_point_bssid: self
                .info
                .access_point_bssid
                .as_ref()
                .and_then(|s| {
                    s.split(':')
                        .map(|s| u8::from_str_radix(s, 16))
                        .collect::<std::result::Result<Vec<_>, _>>()
                        .ok()
                })
                .and_then(|v| v.try_into().ok()),
        };
        *endpoint.lock().unwrap() = Some(e.clone());
        let mut state = self.state.lock().unwrap();
        state.endpoint = Some(e);
        state.server = Some(server);
        let bonjour = LinuxBonjour::new(address, c, self.identity.clone(), self.events.clone());
        bonjour.start()?;
        state.bonjour = Some(bonjour);
        Ok(())
    }
    fn endpoint(&self) -> Result<Iap2WirelessCarPlayEndpoint> {
        self.state
            .lock()
            .unwrap()
            .endpoint
            .clone()
            .ok_or_else(|| std::io::Error::other("Network is not ready"))
    }
    fn validate_ready(&self) -> Result<()> {
        let interface = self
            .info
            .interface_name
            .as_deref()
            .ok_or_else(|| std::io::Error::other("Network interface unavailable"))?;
        let a = crate::linux_hotspot::interface_addresses()?;
        if !a
            .iter()
            .any(|(i, a, _)| i == interface && Some(*a) == self.info.host_address)
        {
            return Err(std::io::Error::other(
                "Hotspot address changed before StartSession",
            ));
        }
        Ok(())
    }
    fn close(&self) -> Result<()> {
        self.closed.store(true, Ordering::Release);
        for s in std::mem::take(&mut *self.channels.lock().unwrap()) {
            let _ = s.close();
        }
        let mut state = self.state.lock().unwrap();
        if let Some(b) = state.bonjour.take() {
            let _ = b.close();
        }
        if let Some(s) = state.server.take() {
            let _ = s.close();
        }
        Ok(())
    }
}

#[cfg(test)]
mod network_tests {
    use super::*;
    #[test]
    fn wifi_configuration_trace_never_emits_the_network_passphrase() {
        let captured = Arc::new(Mutex::new(Vec::new()));
        let copy = captured.clone();
        let events = JsonEvents::new(Arc::new(move |line| copy.lock().unwrap().push(line)));
        let endpoint = Iap2WirelessCarPlayEndpoint {
            ssid: "DIRECT-Car".into(),
            passphrase: "private-car-secret".into(),
            channel: 6,
            security: Iap2WirelessSecurity::WpaWpa2,
            ip_addresses: vec!["192.168.49.2".into()],
            air_play_port: 7000,
            device_identifier: "AA:BB:CC:DD:EE:FF".into(),
            public_key: "1234".into(),
            source_version: "1.0".into(),
            access_point_bssid: None,
        };
        let frame = Iap2WirelessControlClient::accessory_wifi_configuration(&endpoint).unwrap();
        let trace =
            Iap2FrameFormatter::format_with_context(Iap2TraceDirection::Tx, "wireless", &frame);
        assert!(trace.contains(&endpoint.passphrase));
        events.trace(&trace);
        let output = captured.lock().unwrap().join(" ");
        assert!(!output.contains(&endpoint.passphrase));
        assert!(!output.contains("raw-body"));
        assert!(output.contains("0x5703"));
    }
    #[test]
    fn carplay_uses_connected_credentials_then_first_saved_in_range() {
        let saved =
            ["Absent", "Home", "Car: Wi-Fi"].map(|ssid| crate::settings::SharedWifiNetwork {
                ssid: ssid.into(),
                password: format!("{ssid}-secret"),
            });
        let network = shared_network(&saved, ":Home\n*:Car\\: Wi-Fi\n").unwrap();
        assert_eq!(network, &saved[2]);
        assert_eq!(
            shared_network(&saved, ":Car\\: Wi-Fi\n:Home\n"),
            Some(&saved[1])
        );
        assert!(shared_network(&saved, "*:Unknown\n:Home\n").is_none());
        assert!(shared_network(&saved, "").is_none());
    }
    #[test]
    fn air_play_config_declares_the_openxplay_car_button() {
        let config = air_play_config(
            "02:00:00:00:00:02".into(),
            "02:00:00:00:00:03".into(),
            (30, false),
        );
        let info = AirPlayInfoPlist::build(&config).unwrap();
        let key = |key: &str| info.get(key).unwrap();
        assert_eq!(key("oemIconVisible"), &BplistValue::Boolean(true));
        assert_eq!(key("oemIconLabel").as_str(), Some("OpenXPlay"));
        let icons = key("oemIcons").as_array().unwrap();
        assert_eq!(icons.len(), 1);
        let field = |key: &str| icons[0].get(key).unwrap();
        assert_eq!(
            (
                field("widthPixels").as_u64(),
                field("heightPixels").as_u64()
            ),
            (Some(192), Some(192))
        );
        let png = field("imageData").as_data().unwrap();
        assert_eq!(png, crate::branding::OEM_ICON_PNG);
        // The declared size matches the PNG: signature, then IHDR width and height.
        assert_eq!(&png[..8], b"\x89PNG\r\n\x1a\n");
        assert_eq!(&png[12..16], b"IHDR");
        let side = |at: usize| u32::from_be_bytes(png[at..at + 4].try_into().unwrap());
        assert_eq!((side(16), side(20)), (192, 192));
        // Opaque 8-bit RGB: the tile has no transparent corners for the iPhone to fill.
        assert_eq!((png[24], png[25]), (8, 2));
    }
}
