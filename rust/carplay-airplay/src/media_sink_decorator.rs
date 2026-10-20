// SPDX-FileCopyrightText: DiPlay and xcertplay contributors
// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Derived from DiPlay (https://github.com/shihabal3amri/DiPlay), based on xcertplay (https://github.com/shilapi/xcertplay). Modified by OpenXPlay.org on 2026-10-15.

use crate::*;
use carplay_iap2::{
    mfi::MfiAuthenticator, DuplexStream, Iap2Session, Iap2WirelessCarPlayEndpoint,
    Iap2WirelessSecurity, Receive,
};
use std::{
    sync::Arc,
    time::{Duration, Instant},
};

/// Platform interface for `SessionControl`. Implementations must support bounded shutdown.
pub trait SessionControl: Send + Sync {
    fn show_view_area(&self, _index: usize) -> bool {
        false
    }
    /// Errors propagate to the lifecycle owner.
    fn send_touch(&self, contacts: &[AirPlayContact]) -> Result<bool>;
}
/// Platform interface for `MediaSinkDecorator`. Implementations must support bounded shutdown.
pub trait MediaSinkDecorator: Send + Sync {
    /// Errors propagate to the lifecycle owner.
    fn decorate(
        &self,
        sink: Arc<dyn MediaSink>,
        control: Arc<dyn SessionControl>,
    ) -> Arc<dyn MediaSink>;
}
