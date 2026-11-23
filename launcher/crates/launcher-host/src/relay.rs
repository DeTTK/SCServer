//! Capability-scoped game tunnel. It exposes only backend, gateway and world traffic.
use anyhow::{Context, Result, bail};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use iroh::{
    Endpoint, EndpointAddr, RelayMode,
    endpoint::{Connection, presets},
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::Arc,
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
        if ticket
            .address
            .relay_urls()
            .any(|u| u.scheme() != "https" || !u.username().is_empty() || u.password().is_some())
        {
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
async fn endpoint(custom: &str, relay_only: bool) -> Result<Endpoint> {
    let mut builder = Endpoint::builder(presets::N0)
        .alpns(vec![ALPN.to_vec()])
        .clear_address_lookup();
    if !custom.trim().is_empty() {
        let u: iroh::RelayUrl = custom.parse()?;
        if u.scheme() != "https" {
            bail!("RELAY_URL: HTTPS required")
        };
        builder = builder.relay_mode(RelayMode::custom([u]));
    }
    if relay_only {
        builder = builder.clear_ip_transports();
    }
    Ok(builder.bind().await?)
}
fn task(stop: &CancellationToken, future: impl std::future::Future<Output = ()> + Send + 'static) {
    let s = stop.clone();
    tokio::spawn(async move {
        tokio::select! {_=s.cancelled()=>{},_=future=>{}}
    });
}
impl Tunnel {
    pub async fn host(base: u16, custom: &str) -> Result<Self> {
        Self::host_mode(base, custom, false).await
    }
    async fn host_mode(base: u16, custom: &str, relay_only: bool) -> Result<Self> {
        let ep = endpoint(custom, relay_only).await?;
        if tokio::time::timeout(Duration::from_secs(20), ep.online())
            .await
            .is_err()
        {
            ep.close().await;
            bail!("RELAY_OFFLINE: Сеть Iroh недоступна")
        }
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
        task(&stop, async move {
            while let Some(incoming) = listener.accept().await {
                let Ok(permit) = limit.clone().try_acquire_owned() else {
                    incoming.refuse();
                    continue;
                };
                let key = bytes;
                let s = signal.clone();
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
                        udp_host(conn.clone(), udp, &s);
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
        })
    }
    pub async fn join(value: &str, custom: &str) -> Result<Self> {
        Self::join_mode(value, custom, false).await
    }
    async fn join_mode(value: &str, custom: &str, relay_only: bool) -> Result<Self> {
        let ticket = Invite::parse(value)?;
        let ep = endpoint(custom, relay_only).await?;
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
        udp_client(conn, world, &stop);
        Ok(Self {
            endpoint: ep,
            stop,
            invite: ticket.encode()?,
            backend_port,
            game_port,
            world_port,
            remote_base: ticket.base,
        })
    }
}
// UDP stays datagram-based. Large game datagrams are fragmented with bounded reassembly.
fn send_packet(conn: &Connection, id: u32, packet: &[u8]) -> Result<()> {
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
    for i in 0..count {
        let mut frame = Vec::with_capacity(size + 8);
        frame.extend_from_slice(&id.to_be_bytes());
        frame.extend_from_slice(&(i as u16).to_be_bytes());
        frame.extend_from_slice(&(count as u16).to_be_bytes());
        frame.extend_from_slice(
            &packet[(i * size).min(packet.len())..((i + 1) * size).min(packet.len())],
        );
        conn.send_datagram(frame.into())?;
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
fn udp_host(conn: Connection, socket: UdpSocket, stop: &CancellationToken) {
    task(stop, async move {
        let mut buffer = vec![0; 65536];
        let mut packets = Packets::default();
        let mut id = 0u32;
        loop {
            tokio::select! {
                r=socket.recv(&mut buffer)=>match r{Ok(n)=>{id=id.wrapping_add(1);let _=send_packet(&conn,id,&buffer[..n]);},Err(_)=>break},
                r=conn.read_datagram()=>match r{Ok(v)=>if let Some(p)=packets.push(&v){let _=socket.send(&p).await;},Err(_)=>break}
            }
        }
    });
}
fn udp_client(conn: Connection, socket: UdpSocket, stop: &CancellationToken) {
    task(stop, async move {
        let mut buffer = vec![0; 65536];
        let mut packets = Packets::default();
        let mut peer: Option<SocketAddr> = None;
        let mut id = 0u32;
        loop {
            tokio::select! {
                r=socket.recv_from(&mut buffer)=>match r{Ok((n,addr))=>{if !addr.ip().is_loopback(){continue};peer=Some(addr);id=id.wrapping_add(1);let _=send_packet(&conn,id,&buffer[..n]);},Err(_)=>break},
                r=conn.read_datagram()=>match r{Ok(v)=>if let (Some(p),Some(peer))=(packets.push(&v),peer){let _=socket.send_to(&p,peer).await;},Err(_)=>break}
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
        // Disable all IP transports on both endpoints: success proves the relay path.
        let host = Tunnel::host_mode(base, "", true).await.unwrap();
        let mut invalid = Invite::parse(&host.invite).unwrap();
        invalid.key = "00".repeat(32);
        assert!(
            Tunnel::join_mode(&invalid.encode().unwrap(), "", true)
                .await
                .is_err()
        );
        let client = Tunnel::join_mode(&host.invite, "", true).await.unwrap();
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
        for size in [40, 4096, 32000] {
            let payload = vec![37; size];
            udp.send(&payload).await.unwrap();
            let mut out = vec![0; 65536];
            let n = tokio::time::timeout(Duration::from_secs(12), udp.recv(&mut out))
                .await
                .unwrap()
                .unwrap();
            assert_eq!(&out[..n], payload);
        }
        let local = client.backend_port;
        drop(client);
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert!(TcpStream::connect(("127.0.0.1", local)).await.is_err());
        drop(host);
        cancellation.cancel();
    }
}
