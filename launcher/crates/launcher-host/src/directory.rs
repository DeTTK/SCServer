//! Public directory. Iroh authenticates the owner of each short-lived listing.
use crate::{relay, relay_catalog::bscp_relay_ca};
use anyhow::{Context, Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use bscp_launcher_core::{Layout, Lease, write_json};
use iroh::{
    Endpoint, EndpointAddr, EndpointId, RelayMode, RelayUrl, SecretKey, endpoint::presets,
    tls::CaTlsConfig,
};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use std::{
    collections::{HashMap, HashSet},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

const ALPN: &[u8] = b"bscp/server-directory/1";
const TTL: Duration = Duration::from_secs(70);
const DEADLINE: Duration = Duration::from_secs(7);
const CONFIG_REFRESH: Duration = Duration::from_secs(120);
const MAX_LISTINGS: usize = 64;
const CATALOG_URL: &str =
    "https://raw.githubusercontent.com/DeTTK/SCServer/main/launcher/server-directory.json";
const EMBEDDED_ADDRESS: &str = include_str!("../../../server-directory.json");
const PUBLIC_RELAYS: &str = include_str!("../../../relays.json");
pub const ICON_SIDE: u32 = 64;
pub const ICON_BYTES: usize = ICON_SIDE as usize * ICON_SIDE as usize * 4;
pub const ICON_PATH: &str = "config/public-server-icon.json";

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PublicServer {
    pub name: String,
    pub invite: String,
    pub players: u16,
    pub icon: Option<String>,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct DirectoryAddress {
    endpoint: EndpointId,
    relay: RelayUrl,
}

impl DirectoryAddress {
    fn address(&self) -> EndpointAddr {
        EndpointAddr::new(self.endpoint).with_relay_url(self.relay.clone())
    }

    fn parse(bytes: &[u8]) -> Result<Self> {
        ensure!(bytes.len() <= 4096, "DIRECTORY_CONFIG_SIZE");
        let address: Self = serde_json::from_slice(bytes)?;
        ensure!(relay::relay_origin(&address.relay), "DIRECTORY_RELAY");
        Ok(address)
    }

    async fn download() -> Result<Self> {
        let mut response = reqwest::Client::builder()
            .timeout(Duration::from_secs(4))
            .redirect(reqwest::redirect::Policy::none())
            .build()?
            .get(CATALOG_URL)
            .send()
            .await?
            .error_for_status()?;
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await? {
            ensure!(chunk.len() <= 4096 - bytes.len(), "DIRECTORY_CONFIG_SIZE");
            bytes.extend_from_slice(&chunk);
        }
        Self::parse(&bytes)
    }
}

#[derive(Default)]
struct DirectoryConfig {
    downloaded: Option<DirectoryAddress>,
    last_fetch: Option<Instant>,
    fetch: Option<tokio::task::JoinHandle<Result<DirectoryAddress>>>,
}

impl DirectoryConfig {
    async fn address(&mut self, layout: &Layout) -> Result<DirectoryAddress> {
        match std::fs::read(layout.at("config/server-directory.json")) {
            Ok(bytes) => return DirectoryAddress::parse(&bytes),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
        if self.fetch.as_ref().is_some_and(|fetch| fetch.is_finished()) {
            if let Ok(Ok(address)) = self.fetch.take().unwrap().await {
                self.downloaded = Some(address);
            }
        }
        if self
            .last_fetch
            .is_none_or(|last| last.elapsed() >= CONFIG_REFRESH)
        {
            self.last_fetch = Some(Instant::now());
            self.fetch = Some(tokio::spawn(DirectoryAddress::download()));
        }
        match &self.downloaded {
            Some(address) => Ok(address.clone()),
            None => DirectoryAddress::parse(EMBEDDED_ADDRESS.as_bytes()),
        }
    }
}

#[derive(Serialize, Deserialize)]
#[serde(tag = "operation", content = "server", rename_all = "snake_case")]
enum Request {
    List,
    Publish(PublicServer),
    Remove,
}

#[derive(Serialize, Deserialize)]
#[serde(tag = "result", content = "servers", rename_all = "snake_case")]
enum Response {
    Ok,
    List(Vec<PublicServer>),
    Rejected,
}

pub fn encode_icon(rgba: &[u8]) -> Result<String> {
    ensure!(rgba.len() == ICON_BYTES, "DIRECTORY_ICON_SIZE");
    Ok(STANDARD.encode(rgba))
}

pub fn decode_icon(encoded: &str) -> Result<Vec<u8>> {
    ensure!(
        encoded.len() == ICON_BYTES.div_ceil(3) * 4,
        "DIRECTORY_ICON_SIZE"
    );
    let bytes = STANDARD.decode(encoded)?;
    ensure!(bytes.len() == ICON_BYTES, "DIRECTORY_ICON_SIZE");
    Ok(bytes)
}

pub(crate) fn public_relay(url: &RelayUrl) -> bool {
    let known: serde_json::Value = serde_json::from_str(PUBLIC_RELAYS).expect("embedded relays");
    known["relays"].as_array().is_some_and(|relays| {
        relays
            .iter()
            .filter_map(|value| value.as_str())
            .filter_map(|value| value.parse::<RelayUrl>().ok())
            .any(|relay| relay == *url)
    })
}

fn verify_listing(server: &PublicServer) -> Result<EndpointId> {
    let name = server.name.trim();
    ensure!(
        !name.is_empty() && name.chars().count() <= 80 && !name.chars().any(char::is_control),
        "DIRECTORY_NAME"
    );
    let (id, relay) = relay::public_ticket(&server.invite)?;
    ensure!(public_relay(&relay), "DIRECTORY_RELAY");
    if let Some(icon) = &server.icon {
        decode_icon(icon)?;
    }
    Ok(id)
}

type Listings = HashMap<EndpointId, (Instant, PublicServer)>;

fn handle(listings: &mut Listings, peer: EndpointId, request: Request, now: Instant) -> Response {
    listings.retain(|_, (seen, _)| now.duration_since(*seen) < TTL);
    match request {
        Request::List => {
            let mut rows: Vec<_> = listings
                .values()
                .map(|(_, server)| server.clone())
                .collect();
            rows.sort_unstable_by(|a, b| {
                b.players
                    .cmp(&a.players)
                    .then_with(|| a.name.cmp(&b.name))
                    .then_with(|| a.invite.cmp(&b.invite))
            });
            Response::List(rows)
        }
        Request::Publish(server) => {
            if verify_listing(&server).ok() != Some(peer)
                || (!listings.contains_key(&peer) && listings.len() >= MAX_LISTINGS)
            {
                return Response::Rejected;
            }
            listings.insert(peer, (now, server));
            Response::Ok
        }
        Request::Remove => {
            listings.remove(&peer);
            Response::Ok
        }
    }
}

async fn endpoint(relay: &RelayUrl, identity: Option<SecretKey>) -> Result<Endpoint> {
    let mut builder = Endpoint::builder(presets::N0)
        .clear_address_lookup()
        .relay_mode(RelayMode::custom([relay.clone()]));
    if let Some(ca) = bscp_relay_ca(relay) {
        builder = builder.ca_tls_config(CaTlsConfig::custom_roots([ca.into()]));
    }
    if let Some(key) = identity {
        builder = builder
            .secret_key(key)
            .alpns(vec![ALPN.to_vec()])
            .clear_ip_transports();
    }
    Ok(builder.bind().await?)
}

async fn exchange<T: DeserializeOwned>(
    ep: &Endpoint,
    address: EndpointAddr,
    request: Request,
    limit: usize,
) -> Result<T> {
    tokio::time::timeout(DEADLINE, async {
        let connection = ep
            .connect(address, ALPN)
            .await
            .context("DIRECTORY_CONNECT")?;
        let (mut tx, mut rx) = connection.open_bi().await?;
        tx.write_all(&serde_json::to_vec(&request)?).await?;
        tx.finish()?;
        let response =
            serde_json::from_slice(&rx.read_to_end(limit).await.context("DIRECTORY_REPLY")?)?;
        connection.close(0u32.into(), b"directory request complete");
        Ok(response)
    })
    .await
    .context("DIRECTORY_TIMEOUT")?
}

#[derive(Default)]
pub(crate) struct Publisher {
    config: DirectoryConfig,
    link: Option<(RelayUrl, Endpoint)>,
}

impl Publisher {
    pub(crate) async fn advertise(
        &mut self,
        layout: &Layout,
        ep: &Endpoint,
        server: Option<PublicServer>,
    ) -> Result<()> {
        let address = self.config.address(layout).await?;
        let request = server.map_or(Request::Remove, Request::Publish);
        let outbound =
            if !ep.is_closed() && ep.addr().relay_urls().any(|relay| *relay == address.relay) {
                ep
            } else {
                // The directory relay has its own TLS trust. Use the game identity on that route,
                // including when removing a listing after the game endpoint has closed.
                if self.link.as_ref().is_none_or(|(relay, link)| {
                    *relay != address.relay || link.id() != ep.id() || link.is_closed()
                }) {
                    if let Some((_, old)) = self.link.take() {
                        tokio::spawn(async move { old.close().await });
                    }
                    let link = endpoint(&address.relay, Some(ep.secret_key().clone())).await?;
                    self.link = Some((address.relay.clone(), link));
                }
                &self.link.as_ref().expect("publication link initialized").1
            };
        let response: Response = exchange(outbound, address.address(), request, 128).await?;
        ensure!(matches!(response, Response::Ok), "DIRECTORY_REJECTED");
        Ok(())
    }
}

pub struct Browser {
    runtime: tokio::runtime::Runtime,
    config: DirectoryConfig,
    link: Option<(DirectoryAddress, Endpoint)>,
    failures: u8,
    probing: Arc<Mutex<HashSet<String>>>,
    probe_slots: Arc<tokio::sync::Semaphore>,
}

impl Browser {
    pub fn new() -> Result<Self> {
        Ok(Self {
            runtime: tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()?,
            config: DirectoryConfig::default(),
            link: None,
            failures: 0,
            probing: Arc::default(),
            probe_slots: Arc::new(tokio::sync::Semaphore::new(8)),
        })
    }

    pub fn list(&mut self, layout: &Layout) -> Result<Vec<PublicServer>> {
        let link = &mut self.link;
        let config = &mut self.config;
        let failures = &mut self.failures;
        self.runtime.block_on(async {
            let address = config.address(layout).await?;
            if link
                .as_ref()
                .is_none_or(|(prior, ep)| *prior != address || ep.is_closed())
            {
                let ep = endpoint(&address.relay, None).await?;
                if let Some((_, old)) = link.replace((address, ep)) {
                    tokio::spawn(async move { old.close().await });
                }
                *failures = 0;
            }
            let (address, ep) = link.as_ref().expect("directory link initialized");
            let response = exchange(ep, address.address(), Request::List, 2 * 1024 * 1024).await;
            let Response::List(mut servers) = (match response {
                Ok(response) => {
                    *failures = 0;
                    response
                }
                Err(error) => {
                    *failures += 1;
                    // Give the relay a chance to reconnect, then discard a stuck endpoint.
                    if *failures >= 2 {
                        if let Some((_, old)) = link.take() {
                            tokio::spawn(async move { old.close().await });
                        }
                    }
                    return Err(error);
                }
            }) else {
                anyhow::bail!("DIRECTORY_REJECTED")
            };
            servers.truncate(MAX_LISTINGS);
            servers.retain(|server| verify_listing(server).is_ok());
            Ok(servers)
        })
    }

    pub fn measure(
        &self,
        servers: &[PublicServer],
        measured: impl Fn(String, Option<u64>) + Clone + Send + 'static,
    ) {
        for server in servers {
            let invite = server.invite.clone();
            {
                let mut probing = self.probing.lock().unwrap();
                if probing.len() >= MAX_LISTINGS || !probing.insert(invite.clone()) {
                    continue;
                }
            }
            let probing = self.probing.clone();
            let slots = self.probe_slots.clone();
            let measured = measured.clone();
            self.runtime.spawn(async move {
                if let Ok(_slot) = slots.acquire().await {
                    let result = tokio::time::timeout(DEADLINE, async {
                        let (_, relay) = relay::public_ticket(&invite)?;
                        let ep = endpoint(&relay, None).await?;
                        let ping = relay::public_ping(&ep, &invite).await;
                        tokio::spawn(async move { ep.close().await });
                        ping
                    })
                    .await;
                    probing.lock().unwrap().remove(&invite);
                    measured(invite, result.ok().and_then(Result::ok));
                }
            });
        }
    }
}

async fn serve(ep: Endpoint, ttl_list: Arc<Mutex<Listings>>) -> Result<()> {
    let slots = Arc::new(tokio::sync::Semaphore::new(48));
    while let Some(incoming) = ep.accept().await {
        let Ok(slot) = slots.clone().try_acquire_owned() else {
            incoming.refuse();
            continue;
        };
        let listings = ttl_list.clone();
        tokio::spawn(async move {
            let _slot = slot;
            let _ = tokio::time::timeout(DEADLINE, async {
                let connection = incoming.await?;
                let (mut tx, mut rx) = connection.accept_bi().await?;
                let request = serde_json::from_slice(&rx.read_to_end(32 * 1024).await?)?;
                let response = handle(
                    &mut listings.lock().unwrap(),
                    connection.remote_id(),
                    request,
                    Instant::now(),
                );
                tx.write_all(&serde_json::to_vec(&response)?).await?;
                tx.finish()?;
                // Keep the connection alive until the peer has read the reply.
                connection.closed().await;
                Ok::<_, anyhow::Error>(())
            })
            .await;
        });
    }
    Ok(())
}

pub async fn run(layout: &Layout, relay: &str) -> Result<()> {
    layout.init()?;
    let _lease = Lease::acquire(&layout.at("state/public-directory.lock"))?;
    let relay: RelayUrl = relay.parse()?;
    ensure!(relay::relay_origin(&relay), "DIRECTORY_RELAY");
    let key_path = layout.at("config/public-directory-private.json");
    let key = if key_path.exists() {
        serde_json::from_slice::<SecretKey>(&std::fs::read(&key_path)?)?
    } else {
        let key = SecretKey::generate();
        write_json(&key_path, &key)?;
        key
    };
    let public = DirectoryAddress {
        endpoint: key.public(),
        relay,
    };
    let ep = endpoint(&public.relay, Some(key)).await?;
    tokio::time::timeout(Duration::from_secs(15), ep.online())
        .await
        .context("DIRECTORY_RELAY_OFFLINE")?;
    write_json(&layout.at("config/server-directory.json"), &public)?;
    println!("{}", serde_json::to_string(&public)?);
    tokio::select! {
        result = serve(ep.clone(), Arc::default()) => result?,
        interrupted = tokio::signal::ctrl_c() => interrupted?,
    }
    ep.close().await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::engine::general_purpose::URL_SAFE_NO_PAD;

    fn listing(id: EndpointId) -> PublicServer {
        let relay: RelayUrl = "https://tomke.myddns.me:8843/".parse().unwrap();
        let address = EndpointAddr::new(id).with_relay_url(relay);
        let body = serde_json::to_vec(&serde_json::json!({
            "version": 1, "address": address, "key": "7c".repeat(32), "base": 29545,
        }))
        .unwrap();
        PublicServer {
            name: "Кordon".into(),
            invite: format!("bscp://{}", URL_SAFE_NO_PAD.encode(body)),
            players: 2,
            icon: Some(encode_icon(&[128; ICON_BYTES]).unwrap()),
        }
    }

    #[test]
    fn owner_controls_listing_and_absent_hosts_expire() {
        let owner = SecretKey::generate().public();
        let other = SecretKey::generate().public();
        let mut entries = Listings::new();
        let now = Instant::now();
        assert!(matches!(
            handle(&mut entries, other, Request::Publish(listing(owner)), now),
            Response::Rejected
        ));
        assert!(matches!(
            handle(&mut entries, owner, Request::Publish(listing(owner)), now),
            Response::Ok
        ));
        handle(&mut entries, other, Request::Remove, now);
        assert_eq!(entries.len(), 1);
        handle(&mut entries, owner, Request::Remove, now);
        assert!(entries.is_empty());
        handle(&mut entries, owner, Request::Publish(listing(owner)), now);
        let Response::List(servers) = handle(&mut entries, other, Request::List, now + TTL) else {
            panic!()
        };
        assert!(servers.is_empty());
        let mut invalid = listing(owner);
        invalid.icon = Some("abcd".into());
        assert!(verify_listing(&invalid).is_err());
    }

    #[tokio::test]
    async fn publication_and_discovery_use_authenticated_iroh_connections() {
        let listener = Endpoint::builder(presets::N0)
            .relay_mode(RelayMode::Disabled)
            .alpns(vec![ALPN.to_vec()])
            .bind()
            .await
            .unwrap();
        let publisher = Endpoint::builder(presets::N0)
            .relay_mode(RelayMode::Disabled)
            .bind()
            .await
            .unwrap();
        let reader = Endpoint::builder(presets::N0)
            .relay_mode(RelayMode::Disabled)
            .bind()
            .await
            .unwrap();
        let task = tokio::spawn(serve(listener.clone(), Arc::default()));
        let service = listener.addr();
        let response: Response = exchange(
            &publisher,
            service.clone(),
            Request::Publish(listing(publisher.id())),
            128,
        )
        .await
        .unwrap();
        assert!(matches!(response, Response::Ok));
        let response: Response = exchange(&reader, service.clone(), Request::List, 32768)
            .await
            .unwrap();
        let Response::List(servers) = response else {
            panic!()
        };
        assert_eq!(servers.len(), 1);
        assert_eq!(servers[0].players, 2);
        assert_eq!(
            decode_icon(servers[0].icon.as_deref().unwrap())
                .unwrap()
                .len(),
            ICON_BYTES
        );
        let _: Response = exchange(&publisher, service.clone(), Request::Remove, 128)
            .await
            .unwrap();
        let response: Response = exchange(&reader, service, Request::List, 128)
            .await
            .unwrap();
        assert!(matches!(response, Response::List(servers) if servers.is_empty()));
        listener.close().await;
        task.await.unwrap().unwrap();
    }

    #[tokio::test(flavor = "multi_thread")]
    #[ignore = "requires an explicitly configured directory and a public game relay"]
    async fn deployed_directory_lists_a_joinable_game() -> Result<()> {
        let root = std::env::var_os("BSCP_DIRECTORY_QA_ROOT").context("BSCP_DIRECTORY_QA_ROOT")?;
        let relay = std::env::var("BSCP_DIRECTORY_QA_RELAY").context("BSCP_DIRECTORY_QA_RELAY")?;
        let layout = Layout::new(root);
        let host = relay::Tunnel::host(29994, &relay, &layout.root).await?;
        let backend = tokio::net::TcpListener::bind("127.0.0.1:29994").await?;
        let tcp = tokio::spawn(async move {
            use tokio::io::AsyncWriteExt;
            let (mut connection, _) = backend.accept().await.unwrap();
            connection.write_all(b"ready").await.unwrap();
        });
        let server = PublicServer {
            name: "BSCPCRAFT directory QA".into(),
            invite: host.public_invite()?,
            players: 2,
            icon: Some(encode_icon(&[255; ICON_BYTES])?),
        };
        let endpoint = host.directory_endpoint();
        let mut publisher = Publisher::default();
        let started = Instant::now();
        publisher
            .advertise(&layout, &endpoint, Some(server.clone()))
            .await?;
        let publish_ms = started.elapsed().as_millis();
        let result: Result<()> = async {
            let address = DirectoryConfig::default().address(&layout).await?;
            let reader = super::endpoint(&address.relay, None).await?;
            let started = Instant::now();
            let reply: Response = exchange(&reader, address.address(), Request::List, 2*1024*1024).await?;
            let list_ms = started.elapsed().as_millis();
            ensure!(matches!(reply, Response::List(list) if list.iter().any(|row|
                row.invite == server.invite && row.players == 2 && row.icon == server.icon)));
            let (_, game_relay) = relay::public_ticket(&server.invite)?;
            let probe = super::endpoint(&game_relay, None).await?;
            let ping = tokio::time::timeout(DEADLINE, relay::public_ping(&probe, &server.invite)).await??;
            probe.close().await;
            ensure!(ping > 0);
            let join = relay::Tunnel::join(&server.invite, &relay, &layout.root).await?;
            let mut stream = tokio::net::TcpStream::connect(("127.0.0.1", join.backend_port)).await?;
            use tokio::io::AsyncReadExt;
            let mut expected = [0; 5];
            tokio::time::timeout(DEADLINE, stream.read_exact(&mut expected)).await??;
            ensure!(&expected == b"ready");
            println!("World published in {publish_ms} ms, listed in {list_ms} ms, ping {ping} ms, TCP data received");
            publisher.advertise(&layout, &endpoint, None).await?;
            let reply: Response = exchange(&reader, address.address(), Request::List, 2*1024*1024).await?;
            ensure!(matches!(reply, Response::List(list) if list.iter().all(|row| row.invite != server.invite)));
            reader.close().await;
            Ok(())
        }.await;
        if result.is_err() {
            let _ = publisher.advertise(&layout, &endpoint, None).await;
        }
        tcp.abort();
        result
    }

    #[test]
    #[ignore = "uses a public game relay for an isolated directory and game tunnels"]
    fn isolated_directory_refreshes_during_ping_timeouts_and_recovers_after_restart() -> Result<()>
    {
        use std::sync::mpsc;
        let relay_url =
            std::env::var("BSCP_DIRECTORY_QA_RELAY").context("BSCP_DIRECTORY_QA_RELAY")?;
        let relay: RelayUrl = relay_url.parse()?;
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()?;
        let temp = tempfile::tempdir()?;
        let layout = Layout::new(temp.path());
        layout.init()?;
        let key = SecretKey::generate();
        let public = DirectoryAddress {
            endpoint: key.public(),
            relay: relay.clone(),
        };
        write_json(&layout.at("config/server-directory.json"), &public)?;
        let start_directory = || {
            rt.block_on(async {
                let link = endpoint(&relay, Some(key.clone())).await?;
                tokio::time::timeout(Duration::from_secs(15), link.online()).await?;
                let task = tokio::spawn(serve(link.clone(), Arc::default()));
                Ok::<_, anyhow::Error>((link, task))
            })
        };
        let (service, task) = start_directory()?;
        let (a, b) = rt.block_on(async {
            tokio::try_join!(
                relay::Tunnel::host(29991, &relay_url, &layout.root),
                relay::Tunnel::host(29994, &relay_url, &layout.root)
            )
        })?;
        let result = (|| {
            let mut row_a = listing(a.directory_endpoint().id());
            row_a.invite = a.public_invite()?;
            let mut row_b = listing(b.directory_endpoint().id());
            row_b.name = "Мир Б".to_owned();
            row_b.invite = b.public_invite()?;
            row_b.players = 1;
            let mut pub_a = Publisher::default();
            let mut pub_b = Publisher::default();
            let mut browser = Browser::new()?;
            assert!(browser.list(&layout)?.is_empty());
            let started = Instant::now();
            rt.block_on(pub_a.advertise(&layout, &a.directory_endpoint(), Some(row_a.clone())))?;
            assert_eq!(browser.list(&layout)?.first().unwrap().invite, row_a.invite);
            println!(
                "First isolated listing visible in {} ms",
                started.elapsed().as_millis()
            );

            let dead: Vec<_> = (0..24)
                .map(|_| listing(SecretKey::generate().public()))
                .collect();
            let (send, probes) = mpsc::channel();
            browser.measure(&dead, move |_, delay| {
                let _ = send.send(delay);
            });
            let started = Instant::now();
            rt.block_on(pub_b.advertise(&layout, &b.directory_endpoint(), Some(row_b.clone())))?;
            assert_eq!(browser.list(&layout)?.len(), 2);
            println!(
                "Second listing visible in {} ms with 24 unresponsive ping targets",
                started.elapsed().as_millis()
            );
            assert!(
                probes.try_iter().count() < dead.len(),
                "listing was blocked by ping probes"
            );
            row_b.players = 3;
            rt.block_on(pub_b.advertise(&layout, &b.directory_endpoint(), Some(row_b.clone())))?;
            let rows = browser.list(&layout)?;
            assert_eq!(rows[0].invite, row_b.invite);
            assert_eq!(rows[0].players, 3);
            assert_eq!(rows[0].icon, row_b.icon);

            rt.block_on(async {
                task.abort();
                service.close().await;
            });
            let outage = Instant::now();
            assert!(browser.list(&layout).is_err());
            assert!(browser.list(&layout).is_err());
            assert!(
                browser.link.is_none(),
                "two failures must discard the stale connection"
            );
            println!(
                "Both failed reads returned in {} ms total",
                outage.elapsed().as_millis()
            );
            let (service, task) = start_directory()?;
            let recovered = Instant::now();
            rt.block_on(pub_a.advertise(&layout, &a.directory_endpoint(), Some(row_a.clone())))?;
            assert_eq!(browser.list(&layout)?.first().unwrap().invite, row_a.invite);
            println!(
                "Previous browser sees the re-published world {} ms after restart",
                recovered.elapsed().as_millis()
            );
            let closed = a.directory_endpoint();
            rt.block_on(closed.close());
            rt.block_on(pub_a.advertise(&layout, &closed, None))?;
            assert!(browser.list(&layout)?.is_empty());
            rt.block_on(async {
                task.abort();
                service.close().await;
            });
            Ok(())
        })();
        rt.block_on(async {
            drop((a, b));
        });
        result
    }
}
