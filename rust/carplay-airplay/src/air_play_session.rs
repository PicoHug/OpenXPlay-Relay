// SPDX-FileCopyrightText: DiPlay and xcertplay contributors
// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Derived from DiPlay (https://github.com/shihabal3amri/DiPlay), based on xcertplay (https://github.com/shilapi/xcertplay). Modified by OpenXPlay.org on 2026-10-15.

use crate::*;
use carplay_iap2::{DuplexStream, Receive};
use std::{
    net::{IpAddr, SocketAddr},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Mutex, Weak,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use BplistValue as V;
/// Platform interface for `AirPlaySessionListener`. Implementations must support bounded shutdown.
pub trait AirPlaySessionListener: Send + Sync {
    fn on_device_info(&self, _session: &AirPlaySession, _info: &AirPlayDeviceInfo) {}
    fn on_host_ui_requested(&self, _session: &AirPlaySession) {}
    fn on_command(&self, _session: &AirPlaySession, _kind: &str, _params: &BplistValue) {}
    fn on_remote_control_message(
        &self,
        _session: &AirPlaySession,
        _stream_id: u64,
        _message: &BplistValue,
    ) {
    }

    /// Errors propagate to the lifecycle owner.
    fn on_tcp_accepted(&self, _event: AirPlayTcpAccepted) {}
    /// Errors propagate to the lifecycle owner.
    fn on_session_active(&self, _session: Arc<AirPlaySession>) {}
    /// Errors propagate to the lifecycle owner.
    fn on_session_ended(&self, _session: Arc<AirPlaySession>) {}
    /// Errors propagate to the lifecycle owner.
    fn on_transport_error(&self, _message: &str) {}
    /// Errors propagate to the lifecycle owner.
    fn on_debug_log(&self, _message: &str) {}
}
/// Platform interface for `AirPlayMediaHandler`. Implementations must support bounded shutdown.
pub trait AirPlayMediaHandler: Send + Sync {
    /// Errors propagate to the lifecycle owner.
    fn on_screen(
        &self,
        session: &AirPlaySession,
        kind: u16,
        stream: &BplistValue,
    ) -> Result<Option<u16>>;
    /// Errors propagate to the lifecycle owner.
    fn on_audio(
        &self,
        session: &AirPlaySession,
        kind: u16,
        stream: &BplistValue,
    ) -> Result<Option<BplistValue>>;
    /// Errors propagate to the lifecycle owner.
    fn on_buffered_audio(
        &self,
        session: &AirPlaySession,
        stream: &BplistValue,
    ) -> Result<Option<BplistValue>>;
    /// Errors propagate to the lifecycle owner.
    fn on_buffered_audio_control(
        &self,
        session: &AirPlaySession,
        method: &str,
        body: &BplistValue,
    ) -> Result<Option<BplistValue>>;
    /// Errors propagate to the lifecycle owner.
    fn on_data_stream(
        &self,
        session: &AirPlaySession,
        stream: &BplistValue,
    ) -> Result<Option<BplistValue>>;
    /// Errors propagate to the lifecycle owner.
    fn on_feedback(&self, session: &AirPlaySession) -> Result<Option<BplistValue>>;
    /// Errors propagate to the lifecycle owner.
    fn on_setup_response_sent(&self, session: &AirPlaySession) -> Result<()>;
    /// Errors propagate to the lifecycle owner.
    fn on_teardown(&self, session: &AirPlaySession, kind: u16) -> Result<()>;
    /// Errors propagate to the lifecycle owner.
    fn on_session_closed(&self, session: &AirPlaySession) -> Result<()>;
}

#[derive(Clone, Debug)]
pub struct AirPlayDeviceInfo {
    pub name: String,
    pub device_id: String,
    pub wifi_mac: String,
    pub model: String,
}
struct PairingState {
    setup: PairSetup,
    verify: PairVerify,
    cipher: Option<ControlCipher>,
}
struct EventState {
    socket: Option<Arc<dyn DuplexStream>>,
    cipher: Option<ControlCipher>,
    cseq: u64,
    pending_night_mode: Option<bool>,
}
#[derive(Clone)]
struct Network {
    transport: Arc<dyn StreamTransport>,
    peer: IpAddr,
}
#[derive(Default)]
struct Resources {
    ntp: Option<NtpClock>,
    event_server: Option<Arc<dyn StreamListener>>,
    keep_alive: Option<Arc<dyn DatagramTransport>>,
}
pub struct AirPlaySession {
    socket: Arc<dyn DuplexStream>,
    config: AirPlayConfig,
    mfi: Arc<dyn MfiAuthenticator>,
    media: Arc<dyn AirPlayMediaHandler>,
    listener: Arc<dyn AirPlaySessionListener>,
    own: Weak<Self>,
    closed: AtomicBool,
    started: AtomicBool,
    pairing: Mutex<PairingState>,
    event: Mutex<EventState>,
    network: Mutex<Option<Network>>,
    resources: Mutex<Resources>,
    active_streams: Mutex<Vec<u16>>,
    workers: Mutex<Vec<JoinHandle<()>>>,
    main_screen_token: Mutex<Option<u64>>,
    screen_generation: AtomicU64,
}
fn dictionary<const N: usize>(v: [(&str, V); N]) -> V {
    V::dict(v)
}
fn empty() -> V {
    V::Dictionary(Vec::new())
}
fn response(status: u16) -> RtspResponse {
    RtspResponse {
        status,
        ..Default::default()
    }
}
fn bytes_response(kind: &str, body: Vec<u8>) -> RtspResponse {
    RtspResponse {
        status: 200,
        headers: [("Content-Type".into(), kind.into())].into(),
        body,
        ..Default::default()
    }
}
fn plist_response(body: &V) -> Result<RtspResponse> {
    Ok(bytes_response(
        PLIST_CONTENT_TYPE,
        BplistCodec::encode(body)?,
    ))
}
impl AirPlaySession {
    pub fn new(
        socket: Arc<dyn DuplexStream>,
        config: AirPlayConfig,
        identity: Arc<AirPlayIdentity>,
        pairings: Arc<dyn PairingStore>,
        mfi: Arc<dyn MfiAuthenticator>,
        media: Arc<dyn AirPlayMediaHandler>,
        listener: Arc<dyn AirPlaySessionListener>,
    ) -> Result<Arc<Self>> {
        Ok(Arc::new_cyclic(|own| Self {
            socket,
            config,
            mfi,
            media,
            listener,
            own: own.clone(),
            closed: AtomicBool::new(false),
            started: AtomicBool::new(false),
            pairing: Mutex::new(PairingState {
                setup: PairSetup::new(identity.clone(), pairings.clone()),
                verify: PairVerify::new(identity, pairings),
                cipher: None,
            }),
            event: Mutex::new(EventState {
                socket: None,
                cipher: None,
                cseq: 0,
                pending_night_mode: None,
            }),
            network: Mutex::new(None),
            resources: Mutex::new(Resources::default()),
            active_streams: Mutex::new(Vec::new()),
            workers: Mutex::new(Vec::new()),
            main_screen_token: Mutex::new(None),
            screen_generation: AtomicU64::new(0),
        }))
    }
    /// Supply portable I/O factories and the accepted control peer before starting the session.
    pub fn configure_transport(
        &self,
        transport: Arc<dyn StreamTransport>,
        peer: IpAddr,
    ) -> Result<()> {
        if self.started.load(Ordering::Acquire) {
            return Err(std::io::Error::other("AirPlaySession already started"));
        }
        *self.network.lock().unwrap() = Some(Network { transport, peer });
        Ok(())
    }
    pub fn remote_address(&self) -> Option<IpAddr> {
        self.network.lock().unwrap().as_ref().map(|n| n.peer)
    }
    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::Acquire)
    }
    pub fn main_screen_session_token(&self) -> Option<u64> {
        if self.is_closed() {
            None
        } else {
            *self.main_screen_token.lock().unwrap()
        }
    }
    pub fn log_debug(&self, message: &str) {
        self.listener.on_debug_log(message);
    }
    pub fn start(self: &Arc<Self>) -> Result<()> {
        if self.started.swap(true, Ordering::AcqRel) {
            return Ok(());
        }
        let this = self.clone();
        if let Err(error) = self.spawn("airplay-control", move || this.run_control()) {
            self.started.store(false, Ordering::Release);
            return Err(error);
        }
        Ok(())
    }
    fn spawn(&self, name: &str, body: impl FnOnce() + Send + 'static) -> Result<()> {
        let handle = thread::Builder::new()
            .name(name.into())
            .stack_size(256 * 1024)
            .spawn(body)?;
        self.workers.lock().unwrap().push(handle);
        Ok(())
    }
    pub fn close(&self) -> Result<()> {
        if self.closed.swap(true, Ordering::AcqRel) {
            return Ok(());
        }
        *self.main_screen_token.lock().unwrap() = None;
        let _ = self.socket.close();
        let event_socket = {
            let mut event = self.event.lock().unwrap();
            event.cipher = None;
            event.socket.take()
        };
        if let Some(socket) = event_socket {
            let _ = socket.close();
        }
        let resources = std::mem::take(&mut *self.resources.lock().unwrap());
        if let Some(server) = resources.event_server {
            let _ = server.close();
        }
        if let Some(socket) = resources.keep_alive {
            let _ = socket.close();
        }
        if let Some(ntp) = resources.ntp {
            let _ = ntp.close();
        }
        if let Err(error) = self.media.on_session_closed(self) {
            self.listener
                .on_debug_log(&format!("airplay media stream teardown failed: {error}"));
        }
        if let Some(this) = self.own.upgrade() {
            self.listener.on_session_ended(this);
        }
        let handles = std::mem::take(&mut *self.workers.lock().unwrap());
        let deadline = Instant::now() + Duration::from_secs(2);
        for handle in handles {
            if handle.thread().id() == thread::current().id() {
                continue;
            }
            while !handle.is_finished() && Instant::now() < deadline {
                thread::sleep(Duration::from_millis(5));
            }
            if handle.is_finished() {
                let _ = handle.join();
            }
        }
        Ok(())
    }
    pub fn shared_secret(&self) -> Option<[u8; 32]> {
        self.pairing.lock().unwrap().verify.shared()
    }
    pub fn controller_id(&self) -> Option<String> {
        self.pairing.lock().unwrap().verify.verified_controller_id()
    }
    pub fn synced_ntp(&self) -> u64 {
        let ntp = self.resources.lock().unwrap().ntp.clone();
        ntp.map_or_else(
            || {
                NtpClock::to_ntp(
                    SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_nanos() as u64,
                )
            },
            |n| n.synced_ntp(),
        )
    }
    pub fn send_command(&self, command: &V) -> Result<bool> {
        self.send_command_headers(command, "")
    }
    fn send_command_headers(&self, command: &V, extra: &str) -> Result<bool> {
        let result = {
            let mut event = self.event.lock().unwrap();
            self.send_command_locked(&mut event, command, extra)
        };
        match result {
            Ok(v) => Ok(v),
            Err(_) => {
                let _ = self.close();
                Ok(false)
            }
        }
    }
    fn send_command_locked(
        &self,
        event: &mut EventState,
        command: &V,
        extra: &str,
    ) -> Result<bool> {
        if self.is_closed() {
            return Ok(false);
        }
        let Some(socket) = event.socket.clone() else {
            return Ok(false);
        };
        if event.cipher.is_none() {
            return Ok(false);
        }
        event.cseq = event.cseq.wrapping_add(1);
        let body = BplistCodec::encode(command)?;
        let head = format!(
            "POST /command RTSP/1.0\r\n{extra}Content-Type: {PLIST_CONTENT_TYPE}\r\nContent-Length: {}\r\nCSeq: {}\r\n\r\n",
            body.len(),
            event.cseq
        );
        let bytes = [head.as_bytes(), &body].concat();
        let sealed = event.cipher.as_mut().unwrap().encrypt(&bytes)?;
        socket.send(&sealed)?;
        Ok(true)
    }
    pub fn send_remote_control_message(&self, stream_id: u64, message: &V) -> Result<bool> {
        self.send_command_headers(
            &dictionary([(
                "params",
                dictionary([("data", V::Data(BplistCodec::encode(message)?))]),
            )]),
            &format!("X-Apple-StreamID: {stream_id}\r\n"),
        )
    }
    pub fn send_touch(&self, contacts: &[AirPlayContact]) -> Result<bool> {
        let contacts: Vec<_> = contacts
            .iter()
            .map(|c| AirPlayContact {
                x: c.x * self.config.main.width_pixels as f64,
                y: c.y * self.config.main.height_pixels as f64,
                ..c.clone()
            })
            .collect();
        self.send_hid_report(
            crate::air_play_hid::TOUCH_HID_UID,
            AirPlayHid::touch_report(&contacts)?,
        )
    }
    fn send_hid_report(&self, uid: u64, report: Vec<u8>) -> Result<bool> {
        self.send_command(&dictionary([
            ("type", "hidSendReport".into()),
            ("uuid", format!("{uid:x}").into()),
            ("hidReport", V::Data(report)),
        ]))
    }
    pub fn send_knob(&self, state: &AirPlayKnobState, momentary: bool) -> Result<()> {
        self.send_hid_report(
            crate::air_play_hid::KNOB_HID_UID,
            AirPlayHid::knob_report(state),
        )?;
        if momentary {
            self.send_hid_report(
                crate::air_play_hid::KNOB_HID_UID,
                AirPlayHid::knob_report(&AirPlayKnobState::default()),
            )?;
        }
        Ok(())
    }
    pub fn send_media(&self, index: u8) -> Result<()> {
        self.send_hid_report(
            crate::air_play_hid::MEDIA_HID_UID,
            AirPlayHid::media_report(index),
        )?;
        self.send_hid_report(
            crate::air_play_hid::MEDIA_HID_UID,
            AirPlayHid::media_report(0),
        )?;
        Ok(())
    }
    pub fn send_telephony(&self, index: u8) -> Result<()> {
        self.send_hid_report(
            crate::air_play_hid::TELEPHONY_HID_UID,
            AirPlayHid::telephony_report(index),
        )?;
        self.send_hid_report(
            crate::air_play_hid::TELEPHONY_HID_UID,
            AirPlayHid::telephony_report(0),
        )?;
        Ok(())
    }
    pub fn invoke_siri(&self) -> Result<()> {
        for action in [2u64, 3] {
            self.send_command(&dictionary([
                ("type", "requestSiri".into()),
                ("params", dictionary([("siriAction", action.into())])),
            ]))?;
        }
        Ok(())
    }
    pub fn send_iap_message(&self, data: &[u8], timeout: Duration) -> Result<bool> {
        let command = dictionary([
            ("type", "iAPSendMessage".into()),
            ("params", dictionary([("data", V::Data(data.to_vec()))])),
        ]);
        let start = Instant::now();
        loop {
            let ready = {
                let e = self.event.lock().unwrap();
                e.socket.is_some() && e.cipher.is_some()
            };
            if ready || timeout.is_zero() {
                return self.send_command(&command);
            }
            if self.is_closed() || start.elapsed() >= timeout {
                return Ok(false);
            }
            thread::sleep(Duration::from_millis(25).min(timeout.saturating_sub(start.elapsed())));
        }
    }
    pub fn set_night_mode(&self, night: bool) -> Result<bool> {
        self.event.lock().unwrap().pending_night_mode = Some(night);
        self.send_pending_night_mode()
    }
    fn send_pending_night_mode(&self) -> Result<bool> {
        let result = {
            let mut event = self.event.lock().unwrap();
            let Some(night) = event.pending_night_mode else {
                return Ok(true);
            };
            let sent = self.send_command_locked(
                &mut event,
                &dictionary([
                    ("type", "setNightMode".into()),
                    ("params", dictionary([("nightMode", night.into())])),
                ]),
                "",
            );
            if matches!(sent, Ok(true)) {
                event.pending_night_mode = None;
            }
            sent
        };
        match result {
            Ok(v) => Ok(v),
            Err(_) => {
                let _ = self.close();
                Ok(false)
            }
        }
    }
    fn run_control(self: Arc<Self>) {
        let result = (|| -> Result<()> {
            let mut encrypted = Vec::new();
            let mut accumulated = Vec::new();
            while !self.is_closed() {
                let chunk = match self
                    .socket
                    .recv(READ_CHUNK_BYTES as usize, Duration::from_millis(100))?
                {
                    Receive::Data(v) => v,
                    Receive::Timeout => continue,
                    Receive::Eof => {
                        self.log_debug("airplay control closing reason=peer EOF");
                        break;
                    }
                };
                let plaintext = {
                    let mut p = self.pairing.lock().unwrap();
                    if let Some(cipher) = &mut p.cipher {
                        encrypted.extend(chunk);
                        let decrypted = cipher.decrypt(&encrypted)?;
                        encrypted = decrypted.rest;
                        decrypted.data
                    } else {
                        chunk
                    }
                };
                accumulated.extend(plaintext);
                if accumulated.len() > 16 * 1024 * 1024 {
                    return Err(std::io::Error::other("airplay control message too large"));
                }
                let parsed = RtspMessage::parse_messages(&accumulated)?;
                accumulated = parsed.rest;
                for request in parsed.messages {
                    self.log_debug(&AirPlayControlDiagnostics::request(
                        &request.method,
                        &request.path,
                        request.body.len(),
                    ));
                    let reply = self
                        .handle_request(&request)
                        .unwrap_or_else(|_| response(500));
                    let wire = RtspMessage::build_response(&request, &reply)?;
                    let wire = {
                        let mut p = self.pairing.lock().unwrap();
                        if let Some(cipher) = &mut p.cipher {
                            cipher.encrypt(&wire)?
                        } else {
                            wire
                        }
                    };
                    self.socket.send(&wire)?;
                    {
                        let mut p = self.pairing.lock().unwrap();
                        if p.cipher.is_none() && p.verify.verified_controller_id().is_some() {
                            p.cipher = Some(p.verify.control_cipher()?);
                        }
                    }
                }
                if let Err(error) = self.media.on_setup_response_sent(&self) {
                    self.log_debug(&format!(
                        "airplay media setup response callback failed: {error}"
                    ));
                }
            }
            Ok(())
        })();
        if let Err(error) = result {
            if !self.is_closed() {
                self.listener
                    .on_transport_error(&format!("control I/O failed: {error}"));
            }
        }
        let _ = self.close();
    }
    /// Handle one complete request. The control worker sends the response before activating encryption.
    pub fn handle_request(&self, request: &RtspRequest) -> Result<RtspResponse> {
        match request.method.as_str() {
            "SETUP" => return self.handle_setup(request),
            "RECORD" => {
                if let Some(this) = self.own.upgrade() {
                    self.listener.on_session_active(this);
                }
                return Ok(response(200));
            }
            "TEARDOWN" => return self.handle_teardown(request),
            _ => {}
        }
        if self.config.buffered_audio_output_enabled()
            && ["SETRATE", "SETRATEANCHORTIME", "GETANCHOR", "FLUSHBUFFERED"]
                .contains(&request.method.as_str())
        {
            let body = if request.body.is_empty() && request.method == "GETANCHOR" {
                empty()
            } else {
                match BplistCodec::decode(&request.body) {
                    Ok(v @ V::Dictionary(_)) => v,
                    _ => return Ok(response(400)),
                }
            };
            return match self
                .media
                .on_buffered_audio_control(self, &request.method, &body)?
            {
                Some(body) => plist_response(&body),
                None => Ok(response(200)),
            };
        }
        let path = request.path.to_lowercase();
        if path.ends_with("/pair-setup") {
            let body = self.pairing.lock().unwrap().setup.handle(&request.body)?;
            return Ok(bytes_response(PAIRING_CONTENT_TYPE, body));
        }
        if path.ends_with("/pair-verify") {
            let body = self.pairing.lock().unwrap().verify.handle(&request.body)?;
            return Ok(bytes_response(PAIRING_CONTENT_TYPE, body));
        }
        if path.ends_with("/auth-setup") {
            return Ok(
                match MfiSapAuthSetup::handle(&request.body, self.mfi.as_ref())? {
                    Some(body) => bytes_response(OCTET_CONTENT_TYPE, body),
                    None => response(400),
                },
            );
        }
        if path.ends_with("/info") {
            return plist_response(&AirPlayInfoPlist::build(&self.config)?);
        }
        if request.method == "POST" && path.ends_with("/command") {
            return self.handle_command(request);
        }
        if request.method == "POST" && path.ends_with("/feedback") {
            return match self.media.on_feedback(self)? {
                Some(body) => plist_response(&body),
                None => Ok(response(200)),
            };
        }
        Ok(response(200))
    }
    fn handle_setup(&self, request: &RtspRequest) -> Result<RtspResponse> {
        if self.is_closed() {
            return Ok(response(400));
        }
        let body = match BplistCodec::decode(&request.body) {
            Ok(v @ V::Dictionary(_)) => v,
            _ => return Ok(response(400)),
        };
        if let Some(streams) = body.get("streams").and_then(V::as_array) {
            return plist_response(&dictionary([(
                "streams",
                V::Array(self.handle_streams(streams)?),
            )]));
        }
        let string = |k: &str| body.get(k).and_then(V::as_str).unwrap_or("").to_string();
        self.log_debug(&AirPlayPeerDiagnostics::summary(
            &string("model"),
            &string("osVersion"),
            &string("sourceVersion"),
        ));
        let info = AirPlayDeviceInfo {
            name: string("name"),
            device_id: string("deviceID"),
            wifi_mac: string("macAddress").to_lowercase(),
            model: string("model"),
        };
        if !info.name.is_empty() || !info.device_id.is_empty() || !info.wifi_mac.is_empty() {
            self.listener.on_device_info(self, &info);
        }
        let network =
            self.network.lock().unwrap().clone().ok_or_else(|| {
                std::io::Error::other("AirPlaySession transport is not configured")
            })?;
        let peer_port = body.get("timingPort").and_then(V::as_u64).unwrap_or(0) as u16;
        let timing = network
            .transport
            .bind_datagram(SocketAddr::new(network.peer, peer_port))?;
        let ntp = NtpClock::new(timing);
        let timing_port = ntp.listen()?;
        if peer_port > 0 {
            ntp.start()?;
        }
        let server = network.transport.listen(network.peer)?;
        let event_port = server.port()?;
        let previous = {
            let mut resources = self.resources.lock().unwrap();
            if self.is_closed() {
                drop(resources);
                let _ = ntp.close();
                let _ = server.close();
                return Ok(response(400));
            }
            std::mem::replace(
                &mut *resources,
                Resources {
                    ntp: Some(ntp),
                    event_server: Some(server.clone()),
                    keep_alive: None,
                },
            )
        };
        if let Some(old) = previous.ntp {
            let _ = old.close();
        }
        if let Some(old) = previous.event_server {
            let _ = old.close();
        }
        if let Some(old) = previous.keep_alive {
            let _ = old.close();
        }
        let this = self
            .own
            .upgrade()
            .ok_or_else(|| std::io::Error::other("Session closed"))?;
        self.spawn("airplay-event-accept", move || {
            if let Err(error) = this.accept_event(server) {
                if !this.is_closed() {
                    this.log_debug(&format!("airplay event accept failed: {error}"));
                    let _ = this.close();
                }
            }
        })?;
        let mut result = vec![
            ("timingPort".to_string(), V::from(timing_port as u64)),
            ("eventPort".to_string(), V::from(event_port as u64)),
        ];
        if body
            .get("keepAliveLowPower")
            .is_some_and(|v| matches!(v, V::Boolean(true)) || v.as_u64() == Some(1))
        {
            let socket = network
                .transport
                .bind_datagram(SocketAddr::new(network.peer, 0))?;
            let port = socket.local_port()?;
            {
                let mut resources = self.resources.lock().unwrap();
                if self.is_closed() {
                    drop(resources);
                    let _ = socket.close();
                    return Ok(response(400));
                }
                resources.keep_alive = Some(socket.clone());
            }
            let this = self.own.upgrade().unwrap();
            self.spawn("airplay-keepalive", move || {
                while !this.is_closed() {
                    if socket.recv(512, Duration::from_millis(100)).is_err() && this.is_closed() {
                        break;
                    }
                }
            })?;
            result.push(("keepAlivePort".into(), V::from(port as u64)));
        }
        result.push((
            "enabledFeatures".into(),
            V::Array(
                setup_enabled_features(&self.config, body.get("features").and_then(V::as_array))
                    .into_iter()
                    .map(V::from)
                    .collect(),
            ),
        ));
        plist_response(&V::Dictionary(result))
    }
    fn handle_streams(&self, streams: &[V]) -> Result<Vec<V>> {
        let mut result = Vec::new();
        for stream in streams {
            let Some(kind) = stream
                .get("type")
                .and_then(V::as_u64)
                .and_then(|k| u16::try_from(k).ok())
            else {
                continue;
            };
            let reply = match kind {
                110 => self.media.on_screen(self, kind, stream)?.map(|port| {
                    dictionary([
                        ("type", V::from(kind as u64)),
                        ("dataPort", V::from(port as u64)),
                    ])
                }),
                100..=102 => self.media.on_audio(self, kind, stream)?,
                103 if self.config.buffered_audio_output_enabled() => {
                    self.media.on_buffered_audio(self, stream)?
                }
                130 => self.media.on_data_stream(self, stream)?,
                _ => None,
            };
            if let Some(reply) = reply {
                if self.is_closed() {
                    self.media.on_teardown(self, kind)?;
                    continue;
                }
                {
                    let mut active = self.active_streams.lock().unwrap();
                    if !active.contains(&kind) {
                        active.push(kind);
                    }
                }
                if kind == 110 {
                    let mut token = self.main_screen_token.lock().unwrap();
                    *token = Some(
                        self.screen_generation
                            .fetch_add(1, Ordering::AcqRel)
                            .wrapping_add(1),
                    );
                }
                result.push(reply);
            }
        }
        Ok(result)
    }
    fn handle_teardown(&self, request: &RtspRequest) -> Result<RtspResponse> {
        let body = BplistCodec::decode(&request.body).ok();
        let types = teardown_stream_types(body.as_ref());
        if types.as_ref().is_none_or(|t| t.contains(&110)) {
            *self.main_screen_token.lock().unwrap() = None;
        }
        let removed = {
            let mut active = self.active_streams.lock().unwrap();
            match types {
                None => std::mem::take(&mut *active),
                Some(types) => types
                    .into_iter()
                    .filter_map(|kind| {
                        active
                            .iter()
                            .position(|value| *value == kind)
                            .map(|index| active.remove(index))
                    })
                    .collect(),
            }
        };
        for kind in removed {
            self.media.on_teardown(self, kind)?;
        }
        Ok(response(200))
    }
    fn handle_command(&self, request: &RtspRequest) -> Result<RtspResponse> {
        let body = BplistCodec::decode(&request.body).unwrap_or_else(|_| empty());
        let kind = body.get("type").and_then(V::as_str).unwrap_or("");
        let params = body.get("params").cloned().unwrap_or_else(empty);
        if let (Some(stream), Some(data)) = (
            request
                .headers
                .get("x-apple-streamid")
                .and_then(|v| v.parse::<u64>().ok()),
            params.get("data").and_then(V::as_data),
        ) {
            if let Ok(message @ V::Dictionary(_)) = BplistCodec::decode(data) {
                self.listener
                    .on_remote_control_message(self, stream, &message);
            }
            return Ok(response(200));
        }
        if kind == "requestUI" {
            self.listener.on_host_ui_requested(self);
        }
        self.listener.on_command(self, kind, &params);
        Ok(response(200))
    }
    fn accept_event(&self, server: Arc<dyn StreamListener>) -> Result<()> {
        while !self.is_closed() {
            let Some(socket) = server.accept(Duration::from_millis(100))? else {
                continue;
            };
            let Some(shared) = self.shared_secret() else {
                let _ = socket.close();
                return Err(std::io::Error::other(
                    "airplay event rejected: pair-verify shared secret unavailable",
                ));
            };
            let key = |info: &[u8]| -> Result<[u8; 32]> {
                Ok(
                    AirPlayCrypto::hkdf_sha512(&shared, b"Events-Salt", info, 32)?
                        .try_into()
                        .unwrap(),
                )
            };
            let cipher = ControlCipher::new(
                key(b"Events-Read-Encryption-Key")?,
                key(b"Events-Write-Encryption-Key")?,
            );
            {
                let mut event = self.event.lock().unwrap();
                if self.is_closed() {
                    let _ = socket.close();
                    return Ok(());
                }
                event.socket = Some(socket.clone());
                event.cipher = Some(cipher);
            }
            self.send_pending_night_mode()?;
            let result = self.run_event_read(&socket);
            let _ = socket.close();
            if !self.is_closed() {
                let _ = self.close();
            }
            return result;
        }
        Ok(())
    }
    fn run_event_read(&self, socket: &Arc<dyn DuplexStream>) -> Result<()> {
        let mut encrypted = Vec::new();
        let mut plaintext = Vec::new();
        let mut rejection_logged: Option<Instant> = None;
        while !self.is_closed() {
            match socket.recv(READ_CHUNK_BYTES as usize, Duration::from_millis(100))? {
                Receive::Timeout => continue,
                Receive::Eof => break,
                Receive::Data(v) => encrypted.extend(v),
            }
            let decrypted = {
                let mut event = self.event.lock().unwrap();
                let Some(cipher) = &mut event.cipher else {
                    break;
                };
                cipher.decrypt(&encrypted)?
            };
            encrypted = decrypted.rest;
            plaintext.extend(decrypted.data);
            if plaintext.len() > 16 * 1024 * 1024 {
                return Err(std::io::Error::other("airplay event message too large"));
            }
            let parsed = RtspMessage::parse_messages(&plaintext)?;
            plaintext = parsed.rest;
            for message in parsed.messages {
                if message.method.starts_with("RTSP/") || message.method.starts_with("HTTP/") {
                    // The iPhone's answer to one of our commands (e.g. forceKeyFrame). Only failures
                    // are logged, at most once a second because touch reports are commands too.
                    if message.path != "200"
                        && rejection_logged.is_none_or(|t| t.elapsed() >= Duration::from_secs(1))
                    {
                        rejection_logged = Some(Instant::now());
                        self.log_debug(&format!(
                            "airplay event command rejected status={} {} cseq={}",
                            message.path,
                            message.protocol,
                            message.headers.get("cseq").map_or("-", String::as_str)
                        ));
                    }
                    continue;
                }
                let wire = RtspMessage::build_response(&message, &response(200))?;
                let mut event = self.event.lock().unwrap();
                if let Some(cipher) = &mut event.cipher {
                    socket.send(&cipher.encrypt(&wire)?)?;
                }
            }
        }
        Ok(())
    }
}
impl AirPlaySession {
    pub fn show_view_area(&self, index: usize) -> bool {
        let count = self.config.main.view_areas.as_ref().map_or(0, Vec::len);
        index < count
            && self
                .send_command(&AirPlayInfoPlist::view_area_command(
                    index as u32,
                    count as u32,
                ))
                .unwrap_or(false)
    }
}
impl SessionControl for AirPlaySession {
    fn show_view_area(&self, index: usize) -> bool {
        AirPlaySession::show_view_area(self, index)
    }
    fn send_touch(&self, contacts: &[AirPlayContact]) -> Result<bool> {
        AirPlaySession::send_touch(self, contacts)
    }
}
pub fn setup_enabled_features(config: &AirPlayConfig, proposed: Option<&[V]>) -> Vec<String> {
    let mut features = Vec::new();
    if config.hevc {
        features.push("hevc".into());
    }
    features.extend(["iAPChannel".into(), "viewAreas".into()]);
    if config.buffered_audio_output_enabled()
        && proposed
            .unwrap_or_default()
            .iter()
            .any(|v| v.as_str() == Some(MAIN_BUFFERED_FEATURE))
    {
        features.push(MAIN_BUFFERED_FEATURE.into());
    }
    features
}
pub fn teardown_stream_types(body: Option<&V>) -> Option<Vec<u16>> {
    let entries: Vec<_> = body
        .and_then(|b| b.get("streams"))
        .and_then(V::as_array)
        .unwrap_or_default()
        .iter()
        .filter_map(|s| {
            Some((
                u16::try_from(s.get("type")?.as_u64()?).ok()?,
                s.get("streamID").and_then(V::as_u64),
            ))
        })
        .collect();
    if entries.is_empty() {
        None
    } else {
        Some(
            entries
                .into_iter()
                .filter(|(t, id)| *t != 130 || id.is_none() || *id == Some(1))
                .map(|(t, _)| t)
                .collect(),
        )
    }
}
/// Protocol message, field, or framing constant.
pub const TAG: &str = "xcertplay-usb";
/// Protocol message, field, or framing constant.
pub const PLIST_CONTENT_TYPE: &str = "application/x-apple-binary-plist";
/// Protocol message, field, or framing constant.
pub const PAIRING_CONTENT_TYPE: &str = "application/pairing+tlv8";
/// Protocol message, field, or framing constant.
pub const OCTET_CONTENT_TYPE: &str = "application/octet-stream";
/// Protocol message, field, or framing constant.
pub const STREAM_TYPE_MAIN_SCREEN: u64 = 110;
/// Protocol message, field, or framing constant.
pub const STREAM_TYPE_ALT_SCREEN: u64 = 111;
/// Protocol message, field, or framing constant.
pub const STREAM_TYPE_MAIN_AUDIO: u64 = 100;
/// Protocol message, field, or framing constant.
pub const STREAM_TYPE_ALT_AUDIO: u64 = 101;
/// Protocol message, field, or framing constant.
pub const STREAM_TYPE_MAIN_HIGH_AUDIO: u64 = 102;
/// Protocol message, field, or framing constant.
pub const STREAM_TYPE_DATA: u64 = 130;
/// Protocol message, field, or framing constant.
pub const STREAM_TYPE_MAIN_BUFFERED_AUDIO: u64 = 103;
/// Protocol message, field, or framing constant.
pub const ZOOM_DIRECTION_IN: u64 = 0;
/// Protocol message, field, or framing constant.
pub const ZOOM_DIRECTION_OUT: u64 = 1;
/// Protocol message, field, or framing constant.
pub const EVENT_READY_POLL_MILLIS: u64 = 25;
/// Protocol message, field, or framing constant.
pub const NANOS_PER_MILLISECOND: u64 = 1_000_000;
/// Protocol message, field, or framing constant.
pub const MAIN_BUFFERED_FEATURE: &str = "mainBuffered";

pub const READ_CHUNK_BYTES: u64 = 16384;

#[cfg(test)]
mod event_tests {
    use super::*;
    use carplay_iap2::mfi::BaaCertificatePair;
    use std::collections::VecDeque;
    #[derive(Default)]
    struct Socket {
        sent: Mutex<Vec<Vec<u8>>>,
        incoming: Mutex<VecDeque<Vec<u8>>>,
    }
    impl DuplexStream for Socket {
        fn send(&self, data: &[u8]) -> Result<()> {
            self.sent.lock().unwrap().push(data.to_vec());
            Ok(())
        }
        fn recv(&self, _: usize, _: Duration) -> Result<Receive> {
            Ok(self
                .incoming
                .lock()
                .unwrap()
                .pop_front()
                .map_or(Receive::Eof, Receive::Data))
        }
        fn close(&self) -> Result<()> {
            Ok(())
        }
    }
    struct Store;
    impl PairingStore for Store {
        fn save(&self, _: &str, _: &[u8; 32]) -> Result<()> {
            Ok(())
        }
        fn get(&self, _: &str) -> Result<Option<[u8; 32]>> {
            Ok(None)
        }
        fn clear(&self) -> Result<()> {
            Ok(())
        }
    }
    struct Auth;
    impl MfiAuthenticator for Auth {
        fn protocol_major(&self) -> Result<u8> {
            Ok(3)
        }
        fn read_certificate(&self, _: usize) -> Result<Vec<u8>> {
            Ok(vec![])
        }
        fn sign_challenge(&self, _: &[u8]) -> Result<Vec<u8>> {
            Ok(vec![])
        }
        fn baa_certificates(&self) -> Result<BaaCertificatePair> {
            Err(std::io::Error::other("unused"))
        }
    }
    #[derive(Default)]
    struct Listener(Mutex<Vec<String>>);
    impl AirPlaySessionListener for Listener {
        fn on_debug_log(&self, message: &str) {
            self.0.lock().unwrap().push(message.into());
        }
    }
    struct Media;
    impl AirPlayMediaHandler for Media {
        fn on_screen(&self, _: &AirPlaySession, _: u16, _: &V) -> Result<Option<u16>> {
            Ok(None)
        }
        fn on_audio(&self, _: &AirPlaySession, _: u16, _: &V) -> Result<Option<V>> {
            Ok(None)
        }
        fn on_buffered_audio(&self, _: &AirPlaySession, _: &V) -> Result<Option<V>> {
            Ok(None)
        }
        fn on_buffered_audio_control(
            &self,
            _: &AirPlaySession,
            _: &str,
            _: &V,
        ) -> Result<Option<V>> {
            Ok(None)
        }
        fn on_data_stream(&self, _: &AirPlaySession, _: &V) -> Result<Option<V>> {
            Ok(None)
        }
        fn on_feedback(&self, _: &AirPlaySession) -> Result<Option<V>> {
            Ok(None)
        }
        fn on_setup_response_sent(&self, _: &AirPlaySession) -> Result<()> {
            Ok(())
        }
        fn on_teardown(&self, _: &AirPlaySession, _: u16) -> Result<()> {
            Ok(())
        }
        fn on_session_closed(&self, _: &AirPlaySession) -> Result<()> {
            Ok(())
        }
    }
    fn session() -> (Arc<AirPlaySession>, Arc<Socket>) {
        let (session, socket, _) = logged_session();
        (session, socket)
    }
    fn logged_session() -> (Arc<AirPlaySession>, Arc<Socket>, Arc<Listener>) {
        configured_session(AirPlayConfig::linux_receiver("id".into(), "bt".into()))
    }
    fn configured_session(
        config: AirPlayConfig,
    ) -> (Arc<AirPlaySession>, Arc<Socket>, Arc<Listener>) {
        let listener = Arc::new(Listener::default());
        let key = AirPlayCrypto::ed25519_generate().unwrap();
        let session = AirPlaySession::new(
            Arc::new(Socket::default()),
            config,
            Arc::new(AirPlayIdentity {
                private_key: key.private_key,
                public_key: key.public_key,
                pairing_id: "accessory".into(),
            }),
            Arc::new(Store),
            Arc::new(Auth),
            Arc::new(Media),
            listener.clone(),
        )
        .unwrap();
        let socket = Arc::new(Socket::default());
        (session, socket, listener)
    }
    fn ready(session: &AirPlaySession, socket: &Arc<Socket>) {
        let mut event = session.event.lock().unwrap();
        event.socket = Some(socket.clone());
        event.cipher = Some(ControlCipher::new([1; 32], [2; 32]));
    }
    fn commands(socket: &Socket) -> Vec<V> {
        let mut client = ControlCipher::new([2; 32], [1; 32]);
        let wire = socket.sent.lock().unwrap().concat();
        let plain = client.decrypt(&wire).unwrap().data;
        RtspMessage::parse_messages(&plain)
            .unwrap()
            .messages
            .into_iter()
            .map(|m| {
                assert_eq!(m.method, "POST");
                assert_eq!(m.path, "/command");
                BplistCodec::decode(&m.body).unwrap()
            })
            .collect()
    }
    #[test]
    fn show_view_area_uses_the_encrypted_event_command() {
        let mut config = AirPlayConfig::linux_receiver("id".into(), "bt".into());
        config.main.view_areas = Some(vec![
            AirPlayViewArea {
                width: 1280,
                height: 720,
                origin_x: 0,
                origin_y: 0,
                dock_edge: None,
            },
            AirPlayViewArea {
                width: 960,
                height: 720,
                origin_x: 0,
                origin_y: 0,
                dock_edge: None,
            },
        ]);
        let (session, socket, _) = configured_session(config);
        assert!(!session.show_view_area(1));
        ready(&session, &socket);
        assert!(!session.show_view_area(2));
        assert!(SessionControl::show_view_area(&*session, 1));
        assert_eq!(
            commands(&socket),
            vec![AirPlayInfoPlist::view_area_command(1, 2)]
        );
        session.close().unwrap();
    }
    #[test]
    fn normalized_touch_and_key_releases_keep_event_order() {
        let (session, socket) = session();
        ready(&session, &socket);
        assert!(session
            .send_touch(&[AirPlayContact {
                id: 9,
                x: 0.5,
                y: 0.25,
                down: true
            }])
            .unwrap());
        session.send_media(3).unwrap();
        session.send_telephony(1).unwrap();
        session
            .send_knob(
                &AirPlayKnobState {
                    select: true,
                    wheel: 2,
                    ..Default::default()
                },
                true,
            )
            .unwrap();
        session.invoke_siri().unwrap();
        let cmds = commands(&socket);
        assert_eq!(cmds.len(), 9);
        assert_eq!(
            cmds[0].get("hidReport").unwrap().as_data().unwrap(),
            &[0, 1, 0x80, 2, 0xb4, 0, 1, 0, 0, 0, 0, 0]
        );
        assert_eq!(cmds[1].get("hidReport").unwrap().as_data().unwrap(), &[3]);
        assert_eq!(cmds[2].get("hidReport").unwrap().as_data().unwrap(), &[0]);
        assert_eq!(
            cmds[5].get("hidReport").unwrap().as_data().unwrap(),
            &[1, 0, 0, 2]
        );
        assert_eq!(
            cmds[6].get("hidReport").unwrap().as_data().unwrap(),
            &[0, 0, 0, 0]
        );
        for (cmd, action) in cmds[7..].iter().zip([2, 3]) {
            assert_eq!(cmd.get("type").and_then(V::as_str), Some("requestSiri"));
            assert_eq!(
                cmd.get("params")
                    .unwrap()
                    .get("siriAction")
                    .and_then(V::as_u64),
                Some(action)
            );
        }
        session.close().unwrap();
    }
    #[test]
    fn pending_night_mode_and_iap_survive_unready_event_channel() {
        let (session, socket) = session();
        assert!(!session.set_night_mode(true).unwrap());
        assert!(!session.set_night_mode(false).unwrap());
        ready(&session, &socket);
        assert!(session.send_pending_night_mode().unwrap());
        assert!(session
            .send_iap_message(&[0x40, 0x40, 0, 6], Duration::ZERO)
            .unwrap());
        let cmds = commands(&socket);
        assert_eq!(cmds.len(), 2);
        assert_eq!(
            cmds[0].get("params").unwrap().get("nightMode"),
            Some(&V::Boolean(false))
        );
        assert_eq!(
            cmds[1].get("type").and_then(V::as_str),
            Some("iAPSendMessage")
        );
        assert_eq!(
            cmds[1]
                .get("params")
                .unwrap()
                .get("data")
                .unwrap()
                .as_data()
                .unwrap(),
            &[0x40, 0x40, 0, 6]
        );
        session.close().unwrap();
    }
    #[test]
    fn event_reader_ignores_responses_and_acknowledges_peer_requests() {
        let (session, socket) = session();
        ready(&session, &socket);
        let mut peer = ControlCipher::new([2; 32], [1; 32]);
        let wire = peer
            .encrypt(b"RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\nPOST /event RTSP/1.0\r\nCSeq: 7\r\n\r\n")
            .unwrap();
        socket
            .incoming
            .lock()
            .unwrap()
            .extend([wire[..1].to_vec(), wire[1..].to_vec()]);
        let stream: Arc<dyn DuplexStream> = socket.clone();
        session.run_event_read(&stream).unwrap();
        let sent = socket.sent.lock().unwrap();
        assert_eq!(sent.len(), 1);
        let plaintext = peer.decrypt(&sent[0]).unwrap().data;
        assert!(plaintext.starts_with(b"RTSP/1.0 200 OK\r\n"));
        assert!(String::from_utf8(plaintext)
            .unwrap()
            .contains("CSeq: 7\r\n"));
        drop(sent);
        session.close().unwrap();
    }
    #[test]
    fn event_reader_logs_rejected_commands_once_a_second() {
        let (session, socket, listener) = logged_session();
        ready(&session, &socket);
        let mut peer = ControlCipher::new([2; 32], [1; 32]);
        let wire = peer
            .encrypt(
                b"RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n\
                  RTSP/1.0 400 Bad Request\r\nCSeq: 2\r\n\r\n\
                  RTSP/1.0 501 Not Implemented\r\nCSeq: 3\r\n\r\n",
            )
            .unwrap();
        socket.incoming.lock().unwrap().push_back(wire);
        let stream: Arc<dyn DuplexStream> = socket.clone();
        session.run_event_read(&stream).unwrap();
        let logs = listener.0.lock().unwrap();
        let rejected: Vec<_> = logs.iter().filter(|l| l.contains("rejected")).collect();
        assert_eq!(
            rejected,
            ["airplay event command rejected status=400 Bad Request cseq=2"]
        );
        assert!(socket.sent.lock().unwrap().is_empty());
        drop(logs);
        session.close().unwrap();
    }
}
