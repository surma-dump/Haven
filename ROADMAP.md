# Haven Roadmap

## Completed

### Terminal
- [x] **Paste into terminal** — clipboard paste on toolbar/selection bar
- [x] **Bracket paste mode** — `ESC[200~`/`ESC[201~` wrapping for safe multi-line paste
- [x] **Highlighter-style text selection** — long-press drag with edge-scroll
- [x] **Terminal rendering fix** — main-thread emulator writes prevent resize corruption
- [x] **Keyboard toolbar customization** — JSON layout, configurable rows and keys
- [x] **Terminal color schemes** — Haven, Solarized Dark/Light, Monokai, Dracula, Nord, Gruvbox
- [x] **Arrow key fix for TUI apps** — correct VT key dispatch in termlib
- [x] **OSC sequence support** — OSC 8 hyperlinks, OSC 9/777 notifications, OSC 7 CWD tracking
- [x] **Smart clipboard** — strip TUI borders and unwrap soft-wrapped lines on copy
- [x] **Session manager search** — magnifying glass sends native search keys (tmux/zellij/screen/byobu), falls back to shell Ctrl+R
- [x] **Copy last command output** — OSC 133 semantic shell integration, one-tap copy of last command's output with setup dialog
- [x] **OSC 133-safe prompt detection** — session manager commands (tmux/zellij) work alongside shell integration escape sequences

### Wayland Desktop
- [x] **Desktop addons** — install and launch GUI apps within the Wayland compositor
- [x] **GTK Wayland preference** — GTK apps use Wayland backend by default

### Connections
- [x] **Import SSH keys** — PEM/OpenSSH/PuTTY PPK format with passphrase support
- [x] **FIDO2 SSH keys** — ecdsa-sk, ed25519-sk hardware key support
- [x] **Network discovery** — mDNS/broadcast LAN scanning for SSH hosts
- [x] **Port forwarding** — local (`-L`) and remote (`-R`) with visual flow diagrams, live add/edit/remove
- [x] **ProxyJump / multi-hop** — `ssh -J` style jump hosts via direct-tcpip channels
- [x] **Custom session commands** — override tmux/screen/zellij template with `{name}` placeholder
- [x] **Per-connection SSH options** — freeform ssh_config-style key-value pairs
- [x] **Drag-to-reorder connections** — manual ordering of connection list
- [x] **Fresh DNS resolution** — re-resolve hostnames on each connection attempt
- [x] **Background notification** — persistent notification with disconnect action while sessions active
- [x] **Mosh support** — UDP-based mobile shell for unreliable connections
- [x] **Eternal Terminal** — ET protocol support
- [x] **Reticulum** — mesh network transport
- [x] **Network-aware reconnect** — ConnectivityManager detects WiFi/cellular/VPN changes, triggers immediate SSH reconnect (2s debounce) instead of waiting for TCP timeout

### Security
- [x] **Encrypted password storage** — AES-256-GCM encrypted stored passwords
- [x] **Encrypted SSH keys at rest** — Android Keystore wrapping
- [x] **Prevent screenshots** — optional FLAG_SECURE
- [x] **Zero passwords from memory** — wipe credential buffers after auth
- [x] **Screen lock** — biometric, PIN, password, or pattern unlock on launch
- [x] **Backup & restore** — encrypted export/import of all data (AES-256-GCM, PBKDF2)

### Remote Desktop
- [x] **VNC viewer** — embedded VNC client over SSH port forwarding
- [x] **RDP** — Windows remote desktop via IronRDP (Rust/UniFFI)
- [x] **Local Xfce desktop** — PRoot VNC desktop environment

### File Transfer
- [x] **SFTP browser** — full file browser with upload/download/open
- [x] **SMB/CIFS** — Windows file share browsing
- [x] **DocumentsProvider** — expose SFTP as Android storage provider

### PRoot / Local
- [x] **Local Alpine Linux terminal** — PRoot-based local shell
- [x] **Desktop environment setup** — one-tap Xfce4 + VNC installation

## Near-term

### Cloud & file sync
- [ ] **rclone sync** — one-tap folder sync between remotes (local ↔ cloud, cloud ↔ cloud) from the file browser, with progress and conflict resolution
- [ ] **rclone bisync** — two-way sync with change detection for keeping folders in lockstep
- [x] **Media streaming** — stream audio/video from rclone remotes to VLC/any player via local HTTP server with M3U playlists and natural sort

### Media tools
- [ ] **ffmpeg integration** — video/audio conversion, compression, audio extraction, trimming, and merging via [ffmpeg-kit](https://github.com/arthenica/ffmpeg-kit) with a task-based GUI in the file browser. Long-press a media file to convert, compress, extract audio, or trim. Works on local files, SFTP, SMB, and rclone remotes. Presets for common operations (compress for sharing, extract MP3, re-encode for compatibility) plus advanced options for power users.

### Encryption
- [ ] **age file encryption** — encrypt/decrypt files in the SFTP/rclone browser using [age](https://age-encryption.org) (Go library via gomobile), with key management in Haven's key store

### Networking
- [ ] **WireGuard** — built-in WireGuard tunnel for self-hosted VPN (userspace Go implementation), auto-discover and connect to peers without a separate VPN app
- [x] **Tailscale auto-discovery** — detect Tailscale peers via local API and show as discovered hosts

### Terminal depth
- [ ] **Split panes** — horizontal/vertical splits within a tab, independent SSH sessions per pane
- [ ] **Prompt-to-prompt navigation** — jump between commands using OSC 133 markers (infrastructure exists in termlib accessibility layer)

### Workflow continuity
- [ ] **Workspace profiles** — "Work" opens SSH tabs + port forwards + SFTP in one tap

### PRoot development
- [ ] **Curated dev stacks** — one-tap Python/Node.js/Rust/Go installation following the existing DesktopEnvironment enum pattern
- [ ] **sshfs mounts** — mount remote filesystems inside PRoot

### Security
- [ ] **Agent forwarding** — SSH agent for `git push` from remote servers

## Longer-term

- [ ] **X11 forwarding** — lightweight X11 server for individual GUI applications
- [ ] **Connection groups/folders** — organize by project or environment
- [ ] **Snippet/command library** — save and recall frequent commands
- [ ] **Per-profile auth unlock** — require authentication for high-security connections
- [ ] **Audit log UI** — surface ConnectionLog entity for security-conscious users
