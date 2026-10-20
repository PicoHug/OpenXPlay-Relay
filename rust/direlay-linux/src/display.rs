// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-19

use carplay_airplay::{AirPlayDisplayConfig, AirPlayViewArea};
use oxp_core::model::DisplaySettings;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    io::Write,
    path::PathBuf,
    time::{Duration, Instant},
};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Size {
    pub width: u32,
    pub height: u32,
}
impl Size {
    fn valid(self) -> bool {
        (1..=32768).contains(&self.width) && (1..=32768).contains(&self.height)
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Screen {
    pub width: u32,
    pub height: u32,
    pub density_dpi: u32,
}
impl Screen {
    fn valid(self) -> bool {
        Size {
            width: self.width,
            height: self.height,
        }
        .valid()
            && (1..=32768).contains(&self.density_dpi)
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HeadUnit {
    pub screen: Screen,
    pub viewport: Option<Size>,
    pub fullscreen: Option<bool>,
    pub fullscreen_preferred: Option<bool>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Record {
    pub screen: Screen,
    pub size: Size,
    pub fullscreen: bool,
    pub count: u64,
    pub last_seen: u64,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DisplayState {
    pub resolution: String,
    pub screen_width: u32,
    pub video_codec: String,
    pub head_unit: Option<HeadUnit>,
    pub canvas: Size,
    pub max_macroblocks: Option<u32>,
    pub records: Vec<Record>,
}
impl Default for DisplayState {
    fn default() -> Self {
        Self {
            resolution: "auto".into(),
            screen_width: 200,
            video_codec: "auto".into(),
            head_unit: None,
            canvas: Size {
                width: 1280,
                height: 720,
            },
            max_macroblocks: None,
            records: vec![],
        }
    }
}
impl DisplayState {
    pub fn settings(&self) -> DisplaySettings {
        DisplaySettings {
            resolution: self.resolution.clone(),
            screen_width: self.screen_width,
            video_codec: self.video_codec.clone(),
        }
    }
    pub fn set_settings(&mut self, s: &DisplaySettings) {
        self.resolution = s.resolution.clone();
        self.screen_width = s.screen_width;
        self.video_codec = s.video_codec.clone();
    }
    pub fn load(path: &std::path::Path) -> Self {
        let v = std::fs::read(path)
            .ok()
            .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
            .unwrap_or(Value::Null);
        let mut s = Self::default();
        if let Some(r) = v["resolution"]
            .as_str()
            .filter(|r| ["auto", "native", "1080p", "720p", "screen"].contains(r))
        {
            s.resolution = r.into();
        }
        if let Some(w) = v["screenWidth"]
            .as_u64()
            .filter(|w| [125, 200, 250, 300].contains(w))
        {
            s.screen_width = w as u32;
        }
        if let Some(c) = v["videoCodec"]
            .as_str()
            .filter(|c| ["auto", "h264", "h265"].contains(c))
        {
            s.video_codec = c.into();
        }
        s.head_unit = serde_json::from_value::<HeadUnit>(v["headUnit"].clone())
            .ok()
            .filter(|h| h.screen.valid() && h.viewport.is_none_or(Size::valid));
        if let Ok(c) = serde_json::from_value::<Size>(v["canvas"].clone()) {
            if c.valid()
                && c.width >= 320
                && c.height >= 240
                && c.width <= 3840
                && c.height <= 2160
                && c.width % 2 == 0
                && c.height % 2 == 0
            {
                s.canvas = c;
            }
        }
        s.max_macroblocks = v["maxMacroblocks"]
            .as_u64()
            .filter(|n| (1..=139264).contains(n))
            .map(|n| n as u32);
        if let Some(rows) = v["records"].as_array() {
            s.records = rows
                .iter()
                .take(200)
                .filter_map(|r| serde_json::from_value::<Record>(r.clone()).ok())
                .filter(|r| r.screen.valid() && r.size.valid() && r.count > 0)
                .collect();
        }
        s.trim();
        s
    }
    pub fn save(&self, path: &std::path::Path) -> std::io::Result<()> {
        std::fs::create_dir_all(path.parent().unwrap())?;
        let temporary = path.with_extension("json.tmp");
        let mut f = std::fs::File::create(&temporary)?;
        f.write_all(&serde_json::to_vec(self)?)?;
        f.sync_all()?;
        std::fs::rename(temporary, path)?;
        std::fs::File::open(path.parent().unwrap())?.sync_all()
    }
    fn trim(&mut self) {
        self.records.sort_by_key(|r| std::cmp::Reverse(r.last_seen));
        let mut units: Vec<(Screen, usize)> = vec![];
        let mut sizes = vec![];
        self.records.retain(|r| {
            if sizes.contains(&(r.screen, r.size, r.fullscreen)) {
                return false;
            }
            let index = match units.iter().position(|(s, _)| *s == r.screen) {
                Some(i) => i,
                None if units.len() < 10 => {
                    units.push((r.screen, 0));
                    units.len() - 1
                }
                _ => return false,
            };
            if units[index].1 >= 20 {
                return false;
            }
            units[index].1 += 1;
            sizes.push((r.screen, r.size, r.fullscreen));
            true
        });
    }
    pub fn observe(&mut self, h: HeadUnit, limit: Option<u32>, now: u64) -> bool {
        if self.head_unit.as_ref() == Some(&h) && self.max_macroblocks == limit {
            return false;
        }
        if let (Some(size), Some(fullscreen)) = (h.viewport, h.fullscreen) {
            if self.head_unit.as_ref().is_none_or(|old| {
                old.screen != h.screen
                    || old.viewport != h.viewport
                    || old.fullscreen != h.fullscreen
            }) {
                let index = self.records.iter().position(|r| {
                    r.screen == h.screen && r.size == size && r.fullscreen == fullscreen
                });
                let count = index
                    .map(|i| self.records.remove(i).count)
                    .unwrap_or(0)
                    .saturating_add(1);
                self.records.push(Record {
                    screen: h.screen,
                    size,
                    fullscreen,
                    count,
                    last_seen: now,
                });
                self.trim();
            }
        }
        self.head_unit = Some(h);
        self.max_macroblocks = limit;
        self.canvas = self.planned_canvas();
        true
    }
    pub fn planned_canvas(&self) -> Size {
        self.head_unit
            .as_ref()
            .map(|h| {
                fit_canvas(
                    Size {
                        width: h.screen.width,
                        height: h.screen.height,
                    },
                    &self.resolution,
                    self.max_macroblocks,
                )
            })
            .unwrap_or(self.canvas)
    }
}
pub fn macroblocks(s: Size) -> u32 {
    s.width.div_ceil(16) * s.height.div_ceil(16)
}
pub fn decoder_limit(codec: &str) -> Option<u32> {
    let (prefix, hex) = codec.split_once('.')?;
    if !["avc1", "avc3"].contains(&prefix)
        || hex.len() != 6
        || !hex.bytes().all(|c| c.is_ascii_hexdigit())
    {
        return None;
    }
    Some(match u8::from_str_radix(&hex[4..], 16).ok()? {
        0..=10 => 99,
        11 | 12 | 13 | 20 => 396,
        21 => 792,
        22 | 30 => 1620,
        31 => 3600,
        32 => 5120,
        40 | 41 => 8192,
        42 => 8704,
        50 => 22080,
        51 | 52 => 36864,
        _ => 139264,
    })
}
pub fn fit_canvas(mut size: Size, resolution: &str, limit: Option<u32>) -> Size {
    if !size.valid() {
        return Size {
            width: 1280,
            height: 720,
        };
    }
    let short_cap = match resolution {
        "720p" => 720.,
        "1080p" => 1080.,
        _ => f64::INFINITY,
    };
    let cap_scale = (short_cap / size.width.min(size.height) as f64).min(1.);
    size.width = (size.width as f64 * cap_scale).max(1.) as u32;
    size.height = (size.height as f64 * cap_scale).max(1.) as u32;
    let portrait = size.height > size.width;
    let native = resolution == "native";
    let (min_w, min_h) = if portrait { (360., 640.) } else { (640., 360.) };
    let (max_w, max_h) = if native {
        (3840., 2160.)
    } else if portrait {
        (1080., 1920.)
    } else {
        (1920., 1080.)
    };
    let lower = (min_w / size.width as f64).max(min_h / size.height as f64);
    let upper = (max_w / size.width as f64).min(max_h / size.height as f64);
    if lower > upper {
        return Size {
            width: 1280,
            height: 720,
        };
    }
    let scale = 1_f64.clamp(lower, upper);
    let even = |v: f64| {
        if native {
            (v / 2.).floor() as u32 * 2
        } else {
            (v / 2.).round() as u32 * 2
        }
    };
    let mut fitted = Size {
        width: even(size.width as f64 * scale),
        height: even(size.height as f64 * scale),
    };
    if size.height > size.width && fitted.height <= fitted.width {
        fitted.height = fitted.width + 2;
    }
    if size.width > size.height && fitted.width <= fitted.height {
        fitted.width = fitted.height + 2;
    }
    if let Some(limit) = limit.filter(|l| macroblocks(fitted) > *l) {
        let mut scale = (limit as f64 * 256. / (fitted.width as f64 * fitted.height as f64)).sqrt();
        loop {
            let s = Size {
                width: ((fitted.width as f64 * scale / 2.) as u32 * 2).max(320),
                height: ((fitted.height as f64 * scale / 2.) as u32 * 2).max(240),
            };
            if macroblocks(s) <= limit || scale < 0.1 {
                fitted = s;
                break;
            }
            scale *= 0.98;
        }
    }
    fitted
}
pub fn physical_width(canvas: Size, preset: u32) -> u32 {
    ((preset as f64 * (canvas.width as f64 / 1920.).max(1.) / 25.).ceil() as u32 * 25)
        .clamp(100, 400)
}
fn distance(a: Size, b: Size) -> f64 {
    ((a.width as f64 / a.height as f64) / (b.width as f64 / b.height as f64) - 1.).abs()
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Area {
    pub size: Size,
    pub fullscreen: bool,
    pub windowed: bool,
}
pub fn plan_areas(canvas: Size, head: Option<&HeadUnit>, records: &[Record]) -> Vec<Area> {
    let mut areas = vec![Area {
        size: canvas,
        fullscreen: true,
        windowed: true,
    }];
    let rows: Vec<_> = records
        .iter()
        .filter(|r| head.is_some_and(|h| h.screen == r.screen))
        .collect();
    for full in [true, false] {
        let mut candidates: Vec<_> = rows
            .iter()
            .copied()
            .filter(|r| r.fullscreen == full)
            .collect();
        candidates.sort_by_key(|r| (std::cmp::Reverse(r.count), std::cmp::Reverse(r.last_seen)));
        let mut sizes: Vec<_> = candidates.iter().map(|r| r.size).collect();
        if rows.is_empty() {
            if let Some(h) = head.filter(|h| h.fullscreen.is_none_or(|f| f == full)) {
                sizes.extend(h.viewport);
            }
        }
        let mut count = 0;
        for size in sizes {
            if !size.valid() || distance(size, canvas) <= 0.02 {
                continue;
            }
            let scale = (canvas.width as f64 / size.width as f64)
                .min(canvas.height as f64 / size.height as f64);
            let fitted = Size {
                width: ((size.width as f64 * scale / 2.) as u32).max(1) * 2,
                height: ((size.height as f64 * scale / 2.) as u32).max(1) * 2,
            };
            if let Some(a) = areas.iter_mut().find(|a| distance(a.size, fitted) <= 0.02) {
                if if full { a.fullscreen } else { a.windowed } {
                    continue;
                }
                if full {
                    a.fullscreen = true;
                } else {
                    a.windowed = true;
                }
            } else {
                areas.push(Area {
                    size: fitted,
                    fullscreen: full,
                    windowed: !full,
                });
            }
            count += 1;
            if count == 3 {
                break;
            }
        }
    }
    areas
}
pub fn match_area(areas: &[Area], head: &HeadUnit) -> Option<usize> {
    let size = head.viewport?;
    if head.fullscreen_preferred.is_some() && head.fullscreen != head.fullscreen_preferred {
        return None;
    }
    areas
        .iter()
        .enumerate()
        .filter(|(i, a)| {
            *i == 0
                || match head.fullscreen_preferred {
                    Some(true) => a.fullscreen,
                    Some(false) => a.windowed,
                    None => true,
                }
        })
        .map(|(i, a)| (i, distance(size, a.size)))
        .filter(|(_, d)| *d <= 0.05)
        .min_by(|a, b| a.1.total_cmp(&b.1))
        .map(|(i, _)| i)
}
#[derive(Default)]
pub struct SwitchDebounce {
    candidate: Option<usize>,
    since: Option<Instant>,
}
impl SwitchDebounce {
    pub fn observe(&mut self, index: Option<usize>, now: Instant) {
        if self.candidate != index {
            self.candidate = index;
            self.since = Some(now);
        }
    }
    pub fn ready(&self, now: Instant) -> Option<usize> {
        self.candidate.filter(|_| {
            self.since
                .is_some_and(|at| now.saturating_duration_since(at) >= Duration::from_millis(300))
        })
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
struct Target {
    canvas: Size,
    physical: u32,
    screen: Option<Screen>,
    settings: DisplaySettings,
}
#[derive(Default)]
pub(crate) struct DisplayRuntime {
    pub saved: DisplayState,
    pub path: Option<PathBuf>,
    applied: Option<Target>,
    pending: Option<(Target, Instant)>,
    last_restart: Option<Instant>,
    restart_pending: bool,
    pub settings_since: Option<Instant>,
    pub areas: Vec<Area>,
    pub active: usize,
    pub connected: bool,
    switch: SwitchDebounce,
}
impl DisplayRuntime {
    fn target(&self) -> Target {
        let canvas = self.saved.planned_canvas();
        Target {
            canvas,
            physical: physical_width(canvas, self.saved.screen_width),
            screen: self.saved.head_unit.as_ref().map(|h| h.screen),
            settings: self.saved.settings(),
        }
    }
    pub fn persist(&self) -> std::io::Result<()> {
        if let Some(path) = &self.path {
            self.saved.save(path)?;
        }
        Ok(())
    }
    pub fn freeze(&mut self) {
        let target = self.target();
        self.saved.canvas = target.canvas;
        self.areas = plan_areas(
            target.canvas,
            self.saved.head_unit.as_ref(),
            &self.saved.records,
        );
        self.applied = Some(target);
        self.active = 0;
        self.pending = None;
        self.restart_pending = false;
        self.settings_since = None;
        self.switch = SwitchDebounce::default();
    }
    pub fn due(&mut self, now: Instant) -> bool {
        let desired = self.target();
        if self.applied.as_ref() == Some(&desired) {
            self.pending = None;
            self.settings_since = None;
            return false;
        }
        if self.restart_pending || self.applied.is_none() {
            return false;
        }
        if self.pending.as_ref().is_none_or(|(t, _)| *t != desired) {
            self.pending = Some((desired, self.settings_since.unwrap_or(now)));
        }
        let ready = self
            .pending
            .as_ref()
            .is_some_and(|(_, at)| now.saturating_duration_since(*at) >= Duration::from_secs(1));
        if ready
            && (self.settings_since.is_some()
                || self
                    .last_restart
                    .is_none_or(|t| now.saturating_duration_since(t) >= Duration::from_secs(10)))
        {
            self.last_restart = Some(now);
            self.restart_pending = true;
            return true;
        }
        false
    }
    pub fn waiting_settings(&self) -> bool {
        (self.settings_since.is_some() || self.pending.is_some()) && !self.restart_pending
    }
    pub fn config(&self, config: &mut AirPlayDisplayConfig) {
        let Some(t) = &self.applied else {
            return;
        };
        config.width_pixels = t.canvas.width;
        config.height_pixels = t.canvas.height;
        config.width_physical_mm = Some(t.physical);
        config.view_areas = Some(
            self.areas
                .iter()
                .map(|a| AirPlayViewArea {
                    width: a.size.width,
                    height: a.size.height,
                    origin_x: 0,
                    origin_y: 0,
                    dock_edge: None,
                })
                .collect(),
        );
        config.initial_view_area = 0;
    }
    pub fn area_message(&self) -> oxp_wire::Message {
        let area = if self.active == 0 {
            Value::Null
        } else {
            let size = self.areas[self.active].size;
            let canvas = self.areas[0].size;
            json!({"x":0,"y":0,"width":size.width,"height":size.height,"canvasWidth":canvas.width,"canvasHeight":canvas.height})
        };
        oxp_wire::Message::Json(json!({"type":"view-area","screen":0,"area":area}))
    }
    pub fn switch_due(&mut self, now: Instant) -> Option<usize> {
        let candidate = self
            .connected
            .then_some(())
            .and(self.saved.head_unit.as_ref())
            .and_then(|h| match_area(&self.areas, h))
            .filter(|i| *i != self.active);
        self.switch.observe(candidate, now);
        self.switch.ready(now)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn size(width: u32, height: u32) -> Size {
        Size { width, height }
    }
    fn head() -> HeadUnit {
        HeadUnit {
            screen: Screen {
                width: 2401,
                height: 1201,
                density_dpi: 280,
            },
            viewport: Some(size(1533, 1014)),
            fullscreen: Some(true),
            fullscreen_preferred: Some(true),
        }
    }
    fn record(s: Size, full: bool, count: u64) -> Record {
        Record {
            screen: head().screen,
            size: s,
            fullscreen: full,
            count,
            last_seen: 1,
        }
    }
    #[test]
    fn canvas_and_physical_width_tables() {
        for (resolution, expected) in [
            ("auto", size(1920, 960)),
            ("native", size(2400, 1200)),
            ("1080p", size(1920, 960)),
            ("720p", size(1440, 720)),
            ("screen", size(1920, 960)),
        ] {
            assert_eq!(
                fit_canvas(size(2401, 1201), resolution, None),
                expected,
                "{}",
                resolution
            );
        }
        assert_eq!(fit_canvas(size(800, 1280), "auto", None), size(800, 1280));
        assert_eq!(
            fit_canvas(size(7680, 4320), "native", None),
            size(3840, 2160)
        );
        for (w, expected) in [
            (1280, [125, 200, 250, 300]),
            (1920, [125, 200, 250, 300]),
            (2400, [175, 250, 325, 375]),
            (3840, [250, 400, 400, 400]),
        ] {
            for (preset, mm) in [125, 200, 250, 300].into_iter().zip(expected) {
                assert_eq!(physical_width(size(w, 960), preset), mm);
            }
        }
        for codec in ["avc1.42E01F", "avc3.640033"] {
            let limit = decoder_limit(codec).unwrap();
            assert!(macroblocks(fit_canvas(size(3840, 2160), "native", Some(limit))) <= limit);
        }
        assert_eq!(decoder_limit("avc1.42E01F"), Some(3600));
        assert_eq!(decoder_limit("avc1.640033"), Some(36864));
        assert_eq!(decoder_limit("hvc1.1.6.L123.B0"), None);
        assert_eq!(decoder_limit("avc1.abcdefg"), None);
    }
    #[test]
    fn records_dedupe_library_matching_and_bounds() {
        let canvas = size(1920, 960);
        let rows = vec![
            record(size(1533, 1014), true, 20),
            record(size(1540, 1014), true, 10),
            record(size(1533, 907), false, 9),
            record(size(1533, 1014), false, 8),
        ];
        let areas = plan_areas(canvas, Some(&head()), &rows);
        assert_eq!(areas.len(), 3);
        assert!(areas[1].fullscreen && areas[1].windowed);
        assert!(!areas[2].fullscreen && areas[2].windowed);
        assert_eq!(match_area(&areas, &head()), Some(1));
        let mut h = head();
        h.fullscreen = Some(false);
        h.viewport = Some(size(1533, 907));
        assert_eq!(match_area(&areas, &h), None);
        h.fullscreen_preferred = Some(false);
        assert_eq!(match_area(&areas, &h), Some(2));
        h.viewport = Some(size(100, 500));
        assert_eq!(match_area(&areas, &h), None);
        let mut saved = DisplayState::default();
        for unit in 0..12 {
            for window in 0..25 {
                let mut h = head();
                h.screen.width += unit;
                h.viewport = Some(size(500 + window, 700));
                saved.observe(h, None, (unit * 100 + window) as u64);
            }
        }
        assert_eq!(saved.records.len(), 200);
        assert_eq!(
            saved
                .records
                .iter()
                .map(|r| r.screen.width)
                .collect::<std::collections::BTreeSet<_>>()
                .len(),
            10
        );
        let fallback = plan_areas(canvas, Some(&head()), &[]);
        assert_eq!(fallback.len(), 2);
        assert_eq!(plan_areas(canvas, None, &[]).len(), 1);
        let rows: Vec<_> = [true, false]
            .into_iter()
            .flat_map(|full| {
                (1..=10).map(move |n| record(size(300 + n * 100, 1000), full, n as u64))
            })
            .collect();
        assert!(plan_areas(canvas, Some(&head()), &rows).len() <= 7);
    }
    #[test]
    fn switching_debounces_without_postponing_identical_reports() {
        let mut d = SwitchDebounce::default();
        let t = Instant::now();
        d.observe(Some(1), t);
        assert_eq!(d.ready(t + Duration::from_millis(299)), None);
        d.observe(Some(1), t + Duration::from_millis(200));
        assert_eq!(d.ready(t + Duration::from_millis(300)), Some(1));
        d.observe(Some(2), t + Duration::from_millis(301));
        assert_eq!(d.ready(t + Duration::from_millis(600)), None);
        d.observe(None, t + Duration::from_secs(1));
        assert_eq!(d.ready(t + Duration::from_secs(2)), None);
    }
    #[test]
    fn refit_uses_applied_screen_and_settings_but_never_windows() {
        let mut d = DisplayRuntime::default();
        let t = Instant::now();
        d.freeze();
        d.saved.observe(head(), None, 1);
        assert!(!d.due(t));
        assert!(d.due(t + Duration::from_secs(1)));
        assert!(!d.due(t + Duration::from_secs(2)));
        d.freeze();
        let mut h = head();
        h.viewport = Some(size(900, 900));
        d.saved.observe(h.clone(), None, 2);
        assert!(!d.due(t + Duration::from_secs(3)));
        assert!(!d.due(t + Duration::from_secs(30)));
        h.screen.width += 2;
        d.saved.observe(h, None, 3);
        assert!(!d.due(t + Duration::from_secs(2)));
        assert!(!d.due(t + Duration::from_secs(3)));
        assert!(d.due(t + Duration::from_secs(11)));
        d.freeze();
        d.saved.screen_width = 250;
        d.settings_since = Some(t + Duration::from_secs(12));
        assert!(!d.due(t + Duration::from_secs(12)));
        assert!(d.due(t + Duration::from_secs(13)));
    }
    #[test]
    fn tolerant_atomic_state_round_trip_and_old_settings_rollback() {
        let dir = std::env::temp_dir().join(format!("oxp-display-state-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("display.json");
        std::fs::write(&path,br#"{"resolution":"nonsense","screenWidth":150,"videoCodec":false,"future":true,"headUnit":{},"records":[{},null]}"#).unwrap();
        let s = DisplayState::load(&path);
        assert_eq!(s.settings(), DisplayState::default().settings());
        assert!(s.records.is_empty());
        assert!(s.head_unit.is_none());
        std::fs::write(
            &path,
            br#"{"resolution":"native","screenWidth":125,"videoCodec":"h265","future":true}"#,
        )
        .unwrap();
        let mut s = DisplayState::load(&path);
        s.observe(head(), Some(36864), 1);
        s.save(&path).unwrap();
        let loaded = DisplayState::load(&path);
        assert_eq!(s.settings(), loaded.settings());
        assert_eq!(loaded.head_unit, Some(head()));
        assert_eq!(loaded.canvas, size(2400, 1200));
        assert_eq!(loaded.records.len(), 1);
        let old = crate::settings::Settings::default();
        old.save(&dir).unwrap();
        s.screen_width = 300;
        s.save(&path).unwrap();
        assert_eq!(crate::settings::Settings::load(&dir).unwrap(), old);
        assert!(serde_json::from_value::<crate::settings::Settings>(
            json!({"resolution":"native"})
        )
        .is_err());
        assert!(!path.with_extension("json.tmp").exists());
        std::fs::write(&path, b"broken").unwrap();
        assert_eq!(
            DisplayState::load(&path).settings(),
            DisplayState::default().settings()
        );
        std::fs::remove_dir_all(dir).unwrap();
    }
}
