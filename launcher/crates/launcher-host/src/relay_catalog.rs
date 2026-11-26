use anyhow::{Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use iroh::RelayUrl;
use reqwest::{Certificate, Client, Response, Url, redirect::Policy};
use serde::Deserialize;
use std::time::{Duration, Instant};
use tokio::task::JoinSet;

const CATALOG_URL: &str =
    "https://raw.githubusercontent.com/DeTTK/SCServer/main/launcher/relays.json";
const EMBEDDED_CATALOG: &str = include_str!("../../../relays.json");
const TEST_CATALOG: &str = include_str!("../../../relays-test.json");

pub(crate) fn test_relay() -> Result<RelayUrl> {
    let mut relays = parse_catalog(TEST_CATALOG.as_bytes())?;
    ensure!(relays.len() == 1, "RELAY_TEST_CATALOG");
    Ok(relays.remove(0))
}
const MAX_CATALOG_BYTES: usize = 8192;
const MAX_RELAYS: usize = 12;

const BSCP_RELAY_CA: &str = include_str!("../assets/tomke-relay-ca.txt");

/// Only the configured BSCPCRAFT relay may use this deployment CA.
pub(crate) fn bscp_relay_ca(origin: &RelayUrl) -> Option<Vec<u8>> {
    (origin.as_str() == "https://tomke.myddns.me:8843/").then(|| {
        STANDARD
            .decode(BSCP_RELAY_CA.trim())
            .expect("embedded public relay CA")
    })
}
fn http_client(origin: Option<&RelayUrl>) -> Result<Client> {
    let mut builder = Client::builder()
        .user_agent("BSCPCRAFT-relay/1")
        .redirect(Policy::none())
        .connect_timeout(Duration::from_secs(2))
        .timeout(Duration::from_secs(3));
    if let Some(ca) = origin.and_then(bscp_relay_ca) {
        builder = builder
            .tls_built_in_root_certs(false)
            .add_root_certificate(Certificate::from_der(&ca)?);
    }
    Ok(builder.build()?)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Catalog {
    schema_version: u32,
    relays: Vec<String>,
}

fn parse_catalog(bytes: &[u8]) -> Result<Vec<RelayUrl>> {
    ensure!(bytes.len() <= MAX_CATALOG_BYTES, "RELAY_CATALOG_SIZE");
    let catalog: Catalog = serde_json::from_slice(bytes)?;
    ensure!(catalog.schema_version == 1, "RELAY_CATALOG_VERSION");
    ensure!(
        (1..=MAX_RELAYS).contains(&catalog.relays.len()),
        "RELAY_CATALOG_COUNT"
    );
    let mut relays = Vec::new();
    for raw in catalog.relays {
        let url = Url::parse(&raw)?;
        ensure!(
            url.scheme() == "https"
                && url.domain().is_some()
                && url.username().is_empty()
                && url.password().is_none()
                && url.path() == "/"
                && url.query().is_none()
                && url.fragment().is_none(),
            "RELAY_CATALOG_URL"
        );
        let relay = RelayUrl::from(url);
        if !relays.contains(&relay) {
            relays.push(relay);
        }
    }
    Ok(relays)
}

async fn bounded_body(mut response: Response, limit: usize) -> Result<Vec<u8>> {
    ensure!(response.status().is_success(), "RELAY_HTTP");
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await? {
        ensure!(chunk.len() <= limit - bytes.len(), "RELAY_HTTP_SIZE");
        bytes.extend_from_slice(&chunk);
    }
    Ok(bytes)
}

async fn probe(client: &Client, relay: &RelayUrl) -> Result<Duration> {
    let health = relay.join("healthz")?;
    // Warm the HTTPS connection so the samples mainly reflect round-trip latency.
    bounded_body(client.get(health.clone()).send().await?, 1024).await?;
    let mut samples = [Duration::ZERO; 3];
    for sample in &mut samples {
        let start = Instant::now();
        bounded_body(client.get(health.clone()).send().await?, 1024).await?;
        *sample = start.elapsed();
    }
    samples.sort_unstable();
    Ok(samples[1])
}

async fn download_catalog(client: &Client, url: &str) -> Result<Vec<RelayUrl>> {
    let response = client
        .get(url)
        .timeout(Duration::from_secs(3))
        .send()
        .await?;
    parse_catalog(&bounded_body(response, MAX_CATALOG_BYTES).await?)
}

async fn rank(client: &Client, relays: Vec<RelayUrl>) -> Vec<RelayUrl> {
    let mut tasks = JoinSet::new();
    for relay in relays {
        let client = if bscp_relay_ca(&relay).is_some() {
            match http_client(Some(&relay)) {
                Ok(client) => client,
                Err(_) => continue,
            }
        } else {
            client.clone()
        };
        tasks.spawn(async move {
            let rtt = tokio::time::timeout(Duration::from_secs(3), probe(&client, &relay))
                .await
                .ok()?
                .ok()?;
            Some((rtt, relay))
        });
    }
    let mut reachable = Vec::new();
    while let Some(result) = tasks.join_next().await {
        if let Ok(Some(measurement)) = result {
            reachable.push(measurement);
        }
    }
    reachable.sort_by(|a, b| (a.0, a.1.as_str()).cmp(&(b.0, b.1.as_str())));
    reachable.into_iter().map(|(_, relay)| relay).collect()
}

pub(crate) async fn ranked_relays() -> Result<Vec<RelayUrl>> {
    let client = http_client(None)?;
    let embedded = parse_catalog(EMBEDDED_CATALOG.as_bytes())?;
    if let Ok(updated) = download_catalog(&client, CATALOG_URL).await
        && updated != embedded
    {
        let ranked = rank(&client, updated).await;
        if !ranked.is_empty() {
            return Ok(ranked);
        }
    }
    Ok(rank(&client, embedded).await)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catalog_is_bounded_and_accepts_only_https_origins() {
        assert!(
            !parse_catalog(EMBEDDED_CATALOG.as_bytes())
                .unwrap()
                .is_empty()
        );
        for url in [
            "http://example.org/",
            "https://user@example.org/",
            "https://127.0.0.1/",
            "https://example.org/path",
            "https://example.org/?query",
        ] {
            let data = serde_json::json!({"schemaVersion": 1, "relays": [url]});
            assert!(parse_catalog(&serde_json::to_vec(&data).unwrap()).is_err());
        }
        let data = serde_json::json!({
            "schemaVersion": 1,
            "relays": ["https://example.org", "https://example.org/"]
        });
        assert_eq!(
            parse_catalog(&serde_json::to_vec(&data).unwrap())
                .unwrap()
                .len(),
            1
        );
        assert!(parse_catalog(&[b' '; MAX_CATALOG_BYTES + 1]).is_err());
    }

    #[test]
    fn bscp_certificate_is_scoped_to_its_exact_origin() {
        for (url, matches) in [
            ("https://tomke.myddns.me:8843/", true),
            ("https://tomke.myddns.me/", false),
            ("https://tomke.myddns.me:8843/healthz", false),
            ("https://tomke.myddns.me:8843/?x=1", false),
            ("https://tomke.myddns.me.example.net:8843/", false),
        ] {
            let url: RelayUrl = url.parse().unwrap();
            assert_eq!(bscp_relay_ca(&url).is_some(), matches);
            if matches {
                http_client(Some(&url)).unwrap();
            }
        }
    }

    #[tokio::test]
    async fn ranks_healthy_relays_by_round_trip_and_skips_unhealthy_ones() {
        use tokio::{
            io::{AsyncReadExt, AsyncWriteExt},
            net::TcpListener,
        };
        let mut fixtures = JoinSet::new();
        let mut urls: Vec<RelayUrl> = Vec::new();
        for (wait, status) in [
            (80, "200 OK"),
            (0, "503 Service Unavailable"),
            (0, "200 OK"),
        ] {
            let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
            urls.push(
                format!("http://{}/", listener.local_addr().unwrap())
                    .parse()
                    .unwrap(),
            );
            fixtures.spawn(async move {
                while let Ok((mut socket, _)) = listener.accept().await {
                    let mut line = Vec::new();
                    while !line.ends_with(b"\r\n\r\n") {
                        line.push(socket.read_u8().await.unwrap());
                    }
                    tokio::time::sleep(Duration::from_millis(wait)).await;
                    let response = format!(
                        "HTTP/1.1 {status}\r\nConnection: close\r\nContent-Length: 2\r\n\r\nok"
                    );
                    socket.write_all(response.as_bytes()).await.unwrap();
                }
            });
        }
        let expected = vec![urls[2].clone(), urls[0].clone()];
        assert_eq!(rank(&Client::new(), urls).await, expected);
    }
}
