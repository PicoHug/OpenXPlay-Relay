// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-16

import type { ReceiverReport, Status } from './api';
import { icon } from './icons';
type Translate = (key: string, parameters?: Record<string, string | number>) => string;
type Info = Record<string, unknown>;
const esc = (s: unknown) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
// Receiver reports are browser-supplied JSON: read every field defensively.
const record = (value: unknown): Info | null => value && typeof value === 'object' && !Array.isArray(value) ? value as Info : null;
const num = (value: unknown): number | null => typeof value === 'number' && Number.isFinite(value) ? value : null;
const str = (value: unknown): string | null => typeof value === 'string' && value.trim() ? value.trim() : null;
const round = (value: number, digits = 1) => String(Number(value.toFixed(digits)));

export type Support = 'hardware' | 'software' | 'none';
const SUPPORT_KEYS: Record<Support, string> = { hardware: 'diag.hardware', software: 'diag.software', none: 'diag.unsupported' };
const FAMILIES: [RegExp, string][] = [[/^avc[13]\./i, 'H.264'], [/^(hvc1|hev1)\./i, 'H.265'], [/^av01\./i, 'AV1'], [/^vp09\./i, 'VP9'], [/^vp8$/i, 'VP8']];
export function codecName(codec: string | null | undefined): string | null {
  return codec ? ({ H264: 'H.264', H265: 'H.265' } as Record<string, string>)[codec] ?? codec : null;
}
// MediaCapabilities powerEfficient means a hardware decoder; any other positive answer is software decoding.
export function codecSupport(info: Info | null): { family: string; support: Support }[] {
  const rank: Record<Support, number> = { none: 0, software: 1, hardware: 2 };
  const result = new Map<string, Support>();
  for (const raw of Array.isArray(info?.videoDecode) ? info.videoDecode : []) {
    const entry = record(raw); const codec = str(entry?.codec);
    const family = codec && FAMILIES.find(([pattern]) => pattern.test(codec))?.[1];
    if (!entry || !family) continue;
    const media = [record(entry.fps30), record(entry.fps60)];
    const support: Support = media.some(m => m?.supported === true && m.powerEfficient === true) ? 'hardware'
      : entry.preferHardware === true || entry.preferSoftware === true || media.some(m => m?.supported === true) ? 'software' : 'none';
    if (rank[support] >= rank[result.get(family) ?? 'none']) result.set(family, support);
  }
  return [...result].map(([family, support]) => ({ family, support }));
}
// "Chromium 120" from the high-entropy brand list, falling back to the user agent string.
export function browserVersions(info: Info | null): { browser: string | null; chromium: string | null } {
  const data = record(info?.userAgentData);
  const list = (Array.isArray(data?.fullVersionList) ? data.fullVersionList : Array.isArray(data?.brands) ? data.brands : [])
    .map(record).map(b => ({ brand: str(b?.brand), version: str(b?.version) }))
    .filter((b): b is { brand: string; version: string } => !!b.brand && !!b.version && !/not.?a.?brand/i.test(b.brand));
  const ua = str(info?.userAgent) ?? '';
  const other = list.find(b => b.brand !== 'Chromium');
  const firefox = /Firefox\/([\d.]+)/.exec(ua)?.[1];
  const safari = /Chrome\//.test(ua) ? undefined : /Version\/([\d.]+).*Safari/.exec(ua)?.[1];
  const fallback = firefox ? `Firefox ${firefox}` : safari ? `Safari ${safari}` : null;
  return { browser: other ? `${other.brand} ${other.version}` : fallback,
    chromium: list.find(b => b.brand === 'Chromium')?.version ?? /(?:Chrome|Chromium)\/([\d.]+)/.exec(ua)?.[1] ?? null };
}
export function browserLabel(info: Info | null): string | null {
  const { browser, chromium } = browserVersions(info);
  return chromium ? `Chromium ${chromium.split('.')[0]}` : browser?.replace(/^(\S+ \d+)[\d.]*$/, '$1') ?? null;
}
export function gpuRenderer(info: Info | null): string | null {
  return str(record(info?.webgl)?.unmaskedRenderer) ?? str(record(info?.webgl2)?.unmaskedRenderer)
    ?? str(record(info?.webgl)?.renderer) ?? str(record(info?.webgpu)?.description);
}
// "ANGLE (Qualcomm, Adreno (TM) 640, OpenGL ES 3.2)" becomes "Adreno 640".
export function gpuLabel(info: Info | null): string | null {
  let name = gpuRenderer(info); if (!name) return null;
  const angle = /^ANGLE \((.*)\)$/.exec(name);
  if (angle) { const parts = angle[1].split(', '); name = parts[1] ?? parts[0]; }
  return name.replace(/\((TM|R)\)/gi, '').replace(/\s*\(0x[0-9a-f]+\)/gi, '').replace(/\s+(Direct3D|OpenGL|Vulkan).*$/i, '').replace(/\s+/g, ' ').trim() || null;
}
export function refreshLabel(info: Info | null): string | null {
  const hz = num(info?.refreshHz); return hz ? `${round(hz)} Hz` : null;
}
export function receiverSummary(info: Info | null, t: Translate): string {
  const codecs = codecSupport(info).filter(c => c.family === 'H.264' || c.family === 'H.265');
  const groups = (['hardware', 'software', 'none'] as Support[]).flatMap(support => {
    const families = codecs.filter(c => c.support === support).map(c => c.family);
    return families.length ? [`${families.join('/')} ${t(SUPPORT_KEYS[support])}`] : [];
  });
  return [browserLabel(info), gpuLabel(info), ...groups, refreshLabel(info)].filter(Boolean).join(' · ');
}

// The latest per-second sample plus totals over the retained window.
export function liveStats(report: ReceiverReport) {
  const samples = report.stats.map(record).filter((s): s is Info => s !== null);
  const last = samples.at(-1) ?? null;
  const pick = (sample: Info | null, ...keys: string[]) => keys.map(key => num(sample?.[key])).find(value => value !== null) ?? null;
  const bytes = pick(last, 'wsBytesPerSecond');
  const values = (key: string) => samples.map(s => num(s[key])).filter((v): v is number => v !== null);
  const worst = [...values('decodeLatencyMaxMs'), ...values('decodeLatencyP95Ms')];
  return { stale: report.idleSeconds > 5, fps: pick(last, 'renderedFps', 'videoFramesPerSecond'),
    p50: pick(last, 'decodeLatencyP50Ms'), p95: pick(last, 'decodeLatencyP95Ms'), drops: pick(last, 'droppedFrames'),
    mbps: bytes === null ? null : bytes * 8 / 1e6, lag: pick(last, 'eventLoopLagMaxMs'),
    window: { seconds: samples.length, drops: values('droppedFrames').reduce((a, b) => a + b, 0),
      worst: worst.length ? Math.max(...worst) : null, longTasks: values('longTaskCount').reduce((a, b) => a + b, 0) } };
}
export function liveLine(report: ReceiverReport, t: Translate): string {
  const s = liveStats(report);
  return [s.fps !== null && t('diag.fps', { value: round(s.fps) }),
    s.p50 !== null && s.p95 !== null && t('diag.decode', { p50: round(s.p50), p95: round(s.p95) }),
    s.drops !== null && t('diag.drops', { value: round(s.drops, 0) }),
    s.mbps !== null && t('diag.bitrate', { value: round(s.mbps) }),
    s.lag !== null && t('diag.lag', { value: round(s.lag, 0) })].filter(Boolean).join(' · ');
}
export function windowLine(report: ReceiverReport, t: Translate): string {
  const w = liveStats(report).window;
  if (w.seconds < 2) return '';
  return [t('diag.window', { seconds: w.seconds }), t('diag.drops', { value: w.drops }),
    w.worst !== null && t('diag.worst', { value: round(w.worst, 0) }), t('diag.longTasks', { value: w.longTasks })].filter(Boolean).join(' · ');
}
function detailRows(report: ReceiverReport, t: Translate): [string, string][] {
  const info = report.info; const data = record(info?.userAgentData);
  const versions = browserVersions(info);
  const screen = record(info?.screen); const audio = record(info?.audio); const network = record(info?.network);
  const dpr = num(info?.devicePixelRatio);
  const heap = [...report.stats].reverse().map(s => num(record(s)?.usedJSHeapSize)).find(v => v !== null) ?? num(record(info?.memory)?.usedJSHeapSize);
  const limit = num(record(info?.memory)?.jsHeapSizeLimit);
  const audioCodecs = (Array.isArray(info?.audioDecoder) ? info.audioDecoder : []).map(record)
    .filter(c => c?.supported === true).map(c => ({ 'mp4a.40.2': 'AAC', opus: 'Opus' } as Record<string, string>)[String(c!.codec)] ?? String(c!.codec));
  return ([
    ['diag.browser', [versions.browser, versions.chromium && `Chromium ${versions.chromium}`, [str(data?.platform), str(data?.platformVersion)].filter(Boolean).join(' '), str(data?.model)].filter(Boolean).join(' · ')],
    ['diag.gpu', gpuRenderer(info) ?? ''],
    ['diag.display', [screen && num(screen.width) && `${screen.width} × ${screen.height}`, dpr && `@${round(dpr, 2)}x`, refreshLabel(info), num(info?.innerWidth) && `${info!.innerWidth} × ${info!.innerHeight}`].filter(Boolean).join(' · ')],
    ['diag.codecs', codecSupport(info).map(c => `${c.family} ${t(SUPPORT_KEYS[c.support])}`).join(', ')],
    ['diag.audio', [num(audio?.sampleRate) && `${round(num(audio!.sampleRate)! / 1000)} kHz`, audioCodecs.join(', ')].filter(Boolean).join(' · ')],
    ['diag.network', [str(network?.type), str(network?.effectiveType), num(network?.downlink) !== null && `${network!.downlink} Mbit/s`, num(network?.rtt) !== null && `RTT ${network!.rtt} ms`].filter(Boolean).join(' · ')],
    ['diag.memory', heap ? `${round(heap / 1048576, 0)}${limit ? ` / ${round(limit / 1048576, 0)}` : ''} MB` : ''],
    ['diag.userAgent', str(info?.userAgent) ?? ''],
  ] as [string, string][]).filter(([, value]) => value);
}
function receiverCard(report: ReceiverReport, index: number, t: Translate): string {
  const live = liveStats(report); const line = liveLine(report, t); const window = windowLine(report, t);
  const rows = detailRows(report, t).map(([key, value]) => `<dt>${t(key)}</dt><dd>${esc(value)}</dd>`).join('');
  return `<article class="receiver-card" data-client="${esc(report.client)}"><div class="receiver-heading"><strong>${esc(t('diag.screen', { n: index + 1 }))}</strong><span class="muted mono">#${esc(report.client)}</span>${live.stale ? `<span class="tag">${t('diag.stale')}</span>` : ''}</div>`
    + `<p class="receiver-summary">${esc(report.info ? receiverSummary(report.info, t) || t('diag.waiting') : t('diag.waiting'))}</p>`
    + (line ? `<p class="mono">${esc(line)}</p>` : '') + (window ? `<p class="muted">${esc(window)}</p>` : '')
    + (rows ? `<details data-details="${esc(report.client)}"><summary>${t('diag.details')}</summary><dl class="facts">${rows}</dl></details>` : '')
    + `<div class="report-row"><button type="button" data-report="${esc(report.client)}">${icon('copy')}<span>${t('diag.copy')}</span></button><span class="report-feedback" role="status"></span></div></article>`;
}

export const VIDEO_REASONS = ['setting-h264', 'setting-h265', 'hevc-failed', 'relay-h264-only', 'main-screen-output-prefers-h264', 'all-main-screen-outputs-hardware-hevc', 'hardware-hevc-unavailable', 'no-main-screen-output', 'output-requires-avc', 'remembered-codec', 'hevc-keyframe-timeout'];
export function videoView(status: Pick<Status, 'video' | 'receivers'>) {
  const video = status.video;
  if (!video?.codec) return null;
  const [reason = '', ...flags] = String(video.reason ?? '').split(';').map(part => part.trim());
  const codec = codecName(video.codec)!;
  // Only screens that told us how they decode the codec in use can be flagged.
  const mismatches = (status.receivers ?? []).flatMap((receiver, index) => {
    const support = codecSupport(receiver.info).find(c => c.family === codec)?.support;
    return support && support !== 'hardware' ? [{ screen: index + 1, client: receiver.client, support }] : [];
  });
  return { codec, reason, pending: flags.includes('codec-change-pending'), codecString: video.codecString ?? null,
    size: video.width && video.height ? `${video.width} × ${video.height}` : null, mismatches };
}
export function videoLine(status: Pick<Status, 'video' | 'receivers'>, t: Translate): string {
  const v = videoView(status); if (!v) return '';
  const line = VIDEO_REASONS.includes(v.reason) ? t('video.line', { codec: v.codec, reason: t(`video.reason.${v.reason}`) }) : t('video.lineBare', { codec: v.codec });
  return v.pending ? `${line} · ${t('video.pending')}` : line;
}
function bluetoothView(status: Status, t: Translate): string {
  const guard = status.bluetoothGuard;
  if (!guard) return '';
  const rows: [string, string | number | null][] = [['bt.state', t(`bt.state.${guard.state}`)], ['bt.attempts', guard.attempts], ['bt.lastRecovery', guard.lastRecovery], ['bt.lastError', guard.lastError], ['bt.address', guard.address]];
  return `<h3>${t('bt.title')}</h3><dl class="facts">${rows.filter(([, value]) => value !== null && value !== '').map(([key, value]) => `<dt>${t(key)}</dt><dd>${esc(value)}</dd>`).join('')}</dl>`;
}
export function diagnosticsView(status: Status, t: Translate): string {
  const v = videoView(status);
  const stream = v && [v.codecString && `<code>${esc(v.codecString)}</code>`, v.size && esc(v.size)].filter(Boolean).join(' · ');
  const video = v ? `<p>${esc(videoLine(status, t))}</p><dl class="facts"><dt>${t('diag.codec')}</dt><dd>${esc(v.codec)}</dd><dt>${t('diag.reason')}</dt><dd><code>${esc(status.video!.reason)}</code></dd>${stream ? `<dt>${t('diag.stream')}</dt><dd>${stream}</dd>` : ''}</dl>`
    + v.mismatches.map(m => `<p class="notice">${esc(t(`video.mismatch.${m.support}`, { screen: t('diag.screen', { n: m.screen }), codec: v.codec }))}</p>`).join('') : '<p class="muted">—</p>';
  const receivers = (status.receivers ?? []).map((report, index) => receiverCard(report, index, t)).join('');
  return `${bluetoothView(status, t)}<h3>${t('diag.video')}</h3>${video}<h3>${t('diag.receivers')}</h3>${receivers || `<p class="muted">${t('diag.none')}</p>`}`;
}
export function receiverReport(status: Status, client: string, now = new Date()): string | null {
  const receiver = status.receivers?.find(r => r.client === client);
  if (!receiver) return null;
  return JSON.stringify({ report: 'openxplay-receiver', generatedAt: now.toISOString(),
    relay: { platform: status.platform, version: status.version, state: status.state, output: status.output },
    video: status.video ?? null, receiver }, null, 2);
}
export interface ClipboardLike { writeText(text: string): Promise<void> }
// The Clipboard API needs a secure context; the fallback copies through a temporary selection.
export async function copyText(text: string, clipboard: ClipboardLike | undefined, secure: boolean, fallback: (text: string) => boolean): Promise<boolean> {
  if (secure && clipboard) {
    try { await clipboard.writeText(text); return true; } catch { /* Fall back to a selection copy. */ }
  }
  try { return fallback(text); } catch { return false; }
}
