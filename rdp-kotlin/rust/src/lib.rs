use std::net::{SocketAddr, TcpStream};
use std::sync::{Arc, Mutex, RwLock};

uniffi::setup_scaffolding!();

#[derive(Debug, uniffi::Error)]
pub enum RdpError {
    ConnectionFailed,
    AuthenticationFailed,
    ProtocolError,
    TlsError,
    Disconnected,
    IoError,
}

impl std::fmt::Display for RdpError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RdpError::ConnectionFailed => write!(f, "Connection failed"),
            RdpError::AuthenticationFailed => write!(f, "Authentication failed"),
            RdpError::ProtocolError => write!(f, "Protocol error"),
            RdpError::TlsError => write!(f, "TLS error"),
            RdpError::Disconnected => write!(f, "Disconnected"),
            RdpError::IoError => write!(f, "I/O error"),
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct RdpConfig {
    pub username: String,
    pub password: String,
    pub domain: String,
    pub width: u16,
    pub height: u16,
    pub color_depth: u8,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FrameData {
    pub width: u16,
    pub height: u16,
    pub pixels: Vec<u8>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct RdpRect {
    pub x: u16,
    pub y: u16,
    pub width: u16,
    pub height: u16,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum MouseButton {
    Left,
    Right,
    Middle,
}

#[uniffi::export(with_foreign)]
pub trait FrameCallback: Send + Sync {
    fn on_frame_update(&self, x: u16, y: u16, w: u16, h: u16);
    fn on_resize(&self, width: u16, height: u16);
}

#[uniffi::export(with_foreign)]
pub trait ClipboardCallback: Send + Sync {
    fn on_remote_clipboard(&self, text: String);
}

/// Internal state for the RDP session.
struct SessionState {
    connected: bool,
    framebuffer: Option<FrameData>,
    dirty_rects: Vec<RdpRect>,
    frame_callback: Option<Arc<dyn FrameCallback>>,
    clipboard_callback: Option<Arc<dyn ClipboardCallback>>,
    shutdown: bool,
}

/// Input events queued by the Kotlin side, consumed by the session thread.
enum InputEvent {
    Key { scancode: u16, pressed: bool },
    UnicodeKey { ch: u32, pressed: bool },
    MouseMove { x: u16, y: u16 },
    MouseButton { button: MouseButton, pressed: bool },
    MouseWheel { vertical: bool, delta: i16 },
    ClipboardText(String),
}

#[derive(uniffi::Object)]
pub struct RdpClient {
    config: RdpConfig,
    state: Arc<RwLock<SessionState>>,
    input_queue: Arc<Mutex<Vec<InputEvent>>>,
    session_thread: Mutex<Option<std::thread::JoinHandle<()>>>,
}

#[uniffi::export]
impl RdpClient {
    #[uniffi::constructor]
    pub fn new(config: RdpConfig) -> Self {
        Self {
            config,
            state: Arc::new(RwLock::new(SessionState {
                connected: false,
                framebuffer: None,
                dirty_rects: Vec::new(),
                frame_callback: None,
                clipboard_callback: None,
                shutdown: false,
            })),
            input_queue: Arc::new(Mutex::new(Vec::new())),
            session_thread: Mutex::new(None),
        }
    }

    pub fn connect(&self, host: String, port: u16) -> Result<(), RdpError> {
        let addr = format!("{}:{}", host, port);
        let stream = TcpStream::connect(&addr).map_err(|_| RdpError::ConnectionFailed)?;
        stream
            .set_nonblocking(false)
            .map_err(|_| RdpError::IoError)?;
        stream
            .set_read_timeout(Some(std::time::Duration::from_millis(100)))
            .map_err(|_| RdpError::IoError)?;

        let server_addr: SocketAddr = stream.peer_addr().map_err(|_| RdpError::IoError)?;

        let config = self.config.clone();
        let state = Arc::clone(&self.state);
        let input_queue = Arc::clone(&self.input_queue);
        let server_name = host.clone();

        let handle = std::thread::Builder::new()
            .name("rdp-session".into())
            .spawn(move || {
                if let Err(e) = run_rdp_session(stream, &config, &state, &input_queue, &server_name, server_addr) {
                    eprintln!("RDP session error: {}", e);
                }
                if let Ok(mut s) = state.write() {
                    s.connected = false;
                }
            })
            .map_err(|_| RdpError::IoError)?;

        if let Ok(mut s) = self.state.write() {
            s.shutdown = false;
        }
        if let Ok(mut jh) = self.session_thread.lock() {
            *jh = Some(handle);
        }

        Ok(())
    }

    pub fn disconnect(&self) {
        if let Ok(mut s) = self.state.write() {
            s.shutdown = true;
            s.connected = false;
        }
        if let Ok(mut jh) = self.session_thread.lock() {
            if let Some(handle) = jh.take() {
                let _ = handle.join();
            }
        }
    }

    pub fn is_connected(&self) -> bool {
        self.state.read().map(|s| s.connected).unwrap_or(false)
    }

    pub fn get_framebuffer(&self) -> Option<FrameData> {
        self.state.read().ok()?.framebuffer.clone()
    }

    pub fn get_dirty_rects(&self) -> Vec<RdpRect> {
        if let Ok(mut s) = self.state.write() {
            std::mem::take(&mut s.dirty_rects)
        } else {
            Vec::new()
        }
    }

    pub fn set_frame_callback(&self, cb: Arc<dyn FrameCallback>) {
        if let Ok(mut s) = self.state.write() {
            s.frame_callback = Some(cb);
        }
    }

    pub fn send_key(&self, scancode: u16, pressed: bool) {
        if let Ok(mut q) = self.input_queue.lock() {
            q.push(InputEvent::Key { scancode, pressed });
        }
    }

    pub fn send_unicode_key(&self, ch: u32, pressed: bool) {
        if let Ok(mut q) = self.input_queue.lock() {
            q.push(InputEvent::UnicodeKey { ch, pressed });
        }
    }

    pub fn send_mouse_move(&self, x: u16, y: u16) {
        if let Ok(mut q) = self.input_queue.lock() {
            q.push(InputEvent::MouseMove { x, y });
        }
    }

    pub fn send_mouse_button(&self, button: MouseButton, pressed: bool) {
        if let Ok(mut q) = self.input_queue.lock() {
            q.push(InputEvent::MouseButton { button, pressed });
        }
    }

    pub fn send_mouse_wheel(&self, vertical: bool, delta: i16) {
        if let Ok(mut q) = self.input_queue.lock() {
            q.push(InputEvent::MouseWheel { vertical, delta });
        }
    }

    pub fn send_clipboard_text(&self, text: String) {
        if let Ok(mut q) = self.input_queue.lock() {
            q.push(InputEvent::ClipboardText(text));
        }
    }

    pub fn set_clipboard_callback(&self, cb: Arc<dyn ClipboardCallback>) {
        if let Ok(mut s) = self.state.write() {
            s.clipboard_callback = Some(cb);
        }
    }
}

/// Build the ironrdp Config with all required fields.
fn build_config(config: &RdpConfig) -> ironrdp_connector::Config {
    use ironrdp_connector::*;
    use ironrdp_pdu::gcc;

    Config {
        credentials: Credentials::UsernamePassword {
            username: config.username.clone().into(),
            password: config.password.clone().into(),
        },
        domain: if config.domain.is_empty() {
            None
        } else {
            Some(config.domain.clone())
        },
        enable_tls: true,
        enable_credssp: true,
        desktop_size: DesktopSize {
            width: config.width,
            height: config.height,
        },
        desktop_scale_factor: 0,
        client_build: 0,
        client_name: "Haven".to_string(),
        keyboard_type: gcc::KeyboardType::IbmEnhanced,
        keyboard_subtype: 0,
        keyboard_functional_keys_count: 12,
        keyboard_layout: 0x0409, // US English
        ime_file_name: String::new(),
        bitmap: Some(BitmapConfig {
            lossy_compression: true,
            color_depth: config.color_depth as u32,
            codecs: ironrdp_pdu::rdp::capability_sets::BitmapCodecs(Vec::new()),
        }),
        dig_product_id: String::new(),
        client_dir: String::new(),
        platform: ironrdp_pdu::rdp::capability_sets::MajorPlatformType::ANDROID,
        hardware_id: None,
        request_data: None,
        autologon: true,
        enable_audio_playback: false,
        performance_flags: ironrdp_pdu::rdp::client_info::PerformanceFlags::default(),
        license_cache: None,
        timezone_info: Default::default(),
        enable_server_pointer: false,
        pointer_software_rendering: false,
    }
}

/// Create a rustls TLS connector that accepts any server certificate.
/// RDP servers typically use self-signed certificates.
fn create_tls_config() -> Result<rustls::ClientConfig, RdpError> {
    use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};

    #[derive(Debug)]
    struct AcceptAnyServerCert;

    impl ServerCertVerifier for AcceptAnyServerCert {
        fn verify_server_cert(
            &self,
            _end_entity: &rustls::pki_types::CertificateDer<'_>,
            _intermediates: &[rustls::pki_types::CertificateDer<'_>],
            _server_name: &rustls::pki_types::ServerName<'_>,
            _ocsp_response: &[u8],
            _now: rustls::pki_types::UnixTime,
        ) -> Result<ServerCertVerified, rustls::Error> {
            Ok(ServerCertVerified::assertion())
        }

        fn verify_tls12_signature(
            &self,
            _message: &[u8],
            _cert: &rustls::pki_types::CertificateDer<'_>,
            _dss: &rustls::DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, rustls::Error> {
            Ok(HandshakeSignatureValid::assertion())
        }

        fn verify_tls13_signature(
            &self,
            _message: &[u8],
            _cert: &rustls::pki_types::CertificateDer<'_>,
            _dss: &rustls::DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, rustls::Error> {
            Ok(HandshakeSignatureValid::assertion())
        }

        fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
            vec![
                rustls::SignatureScheme::RSA_PKCS1_SHA256,
                rustls::SignatureScheme::RSA_PKCS1_SHA384,
                rustls::SignatureScheme::RSA_PKCS1_SHA512,
                rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
                rustls::SignatureScheme::ECDSA_NISTP384_SHA384,
                rustls::SignatureScheme::RSA_PSS_SHA256,
                rustls::SignatureScheme::RSA_PSS_SHA384,
                rustls::SignatureScheme::RSA_PSS_SHA512,
                rustls::SignatureScheme::ED25519,
            ]
        }
    }

    Ok(rustls::ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(AcceptAnyServerCert))
        .with_no_client_auth())
}

/// Run the blocking RDP session on a dedicated thread.
fn run_rdp_session(
    stream: TcpStream,
    config: &RdpConfig,
    state: &Arc<RwLock<SessionState>>,
    input_queue: &Arc<Mutex<Vec<InputEvent>>>,
    server_name: &str,
    server_addr: SocketAddr,
) -> Result<(), RdpError> {
    use ironrdp_blocking::{connect_begin, connect_finalize, mark_as_upgraded, Framed};
    use ironrdp_connector::ServerName;
    use ironrdp_session::{ActiveStage, ActiveStageOutput};
    use ironrdp_session::image::DecodedImage;
    use ironrdp_graphics::image_processing::PixelFormat;

    let rdp_config = build_config(config);
    let mut connector = ironrdp_connector::ClientConnector::new(rdp_config, server_addr);

    // Phase 1: Connection initiation (pre-TLS)
    let mut framed = Framed::new(stream);
    let should_upgrade = connect_begin(&mut framed, &mut connector)
        .map_err(|e| {
            eprintln!("connect_begin failed: {:?}", e);
            RdpError::ConnectionFailed
        })?;

    // Phase 2: TLS upgrade
    let tls_config = create_tls_config()?;
    let (raw_stream, leftover) = framed.into_inner();

    let server_name_ref = rustls::pki_types::ServerName::try_from(server_name.to_string())
        .unwrap_or_else(|_| rustls::pki_types::ServerName::IpAddress(
            server_addr.ip().into()
        ));

    let tls_connector = rustls::ClientConnection::new(
        Arc::new(tls_config),
        server_name_ref,
    ).map_err(|_| RdpError::TlsError)?;

    let tls_stream = rustls::StreamOwned::new(tls_connector, raw_stream);
    let mut tls_framed = Framed::new_with_leftover(tls_stream, leftover);

    let upgraded = mark_as_upgraded(should_upgrade, &mut connector);

    // Phase 3: Extract server public key from TLS for CredSSP
    let server_public_key = tls_framed
        .get_inner()
        .0
        .conn
        .peer_certificates()
        .and_then(|certs| certs.first())
        .map(|cert| cert.as_ref().to_vec())
        .unwrap_or_default();

    // Phase 4: CredSSP + remaining connection sequence
    let sname = ServerName::new(server_name.to_string());

    // No-op network client (reqwest not available on Android).
    // CredSSP's NTLM path doesn't make network calls; only Kerberos does.
    struct NoopNetworkClient;
    impl ironrdp_connector::sspi::network_client::NetworkClient for NoopNetworkClient {
        fn send(&self, _request: &ironrdp_connector::sspi::NetworkRequest) -> ironrdp_connector::sspi::Result<Vec<u8>> {
            Err(ironrdp_connector::sspi::Error::new(
                ironrdp_connector::sspi::ErrorKind::NoAuthenticatingAuthority,
                "Network client not available on Android",
            ))
        }
    }
    let mut network_client = NoopNetworkClient;

    let connection_result = connect_finalize(
        upgraded,
        connector,
        &mut tls_framed,
        &mut network_client,
        sname,
        server_public_key,
        None, // no Kerberos config
    ).map_err(|e| {
        let msg = format!("{:?}", e);
        eprintln!("connect_finalize failed: {}", msg);
        if msg.contains("Authentication") || msg.contains("Credssp") || msg.contains("LOGON_FAILED") {
            RdpError::AuthenticationFailed
        } else {
            RdpError::ConnectionFailed
        }
    })?;

    // Session is connected
    let fb_width = connection_result.desktop_size.width;
    let fb_height = connection_result.desktop_size.height;

    let mut image = DecodedImage::new(PixelFormat::RgbA32, fb_width, fb_height);

    if let Ok(mut s) = state.write() {
        s.connected = true;
        s.framebuffer = Some(FrameData {
            width: fb_width,
            height: fb_height,
            pixels: vec![0u8; fb_width as usize * fb_height as usize * 4],
        });
        if let Some(ref cb) = s.frame_callback {
            cb.on_resize(fb_width, fb_height);
        }
    }

    let mut active_stage = ActiveStage::new(connection_result);

    // Input state tracking
    let mut input_db = ironrdp_input::Database::new();

    // Active session loop
    loop {
        // Check for shutdown
        if let Ok(s) = state.read() {
            if s.shutdown {
                break;
            }
        }

        // Process queued input events
        let pending_inputs: Vec<InputEvent> = {
            if let Ok(mut q) = input_queue.lock() {
                std::mem::take(&mut *q)
            } else {
                Vec::new()
            }
        };

        for event in pending_inputs {
            let fastpath_events = match event {
                InputEvent::Key { scancode, pressed } => {
                    let op = if pressed {
                        ironrdp_input::Operation::KeyPressed(
                            ironrdp_input::Scancode::from_u16(scancode)
                        )
                    } else {
                        ironrdp_input::Operation::KeyReleased(
                            ironrdp_input::Scancode::from_u16(scancode)
                        )
                    };
                    input_db.apply(std::iter::once(op))
                }
                InputEvent::UnicodeKey { ch, pressed } => {
                    if let Some(c) = char::from_u32(ch) {
                        let op = if pressed {
                            ironrdp_input::Operation::UnicodeKeyPressed(c)
                        } else {
                            ironrdp_input::Operation::UnicodeKeyReleased(c)
                        };
                        input_db.apply(std::iter::once(op))
                    } else {
                        smallvec::SmallVec::new()
                    }
                }
                InputEvent::MouseMove { x, y } => {
                    let op = ironrdp_input::Operation::MouseMove(
                        ironrdp_input::MousePosition { x, y }
                    );
                    input_db.apply(std::iter::once(op))
                }
                InputEvent::MouseButton { button, pressed } => {
                    let btn = match button {
                        MouseButton::Left => ironrdp_input::MouseButton::Left,
                        MouseButton::Right => ironrdp_input::MouseButton::Right,
                        MouseButton::Middle => ironrdp_input::MouseButton::Middle,
                    };
                    let op = if pressed {
                        ironrdp_input::Operation::MouseButtonPressed(btn)
                    } else {
                        ironrdp_input::Operation::MouseButtonReleased(btn)
                    };
                    input_db.apply(std::iter::once(op))
                }
                InputEvent::MouseWheel { vertical: _, delta } => {
                    let op = ironrdp_input::Operation::WheelRotations(
                        ironrdp_input::WheelRotations {
                            is_vertical: true,
                            rotation_units: delta as i16,
                        }
                    );
                    input_db.apply(std::iter::once(op))
                }
                InputEvent::ClipboardText(_text) => {
                    // Clipboard handled via CLIPRDR channel, not input
                    smallvec::SmallVec::new()
                }
            };

            if !fastpath_events.is_empty() {
                match active_stage.process_fastpath_input(&mut image, &fastpath_events) {
                    Ok(outputs) => {
                        for output in outputs {
                            if let ActiveStageOutput::ResponseFrame(frame) = output {
                                if let Err(e) = tls_framed.write_all(&frame) {
                                    eprintln!("Write input error: {:?}", e);
                                }
                            }
                        }
                    }
                    Err(e) => {
                        eprintln!("Input processing error: {:?}", e);
                    }
                }
            }
        }

        // Read server PDU
        match tls_framed.read_pdu() {
            Ok((action, frame)) => {
                match active_stage.process(&mut image, action, &frame) {
                    Ok(outputs) => {
                        for output in outputs {
                            match output {
                                ActiveStageOutput::ResponseFrame(response) => {
                                    if let Err(e) = tls_framed.write_all(&response) {
                                        eprintln!("Write response error: {:?}", e);
                                        break;
                                    }
                                }
                                ActiveStageOutput::GraphicsUpdate(rect) => {
                                    update_framebuffer(state, &image, &rect);
                                }
                                ActiveStageOutput::Terminate(reason) => {
                                    eprintln!("Server disconnect: {}", reason);
                                    break;
                                }
                                ActiveStageOutput::DeactivateAll(_cas) => {
                                    // Server-initiated deactivation-reactivation
                                    // Would need to handle reconnection here
                                    break;
                                }
                                _ => {}
                            }
                        }
                    }
                    Err(e) => {
                        eprintln!("Session process error: {:?}", e);
                        break;
                    }
                }
            }
            Err(e) => {
                if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut {
                    // No data available, continue loop to process input
                    continue;
                }
                eprintln!("Read PDU error: {:?}", e);
                break;
            }
        }
    }

    Ok(())
}

/// Copy updated region from DecodedImage to our ARGB framebuffer
/// and notify callbacks.
fn update_framebuffer(
    state: &Arc<RwLock<SessionState>>,
    image: &ironrdp_session::image::DecodedImage,
    rect: &ironrdp_pdu::geometry::InclusiveRectangle,
) {
    use ironrdp_pdu::geometry::Rectangle;

    let fb_width = image.width() as usize;
    let fb_height = image.height() as usize;
    let pixel_data = image.data();

    // Convert BGRA (ironrdp) to ARGB (Android Bitmap)
    let pixel_count = fb_width * fb_height;
    let mut argb = vec![0u8; pixel_count * 4];

    for i in 0..pixel_count {
        let si = i * 4;
        if si + 3 < pixel_data.len() {
            argb[si] = pixel_data[si + 3]; // A
            argb[si + 1] = pixel_data[si + 2]; // R
            argb[si + 2] = pixel_data[si + 1]; // G
            argb[si + 3] = pixel_data[si]; // B
        }
    }

    let rdp_rect = RdpRect {
        x: rect.left,
        y: rect.top,
        width: rect.width(),
        height: rect.height(),
    };

    if let Ok(mut s) = state.write() {
        s.framebuffer = Some(FrameData {
            width: fb_width as u16,
            height: fb_height as u16,
            pixels: argb,
        });
        s.dirty_rects.push(rdp_rect.clone());
        if let Some(ref cb) = s.frame_callback {
            cb.on_frame_update(rdp_rect.x, rdp_rect.y, rdp_rect.width, rdp_rect.height);
        }
    }
}
