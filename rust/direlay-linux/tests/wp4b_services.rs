// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15
// Contract tests for Linux services.
#![allow(dead_code, unused_imports)]
#[path = "../src/admin/mod.rs"]
mod admin;
#[path = "../src/branding.rs"]
mod branding;
#[path = "../src/mdns_host.rs"]
mod mdns_host;
#[path = "../src/provision.rs"]
mod provision;
use openxplay_relay::{boot_timing, platform, plugins, settings};
#[path = "../src/supervisor.rs"]
mod supervisor;
use admin::auth::*;
use admin::*;
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs, io,
    net::IpAddr,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};
static NEXT: AtomicUsize = AtomicUsize::new(0);
struct Temp(PathBuf);
impl Temp {
    fn new() -> Self {
        let p = std::env::temp_dir().join(format!(
            "direlay-wp4b-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&p).unwrap();
        Self(p)
    }
}
impl Drop for Temp {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
/// Admin auth in one fixed boot, also where `/proc` has no boot id.
fn auth(dir: &Path) -> AdminAuth {
    AdminAuth::for_boot(
        Box::new(FileAuthStore(dir.join("auth.json"))),
        "test-boot".into(),
    )
    .unwrap()
}
#[test]
fn pbkdf2_vectors() {
    let hex = |bytes: [u8; 32]| bytes.iter().map(|b| format!("{b:02x}")).collect::<String>();
    assert_eq!(
        hex(pbkdf2(b"password", b"salt", 1)),
        "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"
    );
    assert_eq!(
        hex(pbkdf2(b"password", b"salt", 2)),
        "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43"
    );
    assert_eq!(
        hex(pbkdf2(b"password", b"salt", 4096)),
        "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"
    );
}
#[test]
fn sessions_are_hashed_persistent_and_expire() {
    let dir = Temp::new();
    let mut a = auth(&dir.0);
    assert!(a.default_password());
    let token = a
        .login("10.78.0.2".parse().unwrap(), "openxplay", 100)
        .ok()
        .unwrap();
    assert_eq!(token.len(), 64);
    assert!(a.ready(&token, 101));
    assert!(!a.ready(&token, 100 + SESSION_SECONDS));
    assert!(
        !String::from_utf8(fs::read(dir.0.join("auth.json")).unwrap())
            .unwrap()
            .contains(&token)
    );
    let mut a = auth(&dir.0);
    assert!(a.ready(&token, 101));
    a.logout(&token).unwrap();
    assert!(!auth(&dir.0).ready(&token, 101));
    use std::os::unix::fs::PermissionsExt;
    assert_eq!(
        fs::metadata(dir.0.join("auth.json"))
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o600
    );
}
#[test]
fn sessions_survive_a_restart_but_not_a_reboot_or_an_older_wall_clock_file() {
    let dir = Temp::new();
    let path = dir.0.join("auth.json");
    let load = |boot: &str| {
        AdminAuth::for_boot(Box::new(FileAuthStore(path.clone())), boot.into()).unwrap()
    };
    let ip: IpAddr = "10.78.0.2".parse().unwrap();
    let mut a = load("boot-a");
    let token = a.login(ip, "openxplay", 100).ok().unwrap();
    // A restart in the same boot keeps the expiry, which is on that boot's monotonic clock.
    assert!(load("boot-a").ready(&token, 101));
    assert!(!load("boot-a").ready(&token, 100 + SESSION_SECONDS));
    // After a reboot the monotonic clock starts again and nobody knows how long the relay was off.
    assert!(!load("boot-b").ready(&token, 101));
    assert!(!load("").ready(&token, 101));
    // A new boot's login replaces the old boot's sessions.
    let mut b = load("boot-b");
    let next = b.login(ip, "openxplay", 5).ok().unwrap();
    assert!(load("boot-b").ready(&next, 6));
    assert!(!load("boot-b").ready(&token, 6));
    assert!(!load("boot-a").ready(&next, 6));
    // Earlier releases saved wall-clock expiries: they end, the password stays.
    b.change_password("openxplay", "longer-password")
        .ok()
        .unwrap();
    let mut saved: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
    let hash = saved["bootSessions"]["expiries"]
        .as_object()
        .unwrap()
        .keys()
        .next()
        .unwrap()
        .clone();
    let legacy = json!({"password": saved["password"].take(), "sessions": {hash: u64::MAX}});
    fs::write(&path, legacy.to_string()).unwrap();
    let mut c = load("boot-b");
    assert!(!c.ready(&next, 6));
    assert!(!c.default_password());
    assert!(c.login(ip, "longer-password", 7).is_ok());
}
#[cfg(target_os = "linux")]
#[test]
fn admin_auth_keeps_sessions_of_the_current_boot() {
    let dir = Temp::new();
    let store = || Box::new(FileAuthStore(dir.0.join("auth.json")));
    let token = AdminAuth::new(store())
        .unwrap()
        .login("10.78.0.2".parse().unwrap(), "openxplay", 100)
        .ok()
        .unwrap();
    assert!(AdminAuth::new(store()).unwrap().ready(&token, 101));
}
#[test]
fn password_change_validation_and_reset() {
    let dir = Temp::new();
    let mut a = auth(&dir.0);
    assert!(matches!(
        a.change_password("wrong", "12345678"),
        Err(PasswordError::Current)
    ));
    assert!(matches!(
        a.change_password("openxplay", "short"),
        Err(PasswordError::Format)
    ));
    a.change_password("openxplay", "longer-password")
        .ok()
        .unwrap();
    assert!(!a.default_password());
    let mut a = auth(&dir.0);
    assert!(matches!(
        a.login("127.0.0.1".parse().unwrap(), "openxplay", 1),
        Err(LoginError::Password)
    ));
    assert!(a
        .login("127.0.0.1".parse().unwrap(), "longer-password", 1)
        .is_ok());
    a.reset().unwrap();
    assert!(auth(&dir.0).default_password());
}
#[test]
fn login_rate_limit_is_per_address_and_resets() {
    let dir = Temp::new();
    let mut a = auth(&dir.0);
    let ip = "192.168.1.4".parse().unwrap();
    for _ in 0..5 {
        assert!(matches!(
            a.login(ip, "wrong", 10),
            Err(LoginError::Password)
        ));
    }
    assert!(matches!(
        a.login(ip, "openxplay", 11),
        Err(LoginError::RateLimit(59))
    ));
    assert!(a
        .login("192.168.1.5".parse().unwrap(), "openxplay", 11)
        .is_ok());
    assert!(a.login(ip, "openxplay", 70).is_ok());
}
#[test]
fn private_address_matrix() {
    for ip in [
        "10.78.0.2",
        "10.0.0.1",
        "172.16.0.1",
        "172.31.255.255",
        "192.168.68.2",
        "127.0.0.1",
        "169.254.8.9",
        "::1",
        "fc00::1",
        "fdff::1",
        "fe80::1",
        "febf::1",
        "::ffff:10.78.0.2",
    ] {
        assert!(private_address(ip.parse().unwrap()), "{ip}");
    }
    for ip in [
        "8.8.8.8",
        "172.15.255.255",
        "172.32.0.1",
        "192.169.0.1",
        "0.0.0.0",
        "224.0.0.1",
        "::",
        "2001:4860::1",
        "fec0::1",
        "::ffff:8.8.8.8",
    ] {
        assert!(!private_address(ip.parse().unwrap()), "{ip}");
    }
}
struct FakeAdmin;
impl RelayAdmin for FakeAdmin {
    fn status(&self) -> AdminStatus {
        serde_json::from_value(json!({"platform":"linux","name":"OpenXPlay Linux","version":"0.1.0","host":"openxplay-linux.local","adminUrl":"https://openxplay-linux.local:6975/","receiverUrls":["https://openxplay-linux.local:8443/"],"state":"idle","running":false,"autoStartPaused":false,"iphone":{"name":null,"reason":"none"},"network":{"mode":"shared-wifi","wifi":null},"output":"web","carlink":null,"notices":[],"wifiProvisioning":true})).unwrap()
    }
    fn settings(&self) -> AdminSettings {
        serde_json::from_value(json!({"schema":[{"id":"advanced","titleKey":"section.advanced","fields":[{"key":"wifiChannel","type":"number","labelKey":"field.wifiChannel","min":1,"max":14}]}],"values":{"wifiChannel":6,"plugins":false,"pluginsEnabled":[]},"installedPlugins":[{"id":"org.openxplay.pet","name":{"en":"Pet","zh-CN":"宠物"},"version":"1.0.0","license":"MIT AND CC-BY-NC-SA-4.0","icon":null,"nonCommercialAssets":true}]})).unwrap()
    }
    fn update(&self, values: Values) -> AdminUpdate {
        if values.get("wifiChannel") == Some(&json!(99)) {
            AdminUpdate::Errors(BTreeMap::from([(
                "wifiChannel".into(),
                "error.range".into(),
            )]))
        } else if values.is_empty() {
            AdminUpdate::AppliedNow
        } else {
            AdminUpdate::NextStart
        }
    }
    fn action(&self, name: &str) -> AdminResult {
        if name == "start" {
            Err("error.busy".into())
        } else {
            Ok(())
        }
    }
    fn phones(&self) -> Vec<AdminPhone> {
        vec![serde_json::from_value(json!({"id":"iphone","name":"iPhone","kind":"phone","connected":false,"likelyIphone":true,"selected":false})).unwrap()]
    }
    fn wifi_status(&self) -> WifiClient {
        WifiClient {
            ip: Some("10.78.0.1".into()),
            setup_hotspot: true,
            ..Default::default()
        }
    }
    fn wifi_networks(&self) -> Result<Vec<WifiNetwork>, String> {
        Ok(vec![WifiNetwork {
            ssid: "Home".into(),
            signal: 80,
            secure: true,
            known: false,
        }])
    }
    fn wifi_join(&self, _: &str, _: Option<&str>) -> AdminResult {
        Ok(())
    }
    fn receivers(&self) -> Vec<Value> {
        vec![
            json!({"client":"1","idleSeconds":0.2,"info":{"refreshHz":60},"stats":[{"renderedFps":59.9}]}),
        ]
    }
}
fn server(dir: &Path) -> AdminServer {
    fs::write(dir.join("index.html"), b"test home").unwrap();
    let mut config = AdminServerConfig::new(dir.join("state"), dir.into(), vec![]);
    config.bind = ([127, 0, 0, 1], 0).into();
    AdminServer::new(Arc::new(FakeAdmin), auth(&dir.join("auth")), config)
}
fn req(method: &str, path: &str, body: Value, token: &str) -> Request {
    let mut headers = BTreeMap::from([
        ("x-openxplay-admin".into(), "1".into()),
        ("content-type".into(), "application/json".into()),
    ]);
    if !token.is_empty() {
        headers.insert("cookie".into(), format!("oxp_admin={token}"));
    }
    Request {
        method: method.into(),
        path: path.into(),
        headers,
        body: serde_json::to_vec(&body).unwrap(),
        peer: "10.78.0.4".parse().unwrap(),
    }
}
fn login(server: &AdminServer) -> String {
    let r = server.route(
        &req("POST", "/api/v0/login", json!({"password":"openxplay"}), ""),
        1,
    );
    assert_eq!(r.status, 200);
    let cookie = &r.headers["Set-Cookie"];
    for flag in ["HttpOnly", "Secure", "SameSite=Strict", "Max-Age=2592000"] {
        assert!(cookie.contains(flag));
    }
    cookie
        .split(';')
        .next()
        .unwrap()
        .strip_prefix("oxp_admin=")
        .unwrap()
        .into()
}
fn value(r: Response) -> Value {
    serde_json::from_slice(&r.body).unwrap()
}
#[test]
fn routing_auth_csrf_and_errors() {
    let dir = Temp::new();
    let s = server(&dir.0);
    assert_eq!(
        s.route(&req("GET", "/api/v0/status", json!({}), ""), 1)
            .status,
        401
    );
    assert_eq!(
        s.route(
            &req("POST", "/api/v0/actions/pair-iphone", json!({}), ""),
            1
        )
        .status,
        401
    );
    let token = login(&s);
    assert_eq!(
        value(s.route(&req("GET", "/api/v0/session", json!({}), &token), 2)),
        json!({"state":"ready","defaultPassword":true})
    );
    let mut pairing = req("POST", "/api/v0/actions/pair-iphone", json!({}), &token);
    pairing.headers.remove("x-openxplay-admin");
    assert_eq!(s.route(&pairing, 2).status, 403);
    pairing
        .headers
        .insert("x-openxplay-admin".into(), "1".into());
    assert_eq!(s.route(&pairing, 2).status, 200);
    let mut r = req("POST", "/api/v0/actions/stop", json!({}), &token);
    r.headers.remove("x-openxplay-admin");
    assert_eq!(s.route(&r, 2).status, 403);
    r.headers.insert("x-openxplay-admin".into(), "1".into());
    r.headers.insert("content-type".into(), "text/plain".into());
    assert_eq!(s.route(&r, 2).status, 403);
    assert_eq!(
        s.route(&req("POST", "/api/v0/actions/start", json!({}), &token), 2)
            .status,
        409
    );
    assert_eq!(
        value(s.route(
            &req("PUT", "/api/v0/settings", json!({"wifiChannel":99}), &token),
            2
        )),
        json!({"errors":{"wifiChannel":"error.range"}})
    );
    assert_eq!(
        value(s.route(
            &req("PUT", "/api/v0/settings", json!({"wifiChannel":6}), &token),
            2
        ))["applied"],
        "next-start"
    );
    assert_eq!(
        s.route(
            &req(
                "POST",
                "/api/v0/password",
                json!({"current":"wrong","new":"12345678"}),
                &token
            ),
            2
        )
        .status,
        403
    );
    assert_eq!(
        s.route(
            &req(
                "POST",
                "/api/v0/password",
                json!({"current":"openxplay","new":"short"}),
                &token
            ),
            2
        )
        .status,
        422
    );
    assert_eq!(
        s.route(
            &req("POST", "/api/v0/actions/unknown", json!({}), &token),
            2
        )
        .status,
        404
    );
    assert_eq!(
        s.route(
            &req(
                "PUT",
                "/api/v0/wifi",
                json!({"ssid":"","password":""}),
                &token
            ),
            2
        )
        .status,
        422
    );
    assert_eq!(
        s.route(&req("POST", "/api/v0/logout", json!({}), &token), 2)
            .status,
        200
    );
    assert_eq!(
        s.route(&req("GET", "/api/v0/status", json!({}), &token), 2)
            .status,
        401
    );
}
#[test]
fn receiver_diagnostics_are_opt_in_and_pairing_can_be_cancelled() {
    let dir = Temp::new();
    let s = server(&dir.0);
    let t = login(&s);
    let get = |p| value(s.route(&req("GET", p, json!({}), &t), 2));
    let plain = get("/api/v0/status");
    assert!(plain.get("receivers").is_none());
    assert!(plain.get("bluetoothGuard").is_none());
    assert_eq!(plain["pairedDevices"], json!([]));
    assert!(plain["pairingRemaining"].is_null());
    assert!(get("/api/v0/status?diagnostics=0")
        .get("receivers")
        .is_none());
    let diagnostics = get("/api/v0/status?lang=en&diagnostics=1");
    assert_eq!(diagnostics["receivers"][0]["client"], "1");
    assert_eq!(diagnostics["receivers"][0]["stats"][0]["renderedFps"], 59.9);
    assert_eq!(
        s.route(
            &req("POST", "/api/v0/actions/cancel-pairing", json!({}), &t),
            2
        )
        .status,
        200
    );
}
#[test]
fn routing_contract_shapes() {
    let dir = Temp::new();
    let s = server(&dir.0);
    let t = login(&s);
    let get = |p| value(s.route(&req("GET", p, json!({}), &t), 2));
    let status = get("/api/v0/status");
    assert_eq!(status["wifiClient"]["setupHotspot"], true);
    assert_eq!(status["autoStartPaused"], false);
    assert!(status["receiverUrls"].is_array());
    assert_eq!(
        get("/api/v0/settings")["schema"][0]["fields"][0]["labelKey"],
        "field.wifiChannel"
    );
    let plugins = get("/api/v0/settings");
    assert_eq!(plugins["values"]["plugins"], false);
    assert_eq!(plugins["values"]["pluginsEnabled"], json!([]));
    assert_eq!(plugins["installedPlugins"][0]["name"]["zh-CN"], "宠物");
    assert_eq!(plugins["installedPlugins"][0]["nonCommercialAssets"], true);
    assert_eq!(get("/api/v0/phones")[0]["likelyIphone"], true);
    assert_eq!(
        get("/api/v0/wifi/networks")[0],
        json!({"ssid":"Home","signal":80,"secure":true,"known":false})
    );
}
#[test]
fn routing_429_and_body_limits() {
    let dir = Temp::new();
    let s = server(&dir.0);
    for _ in 0..5 {
        assert_eq!(
            s.route(
                &req("POST", "/api/v0/login", json!({"password":"bad"}), ""),
                1
            )
            .status,
            401
        );
    }
    let response = s.route(
        &req("POST", "/api/v0/login", json!({"password":"openxplay"}), ""),
        2,
    );
    assert_eq!(response.status, 429);
    assert_eq!(response.headers["Retry-After"], "59");
    let mut r = req("POST", "/api/v0/login", json!({}), "");
    r.body = vec![b'x'; admin::server::MAX_BODY + 1];
    assert_eq!(s.route(&r, 1).status, 413);
    r.body = b"[]".to_vec();
    assert_eq!(s.route(&r, 1).status, 400);
}
#[test]
fn static_files_and_traversal() {
    let dir = Temp::new();
    let s = server(&dir.0);
    let get = |p| s.route(&req("GET", p, json!({}), ""), 1);
    assert_eq!(get("/").body, b"test home");
    assert_eq!(get("/index.html?x=1").status, 200);
    for path in [
        "/../secret",
        "/%2e%2e/secret",
        "/a/../../secret",
        "/%00",
        "/a%5csecret",
    ] {
        assert_eq!(get(path).status, 403, "{path}");
    }
    use std::os::unix::fs::symlink;
    symlink("/etc/passwd", dir.0.join("escape")).unwrap();
    assert_eq!(get("/escape").status, 404);
    let mut r = req("GET", "/", json!({}), "");
    r.peer = "8.8.8.8".parse().unwrap();
    assert_eq!(s.route(&r, 1).status, 403);
}
#[test]
fn http_framing_limits_and_smuggling() {
    let peer = "127.0.0.1".parse().unwrap();
    let parse = |s: &[u8]| admin::server::read_request(&mut &s[..], peer);
    assert!(parse(b"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n").is_ok());
    for raw in [
        &b"POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\nContent-Length: 1\r\n\r\n"[..],
        &b"POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n"[..],
        &b"GET / HTTP/1.0\r\nHost: a\r\n\r\n"[..],
    ] {
        assert_eq!(parse(raw).err(), Some(400));
    }
    assert_eq!(
        parse(b"POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 16385\r\n\r\n").err(),
        Some(413)
    );
}
#[test]
fn tls_identity_persists_and_rotates_with_sans() {
    let dir = Temp::new();
    let ips = vec!["10.78.0.1".parse().unwrap()];
    admin::server::tls_config(&dir.0, &ips).unwrap();
    let path = dir.0.join("admin-tls.json");
    let first = fs::read(&path).unwrap();
    admin::server::tls_config(&dir.0, &ips).unwrap();
    assert_eq!(fs::read(&path).unwrap(), first);
    admin::server::tls_config(&dir.0, &["192.168.1.2".parse().unwrap()]).unwrap();
    assert_ne!(fs::read(&path).unwrap(), first);
    use std::os::unix::fs::PermissionsExt;
    assert_eq!(
        fs::metadata(path).unwrap().permissions().mode() & 0o777,
        0o600
    );
}
#[derive(Clone, Default)]
struct FakeNm {
    inner: Arc<Mutex<NmState>>,
}
#[derive(Default)]
struct NmState {
    calls: Vec<(Vec<String>, Duration)>,
    profiles: BTreeMap<String, String>,
    fail: Option<String>,
    scan_fails: bool,
    active_ssid: Option<String>,
}
impl provision::NetworkManagerCli for FakeNm {
    fn run(&self, args: &[String], timeout: Duration) -> io::Result<String> {
        let mut s = self.inner.lock().unwrap();
        s.calls.push((args.to_vec(), timeout));
        let position = |key: &str| args.iter().position(|a| a == key);
        let after = |key: &str| position(key).and_then(|i| args.get(i + 1)).cloned();
        if position("add").is_some() {
            // Like NetworkManager: the UUID is assigned on add and reported in the confirmation.
            assert!(
                position("connection.uuid").is_none(),
                "NetworkManager 1.30 rejects connection.uuid on add"
            );
            let uuid = format!("00000000-0000-4000-8000-{:012x}", s.calls.len());
            s.profiles.insert(uuid.clone(), after("ssid").unwrap());
            return Ok(format!("Connection 'oxp' ({uuid}) successfully added.\n"));
        }
        if position("show").is_some()
            && position("uuid").is_some()
            && !s.profiles.contains_key(&after("uuid").unwrap())
        {
            return Err(io::Error::other("unknown profile"));
        }
        if position("up").is_some() {
            let id = after("uuid").unwrap();
            if !s
                .profiles
                .get(&id)
                .is_some_and(|n| n.starts_with("OpenXPlay-Linux-Setup-"))
            {
                if let Some(fail) = &s.fail {
                    return Err(io::Error::other(fail.clone()));
                }
            }
        }
        if position("up").is_some() {
            s.active_ssid = s.profiles.get(&after("uuid").unwrap()).cloned();
        }
        if position("ACTIVE,SSID").is_some() {
            return Ok(s
                .active_ssid
                .as_ref()
                .map(|ssid| format!("yes:{}\n", ssid.replace('\\', "\\\\").replace(':', "\\:")))
                .unwrap_or_default());
        }
        if position("delete").is_some() {
            s.profiles.remove(&after("uuid").unwrap());
        }
        if position("IP4.ADDRESS").is_some() {
            return Ok("192.168.1.78/24\n".into());
        }
        if position("SSID,SIGNAL,SECURITY,IN-USE").is_some() {
            if s.scan_fails {
                return Err(io::Error::other("scan unavailable"));
            }
            return Ok("Home:82:WPA2:*\nCafe:50:--:\n".into());
        }
        Ok(String::new())
    }
}
fn provisioner(nm: FakeNm, path: &Path) -> provision::WifiProvisioner<FakeNm> {
    provision::WifiProvisioner::new(nm, "wlan0".into(), "02:11:22:33:ab:cd", path).unwrap()
}
#[test]
fn provisioning_without_config_creates_open_24ghz_ap() {
    let dir = Temp::new();
    let nm = FakeNm::default();
    let mut p = provisioner(nm.clone(), &dir.0);
    p.start().unwrap();
    assert_eq!(p.setup_ssid(), "OpenXPlay-Linux-Setup-ABCD");
    assert!(p.status().setup_hotspot);
    assert_eq!(p.status().ip.as_deref(), Some("10.78.0.1"));
    let state = nm.inner.lock().unwrap();
    let add = &state
        .calls
        .iter()
        .find(|(a, _)| a.contains(&"add".into()))
        .unwrap()
        .0;
    for (k, v) in [
        ("802-11-wireless.band", "bg"),
        ("802-11-wireless.channel", "6"),
        ("ipv4.addresses", "10.78.0.1/24"),
        ("ipv4.method", "shared"),
    ] {
        assert!(add.windows(2).any(|a| a == [k, v]));
    }
    assert!(!add.iter().any(|a| a.contains("security")));
}
#[test]
fn provisioning_join_success_ap_down_and_forget() {
    let dir = Temp::new();
    let nm = FakeNm::default();
    let mut p = provisioner(nm.clone(), &dir.0);
    p.start().unwrap();
    p.join("Home", "not a shell $(command)").unwrap();
    assert!(!p.status().setup_hotspot);
    assert_eq!(p.status().ssid.as_deref(), Some("Home"));
    assert_eq!(p.status().ip.as_deref(), Some("192.168.1.78"));
    assert!(
        p.scan()
            .unwrap()
            .iter()
            .find(|n| n.ssid == "Home")
            .unwrap()
            .known
    );
    assert_eq!(p.status().signal, Some(82));
    p.forget().unwrap();
    assert!(p.status().setup_hotspot);
    let state = nm.inner.lock().unwrap();
    assert!(state.calls.iter().any(|(a, _)| a.contains(&"down".into())));
    assert!(state
        .profiles
        .values()
        .all(|n| n.starts_with("OpenXPlay-Linux-Setup-")));
    assert!(state
        .calls
        .iter()
        .any(|(a, _)| a.contains(&"not a shell $(command)".into())));
}
#[test]
fn provisioning_join_failures_restore_ap_with_reason() {
    for (error, reason) in [
        ("Secrets were required, but not provided", "auth"),
        ("SSID not found", "notFound"),
        ("activation timed out", "timeout"),
    ] {
        let dir = Temp::new();
        let nm = FakeNm::default();
        let mut p = provisioner(nm.clone(), &dir.0);
        p.start().unwrap();
        nm.inner.lock().unwrap().fail = Some(error.into());
        assert!(p.join("Home", "bad-password").is_err());
        assert!(p.status().setup_hotspot);
        assert_eq!(p.notices(), [format!("wifi.joinFailed.{reason}")]);
        // Failed activation keeps the saved profile so a later retry can reuse it.
        assert!(nm
            .inner
            .lock()
            .unwrap()
            .profiles
            .values()
            .any(|n| n == "Home"));
    }
}
#[test]
fn provisioning_reboot_uses_owned_profile_with_45_second_deadline() {
    let dir = Temp::new();
    let nm = FakeNm::default();
    {
        let mut p = provisioner(nm.clone(), &dir.0);
        p.start().unwrap();
        p.join("Home", "password").unwrap();
    }
    nm.inner.lock().unwrap().fail = Some("timed out".into());
    let mut p = provisioner(nm.clone(), &dir.0);
    p.start().unwrap();
    assert!(p.status().setup_hotspot);
    let state = nm.inner.lock().unwrap();
    assert!(state
        .calls
        .iter()
        .filter(|(a, _)| a.contains(&"up".into()))
        .all(|(_, t)| *t == Duration::from_secs(45)));
}
#[test]
fn nmcli_escaping_dedupe_and_hidden_ssids() {
    let known = BTreeSet::from(["My:Home\\AP".into()]);
    let rows=provision::parse_networks("My\\:Home\\\\AP:20:WPA2:\nMy\\:Home\\\\AP:90:WPA2:*\nCafe:40:--:\n:80:WPA2:\nbroken:abc:WPA2:\n",&known);
    assert_eq!(rows.len(), 2);
    assert_eq!(
        rows[0],
        WifiNetwork {
            ssid: "My:Home\\AP".into(),
            signal: 90,
            secure: true,
            known: true
        }
    );
    assert!(!rows[1].secure);
}
#[test]
fn mdns_record_contract() {
    let ips = [
        "10.78.0.1".parse().unwrap(),
        "192.168.68.1".parse().unwrap(),
    ];
    let info = mdns_host::service_info(&ips, 6975, 8443).unwrap();
    assert_eq!(info.get_hostname(), "openxplay-linux.local.");
    assert_eq!(info.get_type(), "_openxplay._tcp.local.");
    assert_eq!(info.get_port(), 6975);
    assert_eq!(info.get_property_val_str("platform"), Some("linux"));
    assert_eq!(info.get_property_val_str("receiver"), Some("8443"));
    assert_eq!(
        info.get_property_val_str("version"),
        Some(env!("CARGO_PKG_VERSION"))
    );
    assert_eq!(
        info.get_addresses(),
        &ips.into_iter().map(std::net::IpAddr::V4).collect()
    );
    assert!(!info.is_addr_auto());
    assert!(mdns_host::service_info(&[], 6975, 8443).is_err());
}
fn supervisor_settings() -> supervisor::SupervisorSettings {
    supervisor::SupervisorSettings {
        binary: "oxp-relay".into(),
        socket: "/tmp/receiver.sock".into(),
        web_port: 8443,
        web_root: "/opt/openxplay/share/receiver".into(),
        plugins: None,
        state_dir: "/var/lib/openxplay".into(),
        addresses: vec!["192.168.1.78".parse().unwrap()],
        pairing: true,
        carlink: None,
    }
}
#[test]
fn supervisor_arguments_and_urls() {
    let settings = supervisor_settings();
    let args = settings.arguments();
    let strings: Vec<_> = args.iter().map(|s| s.to_str().unwrap()).collect();
    assert!(strings
        .windows(2)
        .any(|a| a == ["--tls-self-signed", "/var/lib/openxplay/receiver-tls"]));
    assert!(strings.windows(2).any(|a| a == ["--web-bind", "0.0.0.0"]));
    assert!(strings
        .windows(2)
        .any(|a| a == ["--tls-san", "openxplay-linux.local"]));
    assert!(strings
        .windows(2)
        .any(|a| a == ["--tls-san", "192.168.1.78"]));
    assert!(strings.contains(&"--pairing"));
    assert!(strings
        .windows(2)
        .any(|a| a == ["--relay-name", "OpenXPlay Linux"]));
    assert!(strings
        .windows(2)
        .any(|a| a == ["--relay-version", env!("CARGO_PKG_VERSION")]));
    assert_eq!(
        settings.receiver_urls("openxplay-linux.local"),
        [
            "https://openxplay-linux.local:8443/",
            "https://192.168.1.78:8443/"
        ]
    );
}
#[derive(Default)]
struct FakeFactory {
    spawns: AtomicUsize,
    stops: Arc<AtomicUsize>,
}
struct FakeChild(Arc<AtomicUsize>);
impl supervisor::RelayChild for FakeChild {
    fn exited(&mut self) -> io::Result<bool> {
        Ok(true)
    }
    fn stop(&mut self) -> io::Result<()> {
        self.0.fetch_add(1, Ordering::Relaxed);
        Ok(())
    }
}
impl supervisor::ChildFactory for FakeFactory {
    fn spawn(
        &self,
        _: &Path,
        _: &[std::ffi::OsString],
        events: supervisor::EventSink,
    ) -> io::Result<Box<dyn supervisor::RelayChild>> {
        self.spawns.fetch_add(1, Ordering::Relaxed);
        events(json!({"type":"fake-child"}));
        Ok(Box::new(FakeChild(self.stops.clone())))
    }
}
#[test]
fn supervisor_restarts_backs_off_and_stops() {
    let factory = Arc::new(FakeFactory::default());
    let events = Arc::new(Mutex::new(Vec::new()));
    let copy = events.clone();
    let mut s = supervisor::RelaySupervisor::new(
        supervisor_settings(),
        factory.clone(),
        Arc::new(move |e| copy.lock().unwrap().push(e)),
    );
    let policy = supervisor::RestartPolicy {
        initial: Duration::from_millis(5),
        maximum: Duration::from_millis(20),
        stable_after: Duration::from_secs(1),
        poll: Duration::from_millis(1),
    };
    s.set_restart_policy(policy);
    s.start().unwrap();
    let until = Instant::now() + Duration::from_secs(2);
    while factory.spawns.load(Ordering::Relaxed) < 3 && Instant::now() < until {
        thread::sleep(Duration::from_millis(2));
    }
    s.stop();
    let n = factory.spawns.load(Ordering::Relaxed);
    assert!(n >= 3);
    assert_eq!(factory.stops.load(Ordering::Relaxed), n);
    let events = events.lock().unwrap();
    let delays: Vec<_> = events
        .iter()
        .filter_map(|e| e.get("delayMillis").and_then(Value::as_u64))
        .collect();
    assert!(delays.starts_with(&[5, 10]));
    assert_eq!(
        supervisor::restart_delay(policy, 99),
        Duration::from_millis(20)
    );
    assert!(!s.running());
}
#[test]
fn supervisor_forwards_only_bounded_json_objects() {
    let events = Arc::new(Mutex::new(Vec::new()));
    let copy = events.clone();
    let sink: supervisor::EventSink = Arc::new(move |v| copy.lock().unwrap().push(v));
    let mut bytes = b"log text\n{\"type\":\"ready\"}\n[]\n".to_vec();
    bytes.extend(vec![b'x'; 70_000]);
    bytes.extend_from_slice(b"\n{\"type\":\"pairing-url\",\"url\":\"https://host/#k=123\"}");
    supervisor::forward_json_lines(&bytes[..], &sink).unwrap();
    let events = events.lock().unwrap();
    assert_eq!(events.len(), 2);
    assert_eq!(events[1]["type"], "pairing-url");
}
#[test]
#[ignore = "requires a host that permits loopback listen sockets"]
fn tls_server_serves_http_and_stops() {
    use std::io::{Read, Write};
    let dir = Temp::new();
    let server = Arc::new(server(&dir.0));
    let mut handle = server.start().unwrap();
    let saved: Value =
        serde_json::from_slice(&fs::read(dir.0.join("state/admin-tls.json")).unwrap()).unwrap();
    let bytes: Vec<u8> = saved["cert"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_u64().unwrap() as u8)
        .collect();
    let mut roots = rustls::RootCertStore::empty();
    roots
        .add(rustls::pki_types::CertificateDer::from(bytes))
        .unwrap();
    let config = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .unwrap()
    .with_root_certificates(roots)
    .with_no_client_auth();
    let socket = std::net::TcpStream::connect(handle.address).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(3)))
        .unwrap();
    let conn = rustls::ClientConnection::new(
        Arc::new(config),
        rustls::pki_types::ServerName::try_from("localhost").unwrap(),
    )
    .unwrap();
    let mut tls = rustls::StreamOwned::new(conn, socket);
    tls.write_all(b"GET /api/v0/session HTTP/1.1\r\nHost: localhost\r\n\r\n")
        .unwrap();
    let mut response = String::new();
    tls.read_to_string(&mut response).unwrap();
    assert!(response.starts_with("HTTP/1.1 200 OK\r\n"));
    assert!(response.contains("\"defaultPassword\":true"));
    handle.stop();
}
#[test]
fn provisioning_preserves_foreign_profiles() {
    let dir = Temp::new();
    let nm = FakeNm::default();
    nm.inner
        .lock()
        .unwrap()
        .profiles
        .insert("foreign-uuid".into(), "Do not touch".into());
    let mut p = provisioner(nm.clone(), &dir.0);
    p.start().unwrap();
    p.join("Home", "password").unwrap();
    p.forget().unwrap();
    let state = nm.inner.lock().unwrap();
    assert!(state.profiles.contains_key("foreign-uuid"));
    assert!(!state
        .calls
        .iter()
        .any(|(a, _)| a.contains(&"foreign-uuid".into())));
}
#[test]
fn nmcli_real_runner_uses_utf8_c_locale_and_argument_vectors() {
    use provision::NetworkManagerCli;
    use std::os::unix::fs::PermissionsExt;
    let dir = Temp::new();
    let path = dir.0.join("fake-nmcli");
    fs::write(&path, b"#!/bin/sh\nprintf '%s\\n' \"$LC_ALL\" \"$@\"\n").unwrap();
    fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
    let cli = provision::ProcessNetworkManagerCli { executable: path };
    let result = cli
        .run(
            &["-t".into(), "SSID with spaces;$(no shell)".into()],
            Duration::from_secs(1),
        )
        .unwrap();
    // C.UTF-8 keeps messages stable and non-ASCII SSIDs intact (C printed them as "?").
    assert_eq!(result, "C.UTF-8\n-t\nSSID with spaces;$(no shell)\n");
}
#[test]
fn supervisor_carlink_arguments() {
    let mut settings = supervisor_settings();
    settings.carlink = Some(supervisor::CarlinkSettings {
        ble: None,
        head_unit: "192.168.1.10".parse().unwrap(),
        local: "192.168.1.78".parse().unwrap(),
        pin: Some("123456".into()),
        ports: BTreeMap::from([("video".into(), 1234)]),
    });
    let args = settings.arguments();
    let text: Vec<_> = args.iter().map(|s| s.to_str().unwrap()).collect();
    for (key, value) in [
        ("--clink-head-unit", "192.168.1.10"),
        ("--clink-local", "192.168.1.78"),
        ("--pin", "123456"),
        ("--port", "video=1234"),
        ("--state-dir", "/var/lib/openxplay/carlink"),
    ] {
        assert!(text.windows(2).any(|a| a == [key, value]));
    }
}
struct PairingChild;
impl supervisor::RelayChild for PairingChild {
    fn exited(&mut self) -> io::Result<bool> {
        Ok(false)
    }
    fn stop(&mut self) -> io::Result<()> {
        Ok(())
    }
}
struct PairingFactory;
impl supervisor::ChildFactory for PairingFactory {
    fn spawn(
        &self,
        _: &Path,
        _: &[std::ffi::OsString],
        events: supervisor::EventSink,
    ) -> io::Result<Box<dyn supervisor::RelayChild>> {
        events(json!({"type":"pairing-url","url":"https://192.168.1.78:8443/#k=12345678"}));
        Ok(Box::new(PairingChild))
    }
}
#[test]
fn supervisor_receiver_urls_include_live_pairing_code() {
    let mut s = supervisor::RelaySupervisor::new(
        supervisor_settings(),
        Arc::new(PairingFactory),
        Arc::new(|_| {}),
    );
    s.start().unwrap();
    let until = Instant::now() + Duration::from_secs(1);
    while !s.running() && Instant::now() < until {
        thread::sleep(Duration::from_millis(1));
    }
    assert!(s
        .receiver_urls("openxplay-linux.local")
        .iter()
        .all(|url| url.ends_with("/#k=12345678")));
    s.stop();
    assert!(s
        .receiver_urls("openxplay-linux.local")
        .iter()
        .all(|url| !url.contains('#')));
}
#[test]
fn nmcli_scan_fixture_preserves_escaped_bssid() {
    // NetworkManager terse output with an escaped BSSID.
    let fixture = include_str!("fixtures/nmcli-wifi.txt");
    let fields = provision::terse_fields(fixture.lines().next().unwrap());
    assert_eq!(fields, ["*", "36", "DC:A6:32:11:22:33"]);
}
#[test]
fn tls_certificate_validates_and_transports_http_in_memory() {
    use std::io::{Cursor, Read, Write};
    let dir = Temp::new();
    let server = server(&dir.0);
    let config =
        admin::server::tls_config(&dir.0.join("tls"), &["10.78.0.1".parse().unwrap()]).unwrap();
    let saved: Value =
        serde_json::from_slice(&fs::read(dir.0.join("tls/admin-tls.json")).unwrap()).unwrap();
    let cert: Vec<u8> = saved["cert"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_u64().unwrap() as u8)
        .collect();
    let mut roots = rustls::RootCertStore::empty();
    roots
        .add(rustls::pki_types::CertificateDer::from(cert))
        .unwrap();
    let client_config = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .unwrap()
    .with_root_certificates(roots)
    .with_no_client_auth();
    let mut client = rustls::ClientConnection::new(
        Arc::new(client_config),
        rustls::pki_types::ServerName::try_from("openxplay-linux.local").unwrap(),
    )
    .unwrap();
    let mut tls = rustls::ServerConnection::new(config).unwrap();
    for _ in 0..10 {
        let mut outgoing = Vec::new();
        while client.wants_write() {
            client.write_tls(&mut outgoing).unwrap();
        }
        tls.read_tls(&mut Cursor::new(outgoing)).unwrap();
        tls.process_new_packets().unwrap();
        let mut incoming = Vec::new();
        while tls.wants_write() {
            tls.write_tls(&mut incoming).unwrap();
        }
        client.read_tls(&mut Cursor::new(incoming)).unwrap();
        client.process_new_packets().unwrap();
        if !client.is_handshaking() && !tls.is_handshaking() {
            break;
        }
    }
    assert!(!client.is_handshaking());
    assert!(!tls.is_handshaking());
    client
        .writer()
        .write_all(b"GET /api/v0/session HTTP/1.1\r\nHost: openxplay-linux.local\r\n\r\n")
        .unwrap();
    let mut outgoing = Vec::new();
    client.write_tls(&mut outgoing).unwrap();
    tls.read_tls(&mut Cursor::new(outgoing)).unwrap();
    tls.process_new_packets().unwrap();
    let request = admin::server::read_request(&mut tls.reader(), "10.78.0.2".parse().unwrap())
        .ok()
        .unwrap();
    assert_eq!(
        value(server.route(&request, 1)),
        json!({"state":"login","defaultPassword":true})
    );
}
#[test]
fn provisioning_can_switch_between_client_networks() {
    let dir = Temp::new();
    let nm = FakeNm::default();
    let mut p = provisioner(nm.clone(), &dir.0);
    p.start().unwrap();
    p.join("Home", "password").unwrap();
    let down_before = nm
        .inner
        .lock()
        .unwrap()
        .calls
        .iter()
        .filter(|(a, _)| a.contains(&"down".into()))
        .count();
    p.join("Cafe", "").unwrap();
    assert_eq!(p.status().ssid.as_deref(), Some("Cafe"));
    let down_after = nm
        .inner
        .lock()
        .unwrap()
        .calls
        .iter()
        .filter(|(a, _)| a.contains(&"down".into()))
        .count();
    assert_eq!(down_before, down_after);
    assert_eq!(
        provision::classify_join_error(&io::Error::other("The Wi-Fi network could not be found.")),
        "wifi.joinFailed.notFound"
    );
}

#[test]
fn supervisor_ble_carlink_arguments_exclude_static_and_saved_pin() {
    let mut settings = supervisor_settings();
    settings.carlink = Some(supervisor::CarlinkSettings {
        ble: Some(supervisor::BleCarlinkSettings {
            interface: "wlan1".into(),
            address: Some("AA:BB:CC:DD:EE:FF".into()),
            id: "stable-id".into(),
            band: 0,
        }),
        head_unit: "192.168.43.1".parse().unwrap(),
        local: "192.168.43.2".parse().unwrap(),
        pin: Some("1234".into()),
        ports: BTreeMap::new(),
    });
    let args = settings.arguments();
    let text: Vec<_> = args.iter().map(|s| s.to_str().unwrap()).collect();
    for (key, value) in [
        ("--clink-link", "ble"),
        ("--clink-interface", "wlan1"),
        ("--clink-name", branding::DEVICE_NAME),
        ("--clink-model", branding::MODEL),
        ("--clink-id", "stable-id"),
        ("--clink-band", "0"),
        ("--clink-address", "AA:BB:CC:DD:EE:FF"),
        ("--pin-file", "/var/lib/openxplay/carlink/pin"),
    ] {
        assert!(text.windows(2).any(|pair| pair == [key, value]), "{key}");
    }
    for key in ["--clink-head-unit", "--clink-local", "--pin"] {
        assert!(!text.contains(&key));
    }
    settings
        .carlink
        .as_mut()
        .unwrap()
        .ble
        .as_mut()
        .unwrap()
        .address = None;
    assert!(!settings.arguments().iter().any(|s| s == "--clink-address"));
}
#[test]
fn carlink_events_update_link_pin_deadline_and_receiver_state() {
    let mut status = AdminCarlink::default();
    status.apply_event(&json!({"type":"link","state":"scanning"}), 1);
    assert_eq!(status.state, "scanning");
    status.apply_event(&json!({"type":"link","state":"connecting","headUnit":{"address":"AA:BB:CC:DD:EE:FF","name":"Car"}}), 2);
    assert_eq!(
        status.head_unit.as_ref().unwrap().name.as_deref(),
        Some("Car")
    );
    status.apply_event(&json!({"type":"link","state":"joined","network":{"ssid":"DIRECT-Car","interface":"wlan0"}}), 3);
    assert_eq!(status.network.as_ref().unwrap().ssid, "DIRECT-Car");
    status.apply_event(&json!({"type":"state","state":"Authenticating"}), 4);
    assert_eq!(status.state, "authenticating");
    status.apply_event(&json!({"type":"pin-request","client":"unit"}), 5);
    assert!(status.pin_requested);
    assert_eq!(status.pin_expiry, Some(125));
    // Responses carry the seconds left and a display deadline on the wall clock, which may be
    // hours off; the expiry itself is on the monotonic clock.
    let shown = serde_json::to_value(status.clone().at(65, 1_000)).unwrap();
    assert_eq!(shown["pinRemaining"], 60);
    assert_eq!(shown["pinDeadline"], 1_060);
    assert!(shown.get("pinExpiry").is_none());
    let lapsed = status.clone().at(125, 1_000);
    assert!(!lapsed.pin_requested);
    assert_eq!((lapsed.pin_deadline, lapsed.pin_remaining), (None, None));
    assert_eq!(lapsed.detail, "carlink.pinTimeout");
    status.apply_event(&json!({"type":"receiver-started"}), 6);
    assert!(status.pin_requested);
    status.apply_event(&json!({"type":"pin-timeout","client":"unit"}), 125);
    assert!(!status.pin_requested);
    assert_eq!(status.pin_expiry, None);
    assert_eq!(status.detail, "carlink.pinTimeout");
    status.apply_event(&json!({"type":"pin-request","client":"unit"}), 130);
    status.apply_event(&json!({"type":"state","state":"Playing"}), 131);
    assert_eq!(status.state, "playing");
    assert!(!status.pin_requested);
    status.apply_event(
        &json!({"type":"link","state":"lost","reason":"Disconnected"}),
        132,
    );
    assert!(status.network.is_none());
    assert_eq!(status.detail, "Disconnected");
    status.apply_event(&json!({"type":"error","message":"Failed"}), 133);
    assert_eq!(status.state, "error");
    assert_eq!(status.detail, "Failed");
    status.apply_event(&json!({"type":"receiver-restarting"}), 134);
    assert_eq!(status.state, "waiting");
    assert!(status.head_unit.is_none());
}
#[test]
fn forget_carlink_route_uses_auth_and_action_dispatch() {
    let dir = Temp::new();
    let server = server(&dir.0);
    assert_eq!(
        server
            .route(
                &req("POST", "/api/actions/forget-carlink", json!({}), ""),
                1
            )
            .status,
        401
    );
    let token = login(&server);
    assert_eq!(
        server
            .route(
                &req("POST", "/api/v0/actions/forget-carlink", json!({}), &token),
                2
            )
            .status,
        200
    );
    assert_eq!(
        server
            .route(
                &req("POST", "/api/actions/forget-carlink", json!({}), &token),
                2
            )
            .status,
        200
    );
}

#[test]
fn carlink_retains_last_lost_reason_through_errors_restarts_and_discovery() {
    let mut status = AdminCarlink::default();
    status.apply_event(
        &serde_json::json!({
            "type": "link", "state": "lost", "reason": "head_unit_band_unsupported:5180"
        }),
        0,
    );
    assert_eq!(status.detail, "head_unit_band_unsupported:5180");
    for event in [
        serde_json::json!({"type":"state", "state":"Ended"}),
        serde_json::json!({"type":"error", "message":"link_connect_failed"}),
        serde_json::json!({"type":"receiver-restarting"}),
        serde_json::json!({"type":"link", "state":"scanning"}),
        serde_json::json!({"type":"link", "state":"connecting"}),
    ] {
        status.apply_event(&event, 0);
        assert_eq!(
            status.last_lost_reason.as_deref(),
            Some("head_unit_band_unsupported:5180")
        );
        assert_eq!(
            serde_json::to_value(&status).unwrap()["lastLostReason"],
            "head_unit_band_unsupported:5180"
        );
    }
    status.apply_event(
        &serde_json::json!({"type":"link", "state":"lost", "reason":"network_link_lost"}),
        0,
    );
    assert_eq!(
        status.last_lost_reason.as_deref(),
        Some("network_link_lost")
    );
    status.apply_event(&serde_json::json!({"type":"link", "state":"joined"}), 0);
    assert!(status.last_lost_reason.is_none());
}

#[test]
fn supervisor_trust_policy_and_single_user_attempt() {
    struct Child;
    impl supervisor::RelayChild for Child {
        fn exited(&mut self) -> io::Result<bool> {
            Ok(false)
        }
        fn stop(&mut self) -> io::Result<()> {
            Ok(())
        }
    }
    struct Factory(std::sync::mpsc::Sender<(Vec<std::ffi::OsString>, supervisor::EventSink)>);
    impl supervisor::ChildFactory for Factory {
        fn spawn(
            &self,
            _: &Path,
            args: &[std::ffi::OsString],
            events: supervisor::EventSink,
        ) -> io::Result<Box<dyn supervisor::RelayChild>> {
            self.0.send((args.to_vec(), events)).unwrap();
            Ok(Box::new(Child))
        }
    }
    let dir = std::env::temp_dir().join(format!(
        "carlink-c1-{}",
        admin::auth::random_hex(8).unwrap()
    ));
    std::fs::create_dir_all(dir.join("carlink")).unwrap();
    let mut settings = supervisor_settings();
    settings.state_dir = dir.clone();
    settings.carlink = Some(supervisor::CarlinkSettings {
        ble: Some(supervisor::BleCarlinkSettings {
            interface: "wlan0".into(),
            address: None,
            id: "relay".into(),
            band: 0,
        }),
        head_unit: "192.168.43.1".parse().unwrap(),
        local: "192.168.43.2".parse().unwrap(),
        pin: None,
        ports: BTreeMap::new(),
    });
    for value in [
        json!({"identity":{"public":"key"},"peers":{}}),
        json!({"peers":{"unit":""}}),
        json!({"peers":{"unit":"invalid"}}),
    ] {
        std::fs::write(
            dir.join("carlink/trust.json"),
            serde_json::to_vec(&value).unwrap(),
        )
        .unwrap();
        assert!(!supervisor::has_trusted_carlink(&dir));
        assert!(!settings
            .launch_arguments(false)
            .iter()
            .any(|arg| arg == "--clink-link"));
    }
    assert!(settings
        .launch_arguments(false)
        .iter()
        .any(|arg| arg == "--web-port"));
    assert!(settings
        .launch_arguments(true)
        .iter()
        .any(|arg| arg == "--pin-file"));
    let (tx, rx) = std::sync::mpsc::channel();
    let seen = Arc::new(Mutex::new(Vec::new()));
    let copy = seen.clone();
    let mut relay = supervisor::RelaySupervisor::new(
        settings.clone(),
        Arc::new(Factory(tx)),
        Arc::new(move |event| copy.lock().unwrap().push(event)),
    );
    relay.set_restart_policy(supervisor::RestartPolicy {
        initial: Duration::from_millis(1),
        maximum: Duration::from_millis(5),
        stable_after: Duration::from_secs(1),
        poll: Duration::from_millis(1),
    });
    relay.start().unwrap();
    let (args, _) = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    assert!(!args.iter().any(|arg| arg == "--clink-link"));
    relay.connect_carlink();
    let (args, events) = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    assert!(args.iter().any(|arg| arg == "--clink-link"));
    events(json!({"type":"link","state":"lost","reason":"failed"}));
    let (args, _) = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    assert!(!args.iter().any(|arg| arg == "--clink-link"));
    assert!(args.iter().any(|arg| arg == "--web-port"));
    assert!(seen
        .lock()
        .unwrap()
        .iter()
        .any(|event| event["type"] == "carlink-unpaired"));
    relay.stop();
    std::fs::write(
        dir.join("carlink/trust.json"),
        serde_json::to_vec(&json!({"peers":{"unit":format!("{}==", "A".repeat(86))}})).unwrap(),
    )
    .unwrap();
    assert!(supervisor::has_trusted_carlink(&dir));
    assert!(settings
        .launch_arguments(false)
        .iter()
        .any(|arg| arg == "--clink-link"));
    relay.start().unwrap();
    let (args, _) = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    assert!(args.iter().any(|arg| arg == "--clink-link"));
    relay.stop();
    // Carlink-only must remain idle without a trusted unit, then wake on Connect.
    std::fs::remove_file(dir.join("carlink/trust.json")).unwrap();
    settings.web_port = 0;
    let (tx, idle_rx) = std::sync::mpsc::channel();
    let idle_events = Arc::new(Mutex::new(Vec::new()));
    let copy = idle_events.clone();
    let mut idle = supervisor::RelaySupervisor::new(
        settings.clone(),
        Arc::new(Factory(tx)),
        Arc::new(move |v| copy.lock().unwrap().push(v)),
    );
    idle.start().unwrap();
    assert!(idle_rx.recv_timeout(Duration::from_millis(150)).is_err());
    assert!(!idle.running());
    assert_eq!(
        idle_events
            .lock()
            .unwrap()
            .iter()
            .filter(|v| v["type"] == "carlink-unpaired")
            .count(),
        1
    );
    idle.connect_carlink();
    let (args, sink) = idle_rx.recv_timeout(Duration::from_secs(2)).unwrap();
    assert!(args.iter().any(|a| a == "--clink-link"));
    sink(json!({"type":"error","message":"auth_rejected"}));
    assert!(idle_rx.recv_timeout(Duration::from_millis(700)).is_err());
    idle.stop();
    settings.carlink = None;
    assert!(!settings
        .launch_arguments(true)
        .iter()
        .any(|arg| arg == "--clink-link" || arg == "--web-port"));
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn sync_reconciles_owned_profiles_priorities_and_passwords_without_reconnecting() {
    use settings::SharedWifiNetwork;
    let dir = Temp::new();
    let nm = FakeNm::default();
    nm.inner
        .lock()
        .unwrap()
        .profiles
        .insert("foreign".into(), "Foreign".into());
    let mut p = provisioner(nm.clone(), &dir.0);
    let home = SharedWifiNetwork {
        ssid: "Home".into(),
        password: "password".into(),
    };
    let cafe = SharedWifiNetwork {
        ssid: "Cafe".into(),
        password: String::new(),
    };
    p.sync(&[home.clone(), cafe.clone()]).unwrap();
    let home_uuid = nm
        .inner
        .lock()
        .unwrap()
        .profiles
        .iter()
        .find(|(_, ssid)| *ssid == "Home")
        .unwrap()
        .0
        .clone();
    let calls = nm.inner.lock().unwrap().calls.len();
    p.sync(&[
        SharedWifiNetwork {
            password: "new-password".into(),
            ..home
        },
        cafe,
    ])
    .unwrap();
    let state = nm.inner.lock().unwrap();
    assert!(state.calls[calls..]
        .iter()
        .any(|(args, _)| args.contains(&"new-password".into()) && args.contains(&home_uuid)));
    assert!(!state
        .calls
        .iter()
        .any(|(args, _)| args.contains(&"up".into())));
    let priorities: Vec<_> = state
        .calls
        .iter()
        .filter_map(|(args, _)| {
            args.iter()
                .position(|a| a == "connection.autoconnect-priority")
                .map(|i| args[i + 1].as_str())
        })
        .collect();
    assert!(priorities.contains(&"10") && priorities.contains(&"9"));
    drop(state);
    p.sync(&[SharedWifiNetwork {
        ssid: "Home".into(),
        password: String::new(),
    }])
    .unwrap();
    assert!(nm
        .inner
        .lock()
        .unwrap()
        .calls
        .iter()
        .any(|(args, _)| args.contains(&"remove".into())
            && args.contains(&"802-11-wireless-security".into())
            && args.contains(&home_uuid)));
    p.sync(&[]).unwrap();
    let state = nm.inner.lock().unwrap();
    assert_eq!(
        state.profiles,
        BTreeMap::from([("foreign".into(), "Foreign".into())])
    );
    assert!(!state
        .calls
        .iter()
        .any(|(args, _)| args.contains(&"foreign".into())));
    assert!(state
        .calls
        .iter()
        .any(|(args, _)| args.contains(&"down".into()) && args.contains(&home_uuid)));
    assert!(state
        .calls
        .iter()
        .any(|(args, _)| args.contains(&"delete".into()) && args.contains(&home_uuid)));
}
#[test]
fn start_tries_in_range_saved_networks_by_signal_and_scan_failure_uses_list_order() {
    for scan_fails in [false, true] {
        let dir = Temp::new();
        let nm = FakeNm::default();
        let mut p = provisioner(nm.clone(), &dir.0);
        let networks = ["Cafe", "Absent", "Home"].map(|ssid| settings::SharedWifiNetwork {
            ssid: ssid.into(),
            password: String::new(),
        });
        p.sync(&networks).unwrap();
        nm.inner.lock().unwrap().fail = Some("activation timed out".into());
        nm.inner.lock().unwrap().scan_fails = scan_fails;
        p.start().unwrap();
        assert!(p.status().setup_hotspot);
        let state = nm.inner.lock().unwrap();
        let attempts: Vec<_> = state
            .calls
            .iter()
            .filter(|(args, _)| args.contains(&"up".into()))
            .filter_map(|(args, _)| {
                let uuid = &args[args.iter().position(|a| a == "uuid").unwrap() + 1];
                state
                    .profiles
                    .get(uuid)
                    .filter(|ssid| !ssid.starts_with("OpenXPlay-Linux-Setup-"))
                    .map(String::as_str)
            })
            .collect();
        assert_eq!(
            attempts,
            if scan_fails {
                vec!["Cafe", "Absent", "Home"]
            } else {
                vec!["Home", "Cafe"]
            }
        );
    }
}
#[test]
fn join_reuses_owned_profile_and_missing_profiles_are_recreated() {
    let dir = Temp::new();
    let nm = FakeNm::default();
    let mut p = provisioner(nm.clone(), &dir.0);
    p.join("Home", "password").unwrap();
    p.join("Home", "new-password").unwrap();
    let state = nm.inner.lock().unwrap();
    assert_eq!(
        state
            .calls
            .iter()
            .filter(|(args, _)| args.contains(&"add".into()))
            .count(),
        1
    );
    assert_eq!(state.profiles.len(), 1);
    assert!(state
        .calls
        .iter()
        .any(|(args, _)| args.contains(&"modify".into()) && args.contains(&"new-password".into())));
    drop(state);
    nm.inner.lock().unwrap().profiles.clear();
    p.sync(&[settings::SharedWifiNetwork {
        ssid: "Home".into(),
        password: "new-password".into(),
    }])
    .unwrap();
    assert_eq!(nm.inner.lock().unwrap().profiles.len(), 1);
}

#[test]
fn status_observes_networkmanager_autoconnect_instead_of_the_last_join() {
    let dir = Temp::new();
    let nm = FakeNm::default();
    let mut p = provisioner(nm.clone(), &dir.0);
    p.join("Home", "password").unwrap();
    nm.inner.lock().unwrap().active_ssid = Some("Car: Wi-Fi".into());
    assert_eq!(p.current_ssid().as_deref(), Some("Car: Wi-Fi"));
    assert_eq!(p.status().ssid.as_deref(), Some("Car: Wi-Fi"));
    nm.inner.lock().unwrap().active_ssid = None;
    assert_eq!(p.status().ssid, None);
    assert_eq!(p.status().ip, None);
}

#[test]
fn stale_pin_requires_a_request_in_the_same_attempt_and_survives_idle() {
    for message in ["auth_internal_error", "auth_rejected"] {
        let mut status = AdminCarlink::default();
        status.apply_event(&json!({"type":"error","message":message}), 0);
        assert_ne!(status.detail, "carlink.pinStale");
        status.apply_event(&json!({"type":"link","state":"scanning"}), 0);
        status.apply_event(&json!({"type":"pin-request"}), 0);
        status.clear_pin(); // The pre-armed code was supplied immediately.
        status.apply_event(&json!({"type":"state","state":"Negotiating"}), 0);
        status.apply_event(&json!({"type":"error","message":message}), 0);
        assert_eq!(status.detail, "carlink.pinStale");
        for event in [
            json!({"type":"link","state":"lost","reason":"network_link_lost"}),
            json!({"type":"receiver-restarting"}),
            json!({"type":"carlink-unpaired"}),
        ] {
            status.apply_event(&event, 0);
            assert_eq!(status.detail, "carlink.pinStale");
        }
        status.apply_event(&json!({"type":"link","state":"scanning"}), 0);
        status.apply_event(&json!({"type":"error","message":message}), 0);
        assert_ne!(status.detail, "carlink.pinStale");
    }
}

fn home() -> settings::SharedWifiNetwork {
    settings::SharedWifiNetwork {
        ssid: "Home".into(),
        password: "password".into(),
    }
}
#[test]
fn held_provisioner_cannot_change_or_scan_the_carlink_radio() {
    let dir = Temp::new();
    let nm = FakeNm::default();
    let mut p = provisioner(nm.clone(), &dir.0);
    p.sync(&[home()]).unwrap();
    let calls = || nm.inner.lock().unwrap().calls.len();
    let before = calls();
    p.hold_for_carlink(true);
    assert!(p.start().is_err());
    assert!(p.stop().is_err());
    assert!(p.join("Home", "password").is_err());
    assert!(p.sync(&[]).is_err());
    assert!(p.forget().is_err());
    assert!(p.stop_setup().is_err());
    assert!(p.scan().unwrap().is_empty());
    assert_eq!(calls(), before);
    p.hold_for_carlink(false);
    p.stop().unwrap();
    assert!(calls() > before);
}
#[test]
fn stopping_without_an_owned_client_profile_asks_networkmanager_nothing() {
    // At boot in device-hotspot mode the CarPlay AP waits for this step, and NetworkManager
    // answers only once it has set the Wi-Fi device up.
    let dir = Temp::new();
    let nm = FakeNm::default();
    let mut p = provisioner(nm.clone(), &dir.0);
    p.sync(&[]).unwrap();
    p.stop().unwrap();
    assert!(nm.inner.lock().unwrap().calls.is_empty());
    // With one, the active network is checked and that profile taken down.
    p.sync(&[home()]).unwrap();
    nm.inner.lock().unwrap().active_ssid = Some("Home".into());
    p.stop().unwrap();
    let s = nm.inner.lock().unwrap();
    assert!(s
        .calls
        .iter()
        .any(|(a, _)| a.iter().any(|x| x == "ACTIVE,SSID")));
    assert!(s.calls.last().unwrap().0.iter().any(|x| x == "down"));
}

#[test]
fn carlink_credentials_use_the_interfaces_active_uuid_and_hide_nm_errors() {
    struct Secrets {
        fail: bool,
    }
    impl provision::NetworkManagerCli for Secrets {
        fn run(&self, args: &[String], _: Duration) -> io::Result<String> {
            if args.iter().any(|s| s == "GENERAL.CON-UUID") {
                assert_eq!(args.last().unwrap(), "wlan0");
                return Ok("active-uuid\n".into());
            }
            assert_eq!(args.last().unwrap(), "active-uuid");
            if args.iter().any(|s| s == "802-11-wireless.ssid") {
                return Ok("DIRECT:Car\n".into());
            }
            assert!(args.iter().any(|s| s == "-s"));
            assert!(args.iter().any(|s| s == "802-11-wireless-security.psk"));
            if self.fail {
                Err(io::Error::other("private-car-password"))
            } else {
                Ok("private-car-password\n".into())
            }
        }
    }
    let dir = Temp::new();
    for fail in [false, true] {
        let p = provision::WifiProvisioner::new(
            Secrets { fail },
            "wlan0".into(),
            "AA:BB:CC:DD:EE:FF",
            &dir.0,
        )
        .unwrap();
        assert!(p.carlink_network("Different").is_err());
        let result = p.carlink_network("DIRECT:Car");
        if fail {
            assert!(!result
                .unwrap_err()
                .to_string()
                .contains("private-car-password"));
        } else {
            assert_eq!(result.unwrap().password, "private-car-password");
        }
    }
}

#[test]
fn plugin_arguments_off_golden_and_on_filtered_in_order() {
    let dir = Temp::new();
    let mut settings = supervisor_settings();
    settings.addresses.clear();
    settings.pairing = false;
    let expected: Vec<std::ffi::OsString> = [
        "--unix",
        "/tmp/receiver.sock",
        "--web-port",
        "8443",
        "--web-root",
        "/opt/openxplay/share/receiver",
        "--web-bind",
        "0.0.0.0",
        "--tls-self-signed",
        "/var/lib/openxplay/receiver-tls",
        "--tls-san",
        "openxplay-linux.local",
        "--relay-name",
        "OpenXPlay Linux",
        "--relay-version",
        env!("CARGO_PKG_VERSION"),
    ]
    .iter()
    .map(Into::into)
    .collect();
    assert_eq!(settings.arguments(), expected);
    let root = dir.0.join("packages");
    for id in ["org.test.first", "org.test.second"] {
        let package = root.join("p").join(id);
        fs::create_dir_all(&package).unwrap();
        fs::write(package.join("oxp-plugin.json"), serde_json::to_vec(&json!({
            "spec":"oxp-web-plugin/0", "id":id, "version":"1.0.0", "name":{"en":"Test"}, "license":"MIT"
        })).unwrap()).unwrap();
    }
    fs::create_dir_all(root.join("p/org.test.unreadable/oxp-plugin.json")).unwrap();
    settings.plugins = Some(plugins::OutputPlugins {
        root: root.clone(),
        enabled: vec!["org.missing".into(), "org.test.unreadable".into()],
    });
    assert_eq!(settings.arguments(), expected);
    settings.plugins.as_mut().unwrap().enabled = vec![
        "org.test.second".into(),
        "org.missing".into(),
        "org.test.first".into(),
    ];
    let mut enabled = expected.clone();
    enabled.extend([
        "--plugins-root".into(),
        root.into_os_string(),
        "--plugins-enabled".into(),
        "org.test.second,org.test.first".into(),
        "--plugins-state-dir".into(),
        "/var/lib/openxplay".into(),
    ]);
    assert_eq!(settings.arguments(), enabled);
    settings.plugins = None;
    assert_eq!(settings.arguments(), expected);
}
