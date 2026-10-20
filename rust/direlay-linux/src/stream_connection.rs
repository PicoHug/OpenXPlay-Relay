// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15

use crate::Result;
use crate::*;
use std::{
    net::Shutdown,
    os::unix::net::UnixStream,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Condvar, Mutex,
    },
    time::{Duration, Instant},
};
pub struct StreamConnection {
    path: PathBuf,
    input: Arc<DiPlayInput>,
    events: Arc<JsonEvents>,
    closed: Arc<AtomicBool>,
    socket: Arc<Mutex<Option<UnixStream>>>,
    retry: Arc<(Mutex<RetryState>, Condvar)>,
    worker: Mutex<Option<std::thread::JoinHandle<()>>>,
}
#[derive(Default)]
struct RetryState {
    generation: u64,
    started: Option<Instant>,
}
impl RetryState {
    fn delay(&self, backoff: u64) -> Duration {
        if self.started.is_none() {
            Duration::from_millis(2000)
        } else if self
            .started
            .is_some_and(|s| s.elapsed() < Duration::from_secs(5))
        {
            Duration::from_millis(250)
        } else {
            Duration::from_millis(backoff)
        }
    }
}
impl StreamConnection {
    pub fn new(path: PathBuf, input: Arc<DiPlayInput>, events: Arc<JsonEvents>) -> Self {
        Self {
            path,
            input,
            events,
            closed: Arc::new(AtomicBool::new(false)),
            socket: Arc::new(Mutex::new(None)),
            worker: Mutex::new(None),
            retry: Arc::new((Mutex::new(RetryState::default()), Condvar::new())),
        }
    }
    pub fn receiver_started(&self) {
        let mut retry = self.retry.0.lock().unwrap();
        retry.generation = retry.generation.wrapping_add(1);
        retry.started = Some(Instant::now());
        self.retry.1.notify_all();
    }
    pub fn receiver_stopped(&self) {
        let mut retry = self.retry.0.lock().unwrap();
        retry.generation = retry.generation.wrapping_add(1);
        retry.started = None;
        self.retry.1.notify_all();
    }
    pub fn start(&self) -> Result<()> {
        let (path, input, events, closed, socket) = (
            self.path.clone(),
            self.input.clone(),
            self.events.clone(),
            self.closed.clone(),
            self.socket.clone(),
        );
        let retry = self.retry.clone();
        let mut worker = self.worker.lock().unwrap();
        if worker.is_some() {
            return Err(std::io::Error::other("Stream already started"));
        }
        *worker = Some(crate::platform::spawn_worker(
            "stream-reconnect",
            Box::new(move || {
                let mut delay = 2000;
                while !closed.load(Ordering::Acquire) {
                    let generation = retry.0.lock().unwrap().generation;
                    let opened = Instant::now();
                    let result = (|| -> Result<()> {
                        let mut stream = UnixStream::connect(&path)?;
                        stream.set_write_timeout(Some(Duration::from_secs(2)))?;
                        *socket.lock().unwrap() = Some(stream.try_clone()?);
                        oxp_wire::write(
                            &mut stream,
                            &oxp_wire::Message::hello("producer", "openxplay-relay", "relay")
                                .map_err(std::io::Error::other)?,
                        )
                        .map_err(std::io::Error::other)?;
                        let (tx, rx) = mpsc::sync_channel(128);
                        {
                            let mut s = input.state.lock().unwrap();
                            if let Some(settings) = s.declarations.get("display-settings") {
                                oxp_wire::write(&mut stream, settings)
                                    .map_err(std::io::Error::other)?;
                            }
                            oxp_wire::write(&mut stream,&oxp_wire::Message::Json(serde_json::json!({"type":"input-capabilities","codecs":["H264","H265"],"screens":[0],"touch":true,"keys":[],"microphone":true,"keyFrameRequests":true,"reconfigure":"RESTART_SESSION"}))).map_err(std::io::Error::other)?;
                            for (_, m) in s
                                .declarations
                                .iter()
                                .filter(|(key, _)| key.as_str() != "display-settings")
                            {
                                oxp_wire::write(&mut stream, m).map_err(std::io::Error::other)?;
                            }
                            s.output = Some(tx);
                        }
                        let mut reader = stream.try_clone()?;
                        let i = input.clone();
                        let failed = Arc::new(AtomicBool::new(false));
                        let fail = failed.clone();
                        let read_worker = crate::platform::spawn_worker(
                            "stream-input",
                            Box::new(move || {
                                while let Ok(Some(message)) = oxp_wire::read(&mut reader) {
                                    let _ = i.control(&message);
                                }
                                fail.store(true, Ordering::Release);
                            }),
                        )?;
                        let result = loop {
                            if closed.load(Ordering::Acquire) {
                                break Ok(());
                            }
                            if failed.load(Ordering::Acquire) {
                                break Err(std::io::Error::new(
                                    std::io::ErrorKind::UnexpectedEof,
                                    "Stream connection closed",
                                ));
                            }
                            if let Err(e) = input
                                .take_context()
                                .iter()
                                .try_for_each(|update| oxp_wire::write(&mut stream, update))
                            {
                                break Err(std::io::Error::other(e));
                            }
                            match rx.recv_timeout(Duration::from_millis(100)) {
                                Ok(m) => {
                                    if let Err(e) = oxp_wire::write(&mut stream, &m) {
                                        break Err(std::io::Error::other(e));
                                    }
                                }
                                Err(mpsc::RecvTimeoutError::Disconnected) => {
                                    break Err(std::io::Error::other(
                                        "Stream output queue overflow",
                                    ))
                                }
                                Err(mpsc::RecvTimeoutError::Timeout) => {}
                            }
                        };
                        let _ = stream.shutdown(Shutdown::Both);
                        let _ = read_worker.join();
                        input.clear_receiver_capabilities();
                        input.state.lock().unwrap().output = None;
                        socket.lock().unwrap().take();
                        result
                    })();
                    input.clear_receiver_capabilities();
                    input.state.lock().unwrap().output = None;
                    if let Some(s) = socket.lock().unwrap().take() {
                        let _ = s.shutdown(Shutdown::Both);
                    }
                    if let Err(e) = result {
                        events.emit(
                            "stream-disconnected",
                            &serde_json::json!({"reason":e.to_string()}),
                        );
                    }
                    if opened.elapsed() > Duration::from_secs(30) {
                        delay = 2000;
                    }
                    if closed.load(Ordering::Acquire) {
                        break;
                    }
                    let (lock, wake) = &*retry;
                    let state = lock.lock().unwrap();
                    if state.generation != generation {
                        delay = 2000;
                        continue;
                    }
                    let wait = state.delay(delay);
                    events.emit(
                        "stream-retry",
                        &serde_json::json!({"path":path,"retryMillis":wait.as_millis() as u64}),
                    );
                    let state = wake
                        .wait_timeout_while(state, wait, |s| {
                            s.generation == generation && !closed.load(Ordering::Acquire)
                        })
                        .unwrap()
                        .0;
                    if state.generation != generation {
                        delay = 2000;
                        continue;
                    }
                    if state.started.is_some() && wait != Duration::from_millis(250) {
                        delay = (delay * 2).min(30000);
                    }
                }
            }),
        )?);
        Ok(())
    }
    pub fn close(&self) -> Result<()> {
        self.closed.store(true, Ordering::Release);
        {
            let _state = self.retry.0.lock().unwrap();
            self.retry.1.notify_all();
        }
        if let Some(s) = self.socket.lock().unwrap().take() {
            let _ = s.shutdown(Shutdown::Both);
        }
        if let Some(w) = self.worker.lock().unwrap().take() {
            let _ = w.join();
        }
        Ok(())
    }
}
impl Drop for StreamConnection {
    fn drop(&mut self) {
        let _ = self.close();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn receiver_start_wakes_a_backed_off_connection_and_retries_quickly() {
        let (tx, rx) = mpsc::channel();
        let events = Arc::new(JsonEvents::new(Arc::new(move |line| {
            let _ = tx.send(line);
        })));
        let input = Arc::new(DiPlayInput::new(events.clone()));
        let connection =
            StreamConnection::new(PathBuf::from("/nonexistent/oxp-test.sock"), input, events);
        connection.start().unwrap();
        let next_retry = || loop {
            let line = rx.recv_timeout(Duration::from_secs(2)).unwrap();
            let event: serde_json::Value = serde_json::from_str(&line).unwrap();
            if event["event"] == "stream-retry" || event["type"] == "stream-retry" {
                break event;
            }
        };
        assert_eq!(next_retry()["retryMillis"], 2000);
        let started = Instant::now();
        connection.receiver_started();
        assert_eq!(next_retry()["retryMillis"], 250);
        assert!(started.elapsed() < Duration::from_secs(1));
        assert_eq!(next_retry()["retryMillis"], 250);
        connection.close().unwrap();
        assert_eq!(RetryState::default().delay(16000), Duration::from_secs(2));
        let retry = RetryState {
            generation: 1,
            started: Some(Instant::now() - Duration::from_secs(6)),
        };
        assert_eq!(retry.delay(16000), Duration::from_secs(16));
    }
}

#[cfg(test)]
mod context_wire_tests {
    use super::*;
    use std::os::unix::net::UnixListener;
    #[test]
    fn published_context_reaches_only_opted_in_consumer() {
        for opted_in in [false, true] {
            let path = std::env::temp_dir().join(format!(
                "oxp-ctx-{}-{}.sock",
                std::process::id(),
                opted_in
            ));
            let _ = std::fs::remove_file(&path);
            let listener = UnixListener::bind(&path).unwrap();
            let events = Arc::new(JsonEvents::new(Arc::new(|_| {})));
            let input = Arc::new(DiPlayInput::with_plugins(events.clone(), true));
            let connection = StreamConnection::new(path.clone(), input.clone(), events);
            connection.start().unwrap();
            let (mut peer, _) = listener.accept().unwrap();
            peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            for _ in 0..4 {
                oxp_wire::read(&mut peer).unwrap().unwrap();
            }
            let client = if opted_in {
                oxp_wire::context_client("fake")
            } else {
                "legacy".into()
            };
            oxp_wire::write(
                &mut peer,
                &oxp_wire::Message::hello("consumer", "fake", &client).unwrap(),
            )
            .unwrap();
            input.session_context(true);
            // Media item title, nested in parameter group 0, encoded directly from the catalog.
            input.iap2_context(&carplay_iap2::Iap2Frame {
                message_id: 0x5001,
                payload: vec![0, 13, 0, 0, 0, 9, 0, 1, b'S', b'o', b'n', b'g', 0],
            });
            if opted_in {
                let mut found = false;
                for _ in 0..8 {
                    let Some(oxp_wire::Message::Json(value)) = oxp_wire::read(&mut peer).unwrap()
                    else {
                        panic!()
                    };
                    if value["topic"] == "nowPlaying" && value["value"]["title"] == "Song" {
                        found = true;
                        break;
                    }
                }
                assert!(found);
            } else {
                peer.set_read_timeout(Some(Duration::from_millis(400)))
                    .unwrap();
                assert!(oxp_wire::read(&mut peer).is_err());
            }
            connection.close().unwrap();
            std::fs::remove_file(path).unwrap();
        }
    }
}

#[cfg(test)]
mod display_wire_tests {
    use super::*;
    use oxp_wire::Message;
    use serde_json::json;
    use std::os::unix::net::UnixListener;
    #[test]
    fn fake_consumer_updates_display_settings_and_reconnect_replays_them() {
        let dir = std::env::temp_dir().join(format!("oxp-display-wire-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("stream.sock");
        let listener = UnixListener::bind(&path).unwrap();
        let events = Arc::new(JsonEvents::new(Arc::new(|_| {})));
        let input = Arc::new(DiPlayInput::new(events.clone()));
        input.initialize_video_state(&dir);
        let connection = StreamConnection::new(path, input.clone(), events);
        connection.receiver_started();
        connection.start().unwrap();
        let request = json!({"type":"display-settings","resolution":"native","screenWidth":125,"videoCodec":"h265"});
        for round in 0..2 {
            let (mut peer, _) = listener.accept().unwrap();
            peer.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            let Some(Message::Json(hello)) = oxp_wire::read(&mut peer).unwrap() else {
                panic!()
            };
            assert_eq!(hello["type"], "hello");
            let mut settings = None;
            let mut area = None;
            for _ in 0..3 {
                let Some(Message::Json(v)) = oxp_wire::read(&mut peer).unwrap() else {
                    panic!()
                };
                if v["type"] == "display-settings" {
                    settings = Some(v);
                } else if v["type"] == "view-area" {
                    area = Some(v);
                }
            }
            assert!(area.unwrap()["area"].is_null());
            if round == 0 {
                assert_eq!(settings.unwrap()["videoCodec"], "auto");
                let invalid = Message::Json(
                    json!({"type":"display-settings","resolution":"bad","screenWidth":125,"videoCodec":"h265"}),
                );
                assert!(input.control(&invalid).is_err());
                oxp_wire::write(
                    &mut peer,
                    &Message::hello("consumer", "fake", "test").unwrap(),
                )
                .unwrap();
                oxp_wire::write(&mut peer, &Message::Json(request.clone())).unwrap();
                let Some(Message::Json(reply)) = oxp_wire::read(&mut peer).unwrap() else {
                    panic!()
                };
                assert_eq!(reply, request);
                let saved: serde_json::Value =
                    serde_json::from_slice(&std::fs::read(dir.join("display.json")).unwrap())
                        .unwrap();
                assert_eq!(saved["screenWidth"], 125);
                // These declarations survive an ordinary session begin/end as well.
                assert_eq!(input.display_settings().resolution, "native");
            } else {
                assert_eq!(settings.unwrap(), request);
            }
            let _ = peer.shutdown(Shutdown::Both);
        }
        connection.close().unwrap();
        std::fs::remove_dir_all(dir).unwrap();
    }
}
