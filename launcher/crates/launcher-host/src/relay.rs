//! Capability-scoped game tunnel. It exposes only backend, gateway and world traffic.
use crate::relay_catalog::{bscp_relay_ca, ranked_relays};
use anyhow::{Context, Result, bail};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use iroh::{
    Endpoint, EndpointAddr, RelayMap, RelayMode, RelayUrl,
    endpoint::{Connection, presets},
    tls::CaTlsConfig,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    net::SocketAddr,
    path::Path,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream, UdpSocket},
    sync::Semaphore,
};
use tokio_util::sync::CancellationToken;
const ALPN: &[u8] = b"bscp/game-tunnel/1";
const PREFIX: &str = "bscp://";
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Invite {
    version: u8,
    address: EndpointAddr,
    key: String,
    pub base: u16,
}
impl Invite {
    pub fn parse(value: &str) -> Result<Self> {
        if value.len() > 4096 {
            bail!("INVITE_INVALID: Слишком длинное приглашение")
        }
        let raw = URL_SAFE_NO_PAD.decode(
            value
                .trim()
                .strip_prefix(PREFIX)
                .context("INVITE_INVALID")?,
        )?;
        let ticket: Self = serde_json::from_slice(&raw)?;
        if ticket.version != 1
            || !(1024..=65000).contains(&ticket.base)
            || ticket.key.len() != 64
            || hex::decode(&ticket.key)?.len() != 32
        {
            bail!("INVITE_INVALID")
        }
        if ticket.address.relay_urls().any(|u| !relay_origin(u)) {
            bail!("INVITE_INVALID")
        }
        Ok(ticket)
    }
    fn encode(&self) -> Result<String> {
        Ok(format!(
            "{PREFIX}{}",
            URL_SAFE_NO_PAD.encode(serde_json::to_vec(self)?)
        ))
    }
}
pub fn is_invite(s: &str) -> bool {
    s.trim().starts_with(PREFIX)
}
pub fn normalize(s: &str) -> Result<String> {
    Invite::parse(s)?.encode()
}
pub struct Tunnel {
    endpoint: Endpoint,
    stop: CancellationToken,
    pub invite: String,
    pub backend_port: u16,
    pub game_port: u16,
    pub world_port: u16,
    pub remote_base: u16,
    udp: Arc<RelayUdpMetrics>,
}
#[derive(Default)]
struct RelayUdpMetrics {
    received: AtomicU64,
    sent: AtomicU64,
    fragments: AtomicU64,
    fragmented: AtomicU64,
    send_errors: AtomicU64,
    buffer_pressure: AtomicU64,
    forwarded: AtomicU64,
    forward_errors: AtomicU64,
}

impl Drop for Tunnel {
    fn drop(&mut self) {
        self.stop.cancel();
        let ep = self.endpoint.clone();
        tokio::spawn(async move {
            ep.close().await;
        });
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RelayCredentials {
    url: String,
    token: String,
}
struct PrivateRelay {
    url: RelayUrl,
    token: String,
    ca: Vec<u8>,
}
impl PrivateRelay {
    fn load(root: &Path) -> Result<Option<Self>> {
        let config = root.join("config");
        let data = match std::fs::read(config.join("relay-access.json")) {
            Ok(data) => data,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error).context("RELAY_ACCESS_FILE"),
        };
        anyhow::ensure!(data.len() <= 4096, "RELAY_ACCESS_FILE");
        let access: RelayCredentials =
            serde_json::from_slice(&data).context("RELAY_ACCESS_FILE")?;
        let url: RelayUrl = access.url.parse().context("RELAY_ACCESS_URL")?;
        anyhow::ensure!(relay_origin(&url), "RELAY_ACCESS_URL");
        anyhow::ensure!(
            access.token.len() == 64 && access.token.bytes().all(|byte| byte.is_ascii_hexdigit()),
            "RELAY_ACCESS_TOKEN"
        );
        let ca = std::fs::read(config.join("relay-ca.der")).context("RELAY_ACCESS_CA")?;
        anyhow::ensure!((1..=8192).contains(&ca.len()), "RELAY_ACCESS_CA");
        Ok(Some(Self {
            url,
            token: access.token,
            ca,
        }))
    }
}
fn relay_origin(url: &RelayUrl) -> bool {
    url.scheme() == "https"
        && url.host_str().is_some()
        && url.username().is_empty()
        && url.password().is_none()
        && url.path() == "/"
        && url.query().is_none()
        && url.fragment().is_none()
}

async fn endpoint(
    custom: Option<&RelayUrl>,
    private: Option<&PrivateRelay>,
    relay_only: bool,
) -> Result<Endpoint> {
    let mut builder = Endpoint::builder(presets::N0)
        .alpns(vec![ALPN.to_vec()])
        .clear_address_lookup();
    if let Some(url) = custom {
        anyhow::ensure!(relay_origin(url), "RELAY_URL: an HTTPS origin is required");
        if let Some(access) = private.filter(|access| &access.url == url) {
            let relays = RelayMap::from_iter([url.clone()]).with_auth_token(&access.token);
            builder = builder
                .relay_mode(RelayMode::Custom(relays))
                .ca_tls_config(CaTlsConfig::custom_roots([access.ca.clone().into()]));
        } else {
            builder = builder.relay_mode(RelayMode::custom([url.clone()]));
            if let Some(ca) = bscp_relay_ca(url) {
                builder = builder.ca_tls_config(CaTlsConfig::custom_roots([ca.into()]));
            }
        }
    }
    if relay_only {
        builder = builder.clear_ip_transports();
    }
    Ok(builder.bind().await?)
}

async fn online_endpoint(
    custom: Option<&RelayUrl>,
    private: Option<&PrivateRelay>,
    relay_only: bool,
    wait: Duration,
) -> Result<Endpoint> {
    let ep = endpoint(custom, private, relay_only).await?;
    let online = tokio::time::timeout(wait, ep.online()).await.is_ok();
    if !online {
        ep.close().await;
        bail!("RELAY_OFFLINE: Сеть Iroh недоступна")
    }
    Ok(ep)
}

async fn host_endpoint(
    custom: &str,
    private: Option<&PrivateRelay>,
    relay_only: bool,
) -> Result<Endpoint> {
    let selected: Option<RelayUrl> = if custom.trim().is_empty() {
        private.map(|access| access.url.clone())
    } else {
        Some(custom.parse().context("RELAY_URL")?)
    };
    if selected.is_none() {
        let ranked = ranked_relays().await.unwrap_or_default();
        let deadline = Instant::now() + Duration::from_secs(14);
        for relay in ranked {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                break;
            }
            let wait = remaining.min(Duration::from_secs(7));
            if let Ok(ep) = online_endpoint(Some(&relay), None, relay_only, wait).await {
                return Ok(ep);
            }
        }
    }
    online_endpoint(
        selected.as_ref(),
        private,
        relay_only,
        Duration::from_secs(20),
    )
    .await
}

fn task(stop: &CancellationToken, future: impl std::future::Future<Output = ()> + Send + 'static) {
    let s = stop.clone();
    tokio::spawn(async move {
        tokio::select! {_=s.cancelled()=>{},_=future=>{}}
    });
}
impl Tunnel {
    pub fn udp_metrics(&self) -> serde_json::Value {
        let counts = &self.udp;
        let load = |value: &AtomicU64| value.load(Ordering::Relaxed);
        serde_json::json!({
            "udpReceived": load(&counts.received),
            "udpSubmitted": load(&counts.sent),
            "quicFragmentsSubmitted": load(&counts.fragments),
            "udpFragmented": load(&counts.fragmented),
            "quicSendErrors": load(&counts.send_errors),
            "quicFragmentBufferPressure": load(&counts.buffer_pressure),
            "udpForwarded": load(&counts.forwarded),
            "udpForwardErrors": load(&counts.forward_errors),
        })
    }
    pub fn relay_host(&self) -> Option<String> {
        self.endpoint
            .addr()
            .relay_urls()
            .find_map(|url| url.host_str())
            .map(|host| host.trim_end_matches('.').to_owned())
    }

    pub async fn host(base: u16, custom: &str, root: &Path) -> Result<Self> {
        Self::host_mode(base, custom, PrivateRelay::load(root)?.as_ref(), false).await
    }
    async fn host_mode(
        base: u16,
        custom: &str,
        access: Option<&PrivateRelay>,
        relay_only: bool,
    ) -> Result<Self> {
        let ep = host_endpoint(custom, access, relay_only).await?;
        let mut bytes = [0; 32];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut bytes);
        let ticket = Invite {
            version: 1,
            address: ep.addr(),
            key: hex::encode(bytes),
            base,
        };
        let invite = ticket.encode()?;
        let stop = CancellationToken::new();
        let listener = ep.clone();
        let limit = Arc::new(Semaphore::new(32));
        let signal = stop.clone();
        let udp_stats = Arc::new(RelayUdpMetrics::default());
        let listener_stats = udp_stats.clone();
        task(&stop, async move {
            while let Some(incoming) = listener.accept().await {
                let Ok(permit) = limit.clone().try_acquire_owned() else {
                    incoming.refuse();
                    continue;
                };
                let key = bytes;
                let s = signal.clone();
                let stats = listener_stats.clone();
                task(&signal, async move {
                    let _permit = permit;
                    let _ = async {
                        let conn =
                            tokio::time::timeout(Duration::from_secs(12), incoming).await??;
                        let (mut tx, mut rx) =
                            tokio::time::timeout(Duration::from_secs(8), conn.accept_bi())
                                .await??;
                        let mut proof = [0; 32];
                        tokio::time::timeout(Duration::from_secs(8), rx.read_exact(&mut proof))
                            .await??;
                        let mismatch = key
                            .iter()
                            .zip(proof.iter())
                            .fold(0u8, |a, (x, y)| a | (x ^ y));
                        if mismatch != 0 {
                            conn.close(1u32.into(), b"invalid invitation");
                            bail!("INVITE_DENIED")
                        };
                        tx.write_all(b"OK").await?;
                        tx.finish()?;
                        let udp = UdpSocket::bind("127.0.0.1:0").await?;
                        udp.connect(("127.0.0.1", base + 2)).await?;
                        udp_host(conn.clone(), udp, &s, stats);
                        let streams = Arc::new(Semaphore::new(64));
                        while let Ok((mut send, mut recv)) = conn.accept_bi().await {
                            let Ok(p) = streams.clone().try_acquire_owned() else {
                                break;
                            };
                            task(&s, async move {
                                let _p = p;
                                let _ = async {
                                    let channel = tokio::time::timeout(
                                        Duration::from_secs(5),
                                        recv.read_u8(),
                                    )
                                    .await??;
                                    if channel > 1 {
                                        bail!("TUNNEL_CHANNEL")
                                    };
                                    let mut tcp = tokio::time::timeout(
                                        Duration::from_secs(5),
                                        TcpStream::connect(("127.0.0.1", base + channel as u16)),
                                    )
                                    .await??;
                                    tcp.set_nodelay(true)?;
                                    let (mut rd, mut wr) = tcp.split();
                                    let a = async {
                                        tokio::io::copy(&mut recv, &mut wr).await?;
                                        wr.shutdown().await
                                    };
                                    let b = async {
                                        tokio::io::copy(&mut rd, &mut send).await?;
                                        send.finish().map_err(std::io::Error::other)
                                    };
                                    tokio::try_join!(a, b)?;
                                    Ok::<_, anyhow::Error>(())
                                }
                                .await;
                            });
                        }
                        Ok::<_, anyhow::Error>(())
                    }
                    .await;
                });
            }
        });
        Ok(Self {
            endpoint: ep,
            stop,
            invite,
            backend_port: base,
            game_port: base + 1,
            world_port: base + 2,
            remote_base: base,
            udp: udp_stats,
        })
    }
    pub async fn join(value: &str, custom: &str, root: &Path) -> Result<Self> {
        Self::join_mode(value, custom, PrivateRelay::load(root)?.as_ref(), false).await
    }
    async fn join_mode(
        value: &str,
        custom: &str,
        access: Option<&PrivateRelay>,
        relay_only: bool,
    ) -> Result<Self> {
        let ticket = Invite::parse(value)?;
        let fallback: Option<RelayUrl> = if custom.trim().is_empty() {
            None
        } else {
            Some(custom.parse().context("RELAY_URL")?)
        };
        let selected = access
            .and_then(|access| ticket.address.relay_urls().find(|url| **url == access.url))
            .or_else(|| ticket.address.relay_urls().next())
            .or(fallback.as_ref());
        let ep = if relay_only {
            online_endpoint(selected, access, true, Duration::from_secs(12)).await?
        } else {
            endpoint(selected, access, false).await?
        };
        let result = async {
            let conn = tokio::time::timeout(
                Duration::from_secs(25),
                ep.connect(ticket.address.clone(), ALPN),
            )
            .await??;
            let (mut tx, mut rx) = conn.open_bi().await?;
            tx.write_all(&hex::decode(&ticket.key)?).await?;
            tx.finish()?;
            let mut ack = [0; 2];
            tokio::time::timeout(Duration::from_secs(10), rx.read_exact(&mut ack)).await??;
            if &ack != b"OK" {
                bail!("INVITE_DENIED")
            }
            let backend = TcpListener::bind("127.0.0.1:0").await?;
            let game = TcpListener::bind("127.0.0.1:0").await?;
            let world = UdpSocket::bind("127.0.0.1:0").await?;
            Ok::<_, anyhow::Error>((conn, backend, game, world))
        }
        .await;
        let (conn, backend, game, world) = match result {
            Ok(v) => v,
            Err(e) => {
                ep.close().await;
                return Err(e.context("RELAY_CONNECT: Не удалось подключиться по приглашению"));
            }
        };
        let backend_port = backend.local_addr()?.port();
        let game_port = game.local_addr()?.port();
        let world_port = world.local_addr()?.port();
        let stop = CancellationToken::new();
        for (channel, listener) in [(0u8, backend), (1, game)] {
            let c = conn.clone();
            let signal = stop.clone();
            task(&stop, async move {
                let cap = Arc::new(Semaphore::new(64));
                while let Ok((mut tcp, _)) = listener.accept().await {
                    let Ok(p) = cap.clone().try_acquire_owned() else {
                        continue;
                    };
                    let c = c.clone();
                    task(&signal, async move {
                        let _p = p;
                        let _ = async {
                            tcp.set_nodelay(true)?;
                            let (mut tx, mut rx) = c.open_bi().await?;
                            tx.write_all(&[channel]).await?;
                            let (mut rd, mut wr) = tcp.split();
                            let a = async {
                                tokio::io::copy(&mut rd, &mut tx).await?;
                                tx.finish().map_err(std::io::Error::other)
                            };
                            let b = async {
                                tokio::io::copy(&mut rx, &mut wr).await?;
                                wr.shutdown().await
                            };
                            tokio::try_join!(a, b)?;
                            Ok::<_, anyhow::Error>(())
                        }
                        .await;
                    });
                }
            });
        }
        let udp = Arc::new(RelayUdpMetrics::default());
        udp_client(conn, world, &stop, udp.clone());
        Ok(Self {
            endpoint: ep,
            stop,
            invite: ticket.encode()?,
            backend_port,
            game_port,
            world_port,
            remote_base: ticket.base,
            udp,
        })
    }
}
// UDP stays datagram-based. Large game datagrams are fragmented with bounded reassembly.
fn send_packet(conn: &Connection, id: u32, packet: &[u8], stats: &RelayUdpMetrics) -> Result<()> {
    if packet.len() > 65507 {
        bail!("UDP_LIMIT")
    };
    let size = conn
        .max_datagram_size()
        .context("DATAGRAM_DISABLED")?
        .saturating_sub(8)
        .min(1000);
    if size < 64 {
        bail!("DATAGRAM_MTU")
    };
    let count = packet.len().max(1).div_ceil(size);
    if count > 1 {
        stats.fragmented.fetch_add(1, Ordering::Relaxed);
    }
    for i in 0..count {
        let mut frame = Vec::with_capacity(size + 8);
        frame.extend_from_slice(&id.to_be_bytes());
        frame.extend_from_slice(&(i as u16).to_be_bytes());
        frame.extend_from_slice(&(count as u16).to_be_bytes());
        frame.extend_from_slice(
            &packet[(i * size).min(packet.len())..((i + 1) * size).min(packet.len())],
        );
        if conn.datagram_send_buffer_space() < frame.len() {
            stats.buffer_pressure.fetch_add(1, Ordering::Relaxed);
        }
        conn.send_datagram(frame.into())?;
        stats.fragments.fetch_add(1, Ordering::Relaxed);
    }
    Ok(())
}
struct Assembly {
    created: Instant,
    parts: Vec<Option<Vec<u8>>>,
}
#[derive(Default)]
struct Packets(HashMap<u32, Assembly>);
impl Packets {
    fn push(&mut self, frame: &[u8]) -> Option<Vec<u8>> {
        self.0
            .retain(|_, a| a.created.elapsed() < Duration::from_secs(2));
        if frame.len() < 8 || frame.len() > 1008 {
            return None;
        };
        let id = u32::from_be_bytes(frame[..4].try_into().ok()?);
        let index = u16::from_be_bytes(frame[4..6].try_into().ok()?) as usize;
        let count = u16::from_be_bytes(frame[6..8].try_into().ok()?) as usize;
        if count == 0
            || count > 66
            || index >= count
            || self.0.len() >= 64 && !self.0.contains_key(&id)
        {
            return None;
        };
        let a = self.0.entry(id).or_insert_with(|| Assembly {
            created: Instant::now(),
            parts: vec![None; count],
        });
        if a.parts.len() != count {
            return None;
        };
        a.parts[index] = Some(frame[8..].to_vec());
        if a.parts.iter().all(Option::is_some) {
            let a = self.0.remove(&id)?;
            let out: Vec<u8> = a.parts.into_iter().flatten().flatten().collect();
            return (out.len() <= 65507).then_some(out);
        }
        None
    }
}
fn tunnel_udp(conn: &Connection, id: &mut u32, packet: &[u8], stats: &RelayUdpMetrics) {
    *id = id.wrapping_add(1);
    stats.received.fetch_add(1, Ordering::Relaxed);
    let status = if send_packet(conn, *id, packet, stats).is_ok() {
        &stats.sent
    } else {
        &stats.send_errors
    };
    status.fetch_add(1, Ordering::Relaxed);
}

fn udp_forwarded(result: std::io::Result<usize>, stats: &RelayUdpMetrics) {
    let status = if result.is_ok() {
        &stats.forwarded
    } else {
        &stats.forward_errors
    };
    status.fetch_add(1, Ordering::Relaxed);
}

fn udp_host(
    conn: Connection,
    socket: UdpSocket,
    stop: &CancellationToken,
    stats: Arc<RelayUdpMetrics>,
) {
    task(stop, async move {
        let mut buffer = vec![0; 65536];
        let mut packets = Packets::default();
        let mut id = 0u32;
        loop {
            tokio::select! {
                r = socket.recv(&mut buffer) => match r {
                    Ok(n) => tunnel_udp(&conn, &mut id, &buffer[..n], &stats),
                    Err(_) => break,
                },
                r = conn.read_datagram() => match r {
                    Ok(v) => if let Some(p) = packets.push(&v) {
                        udp_forwarded(socket.send(&p).await, &stats);
                    },
                    Err(_) => break,
                }
            }
        }
    });
}
fn udp_client(
    conn: Connection,
    socket: UdpSocket,
    stop: &CancellationToken,
    stats: Arc<RelayUdpMetrics>,
) {
    task(stop, async move {
        let mut buffer = vec![0; 65536];
        let mut packets = Packets::default();
        let mut peer: Option<SocketAddr> = None;
        let mut id = 0u32;
        loop {
            tokio::select! {
                r = socket.recv_from(&mut buffer) => match r {
                    Ok((n, addr)) if addr.ip().is_loopback() => {
                        peer = Some(addr);
                        tunnel_udp(&conn, &mut id, &buffer[..n], &stats);
                    }
                    Ok(_) => continue,
                    Err(_) => break,
                },
                r = conn.read_datagram() => match r {
                    Ok(v) => if let (Some(p), Some(peer)) = (packets.push(&v), peer) {
                        udp_forwarded(socket.send_to(&p, peer).await, &stats);
                    },
                    Err(_) => break,
                }
            }
        }
    });
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn malformed_invites_and_fragments_are_bounded() {
        for v in ["bscp://bad", "http://localhost", "bscp://"] {
            assert!(Invite::parse(v).is_err())
        }
        let mut p = Packets::default();
        assert!(p.push(&[0; 7]).is_none());
        assert!(p.push(&[0, 0, 0, 1, 0, 0, 255, 255]).is_none());
        assert!(p.push(&[0, 0, 0, 1, 0, 1, 0, 2, 2]).is_none());
        assert_eq!(p.push(&[0, 0, 0, 1, 0, 0, 0, 2, 1]), Some(vec![1, 2]));
    }
    #[tokio::test]
    async fn local_iroh_udp_preserves_bursts_and_counts_both_directions() {
        let server = Endpoint::builder(presets::N0)
            .alpns(vec![ALPN.to_vec()])
            .relay_mode(RelayMode::Disabled)
            .clear_address_lookup()
            .bind()
            .await
            .unwrap();
        let client = Endpoint::builder(presets::N0)
            .relay_mode(RelayMode::Disabled)
            .clear_address_lookup()
            .bind()
            .await
            .unwrap();
        let stop = CancellationToken::new();
        let timeout = Duration::from_secs(10);
        let addr = server.addr();
        let (host_conn, client_conn) = tokio::time::timeout(timeout, async {
            tokio::join!(
                async { server.accept().await.unwrap().await.unwrap() },
                async { client.connect(addr, ALPN).await.unwrap() },
            )
        })
        .await
        .unwrap();

        let game = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let host_udp = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        host_udp.connect(game.local_addr().unwrap()).await.unwrap();
        let client_udp = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let player = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        player
            .connect(client_udp.local_addr().unwrap())
            .await
            .unwrap();
        let host_stats = Arc::new(RelayUdpMetrics::default());
        let client_stats = Arc::new(RelayUdpMetrics::default());
        udp_host(host_conn, host_udp, &stop, host_stats.clone());
        udp_client(client_conn, client_udp, &stop, client_stats.clone());

        let mut packet = [0; 5000];
        for size in [25, 83, 975, 1000, 4096].into_iter().cycle().take(40) {
            let data = vec![81; size];
            player.send(&data).await.unwrap();
            let (n, addr) = tokio::time::timeout(timeout, game.recv_from(&mut packet))
                .await
                .unwrap()
                .unwrap();
            assert_eq!(&packet[..n], data);
            game.send_to(&packet[..n], addr).await.unwrap();
            let n = tokio::time::timeout(timeout, player.recv(&mut packet))
                .await
                .unwrap()
                .unwrap();
            assert_eq!(&packet[..n], data);
        }
        for id in 0..48 {
            player.send(&[id; 160]).await.unwrap();
        }
        let mut received = std::collections::HashSet::new();
        for _ in 0..48 {
            let (n, _) = tokio::time::timeout(timeout, game.recv_from(&mut packet))
                .await
                .unwrap()
                .unwrap();
            assert_eq!(n, 160);
            assert!(received.insert(packet[0]));
        }
        assert_eq!(received, (0..48).collect());
        assert_eq!(client_stats.sent.load(Ordering::Relaxed), 88);
        assert_eq!(host_stats.sent.load(Ordering::Relaxed), 40);
        assert!(host_stats.fragmented.load(Ordering::Relaxed) >= 8);
        assert_eq!(host_stats.forward_errors.load(Ordering::Relaxed), 0);
        assert_eq!(client_stats.send_errors.load(Ordering::Relaxed), 0);
        stop.cancel();
        let _ = tokio::join!(server.close(), client.close());
    }

    #[tokio::test]
    #[ignore = "explicit public Iroh relay integration; no player data"]
    async fn public_relay_carries_tcp_udp_and_revokes_invitation() {
        let (base, backend, game, udp) = (40000..50000)
            .step_by(3)
            .find_map(|base| {
                Some((
                    base,
                    std::net::TcpListener::bind(("127.0.0.1", base)).ok()?,
                    std::net::TcpListener::bind(("127.0.0.1", base + 1)).ok()?,
                    std::net::UdpSocket::bind(("127.0.0.1", base + 2)).ok()?,
                ))
            })
            .unwrap();
        backend.set_nonblocking(true).unwrap();
        game.set_nonblocking(true).unwrap();
        udp.set_nonblocking(true).unwrap();
        let cancellation = CancellationToken::new();
        for listener in [backend, game] {
            let listener = TcpListener::from_std(listener).unwrap();
            let signal = cancellation.clone();
            task(&cancellation, async move {
                while let Ok((tcp, _)) = listener.accept().await {
                    task(&signal, async move {
                        let (mut rd, mut wr) = tcp.into_split();
                        let _ = tokio::io::copy(&mut rd, &mut wr).await;
                    });
                }
            });
        }
        let socket = UdpSocket::from_std(udp).unwrap();
        task(&cancellation, async move {
            let mut b = vec![0; 65536];
            while let Ok((n, a)) = socket.recv_from(&mut b).await {
                let _ = socket.send_to(&b[..n], a).await;
            }
        });
        // Force the relay path and optionally select a single origin for reproducible comparisons.
        let target = std::env::var("BSCP_RELAY_QA_URL").unwrap_or_default();
        let private = std::env::var_os("BSCP_RELAY_QA_ROOT").map(|root| {
            PrivateRelay::load(Path::new(&root))
                .unwrap()
                .expect("private relay QA configuration")
        });
        if target.is_empty() && private.is_none() {
            assert!(
                !ranked_relays().await.unwrap().is_empty(),
                "no public relay passed the HTTPS probes"
            );
        }
        if let Some(access) = private.as_ref() {
            let wrong = PrivateRelay {
                url: access.url.clone(),
                token: "00".repeat(32),
                ca: access.ca.clone(),
            };
            assert!(
                online_endpoint(Some(&wrong.url), Some(&wrong), true, Duration::from_secs(5))
                    .await
                    .is_err(),
                "private relay accepted an incorrect relay token"
            );
        }
        let start = Instant::now();
        let host = Tunnel::host_mode(base, &target, private.as_ref(), true)
            .await
            .expect("host relay online");
        let selected = host.relay_host().expect("selected relay");
        eprintln!(
            "Relay online {selected} after {} ms",
            start.elapsed().as_millis()
        );
        if private.is_some() {
            let mut invalid = Invite::parse(&host.invite).unwrap();
            invalid.key = "00".repeat(32);
            assert!(
                Tunnel::join_mode(&invalid.encode().unwrap(), "", private.as_ref(), true)
                    .await
                    .is_err()
            );
        }
        let start = Instant::now();
        let client = Tunnel::join_mode(&host.invite, "", private.as_ref(), true)
            .await
            .expect("peer tunnel connected");
        eprintln!(
            "Peer tunnel connected after {} ms",
            start.elapsed().as_millis()
        );
        for port in [client.backend_port, client.game_port] {
            let mut tcp = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
            tcp.write_all(b"BSCP-TCP-PROBE").await.unwrap();
            let mut out = [0; 14];
            tokio::time::timeout(Duration::from_secs(12), tcp.read_exact(&mut out))
                .await
                .unwrap()
                .unwrap();
            assert_eq!(&out, b"BSCP-TCP-PROBE");
        }
        let udp = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        udp.connect(("127.0.0.1", client.world_port)).await.unwrap();
        let mut round_trips = Vec::new();
        let sizes = [40, 975, 1000];
        for (index, size) in sizes.into_iter().cycle().take(12).enumerate() {
            let payload = vec![index as u8; size];
            let start = Instant::now();
            udp.send(&payload).await.unwrap();
            let reply = tokio::time::timeout(Duration::from_secs(3), async {
                let mut out = [0; 1000];
                loop {
                    let n = udp.recv(&mut out).await.unwrap();
                    if out[..n] == payload {
                        break;
                    }
                }
            })
            .await;
            if reply.is_ok() {
                round_trips.push(start.elapsed().as_millis());
            } else {
                eprintln!("UDP echo timed out for sample {index}, {size} bytes");
            }
        }
        round_trips.sort_unstable();
        let received = round_trips.len();
        eprintln!(
            "UDP echoes: {received}/12, median RTT {:?} ms",
            round_trips.get(received / 2)
        );
        assert_eq!(received, 12, "UDP delivery through forced relay");
        let local = client.backend_port;
        drop(client);
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert!(TcpStream::connect(("127.0.0.1", local)).await.is_err());
        drop(host);
        cancellation.cancel();
    }
}
