// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15
#![allow(unused_imports, unused_variables)]

/// Fallible protocol/platform operation; malformed data and I/O errors propagate.
pub type Result<T> = std::io::Result<T>;
/// cli protocol module.
#[path = "main.rs"]
pub mod cli;
/// di play input protocol module.
pub mod di_play_input;
pub mod display;
pub mod hotspot_lifetime;
/// linux air play server protocol module.
pub mod linux_air_play_server;
/// linux audio protocol module.
pub mod linux_audio;
/// linux bluetooth protocol module.
pub mod linux_bluetooth;
/// linux bonjour protocol module.
pub mod linux_bonjour;
/// linux car play controller protocol module.
pub mod linux_car_play_controller;
/// linux hotspot protocol module.
pub mod linux_hotspot;
/// linux microphone protocol module.
pub mod linux_microphone;
/// linux wireless platform protocol module.
pub mod linux_wireless_platform;
/// network protocol module.
pub mod network;
/// platform protocol module.
pub mod platform;
/// recording media sink protocol module.
pub mod recording_media_sink;
/// Simulated iPhone for tests without one (`--test-input`).
pub mod replay_input;
/// stream bridge sink protocol module.
pub mod stream_bridge_sink;
/// stream connection protocol module.
pub mod stream_connection;

pub use cli::Options;
pub use cli::PersistentState;
pub use di_play_input::DiPlayInput;
pub use linux_air_play_server::LinuxAirPlayServer;
pub use linux_air_play_server::LinuxStreamTransport;
pub use linux_air_play_server::SessionMedia;
pub use linux_audio::AudioNormalizer;
pub use linux_audio::AudioPolicy;
pub use linux_audio::AudioPolicyRun;
pub use linux_bluetooth::BondedDevice;
pub use linux_bluetooth::LinuxBluetooth;
pub use linux_bluetooth::LinuxRfcommStream;
pub use linux_bluetooth::PairingMode;
pub use linux_bonjour::LinuxBonjour;
pub use linux_car_play_controller::BluetoothConnection;
pub use linux_car_play_controller::BootstrapCallbacks;
pub use linux_car_play_controller::CloseResource;
pub use linux_car_play_controller::LinuxCarPlayController;
pub use linux_car_play_controller::MfiResource;
pub use linux_car_play_controller::NetworkAttachment;
pub use linux_car_play_controller::NetworkCallbacks;
pub use linux_car_play_controller::PendingNetwork;
pub use linux_car_play_controller::WirelessPlatform;
pub use linux_hotspot::LinuxHotspot;
pub use linux_microphone::LinuxMicrophoneUplink;
pub use linux_microphone::MicrophoneFrames;
pub use linux_microphone::MicrophoneUplink;
pub use linux_wireless_platform::LinuxNetwork;
pub use linux_wireless_platform::LinuxWirelessPlatform;
pub use network::air_play_port_selector::AirPlayPortSelector;
pub use network::hotspot_address_policy::HotspotAddressPolicy;
pub use network::wireless_host_address::WirelessHostAddress;
pub use network::wireless_hotspot_manager::WirelessHotspotInfo;
pub use network::wireless_hotspot_manager::WirelessHotspotManager;
pub use platform::CommandResult;
pub use platform::CommandRunner;
pub use platform::JsonEvents;
pub use platform::ProcessCommandRunner;
pub use recording_media_sink::RecordingMediaSink;
pub use stream_bridge_sink::StreamBridgeSink;
pub use stream_connection::StreamConnection;

pub mod admin;
pub mod bluetooth_guard;
pub mod bluetooth_wake;
pub mod boot_timing;
pub mod branding;
pub mod mdns_host;
pub mod plugins;
pub mod provision;
pub mod receiver_diagnostics;
pub mod relay_admin;
pub mod secure_connections;
pub mod settings;
pub mod supervisor;

pub mod context;

mod context_route;
