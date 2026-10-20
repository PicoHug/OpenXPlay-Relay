// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15

use crate::Result;
use crate::*;
use carplay_airplay::*;
use carplay_iap2::{mfi::MfiAuthenticator, DuplexStream, Receive};
use std::{
    io::{Read, Write},
    net::{IpAddr, Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream, UdpSocket},
    os::fd::AsRawFd,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};
#[derive(Clone)]
pub struct SessionMedia {
    pub engine: Arc<CarPlayMediaEngine>,
}
pub struct TcpDuplex {
    socket: TcpStream,
    read: Mutex<()>,
    write: Mutex<()>,
    closed: AtomicBool,
}
impl TcpDuplex {
    pub fn new(socket: TcpStream) -> std::io::Result<Self> {
        socket.set_nodelay(true)?;
        Ok(Self {
            socket,
            read: Mutex::new(()),
            write: Mutex::new(()),
            closed: AtomicBool::new(false),
        })
    }
}
impl DuplexStream for TcpDuplex {
    fn send(&self, data: &[u8]) -> Result<()> {
        let _g = self.write.lock().unwrap();
        self.socket
            .set_write_timeout(Some(Duration::from_secs(10)))?;
        (&self.socket).write_all(data)
    }
    fn recv(&self, max: usize, timeout: Duration) -> Result<Receive> {
        let _g = self.read.lock().unwrap();
        if self.closed.load(Ordering::Acquire) {
            return Ok(Receive::Eof);
        }
        self.socket
            .set_read_timeout(Some(timeout.max(Duration::from_millis(1))))?;
        let mut b = vec![0; max];
        match (&self.socket).read(&mut b) {
            Ok(0) => Ok(Receive::Eof),
            Ok(n) => {
                b.truncate(n);
                Ok(Receive::Data(b))
            }
            // An expired read timeout is EAGAIN on Linux; ETIMEDOUT means keepalive gave up on the peer.
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => Ok(Receive::Timeout),
            Err(e) => Err(e),
        }
    }
    fn close(&self) -> Result<()> {
        if !self.closed.swap(true, Ordering::AcqRel) {
            let _ = self.socket.shutdown(Shutdown::Both);
        }
        Ok(())
    }
}
/// Detect a vanished phone on the control connection without treating an idle CarPlay screen as a
/// failure, as DiPlay's TcpLiveness does: probe after 10 s idle, every 3 s, give up after 3 probes,
/// and abandon unacknowledged data after 20 s.
fn configure_peer_liveness(socket: &TcpStream) -> std::io::Result<()> {
    #[cfg(not(target_os = "linux"))]
    {
        let _ = socket;
        Err(std::io::Error::new(
            std::io::ErrorKind::Unsupported,
            "TCP keepalive tuning requires Linux",
        ))
    }
    #[cfg(target_os = "linux")]
    {
        for (level, name, value) in [
            (libc::SOL_SOCKET, libc::SO_KEEPALIVE, 1),
            (libc::IPPROTO_TCP, libc::TCP_KEEPIDLE, 10),
            (libc::IPPROTO_TCP, libc::TCP_KEEPINTVL, 3),
            (libc::IPPROTO_TCP, libc::TCP_KEEPCNT, 3),
            (libc::IPPROTO_TCP, libc::TCP_USER_TIMEOUT, 20_000),
        ] {
            let value: libc::c_int = value;
            let r = unsafe {
                libc::setsockopt(
                    socket.as_raw_fd(),
                    level,
                    name,
                    &value as *const _ as *const _,
                    std::mem::size_of_val(&value) as _,
                )
            };
            if r < 0 {
                return Err(std::io::Error::last_os_error());
            }
        }
        Ok(())
    }
}
pub struct LinuxStreamTransport {
    bind_address: IpAddr,
    peer: Option<IpAddr>,
}
impl LinuxStreamTransport {
    pub fn new(bind_address: IpAddr) -> Self {
        Self {
            bind_address,
            peer: None,
        }
    }
    pub fn for_peer(bind_address: IpAddr, peer: IpAddr) -> Self {
        Self {
            bind_address,
            peer: Some(peer),
        }
    }
}
struct TcpAcceptor {
    listener: TcpListener,
    peer: IpAddr,
    closed: AtomicBool,
}
impl StreamListener for TcpAcceptor {
    fn port(&self) -> Result<u16> {
        Ok(self.listener.local_addr()?.port())
    }
    fn accept(&self, timeout: Duration) -> Result<Option<Arc<dyn DuplexStream>>> {
        let start = Instant::now();
        while !self.closed.load(Ordering::Acquire) {
            match self.listener.accept() {
                Ok((s, p)) => {
                    if self.peer.is_unspecified() || p.ip() == self.peer {
                        return Ok(Some(Arc::new(TcpDuplex::new(s)?)));
                    }
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
                Err(e) => return Err(e),
            }
            if start.elapsed() >= timeout {
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        Ok(None)
    }
    fn close(&self) -> Result<()> {
        self.closed.store(true, Ordering::Release);
        Ok(())
    }
}
struct UdpTransport {
    socket: UdpSocket,
    peer: SocketAddr,
    read: Mutex<()>,
    closed: AtomicBool,
}
impl DatagramTransport for UdpTransport {
    fn set_receive_buffer_bytes(&self, bytes: usize) -> Result<()> {
        let n = bytes.min(i32::MAX as usize) as libc::c_int;
        let r = unsafe {
            libc::setsockopt(
                self.socket.as_raw_fd(),
                libc::SOL_SOCKET,
                libc::SO_RCVBUF,
                &n as *const _ as *const _,
                std::mem::size_of_val(&n) as _,
            )
        };
        if r < 0 {
            Err(std::io::Error::last_os_error())
        } else {
            Ok(())
        }
    }
    fn local_port(&self) -> Result<u16> {
        Ok(self.socket.local_addr()?.port())
    }
    fn recv(&self, max: usize, t: Duration) -> Result<Receive> {
        if self.closed.load(Ordering::Acquire) {
            return Ok(Receive::Eof);
        }
        Ok(match self.recv_from(max, t)? {
            Some((b, _)) => Receive::Data(b),
            None => Receive::Timeout,
        })
    }
    fn send(&self, b: &[u8]) -> Result<()> {
        self.send_to(b, self.peer)
    }
    fn send_to(&self, b: &[u8], peer: SocketAddr) -> Result<()> {
        if self.closed.load(Ordering::Acquire) {
            return Err(std::io::Error::other("Datagram closed"));
        }
        if !self.peer.ip().is_unspecified() && peer.ip() != self.peer.ip() {
            return Err(std::io::Error::other("Unexpected datagram peer"));
        }
        self.socket.send_to(b, peer)?;
        Ok(())
    }
    fn recv_from(&self, max: usize, t: Duration) -> Result<Option<(Vec<u8>, SocketAddr)>> {
        let _g = self.read.lock().unwrap();
        let start = Instant::now();
        while !self.closed.load(Ordering::Acquire) {
            let left = t.saturating_sub(start.elapsed());
            if left.is_zero() {
                break;
            }
            self.socket
                .set_read_timeout(Some(left.min(Duration::from_millis(100))))?;
            let mut b = vec![0; max];
            match self.socket.recv_from(&mut b) {
                Ok((n, p)) => {
                    if (self.peer.ip().is_unspecified() || p.ip() == self.peer.ip())
                        && (self.peer.port() == 0 || p.port() == self.peer.port())
                    {
                        b.truncate(n);
                        return Ok(Some((b, p)));
                    }
                }
                Err(e)
                    if matches!(
                        e.kind(),
                        std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                    ) => {}
                Err(e) => return Err(e),
            }
        }
        Ok(None)
    }
    fn close(&self) -> Result<()> {
        self.closed.store(true, Ordering::Release);
        Ok(())
    }
}
impl StreamTransport for LinuxStreamTransport {
    fn listen(&self, peer: IpAddr) -> Result<Arc<dyn StreamListener>> {
        let listener = TcpListener::bind((self.bind_address, 0))?;
        listener.set_nonblocking(true)?;
        Ok(Arc::new(TcpAcceptor {
            listener,
            peer: self.peer.unwrap_or(peer),
            closed: AtomicBool::new(false),
        }))
    }
    fn bind_datagram(&self, peer: SocketAddr) -> Result<Arc<dyn DatagramTransport>> {
        Ok(Arc::new(UdpTransport {
            socket: UdpSocket::bind((self.bind_address, 0))?,
            peer: SocketAddr::new(self.peer.unwrap_or(peer.ip()), peer.port()),
            read: Mutex::new(()),
            closed: AtomicBool::new(false),
        }))
    }
}
type MediaFactory = Arc<
    dyn Fn(Arc<dyn SessionControl>, Arc<dyn StreamTransport>) -> Arc<CarPlayMediaEngine>
        + Send
        + Sync,
>;
struct ServerState {
    config: AirPlayConfig,
    sessions: Vec<Arc<AirPlaySession>>,
    socket: Option<TcpListener>,
}
pub struct LinuxAirPlayServer {
    address: Ipv4Addr,
    state: Arc<Mutex<ServerState>>,
    identity: Arc<AirPlayIdentity>,
    pairings: Arc<dyn PairingStore>,
    mfi: Arc<dyn MfiAuthenticator>,
    media: Arc<dyn AirPlayMediaHandler>,
    listener: Arc<dyn AirPlaySessionListener>,
    transport: Arc<dyn StreamTransport>,
    closed: Arc<AtomicBool>,
    worker: Mutex<Option<std::thread::JoinHandle<()>>>,
    factory: Option<MediaFactory>,
}
impl LinuxAirPlayServer {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        address: Ipv4Addr,
        config: AirPlayConfig,
        identity: Arc<AirPlayIdentity>,
        pairings: Arc<dyn PairingStore>,
        mfi: Arc<dyn MfiAuthenticator>,
        media: Arc<dyn AirPlayMediaHandler>,
        listener: Arc<dyn AirPlaySessionListener>,
        transport: Arc<dyn StreamTransport>,
    ) -> Self {
        Self {
            address,
            state: Arc::new(Mutex::new(ServerState {
                config,
                sessions: Vec::new(),
                socket: None,
            })),
            identity,
            pairings,
            mfi,
            media,
            listener,
            transport,
            closed: Arc::new(AtomicBool::new(false)),
            worker: Mutex::new(None),
            factory: None,
        }
    }
    pub fn with_media_factory(mut self, factory: MediaFactory) -> Self {
        self.factory = Some(factory);
        self
    }
    pub fn start(&self) -> Result<()> {
        let mut state = self.state.lock().unwrap();
        if state.socket.is_some() || self.closed.load(Ordering::Acquire) {
            return Err(std::io::Error::other(
                "AirPlay server already started or closed",
            ));
        }
        let selected = Mutex::new(None);
        AirPlayPortSelector::select(state.config.port, &|port| {
            let socket = TcpListener::bind((self.address, port))?;
            let port = socket.local_addr()?.port();
            *selected.lock().unwrap() = Some(socket);
            Ok(port)
        })?;
        let socket = selected.into_inner().unwrap().unwrap();
        state.config.port = socket.local_addr()?.port();
        socket.set_nonblocking(true)?;
        state.socket = Some(socket.try_clone()?);
        drop(state);
        let (state, identity, pairings, mfi, media, listener, closed, factory, base_transport) = (
            self.state.clone(),
            self.identity.clone(),
            self.pairings.clone(),
            self.mfi.clone(),
            self.media.clone(),
            self.listener.clone(),
            self.closed.clone(),
            self.factory.clone(),
            self.transport.clone(),
        );
        *self.worker.lock().unwrap() = Some(crate::platform::spawn_worker(
            "airplay-accept",
            Box::new(move || {
                while !closed.load(Ordering::Acquire) {
                    match socket.accept() {
                        Ok((tcp, peer)) => {
                            // Local probes must not replace the phone's active session.
                            if peer.ip().is_loopback()
                                || crate::linux_hotspot::interface_addresses()
                                    .unwrap_or_default()
                                    .iter()
                                    .any(|(_, a, _)| IpAddr::V4(*a) == peer.ip())
                            {
                                let _ = tcp.shutdown(Shutdown::Both);
                                continue;
                            }
                            let liveness = configure_peer_liveness(&tcp);
                            let result = (|| -> Result<()> {
                                let control =
                                    Arc::new(DeferredControl(Mutex::new(std::sync::Weak::new())));
                                let transport: Arc<dyn StreamTransport> = Arc::new(PeerTransport {
                                    base: base_transport.clone(),
                                    peer: peer.ip(),
                                });
                                let engine = factory
                                    .as_ref()
                                    .map(|f| f(control.clone(), transport.clone()));
                                let handler: Arc<dyn AirPlayMediaHandler> = engine
                                    .as_ref()
                                    .map(|e| e.clone() as Arc<dyn AirPlayMediaHandler>)
                                    .unwrap_or_else(|| media.clone());
                                let session = AirPlaySession::new(
                                    Arc::new(TcpDuplex::new(tcp)?),
                                    state.lock().unwrap().config.clone(),
                                    identity.clone(),
                                    pairings.clone(),
                                    mfi.clone(),
                                    handler,
                                    listener.clone(),
                                )?;
                                session.log_debug(&match &liveness {
                                    Ok(()) => "TCP peer health enabled idle=10s interval=3s count=3 deadline=20000ms; no video-idle timeout".to_string(),
                                    Err(e) => format!("TCP peer health tuning unavailable: {e}"),
                                });
                                *control.0.lock().unwrap() = Arc::downgrade(&session);
                                session.configure_transport(transport, peer.ip())?;
                                if let Some(e) = engine {
                                    e.bind_session(&session, peer.ip());
                                }
                                let mut s = state.lock().unwrap();
                                s.sessions.retain(|x| !x.is_closed());
                                s.sessions.push(session.clone());
                                drop(s);
                                session.start()
                            })();
                            if let Err(e) = result {
                                listener.on_transport_error(&e.to_string());
                            }
                        }
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                            std::thread::sleep(Duration::from_millis(20))
                        }
                        Err(e) => {
                            if !closed.load(Ordering::Acquire) {
                                listener.on_transport_error(&e.to_string());
                            }
                            break;
                        }
                    }
                }
            }),
        )?);
        Ok(())
    }
    pub fn config(&self) -> AirPlayConfig {
        self.state.lock().unwrap().config.clone()
    }
    pub fn close(&self) -> Result<()> {
        self.closed.store(true, Ordering::Release);
        let sessions = {
            let mut s = self.state.lock().unwrap();
            s.socket = None;
            std::mem::take(&mut s.sessions)
        };
        for s in sessions {
            let _ = s.close();
        }
        if let Some(w) = self.worker.lock().unwrap().take() {
            let _ = w.join();
        }
        Ok(())
    }
}
struct DeferredControl(Mutex<std::sync::Weak<AirPlaySession>>);
impl SessionControl for DeferredControl {
    fn show_view_area(&self, index: usize) -> bool {
        self.0
            .lock()
            .unwrap()
            .upgrade()
            .is_some_and(|s| s.show_view_area(index))
    }
    fn send_touch(&self, c: &[AirPlayContact]) -> Result<bool> {
        match self.0.lock().unwrap().upgrade() {
            Some(s) => s.send_touch(c),
            None => Ok(false),
        }
    }
}

/// Even wildcard media listeners are restricted to the accepted control connection's peer.
struct PeerTransport {
    base: Arc<dyn StreamTransport>,
    peer: IpAddr,
}
impl StreamTransport for PeerTransport {
    fn listen(&self, _peer: IpAddr) -> Result<Arc<dyn StreamListener>> {
        self.base.listen(self.peer)
    }
    fn bind_datagram(&self, peer: SocketAddr) -> Result<Arc<dyn DatagramTransport>> {
        self.base
            .bind_datagram(SocketAddr::new(self.peer, peer.port()))
    }
}
